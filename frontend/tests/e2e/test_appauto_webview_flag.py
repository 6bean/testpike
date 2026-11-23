
import json
import subprocess

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

CORE = "http://localhost:8083"


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
            return
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台")


def _mk_app_case(api, uid):
    pass
    r = api.post("/projects", json={"name": f"W6旗项目_{uid}", "code": f"W6F{uid[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"WebView落区旗用例_{uid}",
        "precondition": f"前置：设备已连接（{uid}）",
        "expectedResult": f"预期：H5 区域按钮可点（{uid}）",
        "priority": 0,
        "confidence": 1,
        "endType": "web",
        "status": 1,
        "automationTarget": "app",
        "appautoEngine": "MOBILEWRIGHT",
        "steps": [{"stepNo": 1, "description": f"点击页面内嵌网页按钮（{uid}）", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return pid, r.json()["data"]


def _seed_app_script(case_id, uid):
    pass
    doc = {
        "version": "v2", "caseId": case_id, "title": f"WebView落区旗用例_{uid}", "endType": "web",
        "steps": [{"stepId": 1, "action": "app.tap",
                   "target": {"testId": f"web_btn_{uid}", "chain": ["testId"]}}],
        "env": {"ENV": "dev"},
    }
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": json.dumps(doc, ensure_ascii=False), "generatedBy": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"落脚本基线失败: {r.text[:200]}"


def _script_json(case_id):
    raw = sql(f"SELECT action_json FROM tp_script WHERE case_id={case_id} ORDER BY version DESC LIMIT 1")
    return json.loads(raw)


def _open_tree_on_tap_step(page, case_id):
    pass
    page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
    page.locator('[data-test="detail-script-tree-edit"]').click()
    dialog = page.locator('[data-test="step-tree-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)
    page.locator('[data-test^="step-tree-row-"]').first.click()
    expect(page.locator('.ste-form-action')).to_have_text("app.tap", timeout=10000)
    return dialog


def _webview_switch(page):
    return page.locator('[data-test="step-tree-field-webview"] .el-switch')


def _save_via_diff(page):
    page.locator('[data-test="step-tree-save"]').click()
    expect(page.locator('[data-test="step-tree-diff-table"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="step-tree-diff-confirm"]').click()
    expect(page.locator('.el-message').first).to_be_visible(timeout=15000)


def test_appauto_webview_step_flag_roundtrip(page, api_client, collectors, uniq):
    pass
    _inject_token(page)
    pid, case = _mk_app_case(api_client, uniq)
    _seed_app_script(case["id"], uniq)
    try:
        _open_tree_on_tap_step(page, case["id"])
        sw = _webview_switch(page)
        expect(sw).to_be_visible(timeout=10000)
        assert "is-checked" not in (sw.get_attribute("class") or ""), "初值应为关"

        sw.click()
        expect(page.locator('[data-test="step-tree-dirty"]')).to_be_visible(timeout=10000)
        _save_via_diff(page)
        steps = _script_json(case["id"])["steps"]
        tap = next((s for s in steps if s.get("action") == "app.tap"), None)
        assert tap is not None, f"DB 步序缺 app.tap: {steps}"
        assert tap.get("webview") is True, f"DB 步旗未落 true: {tap}"

        _open_tree_on_tap_step(page, case["id"])
        sw = _webview_switch(page)
        expect(sw).to_be_visible(timeout=10000)
        assert "is-checked" in (sw.get_attribute("class") or ""), "reload 后开关应回显为开"

        sw.click()
        expect(page.locator('[data-test="step-tree-dirty"]')).to_be_visible(timeout=10000)
        _save_via_diff(page)
        steps = _script_json(case["id"])["steps"]
        tap = next((s for s in steps if s.get("action") == "app.tap"), None)
        assert tap is not None and "webview" not in tap, f"取消后键未删净: {tap}"

        assert_no_errors(collectors, "T313 WebView 落区步旗")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
