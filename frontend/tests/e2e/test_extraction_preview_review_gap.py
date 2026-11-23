""" spectrum: low-confidence review chips + coverage-gap banner.

Seeds the extracted state through SQL (status=4 + extract_token + extraction_json
+ pending ledger) instead of burning a real LLM run: the live extraction chain is
already covered by test_f2_extraction.py; this spectrum pins the preview echo --
the server-echoed threshold driving the chip verdict, the ledger-driven gap banner
with >8 id truncation, reload persistence and the AP-4 chunk jump-back reuse.
Fixtures are project-scoped scratch data, cleaned in finally with a zero-residue
check (R37: no TRUNCATE of shipped tables).
"""

import json
import re
import time

import httpx
from conftest import ADMIN_PASSWORD, API_BASE, BASE_URL, api_login, assert_no_errors
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from test_f2_extraction import (
    doc_page,
    make_project,
    open_extract_dialog,
    sql,
    upload_doc,
)

LEDGER_PENDING_STEPS = 9          # >8 exercises the banner id truncation
TOTAL_ITEMS = 30                  # worst-scale-ish fixture (>=30 rows, 2 pages)
PAGE1_LOW = 6                     # rows under the echoed threshold on page 1
PAGE1_NULL = 2                    # rows with null confidence on page 1
PAGE2_LOW = 2


def _live_threshold():
    """Read the hot-updatable shipped/live value the same way core does."""
    raw = sql("SELECT config_value FROM tp_sys_config"
              " WHERE config_key='ai.doc.review-flag-threshold'")
    try:
        val = float(raw.strip()) if raw.strip() else 0.7
    except ValueError:
        val = 0.7
    if not (0.05 < val < 0.95):
        raise AssertionError(
            f"live review-flag threshold {val} outside (0.05,0.95): fixture offsets"
            " would not stay strictly below/above it -- adjust the spectrum, do not tune the key")
    return val


def _ensure_chunks(doc_id, uniq, want):
    """Real chunks come from parsing; pad the ledger anchor ids with SQL chunks so
    exactly `want` distinct ids exist (chunk rows are fixture data, FK cascaded)."""
    have = [int(x) for x in sql(
        f"SELECT id FROM tp_document_chunk WHERE document_id={doc_id} ORDER BY id").splitlines() if x]
    max_seq = sql(f"SELECT COALESCE(MAX(seq),0) FROM tp_document_chunk WHERE document_id={doc_id}")
    seq = int(max_seq)
    while len(have) < want:
        seq += 1
        sql("INSERT INTO tp_document_chunk (document_id, chapter, seq, content, token_est)"
            f" VALUES ({doc_id}, '补提验证节', {seq},"
            f" '补提验证节{seq} 关键词{uniq} 用于回跳锚定校验。', 12)")
        have.append(int(sql(
            f"SELECT id FROM tp_document_chunk WHERE document_id={doc_id} AND seq={seq}")))
    return have


