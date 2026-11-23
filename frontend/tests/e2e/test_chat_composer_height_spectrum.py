""" closing window — composer drag-height memory spectrum (16 §97).

Real chain browser -> admin 8081 -> core 8083, zero mocks:
1) dragging the grab strip upward grows the textarea rows (one row per
 21px), the oversized drag clamps at the governance ceiling
 ai.chat.composer-max-rows (factory 10), never past it;
2) the personal rows ride the per-user localStorage bucket and SURVIVE A
 RELOAD (the memory this package ships) — and double-click drops the
 bucket so the factory rows show again;
3) keyboard equivalence on the focused strip (X3: a drag is never the
 only path) — ArrowUp one row, ArrowDown folds to the floor;
4) WeChat-shape layout halves: the input owns a full-width row aligned
 with the thread, the action row underneath carries the photo door on
 the left and the send group on the right, and the pending-image queue
 shows the send-hint line.
Headless=new and the console/network/Issues zero-tolerance sweep ride
the shared conftest collectors.
"""
import base64

import pytest
from chat_spectrum_support import (
    BASE_URL,
    DOCK,
    INPUT,
    assert_no_errors,
    inject_token,
    purge_project,
    seed_project,
)
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(300)]

FAB = '[data-test="ai-chat-fab"]'
STRIP = '[data-test="chat-composer-resize"]'
BODY = '[data-test="chat-dock-body"]'
ACTION_LEFT = '[data-test="chat-action-group-left"]'
ACTIONS = '.chat-composer-actions'
IMAGE_INPUT = '[data-test="chat-image-input"]'
QUEUE_HINT = '[data-test="chat-image-queue-hint"]'

# 1x1 transparent PNG — the pick itself for the pending-image queue half.
TINY_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwAB"
    "BAEAXpTYlQAAAABJRU5ErkJggg=="
)


def _open_dock(page, url):
    inject_token(page)
    page.goto(url, wait_until="domcontentloaded")
    expect(page.locator(FAB)).to_be_visible(timeout=15000)
    page.locator(FAB).click()
    expect(page.locator(DOCK)).to_be_visible(timeout=10000)


def _rows(page):
    # Element Plus falls $attrs through onto the control itself: the
    # data-test lands ON the textarea, so INPUT is the textarea.
    return page.locator(INPUT).get_attribute("rows")


def _drag_strip_up(page, px):
    box = page.locator(STRIP).bounding_box()
    assert box, "the composer grab strip must have a box"
    x = box["x"] + box["width"] / 2
    y = box["y"] + box["height"] / 2
    page.mouse.move(x, y)
    page.mouse.down()
    page.mouse.move(x, y - px, steps=6)
    page.mouse.up()


def _rows_bucket_value(page):
    return page.evaluate(
        "() => {"
        "  const k = Object.keys(localStorage)"
        "    .find((x) => x.startsWith('tp_chat_composer_rows:'));"
        "  return k === undefined ? null : localStorage.getItem(k);"
        "}")


def test_composer_drag_height_memory_reload_bidirectional(page, collectors, api_client, uniq):
    """grow → clamp → reload restores → double-click resets → keyboard steps."""
    pid = seed_project(api_client, uniq, "COMPROW")
    try:
        _open_dock(page, f"{BASE_URL}/#/cases?projectId={pid}")

        # Factory shape on a clean profile: rows start at 3 (was a hard 2).
        # $attrs fall-through puts data-test on the textarea itself.
        ta = page.locator(INPUT)
        expect(ta).to_have_attribute("rows", "3", timeout=10000)

        # Drag 84px up: 84/21 = 4 rows grown → 7. The rows attribute is the
        # live state (it also drives the rendered height) — hard assertion,
        # no conditional wrapper (iron rule 5d).
        _drag_strip_up(page, 84)
        expect(ta).to_have_attribute("rows", "7", timeout=10000)

        # Clamp honesty: an oversized drag stops at the 10 ceiling, never past.
        _drag_strip_up(page, 600)
        expect(ta).to_have_attribute("rows", "10", timeout=10000)
        assert _rows_bucket_value(page) == "10", \
            "the grown rows must ride the per-user localStorage bucket"

        # Reload = the memory half: reopening restores the persisted rows.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(FAB)).to_be_visible(timeout=15000)
        page.locator(FAB).click()
        expect(page.locator(DOCK)).to_be_visible(timeout=10000)
        expect(page.locator(INPUT)).to_have_attribute(
            "rows", "10", timeout=10000)

        # Double-click the strip: bucket dropped, factory rows show again.
        page.locator(STRIP).dblclick()
        expect(page.locator(INPUT)).to_have_attribute(
            "rows", "3", timeout=10000)
        assert _rows_bucket_value(page) is None, \
            "double-click must clear the bucket (reset, not a written 3)"

        # Keyboard equivalence (X3): ArrowUp one row, ArrowDown folds to floor.
        page.locator(STRIP).focus()
        page.keyboard.press("ArrowUp")
        expect(page.locator(INPUT)).to_have_attribute(
            "rows", "4", timeout=10000)
        page.keyboard.press("ArrowDown")
        expect(page.locator(INPUT)).to_have_attribute(
            "rows", "3", timeout=10000)

        assert_no_errors(collectors, "composer drag-height memory")
    finally:
        purge_project(api_client, pid)


