"""Viewport-resident horizontal scrollbar spectrum .

Covers the site-wide enhancer installed by src/utils/tableViewportHScrollbar.ts
through the real stack (real browser + real backend, no mocks):

- worst-scale feed: >=30 real case rows so the table body crosses the viewport's
 lower edge (the exact situation the user complained about: the native bottom
 bar scrolls out of reach);
- the proxy bar floats while the body crosses that edge, with left/width equal
 to the real scroll container;
- dragging the proxy thumb drives the real container's scrollLeft at ANY scroll
 depth (page scrolled mid-table; at the page end where the native bar is
 reachable again the proxy hides — no duplicate);
- trough click and keyboard ArrowRight stay equivalent paths;
- Shift+wheel native horizontal scrolling still works (kept, not replaced);
- a page without any overflowing table renders zero visible proxies;
- three-zero-tolerance audit (4xx/5xx, console error/warn, browser Issues) runs
 last through the shared collectors assertion (conftest.assert_no_errors).
"""
from playwright.sync_api import expect

from conftest import (
    assert_no_errors,
    nav_to,
    pick_project_in_header,
    seed_cases,
    seed_project,
    teardown_extraction_case,
)

CASE_ROWS = 35
WRAP = '[data-test="case-table"] .el-table__body-wrapper .el-scrollbar__wrap'
VISIBLE_PROXY = ".tp-hviewport-bar:not([hidden])"
PROJECT_TAG = "HSV"


def _scroll_left(page):
    return page.evaluate(f"() => document.querySelector(`{WRAP}`).scrollLeft")


def _reset_scroll(page):
    page.evaluate(
        f"() => {{ document.querySelector(`{WRAP}`).scrollLeft = 0; }}",
    )
    page.wait_for_function(
        f"() => document.querySelector(`{WRAP}`).scrollLeft === 0", timeout=5000
    )


def _pin_crossing(page, row_index=10):
    """Scroll the document so the fold lands inside the table body.

    The app layout scrolls in an inner container, not on `window` — so the
    position is set with scrollIntoView on one real row: with the row centred,
    the body (35 rows tall) still crosses the viewport's lower edge, which is
    exactly the state where the native bar is unreachable and the proxy must
    float.
    """
    page.evaluate(
        f"""() => {{
            const rows = document.querySelectorAll(`[data-test="case-table"] tbody tr.el-table__row`);
            rows[{row_index}].scrollIntoView({{ block: 'center' }});
        }}"""
    )
    page.wait_for_timeout(150)  # rAF settle — not a Playwright-managed condition


def _proxy_mirrors_body(page):
    """True when the visible proxy's left/width equal the scroll container's box."""
    return page.evaluate(
        f"""() => {{
            const w = document.querySelector(`{WRAP}`).getBoundingClientRect();
            const p = document.querySelector('{VISIBLE_PROXY}').getBoundingClientRect();
            return Math.abs(w.left - p.left) <= 2 && Math.abs(w.width - p.width) <= 2;
        }}"""
    )


