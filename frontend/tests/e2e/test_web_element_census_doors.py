"""Census migration door spectrum for the page element library .

Posture: the server-side migration mechanics (card filing, landing, rollback,
idempotent convergence) are already proven by
the internal L4 census migration drill. This spectrum covers the
remaining front-end surface under the worst-scale iron law:

 - both trigger doors are disabled while the header carries no project
 segment (R30 single channel: the page never grows its own picker),
 - the ledger door files exactly one pending card per inline element and
 the result dialog reports the honest counts (31 entries, 31 new cards,
 0 already-pending, 0 already-covered),
 - a second click on the same segment is idempotent: zero new cards, the
 whole batch counted as already-pending (the key is the ledger, not a
 time window),
 - persistence across a freshly booted document: the cards still own the
 pending slot after reload (dialog re-read from the server, plus a
 direct DB count by fixture scope),
 - the reference-replacement door scans the same worst-scale batch and,
 with nothing in the library yet, skips every reference honestly
 (scriptsScanned=31, skipped=31, zero SCRIPT_REF cards filed),
 - three zero-tolerance checks on every test: 4xx/5xx + console
 error/warning + Issues panel.

Fixture rows ride psycopg2 directly (31 web cases each with one inline
locator; the case creation door is not the mechanism under test). One
throwaway project owns every row and the finalizer purges by project scope
only — the factory baseline demo projects are never touched (R37).
"""
import os
import uuid

import psycopg2
import pytest
from conftest import BASE_URL, assert_no_errors, nav_to
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from web_element_ledger_kit import dsn

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
PAGE_URL = "http://shop.test/login"
CASE_COUNT = 31  # >=30 worst-scale inline references across the project segment
LEDGER_KIND = "WEB_ELEMENT_WRITE"
REF_KIND = "WEB_ELEMENT_SCRIPT_REF"


def _script_json(selector):
    """Minimal v2 action document: goto anchor plus one inline click step —
    exactly the inline shape the census scanner flags."""
    return (f'{{"endType":"web","setup":[{{"action":"goto","target":{{"url":"{PAGE_URL}"}}}}],'
            f'"steps":[{{"action":"click","target":{{"css":"{selector}","chain":["css"]}}}}]}}'
            )


@pytest.fixture()
def census_fixture():
    """Throwaway project with 31 web cases, each carrying its own inline
    locator so the census yields 31 distinct entries."""
    suffix = uuid.uuid4().hex[:8]
    # tp_project.code is varchar(20): keep the throwaway handle short.
    project_name = f"e2e-wec-{suffix}"
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tp_project (name, code, created_by) "
            "VALUES (%s, %s, 1) RETURNING id",
            (project_name, project_name),
        )
        project_id = cur.fetchone()[0]
        for i in range(CASE_COUNT):
            cur.execute(
                "INSERT INTO tp_test_case (case_no, project_id, title, end_type,"
                " status, source, created_by) "
                "VALUES (%s, %s, %s, 'web', 1, 1, 1) RETURNING id",
                (f"TC-WEC-{suffix}-{i:02d}", project_id, f"e2e-wec-case-{i:02d}"),
            )
            case_id = cur.fetchone()[0]
            cur.execute(
                "INSERT INTO tp_script (case_id, schema_version, action_json,"
                " status, generated_by, version) "
                "VALUES (%s, 'v2', %s::jsonb, 1, 4, 1)",
                (case_id, _script_json(f"#wec-{suffix}-{i:02d}")),
            )
        conn.commit()
    yield {"project_id": project_id, "suffix": suffix, "project_name": project_name}
    _purge(project_id)


def _purge(project_id):
    """Remove the fixture project and everything the doors filed for it,
    scoped by project id only (R47 ownership).

    Table list is the live schema (probed 2026-11-18): tp_execution and
    tp_case_review do not exist here, and swallowing those UndefinedTable
    errors inside one transaction aborted every later statement, leaving
    orphan projects behind. Autocommit keeps the statements independent and
    a real failure surfaces in teardown instead of hiding.
    """
    with psycopg2.connect(dsn()) as conn:
        conn.autocommit = True
        with conn.cursor() as cur:
            targets = "(SELECT id FROM tp_test_case WHERE project_id = %s)"
            for table, column in (("tp_case_step", "case_id"),
                                  ("tp_script_version", "case_id"),
                                  ("tp_script", "case_id"),
                                  ("tp_case_version", "case_id")):
                cur.execute(f"DELETE FROM {table} WHERE {column} IN " + targets,
                            (project_id,))
            for table in ("tp_ai_chat_draft", "tp_web_element",
                          "tp_explore_session", "tp_user_project"):
                cur.execute(f"DELETE FROM {table} WHERE project_id = %s",
                            (project_id,))
            cur.execute("DELETE FROM tp_test_case WHERE project_id = %s",
                        (project_id,))
            cur.execute("DELETE FROM tp_project WHERE id = %s", (project_id,))


def draft_count(project_id, kind):
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT count(*) FROM tp_ai_chat_draft "
            "WHERE project_id = %s AND object_type = %s AND status = 1",
            (project_id, kind),
        )
        return cur.fetchone()[0]


