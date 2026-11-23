

from conftest import (
    BASE_URL,
    attach_program_project,
    create_program,
    delete_program,
    pick_header_context,
    purge_project,
    seed_cases,
    seed_project,
)
from playwright.sync_api import Page, expect

PROGRAM_PROJECTS = 30  # worst-scale fixture floor for the dashboard segment
DASHBOARD_PAGE_SIZE = 20
CASE_ROWS = 5          # a few real rows behind the cases drilldown page


def _watch_api_requests(page: Page, needle: str):
    seen: list[str] = []
    page.on("request", lambda req: seen.append(req.url) if needle in req.url else None)
    return seen


def _wait_until(page: Page, predicate, timeout_ms: int = 30000) -> None:
    """Poll a predicate while the event loop pumps (request callbacks land
    between waits); same bounded-wait posture as the lens list spectra."""
    deadline = timeout_ms
    while deadline > 0 and not predicate():
        page.wait_for_timeout(250)
        deadline -= 250


def test_dashboard_serves_every_project_under_the_worst_scale_lens(
        logged_in, page: Page, collectors, api_client, uniq):
    """30-project program behind the header lens: one row per project survives
    a reload, drilldowns ride programId onto the list pages, footnotes come
    from the server registry."""
    prog_name = f"大盘集{uniq}"
    prog_id = create_program(api_client, prog_name, "dashboard e2e fixture")
    # The shared seed door derives the unique project code from the first six
    # characters of its uniq argument (code = tag + uniq[:6]), so the per-row
    # index must ride in FRONT of uniq — a trailing suffix keeps uniq[:6]
    # identical across rows and the 30 creates collide on 409 duplicate code.
    pids = [
        seed_project(api_client, f"{i:02d}{uniq}", "盘", display_name=f"盘项{uniq}-{i:02d}")
        for i in range(PROGRAM_PROJECTS)
    ]
    for pid in pids:
        attach_program_project(api_client, prog_id, pid)
    seed_cases(api_client, pids[0], f"盘案{uniq}", CASE_ROWS)
    try:
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")  # refresh the login snapshot
        pick_header_context(page, "program-ctx-select", prog_name)
        reqs = _watch_api_requests(page, "/api/v1")
        # Hash-only goto is a same-document navigation that resolves before
        # the mounted view fetches (conftest lesson from the lens list
        # spectra) — reload forces a fresh document, which also proves the
        # lens persists through reload (single localStorage source).
        page.goto(f"{BASE_URL}/#/program-dashboard", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")

        def _feed_carried():
            return [u for u in reqs if "program-overview" in u and f"programId={prog_id}" in u]

        _wait_until(page, _feed_carried)
        assert _feed_carried(), f"no program-overview request carried the lens id; saw {reqs[:8]}"

        rows = page.locator('[data-test="dashboard-project-table"] .el-table__body .el-table__row')
        pager = page.locator('[data-test="dashboard-pagination"]')
        # Worst-scale posture: the feed carries all 30 projects, the table
        # windows them client-side. The account lives in the pager total, the
        # first page renders exactly one window, the next page the remainder.
        expect(rows.first).to_be_visible(timeout=20000)
        expect(rows).to_have_count(DASHBOARD_PAGE_SIZE, timeout=20000)
        expect(pager).to_be_visible()
        expect(pager).to_contain_text(str(PROGRAM_PROJECTS))
        expect(page.locator('[data-test="dashboard-window"]')).to_be_visible()
        pager.locator(".btn-next").click()
        expect(rows).to_have_count(PROGRAM_PROJECTS - DASHBOARD_PAGE_SIZE, timeout=20000)

        # Caliber footnotes are reference material: folded until asked for. The
        # fold anchor is excluded from the registry-row prefix so the two
        # accounts never blur (see the scale contract on disk).
        fold = page.locator('[data-test="dashboard-caliber-fold"]')
        calibers = page.locator('[data-test^="dashboard-caliber-"]:not([data-test="dashboard-caliber-fold"])')
        expect(fold).to_be_visible()
        expect(fold).to_have_attribute("aria-expanded", "false")
        expect(calibers).to_have_count(0)
        fold.click()
        expect(fold).to_have_attribute("aria-expanded", "true")
        expect(calibers.first).to_be_visible(timeout=10000)
        assert calibers.count() > 0
        # Back to page 1 so the drilldown row index matches the seeded project.
        pager.locator(".btn-prev").click()
        expect(rows).to_have_count(DASHBOARD_PAGE_SIZE, timeout=20000)

        # Drilldown: the cases link must open the list page WITH the lens id
        # in the query and the list request must carry it server-side.
        drill_reqs = _watch_api_requests(page, "/api/v1/cases")
        # One drilldown link per project row exists (30 under this lens), so the
        # strict-mode locator must scope to one row — the seeded-cases row.
        rows.nth(0).locator('[data-test="dashboard-drill-cases"]').click()
        expect(page).to_have_url(
            f"{BASE_URL}/#/cases?programId={prog_id}", timeout=15000)
        rows_after = page.locator(".el-table__body .el-table__row")
        expect(rows_after.first).to_be_visible(timeout=20000)

        def _cases_carried():
            return [u for u in drill_reqs if f"programId={prog_id}" in u]

        _wait_until(page, _cases_carried)
        assert _cases_carried(), f"drilled /cases request lost the lens id; saw {drill_reqs[:8]}"
        # The seeded rows are inside the lens segment — no program-slice empty
        # guidance may shadow the table on the drilled page.
        expect(page.get_by_text("当前项目集内暂无用例")).to_have_count(0)
    finally:
        delete_program(api_client, prog_id)
        for pid in pids:
            purge_project(api_client, pid)


def test_dashboard_without_a_lens_guides_and_calls_zero_overview(
        logged_in, page: Page, collectors, api_client):
    """Detached lens (storage keys wiped, snapshot reloaded): the page names
    the header switcher as the way out and issues no overview request at all."""
    page.evaluate(
        "() => { Object.keys(localStorage)"
        ".filter(k => k.startsWith('tp_program_ctx_'))"
        ".forEach(k => localStorage.removeItem(k)) }")
    reqs = _watch_api_requests(page, "program-overview")
    page.goto(f"{BASE_URL}/#/program-dashboard", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="dashboard-no-lens"]')).to_be_visible(timeout=15000)
    expect(page.get_by_text("先在顶栏选择一个项目集").first).to_be_visible()
    expect(page.get_by_text("顶栏").first).to_be_visible()
    expect(page.locator('[data-test="dashboard-project-table"]')).to_have_count(0)
    page.wait_for_timeout(1500)  # bounded settle window for any stray call
    assert not reqs, f"no-lens dashboard still fired overview calls: {reqs[:5]}"
