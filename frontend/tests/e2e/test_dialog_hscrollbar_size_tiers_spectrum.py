"""Dialog scale tiers + dialog-resident horizontal scrollbar spectrum.

Drives the real stack (real browser + real backend, no mocks) over the two
capabilities landed by this batch:

- tier hit: the step-editor dialog (case editor) renders the xl tier from the
  theme single source — width == min(1440px, 92vw), the dialog never exceeds
  85vh, and its body owns the vertical scroll (overflow-y auto);
- dialog/drawer surface: a table inside a 640px drawer overflows horizontally
  (min column sum ~710px) while its body crosses the drawer body's lower edge;
  the shared enhancer (src/utils/tableViewportHScrollbar.ts, dialog mode)
  floats a proxy bar pinned to that inner edge — dragging it, clicking the
  trough or pressing ArrowRight all drive the real scroll container at any
  scroll depth, and once the native bar becomes reachable (body scrolled to
  the end) the proxy hides with no duplicate.

The three-zero-tolerance audit (4xx/5xx, console error/warn, browser Issues)
runs last through the shared collector assertion (conftest.assert_no_errors).
"""
from playwright.sync_api import expect

from conftest import (
    assert_no_errors,
    nav_to,
    pick_project_in_header,
    purge_project,
    seed_cases,
    seed_project,
    sql_query,
)

from script_seed import base_doc, open_detail, open_tree_dialog, read_editor_tier_facts, save_doc

CASE_ROWS = 35
RECYCLE_WRAP = '[data-test="recycle-table"] .el-table__body-wrapper .el-scrollbar__wrap'
DRAWER_BODY = ".el-drawer__body"
VISIBLE_DIALOG_PROXY = ".tp-hviewport-bar--dialog:not([hidden])"


def _case_ids(api_client, pid, limit):
    rows = sql_query(
        f"SELECT id FROM tp_test_case WHERE project_id={pid} ORDER BY id LIMIT {limit}"
    )
    return [int(line) for line in rows.strip().splitlines() if line.strip()]


def test_case_editor_dialog_hits_xl_tier(logged_in, api_client, collectors, uniq):
    """Step-editor dialog = xl tier: 92vw-capped width, 85vh cap, body scroll."""
    page = logged_in
    project_name = f"DialogTiers-{uniq}"
    pid = seed_project(api_client, uniq, "DT", display_name=project_name)
    seed_cases(api_client, pid, f"tier-{uniq}", 3)
    case_id = _case_ids(api_client, pid, 1)[0]
    # The tree-editor entry button only renders once the case owns a script
    # (CaseDetailView renders it in the script branch), so land one through
    # the shared seeding helper before opening the detail page.
    save_doc(case_id, base_doc(case_id, uniq))

    pick_project_in_header(page, project_name)
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    open_detail(page, case_id)
    open_tree_dialog(page)

    measured = read_editor_tier_facts(page)
    assert measured["hasTier"], "case editor dialog must carry the xl tier class (theme single source)"
    assert abs(measured["width"] - measured["expectedWidth"]) <= 2, (
        f"xl tier width must equal min(1440, 92vw): got {measured['width']} "
        f"expected {measured['expectedWidth']}"
    )
    assert measured["height"] <= measured["cap"], (
        f"dialog height must stay within 85vh: got {measured['height']} cap {measured['cap']}"
    )
    assert measured["overflowY"] in {"auto", "scroll"}, (
        f"dialog body must own the vertical scroll, got overflow-y={measured['overflowY']}"
    )

    page.keyboard.press("Escape")
    assert_no_errors(collectors, "dialog xl tier spectrum")
    purge_project(api_client, pid)


