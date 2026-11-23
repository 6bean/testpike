"""Requirement-list -> AI batch-generation hand-off spectrum.

Three journeys pinned with real browsers and the real backend (no mocks):
  S1 checked requirement rows ride the ids allow-list deep link into the batch
     page (hard asserts: exact row count, per-row link tags carry the checked
     ids, whole hand-off within 5 clicks from the list page);
  S2 a forged id mixed into ?requirementIds is refused with an honest warning
     naming the missing count, and never faked into a batch row;
  S3 after extraction confirm the one-click offer deep-links exactly the
     freshly stored requirement points (batch row count == SQL ground truth).
Extraction state for S3 is seeded through SQL (status=4 + extraction_json), the
proven fixture form of the review-gap spectrum; requirement titles carry the run
suffix so the dedup door always creates instead of drafting. Fixtures live in a
dedicated scratch project (R37: no shipped-table writes, cleaned in finally).
"""

import json
import re
import time

from conftest import ADMIN_PASSWORD, ADMIN_USERNAME, BASE_URL, api_login, assert_no_errors, inject_user_token
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from test_f2_extraction import doc_page, make_project, open_extract_dialog, sql, upload_doc
from test_extraction_preview_review_gap import _wait_parsed

BATCH_ROWS = '[data-test="ai-gen-batch-rows"] .batch-row'


def _mk_requirement(api, pid, title):
    r = api.post(f"/projects/{pid}/requirements", json={"source": 3, "title": title})
    assert r.status_code == 200, f"requirement create failed: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"requirement create business failure: {body.get('msg')}"
    return body["data"]


def _open_req_page(page, pid):
    """Deep link + reload (same double-step as doc_page: goto alone races SPA boot)."""
    inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/requirements?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="req-page"]')).to_be_visible(timeout=15000)


def _req_rows(page):
    return page.locator('[data-test="req-table"] tbody tr.el-table__row')


def test_checked_requirements_hand_off_to_batch_page(page, collectors, api_client, uniq):
    """S1: check 3 rows -> batch bar -> one click -> batch page loads exactly them."""
    pid = None
    try:
        pid = make_project(api_client, uniq, "HB1")
        created = [_mk_requirement(api_client, pid, f"交接需求{i}_{uniq}") for i in range(1, 5)]
        picked = created[:3]
        _open_req_page(page, pid)
        expect(_req_rows(page)).to_have_count(4, timeout=15000)

        clicks = 0
        for req in picked:
            row = _req_rows(page).filter(has_text=req["title"]).first
            row.locator(".el-checkbox").click()
            clicks += 1
        bar = page.locator('[data-test="req-batch-bar"]')
        expect(bar).to_be_visible(timeout=5000)
        expect(bar).to_contain_text("3")
        bar.locator('[data-test="req-batch-generate"]').click()
        clicks += 1

        page.wait_for_url(re.compile(r"#/ai/generate\?.*requirementIds="), timeout=15000)
        url_ids = set(page.url.split("requirementIds=")[1].split("&")[0].split(","))
        assert url_ids == {str(r["id"]) for r in picked}, f"deep link ids drifted: {url_ids}"
        # Hard asserts (no conditional rendering): exactly 3 rows, link tag names each id.
        expect(page.locator(BATCH_ROWS)).to_have_count(3, timeout=15000)
        tag_ids = set()
        for idx in range(3):
            tag = page.locator(f'[data-test="ai-gen-batch-link-{idx}"]')
            expect(tag).to_be_visible(timeout=5000)
            text = tag.inner_text()
            assert text.strip(), f"batch link tag {idx} is empty"
            m = re.search(r"#(\d+)", text)
            assert m, f"batch link tag {idx} lost its requirement anchor: {text!r}"
            tag_ids.add(m.group(1))
        assert tag_ids == {str(r["id"]) for r in picked}, f"batch rows lost their anchors: {tag_ids}"
        assert clicks <= 5, f"hand-off took {clicks} clicks, budget is 5"
        assert_no_errors(collectors, "requirements hand-off S1")
        assert_no_browser_issues(collectors.get("browser_issues"), "requirements hand-off S1")
    finally:
        if pid:
            _sweep_project(pid)


def _sweep_project(pid: int) -> None:
    """Drop the temp project subtree in FK order (single source, R40).

    Live-schema FK scan (2026-11-07): tp_user_project/tp_requirement/tp_test_case
    reference tp_project, tp_case_requirement/tp_case_step/tp_script/
    tp_case_defect/tp_review_log reference tp_test_case, and a submitted batch
    leaves tp_ai_task/tp_ai_batch rows (project-keyed, no FK) that orphan the
    linkage gate once requirements are gone. Every child is cleared before its
    parent, otherwise PostgreSQL refuses the delete (tp_user_project_project_id_fkey
    was the first visible refusal).
    """
    cases = f"(SELECT id FROM tp_test_case WHERE project_id={pid})"
    reqs = f"(SELECT id FROM tp_requirement WHERE project_id={pid})"
    sql(f"DELETE FROM tp_case_requirement WHERE case_id IN {cases} OR requirement_id IN {reqs}")
    sql(f"DELETE FROM tp_case_step WHERE case_id IN {cases}")
    sql(f"DELETE FROM tp_script WHERE case_id IN {cases}")
    sql(f"DELETE FROM tp_case_defect WHERE case_id IN {cases}")
    sql(f"DELETE FROM tp_review_log WHERE case_id IN {cases}")
    sql(f"DELETE FROM tp_requirement_review_log WHERE requirement_id IN {reqs}")
    sql(f"DELETE FROM tp_test_case WHERE project_id={pid}")
    sql(f"DELETE FROM tp_requirement WHERE project_id={pid}")
    sql(f"DELETE FROM tp_ai_task WHERE project_id={pid}")
    sql(f"DELETE FROM tp_ai_batch WHERE project_id={pid}")
    sql(f"DELETE FROM tp_user_project WHERE project_id={pid}")
    sql(f"DELETE FROM tp_project WHERE id={pid}")


