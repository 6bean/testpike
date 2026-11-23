"""IT-XQ-08 : stage-ledger face under worst-case scale.

The live incident was a document stuck in status=3 for 38 minutes with no
progress surface at all. landed the per-step stage ledger
(tp_doc_extract_stage) and replayed it in the extracting dialog; this
spectrum pins the worst-scale rendering contract with 320 seeded stage rows
(hundreds of rows is the documented 642-chunk scale, narrowed to the row count
the scroll cap guards):

* only the latest step shows inline (label of the last landed row),
* the full list is collapsed by default and opens via the count-named toggle,
* expansion lands exactly 320 items (hard count, no conditional wrapper),
* the history is capped by a scroll container (clientHeight <= 221 while
 scrollHeight overflows) so hundreds of rows never blow up the dialog,
* the activity clocks keep ticking (idle clock rewrites within 2s),
* reload + reopen replays the same ledger from the database,
* the row entry for an extracting document is clickable ("view progress"),
 which is the display-face gap this segment closes (previously disabled).

Seeding is SQL-only (status=3 + generation token + backfilled rows) so no real
LLM run is burned; fixture data lives in a scratch project and is cleaned in
finally with a zero-residue check (R37: shipped tables are never truncated).
"""

import re
import time

import httpx
import pytest
from conftest import ADMIN_PASSWORD, API_BASE, BASE_URL, api_login, assert_no_errors
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from test_extraction_preview_review_gap import _wait_parsed
from test_f2_extraction import (
    doc_page,
    make_project,
    open_extract_dialog,
    sql,
    upload_doc,
)

STAGE_ROWS = 320                  # worst-scale fixture for the history list
SCROLL_CAP_PX = 221               # CSS max-height 220px + border rounding


def _seed_stage_ledger(doc_id, token):
    """status=3 + fresh generation token + 320 backfilled steps.

    Row 1 is the run start (~11 min ago) and every later step lands 2s closer
    to now, so the total-run clock and the idle clock both tick on second
    granularity while the newest step still reads a plausible recent time.
    ids follow insertion order, which is what the preview endpoint replays.
    """
    sql(f"UPDATE tp_document SET status=3, extract_token='{token}' WHERE id={doc_id}")
    sql("INSERT INTO tp_doc_extract_stage"
        " (document_id, extract_token, stage_code, stage_label,"
        "  created_at, updated_at)"
        " SELECT %d, '%s',"
        "        CASE WHEN g = 1 THEN 'start' ELSE 'group-' || g END,"
        "        CASE WHEN g = 1 THEN '提取启动' ELSE '封组 ' || g || '/%d' END,"
        "        now() - make_interval(secs => 8 + (%d - g) * 2),"
        "        now() - make_interval(secs => 8 + (%d - g) * 2)"
        "   FROM generate_series(1, %d) AS g"
        % (doc_id, token, STAGE_ROWS, STAGE_ROWS, STAGE_ROWS, STAGE_ROWS))
    landed = sql(f"SELECT count(*) FROM tp_doc_extract_stage WHERE document_id={doc_id}"
                 f" AND extract_token='{token}'").strip()
    assert landed == str(STAGE_ROWS), f"stage seed landed {landed} rows, want {STAGE_ROWS}"


