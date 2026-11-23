
import time

import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "无", "expectedResult": "符合预期",
        "priority": 1, "confidence": 2, "endType": "web", "status": 7, "phase": 3,
        "steps": [{"stepNo": 1, "description": "登录演示页", "isAssert": 1}]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _mk_task(pid, case_id, *, status, detail, ai_class=None, note=None):
    pass
    cls = "NULL" if ai_class is None else str(ai_class)
    note_sql = "NULL" if note is None else "'" + note.replace("'", "''") + "'"
    sql_query("INSERT INTO tp_exec_task (project_id, case_id, status, error_type, error_detail, "
              "ai_failure_class, ai_failure_note, task_type, duration_ms, created_at, updated_at) "
              f"VALUES ({pid}, {case_id}, {status}, 4, '{detail}', {cls}, {note_sql}, 2, 1234, "
              "now(), now())")
    return int(sql_query(f"SELECT id FROM tp_exec_task WHERE case_id = {case_id} ORDER BY id DESC LIMIT 1"))


def test_c3_ai_classify_badge(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    try:
        pid = api_client.post("/projects", json={
            "name": f"C3归因_{uniq}", "code": f"C3{uniq.upper()}"}).json()["data"]
        case_id = _mk_case(api_client, pid, f"C3归因用例_{uniq}")
        note = "页面元素存在但断言文本与产品实际返回不一致，判定为产品缺陷"
        t_cls = _mk_task(pid, case_id, status=4, detail=f"AI归因造种失败_{uniq}", ai_class=1, note=note)
        t_plain = _mk_task(pid, case_id, status=4, detail=f"未归类造种失败_{uniq}")
        t_ok = _mk_task(pid, case_id, status=3, detail=None)

        inject_token(page)
        nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")
        page.locator('[data-test="project-ctx-select"]').click()
        option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(
            has_text=f"C3归因_{uniq}").first
        expect(option).to_be_visible(timeout=5000)
        option.click()
        table = page.locator('[data-test="exec-task-table"]')
        expect(table).to_be_visible(timeout=15000)
        row = table.locator("tbody tr").filter(has_text=f"#{t_cls}").first
        expect(row).to_be_visible(timeout=10000)
        row.locator('[data-test="exec-task-detail"]').click()
        dlg = page.locator('[data-test="exec-detail-dialog"]')
        expect(dlg).to_be_visible(timeout=10000)
        badge = dlg.locator('[data-test="exec-ai-class"]')
        expect(badge).to_be_visible(timeout=5000)
        expect(badge).to_contain_text("产品缺陷")
        expect(badge).to_contain_text(note)
        dlg.locator('[data-test="exec-detail-close"]').click()
        expect(dlg).to_be_hidden(timeout=5000)

        row2 = table.locator("tbody tr").filter(has_text=f"#{t_plain}").first
        expect(row2).to_be_visible(timeout=10000)
        row2.locator('[data-test="exec-task-detail"]').click()
        expect(dlg).to_be_visible(timeout=10000)
        expect(dlg.locator('[data-test="exec-error-block"]')).to_be_visible(timeout=5000)
        expect(dlg.locator('[data-test="exec-ai-class"]')).to_have_count(0)
        dlg.locator('[data-test="exec-detail-close"]').click()
        expect(dlg).to_be_hidden(timeout=5000)

        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        hist = page.locator('[data-test="case-history-table"]')
        expect(hist).to_be_visible(timeout=15000)
        rows = hist.locator("tbody tr")
        expect(rows).to_have_count(3, timeout=10000)
        expect(rows.nth(2).locator('[data-test="history-ai-class"]')).to_contain_text("产品缺陷")
        expect(rows.nth(0).locator('[data-test="history-ai-class"]')).to_have_count(0)
        expect(rows.nth(1).locator('[data-test="history-ai-class"]')).to_have_count(0)

        page.reload(wait_until="domcontentloaded")
        expect(hist).to_be_visible(timeout=15000)
        expect(hist.locator("tbody tr").nth(2)
               .locator('[data-test="history-ai-class"]')).to_contain_text("产品缺陷")

        st = api_client.get(f"/cases/{case_id}").json()["data"]["status"]
        assert st == 7, f"归因展示链路不应改变用例状态，实际 status={st}"

        assert_no_errors(collectors, "TC-C3-01 AI 失败归因徽标展示链路")
    finally:
        if pid:
            try:
                api_client.delete(f"/projects/{pid}/purge?force=true")
            except Exception:
                pass
            deadline = time.time() + 30
            while time.time() < deadline:
                left = sql_query(f"SELECT count(*) FROM tp_exec_task WHERE project_id = {pid}")
                if int(left or 0) == 0:
                    break
                time.sleep(2)
