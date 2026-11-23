
import base64
import io
import uuid

from openpyxl import load_workbook
from playwright.sync_api import expect

from conftest import nav_to, assert_no_errors



def _mk_project(api, uniq):
    name = f"P2项目_{uniq}"
    code = f"P2E{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.json().get("code") == "0", f"创建项目失败: {r.text[:200]}"
    return r.json()["data"], name


def _mk_case(api, pid, uniq, title):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "priority": 1,
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


def _read_xlsx(path):
    wb = load_workbook(str(path))
    ws = wb.active
    return [[("" if c.value is None else str(c.value)) for c in row] for row in ws.iter_rows()]


def test_p2_export_ids_flow(logged_in, api_client, collectors, uniq, tmp_path):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "登录成功")
    c2 = _mk_case(api_client, pid, uniq, "登出正常")
    c3 = _mk_case(api_client, pid, uniq, '=HYPERLINK("http://evil.example")')
    api_client.post(f"/tags", json={"projectId": pid, "name": f"冒烟{uniq}", "type": 1})

    try:
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_project(page, pname)
        table = page.locator('[data-test="case-table"]')
        expect(table).to_contain_text(f"登录成功_{uniq}", timeout=8000)

        for title in (f"登录成功_{uniq}", f"登出正常_{uniq}", "=HYPERLINK"):
            row = table.locator(f"tbody tr.el-table__row:has-text('{title}')").first
            row.locator('td.el-table-column--selection .el-checkbox').click()
        bar = page.locator('[data-test="case-batch-bar"]')
        expect(bar).to_be_visible()

        page.locator('[data-test="case-export-btn"]').click()
        item = page.locator('[data-test="case-export-ids"]')
        expect(item).to_be_visible()
        with page.expect_download(timeout=30000) as dl_info:
            item.click()
        download = dl_info.value
        path = tmp_path / download.suggested_filename
        download.save_as(str(path))
        rows = _read_xlsx(path)
        assert rows[0][:11] == ["测试目标", "前置条件", "预期结果", "优先级", "置信度",
                                "终端类型", "步骤", "用例编号", "状态", "标签", "分配人"], rows[0]
        assert len(rows) == 4, f"数据行={len(rows)-1} 期望 3"
        titles = [r0[0] for r0 in rows[1:]]
        assert any(t.startswith("'=") for t in titles), f"公式注入未转义: {titles}"
        assert any(r0[7].startswith("TC-") and r0[8] == "待评审" for r0 in rows[1:])

        expect(page.locator(".el-message").filter(has_text="已导出 3 条用例")).to_be_visible(timeout=5000)

        r = api_client.get(f"/audit-logs?objectType=case&objectId={pid}&action=export&page=1&size=10")
        body = r.json()
        logs = (body.get("data") or {}).get("list") or []
        assert any(x.get("action") == "export" for x in logs), f"导出审计缺失: {r.text[:200]}"

        assert_no_errors(collectors, "P2 勾选导出")
    finally:
        api_client.put(f"/projects/{pid}/restore")
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_p2_export_filter_and_removed(logged_in, api_client, collectors, uniq, tmp_path):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    _mk_case(api_client, pid, uniq, "筛选保留")
    gone = _mk_case(api_client, pid, uniq, "将被删除")
    api_client.delete(f"/cases/{gone['id']}")

    try:
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_project(page, pname)
        table = page.locator('[data-test="case-table"]')
        expect(table).to_contain_text(f"筛选保留_{uniq}", timeout=8000)

        page.locator('[data-test="case-filter-status"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="待评审").first.click()
        expect(table.locator("tbody tr.el-table__row")).to_have_count(1, timeout=5000)
        page.locator('[data-test="case-export-btn"]').click()
        with page.expect_download(timeout=30000) as dl_info:
            page.locator('[data-test="case-export-filter"]').click()
        download = dl_info.value
        path = tmp_path / download.suggested_filename
        download.save_as(str(path))
        rows = _read_xlsx(path)
        assert len(rows) == 2, f"筛选导出行数={len(rows)-1} 期望 1（已删不出现）"
        assert f"筛选保留_{uniq}" in rows[1][0]
        assert all(gone["title"] not in r0[0] for r0 in rows[1:]), "H1 泄漏：已删用例出现在导出文件"
        expect(page.locator(".el-message").filter(has_text="已导出 1 条用例")).to_be_visible(timeout=5000)

        rl = api_client.get(f"/cases?projectId={pid}&page=1&size=5")
        visible_id = rl.json()["data"]["list"][0]["id"]
        r = api_client.post("/cases/export", json={"projectId": pid,
                                                   "ids": [visible_id, gone["id"], 99999999]})
        d = r.json()["data"]
        assert d["total"] == 1 and d["removed"] == 2, \
            f"剔除计数错误: total={d['total']} removed={d['removed']}"
        raw = base64.b64decode(d["content"])
        assert raw[:2] == b"PK"

        assert_no_errors(collectors, "P2 筛选导出+剔除")
    finally:
        api_client.put(f"/projects/{pid}/restore")
        api_client.delete(f"/projects/{pid}/purge?force=true")
