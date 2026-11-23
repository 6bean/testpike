"""WE-S4 P5 panel spectrum for the survey door and whole-page approval .

Posture: the live survey chain (door -> executor -> candidates -> approval ->
audit) is already proven by the live-chain L4 drill. This
spectrum covers the remaining front-end surface under the worst-scale iron
law: 32 candidate rows folded under one page group, the batch line, the
whole-page approval button and its scope, plus the survey dialog door
(ownership echo, empty-URL honest refusal with zero outbound POST).

Fixture rows are inserted directly into tp_web_element under a throwaway
project (the ledger has no bulk write API by design; creation rides the
project-lens-guarded one-by-one door, so a 30+ row page fixture cannot be
built in a sane number of UI clicks). The factory baseline demo projects
are never touched (R37) and every row plus the project are purged by
captured id in the finalizer.

Exercised through the real chain (vite dev -> admin 8081 -> core Feign ->
PostgreSQL):
 - survey door: enabled with a project segment, dialog shows the ownership
 echo, empty URL is refused in place with no survey POST issued,
 - expanded group carries the candidate batch line with the exact pending
 count and the whole-page approval button,
 - approval flips exactly the 32 candidates of that page to confirmed
 (per-row CAS state door), leaves the page's confirmed rows and the
 other page's candidates untouched, and reports the honest count,
 - persistence across reload: batch line disappears once nothing awaits
 approval, the other page keeps its own batch line,
 - three zero-tolerance checks: 4xx/5xx + console error/warning + Issues.
"""
import os
import uuid

import psycopg2

from web_element_ledger_kit import (OUTER_ROW, dsn,
                                     group_row,
                                     inner_rows,
                                     purge_ledger_fixture)
import pytest
from conftest import BASE_URL, assert_no_errors, nav_to
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SURVEY_PAGE = "/demo/survey0"
OTHER_PAGE = "/demo/other"
CANDIDATE_COUNT = 32  # >=30 worst-scale candidates on one folded page
CONFIRMED_COUNT = 2   # pre-confirmed rows on the same page (must stay put)
OTHER_COUNT = 3       # candidates on a second page (approval must not leak)



@pytest.fixture()
def survey_fixture():
    """Throwaway project: 32 candidates + 2 confirmed on SURVEY_PAGE, 3
    candidates on OTHER_PAGE. Names are zero-padded; the server orders
    element rows by name, so row i maps to elem_{suffix}_{i:02d}."""
    suffix = uuid.uuid4().hex[:8]
    # tp_project.code is varchar(20): keep the throwaway handle short.
    project_name = f"e2e-wes-{suffix}"
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tp_project (name, code, created_by) "
            "VALUES (%s, %s, 1) RETURNING id",
            (project_name, project_name),
        )
        project_id = cur.fetchone()[0]
        ids = []
        anchors = (
            '[{"type":"css","value":"#ctl-%d","strength":0.9,"note":""}]'
        )
        for i in range(CANDIDATE_COUNT):
            cur.execute(
                "INSERT INTO tp_web_element (project_id, name, page_key, anchors, state) "
                "VALUES (%s, %s, %s, %s::jsonb, 0) RETURNING id",
                (project_id, f"元组_{suffix}_c{i:02d}", SURVEY_PAGE, anchors % i),
            )
            ids.append(cur.fetchone()[0])
        for i in range(CONFIRMED_COUNT):
            cur.execute(
                "INSERT INTO tp_web_element (project_id, name, page_key, anchors, state) "
                "VALUES (%s, %s, %s, %s::jsonb, 1) RETURNING id",
                (project_id, f"元组_{suffix}_k{i:02d}", SURVEY_PAGE, anchors % (900 + i)),
            )
            ids.append(cur.fetchone()[0])
        for i in range(OTHER_COUNT):
            cur.execute(
                "INSERT INTO tp_web_element (project_id, name, page_key, anchors, state) "
                "VALUES (%s, %s, %s, %s::jsonb, 0) RETURNING id",
                (project_id, f"元组_{suffix}_o{i:02d}", OTHER_PAGE, anchors % (950 + i)),
            )
            ids.append(cur.fetchone()[0])
        conn.commit()
    yield {"project_id": project_id, "suffix": suffix,
           "project_name": project_name, "ids": ids}
    purge_ledger_fixture(project_id, ids)


def expand_group(page, page_key):
    icon = group_row(page, page_key).locator(".el-table__expand-icon")
    if "expand-icon--expanded" not in (icon.get_attribute("class") or ""):
        icon.click()


def goto_fixture_page(page, fixture):
    page.goto(
        f"{BASE_URL}/#/web-elements?projectId={fixture['project_id']}",
        wait_until="domcontentloaded",
    )
    # Hash-only navigation is same-document: a fresh boot must parse the
    # query so useProjectPageContext consumes the fixture project (R30).
    page.reload(wait_until="domcontentloaded")