def goto_fixture_page(page, fixture):
    page.goto(
        f"{BASE_URL}/#/web-elements?projectId={fixture['project_id']}",
        wait_until="domcontentloaded",
    )
    # Hash-only navigation is same-document: a fresh boot must parse the
    # query so useProjectPageContext consumes the fixture project (R30).
    page.reload(wait_until="domcontentloaded")


def dialog_counts(page):
    """label -> value text for the open census result dialog.

    Element Plus renders horizontal bordered description rows as
    td.el-descriptions__label + td.el-descriptions__content (th only appears
    in vertical direction), so select by role classes - guessing the tag name
    measured red on 2026-11-18 (assertion rule 5d: rows must exist, an
    empty read fails instead of returning {}).
    """
    rows = page.locator(
        "[data-test='ledger-census-dialog'] .el-descriptions__body tr")
    expect(rows.first).to_be_visible(timeout=15000)
    got = {}
    for i in range(rows.count()):
        row = rows.nth(i)
        label = row.locator(".el-descriptions__label")
        if label.count():
            got[label.first.inner_text().strip()] = \
                row.locator(".el-descriptions__content").first.inner_text().strip()
    assert got, "census dialog rendered no label/value rows"
    return got


def open_door_and_read(page, door_selector):
    page.locator(door_selector).click()
    dialog = page.locator("[data-test='ledger-census-dialog']")
    expect(dialog).to_be_visible(timeout=60000)
    counts = dialog_counts(page)
    page.locator("[data-test='ledger-census-close']").click()
    expect(dialog).to_be_hidden()
    return counts


def test_census_doors_disabled_without_project_slice(logged_in, collectors):
    """No header segment => both doors disabled with the pick-in-header
 tooltip wired (G4: segment only rides the header, never a page picker)."""
    page = logged_in
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(
        "() => Object.keys(localStorage).filter((k) => k.startsWith('tp_proj_ctx_'))"
        "        .forEach((k) => localStorage.removeItem(k))"
    )
    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")
    ledger_door = page.locator("[data-test='ledger-census-ledger-btn']")
    script_door = page.locator("[data-test='ledger-census-script-btn']")
    expect(ledger_door).to_be_visible(timeout=15000)
    expect(ledger_door).to_be_disabled()
    expect(script_door).to_be_disabled()

    assert_no_errors(collectors, "census doors anchorless disabled")
    assert_no_browser_issues(collectors["browser_issues"],
                             "census doors anchorless disabled")


def test_ledger_door_files_honest_counts_and_persists(logged_in, collectors,
                                                      census_fixture):
    """Worst scale: one click covers 31 inline elements, the dialog names
    the real numbers, the second round is idempotent, and freshly booted
    documents still see the cards as pending (server truth, not cache)."""
    page = logged_in
    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")
    goto_fixture_page(page, census_fixture)

    door = page.locator("[data-test='ledger-census-ledger-btn']")
    expect(door).to_be_enabled(timeout=15000)

    first = open_door_and_read(page, "[data-test='ledger-census-ledger-btn']")
    assert first.get("普查发现的引用条目") == str(CASE_COUNT), first
    assert first.get("本轮新建草案卡") == str(CASE_COUNT), first
    assert first.get("已有待确认草案卡") == "0", first
    assert first.get("页面元素库已覆盖") == "0", first
    assert draft_count(census_fixture["project_id"], LEDGER_KIND) == CASE_COUNT

    # Same segment, same moment: idempotency keys on the pending cards, so
    # nothing new is filed and the honest count moves to already-pending.
    second = open_door_and_read(page, "[data-test='ledger-census-ledger-btn']")
    assert second.get("本轮新建草案卡") == "0", second
    assert second.get("已有待确认草案卡") == str(CASE_COUNT), second

    # Reload persistence: a brand-new document re-reads the server ledger —
    # the cards were not lost, zero new filings.
    goto_fixture_page(page, census_fixture)
    third = open_door_and_read(page, "[data-test='ledger-census-ledger-btn']")
    assert third.get("本轮新建草案卡") == "0", third
    assert third.get("已有待确认草案卡") == str(CASE_COUNT), third
    assert draft_count(census_fixture["project_id"], LEDGER_KIND) == CASE_COUNT

    assert_no_errors(collectors, "ledger census door")
    assert_no_browser_issues(collectors["browser_issues"], "ledger census door")


def test_script_door_scans_and_skips_without_library(logged_in, collectors,
                                                     census_fixture):
    """Reference-replacement door with nothing in the library.

    Replacement scope is library-keyed by design (one card per script whose
    inline locator duplicates an element the page element library already
    holds), so with an empty library every entry is an honest skip:
    skipped = 31, scanned = 0, zero cards filed. The server-side contract of
    this shape rides the L4 census drill (20/20).
    """
    page = logged_in
    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")
    goto_fixture_page(page, census_fixture)

    expect(page.locator("[data-test='ledger-census-script-btn']")
           ).to_be_enabled(timeout=15000)
    counts = open_door_and_read(page, "[data-test='ledger-census-script-btn']")
    assert counts.get("因页面元素库尚无而跳过的引用") == str(CASE_COUNT), counts
    assert counts.get("扫描脚本数") == "0", counts
    assert counts.get("本轮新建草案卡") == "0", counts
    assert draft_count(census_fixture["project_id"], REF_KIND) == 0

    assert_no_errors(collectors, "script-ref census door")
    assert_no_browser_issues(collectors["browser_issues"], "script-ref census door")
