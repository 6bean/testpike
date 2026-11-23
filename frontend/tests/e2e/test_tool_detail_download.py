
import re
import uuid

from conftest import BASE_URL, assert_no_errors, nav_to, row_cmd, sql_query
from playwright.sync_api import expect
from test_t166_tools_page import _cleanup_tool, _create_draft_tool

MARK_NAME = "e2e-t219m-"


def _post_ver(api_client, action: str, tid: int, ver: int) -> dict:
    pass
    r = api_client.post(f"/tools/{tid}/{action}?expectedVersion={ver}")
    body = r.json()
    assert body["code"] == "0", f"{action} 失败: {body}"
    return body["data"]


def _search_row(page, name: str):
    page.fill('[data-test="tool-keyword"]', name)
    page.click('[data-test="tool-search"]')
    row = page.locator('[data-test="tool-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=8000)
    return row



def test_tool_detail_drawer_roundtrip(logged_in, collectors, api_client, uniq):
    page = logged_in
    name = f"{MARK_NAME}{uniq}"
    tid = _create_draft_tool(api_client, name)
    try:
        nav_to(page, "工具管理", "/tools", '[data-test="tool-list-page"]', submenu="AI 能力")
        row = _search_row(page, name)
        row_cmd(page, row, "detail")
        drawer = page.locator('[data-test="tool-detail-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="tool-detail-desc"]')).to_contain_text(name, timeout=8000)
        expect(page.locator('[data-test="tool-detail-desc"]')).to_contain_text("系统级")
        expect(page.locator('[data-test="tool-detail-body"] h2').first).to_contain_text("用途", timeout=8000)
        page.locator('[data-test="tool-detail-tabs"] .el-tabs__item', has_text="版本历史").first.click()
        expect(page.locator('[data-test="tool-version-table"]')).to_be_visible(timeout=5000)
        expect(drawer.get_by_text("暂无版本记录")).to_be_visible(timeout=5000)
        page.keyboard.press("Escape")
        expect(drawer).to_be_hidden(timeout=5000)
        row_cmd(page, row, "detail")
        expect(drawer).to_be_visible(timeout=8000)
        page.keyboard.press("Escape")
        expect(drawer).to_be_hidden(timeout=5000)
        page.goto(f"{BASE_URL}/#/tools", wait_until="domcontentloaded")
        expect(page.locator('[data-test="tool-list-page"]')).to_be_visible(timeout=10000)
        row = _search_row(page, name)
        row_cmd(page, row, "detail")
        expect(drawer).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="tool-detail-body"] h2').first).to_contain_text("用途", timeout=8000)
        assert_no_errors(collectors, "工具详情抽屉往返")
    finally:
        _cleanup_tool(api_client, tid)



def test_tool_edit_reecho_save(logged_in, collectors, api_client, uniq):
    page = logged_in
    name = f"{MARK_NAME}{uniq}"
    long_body = "## 用途\n" + "\n".join(f"第{i}行说明内容-{i}" for i in range(1, 41))
    tid = api_client.post("/tools", {
        "projectId": None, "name": name, "type": 1, "category": "e2e",
        "description": "编辑回显造数", "body": long_body, "submitNow": False,
    }).json()["data"]["id"]
    try:
        nav_to(page, "工具管理", "/tools", '[data-test="tool-list-page"]', submenu="AI 能力")
        row = _search_row(page, name)
        row_cmd(page, row, "edit")
        expect(page.locator('[data-test="tool-form-dialog"]')).to_be_visible(timeout=5000)
        echoed = page.locator('[data-test="tool-form-body"]').input_value()
        assert echoed == long_body, f"编辑回显非 detail 全量: head={echoed[:40]!r} len={len(echoed)}"
        new_body = long_body + "\n## 追加\n编辑后新增段落"
        page.locator('[data-test="tool-form-body"]').fill(new_body)
        with page.expect_response(
            lambda r: r.request.method == "PUT" and f"/api/v1/tools/{tid}" in r.url and r.status == 200
        ):
            page.click('[data-test="tool-form-submit"]')
        expect(page.locator(".el-message--success").first).to_contain_text("已保存", timeout=8000)
        cur = api_client.get(f"/tools/{tid}/detail").json()["data"]
        assert cur["body"] == new_body, "编辑未落库（detail 全量比对失败）"
        page.goto(f"{BASE_URL}/#/tools", wait_until="domcontentloaded")
        expect(page.locator('[data-test="tool-list-page"]')).to_be_visible(timeout=10000)
        row = _search_row(page, name)
        row_cmd(page, row, "edit")
        expect(page.locator('[data-test="tool-form-dialog"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="tool-form-body"]')).to_have_value(new_body, timeout=5000)
        page.keyboard.press("Escape")
        assert_no_errors(collectors, "工具编辑真回显+保存")
    finally:
        _cleanup_tool(api_client, tid)



