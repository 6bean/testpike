
import re
import time
import uuid

import psycopg2

from web_element_ledger_kit import OUTER_ROW, dsn, purge_ledger_fixture
import pytest
from conftest import BASE_URL, assert_no_errors, nav_to
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

FIXTURE_PAGE = "http://localhost:8899/b11_order_page.html"
FIRST_FRAME_MS = 2000
# T1 rides the real LLM chain on the fixture page; the L4 drill measured the
# whole survey at minutes scale. 780s is the in-test wait budget, the outer
# window cap (1500s) stays strictly larger.
SURVEY_WAIT_S = 780
TERMINAL_RE = re.compile(r"^(测绘完成|预算耗尽自动暂停|同页连续无进展提前结束|测绘会话已停止)")


@pytest.fixture()
def throwaway_project():
    """Project row only (T1 needs no seeded elements — the survey creates
    candidates itself; the suffix keeps each run's session tail unique)."""
    suffix = uuid.uuid4().hex[:8]
    name = f"e2e-t661-{suffix}"[:20]
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tp_project (name, code, created_by) VALUES (%s, %s, 1) RETURNING id",
            (name, name),
        )
        row = cur.fetchone()
        assert row is not None, "throwaway project insert returned no id"
        project_id = row[0]
        conn.commit()
    yield {"project_id": project_id, "name": name, "suffix": suffix}
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        # FK order: tp_explore_step -> tp_explore_session -> tp_project; the
        # survey tail frames live in tp_ai_task_event keyed by session id.
        cur.execute(
            "SELECT id FROM tp_explore_session WHERE project_id = %s", (project_id,))
        session_ids = [r[0] for r in cur.fetchall()]
        if session_ids:
            cur.execute("DELETE FROM tp_explore_step WHERE session_id = ANY(%s)",
                        (session_ids,))
            # The explore loop also writes kind='explore' frames under the
            # same job_ref; the teardown recycles every frame this fixture
            # created (R57: paired reclamation at the creation point).
            cur.execute(
                "DELETE FROM tp_ai_task_event WHERE job_ref = ANY(%s)",
                ([str(s) for s in session_ids],))
        cur.execute("DELETE FROM tp_web_element WHERE project_id = %s", (project_id,))
        cur.execute(
            "DELETE FROM tp_explore_session WHERE project_id = %s", (project_id,))
        cur.execute("DELETE FROM tp_project WHERE id = %s", (project_id,))
        conn.commit()
    purge_ledger_fixture(project_id, [])


def open_ledger_with_project(page, project_id):
    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")
    page.goto(f"{BASE_URL}/#/web-elements?projectId={project_id}",
              wait_until="domcontentloaded")
    # Hash-only navigation is same-document: a fresh boot must parse the
    # query so the project segment consumes the fixture id (R30 deep link).
    page.reload(wait_until="domcontentloaded")
    door = page.locator("[data-test='ledger-survey-btn']")
    # 15s: vite lazy-compiled chunks on first hit (posture of the sibling specs).
    expect(door).to_be_enabled(timeout=15000)
    return door


def submit_survey(page, door, url):
    door.click()
    dialog = page.locator("[data-test='ledger-survey-dialog']")
    expect(dialog).to_be_visible()
    dialog.locator("[data-test='ledger-survey-url']").fill(url)
    started = time.monotonic()
    dialog.locator("[data-test='ledger-survey-submit']").click()
    return started


