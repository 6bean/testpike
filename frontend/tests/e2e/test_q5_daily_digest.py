
import re

import httpx
from playwright.sync_api import expect

from conftest import (
    TESTER_PASSWORD,
    add_member,
    assert_no_errors,
    inject_user_token,
    mk_tester,
    sql_query,
)

CORE_BASE = "http://localhost:8083"


def _scan():
    r = httpx.post(f"{CORE_BASE}/internal/cases/daily-digest-scan", timeout=30)
    assert r.status_code == 200 and r.json().get("code") == "0", f"扫描触发失败: {r.text[:300]}"
    return r.json()["data"]


def _goto_prefs_tab(page):
    page.goto(page.url.split("#")[0] + "#/notifications", wait_until="domcontentloaded")
    expect(page.locator('[data-test="notif-center-page"]')).to_be_visible(timeout=15000)
    page.get_by_role("tab", name="订阅设置").click()
    expect(page.locator('[data-test="pref-panel"]')).to_be_visible(timeout=10000)


def _notif_count(user_id):
    return int(sql_query("SELECT count(*) FROM tp_notification WHERE event_type='DAILY_DIGEST'"
                         f" AND user_id={user_id}"))


def test_q5_digest_subscribe_unsubscribe(page, collectors, api_client, uniq):
    pass
    tester_id, _nick = mk_tester(api_client, uniq, prefix="q5")
    username = "q5tester_" + uniq

    r = api_client.post("/projects", json={"name": f"Q5E2E_{uniq}", "code": f"Q5U{uniq[:6].upper()}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api_client.post(f"/projects/{pid}/members", json={"userId": tester_id, "role": 2})
    assert r.json().get("code") == "0", f"加负责人失败: {r.text[:300]}"
    r = api_client.post("/cases", json={
        "projectId": pid, "title": f"日报角标用例{uniq}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2, "endType": "web",
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
    assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]
    sql_query("UPDATE tp_test_case SET created_at = "
              f"date_trunc('day', now()) - interval '1 day' + interval '1 hour' WHERE id = {case_id}")

    try:
        inject_user_token(page, username, TESTER_PASSWORD)
        _goto_prefs_tab(page)
        sw = page.locator('[data-test="pref-switch-DAILY_DIGEST"]')
        expect(sw).to_be_visible(timeout=10000)
        expect(sw).not_to_have_class(re.compile(r"is-checked"), timeout=5000)
        expect(page.locator('[data-test="pref-label-DAILY_DIGEST"] .el-tag')).to_contain_text("默认关")

        _scan()
        assert _notif_count(tester_id) == 0, "未订阅不生成（default_off）"

        sw.click()
        expect(page.locator('[data-test="pref-dirty-tag"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="pref-save-btn"]').click()
        expect(page.locator(".el-message").filter(has_text="已保存").last).to_be_visible(timeout=5000)
        assert sql_query("SELECT enabled FROM tp_notification_pref WHERE user_id="
                         f"{tester_id} AND event_type='DAILY_DIGEST'") == "1"
        page.reload(wait_until="domcontentloaded")
        _goto_prefs_tab(page)
        expect(sw).to_have_class(re.compile(r"is-checked"), timeout=5000)

        _scan()
        assert _notif_count(tester_id) == 1, "订阅后扫描生成 1 条"
        page.get_by_role("tab", name="通知列表").click()
        row = page.locator('[data-test="notif-table"] tbody tr').filter(has_text="每日日报").first
        expect(row).to_be_visible(timeout=10000)
        expect(row).to_contain_text(f"Q5E2E_{uniq}")
        expect(row).to_contain_text("新增用例 1")

        _goto_prefs_tab(page)
        sw.click()
        page.locator('[data-test="pref-save-btn"]').click()
        expect(page.locator(".el-message").filter(has_text="已保存").last).to_be_visible(timeout=5000)
        sql_query(f"DELETE FROM tp_notification WHERE user_id = {tester_id}")
        d = _scan()
        assert _notif_count(tester_id) == 0, "退订后不再生成（D3 单点过滤）"
        assert d["prefFiltered"] >= 1, f"退订计入 prefFiltered: {d}"

        assert_no_errors(collectors, "TC-Q5-01 digest subscribe/unsubscribe")
    finally:
        sql_query(f"DELETE FROM tp_notification WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_notification_pref WHERE user_id = {tester_id}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
        sql_query(f"DELETE FROM tp_user_role WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user_project WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user WHERE id = {tester_id}")
        assert sql_query(f"SELECT count(*) FROM tp_notification WHERE event_type='DAILY_DIGEST'"
                         f" AND user_id={tester_id}") == "0"
        assert sql_query("SELECT count(*) FROM tp_test_case WHERE id = " + str(case_id)) == "0"
