"""First E2E for the Web element ledger page (WE batch S0, .

Worst-scale posture (display-scale iron law): >=30 ledger rows are inserted
directly into tp_web_element under a self-created throwaway project — the
table has no bulk write API by design (creation goes one-by-one through the
project-lens-guarded admin door), and a 30-row page fixture cannot be built
in a reasonable number of UI clicks. The throwaway project never touches the
factory baseline demo projects (R37), and every inserted row plus the project
row are purged by captured id in the finalizer.

The shipped view is a page-key roll-up, not a flat paged table: the outer
table renders one folded row per canonical page key with lifecycle counts,
and a group's element rows enter the DOM only when it is expanded (heavy
detail folded into the group by default). The assertions below follow that
roll-up contract — worst scale = every group visible with zero element rows
rendered until expansion, filters converge the group set, and promotion
happens on an element row inside an expanded group.

Page surface exercised through the real chain (vite dev -> admin 8081 ->
core Feign -> PostgreSQL):
 - roll-up renders all 7 fixture page groups and no flat pager,
 - expanding the first group lists exactly its 5 element rows,
 - instant search by element name converges to one group / one element row,
 - state filter keeps only the chosen lifecycle state inside the roll-up,
 - candidate -> confirmed promotion from the expanded row action persists
 across reload (URL page state restores, DB readback proves the write),
 - three zero-tolerance checks: 4xx/5xx, console error/warning, Issues.
"""
import os
import re
import uuid

import psycopg2

from web_element_ledger_kit import (OUTER_ROW, dsn,
                                     group_row, inner_rows,
                                     purge_ledger_fixture)
import pytest
from conftest import BASE_URL, assert_no_errors, nav_to
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
ROW_COUNT = 34  # >=30 worst-scale rows spread over 7 page-key groups
GROUP_COUNT = 7  # fixture writes page_key /demo/page{i % 7}



@pytest.fixture()
def ledger_fixture():
    """Throwaway project + 34 ledger rows (mostly candidate, some confirmed).

    Layout facts the assertions lean on (i in 0..33):
      - page_key = /demo/page{i % 7} -> groups of 5/5/5/5/5/5/4;
      - state = confirmed when i % 5 == 0 (7 confirmed, 27 candidate);
      - /demo/page0 holds i = 0,7,14,21,28 -> 5 rows, 4 of them candidate,
        and server orders elements by name (zero-padded -> stable).
    """
    suffix = uuid.uuid4().hex[:8]
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tp_project (name, code, created_by) "
            "VALUES (%s, %s, 1) RETURNING id",
            (f"e2e-weled-{suffix}", f"e2e-weled-{suffix}"),
        )
        project_id = cur.fetchone()[0]
        ids = []
        for i in range(ROW_COUNT):
            state = 1 if i % 5 == 0 else 0
            cur.execute(
                "INSERT INTO tp_web_element (project_id, name, page_key, anchors, state) "
                "VALUES (%s, %s, %s, %s::jsonb, %s) RETURNING id",
                (
                    project_id,
                    f"元素_{suffix}_{i:02d}",
                    f"/demo/page{i % 7}",
                    '[{"type":"css","value":"#btn-'
                    + str(i)
                    + '","strength":0.9,"note":""}]',
                    state,
                ),
            )
            ids.append(cur.fetchone()[0])
        conn.commit()
    yield {"project_id": project_id, "suffix": suffix, "ids": ids}
    purge_ledger_fixture(project_id, ids)


def expand_group(page, page_key):
    """Open a group unless it is already open (idempotent across refetches)."""
    icon = group_row(page, page_key).locator(".el-table__expand-icon")
    if "expand-icon--expanded" not in (icon.get_attribute("class") or ""):
        icon.click()


