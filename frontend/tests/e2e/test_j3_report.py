
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import pick_project_in_header, ADMIN_PASSWORD, ADMIN_USERNAME, API_BASE, BASE_URL, assert_no_errors



def _mk_project(api):
    uniq = uuid.uuid4().hex[:8]
    name = f"J3报表_{uniq}"
    code = f"J3{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _mk_case(api, pid, title):
    r = api.post("/cases", json={"projectId": pid, "title": title, "precondition": "前置",
                                "expectedResult": "通过", "priority": 1, "confidence": 2, "endType": "web"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _review_pass(api, case_id):
    r = api.get(f"/cases/{case_id}")
    assert r.status_code == 200, f"用例详情失败: {r.text[:300]}"
    ver = r.json()["data"]["version"]
    r = api.post(f"/cases/{case_id}/review", json={"action": "pass", "version": ver})
    assert r.status_code == 200 and r.json().get("code") == "0", f"评审通过失败: {r.text[:300]}"


@pytest.fixture
def prep_data(api_client):
    pass
    pid, _ = _mk_project(api_client)
    c1 = _mk_case(api_client, pid, "报表用例一")
    c2 = _mk_case(api_client, pid, "报表用例二")
    _review_pass(api_client, c1)
    _review_pass(api_client, c2)
    return pid


def test_report_page_tabs_export_reload(logged_in, collectors, prep_data):
    pass
    page = logged_in
    pid = prep_data
    test_name = "J3 统计报表页"

    page.goto(f"{BASE_URL}/#/report", wait_until="domcontentloaded")

    expect(page.locator('[data-test="project-ctx-select"]')).to_be_visible(timeout=15000)
    # R30: fixture-created project may lag the login snapshot; helper reloads once.
    pick_project_in_header(page, "J3报表_")
    page.wait_for_timeout(500)

    tabs = page.locator('[data-test="report-tabs"] .el-tabs__item')
    expect(tabs).to_have_count(6)
    tab_labels = tabs.all_text_contents()
    for label in ["个人评审进度", "团队看板", "AI 质量", "覆盖率", "执行统计", "跨项目对比"]:
        assert any(label in t for t in tab_labels), f"Tab 缺失: {label}, 实际: {tab_labels}"

    expect(page.locator('[data-test="pr-reviewed"]')).to_have_text("2", timeout=15000)
    expect(page.locator('[data-test="pr-pass"]')).to_have_text("2")

    page.locator('.el-tabs__item:has-text("团队看板")').click()
    expect(page.locator('[data-test="team-board-table"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="team-board-table"] tbody tr').first).to_contain_text("admin")

    for label, sel in [("AI 质量", "ai-stats"), ("覆盖率", "coverage-stats"), ("执行统计", "exec-stats")]:
        page.locator(f'.el-tabs__item:has-text("{label}")').click()
        page.wait_for_timeout(400)

    page.locator('[data-test="report-export-btn"]').click()
    page.locator('.el-dropdown-menu__item:has-text("个人评审进度")').click()
    expect(page.locator(".el-message")).to_contain_text("导出完成", timeout=15000)

    page.goto(f"{BASE_URL}/#/report", wait_until="domcontentloaded")
    pick_project_in_header(page, "J3报表_")
    expect(page.locator('[data-test="pr-reviewed"]')).to_have_text("2", timeout=15000)

    assert_no_errors(collectors, test_name)
