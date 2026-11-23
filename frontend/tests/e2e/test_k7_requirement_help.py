
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to, row_cmd


def _mk_project(api, uniq):
    name = f"K7项目_{uniq}"
    code = f"K7{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _select_project(page, project_name):
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()


def test_k7_requirement_help_popover(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)

    try:
        page.reload(wait_until="domcontentloaded")
        # Requirement ledger re-anchored: navigate the standalone
        # /requirements menu leaf (v12 spectrum).
        nav_to(page, "需求点管理", "/requirements", '[data-test="req-page"]', submenu="设计用例")
        _select_project(page, pname)
        page.locator('[data-test="req-create"]').click()
        expect(page.locator('[data-test="req-edit-dialog"]')).to_be_visible()

        help_icon = page.locator('[data-test="req-help-icon"]')
        expect(help_icon).to_be_visible(timeout=5000)

        help_icon.click()
        drawer = page.locator('[data-test="help-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        expect(drawer.locator("h2").first).to_contain_text("生成结构化用例", timeout=8000)

        page.keyboard.press("Escape")
        expect(drawer).not_to_be_visible(timeout=5000)

        page.locator('[data-test="req-title"]').fill(f"K7帮助验证需求_{uniq}")
        page.locator('[data-test="req-submit"]').click()
        expect(page.locator('[data-test="req-table"]')).to_contain_text(f"K7帮助验证需求_{uniq}", timeout=10000)

        page.goto(f"{BASE_URL}/#/requirements", wait_until="domcontentloaded")
        _select_project(page, pname)
        expect(page.locator('[data-test="req-table"]')).to_contain_text(f"K7帮助验证需求_{uniq}", timeout=10000)

        assert_no_errors(collectors, "K7 需求帮助锚跳")
    finally:
        api_client.delete(f"/projects/{pid}")


def test_k7_requirement_help_edit_dialog_and_close(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    title = f"K7编辑帮助_{uniq}"
    r = api_client.post(f"/projects/{pid}/requirements", json={
        "source": 3, "title": title, "chapter": "3.2",
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建需求点失败: {r.text[:300]}"

    try:
        page.reload(wait_until="domcontentloaded")
        # Same posture as the popover test: standalone ledger leaf.
        nav_to(page, "需求点管理", "/requirements", '[data-test="req-page"]', submenu="设计用例")
        _select_project(page, pname)
        expect(page.locator('[data-test="req-table"]')).to_contain_text(title, timeout=10000)

        row_cmd(page, page.locator(f'tr:has-text("{title}")').first, "edit")
        expect(page.locator('[data-test="req-edit-dialog"]')).to_be_visible()
        expect(page.locator('[data-test="req-help-icon"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="req-help-icon"]').click()
        drawer = page.locator('[data-test="help-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        expect(drawer.locator("h2").first).to_contain_text("生成结构化用例", timeout=8000)
        page.keyboard.press("Escape")
        expect(drawer).not_to_be_visible(timeout=5000)

        page.locator('[data-test="req-dialog-close"]').click()
        expect(page.locator('[data-test="req-edit-dialog"]')).not_to_be_visible(timeout=5000)
        assert_no_errors(collectors, "K7 编辑态锚跳 + 关闭按钮")
    finally:
        api_client.delete(f"/projects/{pid}")
