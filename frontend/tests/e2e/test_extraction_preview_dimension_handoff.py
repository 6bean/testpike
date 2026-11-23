""" spectrum: clickable dimension chips start a targeted pass.

Seeds the preview state through SQL exactly like the review-gap spectrum
(status=4 + extraction_json whose texts hit none of the suggester keywords, so
the missing set is the full trio in its fixed order). What this spectrum pins:
 1. the suggest row renders all three chips, hard-asserted by count and text
 (no conditional wrapping -- an absent chip must turn the run red);
 2. the chip tooltip and confirm copy name the dimension and keep the human
 gate visible (items still need per-item ticking before storing);
 3. Enter on a focused chip opens the same confirm (keyboard equivalent), and
 dismissing it sends no request -- the row must not drift (status stays 4);
 4. accepting one chip runs the real chain (admin -> core -> MQ -> ai-service
 -> LLM): toast, 3=extracting state, polling surface, and the round lands
 back at status=4 with the table re-rendered. No mocks anywhere on the way.
Fixtures are project-scoped scratch data, purged in finally with a zero-residue
check (R37: shipped tables are never truncated here).
"""

import json
import time

import httpx
from conftest import ADMIN_PASSWORD, API_BASE, BASE_URL, api_login, assert_no_errors
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from test_extraction_preview_review_gap import (
    _build_items,
    _ensure_chunks,
    _live_threshold,
    _wait_parsed,
)
from test_f2_extraction import (
    doc_page,
    make_project,
    open_extract_dialog,
    sql,
    upload_doc,
)

ALL_DIMENSIONS = ["异常流", "边界", "非功能"]  # suggester fixed order (single source)
LLM_ROUND_TIMEOUT_S = 240                    # live L3 measured ~64s for this fixture


def _wait_round_landed(doc_id, timeout_s=LLM_ROUND_TIMEOUT_S):
    """Poll the real column until the targeted round writes the preview back;
    an honest timeout names what to look at (ai-service health / provider)."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        status = sql(f"SELECT status FROM tp_document WHERE id={doc_id}").strip()
        if status == "4":
            return
        assert status != "1", \
            f"targeted round failed on the server: status={status}"
        time.sleep(3)
    raise AssertionError(
        f"document {doc_id} still not back at status=4 after {timeout_s}s: "
        "check ai-service consumption logs before suspecting the UI")


def test_extraction_preview_dimension_handoff(page, collectors, api_client, uniq):
    thr = _live_threshold()
    pid = None
    doc_id = None
    try:
        pid = make_project(api_client, uniq, "H")
        content = "# 维度承接需求文档 " + uniq + "\n\n" + "\n".join(
            f"## 承接验证节{k} {uniq}\n\n承接验证节{k} 关键词{uniq} 用于定向补提承接。\n\n"
            for k in range(1, 15))
        doc_id, name, _sha = upload_doc(api_client, pid, uniq, "dim", content=content)
        _wait_parsed(doc_id)

        chunks = _ensure_chunks(doc_id, uniq, 2)
        items = _build_items(thr, chunks, uniq)
        sql("UPDATE tp_document SET status=4, extract_token="
            f"'DIM{uniq[:40]}', extraction_json='{json.dumps(items, ensure_ascii=False)}'::jsonb"
            f" WHERE id={doc_id}")

        doc_page(page, pid)
        dialog = open_extract_dialog(page, name)

        # --- 1. suggest row: all three chips, exact texts, fixed order (5d hard assert) ---
        row = dialog.locator('[data-test="extract-suggest-row"]')
        expect(row).to_be_visible(timeout=10000)
        expect(row).to_contain_text("尚未覆盖的维度：")
        chips = row.locator('[data-test="extract-suggest-chip"]')
        expect(chips).to_have_count(3, timeout=10000)
        expect(chips).to_have_text(ALL_DIMENSIONS)

        # --- 2. tooltip names the dimension and the per-item human gate ---
        chips.first.hover()
        tip = page.locator('.el-popper').filter(has_text="逐条勾选确认").first
        expect(tip).to_be_visible(timeout=5000)
        assert "异常流" in tip.inner_text(), tip.inner_text()

        # --- 3. keyboard equivalent + dismiss sends nothing (row must not drift) ---
        chips.nth(1).focus()
        page.keyboard.press("Enter")
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=5000)
        assert "边界" in box.inner_text(), box.inner_text()
        box.locator('.el-message-box__btns button').first.click()  # cancel
        expect(box).to_be_hidden(timeout=5000)
        assert sql(f"SELECT status FROM tp_document WHERE id={doc_id}").strip() == "4", \
            "dismissed confirm must not start a round"

        # --- 4. accept one chip runs the real chain to completion ---
        chips.first.click()
        expect(box).to_be_visible(timeout=5000)
        assert "异常流" in box.inner_text(), box.inner_text()
        box.locator('.el-message-box__btns button.el-button--primary').click()
        expect(page.locator('.el-message--success')).to_be_visible(timeout=10000)
        expect(dialog.locator('[data-test="extract-parsing-hint"]')).to_be_visible(timeout=10000)

        _wait_round_landed(doc_id)

        # polling surface lands the preview back: table re-renders with rows
        expect(dialog.locator('[data-test="extract-table"]')).to_be_visible(timeout=15000)
        expect(dialog.locator('[data-test="extract-table"] tbody tr')).not_to_have_count(0)
        # stored items stay unselected-by-default: the human store gate is untouched
        assert sql("SELECT count(*) FROM tp_requirement WHERE project_id="
                   f"(SELECT project_id FROM tp_document WHERE id={doc_id})") == "0", \
            "an extraction round must never auto-store requirements"

        assert_no_errors(collectors, "extraction preview dimension handoff")
        assert_no_browser_issues(collectors["browser_issues"], "extraction preview dimension handoff")
    finally:
        if doc_id is not None:
            token = api_login("admin", ADMIN_PASSWORD)
            httpx.delete(f"{API_BASE}/ai/documents/{doc_id}",
                         headers={"Authorization": f"Bearer {token}"}, timeout=30)
        if pid is not None:
            api_client.delete(f"/projects/{pid}/purge?force=true")
        if doc_id is not None:
            assert sql(f"SELECT count(*) FROM tp_document_chunk WHERE document_id={doc_id}") == "0"
            assert sql(f"SELECT count(*) FROM tp_document WHERE id={doc_id}") == "0"
