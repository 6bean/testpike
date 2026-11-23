
import httpx
import pytest
from playwright.sync_api import expect

from conftest import ADMIN_PASSWORD, ADMIN_USERNAME, API_BASE, BASE_URL, assert_no_errors, nav_to, row_cmd



def _login(username: str, password: str) -> tuple[int, dict]:
    r = httpx.post(f"{API_BASE}/auth/login", json={"username": username, "password": password}, timeout=10)
    return r.status_code, r.json()


def _mk_user(api, uniq, username=None):
    username = username or f"n1user_{uniq}"
    r = api.post("/users", json={
        "username": username, "password": "Test@12345", "displayName": f"N1用户{uniq}",
        "email": f"{username}@test.local", "phone": "13900000001", "role": 1,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用户失败: {r.text[:300]}"
    return r.json()["data"], username


def _cleanup(api, usernames):
    for u in usernames:
        r = api.get(f"/users?keyword={u}&size=20")
        for x in r.json().get("data", {}).get("list", []):
            if x.get("username") == u:
                api.delete(f"/users/{x['id']}")


def _lock_user(username: str) -> None:
    pass
    for _ in range(5):
        status, body = _login(username, "WrongPass1!")
        assert status == 401 and body.get("code") == "40101", f"失败响应异常: {status} {body}"
    status, body = _login(username, "Test@12345")
    assert status == 423 and body.get("code") == "42301", f"第 6 次应锁定: {status} {body}"
    assert "锁定" in (body.get("msg") or "") and "分钟" in (body.get("msg") or ""), f"423 人话缺失: {body.get('msg')}"



def test_n1_login_lockout(logged_in, collectors, api_client, uniq):
    page = logged_in
    uid, username = _mk_user(api_client, uniq)

    try:
        page.evaluate("() => localStorage.removeItem('tp_access_token')")
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="login-username"]')).to_be_visible(timeout=10000)
        for i in range(5):
            page.locator('[data-test="login-username"]').fill(username)
            page.locator('[data-test="login-password"]').fill("WrongPass1!")
            page.locator('[data-test="login-submit"]').click()
            expect(page.locator(".el-message:has-text('用户名或密码错误')").first).to_be_visible(timeout=5000)

        page.locator('[data-test="login-password"]').fill("Test@12345")
        page.locator('[data-test="login-submit"]').click()
        expect(page.locator(".el-message:has-text('账号已锁定')").first).to_be_visible(timeout=5000)
        expect(page.locator(".el-message:has-text('账号已锁定')").first).to_contain_text("分钟")

        for k in collectors:
            collectors[k].clear()

        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        token = _login(ADMIN_USERNAME, ADMIN_PASSWORD)[1]["data"]["accessToken"]
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)

        nav_to(page, "用户管理", "/users", '[data-test="user-page"]', submenu="系统管理")
        page.locator('[data-test="user-search"]').fill(username)
        page.keyboard.press("Enter")
        expect(page.locator('[data-test="user-locked-tag"]').first).to_be_visible(timeout=10000)
        row_cmd(page, page.locator(f'[data-test="user-table"] tr:has-text("{username}")').first, "unlock")
        expect(page.locator(".el-message-box")).to_be_visible()
        page.locator(".el-message-box__btns button:has-text('确定')").click()
        expect(page.locator(".el-message:has-text('已解锁')").first).to_be_visible(timeout=5000)

        page.reload()
        page.locator('[data-test="user-search"]').fill(username)
        page.keyboard.press("Enter")
        expect(page.locator('[data-test="user-locked-tag"]')).to_have_count(0, timeout=10000)

        status, body = _login(username, "Test@12345")
        assert status == 200 and body.get("code") == "0", f"解锁后登录失败: {status} {body}"

        assert_no_errors(collectors, "N1 登录锁定与解锁")
    finally:
        _cleanup(api_client, [username])



def test_n1_unlock_forbidden(logged_in, collectors, api_client, uniq):
    page = logged_in
    uid_a, ua = _mk_user(api_client, uniq, username=f"n1a_{uniq}")
    uid_b, ub = _mk_user(api_client, uniq, username=f"n1b_{uniq}")

    try:
        _lock_user(ua)

        status, body = _login(ub, "Test@12345")
        assert status == 200 and body.get("code") == "0", f"B 登录失败: {status} {body}"
        token_b = body["data"]["accessToken"]
        r = httpx.post(f"{API_BASE}/users/{uid_a}/unlock", headers={"Authorization": f"Bearer {token_b}"}, timeout=10)
        assert r.status_code == 403, f"非管理员解锁应 403: {r.status_code} {r.text[:200]}"
        assert r.json().get("code") == "40301", f"业务码应 40301: {r.json()}"

        status, body = _login(ADMIN_USERNAME, ADMIN_PASSWORD)
        token_a = body["data"]["accessToken"]
        r = httpx.post(f"{API_BASE}/users/{uid_a}/unlock", headers={"Authorization": f"Bearer {token_a}"}, timeout=10)
        assert r.status_code == 200 and r.json().get("code") == "0", f"管理员解锁失败: {r.text[:200]}"
        status, body = _login(ua, "Test@12345")
        assert status == 200, f"A 解锁后登录失败: {status} {body}"

        assert_no_errors(collectors, "N1 解锁越权 403")
    finally:
        _cleanup(api_client, [ua, ub])
