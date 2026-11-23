
import re

from conftest import BASE_URL, assert_no_errors, nav_to, sql_query
from playwright.sync_api import expect

BUILTIN_PLUGIN = "pos-retail"


def _create_project(api_client, uniq):
    pass
    resp = api_client.post("/projects", {"name": f"插件项目{uniq}", "code": f"PLG{uniq.upper()}"})
    body = resp.json()
    assert body["code"] == "0", f"创建项目失败: {body}"
    return body["data"]


def _open_plugin_tab(page, pid, reload=False):
    pass
    if reload:
        page.reload(wait_until="domcontentloaded")
    else:
        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    page.wait_for_selector('[data-test="env-table"]', timeout=10000)
    page.click('.el-tabs__item:has-text("插件装配")')
    page.wait_for_selector('[data-test="plugin-applied-table"]', timeout=8000)



def test_plugin_list_page_smoke(logged_in, collectors):
    page = logged_in
    # AN-5: the standalone plugin-list leaf merged into /extensions as
    # the plugins tab — the menu anchor moved, the page contract did not.
    nav_to(page, "扩展集成", "/extensions", '[data-test="extensions-tabs"]', submenu="AI 能力")
    page.click('[data-test="extensions-tab-plugins"]')
    page.wait_for_selector('[data-test="plugin-list-page"]', timeout=10000)
    table = page.locator('[data-test="plugin-list-table"]')
    expect(table).to_be_visible(timeout=10000)
    # Seed assertion (rows=0 not a regression = pit ⑥: built-ins always exist).
    # Worst-scale paging renders one page (20) at a time, so the visible count
    # is the catalog size capped at a page — both facts asserted, no whitelist.
    catalog = int(sql_query("SELECT count(*) FROM tp_plugin"))
    expect(table.locator("tbody tr")).to_have_count(min(catalog, 20), timeout=8000)
    row = table.locator("tbody tr").filter(has_text=BUILTIN_PLUGIN).first
    expect(row).to_be_visible()
    assert "内置样例" in row.inner_text(), f"内置样例标记缺失: {row.inner_text()}"
    expect(page.locator('[data-test="plugin-industry"]')).to_be_visible()
    expect(page.locator('[data-test="plugin-enabled-select"]')).to_be_visible()
    expect(page.locator('[data-test="plugin-search"]')).to_be_visible()

    page.fill('[data-test="plugin-industry"]', "POS")
    page.click('[data-test="plugin-search"]')
    expect(table.locator("tbody tr").filter(has_text="erp-supplychain")).to_have_count(0, timeout=5000)
    expect(row).to_be_visible()
    page.fill('[data-test="plugin-industry"]', "")
    page.click('[data-test="plugin-search"]')
    expect(table.locator("tbody tr").filter(has_text="erp-supplychain").first).to_be_visible(timeout=5000)

    page.click('[data-test="plugin-upload-open"]')
    dialog = page.locator('[data-test="file-upload-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    expect(dialog.locator('[data-test="file-upload-submit"]')).to_be_visible()
    page.keyboard.press("Escape")
    expect(dialog).not_to_be_visible(timeout=5000)
    assert_no_errors(collectors, "T212 插件清单页冒烟")



def test_plugin_apply_uninstall_roundtrip(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid = _create_project(api_client, uniq)
    try:
        _open_plugin_tab(page, pid)
        expect(page.locator('[data-test="plugin-applied-table"] tbody tr')).to_have_count(0, timeout=5000)
        expect(page.locator('[data-test="plugin-apply-open"]')).to_be_visible()
        expect(page.get_by_text("本项目尚未装配插件").first).to_be_visible()

        page.click('[data-test="plugin-apply-open"]')
        dialog = page.locator('[data-test="plugin-apply-dialog"]')
        expect(dialog).to_be_visible(timeout=5000)
        page.click('[data-test="plugin-apply-submit"]')
        expect(page.locator(".el-message--warning").first).to_contain_text("请先选择要装配的插件", timeout=3000)
        page.click('[data-test="plugin-apply-select"]')
        option = page.locator(".tp-plugin-apply-popper .el-select-dropdown__item:visible",
                              has_text=BUILTIN_PLUGIN).first
        option.wait_for(timeout=8000)
        option.click()
        page.click('[data-test="plugin-apply-confirm"]')
        with page.expect_response(
            lambda r: r.request.method == "POST"
            and r.url.startswith(f"{BASE_URL}/api/v1/projects/{pid}/plugins/")
            and r.url.endswith("/apply") and r.status == 200
        ):
            page.click('[data-test="plugin-apply-submit"]')
        expect(page.locator(".el-message--success").first).to_contain_text("装配完成", timeout=8000)

        applied = page.locator('[data-test="plugin-applied-table"]')
        row = applied.locator("tbody tr").filter(has_text=BUILTIN_PLUGIN).first
        expect(row).to_be_visible(timeout=5000)
        assert "启用中" in row.inner_text(), f"状态列未回显: {row.inner_text()}"
        assert re.search(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}", row.inner_text()), \
            f"装配时间未回显: {row.inner_text()}"

        _open_plugin_tab(page, pid, reload=True)
        expect(page.locator('[data-test="plugin-applied-table"] tbody tr')
               .filter(has_text=BUILTIN_PLUGIN).first).to_be_visible(timeout=8000)

        db = sql_query(f"SELECT count(*) FROM tp_project_plugin WHERE project_id={pid}")
        assert db == "1", f"装配落库应为 1 行: {db}"

        plugin_id = sql_query(f"SELECT plugin_id FROM tp_project_plugin WHERE project_id={pid}")
        page.click(f'[data-test="plugin-uninstall-{plugin_id}"]')
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=5000)
        assert "仅摘除装配记录" in box.inner_text(), "卸载确认文案应人话说明不动已生成资产"
        page.locator('.el-message-box__btns button:has-text("卸载")').click()
        expect(page.locator(".el-message--success").first).to_contain_text("已卸载", timeout=8000)
        expect(page.locator('[data-test="plugin-applied-table"] tbody tr')).to_have_count(0, timeout=5000)
        assert sql_query(
            f"SELECT count(*) FROM tp_project_plugin WHERE project_id={pid}") == "0", "卸载后 DB 应零行"

        assert_no_errors(collectors, "T212 装配卸载全往返")
    finally:
        pid_rows = sql_query(f"SELECT plugin_id FROM tp_project_plugin WHERE project_id={pid}")
        for plid in re.findall(r"\d+", pid_rows or ""):
            rr = api_client.delete(f"/projects/{pid}/plugins/{plid}").json()
            assert rr["code"] == "0", f"清理卸载失败: {rr}"
        api_client.delete(f"/projects/{pid}")
