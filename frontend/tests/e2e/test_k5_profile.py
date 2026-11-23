
import httpx
import pytest
from playwright.sync_api import expect

from conftest import ADMIN_PASSWORD, ADMIN_USERNAME, API_BASE, BASE_URL, assert_no_errors



def _login(username: str, password: str) -> str | None:
    r = httpx.post(f"{API_BASE}/auth/login", json={"username": username, "password": password}, timeout=10)
    if r.status_code != 200:
        return None
    body = r.json()
    if body.get("code") != "0":
        return None
    return body["data"]["accessToken"]


def _open_profile(page):
    pass
    page.locator(".user-info").click()
    page.locator('[data-test="dropdown-profile"]').click()
    expect(page.locator('[data-test="profile-page"]')).to_be_visible(timeout=10000)



def test_k5_profile_update(logged_in, collectors, api_client):
    pass
    page = logged_in
    nick = "K5新昵称"
    avatar = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg'/>"

    me = api_client.get("/users/me")
    orig = me.json()["data"]["user"]
    orig_nick = orig.get("nickName") or orig.get("username")
    orig_avatar = orig.get("avatar")

    try:
        _open_profile(page)

        expect(page.locator('[data-test="profile-nick-input"]')).to_have_value(orig_nick)
        expect(page.locator('[data-test="profile-username"]')).to_have_value(ADMIN_USERNAME)

        page.locator('[data-test="profile-nick-input"]').fill(nick)
        page.locator('[data-test="profile-avatar-input"]').fill(avatar)
        page.locator('[data-test="profile-save"]').click()
        expect(page.locator(".el-message:has-text(\"资料已保存\")").first).to_be_visible(timeout=10000)

        expect(page.locator(".user-name")).to_have_text(nick, timeout=10000)
        expect(page.locator('[data-test="header-avatar"] img')).to_have_attribute("src", avatar, timeout=10000)

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        expect(page.locator(".user-name")).to_have_text(nick, timeout=10000)
        expect(page.locator('[data-test="header-avatar"] img')).to_have_attribute("src", avatar, timeout=10000)

        _open_profile(page)
        expect(page.locator('[data-test="profile-nick-input"]')).to_have_value(nick)

        assert_no_errors(collectors, "K5 资料更新")
    finally:
        r = api_client.put("/users/me", json={"displayName": orig_nick, "avatar": orig_avatar or None})
        assert r.status_code == 200 and r.json().get("code") == "0", f"恢复资料失败: {r.text[:300]}"



def test_k5_change_password(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    username = f"k5e2e_{uniq}"
    old_pwd, new_pwd = "K5E2e!old123", "K5E2e!new456"

    r = api_client.post("/users", json={
        "username": username, "password": old_pwd, "displayName": "K5改密测试",
        "email": f"{username}@testpike.local", "phone": "13800000002", "role": 1,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用户失败: {r.text[:300]}"
    uid = r.json()["data"]

    try:
        token = _login(username, old_pwd)
        assert token, "测试用户登录失败"
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)

        _open_profile(page)
        page.locator('.el-tabs__item:has-text("修改密码")').click()
        expect(page.locator('[data-test="profile-old-pwd"]')).to_be_visible()

        page.locator('[data-test="profile-old-pwd"]').fill("WrongOld!1")
        page.locator('[data-test="profile-new-pwd"]').fill(new_pwd)
        page.locator('[data-test="profile-confirm-pwd"]').fill(new_pwd)
        page.locator('[data-test="profile-change-pwd-btn"]').click()
        expect(page.locator(".el-message:has-text(\"原密码不正确\")").first).to_be_visible(timeout=10000)

        page.locator('[data-test="profile-old-pwd"]').fill(old_pwd)
        page.locator('[data-test="profile-new-pwd"]').fill("short")
        page.locator('[data-test="profile-confirm-pwd"]').fill("short")
        page.locator('[data-test="profile-change-pwd-btn"]').click()
        expect(page.locator(".el-message:has-text(\"8~64\")").first).to_be_visible(timeout=10000)

        page.locator('[data-test="profile-old-pwd"]').fill(old_pwd)
        page.locator('[data-test="profile-new-pwd"]').fill(new_pwd)
        page.locator('[data-test="profile-confirm-pwd"]').fill(new_pwd)
        page.locator('[data-test="profile-change-pwd-btn"]').click()
        expect(page.locator(".el-message:has-text(\"密码修改成功\")").first).to_be_visible(timeout=10000)

        page.locator(".user-info").click()
        page.locator(".el-dropdown-menu__item:has-text(\"退出登录\")").click()
        page.locator(".el-message-box__btns button:has-text(\"退出\")").click()
        expect(page.locator('[data-test="login-submit"]')).to_be_visible(timeout=10000)

        page.locator('[data-test="login-username"]').fill(username)
        page.locator('[data-test="login-password"]').fill(old_pwd)
        page.locator('[data-test="login-submit"]').click()
        expect(page.locator(".el-message:has-text(\"用户名或密码错误\")").first).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="my-project-table"]')).to_have_count(0, timeout=3000)

        page.locator('[data-test="login-password"]').fill(new_pwd)
        page.locator('[data-test="login-submit"]').click()
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)

        collectors["console_errors"] = [
            e for e in collectors["console_errors"]
            if "status of 400" not in e and "status of 401" not in e
        ]
        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not ("/users/me/password" in e["url"] or "/auth/login" in e["url"])
        ]
        assert_no_errors(collectors, "K5 改密全链路")
    finally:
        r = api_client.delete(f"/users/{uid}")
        assert r.status_code == 200 and r.json().get("code") == "0", f"清理用户失败: {r.text[:300]}"
