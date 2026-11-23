
import os

import pytest
from playwright.sync_api import expect
from conftest import ADMIN_PASSWORD, ADMIN_USERNAME, BASE_URL, assert_no_errors



def test_login_page_renders(page, collectors):
    pass
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")

    expect(page.locator(".login-card")).to_be_visible()
    expect(page.locator('input[data-test="login-username"]')).to_be_visible()
    expect(page.locator('input[data-test="login-password"]')).to_be_visible()
    expect(page.locator('button[data-test="login-submit"]')).to_be_visible()
    assert page.evaluate("() => document.documentElement.classList.contains('dark')"), "默认应为深色主题"

    assert_no_errors(collectors, "login page render")


def test_login_flow_ui(page, collectors):
    pass
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")

    page.fill('input[data-test="login-username"]', ADMIN_USERNAME)
    page.fill('input[data-test="login-password"]', os.environ.get("ADMIN_INIT_PASSWORD", ADMIN_PASSWORD))
    page.click('button[data-test="login-submit"]')

    page.wait_for_url("**/#/dashboard", timeout=15000)
    page.wait_for_selector(".dashboard-root", timeout=10000)

    expect(page.locator(".user-name")).to_be_visible()
    assert page.locator(".user-name").inner_text().strip(), "顶栏用户名缺失"
    token = page.evaluate("() => localStorage.getItem('tp_access_token')")
    assert token and len(token) > 20, "登录后应写入 accessToken"

    expect(page.locator(".welcome-title")).to_be_visible()
    welcome = page.locator(".welcome-title").inner_text()
    assert "欢迎" in welcome, f"欢迎语异常: {welcome}"

    assert_no_errors(collectors, "login flow")


def test_login_fail_wrong_password(page, collectors):
    pass
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")

    page.fill('input[data-test="login-username"]', ADMIN_USERNAME)
    page.fill('input[data-test="login-password"]', "wrong-password-xyz")
    page.click('button[data-test="login-submit"]')
    expect(page.locator(".login-card")).to_be_visible()
    toast = page.locator(".el-message--error").first
    expect(toast).to_be_visible(timeout=5000)
    text = toast.inner_text()
    assert text and "用户名或密码错误" in text, f"错误提示应为人话中文: {text}"

    collectors["network_4xx"] = [e for e in collectors["network_4xx"] if "/auth/login" not in e["url"]]
    collectors["console_errors"] = [c for c in collectors["console_errors"] if "401" not in c]
    assert_no_errors(collectors, "login fail")
