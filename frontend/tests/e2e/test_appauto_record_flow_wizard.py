
import json
import os
import pathlib
import subprocess
import time
import uuid

import httpx
import pytest
from conftest import (ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors,
                      launch_provider_stub)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = BASE_URL.replace(":5173", ":8081")
CORE = "http://localhost:8083"
SWITCH_KEY = "ai.appauto.record-to-flow"
STUB_PORT = 18097
HITS = "/tmp/testpike-t309-recordflow-e2e-hits.jsonl"


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def _inject_token(page):
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page, token
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def _set_switch(api, value):
    pass
    rows = api.get("/api/v1/configs", params={"groupName": "APP自动化"}).json()["data"]
    hit = [c for c in rows if c["configKey"] == SWITCH_KEY]
    assert hit, "录制转流程开关键缺失（V168 种子未落库？）"
    cfg = hit[0]
    r = api.put(f"/api/v1/configs/{cfg['id']}", json={
        "configKey": SWITCH_KEY, "configValue": value,
        "groupName": cfg.get("groupName") or "APP自动化",
        "valueType": cfg.get("valueType") or "int",
        "encryptFlag": cfg.get("encryptFlag", 0),
        "description": cfg.get("description") or ""})
    assert r.status_code == 200, f"配置翻转失败: {r.status_code} {r.text[:200]}"


def _strip_self403(collectors):
    pass
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not ("/api/v1/appauto/record-flow" in e["url"] and e["status"] == 403)]
    collectors["console_errors"] = [
        c for c in collectors["console_errors"] if "status of 403" not in c]


def _insert_task(pid, cid, result_ext):
    pass
    esc = str(result_ext).replace("'", "''") if result_ext else None
    cols = "project_id, case_id, status, schema_version, action_json, env_base_url"
    vals = f"{pid}, {cid}, 3, 'v2', '{{\"version\":\"v2\",\"steps\":[]}}', 'http://localhost'"
    if esc is not None:
        cols += ", result_ext"
        vals += f", '{esc}'::jsonb"
    return sql(f"INSERT INTO tp_exec_task ({cols}) VALUES ({vals}) RETURNING id").splitlines()[0]


