""" i-surface close-out: ledger rename chain through the real UI.

Chain asserted end to end (browser -> admin bridge -> core -> DB):
 R1 create two candidates on one page through the create door;
 R2 open the anchor dialog of the second row, rename it inline
 (ledger-rename-input / ledger-rename-submit), the input arrives
 prefilled with the live name;
 R3 the list silently re-reads under the NEW name and the old name is
 gone from every visible surface; keyword filter on the old name
 returns zero rows while the new name hits (name-addressed
 references resolve on the new name from the next read onwards);
 R4 DB readback: the row carries the new name and the revision moved
 (same-name replay no-op is covered by the L2 door tests shipped
 with the rename segment);
 R5 clash door: renaming row one onto row two's new name is refused
 with the conflicting value named on screen, row one keeps its name.

Three zero-tolerance monitors (4xx/5xx, console, Issues) run on top.
"""
import uuid

import psycopg2

from web_element_ledger_kit import dsn, purge_ledger_fixture
import pytest
from conftest import (BASE_URL, assert_no_errors, nav_to,
                      whitelist_expected_reject)
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

FIXTURE_PAGE = "http://localhost:8899/b11_order_page.html"


@pytest.fixture()
def throwaway_project():
    suffix = uuid.uuid4().hex[:8]
    name = f"e2e-rn-{suffix}"[:20]
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tp_project (name, code, created_by) VALUES (%s, %s, 1) RETURNING id",
            (name, name),
        )
        row = cur.fetchone()
        assert row is not None, "throwaway project insert returned no id"
        project_id = row[0]
        conn.commit()
    yield {"project_id": project_id, "suffix": suffix}
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT id FROM tp_web_element WHERE project_id = %s", (project_id,))
        purge_ledger_fixture(project_id, [r[0] for r in cur.fetchall()])
        cur.execute("DELETE FROM tp_web_element WHERE project_id = %s", (project_id,))
        cur.execute("DELETE FROM tp_project WHERE id = %s", (project_id,))
        conn.commit()


def open_ledger(page, project_id):
    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")
    page.goto(f"{BASE_URL}/#/web-elements?projectId={project_id}",
              wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    door = page.locator("[data-test='ledger-create-btn']")
    # 15s: vite lazy-compiled chunks on first hit (posture of the sibling specs).
    expect(door).to_be_enabled(timeout=15000)
    return door


def create_element(page, door, name):
    door.click()
    dialog = page.locator("[data-test='ledger-create-dialog']")
    expect(dialog).to_be_visible()
    dialog.locator("[data-test='ledger-form-name']").fill(name)
    dialog.locator("[data-test='ledger-form-url']").fill(f"{FIXTURE_PAGE}?s={name}")
    # Server contract (validateAnchors): a candidate carries at least one
    # anchor — an anchorless row can never be located by the executor. The
    # editor appends a css-typed row; only its value is mandatory here.
    dialog.locator("[data-test='anchor-add']").click()
    dialog.locator("[data-test='anchor-value']").last.fill(f"#{name}")
    dialog.locator("[data-test='ledger-create-submit']").click()
    # el-dialog keeps its wrapper mounted after the first open (no
    # destroy-on-close), so "closed" means hidden, never count 0.
    expect(dialog).not_to_be_visible(timeout=10000)


def db_row(project_id, name):
    """Ledger row (id, revision) addressed by its project+name folded key."""
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT id, revision FROM tp_web_element "
            "WHERE project_id = %s AND name = %s",
            (project_id, name))
        return cur.fetchone()


def query(page, keyword):
    page.locator("[data-test='ledger-keyword']").fill(keyword)
    page.locator("[data-test='ledger-query-btn']").click()


def expand_first_group(page):
    """The pages table is grouped: element rows enter the DOM only after
    their page group is expanded (sibling posture in test_web_element_ledger_page)."""
    icon = page.locator(
        "[data-test='ledger-pages-table'] tr.el-table__row .el-table__expand-icon").first
    expect(icon).to_be_visible(timeout=10000)
    if "expand-icon--expanded" not in (icon.get_attribute("class") or ""):
        icon.click()


