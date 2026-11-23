"""Requirement list: title-cell click scroll spectrum + F1 page-state URL sync.

Defect under test (user report 2026-11-22): on the requirements page a single
left click ANYWHERE in the title column slid the horizontal scrollbar to the far
right. Live attribution: Element Plus 2.14 gives <tbody tabindex="-1">; a plain
(text-only) cell click hands focus to the tbody and the browser reveals the
oversized body — at 1280px width scrollLeft jumped 0 -> 466 (max) inside one
trusted click, chapter column included. The shared guard lives in
src/utils/tableLabelFocusGuard.ts (installTableBodyClickJumpGuard); this file
pins the real-browser truth with a real backend and a real mouse (no mocks).

The second leg locks the page-state URL contract added in the same segment
(F1): filters/page/size/sort ride the query and survive reload; the size ladder
matches the CaseListView baseline.
"""

import re

from conftest import assert_no_errors
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from test_requirement_batch_handoff import _mk_requirement, _open_req_page, _sweep_project
from test_f2_extraction import make_project

WRAP = "[data-test='req-table'] .el-table__body-wrapper .el-scrollbar__wrap"


def _sl(page):
    return page.evaluate("(sel)=>document.querySelector(sel).scrollLeft", WRAP)


def test_plain_cell_click_keeps_scroll_position(page, collectors, api_client, uniq):
    """S1: cold trusted clicks on title/chapter text never move the bar."""
    pid = None
    try:
        pid = make_project(api_client, uniq, "SC1")
        for i in range(1, 14):
            _mk_requirement(api_client, pid, f"滚动谱需求标题{i}_{uniq}，标题足够长以触发省略")
        page.set_viewport_size({"width": 1280, "height": 800})
        _open_req_page(page, pid)
        expect(page.locator("[data-test='req-table'] tbody tr.el-table__row")).to_have_count(10, timeout=15000)

        # Fixture truth: the table must overflow horizontally here, else the
        # defect cannot manifest and this leg would be a silent pass.
        overflow = page.evaluate(
            "(sel)=>{const w=document.querySelector(sel);return w.scrollWidth - w.clientWidth}", WRAP
        )
        assert overflow > 8, f"fixture lost its overflow (delta={overflow}): widen columns or narrow the viewport"

        # Click 1 — the reported shape: first click focuses the tbody (cold).
        # Anchor a visible row's title text (creation order is not page-1 order).
        page.locator("[data-test='req-table'] .req-title").first.click()
        assert _sl(page) == 0, "title-cell click slid the horizontal scrollbar (tbody focus reveal regressed)"

        # Click 2 — move focus away (search box), reset, then the chapter column.
        page.locator("[data-test='req-filter-keyword']").click()
        page.evaluate("(a)=>{document.querySelector(a[0]).scrollLeft = a[1]}", [WRAP, overflow // 2])
        mid = _sl(page)
        assert mid > 0, "scroll fixture: could not place the bar mid-scroll"
        page.locator("[data-test='req-table'] tbody tr.el-table__row").nth(1).locator("td").nth(3).click()
        assert _sl(page) == mid, "chapter-cell click moved the horizontal scrollbar"

        # Click 3 — a plain id/fingerprint text click mid-scroll must hold the
        # bar: the mono span sits on-screen at `mid` (the narrow id column does
        # not — clicking it would let Playwright's actionability scroll move it).
        page.locator("[data-test='req-filter-keyword']").click()
        page.locator("[data-test='req-table'] td [data-test^='req-val-fingerprint-']").first.click()
        assert _sl(page) == mid, "fingerprint-cell click moved the horizontal scrollbar"

        assert_no_errors(collectors, "requirement scroll spectrum S1")
        assert_no_browser_issues(collectors.get("browser_issues"), "requirement scroll spectrum S1")
    finally:
        if pid:
            _sweep_project(pid)


def test_page_state_rides_the_url_and_survives_reload(page, collectors, api_client, uniq):
    """S2 (F1): filter/page/size/sort are rebuilt from the query on reload."""
    pid = None
    try:
        pid = make_project(api_client, uniq, "UR1")
        for i in range(1, 26):
            _mk_requirement(api_client, pid, f"URL谱需求{i:02d}_{uniq}")
        page.set_viewport_size({"width": 1440, "height": 900})
        _open_req_page(page, pid)
        expect(page.locator("[data-test='req-table'] tbody tr.el-table__row")).to_have_count(10, timeout=15000)

        # keyword filter -> URL carries it -> reload keeps input value + rows
        kw = f"URL谱需求20_{uniq}"
        page.locator("[data-test='req-filter-keyword']").fill(kw)
        page.locator("[data-test='req-filter-keyword']").press("Enter")
        expect(page).to_have_url(re.compile("keyword="), timeout=5000)
        expect(page.locator("[data-test='req-table'] tbody tr.el-table__row").first).to_contain_text("URL谱需求2", timeout=8000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator("[data-test='req-filter-keyword']")).to_have_value(kw, timeout=10000)

        # page 2 -> URL page=2 -> reload lands on page 2
        page.locator("[data-test='req-filter-keyword']").fill("")
        page.locator("[data-test='req-search']").click()
        expect(page.locator("[data-test='req-table'] tbody tr.el-table__row")).to_have_count(10, timeout=10000)
        page.locator("[data-test='req-pagination'] .el-pager li", has_text="3").click()
        expect(page).to_have_url(re.compile(r"[?&]page=3"), timeout=5000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator("[data-test='req-pagination'] .el-pager li.is-active")).to_have_text("3", timeout=10000)

        # size ladder -> URL size + remembered per user
        page.locator("[data-test='req-pagination'] .el-pagination__sizes").click()
        page.locator(".el-select-dropdown__item", has_text=re.compile(r"^20")).last.click()
        expect(page).to_have_url(re.compile(r"[?&]size=20"), timeout=5000)
        assert page.evaluate("localStorage.getItem('tp-reqs-page-size')") == "20"

        # header sort -> URL sortBy/sortDir -> reload replays the caret state
        # (anchor note: :data-test on el-table-column is a dead attribute in EP —
        # columns render no element; the sortable header is located by its label)
        page.locator("[data-test='req-table'] th.is-sortable", has_text="更新时间").first.click()
        expect(page).to_have_url(re.compile("sortBy=updated_at"), timeout=5000)
        page.reload(wait_until="domcontentloaded")
        th = page.locator("[data-test='req-table'] th.is-sortable", has_text="更新时间")
        expect(th).to_have_count(1, timeout=10000)
        active = th.evaluate("(el)=>el.className + '|' + [...el.querySelectorAll('span')].map(s=>s.className).join(',')")
        assert ("ascending" in active or "descending" in active), f"sort caret lost after reload: {active}"

        assert_no_errors(collectors, "requirement URL-state S2")
        assert_no_browser_issues(collectors.get("browser_issues"), "requirement URL-state S2")
    finally:
        if pid:
            _sweep_project(pid)