def test_wide_table_hscroll_reachable_at_any_depth(logged_in, api_client, collectors, uniq):
    page = logged_in

    # ── worst-scale fixture: one scratch project with 35 real rows ──
    pid = seed_project(api_client, uniq, PROJECT_TAG, display_name=f"横滚视口{uniq}")
    seed_cases(api_client, pid, f"横滚{uniq}", CASE_ROWS)
    project_name = f"横滚视口{uniq}"

    # Whole segment on one page (the body must grow past the viewport fold);
    # page size is read at component setup, so set it before mounting the page.
    page.evaluate("() => localStorage.setItem('tp-cases-page-size', '1000')")
    pick_project_in_header(page, project_name)
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(CASE_ROWS)

    # Precondition (hard, never a silent skip): the container really overflows.
    overflowing = page.evaluate(
        f"() => {{ const w = document.querySelector(`{WRAP}`);"
        " return w.scrollWidth - w.clientWidth > 4; }"
    )
    assert overflowing, "case table must overflow horizontally at the 1600px viewport"

    # ── proxy floats while the body crosses the viewport bottom ──
    _pin_crossing(page)
    proxy = page.locator(VISIBLE_PROXY).first
    expect(proxy).to_be_visible()
    assert _proxy_mirrors_body(page), "proxy geometry must mirror the overflowing body box"

    # ── native Shift+wheel horizontal path still works on the table ──
    # Aim inside the VISIBLE segment of the body: while pinned, the wrap's box
    # centre lies below the fold (off-viewport) and a wheel there would scroll
    # the page instead of the table.
    before = _scroll_left(page)
    spot = page.evaluate(
        f"""() => {{
            const r = document.querySelector(`{WRAP}`).getBoundingClientRect();
            const top = Math.max(r.top, 0), bottom = Math.min(r.bottom, window.innerHeight - 120);
            return {{ x: r.left + r.width / 2, y: (top + bottom) / 2 }};
        }}"""
    )
    page.mouse.move(spot["x"], spot["y"])
    page.keyboard.down("Shift")
    page.mouse.wheel(0, 300)
    page.keyboard.up("Shift")
    page.wait_for_function(
        f"() => document.querySelector(`{WRAP}`).scrollLeft > {before}", timeout=10000
    )

    # ── thumb drag drives the real container ──
    before = _scroll_left(page)
    thumb = page.locator(f"{VISIBLE_PROXY} .tp-hviewport-bar__thumb").first
    expect(thumb).to_be_visible()
    box = thumb.bounding_box()
    page.mouse.move(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
    page.mouse.down()
    page.mouse.move(box["x"] + box["width"] / 2 + 140, box["y"] + box["height"] / 2, steps=6)
    page.mouse.up()
    page.wait_for_function(
        f"() => document.querySelector(`{WRAP}`).scrollLeft > {before} + 40", timeout=10000
    )

    # ── trough click pages toward the pointer (native-like behaviour) ──
    _reset_scroll(page)
    proxy_box = proxy.bounding_box()
    page.mouse.click(proxy_box["x"] + proxy_box["width"] - 8, proxy_box["y"] + proxy_box["height"] / 2)
    page.wait_for_function(
        f"() => document.querySelector(`{WRAP}`).scrollLeft > 40", timeout=10000
    )

    # ── keyboard equivalence: focus the proxy, ArrowRight pages right ──
    _reset_scroll(page)
    proxy.focus()
    page.keyboard.press("ArrowRight")
    page.wait_for_function(
        f"() => document.querySelector(`{WRAP}`).scrollLeft > 40", timeout=10000
    )

    # ── page end: native bar becomes reachable → proxy hides (no duplicate) ──
    # Land the pagination bar (right below the body) at the viewport bottom:
    # the whole table including its native bar is then visible, so the proxy
    # must not render. (Inner-container layout — window.scrollTo is a no-op.)
    page.evaluate(
        "() => document.querySelector('[data-test=\"case-pagination\"]')"
        ".scrollIntoView({ block: 'end' })"
    )
    page.wait_for_timeout(150)  # rAF settle
    expect(page.locator(VISIBLE_PROXY)).to_have_count(0)

    # ── arbitrary depth again: a deeper mid-table crossing re-floats it ──
    _pin_crossing(page, row_index=24)
    expect(page.locator(VISIBLE_PROXY).first).to_be_visible()
    assert _proxy_mirrors_body(page), "proxy must keep mirroring the body at a deeper scroll position"

    # ── a page without overflowing tables renders zero visible proxies ──
    nav_to(page, "工作台", "/dashboard", '[data-test="my-project-table"]')
    expect(page.locator(VISIBLE_PROXY)).to_have_count(0)

    assert_no_errors(collectors, "viewport horizontal scrollbar spectrum")
    teardown_extraction_case(api_client, None, pid)
