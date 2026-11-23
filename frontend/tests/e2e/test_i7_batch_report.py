
import json
import os
import re
import subprocess
import time


import httpx
import pytest
from playwright.sync_api import expect

from conftest import (
    ADMIN_PASSWORD,
    BASE_URL,
    api_login,
    assert_no_errors,
    executor_runner_control,
    probe_executor_runner_unit,
)

pytestmark = pytest.mark.timeout(300)

EXECUTOR_API = os.environ.get("TP_E2E_EXECUTOR_API", "http://localhost:8082")


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject_token(page):
    pass
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def probe_runner_unit():
    pass
    return probe_executor_runner_unit()


def runner_control(unit, action):
    executor_runner_control(unit, action)


def test_i7_batch_report_card(page, collectors, api_client, uniq):
    pass
    suffix = uniq[:6]
    runner_unit = probe_runner_unit()
    runner_control(runner_unit, "stop")
    pid = bid = None
    try:
        r = api_client.post("/projects", json={"name": f"I7报告项目{suffix}",
                                               "code": f"R7P{suffix}"})
        assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
        pid = r.json()["data"]
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": f"I7报告环境{suffix}", "envType": 2,
            "baseUrl": f"https://i7r-{suffix}.tp-local.test", "endTypes": "web",
            "versionSnapshot": f"i7rep-{suffix}"})
        assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
        env_id = r.json()["data"]
        case_ids = []
        for tag in ("甲", "乙"):
            r = api_client.post("/cases", json={
                "projectId": pid, "title": f"I7报告用例{tag}{suffix}", "precondition": "无",
                "expectedResult": "符合预期", "priority": 1, "confidence": 2,
                "endType": "web", "status": 3,
                "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
            assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
            cid = r.json()["data"]["id"]
            case_ids.append(cid)
            sql(f"""INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by)
                     VALUES ({cid}, 'v2', '{{"caseId":{cid},"steps":[]}}', 2, 1)
                     ON CONFLICT (case_id) DO NOTHING""")
        r = api_client.post("/batches", json={
            "projectId": pid, "name": f"I7报告批{suffix}", "envId": env_id,
            "caseIds": case_ids})
        assert r.json().get("code") == "0", f"建批失败: {r.text[:300]}"
        bid = r.json()["data"]["id"]

        inject_token(page)
        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-report-card"]')).to_have_count(0)

        reg = httpx.post(f"{EXECUTOR_API}/internal/executors/register", timeout=15,
                         json={"name": f"i7rep-{suffix}", "endTypes": "web",
                               "browser": "chromium", "capacity": 2,
                               "executorVersion": "1.0.0", "osInfo": "e2e"}).json()
        eid = reg["data"]["executorId"]
        assert eid, f"执行器注册失败: {reg}"
        r = api_client.post(f"/batches/{bid}/start", json={})
        assert r.json().get("code") == "0", f"启动失败: {r.text[:300]}"

        api_p = httpx.Client(base_url=EXECUTOR_API, timeout=15)
        results = [("pass", None), ("fail", 2)]
        got = 0
        deadline = time.time() + 90
        while time.time() < deadline and got < 2:
            api_p.post(f"/internal/executors/heartbeat?executorId={eid}",
                       json={"runningTasks": got})
            t = (api_p.post("/internal/tasks/claim",
                            json={"executorId": eid}).json().get("data") or {}).get("task")
            if not t:
                time.sleep(2)
                continue
            want_result, want_err = results[got]
            body = {"taskId": t["taskId"], "claimToken": t["claimToken"],
                    "result": want_result, "durationMs": 1200}
            if want_err:
                body["errorType"] = want_err
                body["errorDetail"] = "E2E 活体定位失败样例"
            rr = api_p.post("/internal/tasks/result", json=body).json()
            assert (rr.get("data") or {}).get("accepted") is True, f"回传被拒: {rr}"
            got += 1
        assert got == 2, "90s 内未领满 2 任务（检查 runner 是否真停/协议口）"
        for _ in range(15):
            st = sql(f"SELECT status FROM tp_exec_batch WHERE id={bid}")
            if st in ("6", "7"):
                break
            time.sleep(2)
        assert st == "7", f"批次应收口 7（含 1 失败），实为 {st}"
        assert sql(f"SELECT count(*) FROM tp_batch_report WHERE batch_id={bid}") == "1", \
            "收口后报告快照行应恰 1 行"

        page.reload(wait_until="domcontentloaded")
        card = page.locator('[data-test="batch-report-card"]')
        expect(card).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-report-title"]')).to_contain_text("批次汇总报告")
        expect(page.locator('[data-test="batch-report-total"]')).to_have_text("2")
        expect(page.locator('[data-test="batch-report-pass"]')).to_have_text("1")
        expect(page.locator('[data-test="batch-report-fail"]')).to_have_text("1")
        expect(page.locator('[data-test="batch-report-timeout"]')).to_have_text("0")
        expect(page.locator('[data-test="batch-report-skip"]')).to_have_text("0")
        expect(page.locator('[data-test="batch-report-rate"]')).to_have_text("50.0%")
        expect(page.locator('[data-test="batch-report-envver"]')).to_have_text(f"i7rep-{suffix}")
        dist = page.locator('[data-test="batch-report-faildist"]')
        expect(dist).to_contain_text(re.compile(r"定位错误[:：]\s*1"))
        attr = page.locator('[data-test="batch-report-attr"]')
        expect(attr).to_contain_text("未归类")

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-report-card"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-report-total"]')).to_have_text("2")

        assert_no_errors(collectors, "I7 批次汇总报告卡 草稿无卡/收口呈现/reload")
    finally:
        runner_control(runner_unit, "start")
        if bid:
            sql(f"UPDATE tp_exec_task SET status = 7 WHERE batch_id = {bid} AND status IN (1,2)")
        sql(f"DELETE FROM tp_executor WHERE name LIKE 'i7rep-{suffix}%'")
        if pid:
            api_client.delete(f"/projects/{pid}")
            r = api_client.delete(f"/projects/{pid}/purge?force=true")
            assert r.json().get("code") == "0", f"purge 失败: {r.text[:200]}"
            left_rep = sql("SELECT count(*) FROM tp_batch_report br "
                           "JOIN tp_exec_batch b ON br.batch_id=b.id "
                           f"WHERE b.project_id={pid}")
            assert left_rep == "0", "purge 后报告快照孤儿行未清零（级联缺口回归）"
        assert sql(f"SELECT count(*) FROM tp_exec_batch WHERE project_id={pid}") == "0"
