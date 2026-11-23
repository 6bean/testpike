
import json
import os
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path

import httpx
import pytest
from conftest import (
    ADMIN_PASSWORD,
    API_BASE,
    TESTER_PASSWORD,
    add_member,
    api_login,
    assert_no_errors,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = API_BASE
BASE = API_BASE.replace("/api/v1", "").replace(":8081", ":5173")
CORE = os.environ.get("TP_L3_CORE_BASE", "http://localhost:8083")
STUB_PORT = int(os.environ.get("MOCK_LLM_PORT", "18100"))
HITS = f"/tmp/testpike-t906-sample-hits-{uuid.uuid4().hex[:6]}.jsonl"
KEY_SAMPLE = "ai.appauto.matrix-sample"
CAND_RE = re.compile(r"^- ([A-Z_]+) × ([A-Z_]+) × ([A-Z_]+)", re.MULTILINE)
STUB_SCRIPT = Path(__file__).resolve().parents[3] / "tools" / "mock-llm-matrixsample.py"


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
         "-d", "testpike", "-tAc", q],
        capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"SQL 失败: {q} -> {r.stderr[:200]}")
    return r.stdout.strip()


def sql_rows(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
         "-d", "testpike", "-tAc", q],
        capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"SQL 失败: {q} -> {r.stderr[:200]}")
    return r.stdout.strip().splitlines()


def quote(text):
    return "'" + text.replace("'", "''") + "'"


def hits_count():
    try:
        with open(HITS, encoding="utf-8") as f:
            return sum(1 for _ in f)
    except FileNotFoundError:
        return 0


def last_hit():
    with open(HITS, encoding="utf-8") as f:
        return json.loads(f.readlines()[-1])


def put_switch(val):
    pass
    cfgs = httpx.get(f"{CORE}/internal/configs", timeout=10).json().get("data") or []
    item = next((x for x in cfgs if x.get("configKey") == KEY_SAMPLE), None)
    assert item, f"{KEY_SAMPLE} 配置行不存在（V183 未落库？）"
    r = httpx.put(f"{CORE}/internal/configs/{item['id']}",
                  json={**item, "configValue": str(val)},
                  headers={"X-Operator-Id": "1"}, timeout=10)
    assert r.status_code == 200, f"开关热更失败: {r.text[:200]}"


def _inject_token(page):
    pass
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page, token
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def _app_steps():
    return json.dumps({"version": "v2", "steps": [
        {"action": "app.launch", "target": {"pkg": "com.demo", "chain": "demo"}},
        {"action": "app.tap", "target": {"resourceId": "com.demo:id/btn", "chain": "demo"}},
        {"action": "app.input", "target": {"resourceId": "com.demo:id/et", "chain": "demo"},
         "value": "hi"}]}, ensure_ascii=False)


def _mk_dev_user(api, pid, uid):
    pass
    uname = f"mxdev{uid}"
    r = api.post("/users", json={"username": uname, "password": TESTER_PASSWORD,
                                 "displayName": f"矩阵开发{uid}",
                                 "email": f"{uname}@test.local", "phone": "", "role": 3})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建开发替身失败: {r.text[:300]}"
    duser = r.json()["data"]
    add_member(api, pid, duser)
    return duser, api_login(uname, TESTER_PASSWORD)


def _strip_self403_sample(collectors):
    pass
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not ("/api/v1/appauto/matrix-sample" in e["url"] and e["status"] == 403)]
    collectors["console_errors"] = [
        c for c in collectors["console_errors"] if "status of 403" not in c]


