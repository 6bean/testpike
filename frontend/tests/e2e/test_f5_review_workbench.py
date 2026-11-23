
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import API_BASE, add_member, assert_no_errors, mk_tester, nav_to



def _mk_project(api, uniq):
    pass
    name = f"F5项目_{uniq}"
    code = f"F5{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200, f"创建项目失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    return body["data"], name


def _mk_case(api, pid, uniq, title, priority=1, confidence=2, status=1):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "precondition": f"前置：已登录系统（{uniq}）",
        "expectedResult": f"预期：操作成功（{uniq}）",
        "priority": priority,
        "confidence": confidence,
        "endType": "web",
        "status": status,
        "steps": [{"stepNo": 1, "description": "打开页面 /login", "isAssert": 0}],
    })
    assert r.status_code == 200, f"创建用例失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建用例业务失败: {body.get('msg')}"
    return body["data"]


def _login(username: str, password: str) -> str:
    pass
    r = httpx.post(f"{API_BASE}/auth/login", json={"username": username, "password": password}, timeout=10)
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"登录业务失败: {body.get('msg')}"
    return body["data"]["accessToken"]


def _assign(api, case_id, assignee_id):
    r = api.post(f"/cases/{case_id}/assign", json={"assigneeId": assignee_id})
    assert r.status_code == 200, f"分配用例失败: {r.status_code} {r.text[:300]}"
    assert r.json().get("code") == "0", f"分配业务失败: {r.json().get('msg')}"


def _review_pass(api, case_id):
    pass
    r = api.get(f"/cases/{case_id}")
    assert r.status_code == 200, f"取用例详情失败: {r.status_code}"
    case = r.json()["data"]
    rr = api.post(f"/cases/{case_id}/review", json={"action": "pass", "version": case["version"]})
    assert rr.status_code == 200, f"评审失败: {rr.status_code} {rr.text[:300]}"
    assert rr.json().get("code") == "0", f"评审业务失败: {rr.json().get('msg')}"


def _cleanup(api, pid, tester_id):
    pass
    api.delete(f"/projects/{pid}/purge?force=true")
    api.delete(f"/users/{tester_id}")


def _select_project(page, test_id, project_name):
    page.locator(f'[data-test="{test_id}"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
def _row_count(page, table_test_id):
    return page.locator(f'[data-test="{table_test_id}"] tbody tr.el-table__row').count()


def test_f5_workbench_stats_and_queue(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "工作台用例甲", 0, 1, 1)
    c2 = _mk_case(api_client, pid, uniq, "工作台用例乙", 1, 2, 1)
    _mk_case(api_client, pid, uniq, "未分配用例", 2, 3, 1)
    tester_id, tester_nick = mk_tester(api_client, uniq, "f5")
    add_member(api_client, pid, tester_id)
    _assign(api_client, c1["id"], tester_id)
    _assign(api_client, c2["id"], tester_id)
    _review_pass(api_client, c1["id"])

    try:

        tester_token = _login(f"f5tester_{uniq}", "Test@12345")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{tester_token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "评审工作台", "/review", '[data-test="review-page"]', submenu="设计用例")
        _select_project(page, "project-ctx-select", pname)

        expect(page.locator('[data-test="stat-todo"]')).to_have_text("1")
        expect(page.locator('[data-test="stat-done"]')).to_have_text("1")
        expect(page.locator('[data-test="stat-progress-text"]')).to_contain_text("1/2")
        expect(page.locator('[data-test="stat-rate"]')).to_contain_text("50%")

        expect(page.locator('[data-test="review-table"]')).to_be_visible()
        expect(page.locator('[data-test="review-table"] tbody tr.el-table__row')).to_have_count(2), f"期望 2 行，实际 {_row_count(page, 'review-table')}"
        expect(page.locator('[data-test="review-table"]')).to_contain_text(c1["caseNo"])
        expect(page.locator('[data-test="review-table"]')).to_contain_text(c2["caseNo"])
        expect(page.locator('[data-test="review-table"]')).not_to_contain_text("未分配用例")

        page.locator('[data-test="review-filter-status"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="待评审").first.click()
        expect(page.locator('[data-test="review-table"] tbody tr.el-table__row')).to_have_count(1)
        expect(page.locator('[data-test="review-table"]')).to_contain_text(c2["caseNo"])

        assert_no_errors(collectors, "F-5 工作台统计+队列")
    finally:
        _cleanup(api_client, pid, tester_id)


def test_f5_workbench_quick_review_enter_persist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "快评用例", 0, 2, 1)
    tester_id, tester_nick = mk_tester(api_client, uniq, "f5")
    add_member(api_client, pid, tester_id)
    _assign(api_client, c1["id"], tester_id)

    try:

        tester_token = _login(f"f5tester_{uniq}", "Test@12345")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{tester_token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "评审工作台", "/review", '[data-test="review-page"]', submenu="设计用例")
        _select_project(page, "project-ctx-select", pname)

        page.locator('[data-test="review-quick-toggle"]').click()
        expect(page.locator('[data-test="review-quick-hint"]')).to_be_visible()
        expect(page.locator('[data-test="review-quick-active"]')).to_contain_text(c1["caseNo"])
        page.keyboard.press("Enter")
        page.locator('.el-message-box__btns button:has-text("通过")').click()
        expect(page.locator(".el-message").filter(has_text="已通过")).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="stat-todo"]')).to_have_text("0")
        expect(page.locator('[data-test="stat-done"]')).to_have_text("1")
        expect(page.locator('[data-test="stat-rate"]')).to_contain_text("100%")

        r = api_client.get(f"/cases/{c1['id']}")
        assert r.json()["data"]["status"] == 3, "用例状态应为已通过(3)"

        page.reload(wait_until="domcontentloaded")
        _select_project(page, "project-ctx-select", pname)
        expect(page.locator('[data-test="stat-todo"]')).to_have_text("0")
        expect(page.locator('[data-test="stat-rate"]')).to_contain_text("100%")

        assert_no_errors(collectors, "F-5 工作台快评 Enter")
    finally:
        _cleanup(api_client, pid, tester_id)


