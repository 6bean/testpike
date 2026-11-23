
import re

import httpx
from conftest import (
    API_BASE,
    TESTER_PASSWORD,
    assert_no_errors,
    inject_user_token,
    mk_tester,
    sql_query,
)
from playwright.sync_api import expect

WRONG_PWD = "R4E2E-Wrong9"


def _lock_by_wrong_logins(username: str, times: int = 5):
    pass
    for i in range(times):
        r = httpx.post(f"{API_BASE}/auth/login",
                       json={"username": username, "password": WRONG_PWD}, timeout=15)
        assert r.status_code == 401, f"第 {i + 1} 次连错应 401: {r.status_code} {r.text[:150]}"


def test_r4_login_locked_notification(page, collectors, api_client, uniq):
    pass
    tester_id, _nick = mk_tester(api_client, uniq, prefix="r4")
    username = "r4tester_" + uniq

    try:
        _lock_by_wrong_logins(username)

        locked = sql_query(f"SELECT (locked_until IS NOT NULL)::int FROM tp_user WHERE id={tester_id}")
        assert locked == "1", f"第 5 次连错后应已锁定, got {locked}"
        n_cnt = int(sql_query(
            f"SELECT count(*) FROM tp_notification WHERE event_type='LOGIN_LOCKED' AND user_id={tester_id}"))
        assert n_cnt == 1, f"本人应收 1 条, got {n_cnt}"

        r = httpx.post(f"{API_BASE}/auth/login",
                       json={"username": username, "password": TESTER_PASSWORD}, timeout=15)
        assert r.status_code == 423, f"锁定期正确密码也应 423: {r.status_code}"

        r = api_client.post(f"/users/{tester_id}/unlock")
        assert r.status_code == 200 and r.json().get("code") == "0", f"解锁失败: {r.text[:200]}"
        assert int(sql_query(
            f"SELECT count(*) FROM tp_notification WHERE event_type='LOGIN_LOCKED' "
            f"AND user_id IN ({tester_id})")) == 1, "解锁不补发（IT-R4-01 UI 侧）"

        inject_user_token(page, username, TESTER_PASSWORD)
        page.goto(f"{page.url.split('#')[0]}#/notifications", wait_until="domcontentloaded")
        expect(page.locator('[data-test="notif-table"]')).to_be_visible(timeout=15000)

        row = page.locator('[data-test="notif-table"] tbody tr').filter(
            has_text="账号锁定告警").first
        expect(row).to_be_visible(timeout=10000)
        expect(row).to_contain_text("连续 5 次")
        expect(row).to_contain_text("15 分钟")
        expect(row).to_contain_text("修改密码")
        expect(row).to_contain_text(username)

        page.locator('[data-test="notif-type-filter"]').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("账号锁定告警"):visible').first.click()
        page.locator('[data-test="notif-search"]').click()
        rows = page.locator('[data-test="notif-table"] tbody tr')
        expect(rows).to_have_count(1, timeout=10000)
        expect(rows.first).to_contain_text("账号锁定告警")

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="notif-table"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="notif-table"] tbody tr').first).to_contain_text("账号锁定告警")

        page.get_by_role("tab", name="订阅设置").click()
        expect(page.locator('[data-test="pref-panel"]')).to_be_visible(timeout=10000)
        lock_switch = page.locator('[data-test="pref-switch-LOGIN_LOCKED"]')
        expect(lock_switch).to_be_visible(timeout=10000)
        expect(lock_switch).to_have_class(re.compile(r"is-disabled"), timeout=5000)
        expect(page.locator('[data-test="pref-label-LOGIN_LOCKED"] .el-tag')).to_contain_text("强制")

        assert_no_errors(collectors, "TC-R4-01 login locked notification")
    finally:
        sql_query(f"DELETE FROM tp_notification WHERE user_id = {tester_id} "
                  f"OR dedup_key LIKE 'LOGIN_LOCKED:{tester_id}:%'")
        sql_query(f"DELETE FROM tp_user_role WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user_project WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user WHERE id = {tester_id}")
        left = sql_query(f"SELECT count(*) FROM tp_user WHERE id={tester_id}")
        assert left == "0", f"tester 清理失败: {left}"