def test_appauto_matrix_fanout_wizard_and_report(page, collectors):
    pass
    page, token = _inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    uid = uuid.uuid4().hex[:6]
    code = f"e2emx{uid}"

    r = api.post("/projects", json={"name": f"E2E矩阵{uid}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = int(r.json()["data"])
    sql(f"INSERT INTO tp_project_env (project_id, name, env_type, base_url, end_types, browsers,"
        f" version_snapshot) VALUES ({pid}, '扇出环境', 2, 'http://demo.local', 'web,mobile',"
        f" 'chromium', 'v1')")
    env_id = int(sql(f"SELECT id FROM tp_project_env WHERE project_id={pid}"))
    sql(f"INSERT INTO tp_test_case (case_no, project_id, title, precondition, expected_result,"
        f" priority, end_type, status, version, deleted, flaky, automation_target,"
        f" appauto_engine, created_by) VALUES ('M{uid.upper()}', {pid},"
        f" '矩阵扇出E2E用例', '前置', '预期', 1, 'mobile', 3, 1, 0, 0, 'app',"
        f" 'MOBILEWRIGHT', 1)")
    case_id = int(sql(f"SELECT id FROM tp_test_case WHERE project_id={pid}"))
    sql(f"INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by,"
        f" version) VALUES ({case_id}, 'v2', {quote(_app_steps())}, 1, 1, 1)")
    sql(f"INSERT INTO tp_appauto_device (project_id, name, serial, device_type, model,"
        f" os_version, health_state, last_seen_at) VALUES ({pid}, 'E2E探针机', 'emu-{uid}',"
        f" 'EMULATOR', 'Pixel-7', '13', 'GREEN', now() - interval '30 seconds')")
    device_id = int(sql(f"SELECT id FROM tp_appauto_device WHERE project_id={pid}"))
    sql(f"INSERT INTO tp_appauto_device (project_id, name, serial, device_type, health_state,"
        f" last_seen_at) VALUES ({pid}, 'E2E探针真机', 'adb-{uid}', 'ADB', 'GREEN',"
        f" now() - interval '30 seconds')")
    adb_id = int(sql(f"SELECT id FROM tp_appauto_device WHERE project_id={pid}"
                     f" AND device_type='ADB'"))
    stub = None
    llm_cfg_id = None
    duser = None

    try:
        stub = subprocess.Popen(
            [sys.executable, str(STUB_SCRIPT)],
            env={**os.environ, "MOCK_LLM_PORT": str(STUB_PORT), "MOCK_LLM_HITS": HITS},
            stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        for _ in range(40):
            try:
                httpx.post(f"http://127.0.0.1:{STUB_PORT}/v1/chat/completions",
                           json={"messages": []}, timeout=1)
                break
            except (httpx.HTTPError, OSError):
                time.sleep(0.5)
        assert hits_count() > 0, "供应商位桩预热失败（hits 无基线行）"
        rcfg = httpx.post(f"{CORE}/internal/llm-configs",
                          json={"name": f"E2E抽样桩端点-{uid}", "provider": "openai",
                                "modelName": "matrixsample-stub",
                                "apiKey": "e2e-stub-not-secret",
                                "baseUrl": f"http://127.0.0.1:{STUB_PORT}/v1",
                                "status": 1, "isDefault": 0, "modelType": 1,
                                "description": "T323 抽样包 E2E 供应商位桩"},
                          headers={"X-Operator-Id": "1"}, timeout=10).json()
        assert rcfg.get("code") == "0" and (rcfg.get("data") or {}).get("id"), \
            f"LLM 配置创建失败: {rcfg}"
        llm_cfg_id = int(rcfg["data"]["id"])
        sql(f"UPDATE tp_project SET llm_config_id={llm_cfg_id} WHERE id={pid}")
        page.goto(f"{BASE}/#/cases/{case_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
        entry = page.locator('[data-test="detail-matrix-fanout"]')
        expect(entry).to_be_visible(timeout=15000)
        expect(entry).to_be_enabled()
        entry.click()
        dialog = page.locator('[data-test="matrix-fanout-dialog"]')
        expect(dialog).to_be_visible(timeout=15000)

        put_switch("0")
        h_off = hits_count()
        sample_btn = page.locator('[data-test="matrix-sample-btn"]')
        expect(sample_btn).to_be_visible(timeout=8000)
        expect(sample_btn).to_be_enabled(timeout=8000)
        sample_btn.click()
        serr = page.locator('[data-test="matrix-sample-error"]')
        expect(serr).to_be_visible(timeout=15000)
        expect(serr).to_contain_text("矩阵智能抽样未开启")
        assert hits_count() == h_off, "关态点荐不应真实外呼（hits 不增）"
        expect(page.locator('[data-test="matrix-sample-result"]')).to_have_count(0)

        put_switch("1")
        h_on = hits_count()
        sample_btn.click()
        expect(page.locator('[data-test="matrix-sample-error"]')).to_have_count(0)
        result = page.locator('[data-test="matrix-sample-result"]')
        expect(result).to_be_visible(timeout=30000)
        applied = page.locator('[data-test="matrix-sample-applied"]')
        expect(applied).to_be_visible(timeout=8000)
        assert hits_count() == h_on + 1, "开态点荐应真实外呼恰一次"
        last = last_hit()
        cand_rows = CAND_RE.findall(last.get("user_full", ""))
        assert len(cand_rows) >= 2, f"候选面下传异常: rows={len(cand_rows)}"
        first_key = "-".join(cand_rows[0])
        second_key = "-".join(cand_rows[1])
        expect(page.locator(f'[data-test="matrix-combo-{first_key}"].checked')).to_be_visible(timeout=8000)
        expect(page.locator(f'[data-test="matrix-combo-{second_key}"].checked')).to_be_visible(timeout=8000)
        expect(applied).to_contain_text("2")
        expect(applied).to_contain_text("仅为建议")
        cov = page.locator('[data-test="matrix-sample-coverage"]')
        expect(cov).to_contain_text("候选组合", timeout=8000)
        expect(cov).to_contain_text("机型现装面")
        expect(page.locator('[data-test="matrix-sample-cost"]')).to_contain_text("推荐仅供参考")

        page.locator('[data-test="matrix-cancel-btn"]').click()
        expect(dialog).to_be_hidden(timeout=8000)
        entry.click()
        expect(dialog).to_be_visible(timeout=15000)
        expect(result).to_have_count(0)
        expect(page.locator('[data-test="matrix-combo-list"] .checked')).to_have_count(0)

        combos = page.locator('[data-test="matrix-combo-list"]')
        expect(combos).to_be_visible(timeout=10000)
        mw_emu = page.locator('[data-test="matrix-combo-MOBILEWRIGHT-ANDROID-EMULATOR"]')
        mw_real = page.locator('[data-test="matrix-combo-MOBILEWRIGHT-ANDROID-REAL"]')
        expect(mw_emu).to_be_visible(timeout=10000)
        expect(mw_real).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="matrix-combo-HYPIUM-HARMONY_NEXT-REAL"]')).to_have_count(0)
        mw_emu.click()
        mw_real.click()
        expect(page.locator('[data-test="matrix-selected-count"]')).to_contain_text("2")

        precheck_btn = page.locator('[data-test="matrix-precheck-btn"]')
        expect(precheck_btn).to_be_enabled(timeout=8000)
        precheck_btn.click()
        msg = page.locator('[data-test="matrix-precheck-message"]')
        expect(msg).to_be_visible(timeout=15000)
        expect(msg).to_contain_text("预计")
        expect(msg).to_contain_text("排队至")
        expect(page.locator('[data-test="matrix-precheck-tasks"]')).to_have_text("2")

        dispatch_btn = page.locator('[data-test="matrix-dispatch-btn"]')
        expect(dispatch_btn).to_be_enabled(timeout=10000)
        dispatch_btn.click()
        receipt = page.locator('[data-test="matrix-dispatch-result"]')
        expect(receipt).to_be_visible(timeout=15000)
        expect(receipt).to_contain_text("扇出 2 个执行任务")
        batch_id = int(sql(f"SELECT id FROM tp_exec_batch WHERE project_id={pid}"))
        n_task = int(sql(f"SELECT count(*) FROM tp_exec_task WHERE batch_id={batch_id}"
                         f" AND task_type=6"))
        assert n_task == 2, f"派发落库对账失败: batch={batch_id} task={n_task}"
        refs = [json.loads(x) for x in sql_rows(
            f"SELECT action_json::text FROM tp_exec_task WHERE batch_id={batch_id}")]
        got = {(r.get("leaseRef") or {}).get("deviceId") for r in refs}
        assert got == {device_id, adb_id}, f"两谱设备寻址对账失败: {got}"
        page.locator('[data-test="matrix-goto-batch"]').click()
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)

        duser, dtoken = _mk_dev_user(api, pid, uid)
        rt = httpx.post(f"{API}/appauto/matrix-fanout",
                        json={"projectId": pid, "caseId": case_id, "name": f"越权批-{uid}",
                              "envId": env_id,
                              "combos": [{"engineCode": "MOBILEWRIGHT",
                                          "platformCode": "ANDROID",
                                          "deviceKindCode": "EMULATOR",
                                          "envProfileId": None}]},
                        headers={"Authorization": f"Bearer {dtoken}"}, timeout=30)
        assert rt.status_code == 403 and str(rt.json().get("code")) != "0", \
            f"developer 派发应 403 权限拒，实际 {rt.status_code} {rt.text[:200]}"
        n_task_after = int(sql(f"SELECT count(*) FROM tp_exec_task WHERE batch_id={batch_id}"))
        assert n_task_after == 2, "越权派发不应写库（fail-closed）"

        ids = sql_rows(f"SELECT id FROM tp_exec_task WHERE batch_id={batch_id}")
        envs = [json.loads(x) for x in sql_rows(
            f"SELECT action_json::text FROM tp_exec_task WHERE batch_id={batch_id}")]
        fail_id = next(i for i, e in zip(ids, envs)
                       if (e.get("leaseRef") or {}).get("deviceId") == device_id)
        ok_id = next(i for i in ids if i != fail_id)
        fail_timeline = json.dumps([
            {"stepNo": 1, "action": "app.launch", "result": "pass", "durationMs": 900,
             "detail": ""},
            {"stepNo": 2, "action": "app.tap", "result": "fail", "durationMs": 1200,
             "detail": f"找不到控件 com.demo:id/btn-{uid}"}], ensure_ascii=False)
        ok_timeline = json.dumps([
            {"stepNo": 1, "action": "app.launch", "result": "pass", "durationMs": 800,
             "detail": ""}], ensure_ascii=False)
        sql(f"UPDATE tp_exec_task SET status=4, steps_timeline={quote(fail_timeline)},"
            f" finished_at=now() WHERE id={fail_id}")
        sql(f"UPDATE tp_exec_task SET status=3, steps_timeline={quote(ok_timeline)},"
            f" finished_at=now() WHERE id={ok_id}")

        page.goto(f"{BASE}/#/appauto/matrix-report?projectId={pid}&caseId={case_id}",
                  wait_until="domcontentloaded")
        expect(page.locator('[data-test="matrix-report-page"]')).to_be_visible(timeout=15000)
        table = page.locator('[data-test="matrix-report-table"]')
        expect(table).to_be_visible(timeout=15000)
        model = page.locator('[data-test="matrix-report-model"]')
        expect(model.first).to_contain_text("Pixel-7", timeout=15000)
        expect(model).to_have_count(1)
        heat = page.locator('[data-test^="matrix-report-heat-"]')
        expect(heat.first).to_be_visible(timeout=8000)
        expect(heat.first).to_contain_text("%")
        diff = page.locator('[data-test="matrix-report-diff-message"]')
        expect(diff.first).to_contain_text("失败集中在第", timeout=8000)
        expect(diff.first).to_contain_text("共 1 次")

        page.locator('[data-test="matrix-report-table"] .el-table__expand-icon').first.click()
        steps_table = page.locator('[data-test="matrix-report-steps-table"]')
        expect(steps_table).to_be_visible(timeout=10000)
        expect(steps_table).to_contain_text("app.tap")
        expect(page.locator('[data-test="matrix-report-sample-2"]')).to_contain_text(
            f"找不到控件 com.demo:id/btn-{uid}")

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="matrix-report-table"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="matrix-report-model"]').first).to_contain_text("Pixel-7")

        _strip_self403_sample(collectors)
        assert_no_errors(collectors, "appauto 矩阵扇出+智能推荐+报告 E2E")
    finally:
        try:
            put_switch("1")
        except (httpx.HTTPError, AssertionError):
            print("尾清：开关热更口不可达，已走 SQL 兜底恢复出厂 1")
        sql(f"UPDATE tp_sys_config SET config_value='1' WHERE config_key='{KEY_SAMPLE}'")
        if stub is not None:
            stub.terminate()
        if os.path.exists(HITS):
            os.remove(HITS)
        batch_ids = sql_rows(f"SELECT id FROM tp_exec_batch WHERE project_id={pid}")
        for bid in batch_ids:
            sql(f"DELETE FROM tp_batch_case WHERE batch_id={bid}")
            sql(f"DELETE FROM tp_batch_param_snapshot WHERE batch_id={bid}")
            sql(f"DELETE FROM tp_batch_runtime_change WHERE batch_id={bid}")
        sql(f"DELETE FROM tp_exec_task WHERE project_id={pid}")
        if batch_ids:
            sql(f"DELETE FROM tp_exec_batch WHERE project_id={pid}")
        sql(f"DELETE FROM tp_script WHERE case_id={case_id}")
        sql(f"DELETE FROM tp_training_sample WHERE case_id IN "
            f"(SELECT id FROM tp_test_case WHERE project_id={pid})")
        sql(f"DELETE FROM tp_test_case WHERE project_id={pid}")
        sql(f"DELETE FROM tp_appauto_lease_run WHERE device_id IN "
            f"(SELECT id FROM tp_appauto_device WHERE project_id={pid})")
        sql(f"DELETE FROM tp_appauto_device WHERE project_id={pid}")
        sql(f"DELETE FROM tp_project_env WHERE project_id={pid}")
        if duser is not None:
            api.delete(f"/users/{duser}")
        sql(f"DELETE FROM tp_user_project WHERE project_id={pid}")
        sql(f"DELETE FROM tp_project WHERE id={pid}")
        if llm_cfg_id is not None:
            sql(f"DELETE FROM tp_llm_config WHERE id={llm_cfg_id}")
        left = sql(f"SELECT (SELECT count(*) FROM tp_project WHERE id={pid})||'/'"
                   f"||(SELECT count(*) FROM tp_exec_task WHERE project_id={pid})||'/'"
                   f"||(SELECT count(*) FROM tp_exec_batch WHERE project_id={pid})||'/'"
                   f"||(SELECT count(*) FROM tp_test_case WHERE project_id={pid})||'/'"
                   f"||(SELECT count(*) FROM tp_llm_config WHERE id={llm_cfg_id})")
        assert left == "0/0/0/0/0", f"尾清残留: {left}"
