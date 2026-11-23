
import json
import re

import httpx
import pytest
from playwright.sync_api import expect

from conftest import API_BASE, TESTER_PASSWORD, api_client  # noqa: F401
from conftest import add_member, api_login, assert_no_errors, logged_in, mk_tester  # noqa: F401
from conftest import sql_query, uniq  # noqa: F401

pytestmark = pytest.mark.timeout(180)

CORE = "http://localhost:8083"

STEP_A = "打开登录页"
STEP_B = "输入账号密码"
STEP_C = "点击登录按钮"

EDITED_URL = "http://localhost:8899/b12-edited"


def _mk_project(api, uid):
    r = api.post("/projects", json={"name": f"B12项目_{uid}", "code": f"B12{uid[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case3(api, pid, uid):
    pass
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"树编辑场景_{uid}",
        "precondition": f"前置：浏览器已打开（{uid}）",
        "expectedResult": f"预期：脚本可手编（{uid}）",
        "priority": 1,
        "confidence": 1,
        "endType": "web",
        "status": 1,
        "steps": [
            {"stepNo": 1, "description": STEP_A, "isAssert": 0},
            {"stepNo": 2, "description": STEP_B, "isAssert": 0},
            {"stepNo": 3, "description": STEP_C, "isAssert": 1},
        ],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _script_doc(case_id, uid):
    return json.dumps({
        "version": "v2", "caseId": case_id, "title": f"树编辑场景_{uid}", "endType": "web",
        "steps": [
            {"stepId": 1, "action": "goto", "url": "http://localhost:8899/login"},
            {"stepId": 2, "action": "input", "target": {"text": "用户名", "chain": ["text"]},
             "value": "tester"},
            {"stepId": 3, "action": "expect", "target": {"css": ".welcome", "chain": ["css"]}},
        ],
        "env": {"ENV": "dev", "PACK": "retail-pack-v1", "DATA": "pool-a"},
    }, ensure_ascii=False)


def _save_script(case_id, uid):
    pass
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": _script_doc(case_id, uid), "generatedBy": 1})
    assert r.status_code == 200, f"save 脚本失败: {r.status_code} {r.text[:200]}"
    assert r.json().get("code") == "0", f"save 脚本业务失败: {r.text[:200]}"


def _manual_save_api(api, case_id, url_value):
    pass
    doc = json.loads(_script_doc(case_id, "m"))
    doc["steps"][0]["url"] = url_value
    r = api.put(f"/cases/{case_id}/script?expectedVersion=1",
                json={"schemaVersion": "v2", "actionJson": json.dumps(doc, ensure_ascii=False)})
    assert r.status_code == 200 and r.json().get("code") == "0", \
        f"手编保存失败: {r.status_code} {r.text[:300]}"


def _open_detail(page, case_id):
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")


def _open_tree_dialog(page):
    page.locator('[data-test="detail-script-tree-edit"]').click()
    dialog = page.locator('[data-test="step-tree-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)
    return dialog


def _switch_tab(page, dialog, label):
    dialog.locator(f'.el-tabs__item:has-text("{label}")').first.click()


def _task_count(pid):
    return int(sql_query(f"SELECT count(*) FROM tp_ai_task WHERE project_id={pid}"))


def test_b12_tree_edit_roundtrip_save_persist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case3(api_client, pid, uniq)
        _save_script(case_id, uniq)

        _open_detail(page, case_id)
        dialog = _open_tree_dialog(page)
        expect(dialog.locator('[data-test="step-tree-usage"]')).to_be_visible()
        rows = dialog.locator('[data-test^="step-tree-row-"]')
        expect(rows.first).to_be_visible()
        rows.first.click()
        url_input = dialog.locator('[data-test="step-tree-field-url"] input.el-input__inner')
        expect(url_input).to_be_visible()
        url_input.fill(EDITED_URL)
        expect(dialog.locator('[data-test="step-tree-dirty"]')).to_be_visible()

        dialog.locator('[data-test="step-tree-save"]').click()
        diff = page.locator('[data-test="step-tree-diff-dialog"]')
        expect(diff).to_be_visible()
        expect(page.locator('[data-test="step-tree-diff-table"]')).to_be_visible()
        expect(page.locator('[data-test="step-tree-diff-after"]').first).to_contain_text(EDITED_URL)
        page.locator('[data-test="step-tree-diff-confirm"]').click()
        expect(page.locator(".el-message").filter(has_text="脚本已保存")).to_be_visible(timeout=8000)

        row = sql_query(f"SELECT generated_by, version, action_json FROM tp_script WHERE case_id={case_id}")
        gb, ver, aj = row.split("|", 2)
        assert gb == "4", f"generated_by 应恒置 4，实际 {gb}"
        assert ver == "2", f"version 应 1→2，实际 {ver}"
        assert EDITED_URL in aj, f"action_json 未含新 url: {aj[:200]}"
        assert sql_query(
            "SELECT reason FROM tp_script_version WHERE case_id="
            f"{case_id} ORDER BY version DESC LIMIT 1") == "manual_edit"

        _open_detail(page, case_id)
        dialog = _open_tree_dialog(page)
        _switch_tab(page, dialog, "源码")
        expect(dialog.locator('[data-test="step-tree-source-text"]')).to_have_value(re.compile(re.escape(EDITED_URL)))

        assert_no_errors(collectors, "B12 树编辑全往返保存持久化")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")


def test_b12_source_mode_invalid_switch_rejected(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case3(api_client, pid, uniq)
        _save_script(case_id, uniq)

        _open_detail(page, case_id)
        dialog = _open_tree_dialog(page)
        _switch_tab(page, dialog, "源码")
        textarea = dialog.locator('[data-test="step-tree-source-text"]')
        expect(textarea).to_have_value(re.compile("goto"))
        bad = '{"version":"v2","steps":[{"action":"no_such_action_xyz"}]}'
        textarea.fill(bad)
        _switch_tab(page, dialog, "树编辑")

        expect(dialog.locator('[data-test="step-tree-source-errors"]')).to_be_visible()
        expect(dialog.locator('[data-test="step-tree-source-errors"]')).to_contain_text(
            "no_such_action_xyz")
        expect(page.locator('[data-test="step-tree-tabs"] .el-tabs__item.is-active')).to_contain_text("源码")
        expect(textarea).to_have_value(bad)
        textarea.fill(_script_doc(case_id, uniq))
        _switch_tab(page, dialog, "树编辑")
        expect(page.locator('[data-test="step-tree-tabs"] .el-tabs__item.is-active')).to_contain_text("树编辑")

        assert_no_errors(collectors, "B12 双模切换闸")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")


def test_b12_regen_overwrite_guard_dialog(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case3(api_client, pid, uniq)
        _save_script(case_id, uniq)
        _manual_save_api(api_client, case_id, EDITED_URL)

        _open_detail(page, case_id)
        base_tasks = _task_count(pid)
        page.locator('[data-test="detail-script-regen"]').click()

        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=8000)
        expect(box.locator(".el-message-box__title")).to_contain_text("覆盖手工编辑脚本")
        expect(box.locator(".el-message-box__message")).to_contain_text("手工编辑")
        box.locator('button:has-text("取消")').click()
        expect(box).to_be_hidden(timeout=5000)
        assert _task_count(pid) == base_tasks, "取消覆盖仍建了任务（链路②a 取消静默面回归）"

        page.locator('[data-test="detail-script-regen"]').click()
        expect(box).to_be_visible(timeout=8000)
        box.locator('button:has-text("确认覆盖并重生成")').click()
        toast = page.locator(".el-message").filter(has_text="脚本生成任务已提交（任务 #")
        expect(toast).to_be_visible(timeout=8000)
        m = re.search(r"任务 #(\d+)", toast.inner_text())
        assert m, f"toast 未含真实任务号: {toast.inner_text()}"
        assert _task_count(pid) == base_tasks + 1, "确认覆盖后任务行数未+1"

        assert_no_errors(collectors, "B12 覆盖保护弹窗")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")


def test_b12_keyboard_reorder_persist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case3(api_client, pid, uniq)

        _open_detail(page, case_id)
        item1 = page.locator('[data-test="flow-reorder-item-1"]')
        expect(item1).to_be_visible()
        item1.focus()
        page.keyboard.press(" ")
        page.keyboard.press("ArrowDown")
        page.keyboard.press("ArrowDown")
        expect(page.locator('[data-test="flow-reorder-item-1"]')).to_contain_text(STEP_B)
        expect(page.locator('[data-test="flow-reorder-item-3"]')).to_contain_text(STEP_A)

        save_btn = page.locator('[data-test="flow-reorder-save"]')
        expect(save_btn).to_be_enabled()
        save_btn.click()
        diff = page.locator('[data-test="flow-diff-dialog"]')
        expect(diff).to_be_visible()
        page.locator('[data-test="flow-diff-confirm"]').click()
        expect(page.locator(".el-message").filter(has_text="步骤顺序已更新")).to_be_visible(timeout=8000)

        agg = sql_query("SELECT string_agg(description, '|' ORDER BY step_no) "
                        f"FROM tp_case_step WHERE case_id = {case_id}")
        assert agg == f"{STEP_B}|{STEP_C}|{STEP_A}", f"键盘路 DB 步骤序未重排: {agg}"
        assert sql_query(f"SELECT version FROM tp_test_case WHERE id = {case_id}") == "2"

        _open_detail(page, case_id)
        expect(page.locator('[data-test="flow-reorder-item-1"]')).to_contain_text(STEP_B)

        assert_no_errors(collectors, "B12 键盘拖序持久化")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")


def test_b12_script_put_permission_negative(api_client, uniq):
    pass
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case3(api_client, pid, uniq)
        _save_script(case_id, uniq)
        user_id, _nick = mk_tester(api_client, uniq, prefix="b12")
        add_member(api_client, pid, user_id)
        token = api_login(f"b12tester_{uniq}", TESTER_PASSWORD)
        r = httpx.put(f"{API_BASE}/cases/{case_id}/script?expectedVersion=1",
                      json={"schemaVersion": "v2", "actionJson": _script_doc(case_id, "n")},
                      headers={"Authorization": f"Bearer {token}"}, timeout=15)
        assert r.status_code == 403, f"成员手编应 403，实际 {r.status_code}: {r.text[:200]}"
        body = r.json()
        assert str(body.get("code")) == "40301", f"业务码应 40301: {body}"
        assert ("负责人" in str(body.get("msg")) or "权限" in str(body.get("msg"))), \
            f"403 人话缺失: {body}"
        assert sql_query(f"SELECT generated_by, version FROM tp_script WHERE case_id={case_id}") == "1|1"
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")


ADMIN_PASSWORD_PLACEHOLDER = "Test@12345"
