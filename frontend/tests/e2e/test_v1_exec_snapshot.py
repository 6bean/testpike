
import json
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

pytestmark = pytest.mark.timeout(900)

FIXTURES_URL = "http://localhost:8899/"
PHONE = "13812345678"


def _snapshot_doc():
    return json.dumps({"version": "v2", "caseId": "TC-V1-E2E", "title": "快照页签",
                       "endType": "web", "env": {"ENV": "dev"}, "steps": [
        {"action": "goto", "target": {"url": f"{FIXTURES_URL}demo_page.html"}},
        {"action": "http.request", "method": "POST", "url": f"{FIXTURES_URL}api/order",
         "headers": {"Authorization": "***", "Content-Type": "application/json"},
         "body": {"orderNo": "E2E-SNAP", "buyer": PHONE},
         "expect": {"status": 200}},
        {"action": "http.request", "method": "GET", "url": f"{FIXTURES_URL}api/order/NO-SUCH-ORDER",
         "expect": {"status": 200}},
    ]}, ensure_ascii=False)


def _mk_case(api, pid, title, action_json):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "无", "expectedResult": "OK",
        "priority": 1, "confidence": 2, "endType": "web", "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "快照链", "isAssert": 1}]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]
    esc = action_json.replace("\\", "\\\\").replace("'", "''")
    sql_query("INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
              f"VALUES ({case_id}, 'v2', '{esc}', 1, 4) "
              "ON CONFLICT (case_id) DO UPDATE SET action_json = EXCLUDED.action_json")
    return case_id


def _trial_run_and_wait(api, pid, case_id, timeout=240):
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


def _open_task_detail(page, proj_name, tid):
    inject_token(page)
    nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=proj_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
    table = page.locator('[data-test="exec-task-table"]')
    expect(table).to_be_visible(timeout=15000)
    row = table.locator("tbody tr").filter(has_text=f"#{tid}").first
    expect(row).to_contain_text("失败", timeout=20000)
    row.locator('[data-test="exec-task-detail"]').click()
    dlg = page.locator('[data-test="exec-detail-dialog"]')
    expect(dlg).to_be_visible(timeout=10000)
    return dlg


def test_v1_snapshot_tabs_render(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    try:
        pid = api_client.post("/projects", json={
            "name": f"V1快照{uniq}", "code": f"V1S{uniq.upper()}"}).json()["data"]
        api_client.post(f"/projects/{pid}/envs", json={
            "name": f"V1环境{uniq}", "envType": 2, "baseUrl": FIXTURES_URL})
        case_id = _mk_case(api_client, pid, f"V1快照用例{uniq}", _snapshot_doc())
        task = _trial_run_and_wait(api_client, pid, case_id)
        assert task["status"] == 4, f"任务应失败收口: {task}"
        tid = task["id"]

        dlg = _open_task_detail(page, f"V1快照{uniq}", tid)
        snap = dlg.locator('[data-test="exec-snapshots"]')
        expect(snap).to_be_visible(timeout=10000)

        tab_heads = snap.locator(".el-tabs__item")
        expect(tab_heads).to_have_count(2, timeout=10000)
        expect(tab_heads.filter(has_text="步骤 2")).to_contain_text("http.request")

        page.context.grant_permissions(["clipboard-write"], origin=BASE_URL)
        tab_heads.filter(has_text="步骤 2").click()
        pane = snap.locator(".el-tab-pane:visible")
        req_col = pane.locator('[data-test="snapshot-col-request"]')
        expect(req_col).to_contain_text("POST", timeout=5000)
        expect(req_col).to_contain_text("***MASKED***", timeout=5000)
        expect(req_col).not_to_contain_text("e2e-super-secret")
        expect(req_col).to_contain_text("138****5678", timeout=5000)
        expect(pane.locator('[data-test="snapshot-col-response"]')).to_contain_text("E2E-SNAP", timeout=5000)
        expect(pane.locator('[data-test="snapshot-col-assertions"]')).to_contain_text('"expected": 200', timeout=5000)

        tab_heads.filter(has_text="步骤 3").click()
        pane = snap.locator(".el-tab-pane:visible")
        expect(pane.locator('[data-test="snapshot-col-response"]')).to_contain_text("404", timeout=5000)
        expect(pane.locator('[data-test="snapshot-col-assertions"]')).to_contain_text("false", timeout=5000)

        snap.locator('[data-test="snapshot-copy-request-3"]').click()
        expect(page.locator(".el-message")).to_contain_text("复制到剪贴板", timeout=5000)

        assert_no_errors(collectors, "T230 协议快照页签")
    finally:
        if pid:
            try:
                api_client.delete(f"/projects/{pid}/purge?force=true")
            except Exception:  # noqa: BLE001
                pass


def test_v1_snapshot_block_hidden_for_legacy_task(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    try:
        pid = api_client.post("/projects", json={
            "name": f"V1存量{uniq}", "code": f"V1L{uniq.upper()}"}).json()["data"]
        api_client.post(f"/projects/{pid}/envs", json={
            "name": f"V1存量环境{uniq}", "envType": 2, "baseUrl": FIXTURES_URL})
        ui_only = json.dumps({"version": "v2", "caseId": "TC-V1-LEGACY", "title": "纯UI",
                              "endType": "web", "steps": [
            {"action": "goto", "target": {"url": f"{FIXTURES_URL}demo_page.html"}},
            {"action": "expect", "target": {"text": "不应出现文本XYZ-ZZ"}},
        ]}, ensure_ascii=False)
        case_id = _mk_case(api_client, pid, f"V1存量用例{uniq}", ui_only)
        task = _trial_run_and_wait(api_client, pid, case_id)
        assert task["status"] == 4, f"断言失败步应收口 4: {task['status']}"
        dlg = _open_task_detail(page, f"V1存量{uniq}", task["id"])
        expect(dlg.locator('[data-test="exec-task-status"]')).to_contain_text("失败", timeout=10000)
        expect(dlg.locator('[data-test="exec-snapshots"]')).to_have_count(0)
        assert_no_errors(collectors, "T230 快照区隐藏")
    finally:
        if pid:
            try:
                api_client.delete(f"/projects/{pid}/purge?force=true")
            except Exception:  # noqa: BLE001
                pass
