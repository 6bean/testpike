"""Dialog dragging must work on every dialog, including while the site
profile sidebar is open (user report: the create-profile dialog and, after
the drawer opens, every reachable dialog cannot be dragged).

The spectrum is also the live attribution probe the fix must be grounded
in (no coding from an unproven hypothesis):

1. reported flow - drawer open, create-profile dialog, drag its header:
   the dialog carries no per-page draggable flag, so before the global
   default lands this is the expected red,
2. overlay coexistence - with the drawer (a modal el-drawer) still open,
   a dialog that already declares draggable (global search, Ctrl+K) is
   opened on top and dragged: if this fails, the drawer's event layer
   really swallows pointer input and the fix must also address it; if it
   passes, the report's "everything stops dragging" reduces to the
   missing default and the fix converges on the single-source default
   flip.

Teardown closes the dialogs; no rows are created, so no data cleanup is
needed (the probe deliberately touches nothing that must be reclaimed).
"""
import pytest
from conftest import BASE_URL, assert_no_errors, nav_to, pick_project_in_header
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

DRAG_DX = 140
DRAG_DY = 90

# The ledger entry button is disabled until the header carries a project
# context, so the fixture switches the header onto the factory baseline
# project (present in every chain slot by the baseline gate) instead of
# assuming the session already has one.
BASELINE_PROJECT = "系统内置"


def _open_ledger_drawer(page):
    """Land on the web-element ledger, pick the header project context and open
    the site-profile drawer (the reported host surface)."""
    nav_to(page, "页面元素库", "/web-elements",
           "[data-test='web-element-ledger-page']", submenu="设计用例")
    pick_project_in_header(page, BASELINE_PROJECT)
    btn = page.locator("[data-test='ledger-profile-btn']")
    expect(btn).to_be_enabled(timeout=15000)
    btn.click()
    expect(page.locator("[data-test='profile-toolbar']")).to_be_visible()


def _drag_header(page, dialog_locator):
    """Grab the dialog header (away from the close button) and move it the
    standard drag delta; return the dialog's top-left displacement."""
    header = dialog_locator.locator(".el-dialog__header")
    expect(header).to_be_visible()
    before = dialog_locator.bounding_box()
    assert before, "dialog has no measurable box before the drag"
    header_box = header.bounding_box()
    start_x = before["x"] + 40
    start_y = header_box["y"] + header_box["height"] / 2
    page.mouse.move(start_x, start_y)
    page.mouse.down()
    page.mouse.move(start_x + DRAG_DX, start_y + DRAG_DY, steps=12)
    page.mouse.up()
    page.wait_for_timeout(350)
    after = dialog_locator.bounding_box()
    return before, after


def _assert_moved(before, after):
    dx = after["x"] - before["x"]
    dy = after["y"] - before["y"]
    assert dx > DRAG_DX * 0.5 and dy > DRAG_DY * 0.5, (
        f"dialog did not follow the header drag: moved dx={dx:.0f} dy={dy:.0f} "
        f"(dragged {DRAG_DX}x{DRAG_DY}); dragging must be a global dialog default"
    )


def test_site_profile_dialog_draggable_while_drawer_open(logged_in, collectors):
    """Reported flow: create-profile dialog inside the open site-profile
    sidebar must be draggable without any per-page flag."""
    page = logged_in
    _open_ledger_drawer(page)
    page.locator("[data-test='profile-create-btn']").click()
    dialog = page.locator("[data-test='profile-form'].el-dialog")
    expect(dialog).to_be_visible(timeout=5000)
    before, after = _drag_header(page, dialog)
    _assert_moved(before, after)
    page.keyboard.press("Escape")
    assert_no_errors(collectors, "dialog-drag-default")



def test_draggable_dialog_survives_overlay_from_drawer(logged_in, collectors):
    """Attribution probe: with the drawer modal open, a dialog that already
    declares draggable must still receive pointer events on its header and
    follow the drag. Proves whether the drawer swallows the event layer."""
    page = logged_in
    _open_ledger_drawer(page)
    page.keyboard.press("Control+k")
    dialog = page.locator("[data-test='global-search-dialog'].el-dialog")
    expect(dialog).to_be_visible(timeout=5000)
    header = dialog.locator(".el-dialog__header")
    expect(header).to_be_visible()
    before_box = dialog.bounding_box()
    hb = header.bounding_box()
    # Hit-test at the drag origin (not the header centre): the search dialog
    # keeps its input inside the header band, so the centre point legitimately
    # resolves to that input. What matters is that the point belongs to the
    # dialog itself — i.e. the drawer's modal layer never sits on top of it.
    probe_x = before_box["x"] + 40
    probe_y = hb["y"] + hb["height"] / 2
    owner = page.evaluate(
        "([x, y]) => { const el = document.elementFromPoint(x, y);"
        " return el ? !!el.closest('[data-test=\"global-search-dialog\"]')"
        " : false; }",
        [probe_x, probe_y])
    assert owner, (
        "drawer event layer covers the dialog header at the drag origin: "
        "elementFromPoint resolved outside the dialog")
    before, after = _drag_header(page, dialog)
    _assert_moved(before, after)
    page.keyboard.press("Escape")
    assert_no_errors(collectors, "dialog-drag-overlay")

