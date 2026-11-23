
import httpx
import pytest
from playwright.sync_api import expect

from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    API_BASE,
    BASE_URL,
    add_member,
    assert_no_errors,
    nav_to,
)



def _login(username: str, password: str) -> str:
    r = httpx.post(f"{API_BASE}/auth/login", json={"username": username, "password": password}, timeout=10)
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"登录业务失败: {body.get('msg')}"
    return body["data"]["accessToken"]


def _mk_user(api, uniq, role):
    pass
    username = f"k6user_{uniq}_{role}"
    r = api.post("/users", json={
        "username": username, "password": "Test@12345", "displayName": f"K6-{'测试' if role == 1 else '开发'}{uniq}",
        "email": f"{username}@test.local", "phone": "", "role": role,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用户失败: {r.text[:300]}"
    return r.json()["data"], username


def _mk_case(api, project_id, uniq, title):
    r = api.post("/cases", json={
        "projectId": project_id, "title": title, "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2, "endType": "web",
        "steps": [{"stepNo": 1, "description": f"步骤{uniq}", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]



def test_k6_assign_dialog_tester_only(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in

    r = api_client.post("/projects", json={"name": f"K6E2E_{uniq}", "code": f"K6E{uniq}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    tester_id, tester_name = _mk_user(api_client, uniq, 1)
    dev_id, dev_name = _mk_user(api_client, uniq, 3)
    for uid in (tester_id, dev_id):
        add_member(api_client, pid, uid)
    case_id = _mk_case(api_client, pid, uniq, f"K6 分配弹窗用例 {uniq}")

    try:
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        page.locator('[data-test="project-ctx-select"]').click()
        option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"K6E2E_{uniq}").first
        expect(option).to_be_visible(timeout=10000)
        option.click()
        expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="case-table"]')).to_contain_text(f"K6 分配弹窗用例 {uniq}", timeout=10000)

        c1_row = page.locator(f'[data-test="case-table"] tbody tr.el-table__row:has-text("K6 分配弹窗用例 {uniq}")')
        c1_row.locator(".el-table__expand-icon").click()
        expect(page.locator(f'[data-test="case-expand-{case_id}"]')).to_be_visible(timeout=5000)
        page.locator(f'[data-test="case-expand-{case_id}"] [data-test="case-assign"]').click()
        expect(page.locator('[data-test="assign-dialog"]')).to_be_visible(timeout=10000)

        page.locator('[data-test="assign-member-select"]').click()
        tester_item = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"K6-测试{uniq}").first
        expect(tester_item).to_be_visible(timeout=5000)
        dev_count = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"K6-开发{uniq}").count()
        assert dev_count == 0, f"开发人员不应出现在分配弹窗数据源（count={dev_count}）"
        page.keyboard.press("Escape")

        page.locator('[data-test="assign-member-select"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"K6-测试{uniq}").first.click()
        page.locator('[data-test="assign-submit"]').click()
        expect(page.locator(".el-message:has-text(\"分配成功\")").first).to_be_visible(timeout=10000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-page"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="project-ctx-select"]').click()
        option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"K6E2E_{uniq}").first
        expect(option).to_be_visible(timeout=10000)
        option.click()
        expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="case-table"]')).to_contain_text(f"K6-测试{uniq}", timeout=10000)

        r = api_client.get(f"/cases/{case_id}")
        assert r.status_code == 200, f"用例详情失败: {r.text[:300]}"
        assert r.json()["data"]["assigneeId"] == tester_id, \
            f"assignee 应为测试人员: {r.json()['data'].get('assigneeId')}"

        assert_no_errors(collectors, "K6 分配弹窗数据源")
    finally:
        api_client.delete(f"/cases/{case_id}")
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/users/{tester_id}")
        api_client.delete(f"/users/{dev_id}")



def test_k6_assign_backend_403(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid_a = pid_b = pid_c = 0
    tester_id = dev_id = cross_id = 0

    r = api_client.post("/projects", json={"name": f"K6B_{uniq}", "code": f"K6B{uniq}"})
    pid_a = r.json()["data"]
    r = api_client.post("/projects", json={"name": f"K6C_{uniq}", "code": f"K6C{uniq}"})
    pid_b = r.json()["data"]
    tester_id, _ = _mk_user(api_client, uniq, 1)
    dev_id, _ = _mk_user(api_client, uniq, 3)
    add_member(api_client, pid_a, tester_id)
    add_member(api_client, pid_a, dev_id)
    add_member(api_client, pid_b, tester_id)
    case_id = _mk_case(api_client, pid_a, uniq, f"K6 后端403用例 {uniq}")

    try:
        r = api_client.post(f"/cases/{case_id}/assign", json={"assigneeId": dev_id})
        body = r.json()
        assert r.status_code == 403 and body.get("code") == "40301", \
            f"开发人员分配应 403: {r.status_code} {r.text[:200]}"
        assert "仅可分配给本项目测试人员" in body.get("msg", ""), f"人话缺失: {body.get('msg')}"

        r = api_client.post("/projects", json={"name": f"K6D_{uniq}", "code": f"K6D{uniq}"})
        pid_c = r.json()["data"]
        cross_id, _ = _mk_user(api_client, f"{uniq}x", 1)
        add_member(api_client, pid_c, cross_id)
        r = api_client.post(f"/cases/{case_id}/assign", json={"assigneeId": cross_id})
        body = r.json()
        assert r.status_code == 403 and body.get("code") == "40301", \
            f"跨项目分配应 403: {r.status_code} {r.text[:200]}"
        assert "该用户不是本项目成员" in body.get("msg", ""), f"人话缺失: {body.get('msg')}"

        assert_no_errors(collectors, "K6 后端 403 兜底")
    finally:
        api_client.delete(f"/cases/{case_id}")
        for p in (pid_a, pid_b, pid_c):
            api_client.delete(f"/projects/{p}")
        api_client.delete(f"/users/{tester_id}")
        api_client.delete(f"/users/{dev_id}")
        api_client.delete(f"/users/{cross_id}")
