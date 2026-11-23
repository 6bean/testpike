"""Collaborative live-frame capture spectrum (Playwright real browser, real stack).

Capability under test (W5 stage-two B4 collaborative capture): in the run-detail
monitor window the operator switches to crop mode, drags a rectangle over the
live device frame, files it with a name plus storage scales, and the crop lands
in the element library as an IMAGE element carrying one variant per scale.

Path exercised for real (no mocks): browser crop gesture -> display-pixel to
frame-pixel mapping (utils/frameCropCapture) -> POST
/api/v1/appauto/elements/capture -> admin bridge -> core crop service (decode the
buffered frame, validate the region, render each scale, store the PNGs) ->
element profile -> paged element API and the element-library page.

Frame honesty: frames are booked through the executor's own intake shape into the
core Redis ring buffer (conftest frame fixtures, same posture as the monitor
window spectrum). No physical phone or scrcpy feed is attached to this slot, so
the producer leg (device -> frame) stays an open "await real device" account
; the consumer leg under test here runs end to end.

Zero tolerance: 4xx/5xx, console error/warning and the browser Issues panel are
asserted by assert_no_errors. Every rendering check is a hard assertion - no
conditional count guards that could pass vacuously.
"""
import contextlib
import json
import os

import httpx
import pytest
from conftest import (ADMIN_PASSWORD, API_BASE, BASE_URL, api_login,
                      assert_no_errors, cleanup_monitor_frames,
                      create_running_exec_task, monitor_frame_payload,
                      open_exec_task_detail, push_monitor_frames, sql_query)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(600)


def _auth_headers() -> dict:
    """Real login token for the API cross-checks (no fixture-side shortcuts)."""
    return {"Authorization": f"Bearer {api_login('admin', ADMIN_PASSWORD)}"}

# 320x240 JPEG with four solid colour quadrants (top-left red, top-right blue,
# bottom-left green, bottom-right yellow), produced once by Pillow at quality 60
# and embedded so the spectrum needs no image dependency at run time. Server-side
# ImageIO decodes it; the browser decode is asserted on naturalWidth below.
QUAD_JPEG_B64 = (
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA0JCgsKCA0LCgsODg0PEyAVExISEyccHhcgLikx"
    "MC4pLSwzOko+MzZGNywtQFdBRkxOUlNSMj5aYVpQYEpRUk//2wBDAQ4ODhMREyYVFSZPNS01"
    "T09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0//wAAR"
    "CADwAUADASIAAhEBAxEB/8QAFwABAQEBAAAAAAAAAAAAAAAAAAYFBP/EABQQAQAAAAAAAAAA"
    "AAAAAAAAAAD/xAAYAQEBAQEBAAAAAAAAAAAAAAAABgcEBf/EABwRAQABBAMAAAAAAAAAAAAA"
    "AADhEhZjohViof/aAAwDAQACEQMRAD8AxgHmrYAAAAAAAAAAAAAAAAAB1ALdlYAAAAAAAAAA"
    "AAAAAAADlARDVAAAAAAAAAAAAAAAAAAHUAt2VgAAAAAAAAAAAAAAAAAOUBENUAAAAAAAAAAA"
    "AAAAAAAdQC3ZWAAAAAAAAAAAAAAAAAA5QEQ1QAAAAAAAAAAAAAAAAAB1ALdlYAAAAAAAAAAA"
    "AAAAAADlARDVAAAAAAAAAAAAAAAAAAHUAt2VgAAAAAAAAAAAAAAAAAOUBENUAAAAAAAAAAAA"
    "AAAAAAdQC3ZWAAAAAAAAAAAAAAAAAA5QEQ1QAAAAAAAAAAAAAAAAAB1ALdlYAAAAAAAAAAAA"
    "AAAAADlGwImlT3Ti2hjjYCkunFtDHGwFJdOLaGONgKS6cW0McbAUl04toY42ApLpxbQxxsBS"
    "XTi2hjjYCkunFtDHGwFJdOLaGONgKS6cW0OEXI6roxbQ4uH7+ShhchdGLaDh+/koYXIXRi2g"
    "4fv5KGFyF0YtoOH7+ShhchdGLaDh+/koYXIXRi2g4fv5KGFyF0YtoOH7+ShhchdGLaDh+/ko"
    "YXIXRi2g4fv5KGFyF0YtoOH7+SlAHO8AAAAAAAAAAAAAAAAAABVgJxcgAAAAAAAAAAAAAAAA"
    "AJQBRoYAAAAAAAAAAAAAAAAABVgJxcgAAAAAAAAAAAAAAAAAJQBRoYAAAAAAAAAAAAAAAAAB"
    "VgJxcgAAAAAAAAAAAAAAAAAJQBRoYAAAAAAAAAAAAAAAAABVgJxcgAAAAAAAAAAAAAAAAAJQ"
    "BRoYAAAAAAAAAAAAAAAAABVgJxcgAAAAAAAAAAAAAAAAAJQBRoYAAAAAAAAAAAAAAAAABVgJ"
    "xcgAAAAAAAAAAAAAAAAAJQBRoYAAAAAAAAAAAAAAAAABVgJxcgAAAAAAAAAAAAAAAAAP/9k="
)

