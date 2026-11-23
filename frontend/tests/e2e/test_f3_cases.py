
import uuid

import pytest
from playwright.sync_api import expect

from conftest import nav_to, row_cmd, assert_no_errors



def _mk_project(api, uniq):
    pass
    name = f"F3项目_{uniq}"
    code = f"F3{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200, f"创建项目失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    return body["data"], name


def _mk_case(api, pid, uniq, title, priority, confidence, status):
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


def _select_project(page, project_name):
    pass
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
def _row_count(page):
    return page.locator('[data-test="case-table"] tbody tr.el-table__row').count()


def test_f3_list_render_and_filter(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "登录功能验证", 0, 1, 1)
    c2 = _mk_case(api_client, pid, uniq, "导出报表验证", 2, 3, 1)
    _mk_case(api_client, pid, uniq, "搜索功能验证", 1, 2, 3)

    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]')).to_be_visible()
    expect(page.locator('[data-test="my-project-table"]')).to_contain_text(pname)

    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)
    expect(page.locator('[data-test="case-table"]')).to_be_visible()
    expect(page.locator('[data-test="case-table"]')).to_contain_text("TC-F3")
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(3), f"期望 3 行，实际 {_row_count(page)}"
    expect(page.locator('[data-test="case-table"]')).to_contain_text("置信度")
    expect(page.locator('[data-test="case-table"]')).to_contain_text("待评审")
    expect(page.locator('[data-test="case-table"]')).to_contain_text("已通过")

    page.locator('[data-test="case-filter-status"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="已通过").first.click()
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(1)

    page.locator('[data-test="case-reset"]').click()
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(3)
    page.locator('[data-test="case-filter-priority"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="P2").first.click()
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(1)
    expect(page.locator('[data-test="case-table"]')).to_contain_text("导出报表")

    page.locator('[data-test="case-reset"]').click()
    page.locator('[data-test="case-filter-keyword"]').fill("登录功能")
    page.locator('[data-test="case-search"]').click()
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(1)
    expect(page.locator('[data-test="case-table"]')).to_contain_text("登录功能验证")

    assert_no_errors(collectors, "F-3 列表渲染与筛选")


def test_f3_quick_review_jk_enter_pass(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "待评甲", 0, 1, 1)
    c2 = _mk_case(api_client, pid, uniq, "待评乙", 1, 2, 1)

    page.reload(wait_until="domcontentloaded")
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)
    page.locator('[data-test="case-filter-status"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="待评审").first.click()
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(2)

    page.locator('[data-test="quick-review-toggle"]').click()
    expect(page.locator('[data-test="quick-hint"]')).to_be_visible()
    first_no = page.locator('[data-test="quick-active"]').inner_text().strip()
    assert first_no in (c1["caseNo"], c2["caseNo"]), f"首行编号异常: {first_no}"
    second_no = c2["caseNo"] if first_no == c1["caseNo"] else c1["caseNo"]

    page.keyboard.press("j")
    expect(page.locator('[data-test="quick-active"]')).to_contain_text(second_no)
    page.keyboard.press("k")
    expect(page.locator('[data-test="quick-active"]')).to_contain_text(first_no)

    page.keyboard.press("Enter")
    page.locator('.el-message-box__btns button:has-text("通过")').click()
    expect(page.locator(".el-message").filter(has_text="已通过")).to_be_visible(timeout=5000)
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(1), f"通过后队列应剩 1 行，实际 {_row_count(page)}"
    first_id = c1["id"] if first_no == c1["caseNo"] else c2["id"]

    r = api_client.get(f"/cases/{first_id}")
    assert r.json()["data"]["status"] == 3, "用例状态应为已通过(3)"

    page.reload(wait_until="domcontentloaded")
    _select_project(page, pname)
    page.locator('[data-test="case-filter-status"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="待评审").first.click()
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(1)
    expect(page.locator('[data-test="case-table"]')).to_contain_text(second_no)
    expect(page.locator('[data-test="case-table"]')).not_to_contain_text(first_no)

    assert_no_errors(collectors, "F-3 快评 J/K+Enter")


def test_f3_quick_review_r_need_change_reason(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "需改用例", 1, 2, 1)

    page.reload(wait_until="domcontentloaded")
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)
    expect(page.locator('[data-test="case-table"]')).to_contain_text(c1["caseNo"])

    page.locator('[data-test="quick-review-toggle"]').click()
    page.keyboard.press("r")
    expect(page.locator('[data-test="reason-dialog"]')).to_be_visible()

    page.locator('[data-test="reason-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="请填写原因")).to_be_visible()
    expect(page.locator('[data-test="reason-dialog"]')).to_be_visible()

    page.locator('[data-test="reason-input"]').fill("缺少异常场景覆盖")
    page.locator('[data-test="problem-type-logic_missing"]').click()
    page.locator('[data-test="reason-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="已需修改")).to_be_visible(timeout=5000)
    r = api_client.get(f"/cases/{c1['id']}")
    assert r.json()["data"]["status"] == 4, "用例状态应为需修改(4)"

    page.reload(wait_until="domcontentloaded")
    _select_project(page, pname)
    expect(page.locator('[data-test="case-table"]')).to_contain_text("需修改")

    assert_no_errors(collectors, "F-3 快评 R 需修改")


def test_f3_row_actions_edit_delete(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "行操作用例甲", 0, 1, 1)

    page.reload(wait_until="domcontentloaded")
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)

    row = page.locator(f'[data-test="case-table"] tbody tr.el-table__row:has-text("{c1["caseNo"]}")')
    expect(row.locator('[data-test="ra-detail"]')).to_be_visible()
    expect(row.locator('[data-test="ra-edit"]')).to_be_visible()

    row_cmd(page, row, "edit")
    expect(page.locator('[data-test="case-create-dialog"]')).to_be_visible()
    expect(page.locator('[data-test="case-create-title"]')).to_have_value(c1["title"])
    new_title = f"行操作用例甲改_{uniq}"
    page.locator('[data-test="case-create-title"]').fill(new_title)
    with page.expect_response(lambda r: r.request.method == "PUT" and "/api/v1/cases/" in r.url):
        page.locator('[data-test="case-create-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="修改成功")).to_be_visible(timeout=5000)
    page.reload(wait_until="domcontentloaded")
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)
    expect(page.locator('[data-test="case-table"]')).to_contain_text(new_title)

    row = page.locator(f'[data-test="case-table"] tbody tr.el-table__row:has-text("{new_title}")')
    row_cmd(page, row, "delete")
    expect(page.locator(".el-message").filter(has_text="删除成功")).to_be_visible(timeout=5000)
    page.reload(wait_until="domcontentloaded")
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)
    expect(page.locator('[data-test="case-table"]')).not_to_contain_text(new_title)

    assert_no_errors(collectors, "F-3 行操作编辑/删除")
