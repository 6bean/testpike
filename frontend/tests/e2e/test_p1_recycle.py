
import uuid

from playwright.sync_api import expect

from conftest import nav_to, assert_no_errors



def _mk_project(api, uniq):
    name = f"P1项目_{uniq}"
    code = f"P1R{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.json().get("code") == "0", f"创建项目失败: {r.text[:200]}"
    return r.json()["data"], name


def _mk_case(api, pid, uniq, title):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "priority": 0,
        "confidence": 2,
        "endType": "web",
        "steps": [{"stepNo": 1, "description": "打开页面 /login", "isAssert": 0}],
    })
    assert r.json().get("code") == "0", f"创建用例失败: {r.text[:200]}"
    return r.json()["data"]


def _select_project(page, project_name):
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()


def test_p1_recycle_restore_flow(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, uniq, "回收站用例")
    case_no = case["caseNo"]

    try:
        r = api_client.delete(f"/cases/{case['id']}")
        assert r.json().get("code") == "0"

        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_project(page, pname)
        expect(page.locator('[data-test="case-table"]')).not_to_contain_text(case["title"])
        page.locator('[data-test="case-recycle-open"]').click()
        drawer = page.locator('[data-test="case-recycle-drawer"]')
        expect(drawer).to_be_visible()

        row = drawer.locator(f'[data-test="recycle-table"] tbody tr.el-table__row:has-text("{case_no}")')
        expect(row).to_have_count(1, timeout=5000)
        expect(row).to_contain_text("系统管理员")

        row.locator(f'[data-test="recycle-restore-{case["id"]}"]').click()
        expect(page.locator(".el-message").filter(has_text="已还原")).to_be_visible(timeout=5000)
        expect(drawer.locator('[data-test="recycle-empty"]')).to_be_visible(timeout=5000)
        page.locator('.el-drawer__close-btn, [aria-label="关闭此对话框"]').first.click()
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_project(page, pname)
        expect(page.locator('[data-test="case-table"]')).to_contain_text(case["title"])

        assert_no_errors(collectors, "P1 回收站还原闭环")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_p1_purge_and_archived_projects(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, uniq, "purge用例")

    try:
        api_client.delete(f"/cases/{case['id']}")
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_project(page, pname)
        page.locator('[data-test="case-recycle-open"]').click()
        drawer = page.locator('[data-test="case-recycle-drawer"]')
        expect(drawer).to_be_visible()
        row = drawer.locator('[data-test="recycle-table"] tbody tr.el-table__row').first
        expect(row).to_be_visible(timeout=5000)
        row.locator("button:has-text('彻底删除')").click()
        page.locator('.el-popconfirm:visible:has-text("级联清除") button:has-text("删除")').click()
        expect(page.locator(".el-message").filter(has_text="已彻底删除")).to_be_visible(timeout=5000)
        expect(drawer.locator('[data-test="recycle-empty"]')).to_be_visible(timeout=5000)
        page.keyboard.press("Escape")

        api_client.delete(f"/projects/{pid}")
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "项目管理", "/projects", '[data-test="project-table"]', submenu="系统管理")
        expect(page.locator('[data-test="project-table"]')).not_to_contain_text(pname)

        page.locator('[data-test="project-archived-open"]').click()
        dlg = page.locator('[data-test="archived-dialog"]')
        expect(dlg).to_be_visible()
        arow = dlg.locator(f'[data-test="archived-table"] tbody tr.el-table__row:has-text("{pname}")')
        expect(arow).to_have_count(1, timeout=5000)
        arow.locator(f'[data-test="archived-restore-{pid}"]').click()
        expect(page.locator(".el-message").filter(has_text="已还原")).to_be_visible(timeout=5000)
        page.keyboard.press("Escape")
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "项目管理", "/projects", '[data-test="project-table"]', submenu="系统管理")
        expect(page.locator('[data-test="project-table"]')).to_contain_text(pname)

        assert_no_errors(collectors, "P1 彻底删除+项目归档视图")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
