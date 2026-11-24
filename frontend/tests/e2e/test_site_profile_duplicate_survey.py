"""Duplicate-dispatch guard on the site-profile ledger, proven through the
real browser and the real wire (no stubs, R3).

A whole-site survey may run only once per profile at a time. This spectrum
covers the operator-visible faces of that rule (the server-side refusal
itself is covered by SiteSurveyDispatchTest):

 1. dispatching a profile through the drawer writes a live run row and the
    profile row action flips from the survey entry to the progress entry
    (the on-screen progress wording the 22:21 screenshot showed could not
    be matched to any stored text source -- this readback closes that gap),
 2. the progress entry opens the run's live tail window directly (R62:
    no manual refresh is asked of the operator),
 3. a second dispatch of the same profile is refused over HTTP 400 and the
    refusal names the in-flight run (id + start time) and the two entries
    that show its progress (R63 three-element posture), and writes nothing
    new,
 4. the ledger still shows exactly one run after the refused attempt
    (reload readback, iron law 4).

Teardown reclaims everything this test creates, scoped by the throwaway
project id: the in-flight run is driven to a terminal state first because
an unclaimed run row otherwise keeps the crawl door open behind us (R57).
"""
import os
import re
import uuid

import httpx
import psycopg2
import pytest
from conftest import API_BASE, ADMIN_PASSWORD, ADMIN_USERNAME, BASE_URL, api_login, assert_no_errors, nav_to
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from test_site_survey_full_journey import _sweep_project_rows
from web_element_ledger_kit import create_profile_via_ui, dsn

# Directory address shape (same posture as the journey: the family is what
# gets crawled, not one page). Nothing is crawled in this spectrum because
# no executor claims the run before it is refused and swept.
START_URL = (
    f"http://localhost:{os.environ.get('FIXTURE_PORT', '8899')}/pr/"
)


@pytest.fixture()
def guard_project():
    """Throwaway project row; teardown drives any in-flight run to a
    terminal state first, then sweeps the row families by project id."""
    suffix = uuid.uuid4().hex[:8]
    name = f"e2e-dup-{suffix}"[:20]
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tp_project (name, code, created_by) "
            "VALUES (%s, %s, 1) RETURNING id", (name, name))
        project_id = cur.fetchone()[0]
        conn.commit()
    yield {"project_id": project_id, "name": name, "suffix": suffix}
    with psycopg2.connect(dsn()) as conn:
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE tp_site_survey_run SET status = 'cancelled', "
                "finished_at = now() WHERE project_id = %s AND status = 'running'",
                (project_id,))
            _sweep_project_rows(cur, project_id)
            cur.execute("DELETE FROM tp_project WHERE id = %s", (project_id,))


def _open_ledger_and_drawer(page, project_id):
    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")
    page.goto(f"{BASE_URL}/#/web-elements?projectId={project_id}",
              wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    btn = page.locator("[data-test='ledger-profile-btn']")
    expect(btn).to_be_enabled(timeout=15000)
    btn.click()
    expect(page.locator("[data-test='profile-toolbar']")).to_be_visible()


def _db_running_run(profile_name):
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT r.id, to_char(r.started_at, 'YYYY-MM-DD HH24:MI:SS') "
            "FROM tp_site_survey_run r JOIN tp_site_profile p ON p.id = r.profile_id "
            "WHERE p.name = %s AND r.status = 'running' ORDER BY r.id DESC LIMIT 1",
            (profile_name,))
        return cur.fetchone()


def _db_run_count(profile_name):
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT count(*) FROM tp_site_survey_run r "
            "JOIN tp_site_profile p ON p.id = r.profile_id WHERE p.name = %s",
            (profile_name,))
        return cur.fetchone()[0]