def rename_through_dialog(page, from_name, to_name, *, expect_reject=False):
    query(page, from_name)
    expand_first_group(page)
    row = page.locator(
        f"[data-test^='ledger-elements-'] >> tr:has-text('{from_name}')").first
    expect(row).to_be_visible(timeout=10000)
    row.locator("[data-test='ledger-edit-anchors']").click()
    rename_input = page.locator("[data-test='ledger-rename-input']")
    expect(rename_input).to_be_visible(timeout=10000)
    expect(rename_input).to_have_value(from_name)
    rename_input.fill(to_name)
    # The submit click is paired with its HTTP response, so the wire verdict
    # is captured synchronously — collector event dispatch lags assertions and
    # must never be the source for the expected-status check.
    with page.expect_response(lambda r: r.url.endswith("/name"),
                              timeout=10000) as info:
        page.locator("[data-test='ledger-rename-submit']").click()
    resp = info.value
    if expect_reject:
        assert resp.status == 409, \
            f"clash rename expected 409, got {resp.status}"
        return resp
    assert resp.status == 200, \
        f"rename expected 200, got {resp.status} body={resp.text()[:200]}"
    # The anchor dialog stays open on success (in-place feedback by design);
    # cancel closes it so the toolbar behind is reachable again.
    page.locator("[data-test='ledger-anchors-cancel']").click()
    expect(page.locator("[data-test='ledger-anchors-dialog']")) \
        .not_to_be_visible(timeout=10000)
    return resp


def test_ledger_rename_chain(logged_in, collectors, throwaway_project):
    page = logged_in
    project_id = throwaway_project["project_id"]
    old_name = f"旧名元素{throwaway_project['suffix']}"
    new_name = f"新名元素{throwaway_project['suffix']}"
    other_name = f"登录按钮{throwaway_project['suffix']}"

    door = open_ledger(page, project_id)
    create_element(page, door, other_name)
    create_element(page, door, old_name)

    before = db_row(project_id, old_name)
    assert before is not None, "seed row missing before rename"

    # R2 + R3: rename through the dialog, then the silent re-read under the
    # still-active old keyword must come back empty (old name gone from the
    # server segment), and a fresh query on the new name must hit the row.
    rename_through_dialog(page, old_name, new_name)
    expect(page.locator("[data-test='ledger-empty']")).to_be_visible(timeout=10000)
    assert old_name not in page.locator(".page-root").inner_text(), \
        "old name still on screen after rename"
    query(page, new_name)
    expand_first_group(page)
    expect(page.locator(
        f"[data-test^='ledger-elements-'] >> tr:has-text('{new_name}')").first) \
        .to_be_visible(timeout=10000)

    # R4: DB readback — new name persisted, revision moved.
    after = db_row(project_id, new_name)
    assert after is not None and db_row(project_id, old_name) is None, \
        "rename did not land in the ledger"
    assert after[1] > before[1], "rename left the revision untouched"

    # R5: same-page clash refusal names the conflicting value on screen and
    # leaves the other row untouched (the dialog stays open on rejection).
    page.locator("[data-test='ledger-keyword']").fill("")
    page.locator("[data-test='ledger-query-btn']").click()
    rename_through_dialog(page, other_name, new_name, expect_reject=True)
    expect(page.locator(".el-message", has_text=new_name).first) \
        .to_be_visible(timeout=10000)
    # The refusal is the product contract: exempt exactly this row's 409 via
    # the shared single-source helper and pin the count — a missing rejection
    # (silently open gate) must fail here, not pass quietly. The check runs
    # after the message assertion on purpose: response callbacks are pumped
    # by Playwright calls, not by the psycopg2 round trip.
    clash = db_row(project_id, other_name)
    assert clash is not None, "clashed row vanished from the ledger"
    assert whitelist_expected_reject(
        collectors, f"web-elements/{clash[0]}/name", 409) == 1, \
        "clash 409 count != 1 — rejection door no longer answers on the wire"
    assert db_row(project_id, other_name) is not None, \
        "clashed rename mutated the wrong row"

    assert_no_errors(collectors, "ledger rename chain")
    assert_no_browser_issues(collectors["browser_issues"], "ledger rename chain")
