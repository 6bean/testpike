
from playwright.sync_api import expect

from conftest import assert_no_errors, inject_token, nav_to, row_cmd



def _mk_project(api, code, name):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "预期",
        "priority": 2, "confidence": 2, "endType": "web", "phase": 1, "source": 2, "status": 1,
        "steps": [{"stepNo": 1, "description": "步骤一", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _goto_case_detail(page, uniq, proj_name):
    pass
    nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
    page.locator("[data-test=project-ctx-select]").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=proj_name).first.click()
    rows_or_empty = page.locator('[data-test="case-table"] tbody tr, [data-test="case-empty"]').first
    expect(rows_or_empty).to_be_visible(timeout=10000)
    page.locator('[data-test="case-filter-keyword"]').fill(f"O2面包屑用例_{uniq}")
    page.locator('[data-test="case-search"]').click()
    rows = page.locator('[data-test="case-table"] tbody tr')
    expect(rows.filter(has_text=f"O2面包屑用例_{uniq}")).to_have_count(1, timeout=10000)
    row_cmd(page, rows.first, "detail")
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10000)


def test_o2_detail_breadcrumb_chain(page, collectors, api_client, uniq):
    pass
    pid = _mk_project(api_client, f"O2A{uniq.upper()}", f"O2面包屑项目_{uniq}")
    _mk_case(api_client, pid, f"O2面包屑用例_{uniq}")
    inject_token(page)
    try:
        _goto_case_detail(page, uniq, f"O2面包屑项目_{uniq}")
        bc = page.locator('[data-test="app-breadcrumb"]')
        expect(bc).to_be_visible(timeout=10000)
        expect(bc.locator(".el-breadcrumb__item")).to_have_count(2)
        expect(bc.locator('[data-test="breadcrumb-item-cases"]')).to_contain_text("用例")
        expect(bc.locator('[data-test="breadcrumb-item-current"]')).to_contain_text(
            f"O2面包屑用例_{uniq}", timeout=10000)
        assert_no_errors(collectors, "O2 详情面包屑层级")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_o2_breadcrumb_click_back(page, collectors, api_client, uniq):
    pass
    pid = _mk_project(api_client, f"O2B{uniq.upper()}", f"O2回跳项目_{uniq}")
    _mk_case(api_client, pid, f"O2面包屑用例_{uniq}")
    inject_token(page)
    try:
        _goto_case_detail(page, uniq, f"O2回跳项目_{uniq}")
        bc = page.locator('[data-test="app-breadcrumb"]')
        expect(bc.locator('[data-test="breadcrumb-item-cases"]')).to_be_visible(timeout=10000)
        bc.locator('[data-test="breadcrumb-item-cases"]').click()
        expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="app-breadcrumb"]')).to_have_count(0)
        assert_no_errors(collectors, "O2 面包屑回跳")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_o2_project_detail_breadcrumb(page, collectors, api_client, uniq):
    pass
    pid = _mk_project(api_client, f"O2C{uniq.upper()}", f"O2项目面包屑_{uniq}")
    inject_token(page)
    try:
        nav_to(page, "项目管理", "/projects", '[data-test="project-table"]', submenu="系统管理")
        rows = page.locator('[data-test="project-table"] tbody tr')
        expect(rows.filter(has_text=f"O2项目面包屑_{uniq}")).to_have_count(1, timeout=10000)
        rows.filter(has_text=f"O2项目面包屑_{uniq}").first.locator(".el-link").click()
        page.wait_for_url("**/projects/**", timeout=10000)
        expect(page.locator('[data-test="env-table"]')).to_be_visible(timeout=10000)
        bc = page.locator('[data-test="app-breadcrumb"]')
        expect(bc).to_be_visible(timeout=10000)
        expect(bc.locator('[data-test="breadcrumb-item-projects"]')).to_contain_text("项目")
        expect(bc.locator('[data-test="breadcrumb-item-current"]')).to_contain_text(
            f"O2项目面包屑_{uniq}", timeout=10000)
        bc.locator('[data-test="breadcrumb-item-projects"]').click()
        expect(page.locator('[data-test="project-table"]')).to_be_visible(timeout=10000)
        assert_no_errors(collectors, "O2 项目详情面包屑")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
