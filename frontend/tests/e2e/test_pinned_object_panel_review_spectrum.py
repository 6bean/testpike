
import json
import re
import httpx
import pytest
from conftest import (
    BASE_URL,
    TESTER_PASSWORD,
    add_member,
    assert_no_errors,
    core_base_url,
    inject_token,
    inject_user_token,
    land_draft_frame,
    mk_tester,
    purge_project,
    seed_project,
    sql_query,
)
from playwright.sync_api import expect
from test_selection_ask_and_palette_ask import _seed_case

pytestmark = [pytest.mark.timeout(600)]

CASE_COUNT = 32  # worst-scale fixture: >= 30 rows per the display-scale law


def _journal_three_drafts_on(core: str, sid: int, case_id: int, token: str) -> None:
    """Open three assistant turns and journal one CASE_WRITE draft card per
    turn through the frame door — one frame, one card (post-V363 contract),
    so the same target legitimately carries three pending cards."""
    for i in range(3):
        opened = httpx.post(f"{core}/internal/ai-chat/sessions/{sid}/messages",
                            json={"role": "assistant", "content": f"fixture turn {token}-{i}"},
                            timeout=30).json()
        assert opened.get("code") == "0", opened
        msg_id = int(opened["data"])
        meta = {"objectType": "CASE_WRITE", "targetId": case_id,
                "actionJson": json.dumps({"title": f"钉住面板草案{token}{i}"}, ensure_ascii=False)}
        land_draft_frame(core, sid, msg_id, f"未提交改动草案 {token}-{i}", meta)


def test_pinned_panel_badge_counts_undecided_and_one_key_reaches_review(page, collectors, api_client, uniq):
    """TC-ITR-09 core half: 3 pending cards badge exactly 3; one key focuses the case."""
    pid = seed_project(api_client, uniq, "PINPANEL")
    token = uniq[:6].lower()
    core = core_base_url()
    # The workbench queue only shows cases assigned to the signed-in reviewer,
    # and the assign door rejects admin (requireTester guard, 40301) — so the
    # whole journey rides a dedicated tester session (q4/o8 pattern, single source).
    # The list door is data-scope=self for testers (created_by OR assignee), so
    # every seeded row goes to the tester too — the worst-scale panel and the
    # deep-link hit branch then share one honest session.
    tester_id, _nick = mk_tester(api_client, uniq, prefix="pin")
    tester_username = f"pintester_{uniq}"
    add_member(api_client, pid, tester_id)
    titles = [f"钉住面板{token}甲{i:02d}" for i in range(CASE_COUNT)]
    ids = [_seed_case(api_client, pid, t) for t in titles]
    target_id, target_title = ids[0], titles[0]
    for cid in ids:
        rr = api_client.post(f"/cases/{cid}/assign", json={"assigneeId": tester_id})
        assert rr.status_code == 200 and rr.json().get("code") == "0", rr.text[:200]
    sess = httpx.post(f"{core}/internal/ai-chat/sessions",
                      json={"projectId": pid, "userId": tester_id,
                            "title": f"pinned-fixture-{token}", "source": 1},
                      timeout=30).json()
    assert sess.get("code") == "0", sess
    sid_data = sess["data"]
    sid = int(sid_data["id"] if isinstance(sid_data, dict) else sid_data)
    _journal_three_drafts_on(core, sid, target_id, token)
    try:
        inject_user_token(page, tester_username, TESTER_PASSWORD)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=15000)

        # Show every seeded row at once, then check them all through the
        # header select-all (the panel must survive the worst scale).
        page.locator('[data-test="case-pagination"] .el-pagination__sizes .el-select').click()
        page.locator('.el-select-dropdown__item').filter(has_text="50").first.click()
        expect(page.locator('[data-test="case-table"] .el-table__body tr.el-table__row')).to_have_count(CASE_COUNT, timeout=15000)
        # EP renders the header through table.el-table__header (the former
        # `.el-table__head` path never existed on the DOM); select-all parks
        # every row at once for the worst-scale panel.
        page.locator('[data-test="case-table"] .el-table__header .el-checkbox').first.click()
        expect(page.locator('[data-test="case-ask-ai"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="case-ask-ai"]').click()

        expect(page.locator('[data-test="ai-chat-dock"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="chat-chip-case"]')).to_have_count(CASE_COUNT, timeout=10000)
        expect(page.locator('[data-test="pinned-object-panel"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="pinned-object-row"]')).to_have_count(CASE_COUNT, timeout=10000)
        assert_no_errors(collectors, "pinned panel: worst-scale rows parked")

        target_row = page.locator('[data-test="pinned-object-row"]').filter(has_text=target_title)
        expect(target_row.locator('[data-test="pinned-pending-count"]')).to_have_text(f"未提交改动 3", timeout=15000)

        # The count is a read of the draft ledger — the review state machine
        # must not have moved (the case is still awaiting review).
        g = api_client.get(f"/cases/{target_id}")
        assert int(g.json()["data"]["status"]) == 1, "undecided drafts must not touch the review status"

        # One key into the review workbench, deep-link focused on that case.
        target_row.locator('[data-test="pinned-submit-review"]').click()
        # The deep link keeps its params (caseId/projectId must survive the
        # navigation, R24) — match the route head, not an exact URL.
        expect(page).to_have_url(re.compile(r"#/review\?"), timeout=10000)
        expect(page.locator('[data-test="review-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('.quick-active-row')).to_be_visible(timeout=15000)
        assert target_title in page.locator('.quick-active-row').inner_text(), \
            "the deep-link must focus exactly the case the panel spoke of"
        assert_no_errors(collectors, "pinned panel: one key to the focused review row")
    finally:
        purge_project(api_client, pid)
        # Dedicated tester identity is fixture-owned: sweep zero residue (q4 pattern).
        sql_query(f"DELETE FROM tp_user_role WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user_project WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user WHERE id = {tester_id}")


def test_review_deep_link_names_a_missing_case_honestly(page, collectors, api_client, uniq):
    """Deep-link miss branch: an id outside the reviewer segment gets the
 named warning, never a silent no-op."""
    pid = seed_project(api_client, uniq, "PINMISS")
    token = uniq[:6].lower()
    seeded = _seed_case(api_client, pid, f"钉住面板{token}乙01")
    # Never assigned to admin, so it cannot be in the reviewer's queue; the
    # query id below cannot match any row at all.
    missing_id = seeded + 10 ** 9
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/review?projectId={pid}&caseId={missing_id}",
                  wait_until="domcontentloaded")
        expect(page.locator('[data-test="review-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('.el-message--warning')).to_contain_text(
            "该用例不在当前列表中", timeout=15000)
        assert_no_errors(collectors, "review deep link: honest miss notice")
    finally:
        purge_project(api_client, pid)
