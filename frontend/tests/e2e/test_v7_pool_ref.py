
import json
import re
import threading
import uuid

import httpx
import pytest
from conftest import (  # noqa: F401
    BASE_URL,
    api_client,
    assert_no_errors,
    logged_in,
    nav_to,
    row_cmd,
    sql_query,
    uniq,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)

CORE = "http://localhost:8083"


def _start_order_server(port_holder):
    pass
    import http.server

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            body = json.dumps({"code": "0", "data": [
                {"orderNo": f"ORD-V7-{port_holder['tag']}"},
                {"orderNo": f"ORD-V7-{port_holder['tag']}-2"}],
                "extra": 1}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def _mk_project_env(api, uid):
    r = api.post("/projects", json={"name": f"V7引用项目_{uid}", "code": f"V7E{uid[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"V7环境{uid[:6]}", "envType": 2, "baseUrl": f"https://v7-{uid[:6]}.tp-local.test"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    return pid, r.json()["data"]


def _mk_case(api, pid, uid):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"造数引用场景_{uid}",
        "precondition": f"前置：数据池已备（{uid}）",
        "expectedResult": "预期：参数引用测试数据可插入",
        "priority": 1, "confidence": 1, "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开页面", "isAssert": 0},
                  {"stepNo": 2, "description": "输入查询词", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _save_script(case_id):
    pass
    doc = {
        "version": "v2", "caseId": case_id, "title": "造数引用场景", "endType": "web",
        "steps": [
            {"stepId": 1, "action": "goto", "url": "http://localhost:8899/search"},
            {"stepId": 2, "action": "input", "target": {"text": "搜索框", "chain": ["text"]},
             "value": "init"},
        ],
        "env": {"ENV": "dev"},
    }
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": json.dumps(doc, ensure_ascii=False), "generatedBy": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"落脚本失败: {r.text[:200]}"


def test_v7_pool_ref_insert_roundtrip(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    holder = {"tag": uuid.uuid4().hex[:6]}
    server = _start_order_server(holder)
    pid = None
    try:
        pid, env_id = _mk_project_env(api_client, uniq)
        key = f"sku_{holder['tag']}"
        content = json.dumps({
            "poolType": "订单", "method": "GET",
            "url": f"http://127.0.0.1:{server.server_port}/orders",
            "headers": {}, "body": {}, "extractExpr": "$.data[*].orderNo"}, ensure_ascii=False)
        r = api_client.post("/data-factories", json={
            "projectId": pid, "envId": env_id, "type": 1,
            "name": f"V7造数{uniq[:6]}",
            "content": json.dumps({**json.loads(content), "poolKey": key}, ensure_ascii=False)})
        assert r.json().get("code") == "0", f"建造数失败: {r.text[:300]}"
        fid = r.json()["data"]["id"]
        row = sql_query(f"SELECT content FROM tp_data_factory WHERE id={fid}")
        assert json.loads(row).get("poolKey") == key, f"poolKey 未落 content: {row[:200]}"

        r = api_client.post(f"/data-factories/{fid}/run")
        assert r.json().get("code") == "0", f"执行造数失败: {r.text[:300]}"
        rows = sql_query(
            "SELECT name FROM tp_data_pool_item WHERE project_id="
            f"{pid} AND env_id={env_id} ORDER BY id LIMIT 2")
        names = rows.splitlines()
        assert names[0] == key, f"首条池项 name 应为键名，实际 {names}"

        r = api_client.post("/data-factories", json={
            "projectId": pid, "envId": env_id, "type": 1,
            "name": f"V7无键{uniq[:6]}", "content": content})
        assert r.json().get("code") == "0", f"无键造数失败: {r.text[:300]}"
        fid2 = r.json()["data"]["id"]
        r = api_client.post(f"/data-factories/{fid2}/run")
        assert r.json().get("code") == "0", f"无键执行失败: {r.text[:300]}"
        null_names = sql_query(
            "SELECT count(*) FROM tp_data_pool_item WHERE project_id="
            f"{pid} AND env_id={env_id} AND name IS NULL")
        assert int(null_names) >= 1, "无键厂产出入池 name 应为 NULL（对照面）"

        case_id = _mk_case(api_client, pid, uniq)
        _save_script(case_id)
        page.goto(page.url.split("#")[0] + f"#/cases/{case_id}", wait_until="domcontentloaded")
        page.locator('[data-test="detail-script-tree-edit"]').click()
        dialog = page.locator('[data-test="step-tree-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        dialog.locator('[data-test^="step-tree-row-"]').nth(1).click()
        cascader = dialog.locator('[data-test="step-tree-pool-ref"]')
        expect(cascader).to_be_visible(timeout=8000)
        cascader.click()
        panel = page.locator('.el-cascader-panel')
        expect(panel).to_be_visible()
        env_name = f"V7环境{uniq[:6]}"
        panel.locator(f'.el-cascader-menu:visible .el-cascader-node:has-text("{env_name}")').first.click()
        leaf = panel.locator(f'.el-cascader-menu:visible .el-cascader-node:has-text("{key}")')
        expect(leaf.first).to_be_visible()
        expect(leaf.first).to_contain_text("样本")
        leaf.first.click()
        value_input = dialog.locator('[data-test="step-tree-field-value"] input.el-input__inner:not([readonly])')
        expect(value_input).to_have_value(
            "init" + "{" * 2 + f"DATA:{key}" + "}" * 2, timeout=5000)

        dialog.locator('[data-test="step-tree-save"]').click()
        diff = page.locator('[data-test="step-tree-diff-dialog"]')
        expect(diff).to_be_visible()
        page.locator('[data-test="step-tree-diff-confirm"]').click()
        expect(page.locator(".el-message").filter(has_text="脚本已保存")).to_be_visible(timeout=8000)
        aj = sql_query(f"SELECT action_json FROM tp_script WHERE case_id={case_id}")
        doc = json.loads(aj)
        tok = "{" * 2 + f"DATA:{key}" + "}" * 2
        assert doc["steps"][1]["value"] == f"init{tok}", f"落库 value 非 token 形态: {doc['steps'][1]}"

        page.goto(page.url.split("#")[0] + f"#/cases/{case_id}", wait_until="domcontentloaded")
        page.locator('[data-test="detail-script-tree-edit"]').click()
        expect(dialog).to_be_visible(timeout=10000)
        dialog.locator('.el-tabs__item:has-text("源码")').first.click()
        expect(dialog.locator('[data-test="step-tree-source-text"]')).to_have_value(re.compile(re.escape(tok)))

        assert_no_errors(collectors, "V7 造数引用插入全往返")
    finally:
        server.shutdown()
        server.server_close()
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")


def test_v7_factory_form_poolkey_browser(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    try:
        pid, _env_id = _mk_project_env(api_client, uniq)
        key = f"order_no_{uniq[:6]}"
        # Login predates project creation → authStore.projects is stale
        # (empty-no-project state). Reload refreshes the visible-domain list,
        # and the deep link lands the header context on this test's project
        # (T396b posture: in-page selector retired).
        page.goto(page.url.split("#")[0] + f"#/data-factories?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="datafactory-list-page"]')).to_be_visible(timeout=15000)
        # First visit under a brand-new project has no factories = empty state
        # (DataTablePage renders no table); the page root being present is enough.
        expect(page.locator('[data-test="datafactory-create-btn"]')).to_be_visible(timeout=8000)
        page.locator('[data-test="datafactory-create-btn"]').click()
        dlg = page.locator('[data-test="datafactory-dialog"]')
        expect(dlg).to_be_visible()
        dlg.locator('[data-test="datafactory-form-name"]').fill(f"V7表单造数{uniq[:6]}")
        dlg.locator('[data-test="datafactory-form-env"]').click()
        page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("V7环境{uniq[:6]}"):visible').first.click()
        dlg.locator('[data-test="datafactory-form-content"]').fill(
            json.dumps({"poolType": "订单", "method": "GET", "url": "http://x.tp-local.test/o",
                        "headers": {}, "body": {}, "extractExpr": "$.data.orderNo"}, ensure_ascii=False))
        dlg.locator('[data-test="datafactory-form-poolkey"]').fill(key)
        dlg.locator('[data-test="datafactory-form-submit"]').click()
        expect(page.locator(".el-message").filter(has_text="创建成功")).to_be_visible(timeout=8000)

        row = sql_query("SELECT content FROM tp_data_factory WHERE project_id="
                        f"{pid} AND name='V7表单造数{uniq[:6]}'")
        assert json.loads(row).get("poolKey") == key, f"content 未注入键名: {row[:200]}"

        # Edit refill: re-land on the same project via the header-context deep
        # link (row exists after create = table renders) → key reverse-fill check
        page.goto(f"{BASE_URL}/#/data-factories?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="datafactory-table"]')).to_be_visible(timeout=15000)
        row_cmd(page, page.locator('[data-test="datafactory-table"] tbody tr').first, "edit")
        dlg = page.locator('[data-test="datafactory-dialog"]')
        expect(dlg).to_be_visible()
        expect(dlg.locator('[data-test="datafactory-form-poolkey"]')).to_have_value(key)

        assert_no_errors(collectors, "V7 工厂表单键名链路")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")
