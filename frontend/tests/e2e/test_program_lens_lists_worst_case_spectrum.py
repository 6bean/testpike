

import re

from playwright.sync_api import Page, expect

from conftest import (
    BASE_URL,
    attach_program_project,
    create_program,
    delete_program,
    pick_header_context,
    pick_project_in_header,
    purge_project,
    seed_cases,
    seed_project,
)

CASE_ROWS = 30  # worst-scale fixture floor for the case segment

# route hash -> the page's program-slice empty title (single source of the
# expected noun: the shared hint tail is asserted separately).
LENS_PAGES = {
    "/#/cases": "当前项目集内暂无用例",
    "/#/api-test": "当前项目集内暂无接口",
    "/#/perf": "当前项目集内暂无压测场景",
    "/#/batches": "当前项目集内暂无批次",
    "/#/defects": "当前项目集内暂无缺陷",
    "/#/report-center": "当前项目集内暂无报表记录",
}
LENS_HINT_TAIL = "顶栏项目集切换器"  # common.lensEmptyHint names the header switch


def _pick_program(page: Page, program_name: str) -> None:
    """Choose one program in the header lens; the switch mechanics are
    single-sourced in conftest.pick_header_context (R40) — same posture as the
    project switch, including the login-snapshot reload-retry."""
    pick_header_context(page, "program-ctx-select", program_name)


def _clear_program(page: Page) -> None:
    """Detach the header lens through the select's clear control."""
    sel = page.locator('[data-test="program-ctx-select"]')
    sel.hover()
    clear = sel.locator(".el-select__clear")
    expect(clear).to_be_visible(timeout=5000)
    clear.click()


def _watch_list_requests(page: Page, needle: str):
    """Collect list-request URLs matching `needle` issued after the call."""
    seen = []
    page.on("request", lambda req: seen.append(req.url) if needle in req.url else None)
    return seen


def _wait_until(page: Page, predicate, timeout_ms: int = 20000):
    """Poll a predicate on the UI thread until it turns truthy (request
    callbacks are dispatched while wait_for_timeout pumps the event loop);
    raise with the timeout once elapsed so the caller's assertion still
    carries the live capture list."""
    deadline = timeout_ms
    while deadline > 0 and not predicate():
        page.wait_for_timeout(200)
        deadline -= 200


def test_case_list_carries_program_id_and_drops_it_on_detach(
        logged_in, page: Page, collectors, api_client, uniq):
    """Lens on -> the case list request carries programId and the segment holds;
 detach -> the follow-up request carries no programId at all (legacy
 zero-drift), with 30 seeded rows behind the paged list either way."""
    pid_a = seed_project(api_client, uniq, "透镜A", display_name=f"透镜甲{uniq}")
    pid_b = seed_project(api_client, uniq, "透镜B", display_name=f"透镜乙{uniq}")
    prog_name = f"集透镜{uniq}"
    prog_id = create_program(api_client, prog_name, "lens e2e fixture")
    attach_program_project(api_client, prog_id, pid_a)
    attach_program_project(api_client, prog_id, pid_b)
    seed_cases(api_client, pid_a, f"透A{uniq}", CASE_ROWS)
    try:
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")  # refresh the login snapshot
        _pick_program(page, prog_name)
        pick_project_in_header(page, f"透镜甲{uniq}")
        # A hash-only page.goto is a same-document navigation that resolves
        # before the mounted view has fetched (11-07: rows matched the
        # dashboard's leftover table and the request list was still empty —
        # a test-spectrum race, not a product defect). Reload forces a fresh
        # document so the list mount fetches. The sync expect() has no
        # callable/polling form here and expect_response's waiter is racy
        # across the reload (Playwright Waiter listener KeyError), so wait
        # on the captured request list with a bounded loop.
        reqs = _watch_list_requests(page, "/api/v1")
        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        rows = page.locator(".el-table__body .el-table__row")
        expect(rows.first).to_be_visible(timeout=15000)
        expect(page.get_by_text("当前项目集内暂无用例")).to_have_count(0)
        # the lens rode the request: programId=<id> appears on a list call
        # issued after the pick (page reload / first fetch on mount).
        expect(page).to_have_url(re.compile(r"#/cases"))

        def _lens_carried():
            return [u for u in reqs
                    if "/cases" in u and f"programId={prog_id}" in u]

        _wait_until(page, _lens_carried)
        assert _lens_carried(), f"no /cases request carried programId; saw {reqs[:8]}"

        _clear_program(page)
        reqs_after = _watch_list_requests(page, "/api/v1")

        def _refetched():
            return [u for u in reqs_after if "/cases" in u]

        page.reload(wait_until="domcontentloaded")
        _wait_until(page, _refetched)
        expect(rows.first).to_be_visible(timeout=15000)
        leaked = [u for u in reqs_after if "/cases" in u and "programId" in u]
        assert not leaked, f"detached lens still leaks programId: {leaked[:8]}"
    finally:
        delete_program(api_client, prog_id)
        for pid in (pid_a, pid_b):
            purge_project(api_client, pid)


def test_lens_empty_program_guides_on_every_list_page(
        logged_in, page: Page, collectors, api_client, uniq):
    """One program whose single project has no rows anywhere: each of the six
    wired pages states the program-slice title and points at the header
    switcher for the way out; detaching the lens clears the guidance."""
    pid_d = seed_project(api_client, uniq, "透镜空", display_name=f"透空{uniq}")
    prog_name = f"集空镜{uniq}"
    prog_id = create_program(api_client, prog_name, "lens e2e fixture")
    attach_program_project(api_client, prog_id, pid_d)
    try:
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        _pick_program(page, prog_name)
        # the collection-workbench pages are project-scoped and short-circuit
        # program's own (rowless) project first — then every wired page
        # really sits inside the empty program segment.
        pick_project_in_header(page, f"透空{uniq}")
        for route, title in LENS_PAGES.items():
            page.goto(f"{BASE_URL}{route}", wait_until="domcontentloaded")
            expect(page.get_by_text(title).first).to_be_visible(timeout=15000)
            expect(page.get_by_text(LENS_HINT_TAIL).first).to_be_visible(timeout=5000)
        _clear_program(page)
        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        expect(page.get_by_text("当前项目集内暂无用例")).to_have_count(0, timeout=10000)
    finally:
        delete_program(api_client, prog_id)
        purge_project(api_client, pid_d)
