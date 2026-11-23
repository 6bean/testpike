
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import pick_project_in_header, ADMIN_PASSWORD, ADMIN_USERNAME, API_BASE, BASE_URL, assert_no_errors



def _mk_project(api):
    uniq = uuid.uuid4().hex[:8]
    name = f"J4矩阵_{uniq}"
    code = f"J4{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code, "language": "zh-CN", "priority": "P1"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _mk_requirement(api, pid, title, chapter):
    r = api.post(f"/projects/{pid}/requirements", json={
        "source": 3, "title": title, "acceptanceCriteria": f"{title}验收标准", "chapter": chapter,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建需求失败: {r.text[:300]}"
    data = r.json()["data"]
    return data["id"] if isinstance(data, dict) else data


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "通过",
        "priority": 0, "confidence": 2, "endType": "web", "steps": [],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    data = r.json()["data"]
    return data["id"] if isinstance(data, dict) else data


def _link_case(api, req_id, case_id):
    r = api.post(f"/requirements/{req_id}/cases", json={"caseIds": [case_id]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"关联用例失败: {r.text[:300]}"


@pytest.fixture
def prep_matrix(api_client):
    pass
    pid, name = _mk_project(api_client)
    req_a = _mk_requirement(api_client, pid, "需求A-登录", "认证")
    _mk_requirement(api_client, pid, "需求B-结算", "交易")
    case_id = _mk_case(api_client, pid, "用例-手机号登录")
    _link_case(api_client, req_a, case_id)
    return pid, name, req_a, case_id


def test_coverage_matrix_page(logged_in, collectors, prep_matrix):
    pass
    page = logged_in
    pid, name, req_a, case_id = prep_matrix
    test_name = "J4 覆盖矩阵页"

    page.goto(f"{BASE_URL}/#/coverage", wait_until="domcontentloaded")

    expect(page.locator('[data-test="coverage-page"]')).to_be_visible(timeout=15000)
    pick_project_in_header(page, name)

    expect(page.locator('[data-test="matrix-total"]')).to_have_text("2", timeout=15000)
    expect(page.locator('[data-test="matrix-covered"]')).to_have_text("1")
    expect(page.locator('[data-test="matrix-uncovered"]')).to_have_text("1")

    expect(page.locator('[data-test="matrix-table"] tbody tr')).to_have_count(4, timeout=15000)
    chapter_cells = page.locator('[data-test="matrix-table"] .matrix-chapter-cell').all_text_contents()
    assert any("认证" in c for c in chapter_cells), f"章节组缺失 认证: {chapter_cells}"
    assert any("交易" in c for c in chapter_cells), f"章节组缺失 交易: {chapter_cells}"
    assert page.locator('.matrix-case-header').count() == 1, "用例列应为 1 列"

    covered_row = page.locator('[data-test="matrix-table"] tbody tr:has-text("需求A-登录")')
    expect(covered_row.locator(".matrix-cell-check")).to_have_count(1, timeout=10000)
    uncovered_row = page.locator('[data-test="matrix-table"] tbody tr:has-text("需求B-结算")')
    uncovered_cls = " ".join(uncovered_row.get_attribute("class").split())
    assert "matrix-row-uncovered" in uncovered_cls, f"未覆盖行缺黄色高亮 class: {uncovered_cls}"
    expect(uncovered_row.locator('[data-test="matrix-generate-btn"]')).to_be_visible()

    page.locator('[data-test="matrix-chapter-filter"]').click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("交易")').first.click()
    expect(page.locator('[data-test="matrix-table"] .matrix-chapter-cell:has-text("认证")')).to_have_count(0, timeout=10000)
    expect(page.locator('[data-test="matrix-table"] .matrix-chapter-cell:has-text("交易")').first).to_be_visible()
    page.locator('[data-test="matrix-chapter-filter"]').hover()
    page.locator('[data-test="matrix-chapter-filter"] .el-select__clear').click()
    expect(page.locator('[data-test="matrix-table"] .matrix-chapter-cell:has-text("认证")').first).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="matrix-table"] .matrix-chapter-cell:has-text("交易")').first).to_be_visible()

    page.locator('[data-test="matrix-priority-filter"]').click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("P0")').first.click()
    expect(page.locator('.matrix-case-header')).to_have_count(1, timeout=10000)
    page.locator('[data-test="matrix-priority-filter"]').hover()
    page.locator('[data-test="matrix-priority-filter"] .el-select__clear').click()
    expect(page.locator('.matrix-case-header')).to_have_count(1, timeout=10000)

    page.locator('[data-test="matrix-endtype-filter"]').click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("Web")').first.click()
    expect(page.locator('.matrix-case-header')).to_have_count(1, timeout=10000)
    page.locator('[data-test="matrix-endtype-filter"]').hover()
    page.locator('[data-test="matrix-endtype-filter"] .el-select__clear').click()
    expect(page.locator('.matrix-case-header')).to_have_count(1, timeout=10000)

    auth_group = page.locator('[data-test="matrix-table"] tbody tr:has-text("认证")').first
    auth_group.locator('[data-test="matrix-group-toggle"]').click()
    expect(page.locator('[data-test="matrix-table"] tbody tr:has-text("需求A-登录")')).to_have_count(0, timeout=10000)
    auth_group.locator('[data-test="matrix-group-toggle"]').click()
    expect(page.locator('[data-test="matrix-table"] tbody tr:has-text("需求A-登录")')).to_have_count(1, timeout=10000)

    uncovered_row = page.locator('[data-test="matrix-table"] tbody tr:has-text("需求B-结算")')
    uncovered_row.locator('[data-test="matrix-generate-btn"]').click()
    page.wait_for_url(f"**/#/ai/generate**", timeout=15000)
    expect(page.locator('[data-test="ai-gen-form"]')).to_be_visible(timeout=10000)
    # T396b: project lands via the header context deep link (in-page selector
    # retired) — reload refreshes the visible-domain snapshot, echo asserts name.
    page.reload(wait_until="domcontentloaded")
    echo = page.locator('[data-test="owner-echo"]').first
    expect(echo).to_be_visible(timeout=10000)
    expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(name, timeout=10000)
    expect(page.locator('[data-test="ai-gen-requirement-select"]')).to_contain_text("需求B-结算", timeout=10000)

    page.goto(f"{BASE_URL}/#/coverage", wait_until="domcontentloaded")
    pick_project_in_header(page, name)
    expect(page.locator('[data-test="matrix-total"]')).to_have_text("2", timeout=15000)
    expect(page.locator('[data-test="matrix-covered"]')).to_have_text("1")
    expect(page.locator('[data-test="matrix-uncovered"]')).to_have_text("1")

    assert_no_errors(collectors, test_name)
