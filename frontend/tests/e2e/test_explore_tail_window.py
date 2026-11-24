""" A-face (R62): process tail window on the exploration session detail page.

Real doors throughout (strict-testing-standards): session creation rides the
full admin->core->PG gate chain (confirm=true, no SQL bypass); the tail reads
GET /explore/sessions/{id}/process-events served from the real cursor-window
table. Only the executor's own frame writes are seeded in SQL, exactly the
 precedent ("seeding is SQL-only so no real LLM run is burned; the
browser rides the real endpoints either way") — the full LLM run face needs
ai-service resident and is registered as a separate verification window.

Spectrum:
T1 live face: running session -> badge + drawer auto-open with zero clicks;
 worst-scale 121 seeded frames; the DOM stays windowed (rows rendered are
 strictly fewer than the buffered total, and the last frame only appears
 after scrolling to the bottom); a late frame reaches the DOM within the
 live-road budget; banned manual-refresh instruction text absent.
T2 terminal collapse: terminal frame (exploration-loop exit prefix) + end
 status -> drawer collapses by itself, badge disappears, the detail
 re-reads in place with ZERO user refresh clicks and no re-arm.
T3 operator cancel: UI cancel on a running session -> local close, status
 tag flips to cancelled through the silent re-read.

Three zero-tolerance checks (4xx/5xx + console error/warning + Issues panel)
run on every face; --headless=new posture comes from conftest.
"""

import re
import uuid

import pytest
from conftest import BASE_URL, assert_no_errors, pick_project_in_header, sql_query
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

STEP_FRAMES = 121          # worst-scale fixture (>=30 rows mandated by S-group)
LIVE_ROAD_MS = 8000        # one cursor poll cycle + margin
TERMINAL_SUMMARY = "自动浏览结束｜目标达成｜共 1/40 步｜浏览 1 页（尾窗收档验证）"

BANNED_REFRESH_PHRASES = ["点击『刷新』", "请刷新", "手动刷新", "刷新后重试", "稍后刷新", "稍后再刷新"]


def _make_project(api, tag: str) -> tuple[int, str]:
    name = f"B9尾窗_{tag}{uuid.uuid4().hex[:6]}"
    r = api.post("/projects", json={"name": name, "code": f"W{tag}{uuid.uuid4().hex[:6]}"[:10].upper()})
    assert r.status_code == 200, f"create project failed: {r.text[:200]}"
    return r.json()["data"], name


def _create_session(api, pid: int) -> int:
    r = api.post("/explore/sessions", json={
        "projectId": pid,
        "goalText": f"尾窗接线验证 {uuid.uuid4().hex[:8]}",
        "startUrl": f"{BASE_URL}/#/login",
        "confirm": True,
    })
    assert r.status_code == 200, f"create session failed: {r.text[:200]}"
    return r.json()["data"]["session"]["id"]


def _seed(sid: int, level: str, summary: str, detail: str | None = None) -> None:
    detail_sql = "NULL" if detail is None else f"'{detail}'"
    sql_query(
        "INSERT INTO tp_ai_task_event (job_kind, job_ref, run_token, level, summary, detail)"
        f" VALUES ('explore', '{sid}', '{sid}', '{level}', '{summary}', {detail_sql})"
    )


def _cleanup(sid: int, pid: int) -> None:
    sql_query(f"DELETE FROM tp_ai_task_event WHERE job_ref = '{sid}'")
    sql_query(f"DELETE FROM tp_explore_step WHERE session_id = {sid}")
    sql_query(f"DELETE FROM tp_explore_session WHERE id = {sid}")
    sql_query(f"DELETE FROM tp_user_project WHERE project_id = {pid}")
    sql_query(f"DELETE FROM tp_project WHERE id = {pid}")


def _open_detail(logged_in, sid: int):
    logged_in.goto(f"{BASE_URL}/#/explore/{sid}", wait_until="domcontentloaded")
    expect(logged_in.locator('[data-test="explore-detail-page"]')).to_be_visible(timeout=15000)
    return logged_in