def test_survey_tail_real_chain_kickoff_badge_terminal_and_silent_reread(
        logged_in, collectors, throwaway_project):
    """T1: the live door fills the tail window and closes by itself."""
    page = logged_in
    open_ledger_with_project(page, throwaway_project["project_id"])
    url = f"{FIXTURE_PAGE}?s={throwaway_project['suffix']}"
    started = submit_survey(page, _door(page), url)

    drawer = page.locator("[data-test='ledger-survey-tail']")
    badge = page.locator("[data-test='ledger-survey-live']")
    expect(drawer).to_be_visible(timeout=2000)
    expect(badge).to_be_visible(timeout=2000)

    # First on-screen response (ui.tail.first-frame-ms): the window is opened
    # BEFORE the dispatch POST returns, so within the budget the user must see
    # the honest waiting frame (or, past-budget, the degrade text) — an empty
    # screen is the r4 red (3378ms) this segment exists to kill. Either paint
    # counts; the real kickoff row is asserted by pattern below with its own
    # bound (ack + first poll).
    page.wait_for_selector(".task-tail-empty, .task-tail-row", timeout=FIRST_FRAME_MS)
    first_elapsed_ms = (time.monotonic() - started) * 1000
    assert first_elapsed_ms <= FIRST_FRAME_MS, (
        f"first on-screen response took {first_elapsed_ms:.0f}ms > "
        f"{FIRST_FRAME_MS}ms budget")

    # Collect frames with web-first assertions : rows
    # persist while the run is live and the terminal row stays on screen for
    # the dwell window (ui.tail.terminal-dwell-ms, factory 1500ms) before the
    # auto-collapse — dense rAF polling observes it deterministically, while
    # the previous 2s manual sampler raced the collapse tick and could never
    # see the terminal frame.
    def row_with(pattern):
        return page.locator(".task-tail-row", has_text=pattern).first

    expect(row_with("整页测绘：")).to_be_visible(timeout=5000)
    expect(row_with(re.compile(r"步骤 \d+｜"))).to_be_visible(
        timeout=SURVEY_WAIT_S * 1000)
    expect(row_with("命名")).to_be_visible(timeout=SURVEY_WAIT_S * 1000)
    # The anchored phrase lives at the head of the SUMMARY cell: the row's
    # textContent starts with the timestamp and level badge, so an anchored
    # match must target .task-tail-summary (r5 red: asserting ^ on the row
    # could never match the rendered structure even when the terminal frame
    # landed and collapsed the drawer — live L3 probe session 2440 proved
    # the frame arrives in-stream with this exact prefix).
    expect(page.locator(".task-tail-summary", has_text=TERMINAL_RE).first) \
        .to_be_visible(timeout=SURVEY_WAIT_S * 1000)

    # Auto-collapse + badge gone, with zero user refresh touches.
    expect(drawer).to_have_count(0, timeout=15000)
    expect(badge).to_have_count(0, timeout=15000)

    # Silent re-read: the candidate group for the surveyed page key shows
    # up without any refresh click; the DB readback proves the write.
    expect(page.locator(OUTER_ROW, has_text="b11_order_page").first) \
        .to_be_visible(timeout=15000)
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT count(*) FROM tp_web_element WHERE project_id = %s",
            (throwaway_project["project_id"],))
        landed = cur.fetchone()[0]
    assert landed >= 1, "survey relayed zero candidates into the ledger"

    # R62 banned wording: nowhere on the page may the "manual refresh"
    # instruction sentence appear.
    assert "点击『刷新』" not in page.locator(".page-root").inner_text(), \
        "banned manual-refresh wording on screen"

    assert_no_errors(collectors, "survey tail real chain")
    assert_no_browser_issues(collectors["browser_issues"], "survey tail real chain")


def _door(page):
    """Fresh handle to the survey door (the drawer/badge locators capture
    lazily, the door is clicked exactly once per test)."""
    return page.locator("[data-test='ledger-survey-btn']")


