
import uuid

import pytest
from playwright.sync_api import expect

from conftest import nav_to, row_cmd, assert_no_errors, BASE_URL



def _mk_project(api, uniq):
    name = f"F6项目_{uniq}"
    code = f"F6{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200, f"创建项目失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    return body["data"], name


def _mk_case(api, pid, uniq, title):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "priority": 1,
        "confidence": 2,
        "endType": "web",
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
def _row_count(page):
    return page.locator('[data-test="req-table"] tbody tr.el-table__row').count()


def _open_req_page(page):
    # Requirement ledger re-anchored: standalone /requirements page, the
    # menu leaf navigates straight to it. Same spectrum as k7.
    nav_to(page, "需求点管理", "/requirements", '[data-test="req-page"]', submenu="设计用例")
    expect(page.locator('[data-test="project-ctx-select"]')).to_be_visible(timeout=10000)


def test_f6_requirement_crud(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)

    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    _select_project(page, pname)

    expect(page.locator('[data-test="empty-state"]')).to_be_visible()
    expect(page.locator('[data-test="empty-state"]')).to_contain_text("还没有需求条目")

    page.locator('[data-test="req-create"]').click()
    expect(page.locator('[data-test="req-edit-dialog"]')).to_be_visible()
    page.locator('[data-test="req-source-id"]').fill(f"R-HAND-{uniq}")
    page.locator('[data-test="req-title"]').fill(f"手工登录需求_{uniq}")
    page.locator('[data-test="req-acceptance"]').fill(f"验收标准：登录成功（{uniq}）")
    page.locator('[data-test="req-chapter"]').fill("3.1")
    page.locator('[data-test="req-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="创建成功").last).to_be_visible(timeout=5000)
    page.locator('[data-test="req-create"]').click()
    page.locator('[data-test="req-source"]').get_by_text("Jira").click()
    page.locator('[data-test="req-source-id"]').fill(f"JIRA-{uniq}")
    page.locator('[data-test="req-title"]').fill(f"Jira支付需求_{uniq}")
    page.locator('[data-test="req-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="创建成功")).to_be_visible(timeout=5000)
    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    _select_project(page, pname)
    expect(page.locator('[data-test="req-table"]')).to_contain_text(f"手工登录需求_{uniq}")
    expect(page.locator('[data-test="req-table"]')).to_contain_text(f"Jira支付需求_{uniq}")
    expect(page.locator('[data-test="req-table"] tbody tr.el-table__row')).to_have_count(2), f"期望 2 行，实际 {_row_count(page)}"
    expect(page.locator('[data-test="req-table"]')).to_contain_text("Jira")
    expect(page.locator('[data-test="req-table"]')).to_contain_text("活跃")

    page.locator('[data-test="req-filter-status"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="活跃").first.click()
    expect(page.locator('[data-test="req-table"] tbody tr.el-table__row')).to_have_count(2), f"活跃筛选期望 2 行，实际 {_row_count(page)}"

    page.locator('[data-test="req-filter-status"]').hover()
    page.locator('[data-test="req-filter-status"] .el-select__clear').click()
    page.locator('[data-test="req-filter-keyword"]').fill(f"Jira支付需求_{uniq}")
    page.locator('[data-test="req-filter-keyword"]').press("Enter")
    expect(page.locator('[data-test="req-table"] tbody tr.el-table__row')).to_have_count(1), f"关键词筛选期望 1 行，实际 {_row_count(page)}"
    expect(page.locator('[data-test="req-table"]')).to_contain_text("JIRA-")

    page.locator('[data-test="req-reset"]').click()
    expect(page.locator('[data-test="req-table"] tbody tr.el-table__row')).to_have_count(2), f"重置期望 2 行，实际 {_row_count(page)}"

    row = page.locator(f'[data-test="req-table"] tbody tr.el-table__row:has-text("手工登录需求_{uniq}")')
    row_cmd(page, row, "edit")
    expect(page.locator('[data-test="req-edit-dialog"]')).to_be_visible()
    page.locator('[data-test="req-title"]').fill(f"手工登录需求改_{uniq}")
    page.locator('[data-test="req-status"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="已下线").first.click()
    expect(page.locator(".req-status-hint")).to_be_visible()
    page.locator('[data-test="req-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="更新成功")).to_be_visible(timeout=5000)
    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    _select_project(page, pname)
    expect(page.locator('[data-test="req-table"]')).to_contain_text(f"手工登录需求改_{uniq}")
    expect(page.locator('[data-test="req-table"]')).to_contain_text("已下线")
    expect(page.locator('[data-test="req-table"] tbody tr.el-table__row')).to_have_count(2)

    row = page.locator(f'[data-test="req-table"] tbody tr.el-table__row:has-text("手工登录需求改_{uniq}")')
    row_cmd(page, row, "delete")
    expect(page.locator(".el-message-box")).to_be_visible()
    page.locator('.el-message-box__btns button:has-text("确定")').click()
    expect(page.locator(".el-message").filter(has_text="删除成功")).to_be_visible(timeout=5000)
    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    _select_project(page, pname)
    expect(page.locator('[data-test="req-table"] tbody tr.el-table__row')).to_have_count(1), f"删除后期望 1 行，实际 {_row_count(page)}"

    assert_no_errors(collectors, "F-6 需求点 CRUD")


def test_f6_requirement_link_cases_and_409(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "登录成功用例")
    c2 = _mk_case(api_client, pid, uniq, "支付成功用例")

    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    _select_project(page, pname)

    page.locator('[data-test="req-create"]').click()
    page.locator('[data-test="req-title"]').fill(f"关联测试需求_{uniq}")
    page.locator('[data-test="req-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="创建成功")).to_be_visible(timeout=5000)
    frow = page.locator('[data-test="req-table"] tbody tr.el-table__row').first
    row_cmd(page, frow, "link")
    expect(page.locator('[data-test="req-link-dialog"]')).to_be_visible()
    expect(page.locator('[data-test="req-linked-empty"]')).to_be_visible()
    expect(page.locator('[data-test="req-candidate-table"]')).to_contain_text(c1["caseNo"])
    expect(page.locator('[data-test="req-candidate-table"]')).to_contain_text(c2["caseNo"])

    page.locator('[data-test="req-candidate-table"] tbody tr').first.locator(
        ".el-checkbox").click()
    page.locator('[data-test="req-candidate-table"] tbody tr').nth(1).locator(
        ".el-checkbox").click()
    expect(page.locator('[data-test="req-link-submit"]')).to_have_text("关联所选（2）")
    page.locator('[data-test="req-link-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="关联成功")).to_be_visible(timeout=5000)
    expect(page.locator('[data-test="req-linked-count"]')).to_contain_text("2")
    expect(page.locator(f'[data-test="req-linked-{c1["id"]}"]')).to_be_visible()
    expect(page.locator(f'[data-test="req-linked-{c2["id"]}"]')).to_be_visible()

    page.locator('.el-dialog__footer button:has-text("关闭")').click()
    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    _select_project(page, pname)
    row_cmd(page, page.locator('[data-test="req-table"] tbody tr.el-table__row').first, "link")
    expect(page.locator('[data-test="req-linked-count"]')).to_contain_text("2", timeout=5000)
    expect(page.locator(f'[data-test="req-linked-{c1["id"]}"]')).to_be_visible()

    page.locator(f'[data-test="req-linked-{c1["id"]}"] [data-test="req-unlink"]').click()
    expect(page.locator(".el-message").filter(has_text="已取消关联")).to_be_visible(timeout=5000)
    expect(page.locator('[data-test="req-linked-count"]')).to_contain_text("1")
    expect(page.locator(f'[data-test="req-linked-{c1["id"]}"]')).to_have_count(0)
    page.locator('.el-dialog__footer button:has-text("关闭")').click()
    row_cmd(page, page.locator('[data-test="req-table"] tbody tr.el-table__row').first, "delete")
    expect(page.locator(".el-message-box")).to_be_visible()
    page.locator('.el-message-box__btns button:has-text("确定")').click()
    expect(page.locator(".el-message").filter(has_text="已被用例关联")).to_be_visible(timeout=5000)
    expect(page.locator('[data-test="req-table"] tbody tr.el-table__row')).to_have_count(1)

    # Derive from the single BASE_URL source (slot1 lane runs on another port;
    # a hardcoded main-lane port silently missed the whitelist there).
    del_url = f"{BASE_URL}/api/v1/projects/{pid}/requirements"
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not (e["status"] == 409 and del_url in e["url"])
    ]
    collectors["console_errors"] = [
        e for e in collectors["console_errors"] if "status of 409" not in e
    ]
    assert_no_errors(collectors, "F-6 关联用例 + 409")
