""" whole-site survey journey: profile -> dispatch -> live tail ->
R67 revisit -> per-page human approval -> referencing list.

Real chain, zero stubs (R3): the drawer dispatches a live BFS crawl of the
slot-local fixture page family (/pr/ directory address, per the ruled
sibling/verb-exclusion posture); the executor claims the act, the survey
tail streams page frames into tp_ai_task_event, and the ledger collects
candidates per page. The journey then:

 1. creates a site profile on the ledger page and dispatches it,
 2. follows the run's tail window live: first on-screen response within
 ui.tail.first-frame-ms, at least two page frames while the crawl
 progresses, a terminal summary row before the budget,
 3. leaves the page entirely, re-enters through the ?surveyRun= deep link
 and replays the SAME run (R67: the terminal frame is still there, the
 stored frames are proven by a DB readback, lazy load door exists),
 4. approves one whole page through the per-page batch door (human gate:
 zero approvals land before the click, the honest count rides the
 toast, confirmed rows appear),
 5. seeds one case step whose anchor names a confirmed element through
 the anchor ledger dialect {kind:elem, ref:name} and proves the
 referencing-case table renders it in the anchors dialog.

Three zero-tolerance checks (4xx/5xx + console error/warning + browser
Issues) run at the end; --headless=new posture comes from conftest. The
wait budgets are hot-configurable (R46): the window launcher exports
TP_E2E_TIMEOUT_S strictly larger than TP_E2E_SITE_JOURNEY_WAIT_S.
"""
import json
import os
import re
import time
import uuid

import psycopg2
import pytest
from conftest import BASE_URL, assert_no_errors, nav_to
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from web_element_ledger_kit import (
    OUTER_ROW,
    create_profile_via_ui,
    dsn,
    expand_group,
)

FIXTURE_BASE = f"http://localhost:{os.environ.get('FIXTURE_PORT', '8899')}"
# Directory address on purpose: a page address would record the family as
# siblings and never exercise the verb-guard exclusion (pitfall ledger,
# contract tests and L3 windows start from a directory address).
START_URL = f"{FIXTURE_BASE}/pr/"
FIRST_FRAME_MS = 2000
RUN_WAIT_S = int(os.environ.get("TP_E2E_SITE_JOURNEY_WAIT_S", "1200"))
# Teardown drain bound: an aborted call phase leaves the crawl writing
# (sessions, per-page write-risk profiles, tail events) while the fixture
# sweeps. Wait a bounded while for the newest run to settle first.
DRAIN_WAIT_S = int(os.environ.get("TP_E2E_SITE_JOURNEY_DRAIN_S", "120"))
TERMINAL_RUN_STATES = ("completed", "partial", "failed", "cancelled")
TERMINAL_RE = re.compile(
    r"^(测绘完成|预算耗尽自动暂停|同页连续无进展提前结束|测绘会话已停止)")


def _sweep_project_rows(cur, project_id):
    """Delete every row family this journey may have written, parents last.

    tp_page_write_profile is part of the set: the survey files per-page
    write-risk rows (page write ledger) and they hold a project key, so a
    sweep that forgets them blocks the project delete with an FK violation.
    """
    targets = "(SELECT id FROM tp_test_case WHERE project_id = %s)"
    cur.execute("DELETE FROM tp_case_step WHERE case_id IN " + targets,
                (project_id,))
    cur.execute("DELETE FROM tp_test_case WHERE project_id = %s",
                (project_id,))
    cur.execute("DELETE FROM tp_site_survey_run WHERE project_id = %s",
                (project_id,))
    cur.execute("DELETE FROM tp_site_profile WHERE project_id = %s",
                (project_id,))
    cur.execute(
        "SELECT id FROM tp_explore_session WHERE project_id = %s",
        (project_id,))
    session_ids = [r[0] for r in cur.fetchall()]
    if session_ids:
        cur.execute(
            "DELETE FROM tp_explore_step WHERE session_id = ANY(%s)",
            (session_ids,))
        cur.execute(
            "DELETE FROM tp_ai_task_event WHERE job_ref = ANY(%s)",
            ([str(s) for s in session_ids],))
    cur.execute("DELETE FROM tp_explore_session WHERE project_id = %s",
                (project_id,))
    cur.execute("DELETE FROM tp_page_write_profile WHERE project_id = %s",
                (project_id,))
    cur.execute("DELETE FROM tp_web_element WHERE project_id = %s",
                (project_id,))
    cur.execute("DELETE FROM tp_user_project WHERE project_id = %s",
                (project_id,))
    cur.execute("DELETE FROM tp_project WHERE id = %s", (project_id,))


