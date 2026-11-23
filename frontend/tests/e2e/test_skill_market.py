
import uuid

from conftest import (
    BASE_URL,
    assert_no_errors,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect


def _md_text(name: str) -> str:
    return (f"---\nname: {name}\ntype: 1\nstages: [1, 2]\nscope: global\nversion: 1\n---\n"
            f"## 用途\n{name} 的正文（E2E 市场链标记 {uuid.uuid4().hex[:8]}）\n")


def _create_published_skill(api_client, name: str) -> int:
    pass
    r = api_client.post("/skills", {
        "projectId": None, "name": name, "type": 1,
        "body": "## 用途\n审批造数正文\n", "frontmatterJson": None, "submitNow": True,
    })
    body = r.json()
    assert body["code"] == "0", f"创建 SKILL 失败: {body}"
    sid, ver = body["data"]["id"], body["data"]["version"]
    assert body["data"]["status"] == 2, f"submitNow 应直提 pending: {body['data']}"
    ar = api_client.post(f"/skills/{sid}/approve?expectedVersion={ver}").json()
    assert ar["code"] == "0" and ar["data"]["status"] == 3, f"审批失败: {ar}"
    return sid


def _cleanup_skill(api_client, sid: int):
    pass
    cur = api_client.get(f"/skills/{sid}").json()["data"]
    ver = cur["version"]
    rr = api_client.post(f"/skills/{sid}/retire?expectedVersion={ver}").json()
    assert rr["code"] == "0", f"retire 失败: {rr}"
    dr = api_client.delete(f"/skills/{sid}?expectedVersion={rr['data']['version']}").json()
    assert dr["code"] == "0", f"delete 失败: {dr}"



def _open_market_tab(page):
    pass
    nav_to(page, "SKILL 管理", "/skills", '[data-test="skill-page-tabs"]', submenu="AI 能力")
    page.locator('[data-test="skill-tab-market"]').click()
    expect(page.locator('[data-test="skill-market-page"]')).to_be_visible(timeout=10000)


def test_skill_market_page_smoke(logged_in, collectors):
    page = logged_in
    _open_market_tab(page)
    expect(page.locator('[data-test="market-table"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="market-keyword"]')).to_be_visible()
    for col in ("下载量", "引用项目", "30天调用"):
        expect(page.locator('[data-test="market-table"]').get_by_text(col, exact=True).first).to_be_visible()
    assert_no_errors(collectors, "SKILL 市场页冒烟")



def test_skill_market_download_count_and_import(logged_in, collectors, api_client, uniq, tmp_path):
    page = logged_in
    name = f"e2e-skill-{uniq}"
    sid = _create_published_skill(api_client, name)
    imported_name = f"e2e-import-{uniq}"
    imported_id = None
    try:
        _open_market_tab(page)

        page.fill('[data-test="market-keyword"]', name)
        page.click('[data-test="market-search"]')
        row = page.locator('[data-test="market-table"] tbody tr').filter(has_text=name).first
        expect(row).to_be_visible(timeout=8000)
        assert "系统管理员" in row.inner_text(), f"作者回显缺失: {row.inner_text()}"
        assert int(sql_query(
            f"SELECT download_count FROM tp_skill WHERE id={sid}")) == 0, "初始下载量应为 0"

        with page.expect_response(
            lambda r: "/api/v1/skill-market?" in r.url and r.status == 200
        ):
            page.locator('[data-test="market-table"] th', has_text="下载量").first.click()
        expect(row).to_be_visible(timeout=8000)

        with page.expect_response(
            lambda r: f"/api/v1/skill-market/{sid}/download" in r.url and r.status == 200
        ):
            row.locator('[data-test="market-download"]').click()
            page.locator('.el-dropdown-menu__item:visible', has_text=".md 单文件").first.click()
        expect(page.locator(".el-message--success").first).to_contain_text("已下载", timeout=8000)

        page.goto(f"{BASE_URL}/#/skill-market", wait_until="domcontentloaded")
        expect(page.locator('[data-test="market-table"]')).to_be_visible(timeout=10000)
        assert int(sql_query(
            f"SELECT download_count FROM tp_skill WHERE id={sid}")) == 1, "下载计数未持久化（DB）"
        page.fill('[data-test="market-keyword"]', name)
        page.click('[data-test="market-search"]')
        row = page.locator('[data-test="market-table"] tbody tr').filter(has_text=name).first
        expect(row).to_be_visible(timeout=8000)
        assert "1" == row.locator("td").nth(4).inner_text().strip(), \
               f"列表下载量未回显 1: {row.inner_text()}"

        f = tmp_path / f"{imported_name}.md"
        f.write_text(_md_text(imported_name), encoding="utf-8")
        page.click('[data-test="market-import-open"]')
        expect(page.locator('[data-test="file-upload-dialog"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="file-upload-drop"] input[type="file"]').set_input_files(str(f))
        with page.expect_response(
            lambda r: r.request.method == "POST" and "/api/v1/skill-market/import" in r.url
        ):
            page.click('[data-test="file-upload-submit"]')
        expect(page.locator(".el-message--success").first).to_contain_text("导入成功", timeout=8000)

        lr = api_client.get(f"/skills?keyword={imported_name}").json()
        rows = lr["data"]["list"]
        assert rows, f"导入未落库: {lr}"
        imported_id = rows[0]["id"]
        assert rows[0]["status"] == 2, f"导入应自动进审批 pending: {rows[0]}"

        assert_no_errors(collectors, "SKILL 市场下载计数与导入")
    finally:
        _cleanup_skill(api_client, sid)
        if imported_id is not None:
            cur = api_client.get(f"/skills/{imported_id}").json()["data"]
            api_client.delete(f"/skills/{imported_id}?expectedVersion={cur['version']}")
        assert sql_query(
            f"SELECT count(*) FROM tp_skill WHERE deleted=0 AND name IN ('{name}','{imported_name}')") == "0", \
            "测后残留未清"
