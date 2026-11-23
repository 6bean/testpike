
import re

import pytest
from conftest import BASE_URL, assert_no_errors, nav_to, sql_query
from playwright.sync_api import expect


def _create_project(api_client, uniq):
    pass
    resp = api_client.post("/projects", {"name": f"工具项目{uniq}", "code": f"TOOL{uniq.upper()}"})
    body = resp.json()
    assert body["code"] == "0", f"创建项目失败: {body}"
    return body["data"]


def _create_published_tool(api_client, name):
    pass
    r = api_client.post("/tools", {
        "projectId": None, "name": name, "type": 1, "category": "e2e",
        "description": "U4 启用链造数描述",
        "body": "## 用途\nU4 绑定链造数正文\n", "submitNow": True,
    }).json()
    assert r["code"] == "0", f"创建工具失败: {r}"
    tid, ver = r["data"]["id"], r["data"]["version"]
    ar = api_client.post(f"/tools/{tid}/approve?expectedVersion={ver}").json()
    assert ar["code"] == "0" and ar["data"]["status"] == 3, f"审批失败: {ar}"
    return tid


def _cleanup_tool(api_client, tid):
    pass
    cur = api_client.get(f"/tools/{tid}").json()["data"]
    if cur["status"] == 3:
        rr = api_client.post(f"/tools/{tid}/retire?expectedVersion={cur['version']}").json()
        assert rr["code"] == "0", f"retire 失败: {rr}"
        cur = rr["data"]
    dr = api_client.delete(f"/tools/{tid}?expectedVersion={cur['version']}").json()
    assert dr["code"] == "0", f"delete 失败: {dr}"


def _open_tool_tab(page, pid, reload=False):
    pass
    if reload:
        page.reload(wait_until="domcontentloaded")
    else:
        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    page.wait_for_selector('[data-test="env-table"]', timeout=10000)
    page.click('.el-tabs__item:has-text("工具启用")')
    page.wait_for_selector('[data-test="tool-bind-table"]', timeout=8000)


@pytest.fixture
def project_and_tool(api_client, uniq):
    pid = _create_project(api_client, uniq)
    name = f"e2e-u4-tool-{uniq}"
    tid = _create_published_tool(api_client, name)
    yield pid, tid, name
    cur = api_client.get(f"/projects/{pid}/tools").json()
    if cur["code"] == "0" and cur["data"]["list"]:
        pr = api_client.put(f"/projects/{pid}/tools", json={"tools": [], "expectedVersion": None}).json()
        assert pr["code"] == "0", f"清理解绑失败: {pr}"
    _cleanup_tool(api_client, tid)
    api_client.delete(f"/projects/{pid}")



def test_prj_tool_tab_smoke(logged_in, collectors, project_and_tool):
    page = logged_in
    pid, _tid, _name = project_and_tool
    _open_tool_tab(page, pid)
    expect(page.locator('[data-test="tool-add-select"]')).to_be_visible(timeout=5000)
    expect(page.locator('[data-test="tool-bind-save"]')).to_be_visible()
    expect(page.locator('[data-test="tool-bind-save"]')).to_be_disabled()
    expect(page.locator('[data-test="tool-bind-table"] tbody tr')).to_have_count(0, timeout=5000)
    assert_no_errors(collectors, "T166 U4 页签冒烟")