def test_web_element_ledger_worst_scale_and_promotion(logged_in, collectors, ledger_fixture):
    suffix = ledger_fixture["suffix"]
    page = logged_in

    # Enter the page through the real menu (SPA click navigation, no hash race)
    # The page root is the always-present settle anchor: an empty project
    # segment renders the custom empty block instead of any el-table, so a
    # table selector here would fail before the deep link below.
    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")

    # The page slices by the header project; deep-link the fixture project so
    # the ledger shown is ours (R30: project segment lives in the header, URL
    # param wins per useProjectPageContext).
    page.goto(
        f"{BASE_URL}/#/web-elements?projectId={ledger_fixture['project_id']}",
        wait_until="domcontentloaded",
    )
    # Same-document hash navigation does not re-run the page setup, so the
    # deep-linked projectId would never be consumed by
    # useProjectPageContext (header stayed on "no project selected").
    # A reload boots a fresh document that parses the query first
    # (same posture as the vision rig).
    page.reload(wait_until="domcontentloaded")

    # 0. Rendered filter-band geometry : the keyword input, state
    #    select and query button must share one visual row — center-y within
    #    half a control height. Static class-name checks stayed green while
    #    EP's width:100% default stacked the band vertically, so the contract
    #    is asserted on real bounding boxes, not on the markup.
    band_boxes = [
        page.locator(sel).bounding_box()
        for sel in ("[data-test='ledger-keyword']",
                    "[data-test='ledger-state-filter']",
                    "[data-test='ledger-query-btn']")
    ]
    assert all(band_boxes), "filter band control missing its bounding box"
    centers = [b["y"] + b["height"] / 2 for b in band_boxes]
    assert max(centers) - min(centers) <= band_boxes[0]["height"] / 2 + 1, (
        f"filter band rendered as a vertical stack: centers={centers}"
    )

    # 1. Worst scale under the roll-up contract: every group is visible as a
    #    folded row, no element rows are rendered before expansion, and the
    #    flat pager is gone (replaced by page-key roll-up, not coexisting).
    expect(page.locator(OUTER_ROW)).to_have_count(GROUP_COUNT, timeout=15000)
    for i in range(GROUP_COUNT):
        expect(group_row(page, f"/demo/page{i}")).to_be_visible()
    expect(page.locator("[data-test^='ledger-elements-'] tr.el-table__row")).to_have_count(0)
    expect(page.locator(".el-pagination")).to_have_count(0)
    expand_group(page, "/demo/page0")
    expect(inner_rows(page, "/demo/page0")).to_have_count(5, timeout=15000)

    # 2. Instant search converges the roll-up: one group survives, one
    #    element row inside it, and the filter state mirrors to the URL.
    #    NOTE: el-input forwards the data-test attr onto the inner <input>
    #    itself (DOM probe 2026-11-17), so the attr selector IS the input.
    page.locator("[data-test='ledger-keyword']").fill(f"元素_{suffix}_21")
    page.locator("[data-test='ledger-query-btn']").click()
    expect(page.locator(OUTER_ROW)).to_have_count(1, timeout=15000)
    expect(group_row(page, "/demo/page0")).to_be_visible()
    expect(page).to_have_url(re.compile(r"kw="))
    expand_group(page, "/demo/page0")
    rows_kw = inner_rows(page, "/demo/page0")
    expect(rows_kw).to_have_count(1, timeout=15000)
    expect(rows_kw.first).to_contain_text(f"元素_{suffix}_21")

    # 3. State filter on the roll-up: clearing kw restores 7 groups, keeping
    #    only candidate state trims /demo/page0 to its 4 candidates and every
    page.locator("[data-test='ledger-keyword']").fill("")
    page.locator("[data-test='ledger-query-btn']").click()
    expect(page.locator(OUTER_ROW)).to_have_count(GROUP_COUNT, timeout=15000)
    page.locator("[data-test='ledger-state-filter']").click()
    page.locator(".el-select-dropdown__item", has_text="候选").first.click()
    page.locator("[data-test='ledger-query-btn']").click()
    expect(page.locator(OUTER_ROW)).to_have_count(GROUP_COUNT, timeout=15000)
    expect(page).to_have_url(re.compile(r"state=0"))
    expand_group(page, "/demo/page0")
    rows0 = inner_rows(page, "/demo/page0")
    expect(rows0).to_have_count(4, timeout=15000)
    for i in range(4):
        expect(rows0.nth(i).locator(".el-tag").first).to_contain_text("候选")

    # confirmed segment: page0 now shows exactly 2 confirmed rows (the
    #    (The refetch after the write collapses expansions by design, so the
    #    persistence checks run on freshly booted documents, not live DOM.)
    target_row = rows0.first
    target_name = target_row.locator("td").first.inner_text().strip()
    target_row.locator("[data-test='ledger-confirm']").click()
    expect(page.locator(".el-message, .el-notification").first).to_be_visible()
    page.locator("[data-test='ledger-state-filter']").click()
    page.locator(".el-select-dropdown__item", has_text="已确认").first.click()
    expect(page).to_have_url(re.compile(r"state=1"))
    page.reload(wait_until="domcontentloaded")
    expand_group(page, "/demo/page0")
    confirmed_rows = inner_rows(page, "/demo/page0")
    expect(confirmed_rows).to_have_count(2, timeout=15000)
    promoted = confirmed_rows.filter(has_text=target_name)
    expect(promoted).to_have_count(1)
    expect(promoted.locator(".el-tag").first).to_contain_text("已确认")

    # Candidate side after the write: reload with state=0 restores the
    page.goto(
        f"{BASE_URL}/#/web-elements"
        f"?projectId={ledger_fixture['project_id']}&state=0",
        wait_until="domcontentloaded",
    )
    page.reload(wait_until="domcontentloaded")
    expand_group(page, "/demo/page0")
    candidate_rows = inner_rows(page, "/demo/page0")
    expect(candidate_rows).to_have_count(3, timeout=15000)
    expect(candidate_rows.filter(has_text=target_name)).to_have_count(0)
    for i in range(3):
        expect(candidate_rows.nth(i).locator(".el-tag").first).to_contain_text("候选")

    # DB readback — the write really landed server-side.
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT state FROM tp_web_element WHERE project_id=%s AND name=%s",
            (ledger_fixture["project_id"], target_name),
        )
        assert cur.fetchone()[0] == 1

    # 5. Three zero-tolerance families.
    assert_no_errors(collectors, "web element ledger worst scale")
    assert_no_browser_issues(collectors["browser_issues"], "web element ledger")


