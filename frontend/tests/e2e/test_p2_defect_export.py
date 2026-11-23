
import base64

from openpyxl import load_workbook
from playwright.sync_api import expect

from conftest import assert_no_errors, nav_to, pick_project_in_header


DEFECT_MENU = "缺陷库"


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"P2D项目_{uniq}", "code": f"P2D{uniq[:6].upper()}"})
    assert r.json().get("code") == "0", f"创建项目失败: {r.text[:200]}"
    return r.json()["data"], f"P2D项目_{uniq}"


def _mk_case(api, pid, uniq, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "无", "expectedResult": "符合预期",
        "priority": 1, "confidence": 2, "endType": "web",
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 0}],
    })
    assert r.json().get("code") == "0", f"创建用例失败: {r.text[:200]}"
    return r.json()["data"]


def _mk_defect(api, pid, uniq, title, severity, case_id=None):
    r = api.post("/defect-library", json={
        "projectId": pid, "caseId": case_id, "title": title,
        "description": f"复现描述_{uniq}", "severity": severity,
    })
    assert r.json().get("code") == "0", f"创建缺陷失败: {r.text[:200]}"
    return r.json()["data"]


def _select_project(page, project_name):
    # Assertion fix : the defect page carries no per-page project
    # selector since the IA pass — switch the global header context instead.
    pick_project_in_header(page, project_name)


def _read_xlsx(path):
    wb = load_workbook(str(path))
    ws = wb.active
    return [[("" if c.value is None else str(c.value)) for c in row] for row in ws.iter_rows()]


def test_p2_defect_export_ids_flow(logged_in, api_client, collectors, uniq, tmp_path):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, uniq, f"被关联用例_{uniq}")
    _mk_defect(api_client, pid, uniq, f"登录崩溃_{uniq}", 1, case["id"])
    _mk_defect(api_client, pid, uniq, f"登出白屏_{uniq}", 3)
    _mk_defect(api_client, pid, uniq, '=HYPERLINK("http://evil.example")', 4)

    try:
        page.reload(wait_until="domcontentloaded")
        nav_to(page, DEFECT_MENU, "/defects", '[data-test="defect-list-page"]', submenu="结果与缺陷")
        _select_project(page, pname)
        table = page.locator('[data-test="defect-table"]')
        expect(table).to_contain_text(f"登录崩溃_{uniq}", timeout=8000)

        for title in (f"登录崩溃_{uniq}", f"登出白屏_{uniq}", "=HYPERLINK"):
            row = table.locator(f"tbody tr.el-table__row:has-text('{title}')").first
            row.locator("td.el-table-column--selection .el-checkbox").click()

        page.locator('[data-test="defect-export-btn"]').click()
        item = page.locator('[data-test="defect-export-ids"]')
        expect(item).to_be_visible()
        with page.expect_download(timeout=30000) as dl_info:
            item.click()
        download = dl_info.value
        path = tmp_path / download.suggested_filename
        download.save_as(str(path))
        rows = _read_xlsx(path)
        assert rows[0][:5] == ["缺陷编号", "标题", "严重程度", "状态", "关联用例编号"], rows[0]
        assert len(rows) == 4, f"数据行={len(rows)-1} 期望 3"
        titles = [r0[1] for r0 in rows[1:]]
        assert any(t.startswith("'=") for t in titles), f"公式注入未转义: {titles}"
        crash = next(r0 for r0 in rows[1:] if f"登录崩溃_{uniq}" in r0[1])
        assert crash[2] == "P0" and crash[3] == "新建", f"中文 label 列错误: {crash}"
        assert crash[4] == case["caseNo"], f"关联用例编号列错误: {crash[4]} != {case['caseNo']}"

        expect(page.locator(".el-message").filter(has_text="已导出 3 条缺陷")).to_be_visible(timeout=5000)

        r = api_client.get(f"/audit-logs?objectType=defect&objectId={pid}&action=export&page=1&size=10")
        logs = (r.json().get("data") or {}).get("list") or []
        assert any(x.get("action") == "export" for x in logs), f"导出审计缺失: {r.text[:200]}"

        assert_no_errors(collectors, "P2 缺陷勾选导出")
    finally:
        api_client.put(f"/projects/{pid}/restore")
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_p2_defect_export_filter_and_removed(logged_in, api_client, collectors, uniq, tmp_path):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    _mk_defect(api_client, pid, uniq, f"严重缺陷A_{uniq}", 1)
    _mk_defect(api_client, pid, uniq, f"严重缺陷B_{uniq}", 1)
    _mk_defect(api_client, pid, uniq, f"轻微缺陷C_{uniq}", 4)

    try:
        page.reload(wait_until="domcontentloaded")
        nav_to(page, DEFECT_MENU, "/defects", '[data-test="defect-list-page"]', submenu="结果与缺陷")
        _select_project(page, pname)
        table = page.locator('[data-test="defect-table"]')
        expect(table).to_contain_text(f"严重缺陷A_{uniq}", timeout=8000)

        page.locator('[data-test="defect-filter-severity"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="P0").first.click()
        expect(table.locator("tbody tr.el-table__row")).to_have_count(2, timeout=5000)
        page.locator('[data-test="defect-export-btn"]').click()
        with page.expect_download(timeout=30000) as dl_info:
            page.locator('[data-test="defect-export-filter"]').click()
        download = dl_info.value
        path = tmp_path / download.suggested_filename
        download.save_as(str(path))
        rows = _read_xlsx(path)
        assert len(rows) == 3, f"筛选导出行数={len(rows)-1} 期望 2"
        assert all(r0[2] == "P0" for r0 in rows[1:]), f"筛选未收敛: {[r0[2] for r0 in rows[1:]]}"
        assert all("轻微缺陷" not in r0[1] for r0 in rows[1:])
        expect(page.locator(".el-message").filter(has_text="已导出 2 条缺陷")).to_be_visible(timeout=5000)

        rl = api_client.get(f"/defect-library?projectId={pid}&page=1&size=5")
        visible_id = rl.json()["data"]["list"][0]["id"]
        r = api_client.post("/defect-library/export", json={
            "projectId": pid, "ids": [visible_id, 99999999]})
        d = r.json()["data"]
        assert d["total"] == 1 and d["removed"] == 1, \
            f"剔除计数错误: total={d['total']} removed={d['removed']}"
        raw = base64.b64decode(d["content"])
        assert raw[:2] == b"PK"

        assert_no_errors(collectors, "P2 缺陷筛选导出+剔除")
    finally:
        api_client.put(f"/projects/{pid}/restore")
        api_client.delete(f"/projects/{pid}/purge?force=true")