FRAME_WIDTH = 320
FRAME_HEIGHT = 240

# Crop rectangle expressed in frame pixels. The gesture is derived from these
# numbers, so the echo the dialog must show is known before the drag happens.
CROP_X, CROP_Y, CROP_WIDTH, CROP_HEIGHT = 10, 12, 90, 60

VISION_DIR = os.environ.get("TP_VISION_SHOT_DIR", "/tmp/tp-w5appphase2-vision")


def _shot(page, name: str) -> None:
    """Keep one screenshot per key state for the vision layout review."""
    os.makedirs(VISION_DIR, exist_ok=True)
    page.screenshot(path=f"{VISION_DIR}/{name}.png", full_page=True)


def _frame_point_to_client(box: dict, fx: float, fy: float) -> tuple:
    """Map frame pixels onto viewport pixels (CSS px, same basis as the box)."""
    return (box["left"] + fx * box["width"] / box["naturalWidth"],
            box["top"] + fy * box["height"] / box["naturalHeight"])


def _img_box(page, win) -> dict:
    """Live geometry of the rendered frame image (display size + decoded size)."""
    return page.evaluate(
        """() => {
          const el = document.querySelector('[data-test="monitor-frame"]');
          const r = el.getBoundingClientRect();
          return {left: r.left, top: r.top, width: r.width, height: r.height,
                  naturalWidth: el.naturalWidth, naturalHeight: el.naturalHeight};
        }""")


def _hit_target(page, x: float, y: float) -> dict:
    """Topmost element under a viewport point and whether the stage owns it.

    The crop handlers live on the monitor stage, so a press that lands anywhere
    else (floating toast, layout shifted after the crop hint appeared) is
    silently swallowed and never opens the card. The gesture records what the
    browser really hits before pressing, so a miss fails with numbers instead of
    a bare "card not found".
    """
    return page.evaluate(
        """([x, y]) => {
          const el = document.elementFromPoint(x, y);
          const stage = document.querySelector('[data-test="monitor-stage"]');
          return {
            tag: el ? el.tagName : null,
            cls: el ? String(el.className).slice(0, 120) : null,
            inStage: !!(el && stage && stage.contains(el)),
          };
        }""", [x, y])


def _crop_diagnostics(page) -> dict:
    """Stage/image geometry and visible transient UI at the moment of failure."""
    return page.evaluate(
        """() => {
          const stage = document.querySelector('[data-test="monitor-stage"]');
          const img = document.querySelector('[data-test="monitor-frame"]');
          const sr = stage ? stage.getBoundingClientRect() : null;
          const ir = img ? img.getBoundingClientRect() : null;
          return {
            stage: sr ? {left: sr.left, top: sr.top, width: sr.width, height: sr.height} : null,
            img: ir ? {left: ir.left, top: ir.top, width: ir.width, height: ir.height} : null,
            selectionVisible: !!document.querySelector('[data-test="crop-selection"]'),
            messages: [...document.querySelectorAll('.el-message')].map(e => e.textContent),
            cardTitlePresent: [...document.querySelectorAll('*')]
              .filter(e => e.childElementCount === 0 && e.textContent
                && e.textContent.includes('协同取图：存入元素库')).length,
          };
        }""")