def test_dialog_inner_table_scrollbar_pinned_and_draggable(
    logged_in, api_client, collectors, uniq
):
    """Recycle drawer (640px) table overflows: proxy pins to the inner edge."""
    page = logged_in
    project_name = f"DialogScroll-{uniq}"
    pid = seed_project(api_client, uniq, "DS", display_name=project_name)
    seed_cases(api_client, pid, f"recycle-{uniq}", CASE_ROWS)
    for cid in _case_ids(api_client, pid, CASE_ROWS):
        r = api_client.delete(f"/cases/{cid}")
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:200]

    # The drawer lists 10 rows per page (fixed size ref); a shorter viewport
    # makes the table body cross the drawer body's lower edge — the exact
    # state where the native bar falls out of reach and the proxy must float.
    page.set_viewport_size({"width": 1280, "height": 560})
    pick_project_in_header(page, project_name)
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    page.locator('[data-test="case-recycle-open"]').click()
    drawer = page.locator('[data-test="case-recycle-drawer"]')
    expect(drawer).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="recycle-table"] tbody tr.el-table__row')).to_have_count(10)

    # The drawer slides in through a transform animation (~300ms): measuring
    # mid-slide would compare a moving wrap box against a proxy one frame
    # behind. Wait until the wrap box is at rest (three consecutive rAF
    # frames with no horizontal shift), then every geometry assertion
    # describes the resting state the enhancer re-pins exactly on settle.
    page.wait_for_function(
        f"""() => new Promise((resolve) => {{
            const el = document.querySelector('{RECYCLE_WRAP}');
            let prev = null;
            let stable = 0;
            const tick = () => {{
                const left = el.getBoundingClientRect().left;
                stable = prev !== null && Math.abs(left - prev) < 0.5 ? stable + 1 : 0;
                prev = left;
                if (stable >= 3) resolve(true);
                else requestAnimationFrame(tick);
            }};
            requestAnimationFrame(tick);
        }})""",
        timeout=10000,
    )

    # Hard preconditions (never a silent skip): horizontal overflow exists and
    # the body box really crosses the drawer body's lower edge.
    pre = page.evaluate(
        f"""() => {{
            const w = document.querySelector('{RECYCLE_WRAP}');
            const b = document.querySelector('{DRAWER_BODY}');
            return {{
                overflow: w.scrollWidth - w.clientWidth,
                crossing: w.getBoundingClientRect().bottom > b.getBoundingClientRect().bottom,
                started: w.getBoundingClientRect().top < b.getBoundingClientRect().bottom,
            }};
        }}"""
    )
    assert pre["overflow"] > 4, "recycle table must overflow horizontally inside the 640px drawer"
    assert pre["crossing"] and pre["started"], (
        f"table body must cross the drawer body's lower edge at 1280x560: {pre}"
    )

    proxy = page.locator(VISIBLE_DIALOG_PROXY).first
    expect(proxy).to_be_visible(timeout=5000)

    def _geometry():
        return page.evaluate(
            f"""() => {{
                const w = document.querySelector('{RECYCLE_WRAP}').getBoundingClientRect();
                const b = document.querySelector('{DRAWER_BODY}').getBoundingClientRect();
                const p = document.querySelector('{VISIBLE_DIALOG_PROXY}');
                const pr = p ? p.getBoundingClientRect() : null;
                return {{ wrap: w, body: b, proxy: pr }};
            }}"""
        )

    geo = _geometry()
    assert abs(geo["wrap"]["left"] - geo["proxy"]["left"]) <= 2, "proxy must mirror the wrap box (left)"
    assert abs(geo["wrap"]["width"] - geo["proxy"]["width"]) <= 2, "proxy must mirror the wrap box (width)"
    gap = geo["body"]["bottom"] - geo["proxy"]["bottom"]
    assert 0 <= gap <= 12, f"proxy bottom must pin beside the drawer body edge, gap={gap}"

    # Arbitrary depth: scrolling the drawer body further keeps the pin alive.
    page.evaluate(f"() => {{ document.querySelector('{DRAWER_BODY}').scrollTop += 60; }}")
    page.wait_for_timeout(150)  # rAF settle — not a Playwright-managed condition
    expect(page.locator(VISIBLE_DIALOG_PROXY).first).to_be_visible()
    geo = _geometry()
    assert abs(geo["wrap"]["left"] - geo["proxy"]["left"]) <= 2, "pin must follow the scrolled wrap"

    # Thumb drag drives the real container.
    before = page.evaluate(f"() => document.querySelector('{RECYCLE_WRAP}').scrollLeft")
    thumb = page.locator(f"{VISIBLE_DIALOG_PROXY} .tp-hviewport-bar__thumb").first
    expect(thumb).to_be_visible()
    box = thumb.bounding_box()
    page.mouse.move(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
    page.mouse.down()
    page.mouse.move(box["x"] + box["width"] / 2 + 140, box["y"] + box["height"] / 2, steps=6)
    page.mouse.up()
    page.wait_for_function(
        f"() => document.querySelector('{RECYCLE_WRAP}').scrollLeft > {before} + 40",
        timeout=10000,
    )

    # Trough click pages toward the pointer (native-like behaviour).
    page.evaluate(f"() => document.querySelector('{RECYCLE_WRAP}').scrollLeft = 0")
    proxy_box = page.locator(VISIBLE_DIALOG_PROXY).first.bounding_box()
    page.mouse.click(
        proxy_box["x"] + proxy_box["width"] - 8, proxy_box["y"] + proxy_box["height"] / 2
    )
    page.wait_for_function(
        f"() => document.querySelector('{RECYCLE_WRAP}').scrollLeft > 40", timeout=10000
    )

    # Keyboard equivalence: focus the proxy, ArrowRight pages right.
    page.evaluate(f"() => document.querySelector('{RECYCLE_WRAP}').scrollLeft = 0")
    page.locator(VISIBLE_DIALOG_PROXY).first.focus()
    page.keyboard.press("ArrowRight")
    page.wait_for_function(
        f"() => document.querySelector('{RECYCLE_WRAP}').scrollLeft > 40", timeout=10000
    )

    # End of the drawer body: the native bar becomes reachable → proxy hides
    # (no duplicate control path on the same table).
    page.evaluate(
        f"() => {{ const b = document.querySelector('{DRAWER_BODY}');"
        " b.scrollTop = b.scrollHeight; }"
    )
    page.wait_for_timeout(150)  # rAF settle
    expect(page.locator(VISIBLE_DIALOG_PROXY)).to_have_count(0)

    page.keyboard.press("Escape")
    assert_no_errors(collectors, "dialog-resident scrollbar spectrum")
    purge_project(api_client, pid)