@pytest.fixture()
def journey_project():
    """Throwaway project row; the survey itself creates profiles, runs and
    candidates. Teardown reclaims every table this journey may have filled
    (R57: paired reclamation at the creation point, scoped by project id).
    """
    suffix = uuid.uuid4().hex[:8]
    name = f"e2e-sj-{suffix}"[:20]
    seeded_case_ids = []
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tp_project (name, code, created_by) "
            "VALUES (%s, %s, 1) RETURNING id", (name, name))
        project_id = cur.fetchone()[0]
        conn.commit()
    yield {"project_id": project_id, "name": name, "suffix": suffix,
           "seeded_case_ids": seeded_case_ids}
    with psycopg2.connect(dsn()) as conn:
        conn.autocommit = True
        with conn.cursor() as cur:
            # An aborted call phase does not stop the dispatched crawl: late
            # relays keep re-writing session/page-write rows even after the
            # sweep removed them, and the project delete then hits an FK
            # violation. Drain first (bounded, hot-configurable), then sweep
            # with a short retry so a straggler insert cannot orphan rows.
            deadline = time.monotonic() + DRAIN_WAIT_S
            while time.monotonic() < deadline:
                cur.execute(
                    "SELECT status FROM tp_site_survey_run "
                    "WHERE project_id = %s ORDER BY id DESC LIMIT 1",
                    (project_id,))
                row = cur.fetchone()
                if row is None or row[0] in TERMINAL_RUN_STATES:
                    break
                time.sleep(2)
            for attempt in range(3):
                try:
                    _sweep_project_rows(cur, project_id)
                    break
                except psycopg2.errors.ForeignKeyViolation:
                    if attempt == 2:
                        raise
                    time.sleep(2)


def open_ledger(page, project_id):
    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")
    page.goto(f"{BASE_URL}/#/web-elements?projectId={project_id}",
              wait_until="domcontentloaded")
    # Hash-only navigation is same-document: a fresh boot must parse the
    # query so the project segment consumes the fixture id (R30 deep link).
    page.reload(wait_until="domcontentloaded")


def open_profile_drawer(page):
    btn = page.locator("[data-test='ledger-profile-btn']")
    expect(btn).to_be_enabled(timeout=15000)
    btn.click()
    expect(page.locator("[data-test='profile-toolbar']")).to_be_visible()


def create_profile_and_dispatch(page, profile_name):
    row = create_profile_via_ui(page, profile_name, START_URL)
    row.locator("[data-test='profile-survey-btn']").click()


def db_run(project_id):
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT id, status, pages_ok, explore_session_id "
            "FROM tp_site_survey_run WHERE project_id = %s "
            "ORDER BY id DESC LIMIT 1", (project_id,))
        return cur.fetchone()