@pytest.fixture()
def health_seed(ledger_fixture):
    pass
    ids = ledger_fixture["ids"]
    plan = [
        (0, "1280x720", 1, 19, 1),
        (7, "1280x720", 2, 9, 2),
        (7, "390x844", 2, 8, 3),
        (14, "1280x720", 1, 2, 8),
        (21, "1280x720", 1, 6, 4),
        (21, "1280x720", 10, 16, 4),
        (21, "1280x720", 17, 18, 2),
    ]
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        for i, viewport, days, hits, misses in plan:
            cur.execute(
                "INSERT INTO tp_web_element_health_daily "
                "(bucket_date, element_id, viewport, hits, misses) "
                "VALUES (CURRENT_DATE - CAST(%s AS int), %s, %s, %s, %s)",
                (days, ids[i], viewport, hits, misses),
            )
        conn.commit()
    yield ledger_fixture


def _element_row(page, suffix, i):
    # Scope to the inner elements table body rows only: the outer pages table
    # renders each expanded group as one tr that *contains* the whole inner
    # table, so a bare "tr" filter would also match that ancestor row and
    # every badge inside it (strict-mode violation, 5 badges in one match).
    return page.locator(".elements-table tbody tr",
                        has_text=f"元素_{suffix}_{i:02d}")


def test_web_element_ledger_health_column_badges(
    logged_in, collectors, health_seed
):
    pass
    page = logged_in
    fixture = health_seed
    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")
    page.goto(
        f"{BASE_URL}/#/web-elements?projectId={fixture['project_id']}",
        wait_until="domcontentloaded",
    )
    page.reload(wait_until="domcontentloaded")
    expand_group(page, "/demo/page0")

    suffix = fixture["suffix"]
    expect(_element_row(page, suffix, 0).locator(
        "[data-test='ledger-health-badge']")).to_contain_text("健康", timeout=15000)
    # two columnar viewport buckets (9/2 + 8/3) merge into one 29% read
    row7 = _element_row(page, suffix, 7)
    expect(row7.locator("[data-test='ledger-health-badge']")).to_contain_text("注意")
    assert row7.locator("[data-test='ledger-health-breaking']").count() == 0
    row14 = _element_row(page, suffix, 14)
    expect(row14.locator("[data-test='ledger-health-badge']")).to_contain_text("高风险")
    assert row14.locator("[data-test='ledger-health-breaking']").count() == 0
    row21 = _element_row(page, suffix, 21)
    expect(row21.locator("[data-test='ledger-health-badge']")).to_contain_text("注意")
    expect(row21.locator("[data-test='ledger-health-breaking']")).to_contain_text("将坏")
    # never-reached elements show a label, never a warning colour
    expect(_element_row(page, suffix, 28).locator(
        "[data-test='ledger-health-badge']")).to_contain_text("未触达")
    assert page.locator("[data-test='ledger-health-breaking']").count() == 1

    assert_no_errors(collectors, "ledger health column")
    assert_no_browser_issues(collectors["browser_issues"], "ledger health column")
