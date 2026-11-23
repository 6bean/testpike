# -*- coding: utf-8 -*-
"""Device-journey honest guidance E2E (real browser + real admin chain, no mocks).

Covers the factory-profile gap: the reserve-device quick journey has no device
supply on a fresh install, so the card must stop the user from staring at a dead
0/2 step (three-element guidance: named reason + literal menu path + a working
auto-count way out), and registering a device through the guided path must move
the count without any page reload.

Assertions (strict-testing §3 five checks):
1. no-device profile: guide block present on the reserve-device card with all
   three elements visible, step marked "device required" (never fake-done);
2. first-visit overlay: the reserve-device entry carries the static hardware
   prerequisite tag;
3. guided way out is a true path: Device Wall -> register dialog -> save via the
   literal buttons the guidance names, then SPA-return to the dashboard shows
   the guide retired and the device step completed (count really moved);
4. teardown deletes the created device through the same API mirror;
5. network/console/Issues zero tolerance throughout (collectors + assert_no_errors).
"""
import pytest
from playwright.sync_api import expect
from conftest import BASE_URL, assert_no_errors

pytestmark = pytest.mark.timeout(300)


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} business failure: {body.get('msg')}"
    return body["data"]


def _probe_project_id(api_client):
    pass
    me = _ok(api_client.get("/users/me"), "读取当前用户")
    projects = me.get("projects") or []
    assert projects, "account has no visible project to pin the journey probe"
    return projects[0]["projectId"]


def _goto_dashboard(page):
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="journey-cards-row"]')).to_be_visible(timeout=15000)


def _inject_admin_session(page, *, keep_first_visit: bool = False):
    """Real admin login + token injection (conftest logged_in chronology).

    Land on the app origin first — localStorage is origin-bound, evaluating on
    about:blank raises SecurityError. keep_first_visit retires the dismissed
    flag *before* DashboardView mounts (the overlay decides on onMounted);
    the default marks the journey overlay as seen so it never overlays clicks."""
    from conftest import ADMIN_PASSWORD, api_login
    token = api_login("admin", ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    if keep_first_visit:
        page.evaluate("""() => {
          localStorage.setItem('tp_access_token', '%s');
          localStorage.removeItem('tp_onboarding_dismissed');
        }""" % token)
    else:
        page.evaluate("""() => {
          localStorage.setItem('tp_access_token', '%s');
          localStorage.setItem('tp_onboarding_dismissed', '1');
        }""" % token)
    return token


def test_device_journey_guidance_and_true_path_count_move(page, collectors, api_client, uniq):
    """No-device guidance + guided registration moves the count (no reload)."""
    pid0 = _probe_project_id(api_client)

    # Precondition guard: the pinned probe project must really carry no device,
    # otherwise this suite would be asserting a state the machine is not in.
    devices_before = _ok(api_client.get("/appauto/devices", params={"projectId": pid0}),
                         "读取设备清单")
    assert len(devices_before) == 0, \
        f"probe project {pid0} already has {len(devices_before)} devices; guidance precondition not on this machine"

    _inject_admin_session(page)
    _goto_dashboard(page)
    card = page.locator('[data-test="journey-card-reserve-device"]')
    guide = card.locator('[data-test="journey-device-guide"]')
    expect(guide).to_be_visible(timeout=10000)
    # Element 1: the reason names what is missing (never a bare 0/2 stare).
    expect(guide.locator(".guide-reason")).to_contain_text("当前项目未接入移动设备")
    # Element 2: the path line carries the literal menu names of the real route.
    path_line = guide.locator(".guide-path").inner_text()
    for literal in ("APP 自动化", "设备墙", "注册设备"):
        assert literal in path_line, f"guidance path missing literal menu name {literal}: {path_line}"
    # Element 3: the way-out line must not push manual work onto the user.
    way_out = guide.locator(".guide-ayout").inner_text()
    assert "自动更新" in way_out
    # Honest step tag: device required, never rendered as done.
    step = page.locator('[data-test="journey-step-need-device-reserve-device-devices"]')
    expect(step).to_be_visible()
    assert page.locator('[data-test="journey-step-reserve-device-devices"].step-done').count() == 0

    # ---- the guided way out is walkable: Device Wall -> Register Device ----
    page.goto(f"{BASE_URL}/#/appauto/devices?projectId={pid0}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="device-wall-page"]')).to_be_visible(timeout=15000)
    page.locator('[data-test="device-wall-create-btn"]').click()
    dialog = page.locator('[data-test="device-wall-dialog"]')
    expect(dialog).to_be_visible()
    dialog.locator('[data-test="device-wall-form-name"]').fill(f"指路验证机{uniq[:6]}")
    dialog.locator('[data-test="device-wall-form-serial"]').fill(f"GUIDE-{uniq[:10]}")
    dialog.locator('[data-test="device-wall-form-type"]').click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="模拟器").first.click()
    dialog.locator('[data-test="device-wall-dialog-save"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=10000)

    dev_rows = _ok(api_client.get("/appauto/devices", params={"projectId": pid0}), "读取设备清单(登记后)")
    assert len(dev_rows) == 1, "registration did not land exactly one device"
    dev_id = dev_rows[0]["id"]
    try:
        # ---- SPA return (no reload): guide retired + step really completed ----
        page.locator('[data-test="menu-dashboard"]').first.click()
        expect(page.locator('[data-test="journey-cards-row"]')).to_be_visible(timeout=15000)
        guide_after = page.locator('[data-test="journey-card-reserve-device"] [data-test="journey-device-guide"]')
        expect(guide_after).to_have_count(0, timeout=20000)
        expect(page.locator('[data-test="journey-step-reserve-device-devices"].step-done')).to_be_visible(timeout=5000)
        assert page.locator('[data-test="journey-step-need-device-reserve-device-devices"]').count() == 0
    finally:
        _ok(api_client.delete(f"/appauto/devices/{dev_id}"), "清理指路验证设备")

    assert_no_errors(collectors)


def test_first_visit_overlay_marks_device_prerequisite(page, collectors):
    """First-visit overlay: reserve-device entry carries the static hardware tag."""
    _inject_admin_session(page, keep_first_visit=True)
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    overlay = page.locator('[data-test="onboarding-overlay"]')
    expect(overlay).to_be_visible(timeout=15000)
    expect(overlay.locator('[data-test="onboarding-need-device-reserve-device"]')).to_be_visible()
    assert overlay.locator('[data-test^="onboarding-need-device-"]').count() == 1, \
        "the hardware tag belongs to the device journey only"
    overlay.locator('[data-test="onboarding-dismiss"]').click()
    page.wait_for_timeout(400)
    assert_no_errors(collectors)