def test_composer_wechat_shape_fullwidth_with_queue_hint(page, collectors, api_client, uniq, tmp_path):
    """Input spans the thread width, action row below, queue hint in place."""
    pid = seed_project(api_client, uniq, "COMSHAPE")
    try:
        _open_dock(page, f"{BASE_URL}/#/cases?projectId={pid}")
        expect(page.locator(INPUT)).to_be_visible(timeout=10000)

        # Full-width row, one column: grab strip, textarea and action row
        # share the same width (S-17/D9 — the input owns a full-width row,
        # no button squeezed beside it). The thread body is compared via its
        # inner content width, not the box with its own scroll padding.
        body = page.locator(BODY)
        body_inner = body.evaluate(
            "(el) => el.clientWidth"
            " - parseFloat(getComputedStyle(el).paddingLeft)"
            " - parseFloat(getComputedStyle(el).paddingRight)")
        strip_box = page.locator(STRIP).bounding_box()
        input_box = page.locator(INPUT).bounding_box()
        assert strip_box and input_box, "strip and composer must both have boxes"
        assert strip_box["width"] >= input_box["width"] - 2, (
            "the grab strip must span at least the composer row "
            "(full-bleed affordance is by design)")
        assert abs(input_box["width"] - body_inner) <= 2, (
            f"composer must share the thread content width "
            f"(body inner {body_inner:.0f}px vs input {input_box['width']:.0f}px)")

        # Action row under the input: photo door group on the left, the send
        # group container on the right, never beside the textarea. (The send
        # BUTTON itself is the opt-in preference tp_chat_send_button — the
        # WeChat default sends on Enter — so the shape half binds the group
        # containers, which render unconditionally.)
        expect(page.locator(ACTION_LEFT)).to_be_visible()
        # The right group container is mounted; its buttons render with run
        # state (send is the opt-in preference, stop/steer only mid-run), so
        # the shape half asserts presence, not visibility, on an idle thread.
        expect(page.locator('[data-test="chat-action-group-right"]')).to_have_count(1)
        expect(page.locator('[data-test="chat-image-attach"]')).to_be_visible()
        actions = page.locator(ACTIONS)
        action_box = page.locator(ACTION_LEFT).bounding_box()
        actions_box = actions.bounding_box()
        assert action_box and actions_box, "action groups must have boxes"
        assert abs(actions_box["width"] - input_box["width"]) <= 2, (
            f"the action row spans the same column as the textarea "
            f"(actions {actions_box['width']:.0f}px vs input {input_box['width']:.0f}px)")
        assert action_box["y"] >= input_box["y"] + input_box["height"] - 1, \
            "the action row must sit BELOW the full-width input, not beside it"

        # Pending-image queue gains the send-hint line .
        png = tmp_path / "queue-hint-probe.png"
        png.write_bytes(TINY_PNG)
        page.locator(IMAGE_INPUT).set_input_files(str(png))
        expect(page.locator(QUEUE_HINT)).to_be_visible(timeout=15000)

        assert_no_errors(collectors, "composer WeChat-shape layout")
    finally:
        purge_project(api_client, pid)
