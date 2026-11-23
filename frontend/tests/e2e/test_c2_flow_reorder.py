
import json

import httpx
import pytest
from playwright.sync_api import expect

from conftest import api_client
from conftest import assert_no_errors, logged_in, sql_query, uniq  # noqa: F401

pytestmark = pytest.mark.timeout(180)

CORE = "http://localhost:8083"

STEP_A = "打开登录页"
STEP_B = "输入账号密码"
STEP_C = "点击登录按钮"


def _mk_project(api, uid):
    r = api.post("/projects", json={"name": f"C2项目_{uid}", "code": f"C2{uid[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case3(api, pid, uid):
    pass
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"改序场景_{uid}",
        "precondition": f"前置：浏览器已打开（{uid}）",
        "expectedResult": f"预期：流程可达（{uid}）",
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


def _save_script(case_id, uid):
    pass
    action = json.dumps({
        "version": "v2", "caseId": case_id, "title": f"改序场景_{uid}", "endType": "web",
        "steps": [
            {"stepId": 1, "action": "goto", "target": {"css": "#login"}},
            {"stepId": 2, "action": "input", "target": {"text": "用户名"}, "value": "tester"},
            {"stepId": 3, "action": "expect", "target": {"css": ".welcome"}},
        ],
        "env": {"ENV": "dev", "PACK": "retail-pack-v1", "DATA": "pool-a"},
    }, ensure_ascii=False)
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                    json={"caseId": case_id, "schemaVersion": "v2",
                          "actionJson": action, "generatedBy": 1})
    assert r.status_code == 200, f"save 脚本失败: {r.status_code} {r.text[:200]}"
    assert r.json().get("code") == "0", f"save 脚本业务失败: {r.text[:200]}"


def _open_detail(page, case_id):
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")


def _item(page, n):
    return page.locator(f'[data-test="flow-reorder-item-{n}"]')


def _drag_first_to_last(page, case_id):
    pass
    src, dst = _item(page, 1), _item(page, 3)
    src.drag_to(dst)
    if STEP_A in _item(page, 1).inner_text():
        page.evaluate("""([cid]) => {
            const items = document.querySelectorAll('.reorder-item');
            const dt = new DataTransfer();
            const fire = (el, type) => el.dispatchEvent(new DragEvent(type, {
                bubbles: true, cancelable: true, dataTransfer: dt }));
            void cid;
            fire(items[0], 'dragstart');
            fire(items[2], 'dragover');
            fire(items[2], 'drop');
            fire(items[0], 'dragend');
        }""", [case_id])
    expect(_item(page, 1)).to_contain_text(STEP_B, timeout=5000)
    expect(_item(page, 2)).to_contain_text(STEP_C)
    expect(_item(page, 3)).to_contain_text(STEP_A)


def _put_count(collectors, case_id):
    return sum(1 for r in collectors["requests"]
               if r["method"] == "PUT" and f"/api/v1/cases/{case_id}" in r["url"])


def test_c2_flow_reorder_drag_diff_save_persist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case3(api_client, pid, uniq)
        _save_script(case_id, uniq)

        _open_detail(page, case_id)
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(f"改序场景_{uniq}")
        panel = page.locator('[data-test="flow-reorder-panel"]')
        expect(panel).to_be_visible()
        expect(_item(page, 1)).to_contain_text(STEP_A)
        expect(_item(page, 3)).to_contain_text(STEP_C)
        expect(page.locator('[data-test="flowchart-step-count"]')).to_contain_text("3")
        assert page.locator('[data-test="detail-script-pending"]').count() == 0, "前件：脚本已生成却显示待更新"

        _drag_first_to_last(page, case_id)

        save_btn = page.locator('[data-test="flow-reorder-save"]')
        expect(save_btn).to_be_enabled()
        save_btn.click()
        dialog = page.locator('[data-test="flow-diff-dialog"]')
        expect(dialog).to_be_visible()
        expect(dialog.locator('[data-test="flow-diff-before"]').first).to_contain_text(STEP_A)
        expect(dialog.locator('[data-test="flow-diff-after"]').first).to_contain_text(STEP_B)

        page.locator('[data-test="flow-diff-confirm"]').click()
        expect(page.locator(".el-message").filter(has_text="步骤顺序已更新")).to_be_visible(timeout=8000)
        put_reqs = [r for r in collectors["requests"]
                    if r["method"] == "PUT" and f"/api/v1/cases/{case_id}" in r["url"]]
        assert put_reqs, "确认后零 PUT 请求（提交链断）"
        assert STEP_B in (put_reqs[-1]["body"] or ""), f"PUT body 非新序: {put_reqs[-1]['body']}"

        steps_table = page.locator('[data-test="detail-steps"]')
        rows = steps_table.locator("tbody tr.el-table__row td:nth-child(2)")
        expect(rows.nth(0)).to_contain_text(STEP_B)
        expect(rows.nth(1)).to_contain_text(STEP_C)
        expect(rows.nth(2)).to_contain_text(STEP_A)
        expect(page.locator('[data-test="detail-version"]')).to_contain_text("v2")
        expect(page.locator('[data-test="detail-script-pending"]')).to_be_visible()
        expect(page.locator('[data-test="flowchart-step-count"]')).to_contain_text("3")

        agg = sql_query("SELECT string_agg(description, '|' ORDER BY step_no) "
                        f"FROM tp_case_step WHERE case_id = {case_id}")
        assert agg == f"{STEP_B}|{STEP_C}|{STEP_A}", f"DB 步骤序未重排: {agg}"
        assert sql_query(f"SELECT version FROM tp_test_case WHERE id = {case_id}") == "2"
        assert sql_query(f"SELECT status FROM tp_script WHERE case_id = {case_id}") == "2", \
            "改序后脚本失效联动未落库（tick 126 链回归？）"

        _open_detail(page, case_id)
        expect(steps_table.locator("tbody tr.el-table__row td:nth-child(2)").nth(0)).to_contain_text(STEP_B)
        expect(_item(page, 1)).to_contain_text(STEP_B)
        expect(page.locator('[data-test="detail-script-pending"]')).to_be_visible()

        assert_no_errors(collectors, "C2 拖拽改序 diff 保存持久化")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")


def test_c2_flow_reorder_undo_local_zero_put(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case3(api_client, pid, uniq)

        _open_detail(page, case_id)
        expect(_item(page, 1)).to_contain_text(STEP_A)

        _drag_first_to_last(page, case_id)

        page.locator('[data-test="flow-reorder-undo"]').click()
        expect(_item(page, 1)).to_contain_text(STEP_A)
        expect(_item(page, 2)).to_contain_text(STEP_B)
        expect(_item(page, 3)).to_contain_text(STEP_C)
        expect(page.locator('[data-test="flow-reorder-save"]')).to_be_disabled()

        assert _put_count(collectors, case_id) == 0, "撤销面发生 PUT——本地态误落库（IT-FLOW-02③）"
        agg = sql_query("SELECT string_agg(description, '|' ORDER BY step_no) "
                        f"FROM tp_case_step WHERE case_id = {case_id}")
        assert agg == f"{STEP_A}|{STEP_B}|{STEP_C}", f"撤销面 DB 步骤序被落库: {agg}"
        assert sql_query(f"SELECT version FROM tp_test_case WHERE id = {case_id}") == "1"

        assert_no_errors(collectors, "C2 撤销本地零落库")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")


def test_c2_flow_reorder_purge_zero_residue(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case3(api_client, pid, uniq)
        _save_script(case_id, uniq)
        ver = api_client.get(f"/cases/{case_id}").json()["data"]["version"]
        r = api_client.put(f"/cases/{case_id}", json={
            "steps": [{"stepNo": 1, "description": STEP_C, "isAssert": 1},
                      {"stepNo": 2, "description": STEP_A, "isAssert": 0},
                      {"stepNo": 3, "description": STEP_B, "isAssert": 0}],
            "version": ver})
        assert r.status_code == 200, f"API 改序失败: {r.text[:200]}"
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")
    for table in ("tp_case_step", "tp_script", "tp_script_version"):
        assert sql_query(
            f"SELECT count(*) FROM {table} WHERE case_id = {case_id}") == "0", f"{table} 残留"
    assert sql_query(
        f"SELECT count(*) FROM tp_project WHERE id = {pid}") == "0", "项目残留"
    assert_no_errors(collectors, "C2 零残留")