def test_prj_tool_bind_save_reload_unbind(logged_in, collectors, project_and_tool):
    page = logged_in
    pid, tid, name = project_and_tool
    _open_tool_tab(page, pid)

    page.click('[data-test="tool-add-select"]')
    page.fill('[data-test="tool-add-select"] input', name)
    option = page.locator(".tp-tool-add-popper .el-select-dropdown__item:visible", has_text=name).first
    option.wait_for(timeout=8000)
    option.click()
    row = page.locator('[data-test="tool-bind-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=5000)

    page.click(f'[data-test="tool-enabled-{tid}"]')
    expect(page.locator(f'[data-test="tool-enabled-{tid}"]')).not_to_have_class(
        re.compile(r"is-checked"), timeout=5000)

    with page.expect_response(
        lambda r: r.request.method == "PUT" and f"/api/v1/projects/{pid}/tools" in r.url
    ):
        page.click('[data-test="tool-bind-save"]')
    toast = page.locator(".el-message--success").first
    expect(toast).to_be_visible(timeout=5000)
    assert "已保存" in toast.inner_text()
    expect(page.locator('[data-test="tool-bind-save"]')).to_be_disabled(timeout=5000)

    _open_tool_tab(page, pid, reload=True)
    row = page.locator('[data-test="tool-bind-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=8000)
    expect(page.locator(f'[data-test="tool-enabled-{tid}"]')).not_to_have_class(
        re.compile(r"is-checked"), timeout=5000)
    assert "未保存" not in row.inner_text(), f"启用时间未回显: {row.inner_text()}"

    page.click(f'[data-test="tool-view-{tid}"]')
    drawer = page.locator('[data-test="tool-detail-drawer"]')
    expect(drawer).to_be_visible(timeout=5000)
    body_cell = page.locator('[data-test="tool-detail-body"]').first
    expect(body_cell).to_be_visible(timeout=8000)
    assert "U4 绑定链造数正文" in body_cell.inner_text(), \
        f"详情正文非全量 body: {body_cell.inner_text()[:120]}"
    binding = page.locator('[data-test="tool-detail-binding"]').first
    assert "已停用" in binding.inner_text() or "停用" in binding.inner_text(), \
        f"启用段未回显启停态: {binding.inner_text()}"
    assert "U4 启用链造数描述" in drawer.inner_text(), "描述字段未回显"
    page.keyboard.press("Escape")
    expect(drawer).to_be_hidden(timeout=5000)

    db = sql_query(f"SELECT enabled FROM tp_project_tool WHERE project_id={pid} AND tool_id={tid}")
    assert db == "0", f"DB 落库值与页面编辑不一致: {db}"

    page.click(f'[data-test="tool-unbind-{tid}"]')
    expect(page.locator('[data-test="tool-bind-table"] tbody tr')).to_have_count(0, timeout=5000)
    with page.expect_response(
        lambda r: r.request.method == "PUT" and f"/api/v1/projects/{pid}/tools" in r.url
    ):
        page.click('[data-test="tool-bind-save"]')
    expect(page.locator(".el-message--success").first).to_be_visible(timeout=5000)
    assert sql_query(
        f"SELECT count(*) FROM tp_project_tool WHERE project_id={pid}") == "0", "解绑保存后 DB 应零行"

    assert_no_errors(collectors, "T166 U4 启用保存解绑全链")



def test_tool_invocations_dialog(logged_in, collectors, api_client, uniq):
    page = logged_in
    name = f"e2e-u4-inv-{uniq}"
    tid = _create_published_tool(api_client, name)
    try:
        nav_to(page, "工具管理", "/tools", '[data-test="tool-list-page"]', submenu="AI 能力")
        page.fill('[data-test="tool-keyword"]', name)
        page.click('[data-test="tool-search"]')
        row = page.locator('[data-test="tool-table"] tbody tr').filter(has_text=name).first
        expect(row).to_be_visible(timeout=8000)
        row.locator('[data-test="ra-more"]').click()
        page.locator('[data-test="ra-invocations"]:visible').first.click()
        expect(page.locator('[data-test="tool-inv-dialog"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="tool-inv-table"] tbody tr')).to_have_count(0, timeout=5000)
        page.click('[data-test="tool-inv-query"]')
        expect(page.locator('[data-test="tool-inv-dialog"]')).to_be_visible()
        page.get_by_role("button", name="关闭").last.click()
        assert_no_errors(collectors, "T166 U4 调用历史弹窗")
    finally:
        _cleanup_tool(api_client, tid)
