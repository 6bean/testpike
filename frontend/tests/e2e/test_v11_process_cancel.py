
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(600)

REPO = Path(__file__).resolve().parents[3]
FIXTURES_URL = "http://localhost:8899/"


def _mk_case(api, pid, title):
    head = ('{"action":"goto","target":{"url":"' + FIXTURES_URL + 'demo_page.html"}},'
            '{"action":"http.request","method":"GET","url":"' + FIXTURES_URL +
            'api/order/PROCESS-E2E","expect":{"status":200}},')
    waits = ",".join(['{"action":"wait","ms":8000}'] * 12)
    action_json = ('{"version":"v2","caseId":"' + title + '","title":"' + title +
                   '","endType":"web","env":{"ENV":"dev"},"steps":[' + head + waits + ']}')
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "无", "expectedResult": "OK",
        "priority": 1, "confidence": 2, "endType": "web", "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "进程视图链", "isAssert": 1}]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]
    esc = action_json.replace("\\", "\\\\").replace("'", "''")
    sql_query("INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
              f"VALUES ({case_id}, 'v2', '{esc}', 1, 4) "
              "ON CONFLICT (case_id) DO UPDATE SET action_json = EXCLUDED.action_json")
    return case_id


def _trial_run(api, pid, case_id):
    envs = api.get(f"/projects/{pid}/envs").json()["data"]
    assert envs, "项目无环境"
    r = api.post(f"/cases/{case_id}/trial-run", json={"envId": envs[0]["id"]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"试运行失败: {r.text[:300]}"
    return r.json()["data"]["taskId"]


def _task_status(api, tid):
    return api.get(f"/executors/tasks/{tid}").json()["data"]


def _wait_status(api, tid, pred, timeout, what):
    deadline = time.time() + timeout
    while time.time() < deadline:
        t = _task_status(api, tid)
        if pred(t["status"]):
            return t
        time.sleep(1)
    raise AssertionError(f"任务 {tid} {timeout}s 未满足条件（{what}）")


def _online_executors():
    return int(sql_query(
        "select count(*) from tp_executor where status = 1 "
        "and last_heartbeat > now() - interval '20 seconds'") or 0)


@pytest.fixture()
def live_runner():
    pass
    old = subprocess.run(["pgrep", "-f", "executor/runner.py"],
                         capture_output=True, text=True).stdout.split()
    for pid in old:
        os.kill(int(pid), signal.SIGSTOP)
    proc = subprocess.Popen(
        [sys.executable, "-u", "executor/runner.py"], cwd=REPO,
        stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    try:
        deadline = time.time() + 90
        while time.time() < deadline and _online_executors() < 1:
            time.sleep(3)
        assert _online_executors() >= 1, \
            "新码 runner 90s 未在线（旧 runner 冻结干扰心跳或注册失败——先查 tp_executor 行）"
        yield proc
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
        for pid in old:
            try:
                os.kill(int(pid), signal.SIGCONT)
            except ProcessLookupError:
                pass


def test_v11_process_view_cancel_full(logged_in, api_client, collectors, uniq, live_runner):
    pass
    page = logged_in
    pid = None
    try:
        pid = api_client.post("/projects", json={
            "name": f"V11进程{uniq}", "code": f"V11P{uniq.upper()}"}).json()["data"]
        api_client.post(f"/projects/{pid}/envs", json={
            "name": f"V11环境{uniq}", "envType": 2, "baseUrl": FIXTURES_URL})
        case_id = _mk_case(api_client, pid, f"V11进程用例{uniq}")
        t1 = _trial_run(api_client, pid, case_id)
        t2 = _trial_run(api_client, pid, case_id)

        r = api_client.get("/executors/running?page=1&size=5")
        assert r.status_code == 200, f"running 端点异常: {r.status_code} {r.text[:200]}"
        data = r.json()["data"]
        assert isinstance(data.get("list"), list) and "total" in data
        for row in data["list"]:
            assert {"taskId", "projectId", "caseTitle", "elapsedMs", "timeoutMs"} <= set(row), \
                f"进程视图列契约漂移: {row}"
        r = api_client.post("/executors/tasks/999999999/cancel")
        assert r.status_code == 404 and r.json().get("code") == "40401", \
            f"不存在任务终止语义漂移: {r.status_code} {r.text[:200]}"

        _wait_status(api_client, t1, lambda s: s == 2, 90, "被领取进入执行中")

        inject_token(page)
        # merge: the process page rides the execution page tab (menu leaf
        # retired, ?tab=process deep link resolves via /process redirect).
        nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")
        page.locator('[data-test="execution-tab-process"]').first.click()
        table = page.locator('[data-test="process-table"]')
        expect(table).to_be_visible(timeout=15000)
        row1 = table.locator("tbody tr").filter(has_text=f"#{t1}").first
        expect(row1).to_be_visible(timeout=15000)
        expect(row1.locator('[data-test="process-budget-bar"]')).to_be_visible()

        batch_btn = page.locator('[data-test="process-batch-cancel"]')
        expect(batch_btn).to_be_visible(timeout=5000)
        expect(batch_btn).to_be_disabled()
        row1.locator(".el-checkbox").click()
        expect(batch_btn).to_be_enabled()
        expect(batch_btn).to_contain_text("1")
        row1.locator(".el-checkbox").click()

        row1.locator('[data-test="process-cancel-btn"]').click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=5000)
        expect(box).to_contain_text(f"#{t1}")
        box.locator("button").filter(has_text="终止").click()
        expect(page.locator(".el-message")).to_contain_text("已终止", timeout=8000)
        gone = table.locator("tbody tr").filter(has_text=f"#{t1}")
        expect(gone).to_have_count(0, timeout=15000)
        task1 = _wait_status(api_client, t1, lambda s: s == 7, 20, "终止后置 7")
        assert "已由操作人取消" in (task1.get("errorDetail") or ""), f"终止来源缺失: {task1}"
        time.sleep(20)
        r = api_client.post(f"/executors/tasks/{t1}/cancel")
        assert r.status_code == 409 and r.json().get("code") == "40902", \
            f"已终态重复终止幂等漂移: {r.status_code} {r.text[:200]}"

        # URL already carries ?tab=process (tab watch writes it back); reload
        # remounts the page and the query picks the process tab again (same-hash
        # goto would not remount — 372 lesson).
        page.reload(wait_until="domcontentloaded")
        table2 = page.locator('[data-test="process-table"]')
        expect(table2).to_be_visible(timeout=15000)
        expect(table2.locator("tbody tr").filter(has_text=f"#{t1}")).to_have_count(0)
        t2_task = _wait_status(api_client, t2, lambda s: s >= 3, 150, "自然到终态（不受终止扰动）")
        assert t2_task["status"] in (3, 4, 5, 6), f"t2 应自然终态非取消: {t2_task}"

        r = api_client.post("/executors/tasks/batch-cancel", json=[t1, t2, 999999999])
        assert r.status_code == 200, f"批取消应 200（部分成功）: {r.text[:200]}"
        body = r.json()["data"]
        assert body["cancelled"] == 0 and \
            sorted(body["rejectedTaskIds"]) == sorted([t1, t2, 999999999]), \
            f"部分成功语义漂移: {body}"

        expect(page.locator('[data-test="process-page"]')).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "T231 进程管理页")
    finally:
        if pid:
            try:
                api_client.delete(f"/projects/{pid}/purge?force=true")
            except Exception:  # noqa: BLE001
                pass
