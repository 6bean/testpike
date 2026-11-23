
import httpx
import pytest
from playwright.sync_api import expect

from conftest import ADMIN_PASSWORD, ADMIN_USERNAME, API_BASE, BASE_URL, assert_no_errors, nav_to, row_cmd


DATA_SCOPE_LABELS = {1: "全部项目", 2: "项目内", 3: "仅本人"}


def _login(username: str, password: str) -> str:
    r = httpx.post(f"{API_BASE}/auth/login", json={"username": username, "password": password}, timeout=10)
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"登录业务失败: {body.get('msg')}"
    return body["data"]["accessToken"]


def _mk_role(api, uniq, perm_codes=None):
    pass
    r = api.post("/roles", json={
        "name": f"自动化角色_{uniq}", "code": f"auto_{uniq}", "description": "E2E 临时角色",
        "dataScope": 2, "status": 1,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建角色失败: {r.text[:300]}"
    role_id = r.json()["data"]
    if perm_codes:
        r = api.put(f"/roles/{role_id}/funcs", json={"permCodes": perm_codes})
        assert r.status_code == 200 and r.json().get("code") == "0", f"配置权限点失败: {r.text[:300]}"
    return {"id": role_id, "code": f"auto_{uniq}"}


def _mk_user(api, uniq, role=1):
    pass
    username = f"k4user_{uniq}"
    r = api.post("/users", json={
        "username": username, "password": "Test@12345", "displayName": f"K4用户{uniq}",
        "email": f"{username}@test.local", "phone": "", "role": role,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用户失败: {r.text[:300]}"
    return r.json()["data"], username


def _cleanup_role_user(api, role_id, user_id):
    pass
    if user_id:
        api.put(f"/users/{user_id}/roles", json={"roleIds": []})
        api.delete(f"/users/{user_id}")
    if role_id:
        api.delete(f"/roles/{role_id}")


def _pick_role_option(page, name):
    pass
    cb = page.locator(f'[data-test="user-roles-dialog"] .el-checkbox:has-text("{name}")').first
    expect(cb).to_be_visible(timeout=5000)
    cb.click()



def test_k4_role_crud(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    nav_to(page, "角色管理", "/roles", '[data-test="role-page"]', submenu="系统管理")
    expect(page.locator('[data-test="role-table"]')).to_be_visible()

    table_text = page.locator('[data-test="role-table"]').inner_text()
    for builtin in ("系统管理员", "测试人员", "测试负责人", "开发人员"):
        assert builtin in table_text, f"内置角色缺失: {builtin}"

    code = f"auto_{uniq}"
    page.locator('[data-test="role-create"]').click()
    expect(page.locator('[data-test="role-dialog"]')).to_be_visible()
    page.locator('[data-test="role-code-input"]').fill(code)
    page.locator('[data-test="role-name-input"]').fill(f"自动化角色_{uniq}")
    page.locator('[data-test="role-datascope"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:not(.is-disabled)", has_text="项目内").first.click()
    page.locator('[data-test="role-submit"]').click()
    expect(page.locator('[data-test="role-dialog"]')).to_be_hidden(timeout=10000)
    expect(page.locator('[data-test="role-table"]')).to_contain_text(code, timeout=10000)

    row = page.locator(f'[data-test="role-table"] tr:has-text("{code}")')
    row.locator('[data-test="role-perms"]').click()
    expect(page.locator('[data-test="role-perms-dialog"]')).to_be_visible()
    expect(page.locator('[data-test="perm-tree"]')).to_be_visible(timeout=10000)
    item = page.locator('[data-test="perm-effect-perm:case:case-generate"]')
    expect(item).to_be_visible()
    item.locator('.el-radio-button__inner', has_text="允许").click()
    page.locator('[data-test="role-perms-submit"]').click()
    expect(page.locator('[data-test="role-perms-dialog"]')).to_be_hidden(timeout=10000)

    page.goto(f"{BASE_URL}/#/roles", wait_until="domcontentloaded")
    expect(page.locator('[data-test="role-table"]')).to_contain_text(code, timeout=10000)
    row = page.locator(f'[data-test="role-table"] tr:has-text("{code}")')
    row.locator('[data-test="role-perms"]').click()
    expect(page.locator('[data-test="role-perms-dialog"]')).to_be_visible()
    allow_checked = page.locator(
        '[data-test="perm-effect-perm:case:case-generate"] .el-radio-button.is-active'
    )
    assert allow_checked.count() == 1, "reload 后权限覆写未回显（允许态丢失）"
    page.locator('[data-test="role-perms-close"]').click()

    row = page.locator(f'[data-test="role-table"] tr:has-text("{code}")')
    row.locator('[data-test="role-edit"]').click()
    expect(page.locator('[data-test="role-dialog"]')).to_be_visible()
    page.locator('[data-test="role-name-input"]').fill(f"自动化角色改名_{uniq}")
    page.locator('[data-test="role-submit"]').click()
    expect(page.locator('[data-test="role-dialog"]')).to_be_hidden(timeout=10000)
    page.goto(f"{BASE_URL}/#/roles", wait_until="domcontentloaded")
    expect(page.locator('[data-test="role-table"]')).to_contain_text(f"自动化角色改名_{uniq}", timeout=10000)

    row = page.locator(f'[data-test="role-table"] tr:has-text("{code}")')
    row.locator('[data-test="role-delete"]').click()
    expect(page.locator('[data-test="role-table"]')).not_to_contain_text(code, timeout=10000)

    assert_no_errors(collectors, "K4 角色管理 CRUD")



def test_k4_user_role_assign(logged_in, collectors, api_client, uniq):
    pass
    user_id, username = _mk_user(api_client, uniq)
    try:
        r = api_client.get("/roles")
        assert r.status_code == 200, f"角色列表失败: {r.text[:300]}"
        roles = r.json()["data"]
        tester_role = next(x for x in roles if x["code"] == "tester")
        lead_role = next(x for x in roles if x["code"] == "test_lead")

        page = logged_in
        nav_to(page, "用户管理", "/users", '[data-test="user-page"]', submenu="系统管理")
        expect(page.locator('[data-test="user-table"]')).to_be_visible()

        page.locator('[data-test="user-search"]').fill(username)
        page.locator('[data-test="user-search"]').press('Enter')
        expect(page.locator('[data-test="user-table"]')).to_contain_text(username, timeout=10000)

        row = page.locator(f'[data-test="user-table"] tr:has-text("{username}")')
        row_cmd(page, row, "roles")
        expect(page.locator('[data-test="user-roles-dialog"]')).to_be_visible()
        prechecked_tester = page.locator(
            f'[data-test="user-roles-dialog"] .el-checkbox.is-checked:has-text("{tester_role["name"]}")'
        )
        assert prechecked_tester.count() >= 1, "新用户（role=1）分配弹窗应预勾选测试人员（TC-K11 迁移兼容回显）"

        _pick_role_option(page, str(lead_role["name"]))
        page.locator('[data-test="user-roles-submit"]').click()
        expect(page.locator('[data-test="user-roles-dialog"]')).to_be_hidden(timeout=10000)

        page.goto(f"{BASE_URL}/#/users", wait_until="domcontentloaded")
        expect(page.locator('[data-test="user-table"]')).to_contain_text(username, timeout=10000)
        row = page.locator(f'[data-test="user-table"] tr:has-text("{username}")')
        row_cmd(page, row, "roles")
        expect(page.locator('[data-test="user-roles-dialog"]')).to_be_visible()
        checked_tester = page.locator(
            f'[data-test="user-roles-dialog"] .el-checkbox.is-checked:has-text("{tester_role["name"]}")'
        )
        checked_lead = page.locator(
            f'[data-test="user-roles-dialog"] .el-checkbox.is-checked:has-text("{lead_role["name"]}")'
        )
        assert checked_tester.count() >= 1, "reload 后测试人员角色未勾选回显"
        assert checked_lead.count() >= 1, "reload 后测试负责人角色未勾选回显"
        page.locator('[data-test="user-roles-dialog"] .el-dialog__headerbtn').click()

        assert_no_errors(collectors, "K4 用户角色分配")
    finally:
        _cleanup_role_user(api_client, None, user_id)



def test_k4_permission_denied(logged_in, collectors, api_client, uniq):
    pass
    role = _mk_role(api_client, uniq, perm_codes=["case:review"])
    user_id, username = _mk_user(api_client, uniq)
    try:
        r = api_client.put(f"/users/{user_id}/roles", json={"roleIds": [role["id"]]})
        assert r.status_code == 200 and r.json().get("code") == "0", f"分配角色失败: {r.text[:300]}"

        token = _login(username, "Test@12345")
        page = logged_in
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.reload(wait_until="domcontentloaded")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)

        for leaf in ("menu-roles", "menu-users"):
            assert page.locator(f'[data-test="{leaf}"]').count() == 0, \
                f"受限用户仍看到权限受限菜单叶 {leaf}（v-permission 未生效）"

        page.goto(f"{BASE_URL}/#/roles", wait_until="domcontentloaded")
        page.wait_for_timeout(1500)
        assert "#/dashboard" in page.url or "#/roles" not in page.url, \
            f"无权限页面未被守卫拦截，停留 URL: {page.url}"
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=10000)

        r = httpx.get(f"{API_BASE}/roles", headers={"Authorization": f"Bearer {token}"}, timeout=10)
        assert r.status_code == 403, f"无权限 API 应 403，实际 {r.status_code}: {r.text[:300]}"
        body = r.json()
        assert body.get("code") == "40301", f"业务码应 40301: {body}"
        assert "无操作权限" in body.get("msg", ""), f"403 应人话提示: {body.get('msg')}"

        assert_no_errors(collectors, "K4 无权限 403/按钮隐藏")
    finally:
        _cleanup_role_user(api_client, role["id"], user_id)
