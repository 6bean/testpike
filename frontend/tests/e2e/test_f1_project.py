
import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors



def test_project_crud_with_reload(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    name = f"E2E项目{uniq}"
    code = f"E2E{uniq.upper()}"

    page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
    page.click('button[data-test="project-create"]')
    page.wait_for_selector('div[data-test="project-dialog"]', timeout=5000)
    page.fill('input[data-test="project-name-input"]', name)
    page.fill('input[data-test="project-code-input"]', code)
    page.click('button[data-test="project-submit"]')
    toast = page.locator(".el-message--success").first
    expect(toast).to_be_visible(timeout=5000)
    assert "成功" in toast.inner_text()

    page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
    page.wait_for_selector('[data-test="project-table"]', timeout=8000)
    rows = page.locator('[data-test="project-table"] tbody tr')
    expect(rows.first).to_be_visible(timeout=8000)
    texts = rows.all_inner_texts()
    assert any(name in t for t in texts), f"reload 后项目应存在: {texts}"

    row = rows.filter(has_text=name)
    row.locator('[data-test="ra-edit"]').click()
    page.wait_for_selector('div[data-test="project-dialog"]', timeout=5000)
    page.fill('input[data-test="project-name-input"]', f"{name}-改")
    with page.expect_response(lambda r: r.request.method == "PUT" and "/api/v1/projects/" in r.url):
        page.click('button[data-test="project-submit"]')
    page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
    expect(page.locator('[data-test="project-table"] tbody tr').first).to_be_visible(timeout=8000)
    texts = page.locator('[data-test="project-table"] tbody tr').all_inner_texts()
    assert any(f"{name}-改" in t for t in texts), f"reload 后应显示修改后名称: {texts}"

    row = page.locator('[data-test="project-table"] tbody tr').filter(has_text=f"{name}-改")
    row.locator('[data-test="ra-more"]').click()
    page.locator('[data-test="ra-archive"]:visible').first.click()
    page.wait_for_selector(".el-message-box", timeout=5000)
    with page.expect_response(lambda r: r.request.method == "DELETE" and "/api/v1/projects/" in r.url):
        page.click(".el-message-box__btns button:has-text('归档')")
    page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
    expect(page.locator("[data-test=project-table]")).not_to_contain_text(f"{name}-改")
    texts = page.locator('[data-test="project-table"] tbody tr').all_inner_texts()
    assert not any(f"{name}-改" in t for t in texts), f"归档后不应存在: {texts}"

    resp = api_client.get("/projects?page=1&size=20&status=2")
    data = resp.json()["data"]
    found = [p for p in data["list"] if p["code"] == code]
    assert found and found[0]["status"] == 2, f"后端应保留归档记录 status=2: {found}"

    assert_no_errors(collectors, "project CRUD")
