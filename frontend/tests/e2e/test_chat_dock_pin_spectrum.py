
import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    purge_project,
    seed_project,
)
from chat_spectrum_support import (
    DOCK,
    INPUT,
)
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(300)]

PIN = '[data-test="chat-dock-pin"]'
CLOSE = '[data-test="chat-dock-close"]'
HISTORY_VIEW = '[data-test="chat-history-view"]'
EMPTY_LEDGER = '[data-test="session-empty"]'
EMPTY_NEW = '[data-test="session-empty-new"]'


def _open_dock(page, url):
    inject_token(page)
    page.goto(url, wait_until="domcontentloaded")
    expect(page.locator('[data-test="ai-chat-fab"]')).to_be_visible(timeout=15000)
    page.locator('[data-test="ai-chat-fab"]').click()
    expect(page.locator(DOCK)).to_be_visible(timeout=10000)


def test_dock_pin_two_states_bidirectional(page, collectors, api_client, uniq):
    """Light dock collapses outside (regression); pinned dock survives, Esc/X
    collapse it, and the pin survives a reload."""
    pid = seed_project(api_client, uniq, "DOCKPIN")
    try:
        _open_dock(page, f"{BASE_URL}/#/cases?projectId={pid}")

        # 1) un-pinned default: a page-body pointerdown collapses (R33 zero drift)
        page.mouse.click(300, 500)
        expect(page.locator(DOCK)).not_to_be_visible(timeout=10000)

        # 2) pin, then a body click must NOT collapse
        page.locator('[data-test="ai-chat-fab"]').click()
        expect(page.locator(DOCK)).to_be_visible(timeout=10000)
        expect(page.locator(PIN)).to_have_attribute('aria-pressed', 'false', timeout=5000)
        page.locator(PIN).click()
        expect(page.locator(PIN)).to_have_attribute('aria-pressed', 'true')
        page.mouse.click(300, 500)
        page.wait_for_timeout(400)
        expect(page.locator(DOCK)).to_be_visible(timeout=5000)

        # 3) Esc is the keyboard equivalent of the close button (D6): no modal
        # under the keypress, the dock answers it.
        page.keyboard.press('Escape')
        expect(page.locator(DOCK)).not_to_be_visible(timeout=10000)

        # 4) the pin rode localStorage: reopening keeps the resident form
        # (a body click still does not collapse) and the close X collapses
        # without un-pinning.
        page.locator('[data-test="ai-chat-fab"]').click()
        expect(page.locator(DOCK)).to_be_visible(timeout=10000)
        expect(page.locator(PIN)).to_have_attribute('aria-pressed', 'true', timeout=5000)
        page.mouse.click(300, 500)
        page.wait_for_timeout(400)
        expect(page.locator(DOCK)).to_be_visible(timeout=5000)
        page.locator(CLOSE).click()
        expect(page.locator(DOCK)).not_to_be_visible(timeout=10000)

        # 5) explicit reload: still the pinned form after reopening.
        page.reload(wait_until="domcontentloaded")
        _open_dock(page, f"{BASE_URL}/#/cases?projectId={pid}")
        expect(page.locator(PIN)).to_have_attribute('aria-pressed', 'true', timeout=5000)

        # 6) unpin returns the light dock: outside click collapses again.
        page.locator(PIN).click()
        expect(page.locator(PIN)).to_have_attribute('aria-pressed', 'false')
        page.mouse.click(300, 500)
        expect(page.locator(DOCK)).not_to_be_visible(timeout=10000)

        assert_no_errors(collectors, "dock pin two states")
    finally:
        purge_project(api_client, pid)


