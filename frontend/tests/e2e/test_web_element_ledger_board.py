"""E2E for the ledger health board (WE-S7, revision-storm governance).

Worst-scale posture (display-scale iron law): the same 34-row / 7-group
throwaway project layout as the ledger page spec, extended with the three
reading sources the board consumes —
  - revision storm, stale side: 3 distinct elements of /demo/page0 carry
    tp_web_element_stale_event rows inside the rolling window (default
    element.storm.window-hours=24, threshold element.storm.entry-min=3),
  - revision storm, early-warning side: 3 confirmed elements of
    /demo/page1 have a climbing health ladder over the three 7-day windows
    of tp_web_element_health_daily (miss% 0 -> 20 -> 50, not fully dead),
  - self-heal evidence: 2 of the first 8 probed elements (/demo/page0 rows)
    have tp_exec_step_heal_evidence rows behind throwaway exec tasks, so
    the bounded element-level probe reads healed 2 of covered 8 = 25%.

Hard assertions (no conditional wrapping, iron law 5d):
  - the five board anchors render: ledger-health-board, ledger-storm-card
    (exactly the two storming pages), ledger-heal-rate-card,
    ledger-heal-evidence-count (= seeded rows), ledger-trial-suggest-card,
  - side-by-side-never-pooled counts: the page0 card shows exactly 3 stale
    tags and 0 breaking tags, the page1 card the mirror image — a pooled
    count would make both rows nonzero on one card,
  - rate and evidence read together: heal rate shows 25 (2/8), never a dash,
  - the trial card lists every affected entry (3 + 3 = 6 rows),
  - R62: no "manual refresh" phrasing anywhere in the rendered page text,
  - three zero-tolerance families: 4xx/5xx, console error/warning, Issues.

All fixture rows are DB inserts scoped to the throwaway project and are
purged by captured id in the finalizer (stale events, heal evidence,
their relay tasks, elements, project — tp_exec_task and
tp_web_element_stale_event have no cascade from the project row).
"""
import os
import uuid

import psycopg2
import pytest
from conftest import BASE_URL, assert_no_errors, nav_to
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from web_element_ledger_kit import dsn, purge_ledger_fixture

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
LEDGER_PAGE = "[data-test='web-element-ledger-page']"
ROW_COUNT = 34
GROUP_COUNT = 7


@pytest.fixture()
def board_fixture():
    """34-row project + one stale-side storm, one early-warning storm, heal rows."""
    suffix = uuid.uuid4().hex[:8]
    element_ids = {}
    task_ids = []
    stale_count = 0
    evidence_count = 0
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tp_project (name, code, created_by) "
            "VALUES (%s, %s, 1) RETURNING id",
            (f"e2e-weboard-{suffix}", f"e2e-weboard-{suffix}"),
        )
        project_id = cur.fetchone()[0]

        for i in range(ROW_COUNT):
            # confirmed: the usual every-5th row, plus /demo/page1's first
            # three rows so the early-warning side has state IN (1, 2) entries
            confirmed = i % 5 == 0 or i in (1, 8, 15)
            cur.execute(
                "INSERT INTO tp_web_element (project_id, name, page_key, anchors, state) "
                "VALUES (%s, %s, %s, %s::jsonb, %s) RETURNING id",
                (
                    project_id,
                    f"元素_{suffix}_{i:02d}",
                    f"/demo/page{i % 7}",
                    '[{"type":"css","value":"#btn-' + str(i) + '","strength":0.9,"note":""}]',
                    1 if confirmed else 0,
                ),
            )
            element_ids[i] = cur.fetchone()[0]

        # stale side: three distinct /demo/page0 elements went stale in-window
        for i in (0, 7, 14):
            cur.execute(
                "INSERT INTO tp_web_element_stale_event "
                "(element_id, project_id, page_key) VALUES (%s, %s, %s)",
                (element_ids[i], project_id, "/demo/page0"),
            )
            stale_count += 1

        # early-warning side: climbing miss ladder over the three 7-day
        # windows for /demo/page1's confirmed rows (0% -> 20% -> 50%).
        # Buckets come off the database clock (CURRENT_DATE - day offset)
        # so they line up with the window SQL under test on the same clock.
        ladder = [(17, 10, 0), (10, 8, 2), (3, 5, 5)]
        for i in (1, 8, 15):
            for days, hits, misses in ladder:
                cur.execute(
                    "INSERT INTO tp_web_element_health_daily "
                    "(bucket_date, element_id, viewport, hits, misses) "
                    "VALUES (CURRENT_DATE - CAST(%s AS int), %s, "
                    "'1280x720', %s, %s)",
                    (days, element_ids[i], hits, misses),
                )

        # self-heal evidence behind throwaway relay tasks (no FK on case_id;
        # the element-level door reads by element_id only)
        for i, rows in ((0, 2), (7, 1)):
            cur.execute(
                "INSERT INTO tp_exec_task (project_id, case_id, status) "
                "VALUES (%s, %s, 3) RETURNING id",
                (project_id, 900000 + i),
            )
            task_ids.append(cur.fetchone()[0])
            for step in range(rows):
                cur.execute(
                    "INSERT INTO tp_exec_step_heal_evidence "
                    "(task_id, element_id, step_no, healed, first_anchor_failed, "
                    " from_anchor_type, from_anchor_value, used_anchor_type, "
                    " used_anchor_value, viewport, ledger_revision, similarity_score) "
                    "VALUES (%s, %s, %s, true, true, 'css', '#old-%s', "
                    " 'testid', 'login-btn-%s', '1280x720', 1, 0.93)",
                    (task_ids[-1], element_ids[i], step, i, i),
                )
                evidence_count += 1
        conn.commit()

    yield {
        "project_id": project_id,
        "suffix": suffix,
        "ids": list(element_ids.values()),
        "task_ids": task_ids,
        "stale_count": stale_count,
        "evidence_count": evidence_count,
    }

    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "DELETE FROM tp_web_element_stale_event WHERE project_id = %s",
            (project_id,),
        )
        cur.execute(
            "DELETE FROM tp_exec_step_heal_evidence WHERE element_id = ANY(%s)",
            (list(element_ids.values()),),
        )
        cur.execute(
            "DELETE FROM tp_exec_task WHERE id = ANY(%s)", (task_ids,),
        )
        conn.commit()
    purge_ledger_fixture(project_id, list(element_ids.values()))


