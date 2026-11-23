
import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors


def _create_project(api_client, uniq):
    pass
    code = f"ATR{uniq.upper()}"
    resp = api_client.post("/projects", {"name": f"属性项目{uniq}", "code": code})
    body = resp.json()
    assert body["code"] == "0", f"创建项目失败: {body}"
    return body["data"], code


def _open_edit_dialog(page, name):
    page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
    page.wait_for_selector('[data-test="project-table"]', timeout=10000)
    row = page.locator('[data-test="project-table"] tbody tr').filter(has_text=name)
    expect(row.first).to_be_visible(timeout=8000)
    row.first.locator('[data-test="ra-edit"]').click()
    page.wait_for_selector('div[data-test="project-dialog"]', timeout=5000)
    return row


def _select_owner(page, selector_test, popper_class, label_keyword):
    pass
    page.locator(f'[data-test="{selector_test}"]').click()
    option = page.locator(f".{popper_class} .el-select-dropdown__item:visible", has_text=label_keyword).first
    option.wait_for(timeout=5000)
    option.click()


def test_prj_attributes_save_reload_persist(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid, code = _create_project(api_client, uniq)
    name = f"属性项目{uniq}"
    try:
        _open_edit_dialog(page, name)
        page.fill('input[data-test="project-jira-input"]', "TP2026")
        _select_owner(page, "project-test-owner-select", "tp-test-owner-popper", "admin")
        _select_owner(page, "project-dev-owner-select", "tp-dev-owner-popper", "admin")
        page.fill('input[data-test="project-script-path"]', "/data/scripts/e2e")
        page.fill('input[data-test="project-recording-path"]', "/data/recordings/e2e")
        page.click('[data-test="project-storage-cloud"]')

        with page.expect_response(
            lambda r: r.request.method == "PUT" and f"/api/v1/projects/{pid}" in r.url
        ):
            page.click('button[data-test="project-submit"]')
        toast = page.locator(".el-message--success").first
        expect(toast).to_be_visible(timeout=5000)
        assert "成功" in toast.inner_text()

        view = api_client.get(f"/projects/{pid}").json()["data"]
        assert view["jiraProjectId"] == "TP2026", f"JIRA 键未持久化: {view}"
        assert view["testOwnerId"] and view["devOwnerId"], f"负责人未持久化: {view}"
        assert view["scriptStoragePath"] == "/data/scripts/e2e", view
        assert view["recordingStoragePath"] == "/data/recordings/e2e", view
        assert view["storageMode"] == 3, f"存储模式未持久化: {view['storageMode']}"

        _open_edit_dialog(page, name)
        expect(page.locator('input[data-test="project-jira-input"]')).to_have_value("TP2026")
        expect(page.locator('input[data-test="project-script-path"]')).to_have_value("/data/scripts/e2e")
        expect(page.locator('input[data-test="project-recording-path"]')).to_have_value("/data/recordings/e2e")
        expect(page.locator('[data-test="project-test-owner-select"]')).to_contain_text("admin")
        expect(page.locator('[data-test="project-dev-owner-select"]')).to_contain_text("admin")
        expect(page.locator('[data-test="project-storage-mode"] .is-active')).to_have_text("云存储")

        assert_no_errors(collectors, "T158 项目属性保存+回显")
    finally:
        api_client.delete(f"/projects/{pid}")


def test_prj_attributes_jira_key_invalid_blocked(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid, _ = _create_project(api_client, uniq)
    name = f"属性项目{uniq}"
    try:
        _open_edit_dialog(page, name)
        page.fill('input[data-test="project-jira-input"]', "tp2026")
        page.click('button[data-test="project-submit"]')
        err = page.locator(".el-form-item__error")
        expect(err.first).to_be_visible(timeout=5000)
        assert "JIRA 项目键格式非法" in err.first.inner_text(), err.first.inner_text()
        expect(page.locator('div[data-test="project-dialog"]')).to_be_visible()
        view = api_client.get(f"/projects/{pid}").json()["data"]
        assert view["jiraProjectId"] is None, f"非法键被写入后端: {view}"

        assert_no_errors(collectors, "T158 JIRA 键前端校验")
    finally:
        api_client.delete(f"/projects/{pid}")