def test_extraction_stage_ledger_worst_scale(page, collectors, api_client, uniq):
    pid = None
    doc_id = None
    token = f"SL{uniq[:56]}"
    try:
        pid = make_project(api_client, uniq, "X")
        content = "# 阶段账本最坏规模需求文档 " + uniq + "\n\n" + "\n".join(
            f"## 阶段验证节{k} {uniq}\n\n阶段验证节{k} 关键词{uniq}。\n\n"
            for k in range(1, 9))
        doc_id, name, _sha = upload_doc(api_client, pid, uniq, "stage", content=content)
        _wait_parsed(doc_id)
        _seed_stage_ledger(doc_id, token)

        doc_page(page, pid)
        # The entry for an extracting row is clickable ( gap fix).
        dialog = open_extract_dialog(page, name)

        # --- latest step inline: label of the last landed row only ---
        hint = dialog.locator('[data-test="extract-parsing-hint"]')
        expect(hint).to_be_visible(timeout=15000)
        expect(dialog.locator('[data-test="extract-stage-label"]')).to_have_text(
            f"封组 {STAGE_ROWS}/{STAGE_ROWS}", timeout=10000)
        # exactly one inline row (no full list leaking without the toggle)
        expect(dialog.locator('[data-test="extract-stage-row"]')).to_have_count(1)

        # --- collapsed by default; the toggle names the total count ---
        expect(dialog.locator('[data-test="extract-stage-history"]')).to_have_count(0)
        toggle = dialog.locator('[data-test="extract-stage-toggle"]')
        expect(toggle).to_be_visible()
        assert str(STAGE_ROWS) in toggle.inner_text(), toggle.inner_text()

        # --- idle clock keeps ticking (second granularity, change within 2s) ---
        time_cell = dialog.locator('[data-test="extract-stage-time"]')
        expect(time_cell).to_contain_text("·", timeout=8000)
        first_text = time_cell.inner_text()
        page.wait_for_timeout(2100)  # wall-clock check: the m:ss clock must rewrite
        assert time_cell.inner_text() != first_text, \
            f"idle clock froze between samples: {first_text!r}"
        # total-run clock is present (the seeded start row anchors it)
        expect(dialog.locator('[data-test="extract-stage-total"]')).to_be_visible()

        # --- expand: hard count, never a conditional wrapper ---
        toggle.click()
        history = dialog.locator('[data-test="extract-stage-history"]')
        expect(history).to_be_visible(timeout=5000)
        expect(history.locator('[data-test="extract-stage-item"]')).to_have_count(STAGE_ROWS)

        # --- scroll cap: capped viewport with real overflow inside ---
        metrics = history.evaluate(
            "el => [el.clientHeight, el.scrollHeight]")
        assert metrics[0] <= SCROLL_CAP_PX, \
            f"history viewport grew past the cap: {metrics}"
        assert metrics[1] > metrics[0], \
            f"overflow scrolling missing at {STAGE_ROWS} rows: {metrics}"

        # --- collapse again returns to the single-row face ---
        toggle.click()
        expect(dialog.locator('[data-test="extract-stage-history"]')).to_have_count(0)

        # --- reload + reopen replays the ledger from the database ---
        dialog.locator('[data-test="extract-close-btn"]').click()
        expect(dialog).to_be_hidden(timeout=10000)
        page.goto(f"{BASE_URL}/#/documents?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)
        dialog = open_extract_dialog(page, name)
        expect(dialog.locator('[data-test="extract-stage-label"]')).to_have_text(
            f"封组 {STAGE_ROWS}/{STAGE_ROWS}", timeout=15000)

        assert_no_errors(collectors, "extraction stage ledger worst scale")
        assert_no_browser_issues(collectors["browser_issues"],
                                 "extraction stage ledger worst scale")
    finally:
        if doc_id is not None:
            sql(f"DELETE FROM tp_doc_extract_stage WHERE document_id={doc_id}")
            token_admin = api_login("admin", ADMIN_PASSWORD)
            httpx.delete(f"{API_BASE}/ai/documents/{doc_id}",
                         headers={"Authorization": f"Bearer {token_admin}"}, timeout=30)
        if pid is not None:
            api_client.delete(f"/projects/{pid}/purge?force=true")
        if doc_id is not None:
            assert sql(
                f"SELECT count(*) FROM tp_doc_extract_stage WHERE document_id={doc_id}"
            ).strip() == "0", "stage ledger rows leaked after cleanup"
            assert sql(
                f"SELECT count(*) FROM tp_document WHERE id={doc_id}"
            ).strip() == "0", "document leaked after cleanup"


CANCEL_TEXT = "已手动取消本轮提取任务，可随时重新发起提取"
WORST_SCALE_BLOCKS = 700  # heading line + body line per block => ~1400 chunk rows


def _worst_scale_content(uniq):
    """642-chunk-class document (IT-XQ-08): blank-line separated blocks.

    MarkdownChunkSplitter splits on blank lines and lone newlines, so 700 blocks
    of two lines land on the order of 1400 tp_document_chunk rows — the same
    scale class as incident document 8491 (642 chunks) the stage ledger targets.
    """
    parts = [f"# 全域业务规则文档 {uniq}\n\n"]
    for i in range(1, WORST_SCALE_BLOCKS + 1):
        parts.append(f"## 规则区块{i}\n")
        parts.append(f"第{i}条规则：触发条件成立时必须执行对应校验并返回可读原因。{uniq}-{i}\n\n")
    return "".join(parts)


@pytest.mark.timeout(600)
def test_live_run_stage_echo_and_cancel_at_worst_scale(
        page, collectors, api_client, uniq):
    """IT-XQ-08 live half: real 642-chunk-class run — server stage echo, browser
    face, cancel button state, three-kind zero tolerance (zero mock; the run is
    cancelled right after the start stage lands, so the full 1400-chunk LLM bill
    is never paid while the generation guard rejects the late write-back)."""
    pid = None
    doc_id = None
    try:
        pid = make_project(api_client, uniq, "Y")
        doc_id, name, _sha = upload_doc(api_client, pid, uniq, "live",
                                        content=_worst_scale_content(uniq))
        _wait_parsed(doc_id)
        chunk_count = int(sql(
            f"SELECT count(*) FROM tp_document_chunk WHERE document_id={doc_id}"
        ).strip())
        assert chunk_count >= 642, \
            f"642-chunk-class fixture not landed (tp_document_chunk={chunk_count})"

        # ── start the run on the real chain ──
        doc_page(page, pid)
        dialog = open_extract_dialog(page, name)
        with page.expect_response(
                lambda r: f"/ai/documents/{doc_id}/extract" in r.url and r.status == 200,
                timeout=30000):
            dialog.locator('[data-test="extract-submit-btn"]').click()
        expect(page.locator('.el-message--success').filter(
            has_text="提取任务已提交").first).to_be_visible(timeout=20000)
        expect(dialog.locator('[data-test="extract-parsing-hint"]')).to_be_visible(
            timeout=20000)

        # ── TC-XQ-10 server face: in-flight preview echoes the stage ledger ──
        probe_token = api_login("admin", ADMIN_PASSWORD)
        data = None
        for probe in range(40):  # bounded probe cadence, not an unbounded wait
            r = httpx.get(f"{API_BASE}/ai/documents/{doc_id}/extraction",
                          params={"page": 1, "size": 20},
                          headers={"Authorization": f"Bearer {probe_token}"},
                          timeout=30)
            assert r.status_code == 200, f"preview endpoint not 200: {r.status_code}"
            data = r.json()["data"]
            if data.get("stageLedger"):
                break
            page.wait_for_timeout(5000)
        else:
            raise AssertionError(
                "stageLedger never echoed while running (server-side ledger missing)")
        stages = data["stageLedger"]
        assert len(stages) >= 1, "server stage ledger empty"
        assert all(s.get("code") and s.get("label") and s.get("at") for s in stages), \
            f"stage row missing one of code/label/at: {stages[:3]}"
        assert data.get("lastActivityAt"), "lastActivityAt not echoed with the stage"

        # ── browser face: latest stage + clock rendered (hard asserts, 5d) ──
        expect(dialog.locator('[data-test="extract-stage-row"]')).to_be_visible(
            timeout=30000)
        expect(dialog.locator('[data-test="extract-stage-label"]')).to_be_visible()
        expect(dialog.locator('[data-test="extract-stage-time"]')).not_to_be_empty()

        # ── cancel button state mid-run: one click flips to failure block ──
        cancel_btn = dialog.locator('[data-test="extract-cancel-btn"]')
        expect(cancel_btn).to_be_visible(timeout=10000)
        with page.expect_response(
                lambda r: f"/ai/documents/{doc_id}/extraction-cancel" in r.url
                and r.status == 200,
                timeout=30000):
            cancel_btn.click()
        expect(page.locator('.el-message--success').filter(
            has_text=re.compile("已取消提取")).first).to_be_visible(timeout=20000)
        error_alert = dialog.locator('[data-test="extract-error-alert"]')
        expect(error_alert).to_be_visible(timeout=20000)
        expect(error_alert).to_contain_text(CANCEL_TEXT, timeout=10000)

        # ── DB pin: status=1 + exact cancel text (server truth) ──
        row = sql("SELECT status || '|' || coalesce(extract_error,'') "
                  f"FROM tp_document WHERE id={doc_id}")
        assert row == f"1|{CANCEL_TEXT}", f"cancel write-back DB pin failed: {row}"

        assert_no_errors(collectors, "T566 live worst-scale stage echo + cancel")
        assert_no_browser_issues(collectors["browser_issues"],
                                 "T566 live worst-scale stage echo + cancel")
    finally:
        if doc_id is not None:
            try:
                token = api_login("admin", ADMIN_PASSWORD)
                httpx.delete(f"{API_BASE}/ai/documents/{doc_id}",
                             headers={"Authorization": f"Bearer {token}"}, timeout=30)
            except Exception as e:  # noqa: BLE001
                print(f"  doc cleanup FAIL: {e}")
        if pid is not None:
            try:
                api_client.delete(f"/projects/{pid}/purge?force=true")
            except Exception as e:  # noqa: BLE001
                print(f"  purge FAIL: {e}")
        if pid is not None:
            resid = sql("SELECT (SELECT count(*) FROM tp_document WHERE project_id="
                        + str(pid) + ") || '|' || "
                        "(SELECT count(*) FROM tp_requirement WHERE project_id="
                        + str(pid) + ")").strip()
            assert resid == "0|0", f"zero-residue failed (doc|req): {resid}"