def _card(page, page_key):
    return page.locator("[data-test='ledger-storm-card']", has_text=page_key).first


def _tag_count(page, card, label):
    return card.locator(".board-list-row", has_text=label).locator(".el-tag").count()


def test_ledger_health_board_storm_heal_trial(logged_in, collectors, board_fixture):
    page = logged_in
    nav_to(page, "页面元素库", "/web-elements", LEDGER_PAGE, submenu="设计用例")
    page.goto(
        f"{BASE_URL}/#/web-elements?projectId={board_fixture['project_id']}",
        wait_until="domcontentloaded",
    )
    # same-document hash navigation would not consume the deep-linked project
    page.reload(wait_until="domcontentloaded")

    # 1. Board renders with exactly the two storming pages (no third page
    #    reaches the threshold — negative bound on the count).
    expect(page.locator("[data-test='ledger-health-board']")).to_be_visible(timeout=20000)
    expect(page.locator("[data-test='ledger-storm-card']")).to_have_count(2, timeout=15000)

    # 2. Side-by-side, never pooled: page0 storms on the stale side only
    expect(_card(page, "/demo/page0")).to_be_visible()
    expect(_card(page, "/demo/page0").locator(".board-list-row", has_text="已陈旧")).to_contain_text("已陈旧")
    assert _tag_count(page, _card(page, "/demo/page0"), "已陈旧") == 3
    assert _tag_count(page, _card(page, "/demo/page0"), "将坏") == 0
    assert _tag_count(page, _card(page, "/demo/page1"), "已陈旧") == 0
    assert _tag_count(page, _card(page, "/demo/page1"), "将坏") == 3
    # the three affected names of the stale side are the seeded elements
    first_stale = _card(page, "/demo/page0").locator(".board-list-row", has_text="已陈旧")
    for i in (0, 7, 14):
        expect(first_stale).to_contain_text(f"元素_{board_fixture['suffix']}_{i:02d}")

    # 3. Self-heal card: rate and evidence count read together — bounded
    #    probe covered 8 elements, 2 healed (25%), 3 evidence rows seen.
    expect(page.locator("[data-test='ledger-heal-rate-card']")).to_be_visible()
    expect(page.locator("[data-test='ledger-heal-rate']")).to_contain_text("25")
    expect(page.locator("[data-test='ledger-heal-evidence-count']")).to_contain_text(
        str(board_fixture["evidence_count"])
    )

    # 4. Trial-run suggestion card lists every affected entry (3 stale + 3
    #    about-to-break), never silently dropping one side.
    expect(page.locator("[data-test='ledger-trial-suggest-card']")).to_be_visible()
    expect(page.locator("[data-test='ledger-trial-suggest-card'] .board-trial-item")).to_have_count(6)

    # 5. R62 value-side guard: the board must never tell the user to refresh.
    body_text = page.locator("body").inner_text()
    for forbidden in ("手动刷新", "点击刷新"):
        assert forbidden not in body_text, f"R62 violation on screen: {forbidden}"

    # 6. Three zero-tolerance families.
    assert_no_errors(collectors, "ledger health board")
    assert_no_browser_issues(collectors["browser_issues"], "ledger health board")