def _wait_parsed(doc_id, timeout_s=90):
    """Parsing must land before seeding, otherwise the async parse pass would
    rewrite status/chunks over our fixture (poll the column, honest timeout)."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        status = sql(f"SELECT status FROM tp_document WHERE id={doc_id}")
        if status.strip() in ("1", "2"):
            assert status.strip() == "1", f"document failed to parse: status={status}"
            return
        time.sleep(2)
    raise AssertionError(f"document {doc_id} still unparsed after {timeout_s}s")


def _build_items(thr, chunk_ids, uniq):
    """30 preview rows: fixed counts strictly below / above the live threshold,
    null-confidence rows rendering '-', two anchored rows and one unanchored row."""
    items = []

    def add(idx, conf, anchored=None):
        items.append({
            "id": idx,
            "title": f"提现限额补提条目{idx}-{uniq}",
            "acceptanceCriteria": f"验收标准{idx}",
            "chapter": f"第{idx % 5 + 1}章",
            "confidence": conf,
            "selected": False,
            "requirementId": None,
            "evidence": None,
            "chunkId": anchored,
        })

    # page 1: ids 1..6 under threshold (rows 1-2 carry the jump anchors),
    # ids 7..18 at/above, ids 19..20 null -> '-' with no chip.
    for i in range(PAGE1_LOW):
        add(i + 1, round(max(thr - 0.03 * (i + 1), 0.01), 3),
            chunk_ids[i] if i < 2 else None)
    for i in range(12):
        add(7 + i, round(min(thr + 0.03 * (i + 1), 0.99), 3))
    for i in range(PAGE1_NULL):
        add(19 + i, None)
    # page 2: ids 21..22 under threshold, 23..30 above.
    for i in range(PAGE2_LOW):
        add(21 + i, round(max(thr - 0.10 - 0.03 * i, 0.01), 3))
    for i in range(8):
        add(23 + i, round(min(thr + 0.06 * (i + 1), 0.99), 3))
    return items


def test_extraction_preview_review_gap(page, collectors, api_client, uniq):
    thr = _live_threshold()
    thr_percent = round(thr * 100)
    pid = None
    doc_id = None
    try:
        pid = make_project(api_client, uniq, "G")
        content = "# 补提验证需求文档 " + uniq + "\n\n" + "\n".join(
            f"## 补提验证节{k} {uniq}\n\n补提验证节{k} 关键词{uniq} 用于回跳锚定校验。\n\n"
            for k in range(1, 15))
        doc_id, name, _sha = upload_doc(api_client, pid, uniq, "gap", content=content)
        _wait_parsed(doc_id)

        chunks = _ensure_chunks(doc_id, uniq, LEDGER_PENDING_STEPS)
        items = _build_items(thr, chunks, uniq)
        sql("UPDATE tp_document SET status=4, extract_token="
            f"'GAP{uniq[:40]}', extraction_json='{json.dumps(items, ensure_ascii=False)}'::jsonb"
            f" WHERE id={doc_id}")
        steps = [{"title": f"块#{cid}", "state": "pending"} for cid in chunks[:LEDGER_PENDING_STEPS]]
        sql("INSERT INTO tp_doc_extract_ledger (document_id, extract_token, steps_json)"
            f" VALUES ({doc_id}, 'GAP{uniq[:40]}',"
            f" '{json.dumps(steps, ensure_ascii=False)}')")

        doc_page(page, pid)
        dialog = open_extract_dialog(page, name)

        # --- gap banner: count text + exactly 8 visible ids with the >8 truncation ---
        banner = dialog.locator('[data-test="extract-gap-banner"]')
        expect(banner).to_be_visible(timeout=10000)
        banner_text = banner.inner_text()
        assert f"{LEDGER_PENDING_STEPS} 个文档分块" in banner_text, banner_text
        ids_part = banner_text.split("分块 #")[1].split("）")[0]
        visible_ids = [int(x) for x in re.findall(r"\d+", ids_part)]
        assert len(visible_ids) == 8, f"expected 8 visible ids, got {visible_ids}: {ids_part}"
        assert ids_part.rstrip().endswith("…"), f">8 gaps must truncate with …: {ids_part}"
        assert set(visible_ids) <= set(chunks[:LEDGER_PENDING_STEPS]), ids_part

        # --- page 1 chips: count equals under-threshold rows, driven by the echo ---
        table = dialog.locator('[data-test="extract-table"]')
        expect(table.locator('tbody tr')).to_have_count(20, timeout=10000)
        expect(dialog.locator('[data-test="extract-review-chip"]')).to_have_count(PAGE1_LOW)
        expect(dialog.locator('[data-test="extract-confidence"]')).to_have_count(20 - PAGE1_NULL)

        # --- tooltip names the percent and the settings key (R27: operational info) ---
        chip = dialog.locator('[data-test="extract-review-chip"]').first
        chip.hover()
        tip = page.locator('.el-popper').filter(has_text="建议回看来源原文").first
        expect(tip).to_be_visible(timeout=5000)
        tip_text = tip.inner_text()
        assert f"{thr_percent}%" in tip_text, tip_text
        assert "ai.doc.review-flag-threshold" in tip_text, tip_text

        # --- chunk jump (AP-4 reuse) + honest disabled state for the null anchor ---
        first_row = table.locator('tbody tr').first
        first_row.locator('[data-test="extract-source-btn"]').click()
        viewer = page.locator('[data-test="extract-source-dialog"]')
        expect(viewer).to_be_visible(timeout=10000)
        expect(viewer.locator('[data-test="extract-source-content"]')).to_contain_text(uniq, timeout=10000)
        viewer.locator('[data-test="extract-source-close-btn"]').click()
        expect(viewer).to_be_hidden(timeout=10000)
        row3 = table.locator('tbody tr').nth(2)   # id=3 -> chunkId null
        expect(row3.locator('[data-test="extract-source-btn-disabled"]')).to_be_visible()

        # --- pagination to page 2: 10 rows, 2 under-threshold chips ---
        dialog.locator('[data-test="extract-pagination"] button.btn-next').click()
        expect(table.locator('tbody tr')).to_have_count(10, timeout=10000)
        expect(dialog.locator('[data-test="extract-review-chip"]')).to_have_count(PAGE2_LOW)

        # --- close, reload, reopen: seeded state persists from the database ---
        dialog.locator('[data-test="extract-close-btn"]').click()
        expect(page.locator('[data-test="extract-dialog"]')).to_be_hidden(timeout=10000)
        page.goto(f"{BASE_URL}/#/documents?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)
        dialog = open_extract_dialog(page, name)
        expect(dialog.locator('[data-test="extract-gap-banner"]')).to_be_visible(timeout=10000)
        expect(dialog.locator('[data-test="extract-review-chip"]')).to_have_count(PAGE1_LOW)

        assert_no_errors(collectors, "extraction preview review-gap")
        assert_no_browser_issues(collectors["browser_issues"], "extraction preview review-gap")
    finally:
        if doc_id is not None:
            sql(f"DELETE FROM tp_doc_extract_ledger WHERE document_id={doc_id}")
            token = api_login("admin", ADMIN_PASSWORD)
            httpx.delete(f"{API_BASE}/ai/documents/{doc_id}",
                         headers={"Authorization": f"Bearer {token}"}, timeout=30)
        if pid is not None:
            api_client.delete(f"/projects/{pid}/purge?force=true")
        if doc_id is not None:
            assert sql(f"SELECT count(*) FROM tp_doc_extract_ledger WHERE document_id={doc_id}") == "0"
            assert sql(f"SELECT count(*) FROM tp_document_chunk WHERE document_id={doc_id}") == "0"
