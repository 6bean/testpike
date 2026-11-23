
import httpx
import openpyxl
import pytest
from playwright.sync_api import expect

from conftest import ADMIN_PASSWORD, ADMIN_USERNAME, API_BASE, BASE_URL, assert_no_errors, nav_to, row_cmd



def _login(username: str, password: str) -> str | None:
    r = httpx.post(f"{API_BASE}/auth/login", json={"username": username, "password": password}, timeout=10)
    if r.status_code != 200:
        return None
    body = r.json()
    if body.get("code") != "0":
        return None
    return body["data"]["accessToken"]


def _mk_user(api, uniq, username=None):
    pass
    username = username or f"k3user_{uniq}"
    r = api.post("/users", json={
        "username": username, "password": "Test@12345", "displayName": f"K3用户{uniq}",
        "email": f"{username}@test.local", "phone": "13900000000", "role": 1,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用户失败: {r.text[:300]}"
    return r.json()["data"], username


def _cleanup(api, usernames):
    pass
    for u in usernames:
        r = api.get(f"/users?keyword={u}&size=20")
        for x in r.json().get("data", {}).get("list", []):
            if x.get("username") == u:
                api.delete(f"/users/{x['id']}")


def _user_excel(rows, path):
    pass
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "用户"
    ws.append(["登录名", "姓名", "邮箱", "手机", "角色", "初始密码"])
    for row in rows:
        ws.append(row)
    wb.save(path)
    return path



def test_k3_user_crud(logged_in, collectors, api_client, uniq, tmp_path):
    pass
    page = logged_in
    nav_to(page, "用户管理", "/users", '[data-test="user-page"]', submenu="系统管理")
    expect(page.locator('[data-test="user-table"]')).to_be_visible()

    username = f"k3crud_{uniq}"
    page.locator('[data-test="user-create-btn"]').click()
    expect(page.locator('[data-test="user-edit-dialog"]')).to_be_visible()
    page.locator('[data-test="user-edit-username"]').fill(username)
    page.locator('[data-test="user-edit-displayname"]').fill(f"CRUD用户{uniq}")
    page.locator('[data-test="user-edit-email"]').fill(f"{username}@test.local")
    page.locator('[data-test="user-edit-password"]').fill("Test@12345")
    page.locator('[data-test="user-edit-submit"]').click()
    expect(page.locator('[data-test="user-edit-dialog"]')).to_be_hidden(timeout=10000)
    page.locator('input[data-test="user-search"]').fill(username)
    page.locator('input[data-test="user-search"]').press("Enter")
    expect(page.locator('[data-test="user-table"]')).to_contain_text(username, timeout=10000)

    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="user-page"]')).to_be_visible(timeout=10000)
    page.locator('input[data-test="user-search"]').fill(username)
    page.locator('input[data-test="user-search"]').press("Enter")
    expect(page.locator('[data-test="user-table"]')).to_contain_text(username, timeout=10000)

    expect(page.locator('[data-test="user-table"]')).to_contain_text(username, timeout=10000)
    page.locator('[data-test="user-page"] .el-input__clear').first.click()
    expect(page.locator('[data-test="user-table"] tbody tr').first).to_be_visible(timeout=10000)
    page.locator('input[data-test="user-search"]').fill(username)
    page.locator('input[data-test="user-search"]').press("Enter")
    expect(page.locator('[data-test="user-table"]')).to_contain_text(username, timeout=10000)

    row = page.locator(f'[data-test="user-table"] tr:has-text("{username}")')
    row_cmd(page, row, "edit")
    expect(page.locator('[data-test="user-edit-dialog"]')).to_be_visible()
    page.locator('[data-test="user-edit-displayname"]').fill(f"CRUD改名{uniq}")
    page.locator('[data-test="user-edit-status"]').click()
    page.locator('[data-test="user-edit-submit"]').click()
    expect(page.locator('[data-test="user-edit-dialog"]')).to_be_hidden(timeout=10000)
    page.reload(wait_until="domcontentloaded")
    page.locator('input[data-test="user-search"]').fill(f"CRUD改名{uniq}")
    page.locator('input[data-test="user-search"]').press("Enter")
    expect(page.locator('[data-test="user-table"]')).to_contain_text(f"CRUD改名{uniq}", timeout=10000)
    expect(page.locator('[data-test="user-table"]')).to_contain_text("停用", timeout=10000)

    assert _login(username, "Test@12345") is None, "停用用户登录必须被拒"

    row = page.locator(f'[data-test="user-table"] tr:has-text("CRUD改名{uniq}")')
    row_cmd(page, row.first, "delete")
    expect(page.locator(".el-message-box")).to_be_visible()
    page.locator(".el-message-box__btns .el-button--primary").click()
    page.reload(wait_until="domcontentloaded")
    page.locator('input[data-test="user-search"]').fill(username)
    page.locator('input[data-test="user-search"]').press("Enter")
    expect(page.locator('[data-test="user-table"]')).not_to_contain_text(username, timeout=10000)

    assert_no_errors(collectors, "K3 用户 CRUD")



def test_k3_user_import_retry(logged_in, collectors, api_client, uniq, tmp_path):
    pass
    page = logged_in
    nav_to(page, "用户管理", "/users", '[data-test="user-page"]', submenu="系统管理")

    dup_name = f"k3dup_{uniq}"
    _mk_user(api_client, uniq, username=dup_name)

    page.locator('[data-test="user-import-btn"]').click()
    expect(page.locator('[data-test="user-import-dialog"]')).to_be_visible()
    with page.expect_download() as dl_info:
        page.locator('[data-test="user-import-template"]').click()
    download = dl_info.value
    assert download.suggested_filename.endswith(".xlsx"), download.suggested_filename
    assert download.path() is not None and download.path().stat().st_size > 1000, "模板 xlsx 非空"

    uname = f"k3imp_{uniq}"
    excel = _user_excel([
        [f"{uname}_a", "导入甲", f"{uname}_a@test.local", "13900000001", 1, "Passw0rd!a"],
        [dup_name, "重名", f"{dup_name}@test.local", "13900000002", 1, "Passw0rd!b"],
        [f"{uname}_b", "导入乙", f"{uname}_b@test.local", "13900000003", 2, "Passw0rd!c"],
        ["", "无名", f"{uname}_e@test.local", "13900000005", 1, "Passw0rd!e"],
        [f"{uname}_e", "导入戊", "bad-email", "13900000006", 1, "Passw0rd!f"],
    ], tmp_path / f"k3_{uniq}.xlsx")
    page.locator('[data-test="user-import-upload"] input[type="file"]').set_input_files(str(excel))
    page.locator('[data-test="user-import-submit"]').click()

    expect(page.locator('[data-test="user-import-result"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="import-success-count"]')).to_contain_text("成功 2 条", timeout=10000)
    expect(page.locator('[data-test="import-fail-count"]')).to_contain_text("失败 3 条", timeout=10000)
    fail_table = page.locator('[data-test="import-fail-table"]').inner_text()
    assert "已存在" in fail_table, f"重名原因缺失: {fail_table}"
    assert "邮箱" in fail_table, f"邮箱格式原因缺失: {fail_table}"
    assert "登录名" in fail_table, f"登录名空原因缺失: {fail_table}"

    item = page.locator('[data-test="user-import-upload"] .el-upload-list__item').first
    item.hover()
    page.locator('[data-test="user-import-upload"] .el-upload-list__item .el-icon--close').click()
    fixed = _user_excel([
        [f"{uname}_a", "导入甲", f"{uname}_a@test.local", "13900000001", 1, "Passw0rd!a"],
        [f"{uname}_c", "导入丙", f"{uname}_c@test.local", "13900000004", 3, "Passw0rd!d"],
        [f"{uname}_b", "导入乙", f"{uname}_b@test.local", "13900000003", 2, "Passw0rd!c"],
        [f"{uname}_e", "导入戊", f"{uname}_e@test.local", "13900000005", 1, "Passw0rd!e"],
        [f"{uname}_f", "导入己", f"{uname}_f@test.local", "13900000006", 1, "Passw0rd!f"],
    ], tmp_path / f"k3fix_{uniq}.xlsx")
    page.locator('[data-test="user-import-upload"] input[type="file"]').set_input_files(str(fixed))
    page.locator('[data-test="user-import-retry"]').click()
    expect(page.locator('[data-test="user-import-result"]')).to_contain_text("成功 2 条", timeout=15000)
    expect(page.locator('[data-test="user-import-result"]')).to_contain_text("失败 0 条", timeout=10000)

    page.locator('[data-test="user-import-dialog"] .el-dialog__headerbtn').click()
    page.reload(wait_until="domcontentloaded")
    page.locator('input[data-test="user-search"]').fill(uname)
    page.locator('input[data-test="user-search"]').press("Enter")
    expect(page.locator('[data-test="user-table"]')).to_contain_text(f"{uname}_a", timeout=10000)
    expect(page.locator('[data-test="user-table"]')).to_contain_text(f"{uname}_f", timeout=10000)
    expect(page.locator('[data-test="user-table"]')).to_contain_text(f"{uname}_c", timeout=10000)

    admin_token = _login(ADMIN_USERNAME, ADMIN_PASSWORD)
    with open(fixed, "rb") as f:
        r = httpx.post(f"{API_BASE}/users/import/retry", params={"retryRows": [2, 3, 4]},
                       headers={"Authorization": f"Bearer {admin_token}"},
                       files={"file": ("u.xlsx", f, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")}, timeout=15)
    data = r.json().get("data", {})
    assert data.get("successCount") == 0 and data.get("failCount") == 3, f"幂等失败: {data}"
    assert all("已存在" in str(i.get("reason")) or "重复" in str(i.get("reason")) for i in data.get("items", [])), "幂等原因人话"

    _cleanup(api_client, [dup_name, f"{uname}_a", f"{uname}_b", f"{uname}_c", f"{uname}_e", f"{uname}_f"])
    assert_no_errors(collectors, "K3 批量导入")



def test_k3_reset_password(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    nav_to(page, "用户管理", "/users", '[data-test="user-page"]', submenu="系统管理")

    username = f"k3pwd_{uniq}"
    uid, _ = _mk_user(api_client, uniq, username=username)
    new_pwd = "NewPassw0rd!k3"

    page.reload(wait_until="domcontentloaded")
    page.locator('input[data-test="user-search"]').fill(username)
    page.locator('input[data-test="user-search"]').press("Enter")
    expect(page.locator('[data-test="user-table"]')).to_contain_text(username, timeout=10000)

    row = page.locator(f'[data-test="user-table"] tr:has-text("{username}")')
    row_cmd(page, row, "reset-pwd")
    expect(page.locator('[data-test="user-reset-dialog"]')).to_be_visible()
    page.locator('[data-test="user-reset-input"]').fill(new_pwd)
    page.locator('[data-test="user-reset-submit"]').click()
    expect(page.locator('[data-test="user-reset-dialog"]')).to_be_hidden(timeout=10000)

    assert _login(username, new_pwd) is not None, "重置后新密码必须可登录"
    assert _login(username, "Test@12345") is None, "重置后旧密码必须失效"

    page.reload(wait_until="domcontentloaded")
    page.locator('input[data-test="user-search"]').fill(username)
    page.locator('input[data-test="user-search"]').press("Enter")
    expect(page.locator('[data-test="user-table"]')).to_contain_text(username, timeout=10000)

    _cleanup(api_client, [username])
    assert_no_errors(collectors, "K3 密码重置")