def test_survey_tail_geometry_constant_under_500_frames(logged_in, collectors):
    """T2: 500 replayed frames never grow the drawer, DOM stays windowed."""
    page = logged_in
    fake_session = 880001234
    state = {"release_terminal": False}

    def fake_survey(route):
        if route.request.method != "POST":
            route.fallback()
            return
        route.fulfill(status=200, content_type="application/json", body=(
            '{"code":"0","msg":"ok","data":{"sessionId":%d,'
            '"startUrl":"http://localhost:8899/b11_order_page.html?fake=1"}}'
            % fake_session))

    def fake_events(route):
        raw = route.request.url
        matched = re.search(r"[?&]cursor=(\d+)", raw)
        assert matched is not None, f"process-events request lost its cursor: {raw}"
        cursor = int(matched.group(1))
        if cursor == 0:
            rows = [_ev(1, "整页测绘：http://localhost:8899/b11_order_page.html"
                            f"（会话 {fake_session}，操作人 1）", "thinking")]
        elif cursor == 1:
            rows = [_ev(i + 2, f"步骤 {i + 1}｜页面 /demo/p{i % 9}.html", "step")
                    for i in range(500)]
        elif state["release_terminal"]:
            rows = [_ev(502, "测绘完成：500 步、候选 0 条（夹具注入）", "step")]
        else:
            rows = []
        route.fulfill(status=200, content_type="application/json",
                      body=_events_payload(rows))

    page.route("**/api/v1/web-elements/survey**", fake_survey)
    page.route(
        f"**/api/v1/web-elements/survey/{fake_session}/process-events**",
        fake_events)
    try:
        open_ledger_with_project(page, _fake_project_id(page))
        door = _door(page)
        door.click()
        dialog = page.locator("[data-test='ledger-survey-dialog']")
        expect(dialog).to_be_visible()
        dialog.locator("[data-test='ledger-survey-url']").fill(
            "http://localhost:8899/b11_order_page.html?fake=1")
        dialog.locator("[data-test='ledger-survey-submit']").click()

        drawer = page.locator("[data-test='ledger-survey-tail']")
        expect(drawer).to_be_visible(timeout=5000)
        page.wait_for_selector(".task-tail-row", timeout=10000)
        box_before = drawer.bounding_box()
        assert box_before is not None, "drawer has no bounding box while open"
        height_before = box_before["height"]

        # The 500-frame batch arrives on the next poll; wait for the buffer
        # to stop growing, then read the settled geometry.
        expect(page.locator(".task-tail-row").last) \
            .to_contain_text("步骤 500｜", timeout=20000)
        box_after = drawer.bounding_box()
        assert box_after is not None, "drawer lost its box mid-run"
        height_after = box_after["height"]
        assert height_after == height_before, (
            f"drawer grew with content: {height_before} -> {height_after}")

        dom_rows = page.locator(".task-tail-row").count()
        assert dom_rows <= 400, (
            f"memory tail-cap (element.survey.tail-cap=400) not enforced: "
            f"{dom_rows} rows in DOM")
        row_box = page.locator(".task-tail-row").first.bounding_box()
        assert row_box is not None, "first tail row has no bounding box"
        visible_capacity = height_after / row_box["height"]
        assert dom_rows <= 2 * visible_capacity + 2, (
            f"DOM rows {dom_rows} exceed 2x visible window "
            f"({visible_capacity:.1f}) — windowed rendering regressed")

        # A late terminal frame auto-collapses the drawer (closeOnTerminal).
        state["release_terminal"] = True
        expect(drawer).to_have_count(0, timeout=15000)

        assert_no_errors(collectors, "survey tail geometry window")
        assert_no_browser_issues(collectors["browser_issues"],
                                 "survey tail geometry window")
    finally:
        page.unroute("**/api/v1/web-elements/survey", fake_survey)
        page.unroute(
            f"**/api/v1/web-elements/survey/{fake_session}/process-events**",
            fake_events)


def _fake_project_id(page):
    """The fake doors bypass ownership server-side, but the page needs any
 project segment to enable the door — reuse the top-bar primary project by
 reading a real project id straight from the ledger page context."""
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute("SELECT id FROM tp_project ORDER BY id LIMIT 1")
        row = cur.fetchone()
        assert row is not None, "no project row to slice the fake-door page against"
        return row[0]


def _ev(cursor, summary, level):
    return {
        "cursor": cursor,
        "level": level,
        "summary": summary,
        "detail": None,
        "landedAt": "2026-11-18 17:00:00",
    }


def _events_payload(rows):
    import json
    return json.dumps({"code": "0", "msg": "ok",
                       "data": {"projectId": 1, "events": rows}},
                      ensure_ascii=False)
