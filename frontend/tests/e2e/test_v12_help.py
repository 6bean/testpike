
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to


def _open_drawer_via_topbar(page):
    # R23 stage 1: the "?" entry now sits right of the header title and opens
    # the drawer anchored to the current page's help domain.
    page.locator('[data-test="header-help-btn"]').first.click()
    drawer = page.locator('[data-test="help-drawer"]')
    expect(drawer).to_be_visible(timeout=8000)
    return drawer


def _search(drawer, page, keyword):
    pass
    inp = drawer.locator('[data-test="help-search"] input')
    inp.fill(keyword)


def test_v12_help_drawer_three_topics(logged_in, collectors):
    pass
    page = logged_in
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")

    try:
        drawer = _open_drawer_via_topbar(page)

        expect(drawer.locator('[data-test="help-doc-item"]').first).to_be_visible(timeout=8000)

        _search(drawer, page, "SKILL 知识规范")
        hits = drawer.locator('[data-test="help-doc-item"]')
        expect(hits).to_have_count(1, timeout=5000)
        expect(hits.first).to_contain_text("SKILL 知识规范如何生效")

        _search(drawer, page, "工具的登记")
        expect(hits.first).to_contain_text("工具的登记与模型自动调用", timeout=5000)

        _search(drawer, page, "知识库如何注入")
        expect(hits.first).to_contain_text("知识库如何注入生成", timeout=5000)

        hits.first.click()
        expect(drawer.locator("h2").first).to_contain_text("知识库如何注入生成", timeout=8000)
        expect(drawer.get_by_text("向量索引", exact=False).first).to_be_visible(timeout=8000)

        _search(drawer, page, "zzz不存在的主题zzz")
        expect(drawer.locator('[data-test="help-empty"]')).to_be_visible(timeout=5000)

        assert_no_errors(collectors, "V12 帮助中心三主题搜索")
    finally:
        page.keyboard.press("Escape")


def test_v12_anchor_jump_from_requirement(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    name = f"V12锚跳项目_{uniq}"
    r = api_client.post("/projects", json={"name": name, "code": f"V12{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    pid = r.json()["data"]

    try:
        page.reload(wait_until="domcontentloaded")
        # Requirement ledger re-anchored: it is a standalone /requirements
        # page again, so navigate the menu leaf directly.
        nav_to(page, "需求点管理", "/requirements", '[data-test="req-page"]', submenu="设计用例")
        expect(page.locator('[data-test="project-ctx-select"]')).to_be_visible(timeout=8000)
        page.locator('[data-test="project-ctx-select"]').click()
        option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=name).first
        expect(option).to_be_visible(timeout=5000)
        option.click()

        page.locator('[data-test="req-create"]').click()
        expect(page.locator('[data-test="req-edit-dialog"]')).to_be_visible()

        page.locator('[data-test="req-help-icon"]').click()
        drawer = page.locator('[data-test="help-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)

        expect(drawer.locator("h2").first).to_contain_text("从需求一键生成结构化用例", timeout=8000)

        page.keyboard.press("Escape")
        expect(drawer).not_to_be_visible(timeout=5000)
        page.locator('[data-test="req-title"]').fill(f"V12锚跳回归_{uniq}")
        page.locator('[data-test="req-submit"]').click()
        expect(page.locator('[data-test="req-table"]')).to_contain_text(f"V12锚跳回归_{uniq}", timeout=10000)

        # Persistence after reload (real create path hits the DB; the retired
        # old extraction-tab bookmark must hop to the standalone ledger page
        # with the stale tab key dropped).
        page.goto(f"{BASE_URL}/#/extraction?tab=requirements", wait_until="domcontentloaded")
        expect(page.locator('[data-test="req-page"]')).to_be_visible(timeout=10000)
        assert "/#/requirements" in page.url and "tab=" not in page.url, \
            f"old bookmark must hop to the ledger without the stale tab key: {page.url}"
        assert_no_errors(collectors, "V12 需求页锚跳直达")
    finally:
        api_client.delete(f"/projects/{pid}")