def state_counts(project_id, page_key):
    """(candidate, confirmed) for one fixture page, straight from the DB."""
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT state, count(*) FROM tp_web_element "
            "WHERE project_id = %s AND page_key = %s GROUP BY state",
            (project_id, page_key),
        )
        got = dict(cur.fetchall())
    return got.get(0, 0), got.get(1, 0)


def test_survey_door_refuses_empty_url_without_any_call(logged_in, collectors, survey_fixture):
    page = logged_in
    survey_posts = []
    page.on(
        "request",
        lambda req: survey_posts.append(req.url)
        if "/api/v1/web-elements/survey" in req.url and req.method == "POST"
        else None,
    )

    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")
    goto_fixture_page(page, survey_fixture)

    # Door is enabled because the header carries a project segment.
    # 15s boot window: this spec rides vite lazy-compiled chunks on first
    # hit (same posture as the roll-up assertions elsewhere).
    door = page.locator("[data-test='ledger-survey-btn']")
    expect(door).to_be_enabled(timeout=15000)
    door.click()
    dialog = page.locator("[data-test='ledger-survey-dialog']")
    expect(dialog).to_be_visible()

    # Ownership echo inside the dialog equals the header project (G4 single
    # channel — no second project picker).
    echo = dialog.locator("[data-test='owner-echo-value']")
    expect(echo).to_contain_text(survey_fixture["project_name"])

    # Empty URL is refused in place and NO survey POST leaves the browser.
    dialog.locator("[data-test='ledger-survey-submit']").click()
    expect(page.locator(".el-message--warning").first).to_contain_text(
        "请先填写要测绘的页面 URL"
    )
    expect(page.locator("[data-test='ledger-survey-dialog']")).to_be_visible()
    assert survey_posts == [], f"empty-url submit leaked POSTs: {survey_posts}"

    page.locator("[data-test='ledger-survey-cancel']").click()
    expect(page.locator("[data-test='ledger-survey-dialog']")).to_be_hidden()

    assert_no_errors(collectors, "survey door empty-url refusal")
    assert_no_browser_issues(collectors["browser_issues"], "survey door empty-url refusal")


def test_whole_page_approval_is_exact_and_persists(logged_in, collectors, survey_fixture):
    page = logged_in
    project_id = survey_fixture["project_id"]

    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")
    goto_fixture_page(page, survey_fixture)

    # Worst scale: one folded group carries 32 candidates + 2 confirmed;
    # the batch line names the exact pending count and offers one button.
    # Expanded content renders in el-table's expansion slot — locate the
    # batch lines globally by their exact pending count, not by row nesting.
    expect(page.locator(OUTER_ROW)).to_have_count(2, timeout=15000)
    expand_group(page, SURVEY_PAGE)
    expect(inner_rows(page, SURVEY_PAGE)).to_have_count(
        CANDIDATE_COUNT + CONFIRMED_COUNT, timeout=15000)
    survey_head = page.locator(
        "[data-test='ledger-candidate-head']",
        has_text=f"本页还有 {CANDIDATE_COUNT} 条候选等待批准",
    ).first
    expect(survey_head).to_be_visible()

    # One click approves the whole page; every row rides the CAS door, so
    # the toast reports the honest count.
    survey_head.locator("[data-test='ledger-approve-all']").click()
    expect(page.locator(".el-message--success").first).to_contain_text(
        f"已批准 {CANDIDATE_COUNT} 条候选入库", timeout=60000)

    # DB truth: the page now holds only confirmed rows; the sibling page's
    # candidates were NOT swept up by the whole-page approval.
    cand, conf = state_counts(project_id, SURVEY_PAGE)
    assert (cand, conf) == (0, CANDIDATE_COUNT + CONFIRMED_COUNT), (
        f"approval scope drifted on {SURVEY_PAGE}: candidate={cand} confirmed={conf}")
    other_cand, other_conf = state_counts(project_id, OTHER_PAGE)
    assert (other_cand, other_conf) == (OTHER_COUNT, 0), (
        f"approval leaked into {OTHER_PAGE}: candidate={other_cand} confirmed={other_conf}")

    # Persistence across a freshly booted document (the post-write refetch
    # collapses expansions by design): the approved page lost its batch
    goto_fixture_page(page, survey_fixture)
    expand_group(page, SURVEY_PAGE)
    rows = inner_rows(page, SURVEY_PAGE)
    expect(rows).to_have_count(CANDIDATE_COUNT + CONFIRMED_COUNT, timeout=15000)
    for i in (0, CANDIDATE_COUNT + CONFIRMED_COUNT - 1):
        expect(rows.nth(i).locator(".el-tag").first).to_contain_text("已确认")
    expand_group(page, OTHER_PAGE)
    expect(page.locator("[data-test='ledger-candidate-head']")).to_have_count(1)
    expect(page.locator(
        "[data-test='ledger-candidate-head']",
        has_text=f"本页还有 {OTHER_COUNT} 条候选等待批准",
    )).to_be_visible()

    assert_no_errors(collectors, "whole-page approval spectrum")
    assert_no_browser_issues(collectors["browser_issues"], "whole-page approval spectrum")