def test_tool_download_content_disposition(logged_in, collectors, api_client, uniq):
    page = logged_in
    name = f"{MARK_NAME}{uniq}"
    tid = _create_draft_tool(api_client, name)
    try:
        v0 = api_client.get(f"/tools/{tid}").json()["data"]["version"]
        cur = _post_ver(api_client, "submit", tid, v0)            # submit → pending
        cur = _post_ver(api_client, "approve", tid, cur["version"])  # approve → published v1
        nav_to(page, "工具管理", "/tools", '[data-test="tool-list-page"]', submenu="AI 能力")
        row = _search_row(page, name)
        slug = name.lower()
        with page.expect_response(
            lambda r: "download" in r.url and r.request.method == "GET"
        ) as dl:
            row_cmd(page, row, "download")
        resp = dl.value
        assert resp.status == 200, f"下载应 200: {resp.status}"
        cd = resp.headers.get("content-disposition", "")
        assert re.search(r"filename\*=UTF-8''" + re.escape(slug) + r"-v1\.md", cd), \
            f"Content-Disposition 非 slug 命名规范: {cd!r}"
        row_cmd(page, row, "detail")
        drawer = page.locator('[data-test="tool-detail-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        with page.expect_response(
            lambda r: "download" in r.url and r.request.method == "GET"
        ) as dl2:
            page.locator('[data-test="tool-detail-download"]').click()
        assert dl2.value.status == 200
        cnt = sql_query("SELECT count(*) FROM tp_audit_log WHERE object_id='"
                        f"{tid}' AND action='tool-download'")
        assert int(cnt) == 2, f"下载审计应为 2 笔: got {cnt}"
        assert_no_errors(collectors, "工具下载文件名+审计")
    finally:
        _cleanup_tool(api_client, tid)



def test_tool_version_history_rollback_chain(logged_in, collectors, api_client, uniq):
    page = logged_in
    name = f"{MARK_NAME}{uniq}"
    tid = _create_draft_tool(api_client, name)
    try:
        v0 = api_client.get(f"/tools/{tid}").json()["data"]["version"]
        cur = _post_ver(api_client, "submit", tid, v0)
        cur = _post_ver(api_client, "approve", tid, cur["version"])  # published cv=1
        for k in (2, 3):
            r = api_client.put(f"/tools/{tid}", {"expectedVersion": cur["version"],
                                                 "body": f"## 用途V{k}\n第{k}次改版正文\n"})
            cur = r.json()["data"]
            assert cur["status"] == 2, f"编辑{k}后应 pending: {cur['status']}"
            cur = _post_ver(api_client, "approve", tid, cur["version"])
        assert cur["currentVersion"] == 3, f"改 2 次后 cv 应为 3: {cur['currentVersion']}"

        nav_to(page, "工具管理", "/tools", '[data-test="tool-list-page"]', submenu="AI 能力")
        row = _search_row(page, name)
        row_cmd(page, row, "detail")
        expect(page.locator('[data-test="tool-detail-drawer"]')).to_be_visible(timeout=8000)
        page.locator('[data-test="tool-detail-tabs"] .el-tabs__item', has_text="版本历史").first.click()
        ver_table = page.locator('[data-test="tool-version-table"]')
        expect(ver_table).to_be_visible(timeout=5000)
        rows = ver_table.locator("tbody tr")
        expect(rows).to_have_count(2, timeout=8000)
        expect(rows.nth(0)).to_contain_text("v2")
        expect(rows.nth(0)).to_contain_text("编辑改版")
        expect(rows.nth(1)).to_contain_text("v1")
        page.locator('[data-test="tool-ver-view-1"]').click()
        expect(page.locator('[data-test="tool-version-body"]')).to_contain_text("审批造数正文", timeout=5000)
        with page.expect_response(
            lambda r: r.request.method == "POST" and f"/api/v1/tools/{tid}/rollback" in r.url
        ):
            page.locator('[data-test="tool-ver-rollback-1"]').click()
            box = page.locator(".el-message-box")
            expect(box).to_be_visible(timeout=5000)
            box.locator('button:has-text("确认回滚")').click()
        expect(page.locator(".el-message--success").first).to_contain_text("已回滚到 v1", timeout=8000)
        page.locator('[data-test="tool-detail-tabs"] .el-tabs__item', has_text="概览").first.click()
        expect(page.locator('[data-test="tool-detail-desc"]')).to_contain_text("待审批", timeout=8000)
        expect(page.locator('[data-test="tool-detail-desc"]')).to_contain_text("v4")
        expect(page.locator('[data-test="tool-detail-body"]').locator("h2, p").first).to_be_visible(timeout=5000)
        page.locator('[data-test="tool-detail-tabs"] .el-tabs__item', has_text="版本历史").first.click()
        expect(ver_table.locator("tbody tr")).to_have_count(3, timeout=8000)
        expect(ver_table.locator("tbody tr").nth(0)).to_contain_text("回滚前存档")
        agg = sql_query("SELECT string_agg(version||':'||reason, ',' ORDER BY version) "
                        f"FROM tp_tool_version WHERE tool_id={tid}")
        assert agg == "1:edit,2:edit,3:rollback-pre", f"版本链不符: {agg}"
        cur = api_client.get(f"/tools/{tid}").json()["data"]
        _post_ver(api_client, "approve", tid, cur["version"])
        assert_no_errors(collectors, "工具版本链回滚全链")
    finally:
        _cleanup_tool(api_client, tid)