def test_live_frame_crop_capture_files_image_element(page, api_client, collectors, uniq):
    """Crop a live frame in the monitor window and land it in the element library."""
    name = f"capture-{uniq[:8]}"
    proj_name = f"取图{uniq}"
    pid = None
    tid = None
    element_id = None
    try:
        pid = api_client.post("/projects", json={
            "name": proj_name, "code": f"CAP{uniq[:6].upper()}"}).json()["data"]
        if isinstance(pid, dict):
            pid = pid["id"]
        tid = create_running_exec_task(api_client, pid, uniq, "取图监看用例")

        dlg = open_exec_task_detail(page, proj_name, tid)
        win = dlg.locator('[data-test="exec-monitor-window"]')
        expect(win).to_be_visible(timeout=10000)

        # No frame yet: crop is unavailable and says why (honest disabled state).
        crop_btn = win.locator('[data-test="crop-toggle-btn"]')
        expect(crop_btn).to_be_disabled(timeout=10000)
        expect(win.locator('[data-test="crop-unavailable-hint"]')).to_contain_text(
            "等待设备出帧", timeout=10000)

        # Book one live frame through the executor's own intake shape.
        push_monitor_frames(tid, [monitor_frame_payload(tid, 1, QUAD_JPEG_B64)])
        frame = win.locator('[data-test="monitor-frame"]')
        expect(frame).to_be_visible(timeout=15000)
        box = _img_box(page, win)
        # Browser really decoded the frame (mapping math depends on it).
        assert box["naturalWidth"] == FRAME_WIDTH, f"frame decoded as {box}"
        assert box["naturalHeight"] == FRAME_HEIGHT, f"frame decoded as {box}"
        assert box["width"] > 0 and box["height"] > 0, f"frame not laid out: {box}"
        expect(crop_btn).to_be_enabled(timeout=5000)
        expect(win.locator('[data-test="crop-unavailable-hint"]')).to_have_count(0)

        # Degenerate selection is refused with a human message, no dialog, no call.
        expect(crop_btn).to_have_count(1)
        crop_btn.click()
        expect(win.locator('[data-test="crop-mode-hint"]')).to_be_visible(timeout=5000)
        _shot(page, "01-monitor-crop-mode")
        # Geometry is resampled now that crop mode rendered: entering crop mode
        # reflows the window, and a box measured before the toggle is no longer
        # where the pointer lands (a press outside the stage is silently dropped).
        box = _img_box(page, win)
        assert box["width"] > 0 and box["height"] > 0, f"frame lost layout after toggle: {box}"
        tap_x, tap_y = _frame_point_to_client(box, 40, 40)
        hit = _hit_target(page, tap_x, tap_y)
        assert hit["inStage"], f"tap point misses the monitor stage: hit={hit} box={box}"
        page.mouse.move(tap_x, tap_y)
        page.mouse.down()
        page.mouse.up()
        expect(page.locator(".el-message")).to_contain_text("框选区域太小", timeout=5000)
        # No card: the title text is absent (data-test on el-dialog never renders,
        # so counting that anchor would pass vacuously).
        expect(page.get_by_text("协同取图：存入元素库")).to_have_count(0)
        # The toast floats above the window: the next gesture waits until it is gone,
        # so the press is recorded against the stage and not against the transient.
        expect(page.locator(".el-message")).to_have_count(0, timeout=10000)

        # Real selection: frame pixels (10,12) -> (100,72), mapped from fresh geometry.
        box = _img_box(page, win)
        start = _frame_point_to_client(box, CROP_X, CROP_Y)
        end = _frame_point_to_client(box, CROP_X + CROP_WIDTH, CROP_Y + CROP_HEIGHT)
        for point, role in ((start, "press"), (end, "release")):
            hit = _hit_target(page, point[0], point[1])
            assert hit["inStage"], f"{role} point misses the monitor stage: hit={hit} box={box}"
        page.mouse.move(start[0], start[1])
        page.mouse.down()
        page.mouse.move(end[0], end[1], steps=8)
        page.mouse.up()

        # The registration card opened. el-dialog does not forward data-test to
        # the DOM, so the card is pinned by its title text and by the anchors that
        # do render (echo / name / scales / save button live on inner elements).
        try:
            expect(page.get_by_text("协同取图：存入元素库")).to_be_visible(timeout=10000)
        except AssertionError as exc:
            raise AssertionError(
                f"crop card never opened: press={start} release={end} box={box} "
                f"diagnostics={_crop_diagnostics(page)}") from exc
        region_echo = page.locator('[data-test="crop-region-echo"]')
        expect(region_echo).to_be_visible(timeout=5000)
        expect(region_echo).to_contain_text(
            f"{CROP_X}, {CROP_Y} · {CROP_WIDTH}×{CROP_HEIGHT}", timeout=5000)

        # Save button stays disabled while the element name is empty.
        save_btn = page.locator('[data-test="crop-save-btn"]')
        expect(save_btn).to_be_disabled()
        page.locator('[data-test="crop-name-input"]').fill(name)
        scales = page.locator('[data-test="crop-scale-group"] .el-checkbox')
        expect(scales).to_have_count(2)
        scales.first.click()
        scales.nth(1).click()
        _shot(page, "02-capture-dialog")
        expect(save_btn).to_be_enabled(timeout=5000)

        with page.expect_response(
                lambda r: "/appauto/elements/capture" in r.url, timeout=30000) as landed:
            save_btn.click()
        body = landed.value.json()
        assert landed.value.status == 200 and body.get("code") == "0", \
            f"capture rejected: status={landed.value.status} body={str(body)[:400]}"
        element_id = body["data"]
        assert isinstance(element_id, int) and element_id > 0, f"no element id: {body}"
        expect(page.locator(".el-message")).to_contain_text(name, timeout=10000)

        # Element library page shows the filed profile: IMAGE type, two variants.
        page.goto(f"{BASE_URL}/#/appauto/elements?projectId={pid}&keyword={name}",
                  wait_until="domcontentloaded")
        table = page.locator('[data-test="element-table"]')
        expect(table).to_be_visible(timeout=15000)
        row = table.locator("tbody tr").filter(has_text=name)
        expect(row).to_have_count(1, timeout=15000)
        expect(row.locator('[data-test="element-type"]')).to_contain_text("IMAGE")
        expect(row.locator('[data-test="element-variants"]')).to_contain_text("2")
        _shot(page, "03-element-library")

        # Server-side truth: the profile carries one variant per requested scale,
        # at the cropped size and its 2x enlargement.
        headers = _auth_headers()
        detail = httpx.get(f"{API_BASE}/appauto/elements/{element_id}",
                           headers=headers, timeout=10)
        assert detail.status_code == 200, f"element read-back failed: {detail.text[:300]}"
        profile = detail.json()["data"]
        variants = profile["variants"]
        if isinstance(variants, str):
            variants = json.loads(variants)
        # Compare (width, height) numerically: sorting the "WxH" strings would put
        # "180x120" before "90x60" and make a correct archive look wrong.
        res_pairs = sorted(tuple(int(p) for p in str(v.get("resolution")).split("x"))
                           for v in variants)
        assert res_pairs == [(CROP_WIDTH, CROP_HEIGHT), (CROP_WIDTH * 2, CROP_HEIGHT * 2)], \
            f"variant resolutions: {res_pairs}"
        assert profile["locatorType"] == "IMAGE", f"profile type: {profile}"

        assert_no_errors(collectors, "协同取图真链 E2E")
    finally:
        if element_id is not None:
            with contextlib.suppress(Exception):
                httpx.delete(f"{API_BASE}/appauto/elements/{element_id}",
                             headers=_auth_headers(), timeout=10)
        if tid is not None:
            cleanup_monitor_frames(tid)
            sql_query(f"DELETE FROM tp_exec_task WHERE id = {tid}")
        if pid is not None:
            with contextlib.suppress(Exception):
                api_client.delete(f"/projects/{pid}/purge?force=true")