def test_f5_assign_single_and_unassigned_view(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "待分配甲", 0, 1, 1)
    c2 = _mk_case(api_client, pid, uniq, "待分配乙", 1, 2, 1)
    tester_id, tester_nick = mk_tester(api_client, uniq, "f5")
    add_member(api_client, pid, tester_id)

    try:

        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_project(page, "project-ctx-select", pname)
        expect(page.locator('[data-test="case-table"]')).to_contain_text(c1["caseNo"])

        c1_row = page.locator(f'[data-test="case-table"] tbody tr.el-table__row:has-text("{c1["caseNo"]}")')
        c1_row.locator(".el-table__expand-icon").click()
        expect(page.locator(f'[data-test="case-expand-{c1["id"]}"]')).to_be_visible(timeout=5000)
        page.locator(f'[data-test="case-expand-{c1["id"]}"] [data-test="case-assign"]').click()
        expect(page.locator('[data-test="assign-dialog"]')).to_be_visible()

        page.locator('[data-test="assign-member-select"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=tester_nick).first.click()
        page.locator('[data-test="assign-submit"]').click()
        expect(page.locator(".el-message").filter(has_text="分配成功")).to_be_visible(timeout=5000)
        r = api_client.get(f"/cases/{c1['id']}")
        assert r.json()["data"]["assigneeId"] == tester_id, f"assigneeId 应为 {tester_id}"

        page.reload(wait_until="domcontentloaded")
        _select_project(page, "project-ctx-select", pname)
        expect(page.locator('[data-test="case-table"]')).to_contain_text(tester_nick)

        page.locator('[data-test="case-filter-assignee"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="未分配").first.click()
        expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(1)
        expect(page.locator('[data-test="case-table"]')).to_contain_text(c2["caseNo"])
        expect(page.locator('[data-test="case-table"]')).not_to_contain_text(c1["caseNo"])

        tester_token = _login(f"f5tester_{uniq}", "Test@12345")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{tester_token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_project(page, "project-ctx-select", pname)
        page.locator('[data-test="case-filter-assignee"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="分配给我").first.click()
        expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(1)
        expect(page.locator('[data-test="case-table"]')).to_contain_text(c1["caseNo"])

        assert_no_errors(collectors, "F-5 单条分配+未分配视图")
    finally:
        _cleanup(api_client, pid, tester_id)


def test_f5_batch_assign_persist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "批量甲", 0, 1, 1)
    c2 = _mk_case(api_client, pid, uniq, "批量乙", 1, 2, 1)
    c3 = _mk_case(api_client, pid, uniq, "批量丙", 2, 3, 1)
    tester_id, tester_nick = mk_tester(api_client, uniq, "f5")
    add_member(api_client, pid, tester_id)

    try:

        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_project(page, "project-ctx-select", pname)
        expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(3)

        rows = page.locator('[data-test="case-table"] tbody tr.el-table__row')
        rows.filter(has_text=c1["caseNo"]).locator(".el-checkbox").click()
        rows.filter(has_text=c2["caseNo"]).locator(".el-checkbox").click()
        expect(page.locator('[data-test="case-batch-bar"]')).to_be_visible()
        expect(page.locator('[data-test="case-batch-bar"]')).to_contain_text("已选 2 条")

        page.locator('[data-test="case-batch-assign"]').click()
        expect(page.locator('[data-test="assign-dialog"]')).to_be_visible()
        page.locator('[data-test="assign-member-select"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=tester_nick).first.click()
        page.locator('[data-test="assign-submit"]').click()
        expect(page.locator(".el-message").filter(has_text="分配成功")).to_be_visible(timeout=5000)
        for c in (c1, c2):
            r = api_client.get(f"/cases/{c['id']}")
            assert r.json()["data"]["assigneeId"] == tester_id, f"用例 {c['caseNo']} 应分配给 {tester_id}"
        r3 = api_client.get(f"/cases/{c3['id']}")
        assert r3.json()["data"]["assigneeId"] is None, "批量丙 不应被分配"

        page.reload(wait_until="domcontentloaded")
        _select_project(page, "project-ctx-select", pname)
        expect(page.locator('[data-test="case-table"]')).to_contain_text(tester_nick)
        page.locator('[data-test="case-filter-assignee"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="未分配").first.click()
        expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(1)
        expect(page.locator('[data-test="case-table"]')).to_contain_text(c3["caseNo"])

        assert_no_errors(collectors, "F-5 批量分配")
    finally:
        _cleanup(api_client, pid, tester_id)
