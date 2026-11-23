
import json
import os
import pathlib
import subprocess
import time
import uuid

import httpx
import pytest
from conftest import (ADMIN_PASSWORD, BASE_URL, API_ORIGIN, core_base_url,
                      sql_query, slot_port_offset, api_login, assert_no_errors,
                      launch_provider_stub)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

# All service doors come from the conftest single source (chain-slot aware);
# literal main-slot ports/DB names here would drive another chain's stack.
API = API_ORIGIN
CORE = core_base_url()
SWITCH_KEY = "ai.appauto.nl-step"
STUB_BASE = 18096
STUB_PORT = STUB_BASE + slot_port_offset()
HITS = "/tmp/testpike-t314-nlstep-e2e-hits.jsonl"


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
    assert hit, "自然语言生成开关键缺失（V163 种子未落库？）"
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
        if not ("/api/v1/appauto/nl-step" in e["url"] and e["status"] == 403)]
    collectors["console_errors"] = [
        c for c in collectors["console_errors"] if "status of 403" not in c]


def test_nl_step_wizard_generate_merge_persist(page, collectors):
    pass
    page, token = _inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    uniq = uuid.uuid4().hex[:6]
    pid = None
    stub = None
    flipped = False
    llm_cfg_id = None
    try:
        pid = api.post("/api/v1/projects",
                       json={"name": f"NL向导E2E项目{uniq}", "code": f"NLW{uniq[:6].upper()}"}
                       ).json()["data"]
        cbody = api.post("/api/v1/cases", json={
            "projectId": pid,
            "title": f"购物车结算入口_{uniq}",
            "precondition": f"前置：App 已安装（{uniq}）",
            "expectedResult": f"预期：结算页可达（{uniq}）",
            "priority": 0, "confidence": 1, "endType": "mobile", "status": 1,
            "steps": [{"stepNo": 1, "description": f"启动应用（{uniq}）", "isAssert": 0}],
        }).json()["data"]
        cid = cbody["id"] if isinstance(cbody, dict) else cbody
        ver = int(sql_query(f"SELECT version FROM tp_test_case WHERE id={cid}"))
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
                         json={"name": f"NL向导E2E桩端点-{uniq}", "provider": "openai",
                               "modelName": "nlstep-stub", "apiKey": "e2e-stub-not-secret",
                               "baseUrl": f"http://127.0.0.1:{STUB_PORT}/v1",
                               "status": 1, "isDefault": 0, "modelType": 1,
                               "description": "自然语言步骤E2E供应商位桩"}).json()
        assert cfg.get("code") == "0", f"清单行创建失败: {cfg}"
        llm_cfg_id = int(cfg["data"]["id"])
        sql_query(f"UPDATE tp_project SET llm_config_id={llm_cfg_id} WHERE id={pid}")
        try:
            stub = launch_provider_stub("mock-llm-nlstep.py", STUB_PORT,
                                        {"MOCK_LLM_HITS": HITS})
        except AssertionError as stub_err:
            pytest.skip(f"供应商位桩未起（环境件）：{stub_err}，向导开态段跳过")

        page.goto(f"{BASE_URL}/#/cases/{cid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="detail-script-tree-edit"]').click()
        dialog = page.locator('[data-test="step-tree-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        nl_btn = page.locator('[data-test="step-tree-nl-open"]')
        expect(nl_btn).to_be_visible(timeout=10000)
        expect(nl_btn).to_be_enabled(timeout=5000)

        _set_switch(api, "0")  # pin the closed state explicitly; the factory seed flipped to ON with the AI default-on batch
        nl_btn.click()
        nl_dialog = page.locator('[data-test="step-tree-nl-dialog"]')
        expect(nl_dialog).to_be_visible(timeout=10000)
        page.locator('[data-test="step-tree-nl-desc"]').fill("点击结算按钮")
        page.locator('[data-test="step-tree-nl-generate"]').click()
        gate_alert = page.locator('[data-test="step-tree-nl-gate-off-alert"]')
        expect(gate_alert).to_be_visible(timeout=15000)
        expect(gate_alert).to_contain_text("未开启")
        expect(page.locator('[data-test="step-tree-nl-gate-off-settings-btn"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="step-tree-nl-cancel"]').click()
        expect(nl_dialog).not_to_be_visible(timeout=5000)

        _set_switch(api, "1")
        flipped = True
        nl_btn.click()
        expect(nl_dialog).to_be_visible(timeout=10000)
        page.locator('[data-test="step-tree-nl-desc"]').fill("点击结算按钮，输入数量 2")
        page.locator('[data-test="step-tree-nl-generate"]').click()
        rows = page.locator('[data-test="step-tree-nl-step-row"]')
        expect(rows).to_have_count(2, timeout=30000)
        expect(page.locator('[data-test="step-tree-nl-count"]')).to_contain_text("2 / 2")
        expect(page.locator('[data-test="step-tree-nl-merge"]')).to_contain_text("并入 2 步")

        rows.nth(1).locator('.el-checkbox').click()
        expect(page.locator('[data-test="step-tree-nl-count"]')).to_contain_text("1 / 2")
        expect(page.locator('[data-test="step-tree-nl-merge"]')).to_contain_text("并入 1 步")
        page.locator('[data-test="step-tree-nl-merge"]').click()
        expect(dialog).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="step-tree-dirty"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="step-tree-save"]').click()
        diff_dialog = page.locator('[data-test="step-tree-diff-dialog"]')
        expect(diff_dialog).to_be_visible(timeout=10000)
        page.locator('[data-test="step-tree-diff-confirm"]').click()
        expect(diff_dialog).not_to_be_visible(timeout=30000)
        expect(dialog).not_to_be_visible(timeout=10000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="detail-script-tree-edit"]').click()
        expect(dialog).to_be_visible(timeout=10000)
        step_rows = page.locator('[data-test^="step-tree-row-"]')
        expect(step_rows).to_have_count(2, timeout=10000)
        page.keyboard.press("Escape")
        page.keyboard.press("Escape")
        aj = sql_query(f"SELECT action_json FROM tp_script WHERE case_id={cid}")
        assert '"app.tap"' in aj, f"并入步未落库: {aj[:200]}"
        assert '"app.input"' not in aj, f"反选步误落库: {aj[:200]}"

        _strip_self403(collectors)
        assert_no_errors(collectors, "T314 一句话生成 APP 步骤向导")
    finally:
        if flipped:
            _set_switch(api, "1")  # restore factory default (K8 26-key family = ON)
        if stub:
            stub.terminate()
        try:
            if llm_cfg_id:
                sql_query(f"DELETE FROM tp_llm_config WHERE id={llm_cfg_id}")
            if pid:
                api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
        except Exception:  # noqa: BLE001,S110
            pass
