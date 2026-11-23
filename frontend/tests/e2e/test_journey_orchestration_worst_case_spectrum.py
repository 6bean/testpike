"""Journey orchestration pages under the worst-case display scale.

The wall listing (project-set journeys across programs) and the ordered
segment editor must stay usable at 30+ rows: the wall serves a paged segment
with working keyword search and page-2 deep links, and the detail view
renders the full ordered segment table. All data goes through the real
admin API — no mocks, no direct database writes.

Entry paths (user view): journeys wall at /#/journeys, plan detail at
/#/journeys/{id}. Page-state URLs (programId/keyword/page query) are the
single source the deep links below rely on.
"""

from playwright.sync_api import Page, expect

from conftest import BASE_URL, publish_shared_asset, purge_project, seed_project

WALL_ROWS = 35          # worst-case listing scale for the wall
SEGMENT_COUNT = 30      # worst-case ordered-segment table
MAX_ROWS_PER_PAGE = 20  # a listing page may never dump the whole set


def _seed_program(api_client, uniq):
    resp = api_client.post(
        "/programs", json={"name": f"旅程夹具项目集{uniq}", "description": "e2e fixture"}
    )
    assert resp.status_code == 200 and resp.json().get("code") == "0", resp.text[:300]
    return resp.json()["data"]


def _seed_journey(api_client, program_id, name):
    resp = api_client.post(
        "/journeys", json={"programId": program_id, "name": name, "ownerUserId": 1}
    )
    assert resp.status_code == 200 and resp.json().get("code") == "0", resp.text[:300]
    return resp.json()["data"]


def _first_published_asset(api_client, project_id):
    """Published heads are globally discoverable (system bucket law, R34).

    The segment fixture pins a real published asset row and therefore seeds
    its own head through the single-source conftest helper (R37: never assume
    a non-empty slot pool; R40: no second copy of the publish call).
    """
    return publish_shared_asset(
        api_client, project_id, "case_fragment",
        f"journey-pin-{project_id}", "[]")["id"]


def test_journey_wall_stays_paged_at_35_rows(logged_in, page: Page, collectors, api_client, uniq):
    program_id = _seed_program(api_client, uniq)
    first_id = None
    for i in range(WALL_ROWS):
        journey_id = _seed_journey(api_client, program_id, f"{uniq}-journey-{i:02d}")
        first_id = first_id or journey_id

    # No program selected: the create door is honestly disabled (R25) and
    # points at the reason instead of failing later.
    page.goto(f"{BASE_URL}/#/journeys", wait_until="domcontentloaded")
    expect(page.locator("[data-test=journey-wall-page]")).to_be_visible(timeout=10_000)
    expect(page.locator("[data-test=journey-create-disabled]")).to_be_visible()

    # Program selected through the page-level query selector (exempt type 2:
    # query parameter, not a project segment switch) — deep link is the source.
    page.goto(f"{BASE_URL}/#/journeys?programId={program_id}", wait_until="domcontentloaded")
    rows = page.locator("[data-test=journey-table] .el-table__body tbody tr")
    expect(rows).to_have_count(MAX_ROWS_PER_PAGE, timeout=10_000)
    expect(page.locator("[data-test=journey-pagination]")).to_be_visible()

    # Keyword search narrows to the single matching row (≤2 actions reach).
    page.locator("[data-test=journey-keyword-input] input, [data-test=journey-keyword-input]").first.fill(f"{uniq}-journey-07")
    page.locator("[data-test=journey-query]").click()
    expect(rows).to_have_count(1, timeout=10_000)

    # Page-2 deep link returns a different segment (page state is URL, R22).
    page.goto(
        f"{BASE_URL}/#/journeys?programId={program_id}&page=2", wait_until="domcontentloaded"
    )
    page_rows = page.locator("[data-test=journey-table] .el-table__body tbody tr")
    expect(page_rows).to_have_count(WALL_ROWS - MAX_ROWS_PER_PAGE, timeout=10_000)

    # Opening a row lands on the plan detail page. The wall may order rows by recency,
    # so reach the known fixture row through its unique keyword (≤2 actions, S-group)
    # instead of assuming it sits on page one.
    page.goto(f"{BASE_URL}/#/journeys?programId={program_id}", wait_until="domcontentloaded")
    page.locator("[data-test=journey-keyword-input] input, [data-test=journey-keyword-input]").first.fill(
        f"{uniq}-journey-00")
    page.locator("[data-test=journey-query]").click()
    page.locator(f"[data-test=journey-open-{first_id}]").click()
    expect(page.locator("[data-test=journey-plan-page]")).to_be_visible(timeout=10_000)


def test_journey_segment_editor_holds_30_segments(
    logged_in, page: Page, collectors, api_client, uniq
):
    program_id = _seed_program(api_client, uniq)
    journey_id = _seed_journey(api_client, program_id, f"{uniq}-full-01")
    project_id = seed_project(api_client, uniq, "jseg", display_name=f"旅程消费项目{uniq}")
    asset_row_id = _first_published_asset(api_client, project_id)
    try:
        for seq in range(1, SEGMENT_COUNT + 1):
            resp = api_client.post(
                f"/journeys/{journey_id}/segments",
                json={
                    "seqNo": seq,
                    "projectId": project_id,
                    "targetKind": "asset",
                    "assetRowId": asset_row_id,
                    "caseId": None,
                    "paramsOverrideJson": None,
                },
            )
            assert resp.status_code == 200 and resp.json().get("code") == "0", resp.text[:300]

        page.goto(f"{BASE_URL}/#/journeys/{journey_id}", wait_until="domcontentloaded")
        expect(page.locator("[data-test=journey-segment-table]")).to_be_visible(timeout=10_000)
        rows = page.locator("[data-test=journey-segment-table] .el-table__body tbody tr")
        expect(rows).to_have_count(SEGMENT_COUNT, timeout=10_000)

        # The append form is present with its scoped doors (project, kind, asset).
        expect(page.locator("[data-test=journey-segment-form]")).to_be_visible()
        expect(page.locator("[data-test=journey-form-project]")).to_be_visible()

        # Ownership echo (R23/G group): the detail heading carries the journey
        # entity name, not a duplicated page title.
        expect(page.locator("[data-test=journey-plan-title]")).to_contain_text(
            f"{uniq}-full-01"
        )
    finally:
        purge_project(api_client, project_id)
