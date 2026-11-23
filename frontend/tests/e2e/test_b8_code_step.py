
import json
import re

import httpx
import pytest
from playwright.sync_api import expect

from conftest import api_client
from conftest import assert_no_errors, logged_in, sql_query, uniq  # noqa: F401

pytestmark = pytest.mark.timeout(180)

CORE = "http://localhost:8083"

CLEAN_CODE = "print('{\"total\": 42}')"
FORBIDDEN_CODE = "import socket"


def _mk_project(api, uid):
    r = api.post("/projects", json={"name": f"B8代码步项目_{uid}", "code": f"B8{uid[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uid):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"代码步场景_{uid}",
        "precondition": f"前置：浏览器已打开（{uid}）",
        "expectedResult": f"预期：代码步可保存（{uid}）",
        "priority": 1,
        "confidence": 1,
        "endType": "web",
        "status": 1,
        "steps": [
            {"stepNo": 1, "description": "打开登录页", "isAssert": 0},
            {"stepNo": 2, "description": "输出结果", "isAssert": 1},
        ],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _script_doc(case_id, uid):
    return json.dumps({
        "version": "v2", "caseId": case_id, "title": f"代码步场景_{uid}", "endType": "web",
        "steps": [
            {"stepId": 1, "action": "goto", "url": "http://localhost:8899/login"},
            {"stepId": 2, "action": "expect", "target": {"css": ".welcome", "chain": ["css"]}},
        ],
        "env": {"ENV": "dev", "PACK": "retail-pack-v1", "DATA": "pool-a"},
    }, ensure_ascii=False)


def _save_script(case_id, uid):
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": _script_doc(case_id, uid), "generatedBy": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"save 脚本失败: {r.text[:200]}"


def _open_tree_dialog(page, case_id):
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")
    page.locator('[data-test="detail-script-tree-edit"]').click()
    dialog = page.locator('[data-test="step-tree-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)
    return dialog


def _insert_code_step(dialog):
    dialog.locator('[data-test="step-tree-insert-code"]').click()
    rows = dialog.locator('[data-test^="step-tree-row-"]')
    expect(rows).to_have_count(3, timeout=5000)
    rows.last.click()
    return dialog


def _db_script_row(case_id):
    return sql_query(f"SELECT version, generated_by, action_json FROM tp_script WHERE case_id={case_id}")


def test_b8_insert_code_step_save_persist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq)
        _save_script(case_id, uniq)

        dialog = _open_tree_dialog(page, case_id)
        _insert_code_step(dialog)

        lang_select = dialog.locator('[data-test="step-tree-field-language"] .el-select')
        expect(lang_select).to_be_visible()
        lang_select.click()
        dropdown = page.locator(".el-select-dropdown:visible")
        expect(dropdown).to_be_visible()
        texts = dropdown.locator(".el-select-dropdown__item").all_text_contents()
        assert texts == ["python"], f"language 候选应仅 python（js 禁给），实际 {texts}"
        page.keyboard.press("Escape")

        code_area = dialog.locator('[data-test="step-tree-field-code"] textarea')
        expect(code_area).to_be_visible()
        code_area.fill(CLEAN_CODE)
        timeout_input = dialog.locator('[data-test="step-tree-field-timeoutMs"] input')
        timeout_input.fill("5000")
        timeout_input.press("Enter")
        saveas_input = dialog.locator('[data-test="step-tree-field-saveAs"] input.el-input__inner')
        saveas_input.fill("result")
        expect(dialog.locator('[data-test="step-tree-dirty"]')).to_be_visible()

        dialog.locator('[data-test="step-tree-save"]').click()
        diff = page.locator('[data-test="step-tree-diff-dialog"]')
        expect(diff).to_be_visible()
        page.locator('[data-test="step-tree-diff-confirm"]').click()
        expect(page.locator(".el-message").filter(has_text="脚本已保存")).to_be_visible(timeout=8000)

        ver, gb, aj = _db_script_row(case_id).split("|", 2)
        assert gb == "4", f"generated_by 应恒置 4，实际 {gb}"
        assert ver == "2", f"version 应 1→2，实际 {ver}"
        doc = json.loads(aj)
        step = doc["steps"][2]
        assert step["action"] == "code.step"
        assert step["language"] == "python"
        assert step["code"] == CLEAN_CODE
        assert step["timeoutMs"] == 5000
        assert step["saveAs"] == "result"

        dialog = _open_tree_dialog(page, case_id)
        dialog.locator('.el-tabs__item:has-text("源码")').first.click()
        expect(dialog.locator('[data-test="step-tree-source-text"]')).to_have_value(
            re.compile(r"code\.step"), timeout=5000)
        dialog.locator('.el-tabs__item:has-text("树编辑")').first.click()
        expect(dialog.locator('[data-test^="step-tree-row-"]').last).to_contain_text("自定义代码")

        assert_no_errors(collectors, "B8 代码步插入保存持久化")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")


def test_b8_forbidden_code_rejected_by_save_chain(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq)
        _save_script(case_id, uniq)

        dialog = _open_tree_dialog(page, case_id)
        _insert_code_step(dialog)
        code_area = dialog.locator('[data-test="step-tree-field-code"] textarea')
        expect(code_area).to_be_visible()
        code_area.fill(FORBIDDEN_CODE)

        dialog.locator('[data-test="step-tree-save"]').click()
        diff = page.locator('[data-test="step-tree-diff-dialog"]')
        expect(diff).to_be_visible()
        page.locator('[data-test="step-tree-diff-confirm"]').click()

        err = dialog.locator('[data-test="step-tree-save-error"]')
        expect(err).to_be_visible(timeout=8000)
        expect(err).to_contain_text("网络访问")
        ver, gb, _ = _db_script_row(case_id).split("|", 2)
        assert ver == "1" and gb == "1", f"违规保存后 DB 应零变化，实际 version={ver} generated_by={gb}"

        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"] if "/script" not in e["url"] or e["status"] != 400]
        collectors["console_errors"] = [
            c for c in collectors["console_errors"] if "status of 400" not in c]

        assert_no_errors(collectors, "B8 违规代码保存链拒单")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")
