
import json

import httpx
import pytest
from playwright.sync_api import expect

from conftest import assert_no_errors, nav_to, pick_project_in_header
from conftest import api_client, logged_in, sql_query, uniq  # noqa: F401

pytestmark = pytest.mark.timeout(180)

CORE = "http://localhost:8083"
API = "http://localhost:8081/api/v1"

STEP_A = "打开登录页"
STEP_B = "输入账号密码"
STEP_C = "点击登录按钮"


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} 业务失败: {body.get('msg')}"
    return body["data"]


def _mk_fixture(api, uid):
    pass
    pid = _ok(api.post("/projects", json={
        "name": f"别名项目{uid}", "code": f"AL{uid[:6].upper()}"}), "建项目")
    cid = _ok(api.post("/cases", json={
        "projectId": pid, "title": f"别名场景{uid}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 1,
        "endType": "web", "status": 1,
        "steps": [
            {"stepNo": 1, "description": STEP_A, "isAssert": 0},
            {"stepNo": 2, "description": STEP_B, "isAssert": 0},
            {"stepNo": 3, "description": STEP_C, "isAssert": 1}]}), "建用例")["id"]
    action = json.dumps({
        "version": "v2", "caseId": str(cid), "endType": "web",
        "steps": [
            {"stepId": 1, "action": "goto", "target": {"css": "#login"}},
            {"stepId": 2, "action": "input", "target": {"text": "用户名"}, "value": "tester"},
            {"stepId": 3, "action": "expect", "target": {"css": ".welcome"}}],
        "env": {"ENV": "dev"},
    }, ensure_ascii=False)
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": cid, "schemaVersion": "v2",
                         "actionJson": action, "generatedBy": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"预置脚本失败: {r.text[:200]}"
    return pid, cid


def _open_detail(page, case_id):
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")


def _pick_project(page, name):
    pass
    pick_project_in_header(page, name)


def test_v7_step_alias_register_reload_and_group_readback(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    uid = uniq
    alias = f"创建订单{uid}"
    pid = cid = None
    alias_ids = []
    try:
        pid, cid = _mk_fixture(api_client, uid)

        _open_detail(page, cid)
        panel = page.locator('[data-test="flow-reorder-panel"]')
        expect(panel).to_be_visible()
        expect(page.locator('[data-test="step-rename-btn-1"]')).to_be_visible()
        page.locator('[data-test="step-rename-btn-1"]').click()
        dialog = page.locator('[data-test="step-alias-dialog"]')
        expect(dialog).to_be_visible()
        page.locator('[data-test="step-alias-name-input"]').fill(alias)
        expect(page.locator('[data-test="step-alias-hint"]')).to_contain_text(alias)
        page.locator('[data-test="step-alias-save"]').click()
        expect(page.locator(".el-message").filter(has_text="步骤别名已登记")).to_be_visible(timeout=8000)

        row = sql_query(
            f"SELECT alias_name || '#' || kind || '#' || target FROM tp_step_alias "
            f"WHERE project_id = {pid} AND alias_name = '{alias}'")
        assert row == f"{alias}#step#1", f"别名未落库或指向漂移: {row}"

        _open_detail(page, cid)
        expect(page.locator('[data-test="flow-reorder-panel"]')).to_be_visible()
        page.locator('[data-test="step-rename-btn-1"]').click()
        expect(page.locator('[data-test="step-alias-current"]')).to_contain_text(alias)
        expect(page.locator('[data-test="step-alias-name-input"]')).to_have_value(alias)
        page.locator('[data-test="step-alias-cancel"]').click()

        nav_to(page, "角色与绑定", "/role-bindings",
               '[data-test="role-card-page"]', submenu="造数与资产")
        _pick_project(page, f"别名项目{uid}")
        group = page.locator('[data-test="role-card-alias-group"]')
        expect(group).to_be_visible()
        expect(group).to_contain_text(alias)
        expect(group).to_contain_text("{{" + alias + ".字段}}")
        copy_btn = page.locator('[data-test^="role-card-alias-copy-"]').first
        expect(copy_btn).to_be_visible()
        page.context.grant_permissions(["clipboard-read", "clipboard-write"],
                                       origin="http://localhost:5173")
        copy_btn.click()
        expect(page.locator(".el-message").filter(has_text="复制").first) \
            .to_contain_text("复制", timeout=5000)

        posts = [r for r in collectors["requests"]
                 if r["method"] == "POST" and "/api/v1/apitest/step-aliases" in r["url"]]
        assert posts, "登记未走 admin 用户端接口（请求链断）"

        assert_no_errors(collectors, "引用变量别名三入口用户端链")
    finally:
        if pid:
            r = httpx.get(f"{API}/apitest/step-aliases", headers=api_client.headers,
                          params={"projectId": pid}, timeout=15)
            if r.status_code == 200:
                for a in r.json().get("data") or []:
                    alias_ids.append(a["id"])
                    httpx.delete(f"{API}/apitest/step-aliases/{a['id']}",
                                 headers=api_client.headers, timeout=15)
            api_client.delete(f"/projects/{pid}/purge?force=true")
        left = sql_query(
            f"SELECT count(*) FROM tp_step_alias WHERE project_id = {pid}") if pid else "0"
        assert left == "0", f"别名清理残留 pid={pid} count={left}"
