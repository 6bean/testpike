
import openpyxl
import pytest
from playwright.sync_api import expect

from conftest import nav_to, assert_no_errors


CASE_HEADERS = ["测试目标", "前置条件", "预期结果", "优先级", "置信度", "终端类型", "步骤"]


def _mk_project(api, uniq):
    name = f"J1导入项目_{uniq}"
    code = f"J1{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200, f"创建项目失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    return body["data"], name


def _mk_xlsx(rows, path):
    pass
    wb = openpyxl.Workbook()
    ws = wb.active if wb.active is not None else wb.create_sheet()
    ws.append(CASE_HEADERS)
    for r in rows:
        ws.append(r)
    wb.save(path)
    return path


def _select_project(page, project_name):
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()


def test_j1_case_import_full_flow(logged_in, api_client, collectors, uniq, tmp_path):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)

    page.reload(wait_until="domcontentloaded")
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, pname)

    page.locator('[data-test="case-import"]').click()
    expect(page.locator('[data-test="import-dialog"]')).to_be_visible()

    with page.expect_download() as dl_info:
        page.locator('[data-test="import-template-download"]').click()
    download = dl_info.value
    template_path = tmp_path / f"template_{uniq}.xlsx"
    download.save_as(str(template_path))
    wb = openpyxl.load_workbook(template_path)
    ws = wb.active if wb.active is not None else wb.create_sheet()
    headers = [c.value for c in ws[1]]
    assert headers == CASE_HEADERS, f"模板列与导出模板不一致: {headers}"

    rows = [
        [f"导入用例A_{uniq}", "前置A", "预期A", 1, 2, "web", "打开页面=>页面展示"],
        [f"导入用例B_{uniq}", "前置B", "预期B", 0, 1, "mobile", "点击按钮=>弹窗出现"],
        [f"导入用例C_{uniq}", "前置C", "预期C", 5, 2, "web", "步骤"],
    ]
    file_path = _mk_xlsx(rows, tmp_path / f"import_{uniq}.xlsx")

    page.locator('[data-test="import-upload"] input[type="file"]').set_input_files(str(file_path))
    page.locator('[data-test="import-submit"]').click()
    expect(page.locator('[data-test="import-result"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="import-success-count"]')).to_contain_text("成功 2")
    expect(page.locator('[data-test="import-fail-count"]')).to_contain_text("失败 1")
    fail_items = page.locator('[data-test="import-fail-item"]')
    expect(fail_items).to_have_count(1)
    expect(fail_items.first).to_contain_text("优先级")
    expect(page.locator('[data-test="import-fix-hint"]')).to_be_visible()

    wb = openpyxl.load_workbook(file_path)
    ws = wb.active if wb.active is not None else wb.create_sheet()
    ws.cell(row=4, column=4, value=2)
    fixed_path = tmp_path / f"import_fixed_{uniq}.xlsx"
    wb.save(fixed_path)
    page.locator('[data-test="import-upload"] input[type="file"]').set_input_files(str(fixed_path))
    page.locator('[data-test="import-retry"]').click()
    expect(page.locator('[data-test="import-dialog"]')).not_to_be_visible(timeout=15000)
    expect(page.locator(".el-message--success").last).to_contain_text("重试完成", timeout=5000)

    page.reload(wait_until="domcontentloaded")
    # The header switch renders only after the /users/me snapshot arrives;
    # the unbounded expect raced it on a cold lane boot (slot1 2026-11-03).
    page.wait_for_selector('[data-test="project-ctx-select"]', timeout=15000)
    _select_project(page, pname)
    expect(page.locator('[data-test="case-table"]')).to_be_visible()
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(3)
    expect(page.locator('[data-test="case-table"]')).to_contain_text(f"导入用例A_{uniq}")
    expect(page.locator('[data-test="case-table"]')).to_contain_text(f"导入用例C_{uniq}")
    expect(page.locator('[data-test="case-table"]')).to_contain_text("存量导入")

    r = api_client.get(f"/cases?projectId={pid}")
    assert r.status_code == 200
    cases = r.json()["data"]["list"]
    imported = [c for c in cases if c["title"].startswith(f"导入用例{uniq[-2:]}") or f"导入用例A_{uniq}" in c["title"]]
    assert len(cases) == 3, f"期望 3 条用例，实际 {len(cases)}"
    assert all(c["source"] == 3 for c in cases), "导入用例来源应为 3（存量导入）"

    assert_no_errors(collectors, "TC-J1-03 用例导入全链路")


def test_j1_requirement_import_flow(logged_in, api_client, collectors, uniq, tmp_path):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)

    page.reload(wait_until="domcontentloaded")
    # Requirement ledger re-anchored: navigate the standalone
    # /requirements menu leaf directly (k7 spectrum).
    nav_to(page, "需求点管理", "/requirements", '[data-test="req-page"]', submenu="设计用例")
    expect(page.locator('[data-test="project-ctx-select"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=pname).first
    expect(option).to_be_visible(timeout=5000)
    option.click()

    page.locator('[data-test="req-import"]').click()
    expect(page.locator('[data-test="import-dialog"]')).to_be_visible()
    expect(page.locator('[data-test="import-target-status"]')).to_have_count(0)

    with page.expect_download() as dl_info:
        page.locator('[data-test="import-template-download"]').click()
    download = dl_info.value
    template_path = tmp_path / f"req_template_{uniq}.xlsx"
    download.save_as(str(template_path))
    wb = openpyxl.load_workbook(template_path)
    ws = wb.active if wb.active is not None else wb.create_sheet()
    req_headers = [c.value for c in ws[1]]
    assert req_headers == ["需求标题", "章节", "验收标准", "来源"], f"需求模板列不符: {req_headers}"

    upload_path = tmp_path / f"req_import_{uniq}.xlsx"
    wb2 = openpyxl.Workbook()
    ws2 = wb2.active if wb2.active is not None else wb2.create_sheet()
    ws2.append(req_headers)
    ws2.append([f"导入需求X_{uniq}", "3.1", "验收标准X", "1"])
    ws2.append([f"导入需求Y_{uniq}", "3.2", "验收标准Y", "2"])
    wb2.save(upload_path)
    page.locator('[data-test="import-upload"] input[type="file"]').set_input_files(str(upload_path))
    page.locator('[data-test="import-submit"]').click()
    expect(page.locator('[data-test="import-dialog"]')).not_to_be_visible(timeout=15000)
    expect(page.locator(".el-message--success").last).to_contain_text("成功 2", timeout=5000)

    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="project-ctx-select"]')).to_be_visible()
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=pname).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
    expect(page.locator('[data-test="req-table"]')).to_contain_text(f"导入需求X_{uniq}")
    expect(page.locator('[data-test="req-table"]')).to_contain_text(f"导入需求Y_{uniq}")

    assert_no_errors(collectors, "TC-J1-03 需求点导入链路")