def test_forged_id_refused_with_honest_count(page, collectors, api_client, uniq):
    """S2: one real id + one forged id -> warning names 1 missing, batch has 1 row."""
    pid = None
    try:
        pid = make_project(api_client, uniq, "HB2")
        real = _mk_requirement(api_client, pid, f"真实需求_{uniq}")
        max_id = int(sql("SELECT COALESCE(MAX(id),0) FROM tp_requirement").strip() or 0)
        forged = max_id + 424242
        inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/ai/generate?projectId={pid}"
                  f"&requirementIds={real['id']},{forged}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")

        msg = page.wait_for_selector('.el-message:has-text("条需求点不在当前项目")', timeout=20000)
        assert msg.inner_text().startswith("1 条"), f"warning must name exactly 1 missing: {msg.inner_text()}"
        expect(page.locator(BATCH_ROWS)).to_have_count(1, timeout=15000)
        expect(page.locator('[data-test="ai-gen-batch-link-0"]')).to_be_visible(timeout=5000)
        assert str(real["id"]) in page.locator('[data-test="ai-gen-batch-link-0"]').inner_text()
        assert_no_errors(collectors, "requirements hand-off S2")
        assert_no_browser_issues(collectors.get("browser_issues"), "requirements hand-off S2")
    finally:
        if pid:
            _sweep_project(pid)


def test_extract_confirm_one_click_into_batch(page, collectors, api_client, uniq):
    """S3: confirm 3 of 4 seeded preview rows -> offer -> batch rows == SQL ground truth."""
    pid = None
    try:
        pid = make_project(api_client, uniq, "HB3")
        content = "# 交接验证需求文档 " + uniq + "\n\n" + "\n".join(
            f"## 交接验证节{k} {uniq}\n\n交接验证节{k} 关键词{uniq} 用于一键交接核验。\n\n"
            for k in range(1, 15))
        doc_id, name, _sha = upload_doc(api_client, pid, uniq, "hb", content=content)
        _wait_parsed(doc_id)

        items = [{
            "id": i, "title": f"确认需求{i}_{uniq}",
            "acceptanceCriteria": f"验收准则{i}_{uniq}", "chapter": "交接验证节",
            "confidence": 0.95, "selected": i <= 3, "requirementId": None,
        } for i in range(1, 5)]
        sql("UPDATE tp_document SET status=4, extract_token="
            f"'HB{uniq[:40]}', extraction_json='{json.dumps(items, ensure_ascii=False)}'::jsonb"
            f" WHERE id={doc_id}")

        doc_page(page, pid)
        dialog = open_extract_dialog(page, name)
        expect(dialog.locator('[data-test="extract-table"]')).to_be_visible(timeout=15000)
        rows = dialog.locator('[data-test="extract-table"] tbody tr.el-table__row')
        expect(rows).to_have_count(4, timeout=10000)
        for i in range(1, 4):
            rows.nth(i - 1).locator(".el-checkbox").click()
        confirm = dialog.locator('[data-test="extract-confirm-btn"]')
        expect(confirm).to_be_enabled(timeout=5000)
        confirm.click()

        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=15000)
        expect(box).to_contain_text("本次新入库 3 条", timeout=5000)
        page.locator('.el-message-box__btns button:has-text("确定")').click()

        page.wait_for_url(re.compile(r"#/ai/generate\?.*requirementIds="), timeout=20000)
        landed = int(sql(f"SELECT count(*) FROM tp_requirement WHERE project_id={pid}").strip())
        assert landed == 3, f"SQL ground truth broken: expected 3 landed, got {landed}"
        expect(page.locator(BATCH_ROWS)).to_have_count(landed, timeout=15000)
        # The refused 4th row must not exist in the batch (checked against SQL, not guessed).
        url_ids = set(page.url.split("requirementIds=")[1].split("&")[0].split(","))
        db_ids = set(sql(f"SELECT id FROM tp_requirement WHERE project_id={pid}").split())
        assert url_ids == db_ids, f"deep link ids != stored rows: url={url_ids} db={db_ids}"
        assert_no_errors(collectors, "requirements hand-off S3")
        assert_no_browser_issues(collectors.get("browser_issues"), "requirements hand-off S3")
    finally:
        if pid:
            sql("DELETE FROM tp_document_chunk WHERE document_id IN"
                f" (SELECT id FROM tp_document WHERE project_id={pid})")
            sql(f"DELETE FROM tp_document WHERE project_id={pid}")
            _sweep_project(pid)
