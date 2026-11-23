
import httpx
import pytest
from conftest import (
    API_BASE,
    BASE_URL,
    TESTER_PASSWORD,
    api_login,
    assert_no_errors,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.xdist_group("r5-pwd-expiry")

NEW_PWD = "R5E2E@New789"


def _exempt_pwd_expired_noise(collectors):
    pass
    noise = [e for e in collectors["network_4xx"]
             if e["status"] == 428 and "me/permissions" in e["url"]]
    if not noise:
        return
    collectors["network_4xx"] = [e for e in collectors["network_4xx"] if e not in noise]
    collectors["console_errors"] = [
        c for c in collectors["console_errors"] if "428" not in c
    ]


def _login_ui(page, username, password):
    pass
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.locator('[data-test="login-username"]').fill(username)
    page.locator('[data-test="login-password"]').fill(password)
    page.locator('[data-test="login-submit"]').click()


def _set_expire_days(api_client, days):
    pass
    cfg_id = sql_query("SELECT id FROM tp_sys_config WHERE config_key='password.expire-days'")
    orig = sql_query("SELECT config_value FROM tp_sys_config WHERE config_key='password.expire-days'")
    r = api_client.put(f"/configs/{cfg_id}", json={
        "configKey": "password.expire-days", "configValue": str(days),
        "groupName": "安全", "valueType": "int", "encryptFlag": 0,
        "description": "密码有效天数（R5；0=永不过期）"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"阈值改 {days} 失败: {r.text[:150]}"
    return orig


def _mk_expired_tester(api_client, uniq, prefix="r5"):
    pass
    username = f"{prefix}tester_{uniq}"
    r = api_client.post("/users", json={
        "username": username, "password": TESTER_PASSWORD, "displayName": f"R5过期{uniq[:6]}",
        "email": f"{username}@test.local", "phone": "", "role": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:200]
    tester_id = r.json()["data"]
    sql_query(f"UPDATE tp_user SET password_changed_at = now() - interval '91 days' WHERE id={tester_id}")
    return tester_id, username


def _purge_tester(tester_id):
    pass
    sql_query(f"DELETE FROM tp_notification WHERE user_id = {tester_id}")
    sql_query(f"DELETE FROM tp_user_role WHERE user_id = {tester_id}")
    sql_query(f"DELETE FROM tp_user_project WHERE user_id = {tester_id}")
    sql_query(f"DELETE FROM tp_user WHERE id = {tester_id}")
    left = sql_query(f"SELECT count(*) FROM tp_user WHERE id={tester_id}")
    assert left == "0", f"tester 清理失败: {left}"


def test_r5_password_expired_forces_change(page, collectors, api_client, uniq):
    pass
    tester_id, username = _mk_expired_tester(api_client, uniq)

    try:
        _login_ui(page, username, TESTER_PASSWORD)
        page.wait_for_url("**/#/password-expired", timeout=15000)
        page_card = page.locator('[data-test="pwd-expired-page"]')
        expect(page_card).to_be_visible()
        expect(page_card).to_contain_text("密码已过期")

        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        page.wait_for_url("**/#/password-expired", timeout=15000)
        expect(page_card).to_be_visible()

        token = api_login(username, TESTER_PASSWORD)
        rh = httpx.get(f"{API_BASE}/cases", headers={"Authorization": f"Bearer {token}"}, timeout=15)
        assert rh.status_code == 428, f"过期会话直调接口应 428: {rh.status_code}"
        assert "密码已过期" in rh.text, f"428 人话: {rh.text[:150]}"

        page.locator('[data-test="pwd-expired-old"]').fill(TESTER_PASSWORD)
        page.locator('[data-test="pwd-expired-new"]').fill(NEW_PWD)
        page.locator('[data-test="pwd-expired-confirm"]').fill(NEW_PWD)
        page.locator('[data-test="pwd-expired-submit"]').click()
        page.wait_for_url("**/#/login", timeout=15000)

        fresh = sql_query(
            "SELECT (password_changed_at > now() - interval '2 minutes')::int FROM tp_user "
            f"WHERE id={tester_id}")
        assert fresh == "1", f"改密后计时点应刷新, got {fresh}"

        _login_ui(page, username, NEW_PWD)
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)

        _exempt_pwd_expired_noise(collectors)
        assert_no_errors(collectors, "TC-R5-01 过期强制改密")
    finally:
        _purge_tester(tester_id)


def test_r5_expire_days_zero_never_forced(page, collectors, api_client, uniq):
    pass
    tester_id, username = None, None
    orig = None
    try:
        orig = _set_expire_days(api_client, 0)
        tester_id, username = _mk_expired_tester(api_client, uniq, prefix="r5z")

        _login_ui(page, username, TESTER_PASSWORD)
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        assert "#/password-expired" not in page.url, f"expire=0 不应跳改密页: {page.url}"

        assert_no_errors(collectors, "TC-R5-02 expire=0 永不过期")
    finally:
        if orig is not None:
            r = api_client.put("/configs/" + sql_query(
                "SELECT id FROM tp_sys_config WHERE config_key='password.expire-days'"), json={
                "configKey": "password.expire-days", "configValue": orig,
                "groupName": "安全", "valueType": "int", "encryptFlag": 0,
                "description": "密码有效天数（R5；0=永不过期）"})
            assert r.json().get("code") == "0", f"阈值恢复失败: {r.text[:150]}"
        if tester_id:
            _purge_tester(tester_id)
