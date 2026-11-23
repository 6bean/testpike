
import time
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors, sql_query

pytestmark = pytest.mark.timeout(300)

API8082 = "http://127.0.0.1:8082"


def inject_token(page):
    pass
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page, token
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def register_executor(name, capabilities=None):
    payload = {
        "name": name, "endTypes": "web", "browsers": "chromium",
        "capacity": 2, "version": "1.0.0", "runningTasks": 0,
    }
    if capabilities is not None:
        payload["capabilities"] = capabilities
    r = httpx.post(f"{API8082}/internal/executors/register", json=payload, timeout=15)
    assert r.status_code == 200 and r.json().get("code") in (0, "0"), r.text[:200]
    data = r.json()["data"]
    return int(data["executorId"] if isinstance(data, dict) else data)


def open_self_check(page, executor_name):
    card = page.locator('[data-test="executor-card"]', has_text=executor_name).first
    expect(card).to_be_visible(timeout=15000)
    card.locator('[data-test="executor-self-check-btn"]').click()
    drawer = page.locator('[data-test="self-check-drawer"]')
    expect(drawer).to_be_visible(timeout=10000)
    return drawer


def cleanup(names):
    ids = ",".join(f"'{n}'" for n in names)
    sql_query(f"DELETE FROM tp_cluster_component WHERE node_id IN (SELECT id::text FROM tp_executor WHERE name IN ({ids}))")
    sql_query(f"DELETE FROM tp_executor WHERE name IN ({ids})")


def test_t345b_self_check_report_one_screen(page, collectors):
    pass
    page, _token = inject_token(page)
    suffix = uuid.uuid4().hex[:6]
    full = f"e2e-t345b-{suffix}"
    bare = f"e2e-t345b-bare-{suffix}"
    caps = {
        "hostOs": f"E2ET345B{suffix}",
        "tools": {"adb": True, "appium": False},
        "runtimes": {"node": "22.14.0"},
        "engines": ["MOBILEWRIGHT"],
        "platforms": ["ANDROID"],
        "deviceKinds": ["EMULATOR"],
    }
    try:
        register_executor(full, caps)
        register_executor(bare)

        page.goto(f"{BASE_URL}#/execution", wait_until="domcontentloaded")
        expect(page.locator('[data-test="execution-root"]')).to_be_visible(timeout=10000)

        drawer = open_self_check(page, full)
        head = drawer.locator('[data-test="self-check-head"]')
        expect(head).to_contain_text(full)
        expect(head).to_contain_text("在线")
        body = drawer.locator('[data-test="self-check-tools"]')
        expect(body).to_be_visible()
        expect(drawer.locator('[data-test="self-check-tool-adb"]')).to_be_visible()
        expect(drawer.locator('[data-test="self-check-tool-appium"]')).to_be_visible()
        expect(drawer.locator('[data-test="self-check-missing"]')).to_contain_text("appium")
        expect(drawer.locator('[data-test="self-check-runtimes"]')).to_contain_text("22.14.0")
        expect(drawer.locator('[data-test="self-check-engines"]')).to_contain_text("MOBILEWRIGHT")
        expect(drawer.locator('[data-test="self-check-platforms"]')).to_contain_text("ANDROID")
        expect(drawer.locator('[data-test="self-check-device-kinds"]')).to_contain_text("EMULATOR")
        dc = drawer.locator('[data-test="self-check-device-count"]')
        expect(dc).to_contain_text("0")
        expect(dc).not_to_contain_text("归属不可辨")
        expect(drawer.locator('[data-test="self-check-components"]')).to_contain_text("暂无部件版本记录")

        page.keyboard.press("Escape")
        drawer2 = open_self_check(page, bare)
        expect(drawer2.locator('[data-test="self-check-not-reported"]')).to_be_visible()
        expect(drawer2.locator('[data-test="self-check-tools"]')).not_to_be_visible()

        page.keyboard.press("Escape")
        page.goto(f"{BASE_URL}/#/execution", wait_until="domcontentloaded")
        drawer3 = open_self_check(page, full)
        expect(drawer3.locator('[data-test="self-check-head"]')).to_contain_text(full)
        expect(drawer3.locator('[data-test="self-check-engines"]')).to_contain_text("MOBILEWRIGHT")

        page.keyboard.press("Escape")
        cleanup([full, bare])
        page.locator('[data-test="executor-refresh"]').first.click()
        gone = page.locator('[data-test="executor-card"]', has_text=full)
        expect(gone).to_have_count(0, timeout=15000)

        assert_no_errors(collectors, "T345b 自检报告一屏")
    finally:
        cleanup([full, bare])