def test_record_flow_wizard_draft_confirm_persist(page, collectors):
    pass
    page, token = _inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    uniq = uuid.uuid4().hex[:6]
    pid = None
    stub = None
    flipped = False
    llm_cfg_id = None
    task_ids = []
    try:
        pid = api.post("/api/v1/projects",
                       json={"name": f"录制转流程E2E项目{uniq}", "code": f"RFW{uniq[:6].upper()}"}
                       ).json()["data"]
        cbody = api.post("/api/v1/cases", json={
            "projectId": pid,
            "title": f"登录流程回放_{uniq}",
            "precondition": f"前置：App 已安装（{uniq}）",
            "expectedResult": f"预期：登录成功（{uniq}）",
            "priority": 0, "confidence": 1, "endType": "mobile", "status": 1,
            "steps": [{"stepNo": 1, "description": f"启动应用（{uniq}）", "isAssert": 0}],
        }).json()["data"]
        cid = cbody["id"] if isinstance(cbody, dict) else cbody
        ver = int(sql(f"SELECT version FROM tp_test_case WHERE id={cid}"))
        r_upd = api.put(f"/api/v1/cases/{cid}", json={
            "automationTarget": "app", "appautoEngine": "MOBILEWRIGHT", "version": ver})
        assert r_upd.status_code == 200, f"挂 app 目标失败: {r_upd.text[:200]}"
        doc = {"version": "v2", "caseId": cid, "title": f"seed_{uniq}", "endType": "mobile",
               "steps": [{"stepId": 1, "action": "app.launch", "target": {"text": "被测应用"}}],
               "env": {"ENV": "dev"}}
        r_seed = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                            json={"caseId": cid, "schemaVersion": "v2",
                                  "actionJson": json.dumps(doc, ensure_ascii=False),
                                  "generatedBy": 1})
        assert r_seed.status_code == 200 and r_seed.json().get("code") == "0", \
            f"落脚本基线失败: {r_seed.text[:200]}"
        cfg = httpx.post(f"{CORE}/internal/llm-configs", headers={"X-Operator-Id": "1"}, timeout=10,
                         json={"name": f"录制转流程E2E桩端点-{uniq}", "provider": "openai",
                               "modelName": "nlstep-stub", "apiKey": "e2e-stub-not-secret",
                               "baseUrl": f"http://127.0.0.1:{STUB_PORT}/v1",
                               "status": 1, "isDefault": 0, "modelType": 1,
                               "description": "录制反推E2E供应商位桩"}).json()
        assert cfg.get("code") == "0", f"清单行创建失败: {cfg}"
        llm_cfg_id = int(cfg["data"]["id"])
        sql(f"UPDATE tp_project SET llm_config_id={llm_cfg_id} WHERE id={pid}")

        events = {"recordEvents": {"level": "scrcpy", "dropped": 0, "events": [
            {"type": "tap", "x": 100, "y": 200, "ts": 1},
            {"type": "tap", "x": 120, "y": 480, "ts": 2},
            {"type": "key", "code": "KEYCODE_BACK", "ts": 3}]}}
        task_ok = _insert_task(pid, cid, json.dumps(events, ensure_ascii=False))
        task_ids.append(task_ok)
        task_empty = _insert_task(pid, cid, None)
        task_ids.append(task_empty)

        _set_switch(api, "0")
        flipped = False

        try:
            stub = launch_provider_stub("mock-llm-nlstep.py", STUB_PORT,
                                        {"MOCK_LLM_HITS": HITS})
        except AssertionError as stub_err:
            pytest.skip(f"供应商位桩未起（环境件）：{stub_err}，录制转流程开态段跳过")

        page.goto(f"{BASE_URL}/#/cases/{cid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="detail-script-tree-edit"]').click()
        dialog = page.locator('[data-test="step-tree-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        rec_btn = page.locator('[data-test="step-tree-record-open"]')
        expect(rec_btn).to_be_visible(timeout=10000)
        expect(rec_btn).to_be_enabled(timeout=5000)

        rec_btn.click()
        rec_dialog = page.locator('[data-test="step-tree-record-dialog"]')
        expect(rec_dialog).to_be_visible(timeout=10000)
        page.locator('[data-test="step-tree-record-task"] input').fill(task_empty)
        page.locator('[data-test="step-tree-record-load"]').click()
        empty_hint = page.locator('[data-test="step-tree-record-empty-events"]')
        expect(empty_hint).to_be_visible(timeout=15000)
        expect(empty_hint).to_contain_text("没有录制事件")
        expect(page.locator('[data-test="step-tree-record-generate"]')).to_have_count(0)

        page.locator('[data-test="step-tree-record-task"] input').fill(task_ok)
        page.locator('[data-test="step-tree-record-load"]').click()
        events_row = page.locator('[data-test="step-tree-record-events"]')
        expect(events_row).to_be_visible(timeout=15000)
        expect(events_row).to_contain_text("3")
        expect(events_row).to_contain_text("scrcpy")
        page.locator('[data-test="step-tree-record-title"]').fill(f"登录回放_{uniq}")
        page.locator('[data-test="step-tree-record-generate"]').click()
        gate_alert = page.locator('[data-test="step-tree-record-gate-off-alert"]')
        expect(gate_alert).to_be_visible(timeout=15000)
        expect(gate_alert).to_contain_text("未开启")
        expect(page.locator('[data-test="step-tree-record-gate-off-settings-btn"]')).to_be_visible(timeout=5000)

        _set_switch(api, "1")
        flipped = True
        page.locator('[data-test="step-tree-record-load"]').click()
        expect(events_row).to_be_visible(timeout=15000)
        page.locator('[data-test="step-tree-record-generate"]').click()
        step_rows = page.locator('[data-test="step-tree-record-step-row"]')
        expect(step_rows).to_have_count(2, timeout=30000)
        expect(page.locator('[data-test="step-tree-record-count"]')).to_contain_text("2 / 2")
        expect(page.locator('[data-test="step-tree-record-confirm"]')).to_contain_text("2")

        step_rows.nth(1).locator('.el-checkbox').click()
        expect(page.locator('[data-test="step-tree-record-count"]')).to_contain_text("1 / 2")
        page.locator('[data-test="step-tree-record-confirm"]').click()
        expect(page.locator('[data-test="step-tree-record-draft"]')).to_have_count(0, timeout=30000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="detail-script-tree-edit"]').click()
        expect(dialog).to_be_visible(timeout=10000)
        tree_rows = page.locator('[data-test^="step-tree-row-"]')
        expect(tree_rows).to_have_count(2, timeout=10000)
        page.keyboard.press("Escape")
        page.keyboard.press("Escape")
        aj = sql(f"SELECT action_json FROM tp_script WHERE case_id={cid}")
        assert '"app.tap"' in aj, f"入库步未落库: {aj[:200]}"
        assert '"app.input"' not in aj, f"反选步误落库: {aj[:200]}"
        pending = int(sql(f"SELECT count(*) FROM tp_appauto_record_draft "
                          f"WHERE project_id={pid} AND status=1"))
        assert pending == 0, f"草稿未翻状态（待审残留 {pending}）"

        _strip_self403(collectors)
        assert_no_errors(collectors, "T309 录制转流程两层门向导")
    finally:
        if flipped:
            try:
                _set_switch(api, "1")  # restore factory default (K8 26-key family = ON)
            except Exception:  # noqa: BLE001,S110
                pass
        if stub:
            stub.terminate()
        try:
            for t in task_ids:
                sql(f"DELETE FROM tp_exec_task WHERE id={t}")
            sql(f"DELETE FROM tp_appauto_record_draft WHERE project_id={pid}")
            if llm_cfg_id:
                sql(f"DELETE FROM tp_llm_config WHERE id={llm_cfg_id}")
            if pid:
                api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
        except Exception:  # noqa: BLE001,S110
            pass
