
import json
import re
import uuid

import httpx
import pytest
from conftest import (  # noqa: F401
    api_client,
    assert_no_errors,
    logged_in,
    row_cmd,
    sql_query,
    uniq,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)

CORE = "http://localhost:8083"


def _select_option(page, trigger, option_text, popper_class):
    pass
    trigger.click()
    popper = page.locator(f".{popper_class}:visible")
    expect(popper.first).to_be_visible(timeout=8000)
    popper.locator(f'.el-select-dropdown__item:has-text("{option_text}")').first.click()


def _mk_case(api, pid, uid):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"genkit浮层场景_{uid}",
        "precondition": f"前置：目录端点在位（{uid}）",
        "expectedResult": "预期：造数函数骨架可插入并落库",
        "priority": 1, "confidence": 1, "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开页面", "isAssert": 0},
                  {"stepNo": 2, "description": "输入查询词", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _save_script(case_id):
    doc = {
        "version": "v2", "caseId": case_id, "title": "genkit浮层场景", "endType": "web",
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


def test_tool_genkit_form_roundtrip(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    name = f"genkitE2E_{uniq}"
    tid = None
    try:
        page.goto(page.url.split("#")[0] + "#/tools", wait_until="domcontentloaded")
        expect(page.locator('[data-test="tool-list-page"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="tool-create-btn"]').click()
        dialog = page.locator('[data-test="tool-form-dialog"]')
        expect(dialog).to_be_visible()
        dialog.locator('[data-test="tool-form-name"]').fill(name)
        _select_option(page, dialog.locator('[data-test="tool-form-type"]'), "可执行",
                       "tp-tool-form-type-popper")
        _select_option(page, dialog.locator('[data-test="tool-form-exec-kind"]'), "GENKIT",
                       "tp-tool-form-execkind-popper")
        fn_sel = dialog.locator('[data-test="tool-form-genkit-fn"]')
        expect(fn_sel).to_be_visible(timeout=8000)
        fn_sel.click()
        fn_popper = page.locator(".tp-tool-form-genkit-fn-popper:visible")
        expect(fn_popper.first).to_be_visible(timeout=10000)
        expect(fn_popper.locator(".el-select-group__title").first).to_be_visible()
        fn_popper.locator('.el-select-dropdown__item:has-text("md5")').first.click()
        hint = page.locator('[data-test="tool-form-genkit-hint"]')
        expect(hint).to_contain_text("原文", timeout=5000)
        spec = dialog.locator('[data-test="tool-form-exec-spec"]')
        expect(spec).to_have_value(re.compile(r'\{"function":"md5"\}'), timeout=5000)
        dialog.locator('[data-test="tool-form-submit"]').click()
        expect(page.locator(".el-message").filter(has_text="草稿")).to_be_visible(timeout=8000)
        page.goto(page.url.split("#")[0] + "#/tools", wait_until="domcontentloaded")
        page.locator('[data-test="tool-keyword"]').fill(name)
        page.locator('[data-test="tool-search"]').click()
        row = page.locator('[data-test="tool-table"] tbody tr').first
        expect(row).to_contain_text(name, timeout=8000)
        row_cmd(page, row, "edit")
        expect(dialog).to_be_visible()
        expect(dialog.locator('[data-test="tool-form-genkit-fn"]')).to_contain_text("md5", timeout=8000)
        dialog.locator(".el-dialog__headerbtn").first.click()
        r = api_client.get(f"/tools?page=1&size=50&keyword={name}")
        rows = (r.json().get("data") or {}).get("list") or []
        tid = next((x["id"] for x in rows if x["name"] == name), None)
        assert tid, f"未查到刚建草稿 {name}: {r.text[:200]}"
    finally:
        if tid:
            cur = api_client.get(f"/tools/{tid}").json()["data"]
            dr = api_client.delete(f"/tools/{tid}?expectedVersion={cur['version']}")
            assert dr.json().get("code") == "0", f"删草稿失败: {dr.text[:200]}"
        assert_no_errors(collectors, "工具页 GENKIT 表单往返")


def test_step_tree_gen_ref_insert(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    try:
        r = api_client.post("/projects", json={"name": f"G3浮层项目_{uniq}", "code": f"G3{uniq[:6].upper()}"})
        assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
        pid = r.json()["data"]
        case_id = _mk_case(api_client, pid, uniq)
        _save_script(case_id)

        page.goto(page.url.split("#")[0] + f"#/cases/{case_id}", wait_until="domcontentloaded")
        page.locator('[data-test="detail-script-tree-edit"]').click()
        dialog = page.locator('[data-test="step-tree-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        dialog.locator('[data-test^="step-tree-row-"]').nth(1).click()
        gen_sel = dialog.locator('[data-test="step-tree-gen-ref"]')
        expect(gen_sel).to_be_visible(timeout=8000)
        gen_sel.click()
        gen_popper = page.locator(".el-select-dropdown:visible").last
        expect(gen_popper).to_be_visible(timeout=10000)
        opt = gen_popper.locator('[data-test="step-tree-gen-opt-md5"]').first
        expect(opt).to_be_visible(timeout=10000)
        expect(gen_popper.locator(".el-select-group__title").first).to_be_visible()
        opt.click()
        value_input = dialog.locator(
            '[data-test="step-tree-field-value"] input.el-input__inner')
        expect(value_input).to_have_value("init" + "${gen.md5(text=)}", timeout=5000)

        dialog.locator('[data-test="step-tree-save"]').click()
        diff = page.locator('[data-test="step-tree-diff-dialog"]')
        expect(diff).to_be_visible()
        page.locator('[data-test="step-tree-diff-confirm"]').click()
        expect(page.locator(".el-message").filter(has_text="脚本已保存")).to_be_visible(timeout=8000)
        aj = sql_query(f"SELECT action_json FROM tp_script WHERE case_id={case_id}")
        doc = json.loads(aj)
        assert doc["steps"][1]["value"] == "init${gen.md5(text=)}", \
            f"落库 value 非骨架形态: {doc['steps'][1]}"

        page.goto(page.url.split("#")[0] + f"#/cases/{case_id}", wait_until="domcontentloaded")
        page.locator('[data-test="detail-script-tree-edit"]').click()
        expect(dialog).to_be_visible(timeout=10000)
        dialog.locator('.el-tabs__item:has-text("源码")').first.click()
        expect(dialog.locator('[data-test="step-tree-source-text"]')).to_have_value(
            re.compile(re.escape("${gen.md5(text=)}")))
        assert_no_errors(collectors, "步骤编辑器 gen 浮层插入往返")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")