def test_empty_ledger_direct_new_lands_on_composer(page, collectors, api_client, uniq):
    """Fresh segment shows the hint plus ONE direct New action: a single
 activation leaves the ledger view with the composer mounted (revised
 click ledger — no history hop, no second view in between)."""
    pid = seed_project(api_client, uniq, "DOCKNEW")
    try:
        _open_dock(page, f"{BASE_URL}/#/cases?projectId={pid}")

        # Open the ledger view; the fresh project answers with the honest
        # empty state (server-side nothing booked).
        page.locator('[data-test="chat-dock-history"]').click()
        expect(page.locator(HISTORY_VIEW)).to_be_visible(timeout=10000)
        expect(page.locator(EMPTY_LEDGER)).to_be_visible(timeout=10000)
        expect(page.locator(EMPTY_NEW)).to_be_visible(timeout=5000)

        # ONE activation lands the thread view with the composer visible.
        page.locator(EMPTY_NEW).click()
        expect(page.locator(HISTORY_VIEW)).to_have_count(0, timeout=10000)
        expect(page.locator(INPUT)).to_be_visible(timeout=10000)

        # The composer focus is the keyboard path to the first question; the
        # header keeps the primary-position New button (single door source).
        expect(page.locator('[data-test="chat-dock-new"]')).to_be_visible(timeout=5000)

        assert_no_errors(collectors, "empty ledger direct new")
    finally:
        purge_project(api_client, pid)


def test_resident_dock_shrinks_the_page_measured(page, collectors, api_client, uniq):
    pass
    pid = seed_project(api_client, uniq, "DOCKSQ")
    try:
        _open_dock(page, f"{BASE_URL}/#/cases?projectId={pid}")
        expect(page.locator('[data-test="case-page"]')).to_be_visible(timeout=15000)
        page.locator(PIN).click()
        expect(page.locator(PIN)).to_have_attribute('aria-pressed', 'true')

        # The root marker and the published offset are the single-sourced pair
        # (chatDockLayout judge -> --tp-chat-dock-overlay-offset).
        squeeze = page.evaluate(
            "() => ({ resident: document.documentElement.classList.contains('dock-resident'),"
            " offset: getComputedStyle(document.documentElement)"
            ".getPropertyValue('--tp-chat-dock-overlay-offset').trim() })"
        )
        assert squeeze['resident'] is True, 'pinned dock must publish the resident root class'
        assert squeeze['offset'].endswith('px') and float(squeeze['offset'][:-2]) > 0, \
            f'resident form must publish a positive gutter offset, got {squeeze["offset"]}'

        dock_box = page.locator(DOCK).bounding_box()
        table_box = page.locator('[data-test="case-page"] .el-table').first.bounding_box()
        assert dock_box and table_box
        assert table_box['x'] + table_box['width'] <= dock_box['x'] + 1, (
            f"criterion 1 broken: the wide table still runs under the resident dock "
            f"(table right {table_box['x'] + table_box['width']:.0f} > dock left {dock_box['x']:.0f})")

        # Criterion 2 (dialog tier): a centered dialog must re-center into the
        # visible page instead of sitting half under the dock.
        page.locator('[data-test="case-create"]').click()
        dialog = page.locator('[data-test="case-create-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        dialog_box = dialog.bounding_box()
        assert dialog_box, 'create dialog never measured'
        assert dialog_box['x'] + dialog_box['width'] <= dock_box['x'] + 1, (
            f"criterion 2 broken: dialog right edge {dialog_box['x'] + dialog_box['width']:.0f} "
            f"crosses the dock left edge {dock_box['x']:.0f}")
        page.keyboard.press('Escape')
        expect(dialog).not_to_be_visible(timeout=10000)

        # Un-pinning drops both the marker and the offset (light form restored,
        # byte-identical defaults for every other layer).
        page.locator(PIN).click()
        expect(page.locator(PIN)).to_have_attribute('aria-pressed', 'false')
        relaxed = page.evaluate(
            "() => ({ resident: document.documentElement.classList.contains('dock-resident'),"
            " offset: getComputedStyle(document.documentElement)"
            ".getPropertyValue('--tp-chat-dock-overlay-offset').trim() })"
        )
        assert relaxed['resident'] is False, 'un-pinned form must drop the resident marker'
        assert relaxed['offset'] in ('0px', ''), f'light form must keep the zero offset, got {relaxed["offset"]}'

        assert_no_errors(collectors, 'resident dock shrink measured')
    finally:
        purge_project(api_client, pid)
