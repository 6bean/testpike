
import json
import time

import httpx
from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    API_BASE,
    BASE_URL,
    assert_no_errors,
    inject_token,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect


FIXTURES_URL = "http://localhost:8899/"
UNIQ8_ACTION = json.dumps({"steps": [
    {"action": "goto", "url": "http://localhost:8899/demo_page.html"},
    {"action": "input", "target": {"css": "#username"}, "value": "o9-e2e"},
    {"action": "click", "target": {"css": "#login-btn"}},
    {"action": "expect", "text": "欢迎"},
]}, ensure_ascii=False)


def _login() -> str:
    r = httpx.post(f"{API_BASE}/auth/login",
                   json={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}, timeout=10)
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:300]}"
    return r.json()["data"]["accessToken"]


def _headers() -> dict:
    return {"Authorization": f"Bearer {_login()}"}


def _purge(api, pid):
    try:
        api.delete(f"/projects/{pid}/purge?force=true")
    except Exception:
        pass


def _mk_case_with_script(api, pid, title):
    pass
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "无", "expectedResult": "符合预期",
        "priority": 1, "confidence": 2, "endType": "web", "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "登录演示页", "isAssert": 1}]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]
    esc = UNIQ8_ACTION.replace("\\", "\\\\").replace("'", "''")
    sql_query("INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
              f"VALUES ({case_id}, 'v2', '{esc}', 1, 1) "
              "ON CONFLICT (case_id) DO UPDATE SET action_json = EXCLUDED.action_json")
    return case_id


def _trial_run_and_wait(api, pid, case_id, timeout=120):
    pass
    envs = api.get(f"/projects/{pid}/envs").json()["data"]
    assert envs, "项目无环境"
    r = api.post(f"/cases/{case_id}/trial-run", json={"envId": envs[0]["id"]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"试运行失败: {r.text[:300]}"
    tid = r.json()["data"]["taskId"]
    deadline = time.time() + timeout
    while time.time() < deadline:
        t = api.get(f"/executors/tasks/{tid}").json()["data"]
        if t["status"] >= 3:
            return t
        time.sleep(2)
    raise AssertionError(f"任务 {tid} {timeout}s 未到终态（活体执行器未消费？）")


def test_o9_rerun_flow(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    try:
        pid = api_client.post("/projects", json={
            "name": f"O9E2E甲_{uniq}", "code": f"O9A{uniq.upper()}"}).json()["data"]
        api_client.post(f"/projects/{pid}/envs", json={
            "name": f"O9环境甲_{uniq}", "envType": 2, "baseUrl": FIXTURES_URL})
        case_id = _mk_case_with_script(api_client, pid, f"O9重跑用例_{uniq}")
        task = _trial_run_and_wait(api_client, pid, case_id)
        assert task["status"] in (3, 4), f"试运行异常: {task['status']}"
        sql_query("UPDATE tp_exec_task SET status = 4, error_detail = 'E2E 造失败前置', "
                  "steps_timeline = jsonb_set(steps_timeline::jsonb, '{0,status}', '\"fail\"') "
                  f"WHERE id = {task['id']}")
        tid = task["id"]

        inject_token(page)
        nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")
        page.locator('[data-test="project-ctx-select"]').click()
        option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"O9E2E甲_{uniq}").first
        expect(option).to_be_visible(timeout=5000)
        option.click()
        table = page.locator('[data-test="exec-task-table"]')
        expect(table).to_be_visible(timeout=15000)
        row = table.locator("tbody tr").filter(has_text=f"#{tid}").first
        expect(row).to_contain_text("失败", timeout=10000)

        row.locator('[data-test="exec-task-detail"]').click()
        dlg = page.locator('[data-test="exec-detail-dialog"]')
        expect(dlg).to_be_visible(timeout=10000)
        expect(dlg.locator('[data-test="exec-task-status"]')).to_contain_text("失败")
        expect(dlg.locator('[data-test="exec-timeline"] tbody tr')).to_have_count(4)
        shot_btn = dlg.locator('[data-test^="step-shot-link-"]').first
        expect(shot_btn).to_be_visible(timeout=5000)
        shot_btn.click()
        viewer = page.locator(".el-image-viewer__wrapper")
        expect(viewer).to_be_visible(timeout=5000)
        expect(viewer.locator("img").first).to_be_visible(timeout=15000)
        viewer.locator(".el-image-viewer__close").click()
        expect(viewer).to_be_hidden(timeout=5000)
        log_btn = dlg.locator('[data-test^="step-log-link-"]').first
        expect(log_btn).to_be_visible()
        log_btn.click()
        expect(dlg.locator('[data-test="exec-console-log"].log-flash')).to_be_visible(timeout=3000)
        dlg.locator('[data-test="exec-detail-close"]').click()
        expect(dlg).to_be_hidden(timeout=5000)

        row.locator(f'[data-test="exec-task-rerun-{tid}"]').click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=5000)
        box.locator(".el-message-box__btns button:has-text('确定')").click()
        expect(page.locator(".el-message").filter(has_text="已发起重跑").first).to_be_visible(timeout=10000)

        rows = sql_query(f"SELECT id FROM tp_exec_task WHERE case_id = {case_id} ORDER BY id DESC LIMIT 1")
        t2 = int(rows.splitlines()[0])
        assert t2 != tid, "重跑未创建新任务"
        h = _headers()
        r = httpx.post(f"{API_BASE}/batch-cases/{tid}/rerun", headers=h, timeout=10)
        d = r.json()["data"]
        assert r.status_code == 200 and d["created"] is False and d["taskId"] == t2, \
            f"幂等失败: {r.text[:200]}"
        r = httpx.post(f"{API_BASE}/batch-cases/{t2}/rerun", headers=h, timeout=10)
        assert r.status_code >= 400 or r.json().get("code") != "0", f"执行中未拒绝: {r.text[:200]}"
        assert any(k in r.text for k in ("排队", "执行中")), f"拒绝非人话: {r.text[:200]}"

        st = 0
        deadline = time.time() + 120
        while time.time() < deadline:
            st = int(sql_query(f"SELECT status FROM tp_exec_task WHERE id = {t2}"))
            if st >= 3:
                break
            time.sleep(3)
        assert st >= 3, f"重跑任务 {t2} 未到终态（status={st}）"
        page.locator('[data-test="exec-task-refresh"]').click()
        expect(table.locator("tbody tr").filter(has_text=f"#{t2}").first).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "O9 单条重跑 + 步骤直达")
    finally:
        if pid:
            _purge(api_client, pid)


