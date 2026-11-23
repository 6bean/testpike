
import re

import httpx
from conftest import (
    API_BASE,
    TESTER_PASSWORD,
    add_member,
    assert_no_errors,
    inject_user_token,
    mk_tester,
    sql_query,
)
from playwright.sync_api import expect


def _api_as(username, password):
    pass
    r = httpx.post(f"{API_BASE}/auth/login",
                   json={"username": username, "password": password}, timeout=15)
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:200]}"
    token = r.json()["data"]["accessToken"]
    return httpx.Client(base_url=API_BASE, headers={"Authorization": f"Bearer {token}"},
                        timeout=15)


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"P5项目_{uniq}", "code": f"P5T{uniq[:6].upper()}"})
    assert r.json().get("code") == "0", f"创建项目失败: {r.text[:200]}"
    return r.json()["data"]


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "priority": 1, "confidence": 2, "endType": "web",
        "steps": [{"stepNo": 1, "description": "打开登录页", "isAssert": 0}],
    })
    assert r.json().get("code") == "0", f"创建用例失败: {r.text[:200]}"
    return r.json()["data"]["id"]


def _close_defect_trigger(api, case_id, key):
    pass
    r = api.post("/defects", json={"caseId": case_id, "defectKey": key, "defectStatus": "open"})
    assert r.json().get("code") == "0", f"关联缺陷失败: {r.text[:200]}"
    d = r.json()["data"]
    r = api.put(f"/defects/{d['id']}/status",
                json={"defectStatus": "closed", "version": d["version"]})
    assert r.json().get("code") == "0", f"回填 closed 失败: {r.text[:200]}"


def _goto_prefs_tab(page):
    page.goto(f"{page.url.split('#')[0]}#/notifications", wait_until="domcontentloaded")
    expect(page.locator('[data-test="notif-center-page"]')).to_be_visible(timeout=15000)
    page.get_by_role("tab", name="订阅设置").click()
    expect(page.locator('[data-test="pref-panel"]')).to_be_visible(timeout=10000)


def _switch(page, event_type):
    return page.locator(f'[data-test="pref-switch-{event_type}"]')


def _expect_switch_on(page, event_type):
    expect(_switch(page, event_type)).to_have_class(re.compile(r"is-checked"), timeout=5000)


def _expect_switch_off(page, event_type):
    expect(_switch(page, event_type)).not_to_have_class(re.compile(r"is-checked"), timeout=5000)


def _pref_count(user_id, event_type, enabled=None):
    cond = f" AND enabled={enabled}" if enabled is not None else ""
    return int(sql_query(
        f"SELECT count(*) FROM tp_notification_pref WHERE user_id={user_id}"
        f" AND event_type='{event_type}'{cond}"))


def _notif_count(user_id, event_type):
    return int(sql_query(
        f"SELECT count(*) FROM tp_notification WHERE user_id={user_id}"
        f" AND event_type='{event_type}'"))


def test_p5_pref_unsubscribes_real_event(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    tester_id, _nick = mk_tester(api_client, uniq, prefix="p5")
    username = "p5tester_" + uniq
    pid = _mk_project(api_client, uniq)
    add_member(api_client, pid, tester_id)

    try:
        tapp = _api_as(username, TESTER_PASSWORD)
        case_id = _mk_case(tapp, pid, f"P5触发用例_{uniq}")

        inject_user_token(page, username, TESTER_PASSWORD)
        _goto_prefs_tab(page)
        expect(_switch(page, "DEFECT_CLOSED")).to_be_visible(timeout=10000)
        _expect_switch_on(page, "DEFECT_CLOSED")
        expect(page.locator('[data-test="pref-label-DEFECT_CLOSED"] .el-tag')).to_have_count(0)
        _expect_switch_off(page, "EXECUTOR_ONLINE")
        expect(page.locator('[data-test="pref-label-EXECUTOR_ONLINE"] .el-tag')).to_contain_text("默认关")

        _switch(page, "DEFECT_CLOSED").click()
        expect(page.locator('[data-test="pref-dirty-tag"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="pref-save-btn"]').click()
        expect(page.locator(".el-message").filter(has_text="已保存").last).to_be_visible(timeout=5000)
        assert _pref_count(tester_id, "DEFECT_CLOSED", 0) == 1, "偏好行落库（后端持久化）"

        page.reload(wait_until="domcontentloaded")
        _goto_prefs_tab(page)
        _expect_switch_off(page, "DEFECT_CLOSED")

        _close_defect_trigger(api_client, case_id, f"P5K1{uniq}")
        assert _notif_count(tester_id, "DEFECT_CLOSED") == 0, "退订者事件不生成（07 单点过滤）"

        _switch(page, "DEFECT_CLOSED").click()
        page.locator('[data-test="pref-save-btn"]').click()
        expect(page.locator(".el-message").filter(has_text="已保存").last).to_be_visible(timeout=5000)
        assert _pref_count(tester_id, "DEFECT_CLOSED", 1) == 1, "重新订阅 upsert 生效"

        _close_defect_trigger(api_client, case_id, f"P5K2{uniq}")
        assert _notif_count(tester_id, "DEFECT_CLOSED") == 1, "重新订阅恢复接收（真实链）"

        page.get_by_role("tab", name="通知列表").click()
        row = page.locator('[data-test="notif-table"] tbody tr').filter(has_text="缺陷已关闭").first
        expect(row).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "TC-P5-01 pref filters real event")
        tapp.close()
    finally:
        sql_query(f"DELETE FROM tp_notification WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_notification_pref WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_defect WHERE external_key LIKE 'P5K%{uniq}'")
        api_client.delete(f"/projects/{pid}/purge?force=true")
        sql_query(f"DELETE FROM tp_user_role WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user WHERE id = {tester_id}")
        assert sql_query(f"SELECT count(*) FROM tp_user WHERE id={tester_id}") == "0"


def test_p5_forced_events_grayed_and_api_rejected(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    tester_id, _nick = mk_tester(api_client, uniq, prefix="p5f")
    username = "p5ftester_" + uniq

    try:
        inject_user_token(page, username, TESTER_PASSWORD)
        _goto_prefs_tab(page)

        for ev in ("LOGIN_LOCKED", "LOGIN_SESSION_REVOKE", "REVIEW_TIMEOUT", "ENV_UNHEALTHY"):
            sw = _switch(page, ev)
            expect(sw).to_be_visible(timeout=10000)
            expect(sw).to_have_class(re.compile(r"is-disabled"), timeout=5000)
            expect(page.locator(f'[data-test="pref-label-{ev}"] .el-tag')).to_contain_text("强制")

        expect(_switch(page, "REVIEW_REMIND")).not_to_have_class(re.compile(r"is-disabled"))

        tapp = _api_as(username, TESTER_PASSWORD)
        r = tapp.put("/notification-prefs",
                     json=[{"eventType": "REVIEW_REMIND", "enabled": False},
                           {"eventType": "LOGIN_LOCKED", "enabled": False}])
        assert r.status_code == 400, f"白名单强关应 400: {r.status_code} {r.text[:200]}"
        assert "安全通知不可退订" in r.json().get("msg", ""), f"400 人话: {r.text[:200]}"
        assert _pref_count(tester_id, "REVIEW_REMIND") == 0, "混合批量整笔拒绝无半生效"

        assert_no_errors(collectors, "TC-P5-02 forced grayed + 400")
        tapp.close()
    finally:
        sql_query(f"DELETE FROM tp_notification_pref WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user_role WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user WHERE id = {tester_id}")
        assert sql_query(f"SELECT count(*) FROM tp_user WHERE id={tester_id}") == "0"