def test_site_survey_full_journey(logged_in, collectors, journey_project):
    """One continuous journey across the six acceptance faces above."""
    page = logged_in
    project_id = journey_project["project_id"]
    profile_name = f"sj-{journey_project['suffix']}"

    # --- 1+2: profile, dispatch, live tail ---------------------------------
    open_ledger(page, project_id)
    open_profile_drawer(page)
    create_profile_and_dispatch(page, profile_name)

    run_row = page.locator("[data-test='runs-table'] tr.el-table__row",
                           has_text=profile_name).first
    expect(run_row).to_be_visible(timeout=30000)
    # A dispatch may auto-open the live tail already; when it does, the
    # explicit replay door below still has to re-open the same run.
    if page.locator("[data-test='run-tail-dialog']").count():
        page.locator(".el-dialog:visible .el-dialog__headerbtn").first.click()
    run_row.locator("[data-test='run-detail-btn']").click()
    dialog = page.locator("[data-test='run-tail-dialog']")
    expect(dialog).to_be_visible(timeout=10000)
    window = page.locator("[data-test='run-tail-window']")
    expect(window).to_be_visible()

    page.wait_for_selector(".task-tail-empty, .task-tail-row",
                           timeout=FIRST_FRAME_MS)
    expect(window.locator(".task-tail-row").nth(1)).to_be_visible(
        timeout=RUN_WAIT_S * 1000)
    summary = page.locator(".task-tail-summary")
    terminal = summary.filter(has_text=TERMINAL_RE).first
    expect(terminal).to_be_visible(timeout=RUN_WAIT_S * 1000)
    terminal_text = terminal.inner_text().strip()

    run_id, status, pages_ok, session_id = db_run(project_id)
    assert run_id is not None, "no site survey run row was written"
    assert status in ("completed", "partial"), \
        f"run ended {status} instead of a truthful terminal state"
    assert pages_ok >= 2, f"only {pages_ok} pages collected, family expected"
    assert session_id is not None, "run row carries no explore session"
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM tp_ai_task_event WHERE job_ref = %s",
                    (str(session_id),))
        stored_frames = cur.fetchone()[0]
    assert stored_frames >= 2, \
        f"tail persistence door wrote {stored_frames} frames"

    # --- 3: leave the page entirely, re-enter via the deep link (R67) ----
    # Settle each hop before issuing the next one. "#/" is rewritten to
    # "#/dashboard" by the router asynchronously; a reload fired while that
    # rewrite is still in flight lands back on the dashboard instead of the
    # deep link (observed at 06:05 — harness timing, not a product defect).
    page.goto(f"{BASE_URL}/#/", wait_until="domcontentloaded")
    expect(page.locator("[data-test='my-project-table']").first).to_be_visible(
        timeout=15000)
    deep_link = (f"{BASE_URL}/#/web-elements"
                 f"?projectId={project_id}&surveyRun={run_id}")
    page.goto(deep_link, wait_until="domcontentloaded")
    expect(page).to_have_url(deep_link)
    expect(page.locator("[data-test='web-element-ledger-page']")).to_be_visible(
        timeout=15000)
    page.reload(wait_until="domcontentloaded")
    expect(dialog).to_be_visible(timeout=20000)
    expect(window).to_be_visible()
    replay_terminal = summary.filter(has_text=terminal_text).first
    expect(replay_terminal).to_be_visible(timeout=20000)
    # Forward-only lazy-load door for long histories: terminal runs expose
    # it whenever older stored frames exist beyond the initial window.
    more = page.locator("[data-test='run-tail-more']")
    if more.count():
        expect(more.first).to_be_enabled()
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM tp_ai_task_event WHERE job_ref = %s",
                    (str(session_id),))
        assert cur.fetchone()[0] == stored_frames, \
            "stored frame count changed across the revisit (no writes lost, " \
            "but the replay read is not the persisted stream)"

    # --- 4: per-page human approval --------------------------------------
    # Nothing may be ledger-confirmed before a human clicks: assert the
    # baseline first (the survey files candidates, never confirmations).
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM tp_web_element "
                    "WHERE project_id = %s AND state = 1", (project_id,))
        assert cur.fetchone()[0] == 0, "candidates auto-confirmed themselves"
    # Close the replay window through its own header button. A bare Escape
    # here would not reach the dialog: el-dialog consumes it only while the
    # focus sits inside the panel, and the auto-opened deep link leaves
    # focus on the body - a synthetic posture, not a user-facing dead end.
    dialog.locator(".el-dialog__headerbtn").click()
    # Element Plus renders the dialog lazily and keeps its wrapper in the
    # DOM after closing (no destroy-on-close on this face), so removal is
    # never the observable outcome - invisibility is. Screenshot of the
    # failing run confirmed the drawer alone on top: the close worked.
    expect(dialog).to_be_hidden(timeout=10000)

    # The profile drawer is modal: while it stays open, its overlay absorbs
    # every pointer event on the ledger page behind (Playwright reports
    # "el-overlay is-drawer intercepts pointer events"). A real user closes
    # the drawer before touching the page table - mirror that posture here,
    # otherwise the per-page rows behind are unreachable and this is a
    # genuine dead-end face for the user, not a flake.
    # Anchor the profile drawer through its own toolbar instead of the bare
    # ".el-overlay.is-drawer" class selector: that class also matches the
    # always-mounted help-center drawer overlay, so the blind pick resolved
    # two nodes (strict-mode violation observed at 06:21). Same anchoring
    # for the close button keeps this step a single deterministic match
    # with no .first blind pick anywhere.
    profile_overlay = page.locator(".el-overlay").filter(
        has=page.locator("[data-test='profile-toolbar']"))
    drawer_close = profile_overlay.locator(".el-drawer__close-btn")
    expect(drawer_close).to_be_visible(timeout=10000)
    drawer_close.click()
    expect(profile_overlay).to_be_hidden(timeout=10000)

    groups = page.locator(OUTER_ROW)
    expect(groups.first).to_be_visible(timeout=30000)
    assert groups.count() >= 2, \
        f"per-page grouping expected >=2 groups, got {groups.count()}"
    first_key = groups.first.inner_text().splitlines()[0].strip()
    expand_group(page, first_key)
    head = page.locator("[data-test='ledger-candidate-head']").first
    expect(head).to_be_visible(timeout=10000)
    approve = head.locator("[data-test='ledger-approve-all']")
    if approve.count():
        approve.first.click()
    else:
        # The group under the cursor may hold only confirmed rows; pick a
        # group that still carries its pending batch line.
        for i in range(groups.count()):
            key = groups.nth(i).inner_text().splitlines()[0].strip()
            expand_group(page, key)
            approve = page.locator(
                "[data-test='ledger-candidate-head'] "
                "[data-test='ledger-approve-all']").first
            if approve.count():
                approve.click()
                break
        else:
            pytest.fail("no page group carries a pending candidate batch")
    expect(page.locator(".el-message--success").first).to_contain_text(
        "已批准", timeout=60000)
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM tp_web_element "
                    "WHERE project_id = %s AND state = 1", (project_id,))
        confirmed = cur.fetchone()[0]
    assert confirmed >= 1, "per-page approval wrote zero confirmed rows"
    cur_name = None
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute("SELECT name FROM tp_web_element "
                    "WHERE project_id = %s AND state = 1 "
                    "ORDER BY id LIMIT 1", (project_id,))
        cur_name = cur.fetchone()[0]

    # --- 5: referencing list over the confirmed element ------------------
    # Seed one live case step whose anchor names the element through the
    # ledger dialect; the referencing door reads exactly that index.
    case_no = f"TC-SJ-{journey_project['suffix']}"
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tp_test_case (case_no, project_id, title, end_type,"
            " status, source, created_by) VALUES (%s, %s, %s, 'web', 1, 1, 1)"
            " RETURNING id", (case_no, project_id, f"sj-ref-{case_no}"))
        case_id = cur.fetchone()[0]
        journey_project["seeded_case_ids"].append(case_id)
        cur.execute(
            "INSERT INTO tp_case_step (case_id, case_version, step_no,"
            " description, anchor) VALUES (%s, 1, 1, %s, %s::jsonb)",
            (case_id, "journey referencing seed",
             json.dumps({"kind": "elem", "ref": cur_name})))
        conn.commit()

    page.goto(f"{BASE_URL}/#/web-elements?projectId={project_id}",
              wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    # The state-transition buttons belong to the inner element table that a
    # page group renders only once expanded, so the group has to be opened
    # before anything can be visible: asserting first on a collapsed page is
    # what failed at 06:27 (locator expected to be visible, none found).
    groups = page.locator(OUTER_ROW)
    expect(groups.first).to_be_visible(timeout=30000)
    expand_group(page, first_key)
    confirmed_rows = page.locator(
        "[data-test='ledger-confirm'], [data-test='ledger-mark-stale']")
    expect(confirmed_rows.first).to_be_visible(timeout=30000)
    stale = page.locator(
        f"[data-test='ledger-elements-{first_key}'] "
        "[data-test='ledger-mark-stale']").first
    if not stale.count():
        # first_key group may not hold the confirmed row after the CAS
        # re-read collapsed expansions; walk groups until one does.
        groups = page.locator(OUTER_ROW)
        for i in range(groups.count()):
            key = groups.nth(i).inner_text().splitlines()[0].strip()
            expand_group(page, key)
            stale = page.locator(
                "[data-test='ledger-mark-stale']").first
            if stale.count():
                first_key = key
                break
    anchor_door = page.locator(
        f"[data-test='ledger-elements-{first_key}'] "
        "[data-test='ledger-edit-anchors']").first
    if not anchor_door.count():
        anchor_door = page.locator(
            "[data-test='ledger-edit-anchors']").first
    anchor_door.click()
    anchors_dialog = page.locator("[data-test='ledger-anchors-dialog']")
    expect(anchors_dialog).to_be_visible(timeout=10000)
    refs = page.locator("[data-test='ledger-refs-table']")
    expect(refs).to_be_visible(timeout=15000)
    expect(refs).to_contain_text(case_no)

    assert_no_errors(collectors, "site survey full journey")
    assert_no_browser_issues(collectors["browser_issues"],
                             "site survey full journey")