def test_duplicate_survey_refused_with_in_flight_run_named(logged_in, collectors, guard_project):
    """The whole face sequence: flip -> progress entry -> refusal -> nothing new."""
    page = logged_in
    profile_name = f"dup-{guard_project['suffix']}"

    _open_ledger_and_drawer(page, guard_project["project_id"])
    row = create_profile_via_ui(page, profile_name, START_URL)

    # 1. first dispatch rides the real door: run row appears, row action flips.
    row.locator("[data-test='profile-survey-btn']").click()
    run_row = page.locator("[data-test='runs-table'] tr.el-table__row",
                           has_text=profile_name).first
    expect(run_row).to_be_visible(timeout=30000)
    if page.locator("[data-test='run-tail-dialog']").count():
        page.locator(".el-dialog:visible .el-dialog__headerbtn").first.click()

    running = _db_running_run(profile_name)
    assert running is not None, "dispatch wrote no running survey run"
    run_id, started_at = running

    # 2. the on-screen current wording (pitfall ledger: the 22:21 screenshot
    #    words matched no stored text source; this is the live readback).
    progress_btn = row.locator("[data-test='profile-progress-btn']")
    expect(progress_btn).to_be_visible()
    assert "查看进度" in progress_btn.inner_text(), \
        f"row action kept the survey entry while a run is in flight: {progress_btn.inner_text()!r}"
    assert row.locator("[data-test='profile-survey-btn']").count() == 0, \
        "the survey entry stayed clickable while a run is in flight"
    artifacts = os.path.join(os.path.dirname(__file__), "artifacts", "general")
    os.makedirs(artifacts, exist_ok=True)
    page.screenshot(path=os.path.join(
        artifacts, f"dup_guard_row_action_{guard_project['suffix']}.png"),
        full_page=True)

    # 3. the progress entry opens the live tail window directly (R62).
    progress_btn.click()
    dialog = page.locator("[data-test='run-tail-dialog']")
    expect(dialog).to_be_visible(timeout=10000)
    expect(page.locator("[data-test='run-tail-window']")).to_be_visible()
    page.locator(".el-dialog:visible .el-dialog__headerbtn").first.click()

    # 4. second dispatch over the real wire: refused 400, refusal names the
    #    run (id + start time) and the two progress entries (R63).
    token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)
    with httpx.Client(base_url=API_BASE, timeout=15) as client:
        refused = client.post(f"/site-profiles/{_profile_id(profile_name)}"
                             f"/survey",
                             headers={"Authorization": f"Bearer {token}"})
    assert refused.status_code == 400, \
        f"duplicate dispatch returned {refused.status_code}: {refused.text[:300]}"
    message = refused.json().get("msg", "") or refused.json().get("message", "")
    assert str(run_id) in message, f"refusal does not name the run id: {message}"
    assert started_at in message, f"refusal does not name the start time: {message}"
    assert "查看进度" in message and "进程详情" in message, \
        f"refusal misses the two progress entries: {message}"
    assert re.search(r"尚未结束|正在进行中", message), \
        f"refusal does not state why nothing started: {message}"

    # 5. nothing new was written; after a full reload the ledger still shows
    #    the same single run and the row action is still the progress entry
    #    (iron law 4: re-open and read back, never trust the in-memory page).
    assert _db_run_count(profile_name) == 1, \
        "the refused dispatch wrote a second run row"
    page.reload(wait_until="domcontentloaded")
    ledger_btn = page.locator("[data-test='ledger-profile-btn']")
    expect(ledger_btn).to_be_enabled(timeout=15000)
    ledger_btn.click()
    expect(page.locator("[data-test='profile-toolbar']")).to_be_visible()
    persisted_row = page.locator("[data-test='profile-table'] tr.el-table__row",
                                 has_text=profile_name).first
    expect(persisted_row.locator("[data-test='profile-progress-btn']")) \
        .to_be_visible(timeout=10000)
    assert persisted_row.locator(
        "[data-test='profile-survey-btn']").count() == 0, \
        "after reload the survey entry came back while the run is still in flight"

    # 6. three zero-tolerance families over the whole spectrum.
    assert_no_errors(collectors, "duplicate survey guard spectrum")
    assert_no_browser_issues(collectors["browser_issues"],
                             "duplicate survey guard spectrum")


def _profile_id(profile_name):
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute("SELECT id FROM tp_site_profile WHERE name = %s",
                    (profile_name,))
        return cur.fetchone()[0]