def test_o9_history_compare(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    try:
        pid = api_client.post("/projects", json={
            "name": f"O9E2E乙_{uniq}", "code": f"O9B{uniq.upper()}"}).json()["data"]
        api_client.post(f"/projects/{pid}/envs", json={
            "name": f"O9环境乙_{uniq}", "envType": 2, "baseUrl": FIXTURES_URL})
        case_id = _mk_case_with_script(api_client, pid, f"O9历史用例_{uniq}")
        t1 = _trial_run_and_wait(api_client, pid, case_id)
        sql_query(f"UPDATE tp_exec_task SET status = 4, error_type = 2, "
                  f"error_detail = 'E2E 历史对比失败样本_{uniq}' WHERE id = {t1['id']}")
        t2 = _trial_run_and_wait(api_client, pid, case_id)
        assert t2["id"] != t1["id"]

        inject_token(page)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        hist = page.locator('[data-test="case-history-table"]')
        expect(hist).to_be_visible(timeout=15000)
        rows = hist.locator("tbody tr")
        expect(rows).to_have_count(2, timeout=10000)

        rows.locator(".el-checkbox").nth(0).click()
        rows.locator(".el-checkbox").nth(1).click()
        cmp_btn = page.locator('[data-test="history-compare-btn"]')
        expect(cmp_btn).to_be_enabled(timeout=5000)
        cmp_btn.click()
        dlg = page.locator('[data-test="history-compare-dialog"]')
        expect(dlg).to_be_visible(timeout=5000)
        body = dlg.locator("tbody")
        expect(body).to_contain_text("失败", timeout=5000)
        expect(body).to_contain_text("成功")
        expect(body).to_contain_text(f"E2E 历史对比失败样本_{uniq}")
        expect(dlg.locator(".compare-shot img").first).to_be_visible(timeout=15000)
        dlg.locator('[data-test="history-compare-close"]').click()
        expect(dlg).to_be_hidden(timeout=5000)

        assert_no_errors(collectors, "O9 执行历史并排对比")
    finally:
        if pid:
            _purge(api_client, pid)
