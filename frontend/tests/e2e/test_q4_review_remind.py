
import httpx
from playwright.sync_api import expect

from conftest import (
    TESTER_PASSWORD,
    assert_no_errors,
    inject_user_token,
    mk_tester,
    sql_query,
)

CORE_BASE = "http://localhost:8083"


def _scan():
    r = httpx.post(f"{CORE_BASE}/internal/cases/review-remind-scan", timeout=30)
    assert r.status_code == 200 and r.json().get("code") == "0", f"扫描触发失败: {r.text[:300]}"
    return r.json()["data"]


def test_q4_overdue_badge_and_notification(page, collectors, api_client, uniq):
    pass
    tester_id, _nick = mk_tester(api_client, uniq, prefix="q4")
    username = "q4tester_" + uniq

    r = api_client.post("/projects", json={"name": f"Q4E2E_{uniq}", "code": f"Q4E{uniq[:6].upper()}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    api_client.post(f"/projects/{pid}/members", json={"userId": tester_id, "role": 1})

    r = api_client.post("/cases", json={
        "projectId": pid, "title": f"催办角标用例{uniq}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2, "endType": "web",
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
    assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case = r.json()["data"]
    r = api_client.post(f"/cases/{case['id']}/assign", json={"assigneeId": tester_id})
    assert r.json().get("code") == "0", f"分配失败: {r.text[:300]}"
    sql_query(f"UPDATE tp_test_case SET assigned_at = now() - interval '80 hours' WHERE id = {case['id']}")

    pid_done = False
    try:
        d = _scan()
        assert d["reminded"] >= 1, f"催办应至少 1 条: {d}"

        inject_user_token(page, username, TESTER_PASSWORD)
        badge = page.locator('[data-test="review-overdue-badge"]')
        expect(badge).to_be_visible(timeout=15000)
        expect(badge).to_contain_text("超时 1")
        card = page.locator('[data-test="todo-review-card"]')
        expect(card).to_contain_text("1")

        page.reload(wait_until="domcontentloaded")
        expect(badge).to_be_visible(timeout=15000)
        expect(badge).to_contain_text("超时 1")

        page.goto(f"{page.url.split('#')[0]}#/notifications", wait_until="domcontentloaded")
        expect(page.locator('[data-test="notif-table"]')).to_be_visible(timeout=15000)
        row = page.locator('[data-test="notif-table"] tbody tr').filter(
            has_text="评审催办").first
        expect(row).to_be_visible(timeout=10000)
        expect(row).to_contain_text(case["caseNo"])

        _scan()
        n1 = int(sql_query(
            f"SELECT count(*) FROM tp_notification WHERE event_type='REVIEW_REMIND' AND user_id={tester_id}"))
        assert n1 == 1, f"同日重扫通知应仍 1 条, got {n1}"

        api_client.delete(f"/projects/{pid}/purge?force=true")
        pid_done = True
        page.goto(f"{page.url.split('#')[0]}#/dashboard", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="dashboard-todo-cards"]')).to_be_visible(timeout=15000)
        expect(badge).to_have_count(0)

        assert_no_errors(collectors, "TC-Q4-01 overdue badge")
    finally:
        if not pid_done:
            api_client.delete(f"/projects/{pid}/purge?force=true")
        sql_query(f"DELETE FROM tp_notification WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user_role WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user_project WHERE user_id = {tester_id}")
        sql_query(f"DELETE FROM tp_user WHERE id = {tester_id}")
