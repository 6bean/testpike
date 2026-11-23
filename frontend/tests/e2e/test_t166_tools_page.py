
import io
import json
import uuid
import zipfile

from conftest import (
    BASE_URL,
    assert_no_errors,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect


def _open_tool_market_tab(page):
    pass
    nav_to(page, "工具管理", "/tools", '[data-test="tool-page-tabs"]', submenu="AI 能力")
    page.locator('[data-test="tool-tab-market"]').click()
    expect(page.locator('[data-test="tool-market-page"]')).to_be_visible(timeout=10000)


def _tool_contract(name: str) -> dict:
    return {
        "name": name,
        "type": 1,
        "category": "e2e",
        "description": f"E2E 导入契约 {uuid.uuid4().hex[:8]}",
        "body": f"## 用途\n{name} 的文档正文（市场导入链）\n",
    }


def _create_draft_tool(api_client, name: str) -> int:
    pass
    r = api_client.post("/tools", {
        "projectId": None, "name": name, "type": 1, "category": "e2e",
        "description": "审批链造数描述",
        "body": "## 用途\n审批造数正文\n", "submitNow": False,
    })
    body = r.json()
    assert body["code"] == "0", f"创建工具失败: {body}"
    assert body["data"]["status"] == 1, f"应为草稿: {body['data']}"
    return body["data"]["id"]


def _cleanup_tool(api_client, tid: int):
    pass
    cur = api_client.get(f"/tools/{tid}").json()["data"]
    if cur["status"] == 3:
        rr = api_client.post(f"/tools/{tid}/retire?expectedVersion={cur['version']}").json()
        assert rr["code"] == "0", f"retire 失败: {rr}"
        cur = rr["data"]
    dr = api_client.delete(f"/tools/{tid}?expectedVersion={cur['version']}").json()
    assert dr["code"] == "0", f"delete 失败: {dr}"



def test_tool_pages_smoke(logged_in, collectors):
    page = logged_in
    nav_to(page, "工具管理", "/tools", '[data-test="tool-list-page"]', submenu="AI 能力")
    expect(page.locator('[data-test="tool-table"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="tool-keyword"]')).to_be_visible()
    expect(page.locator('[data-test="tool-create-btn"]')).to_be_visible()
    _open_tool_market_tab(page)
    expect(page.locator('[data-test="tool-market-table"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="tool-market-keyword"]')).to_be_visible()
    for col in ("下载量", "引用项目", "30天调用"):
        expect(page.locator('[data-test="tool-market-table"]').get_by_text(col, exact=True).first).to_be_visible()
    assert_no_errors(collectors, "工具管理/市场页冒烟")



def test_tool_crud_approval_market_roundtrip(logged_in, collectors, api_client, uniq, tmp_path):
    page = logged_in
    name = f"e2e-tool-{uniq}"
    imported_name = f"e2e-import-{uniq}"
    tid = _create_draft_tool(api_client, name)
    imported_id = None
    try:
        nav_to(page, "工具管理", "/tools", '[data-test="tool-list-page"]', submenu="AI 能力")
        page.fill('[data-test="tool-keyword"]', name)
        page.click('[data-test="tool-search"]')
        row = page.locator('[data-test="tool-table"] tbody tr').filter(has_text=name).first
        expect(row).to_be_visible(timeout=8000)
        assert "草稿" in row.inner_text(), f"状态列应回显草稿: {row.inner_text()}"

        page.click('[data-test="tool-create-btn"]')
        expect(page.locator('[data-test="tool-form-dialog"]')).to_be_visible(timeout=5000)
        page.click('[data-test="tool-form-submit"]')
        expect(page.locator(".el-form-item__error").first).to_contain_text("请输入工具名称", timeout=3000)
        page.fill('[data-test="tool-form-name"]', f"{name}-web")
        page.locator('[data-test="tool-form-body"]').fill("## 用途\n浏览器建工具正文\n")
        page.click('[data-test="tool-form-submit-now"]')
        with page.expect_response(
            lambda r: r.request.method == "POST" and r.url.startswith(f"{BASE_URL}/api/v1/tools")
            and "/tool-market" not in r.url and r.status == 200
        ):
            page.click('[data-test="tool-form-submit"]')
        expect(page.locator(".el-message--success").first).to_contain_text("已创建并提交审批", timeout=8000)

        page.goto(f"{BASE_URL}/#/tools", wait_until="domcontentloaded")
        expect(page.locator('[data-test="tool-list-page"]')).to_be_visible(timeout=10000)
        page.fill('[data-test="tool-keyword"]', f"{name}-web")
        page.click('[data-test="tool-search"]')
        web_row = page.locator('[data-test="tool-table"] tbody tr').filter(has_text=f"{name}-web").first
        expect(web_row).to_be_visible(timeout=8000)
        assert "待审批" in web_row.inner_text(), f"reload 后状态未持久化: {web_row.inner_text()}"
        web_id = int(sql_query(
            f"SELECT id FROM tp_tool WHERE name='{name}-web'"))
        _cleanup_tool(api_client, web_id)

        page.goto(f"{BASE_URL}/#/tools", wait_until="domcontentloaded")
        expect(page.locator('[data-test="tool-list-page"]')).to_be_visible(timeout=10000)
        page.fill('[data-test="tool-keyword"]', name)
        page.click('[data-test="tool-search"]')
        row = page.locator('[data-test="tool-table"] tbody tr').filter(has_text=name).first
        expect(row).to_be_visible(timeout=8000)
        row.locator('[data-test="ra-more"]').click()
        page.locator('.el-dropdown-menu__item:visible', has_text="编辑").first.click()
        expect(page.locator('[data-test="tool-form-dialog"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="tool-form-name"]')).to_have_value(name)
        page.locator('[data-test="tool-form-desc"]').fill("编辑后的描述-持久化验证")
        page.click('[data-test="tool-form-submit"]')
        expect(page.locator(".el-message--success").first).to_contain_text("已保存", timeout=8000)
        cur = api_client.get(f"/tools/{tid}").json()["data"]
        assert cur["description"] == "编辑后的描述-持久化验证", f"编辑未落库: {cur}"

        row.locator('[data-test="ra-more"]').click()
        page.locator('.el-dropdown-menu__item:visible', has_text="提交审批").first.click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=5000)
        with page.expect_response(
            lambda r: r.request.method == "POST" and f"/api/v1/tools/{tid}/submit" in r.url
        ):
            box.locator('button:has-text("确定")').click()
        expect(row.locator('[data-test="tool-status-tag"]')).to_contain_text("待审批", timeout=8000)
        cur = api_client.get(f"/tools/{tid}").json()["data"]
        assert cur["status"] == 2, f"提审后应 pending: {cur}"
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="tool-list-page"]')).to_be_visible(timeout=10000)
        page.fill('[data-test="tool-keyword"]', name)
        page.click('[data-test="tool-search"]')
        row = page.locator('[data-test="tool-table"] tbody tr').filter(has_text=name).first
        expect(row).to_be_visible(timeout=8000)
        row.locator('[data-test="ra-more"]').click()
        page.locator('.el-dropdown-menu__item:visible', has_text="通过").first.click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=5000)
        with page.expect_response(
            lambda r: r.request.method == "POST" and f"/api/v1/tools/{tid}/approve" in r.url
        ):
            box.locator('button:has-text("确定")').click()
        expect(row.locator('[data-test="tool-status-tag"]')).to_contain_text("已发布", timeout=8000)
        cur = api_client.get(f"/tools/{tid}").json()["data"]
        assert cur["status"] == 3, f"审批后应 published: {cur}"

        _open_tool_market_tab(page)
        page.fill('[data-test="tool-market-keyword"]', name)
        page.click('[data-test="tool-market-search"]')
        mrow = page.locator('[data-test="tool-market-table"] tbody tr').filter(has_text=name).first
        expect(mrow).to_be_visible(timeout=8000)
        assert "系统管理员" in mrow.inner_text(), f"作者回显缺失: {mrow.inner_text()}"
        assert int(sql_query(
            f"SELECT download_count FROM tp_tool WHERE id={tid}")) == 0, "初始下载量应为 0"

        with page.expect_response(
            lambda r: "/api/v1/tool-market?" in r.url and r.status == 200
        ):
            page.locator('[data-test="tool-market-table"] th', has_text="下载量").first.click()
        expect(mrow).to_be_visible(timeout=8000)

        with page.expect_response(
            lambda r: f"/api/v1/tool-market/{tid}/download" in r.url and r.status == 200
        ):
            mrow.locator('[data-test="tool-market-download"]').click()
            page.locator('.el-dropdown-menu__item:visible', has_text=".md 文档正文").first.click()
        expect(page.locator(".el-message--success").first).to_contain_text("已下载", timeout=8000)

        page.goto(f"{BASE_URL}/#/tool-market", wait_until="domcontentloaded")
        expect(page.locator('[data-test="tool-market-table"]')).to_be_visible(timeout=10000)
        assert int(sql_query(
            f"SELECT download_count FROM tp_tool WHERE id={tid}")) == 1, "下载计数未持久化（DB）"
        page.fill('[data-test="tool-market-keyword"]', name)
        page.click('[data-test="tool-market-search"]')
        mrow = page.locator('[data-test="tool-market-table"] tbody tr').filter(has_text=name).first
        expect(mrow).to_be_visible(timeout=8000)
        assert mrow.locator("td").nth(5).inner_text().strip() == "1", \
               f"列表下载量未回显 1: {mrow.inner_text()}"

        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("tool.json", json.dumps(_tool_contract(imported_name), ensure_ascii=False))
        f = tmp_path / f"{imported_name}.zip"
        f.write_bytes(buf.getvalue())
        page.click('[data-test="tool-market-import-open"]')
        expect(page.locator('[data-test="file-upload-dialog"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="file-upload-drop"] input[type="file"]').set_input_files(str(f))
        with page.expect_response(
            lambda r: r.request.method == "POST" and "/api/v1/tool-market/import" in r.url
        ):
            page.click('[data-test="file-upload-submit"]')
        expect(page.locator(".el-message--success").first).to_contain_text("导入成功", timeout=8000)

        lr = api_client.get(f"/tools?keyword={imported_name}").json()
        rows = lr["data"]["list"]
        assert rows, f"导入未落库: {lr}"
        imported_id = rows[0]["id"]
        assert rows[0]["status"] == 2, f"导入应自动进审批 pending: {rows[0]}"

        assert_no_errors(collectors, "工具 CRUD/审批/市场下载导入往返")
    finally:
        _cleanup_tool(api_client, tid)
        if imported_id is not None:
            cur = api_client.get(f"/tools/{imported_id}").json()["data"]
            api_client.delete(f"/tools/{imported_id}?expectedVersion={cur['version']}")
        assert sql_query(
            f"SELECT count(*) FROM tp_tool WHERE name IN ('{name}','{name}-web','{imported_name}')") == "0", \
            "测后残留未清"
