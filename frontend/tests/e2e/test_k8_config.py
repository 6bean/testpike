
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import ADMIN_PASSWORD, ADMIN_USERNAME, API_BASE, BASE_URL, assert_no_errors, nav_to



def _login(username: str, password: str) -> str | None:
    r = httpx.post(f"{API_BASE}/auth/login", json={"username": username, "password": password}, timeout=10)
    if r.status_code != 200:
        return None
    body = r.json()
    if body.get("code") != "0":
        return None
    return body["data"]["accessToken"]


def _mk_config(api, key, value, group="系统", vtype="string", encrypt=0, desc="E2E 测试配置"):
    r = api.post("/configs", json={
        "configKey": key, "configValue": value, "groupName": group,
        "valueType": vtype, "encryptFlag": encrypt, "description": desc,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建配置失败: {r.text[:300]}"
    return r.json()["data"]


def _cleanup(api, keys):
    pass
    r = api.get("/configs")
    for x in r.json().get("data", []):
        if x.get("configKey") in keys:
            api.delete(f"/configs/{x['id']}")



def test_k8_config_crud(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    key = f"e2e.k8.plain.{uniq}"

    try:
        nav_to(page, "系统配置", "/configs", '[data-test="config-page"]', submenu="系统管理")

        rows = page.locator('[data-test="config-table"] tbody tr')
        expect(rows.first).to_be_visible()
        expect(page.locator('code:text-is("retention.video-days")').first).to_be_visible(timeout=10000)
        assert rows.count() >= 10, f"种子配置应 ≥10 条, got {rows.count()}"

        page.locator('[data-test="config-create"]').click()
        expect(page.locator('[data-test="config-dialog"]')).to_be_visible()
        page.locator('[data-test="config-key-input"]').fill(key)
        page.locator('[data-test="config-value-input"]').fill("42")
        page.locator('[data-test="config-submit"]').click()
        expect(page.locator('[data-test="config-plain"]:has-text("42")').first).to_be_visible(timeout=10000)

        page.goto(f"{BASE_URL}/#/configs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="config-page"]')).to_be_visible()
        expect(page.locator(f'code:has-text("{key}")').first).to_be_visible(timeout=10000)

        row = page.locator(f'tr:has(code:text-is("{key}"))')
        row.locator('[data-test="config-edit"]').click()
        expect(page.locator('[data-test="config-dialog"]')).to_be_visible()
        page.locator('[data-test="config-value-input"]').fill("99")
        page.locator('[data-test="config-submit"]').click()
        expect(page.locator('[data-test="config-plain"]:has-text("99")').first).to_be_visible(timeout=10000)
        page.goto(f"{BASE_URL}/#/configs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="config-page"]')).to_be_visible()
        expect(page.locator('[data-test="config-plain"]:has-text("99")').first).to_be_visible(timeout=10000)

        _mk_config(api_client, f"e2e.k8.sec.{uniq}", "1", group="安全", desc="E2E 安全分组")
        page.locator('[data-test="config-group-filter"]').get_by_text("安全", exact=True).click()
        expect(page.locator('[data-test="config-table"] tbody tr').first).to_be_visible(timeout=10000)
        expect(page.locator(f'code:has-text("e2e.k8.sec.{uniq}")').first).to_be_visible(timeout=10000)
        sec_count = page.locator('[data-test="config-table"] tbody tr').count()
        assert sec_count == 13, f"安全分组应 13 条（种子 12+新增 1）, got {sec_count}"

        page.locator('[data-test="config-group-filter"]').get_by_text("全部", exact=True).click()
        row = page.locator(f'tr:has(code:text-is("{key}"))')
        row.locator('[data-test="config-delete"]').click()
        page.locator('.el-message-box__btns button:has-text("删除")').click()
        expect(page.locator(f'code:has-text("{key}")')).to_have_count(0, timeout=10000)
        page.goto(f"{BASE_URL}/#/configs", wait_until="domcontentloaded")
        expect(page.locator(f'code:has-text("{key}")')).to_have_count(0, timeout=10000)

        assert_no_errors(collectors, "K8 配置 CRUD")
    finally:
        _cleanup(api_client, [key, f"e2e.k8.sec.{uniq}"])



def test_k8_config_appauto_group(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    key = f"e2e.k8.appauto.{uniq}"

    try:
        nav_to(page, "系统配置", "/configs", '[data-test="config-page"]', submenu="系统管理")

        filter_bar = page.locator('[data-test="config-group-filter"]')
        expect(filter_bar.get_by_text("APP自动化", exact=True)).to_be_visible(timeout=10000)
        filter_bar.get_by_text("APP自动化", exact=True).click()

        expect(page.locator('code:text-is("appauto.default-engine")').first).to_be_visible(timeout=10000)
        expect(page.locator('code:text-is("appauto.device-quarantine-failures")').first).to_be_visible(timeout=10000)

        page.locator('[data-test="config-create"]').click()
        expect(page.locator('[data-test="config-dialog"]')).to_be_visible()
        page.locator('[data-test="config-key-input"]').fill(key)
        page.locator('[data-test="config-value-input"]').fill("MOBILEWRIGHT")
        page.locator('[data-test="config-group-input"]').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("APP自动化")').first.click()
        page.locator('[data-test="config-submit"]').click()
        expect(page.locator(f'code:has-text("{key}")').first).to_be_visible(timeout=10000)

        page.goto(f"{BASE_URL}/#/configs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="config-page"]')).to_be_visible()
        page.locator('[data-test="config-group-filter"]').get_by_text("APP自动化", exact=True).click()
        expect(page.locator(f'code:has-text("{key}")').first).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "K8 APP自动化分组")
    finally:
        _cleanup(api_client, [key])



def test_k8_config_sensitive(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    key = f"e2e.k8.secret.{uniq}"
    secret_val = f"sk-e2e-secret-{uniq}"

    try:
        created = _mk_config(api_client, key, secret_val, group="通知", vtype="string",
                             encrypt=1, desc="E2E 敏感配置")
        assert created.get("configValue") == "******", f"敏感配置应打码返回: {created}"

        import os
        import subprocess
        db_user = os.environ.get("DB_USERNAME", "testpike")
        out = subprocess.run(
            ["docker", "exec", "testpike-postgres", "psql", "-U", db_user, "-d", "testpike", "-tAc",
             f"SELECT config_value FROM tp_sys_config WHERE config_key='{key}'"],
            capture_output=True, text=True).stdout.strip()
        assert out and secret_val not in out, f"DB 存在明文! stored={out}"

        nav_to(page, "系统配置", "/configs", '[data-test="config-page"]', submenu="系统管理")
        row = page.locator(f'tr:has(code:text-is("{key}"))')
        expect(row.locator('[data-test="config-masked"]')).to_be_visible(timeout=10000)
        assert "******" in row.locator('[data-test="config-masked"]').text_content()

        r = api_client.put(f"/configs/{created['id']}", json={
            "configKey": key, "configValue": f"new-secret-{uniq}", "groupName": "通知",
            "valueType": "string", "encryptFlag": 1, "description": "E2E 敏感配置-改",
        })
        assert r.status_code == 200 and r.json().get("code") == "0", f"更新失败: {r.text[:300]}"
        r = api_client.get("/configs")
        updated = [x for x in r.json()["data"] if x.get("configKey") == key]
        assert updated and updated[0].get("configValue") == "******", "更新后仍打码"
        r = api_client.get(f"/configs")
        assert r.status_code == 200

        assert_no_errors(collectors, "K8 敏感配置")
    finally:
        _cleanup(api_client, [key])



def test_k8_config_permission(page, collectors, uniq):
    pass
    token = _login("tester", "tester123")
    if not token:
        pytest.skip("本地无 tester 种子用户（权限场景由 L4 覆盖）")
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)

    if page.locator('[data-test="system-menu"]').count() > 0:
        page.locator('.el-sub-menu__title:has-text("系统管理")').first.click()
        expect(page.locator('[data-test="menu-configs"]')).to_have_count(0)
    else:
        expect(page.locator('[data-test="menu-configs"]')).to_have_count(0)

    page.goto(f"{BASE_URL}/#/configs", wait_until="domcontentloaded")
    page.wait_for_timeout(1500)
    assert "#/dashboard" in page.url or "#/configs" not in page.url, f"守卫未拦截: {page.url}"

    r = httpx.get(f"{API_BASE}/configs", headers={"Authorization": f"Bearer {token}"}, timeout=10)
    assert r.status_code == 403 and r.json().get("code") == "40301", f"应 403: {r.status_code} {r.text[:200]}"
    assert "perm:system:system-config" in r.json().get("msg", ""), f"人话应指明权限点: {r.json().get('msg')}"

    assert_no_errors(collectors, "K8 权限")
