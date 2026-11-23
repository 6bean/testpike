
import os
import subprocess
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


def _mk_model(api, name, provider="deepseek", model_name="deepseek-chat", api_key="sk-e2e-secret",
              status=1, is_default=0):
    r = api.post("/llm-configs", json={
        "name": name, "provider": provider, "modelName": model_name,
        "apiKey": api_key, "baseUrl": f"https://api.{provider}.com/v1",
        "status": status, "isDefault": is_default, "description": "E2E 测试模型",
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建模型失败: {r.text[:300]}"
    return r.json()["data"]


def _cleanup_models(api, names):
    pass
    r = api.get("/llm-configs")
    for x in r.json().get("data", []):
        if x.get("name") in names:
            api.delete(f"/llm-configs/{x['id']}")


def _cleanup_projects(api, codes):
    r = api.get("/projects")
    data = r.json().get("data", {})
    items = data.get("list") or data.get("records") or (data if isinstance(data, list) else [])
    for x in items:
        if x.get("code") in codes:
            api.delete(f"/projects/{x['id']}")



def test_k9_llm_crud(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    name = f"e2e-k9-{uniq}"
    secret = f"sk-e2e-{uniq}-SECRET"

    try:
        nav_to(page, "大模型配置", "/llm-configs", '[data-test="llm-config-page"]', submenu="系统管理")

        page.locator('[data-test="llm-create"]').click()
        expect(page.locator('[data-test="llm-dialog"]')).to_be_visible()
        page.locator('[data-test="llm-name-input"]').fill(name)
        page.locator('[data-test="llm-model-input"]').fill("deepseek-chat")
        page.locator('[data-test="llm-key-input"]').fill(secret)
        page.locator('[data-test="llm-save"]').click()
        expect(page.locator(f'tr:has-text("{name}")').first).to_be_visible(timeout=10000)

        row = page.locator(f'tr:has-text("{name}")')
        expect(row.locator('[data-test="llm-key-masked"]')).to_be_visible()
        assert "******" in row.locator('[data-test="llm-key-masked"]').text_content()

        r = api_client.get("/llm-configs")
        created = [x for x in r.json()["data"] if x.get("name") == name]
        assert created and created[0].get("apiKeyMasked") == "******", f"API 应打码: {created}"

        db_user = os.environ.get("DB_USERNAME", "testpike")
        out = subprocess.run(
            ["docker", "exec", "testpike-postgres", "psql", "-U", db_user, "-d", "testpike", "-tAc",
             f"SELECT api_key FROM tp_llm_config WHERE name='{name}'"],
            capture_output=True, text=True).stdout.strip()
        assert out and secret not in out, f"DB 存在明文! stored={out}"

        page.goto(f"{BASE_URL}/#/llm-configs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="llm-config-page"]')).to_be_visible()
        expect(page.locator(f'tr:has-text("{name}")').first).to_be_visible(timeout=10000)

        row = page.locator(f'tr:has-text("{name}")')
        row.locator('[data-test="llm-edit"]').click()
        expect(page.locator('[data-test="llm-dialog"]')).to_be_visible()
        page.locator('[data-test="llm-model-input"]').fill("deepseek-chat-0324")
        page.locator('[data-test="llm-save"]').click()
        expect(page.locator(f'tr:has-text("{name}")').first).to_be_visible(timeout=10000)
        expect(page.locator('tr:has-text("deepseek-chat-0324")').first).to_be_visible(timeout=10000)
        page.goto(f"{BASE_URL}/#/llm-configs", wait_until="domcontentloaded")
        expect(page.locator('tr:has-text("deepseek-chat-0324")').first).to_be_visible(timeout=10000)

        row = page.locator(f'tr:has-text("{name}")')
        row.locator('[data-test="llm-delete"]').click()
        page.locator('.el-message-box__btns button:has-text("删除")').click()
        expect(page.locator(f'tr:has-text("{name}")')).to_have_count(0, timeout=10000)
        page.goto(f"{BASE_URL}/#/llm-configs", wait_until="domcontentloaded")
        expect(page.locator(f'tr:has-text("{name}")')).to_have_count(0, timeout=10000)

        assert_no_errors(collectors, "K9 大模型配置 CRUD")
    finally:
        _cleanup_models(api_client, [name])



def test_k9_project_llm_select(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    suffix = uniq
    proj_name = f"E2EK9项目{suffix}"
    proj_code = f"E2EK9{suffix}"
    model_name = f"e2e-k9m-{suffix}"

    try:
        model = _mk_model(api_client, model_name, is_default=0)
        _mk_model(api_client, f"e2e-k9m2-{suffix}", is_default=0)

        r = api_client.post("/projects", json={
            "name": proj_name, "code": proj_code, "defaultLang": "zh-CN", "defaultPriority": "P1"})
        assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
        proj_id = r.json()["data"]

        nav_to(page, "项目管理", "/projects", '[data-test="project-table"]', submenu="系统管理")
        page.locator(f'tr:has-text("{proj_code}") .el-link').first.click()
        expect(page.locator('.detail-tabs')).to_be_visible(timeout=10000)
        page.locator('.el-tabs__item:has-text("大模型")').click()
        expect(page.locator('[data-test="llm-config-select"]')).to_be_visible(timeout=10000)

        page.locator('[data-test="llm-config-select"]').click()
        expect(page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("e2e-k9m-")').first).to_be_visible(timeout=10000)

        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("e2e-k9m2-")').first.click()
        page.locator('[data-test="llm-config-save"]').click()
        expect(page.locator('[data-test="llm-config-name"]:has-text("e2e-k9m2-")').first).to_be_visible(timeout=10000)

        page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
        expect(page.locator('[data-test="project-table"]')).to_be_visible()
        page.locator(f'tr:has-text("{proj_code}") .el-link').first.click()
        expect(page.locator('.detail-tabs')).to_be_visible(timeout=10000)
        page.locator('.el-tabs__item:has-text("大模型")').click()
        expect(page.locator('[data-test="llm-config-select"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="llm-config-name"]:has-text("e2e-k9m2-")').first).to_be_visible(timeout=10000)

        page.locator('[data-test="llm-config-select"]').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("e2e-k9m-")').first.click()
        page.locator('[data-test="llm-config-save"]').click()
        expect(page.locator('[data-test="llm-config-name"]:has-text("e2e-k9m-")').first).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "K9 项目选用回显")
    finally:
        _cleanup_projects(api_client, [proj_code])
        _cleanup_models(api_client, [model_name, f"e2e-k9m2-{suffix}"])



def test_k9_llm_permission(page, collectors, uniq):
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
        expect(page.locator('[data-test="menu-llm-configs"]')).to_have_count(0)
    else:
        expect(page.locator('[data-test="menu-llm-configs"]')).to_have_count(0)

    page.goto(f"{BASE_URL}/#/llm-configs", wait_until="domcontentloaded")
    page.wait_for_timeout(1500)
    assert "#/dashboard" in page.url or "#/llm-configs" not in page.url, f"守卫未拦截: {page.url}"

    r = httpx.get(f"{API_BASE}/llm-configs", headers={"Authorization": f"Bearer {token}"}, timeout=10)
    assert r.status_code == 403 and r.json().get("code") == "40301", f"应 403: {r.status_code} {r.text[:200]}"
    assert "perm:system:llm-config" in r.json().get("msg", ""), f"人话应指明权限点: {r.json().get('msg')}"

    assert_no_errors(collectors, "K9 权限")
