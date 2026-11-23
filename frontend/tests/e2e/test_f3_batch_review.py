
import re
import uuid

import pytest
from playwright.sync_api import expect

from conftest import nav_to, assert_no_errors



def _mk_project(api, uniq):
    name = f"F3B项目_{uniq}"
    code = f"F3B{uniq[:6].upper()}"
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
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()


def test_f3_batch_pass_persist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "批量甲", 0, 1, 1)
    c2 = _mk_case(api_client, pid, uniq, "批量乙", 1, 3, 1)

    page.reload(wait_until="domcontentloaded")
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(2)

    page.locator('[data-test="case-table"] thead .el-checkbox').click()
    expect(page.locator('[data-test="case-batch-bar"]')).to_be_visible()
    expect(page.locator('[data-test="case-batch-pass"]')).to_be_visible()
    expect(page.locator('[data-test="case-batch-reject"]')).to_be_visible()

    page.locator('[data-test="case-batch-pass"]').click()
    expect(page.locator(".el-message-box")).to_contain_text("2 条")
    expect(page.locator(".el-message-box")).to_contain_text("低置信度")
    page.locator('.el-message-box__btns button:has-text("通过所选用例")').click()

    expect(page.locator(".el-message").filter(has_text="2 条成功")).to_be_visible(timeout=5000)

    for c in (c1, c2):
        r = api_client.get(f"/cases/{c['id']}")
        assert r.json()["data"]["status"] == 3, f"用例 {c['caseNo']} 应为已通过(3)"

    page.reload(wait_until="domcontentloaded")
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)
    page.locator('[data-test="case-filter-status"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="已通过").first.click()
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(2)
    expect(page.locator('[data-test="case-table"]')).to_contain_text("批量甲")
    expect(page.locator('[data-test="case-table"]')).to_contain_text("批量乙")

    assert_no_errors(collectors, "F-3 批量通过")


def test_f3_batch_reject_reason_required(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "拒绝甲", 0, 2, 1)
    c2 = _mk_case(api_client, pid, uniq, "拒绝乙", 2, 2, 1)

    page.reload(wait_until="domcontentloaded")
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(2)

    page.locator('[data-test="case-table"] thead .el-checkbox').click()
    page.locator('[data-test="case-batch-reject"]').click()
    expect(page.locator('[data-test="reason-dialog"]')).to_be_visible()
    page.locator('[data-test="reason-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="请填写原因")).to_be_visible(timeout=5000)

    page.locator('[data-test="reason-input"]').fill(f"不符合验收标准（{uniq}）")
    page.locator('[data-test="problem-type-logic_missing"]').click()
    page.locator('[data-test="reason-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="2 条成功")).to_be_visible(timeout=5000)

    for c in (c1, c2):
        r = api_client.get(f"/cases/{c['id']}")
        assert r.json()["data"]["status"] == 5, f"用例 {c['caseNo']} 应为已拒绝(5)"

    page.reload(wait_until="domcontentloaded")
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)
    page.locator('[data-test="case-filter-status"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="已拒绝").first.click()
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(2)

    assert_no_errors(collectors, "F-3 批量拒绝")


def test_f3_batch_skip_terminal_and_mixed(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "混合待评", 0, 1, 1)
    c2 = _mk_case(api_client, pid, uniq, "混合已过", 1, 2, 3)

    page.reload(wait_until="domcontentloaded")
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(2)

    page.locator('[data-test="case-table"] tbody tr.el-table__row').filter(has_text=c1["caseNo"]).locator(".el-checkbox").click()
    expect(page.locator('[data-test="case-batch-bar"]')).to_be_visible()

    passed_row = page.locator('[data-test="case-table"] tbody tr.el-table__row').filter(has_text="混合已过")
    expect(passed_row.locator(".el-checkbox")).to_have_class(re.compile(r"is-disabled"))

    page.locator('[data-test="case-batch-pass"]').click()
    page.locator('.el-message-box__btns button:has-text("通过所选用例")').click()
    expect(page.locator(".el-message").filter(has_text="1 条成功")).to_be_visible(timeout=5000)

    r = api_client.get(f"/cases/{c1['id']}")
    assert r.json()["data"]["status"] == 3, f"用例 {c1['caseNo']} 应为已通过(3)"
    r2 = api_client.get(f"/cases/{c2['id']}")
    assert r2.json()["data"]["status"] == 3, f"用例 {c2['caseNo']} 应保持已通过(3)"

    assert_no_errors(collectors, "F-3 批量跳过语义")