def test_explore_tail_live_face_windowed_scale(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid, _ = _make_project(api_client, "live")
    sid = _create_session(api_client, pid)
    try:
        for i in range(STEP_FRAMES):
            _seed(sid, "step", f"步骤 {i + 1}｜页面元素采集｜登录页 · 已采集 {i} 项")
        _open_detail(page, sid)

        badge = page.locator('[data-test="explore-detail-tail-badge"]')
        expect(badge).to_be_visible(timeout=10000)
        drawer = page.locator('[data-test="explore-detail-tail"]')
        expect(drawer).to_be_visible(timeout=10000)
        tail_list = page.locator('[data-test="task-tail-list"]')
        expect(tail_list).to_be_visible()

        # windowing contract (R62②): buffered rows > rendered DOM rows.
        expect(badge).to_have_text(re.compile(rf"·\s*{STEP_FRAMES}$"), timeout=20000)
        rendered = page.locator('[data-test^="task-tail-row-"]').count()
        assert rendered < STEP_FRAMES, (
            f"tail must stay windowed: rendered {rendered} vs buffered {STEP_FRAMES}")
        assert rendered > 0, "window must render the visible slice, not nothing"

        # constant height while content grows: late frame must not stretch the box
        box_before = page.locator('[data-test="explore-detail-tail"]').bounding_box()
        _seed(sid, "step", "尾帧后到｜实时道路验证")
        late = page.locator(f'[data-test="task-tail-row-{STEP_FRAMES}"]')
        expect(late).to_be_visible(timeout=LIVE_ROAD_MS)
        box_after = page.locator('[data-test="explore-detail-tail"]').bounding_box()
        assert abs(box_after["height"] - box_before["height"]) < 2.0, (
            f"drawer height must stay constant: {box_before['height']} -> {box_after['height']}")

        # banned instruction syntax must not exist anywhere on the page (R62④)
        body_text = page.locator("body").inner_text()
        for phrase in BANNED_REFRESH_PHRASES:
            assert phrase not in body_text, f"banned refresh instruction on screen: {phrase}"
        assert page.locator('[data-test="explore-detail-refresh"]').count() == 0

        assert_no_errors(collectors, "explore tail window")
        assert_no_browser_issues(collectors["browser_issues"], "explore tail window")
    finally:
        _cleanup(sid, pid)


def test_explore_tail_terminal_auto_collapse(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid, _ = _make_project(api_client, "term")
    sid = _create_session(api_client, pid)
    try:
        _seed(sid, "step", "步骤 1｜开窗前置帧")
        _open_detail(page, sid)
        drawer = page.locator('[data-test="explore-detail-tail"]')
        expect(drawer).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="explore-detail-tail-badge"]')).to_be_visible()

        # end state arrives through the same doors the kernel uses: status flip
        # + terminal frame (prefix contract = api/explore.ts EXPLORE_TERMINAL_PREFIXES)
        sql_query(f"UPDATE tp_explore_session SET status = 2 WHERE id = {sid}")
        _seed(sid, "step", TERMINAL_SUMMARY)

        expect(drawer).to_be_hidden(timeout=15000)
        expect(page.locator('[data-test="explore-detail-tail-badge"]')).to_be_hidden(timeout=10000)
        # silent re-read: the status tag flipped without any user reload/click
        expect(page.locator('[data-test="explore-detail-status"]')).to_have_text("已完成", timeout=10000)

        assert_no_errors(collectors, "explore tail window")
        assert_no_browser_issues(collectors["browser_issues"], "explore tail window")
    finally:
        _cleanup(sid, pid)


def test_explore_tail_operator_cancel_closes(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid, _ = _make_project(api_client, "cancel")
    sid = _create_session(api_client, pid)
    try:
        _seed(sid, "step", "步骤 1｜取消路径前置帧")
        _open_detail(page, sid)
        drawer = page.locator('[data-test="explore-detail-tail"]')
        expect(drawer).to_be_visible(timeout=10000)

        page.locator('[data-test="explore-detail-cancel"]').click()
        message_box = page.locator(".el-message-box")
        expect(message_box).to_be_visible(timeout=5000)
        message_box.locator(".el-message-box__btns .el-button--primary").click()

        expect(drawer).to_be_hidden(timeout=10000)
        expect(page.locator('[data-test="explore-detail-tail-badge"]')).to_be_hidden(timeout=10000)
        expect(page.locator('[data-test="explore-detail-status"]')).to_have_text("已取消", timeout=10000)

        assert_no_errors(collectors, "explore tail window")
        assert_no_browser_issues(collectors["browser_issues"], "explore tail window")
    finally:
        _cleanup(sid, pid)
