
import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors



@pytest.fixture
def project(api_client, uniq):
    pass
    name = f"详情页项目{uniq}"
    code = f"DTL{uniq.upper()}"
    resp = api_client.post("/projects", json={"name": name, "code": code, "description": "E2E 详情页测试"})
    assert resp.status_code == 200, f"创建项目失败: {resp.status_code} {resp.text[:300]}"
    body = resp.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    pid = body["data"]
    yield pid, name
    api_client.delete(f"/projects/{pid}")


def test_project_envs_crud_reload(logged_in, collectors, project, uniq):
    pass
    page = logged_in
    pid, name = project
    env_name = f"环境{uniq}"
    base_url = f"https://e2e-{uniq}.example.com"

    page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    page.wait_for_selector('[data-test="env-table"]', timeout=8000)

    page.click('button[data-test="env-add"]')
    page.wait_for_selector('div[data-test="env-dialog"]', timeout=5000)
    page.fill('input[data-test="env-name-input"]', env_name)
    page.fill('input[data-test="env-url-input"]', base_url)
    with page.expect_response(lambda r: r.request.method == "POST" and "/envs" in r.url):
        page.click('button[data-test="env-submit"]')
    page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    rows = page.locator('[data-test="env-table"] tbody tr')
    expect(rows.filter(has_text=env_name).first).to_be_visible(timeout=8000)
    texts = rows.all_inner_texts()
    assert any(env_name in t and base_url in t for t in texts), f"reload 后环境应存在: {texts}"

    row = rows.filter(has_text=env_name)
    row.locator('[data-test="ra-edit"]').click()
    page.wait_for_selector('div[data-test="env-dialog"]', timeout=5000)
    page.fill('input[data-test="env-name-input"]', f"{env_name}-改")
    with page.expect_response(lambda r: r.request.method == "PUT" and "/envs/" in r.url):
        page.click('button[data-test="env-submit"]')
    page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="env-table"] tbody tr').filter(has_text=f"{env_name}-改")).to_be_visible(timeout=8000)
    texts = page.locator('[data-test="env-table"] tbody tr').all_inner_texts()
    assert any(f"{env_name}-改" in t for t in texts), "reload 后应显示修改后环境名"

    row = page.locator('[data-test="env-table"] tbody tr').filter(has_text=f"{env_name}-改")
    row.locator('[data-test="ra-more"]').click()
    with page.expect_response(lambda r: r.request.method == "DELETE" and "/envs/" in r.url):
        page.locator('[data-test="ra-delete"]:visible').first.click()
    page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="env-table"]')).not_to_contain_text(f"{env_name}-改")
    texts = page.locator('[data-test="env-table"] tbody tr').all_inner_texts()
    assert not any(f"{env_name}-改" in t for t in texts), "删除后环境不应存在"

    assert_no_errors(collectors, "project envs CRUD")


def test_project_member_add_reload(logged_in, collectors, project, uniq, api_client):
    pass
    page = logged_in
    pid, name = project
    member_name = f"e2e_member_{uniq}"
    member_pwd = "E2e@123456"

    resp = api_client.post("/users", json={
        "username": member_name, "password": member_pwd,
        "displayName": f"E2E成员{uniq}", "role": 1,
    })
    assert resp.status_code == 200, f"创建测试用户失败: {resp.status_code} {resp.text[:300]}"
    uid = resp.json()["data"]

    try:
        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
        page.wait_for_selector('[data-test="env-table"]', timeout=8000)
        page.click('.el-tabs__item:has-text("成员管理")')
        page.wait_for_selector('[data-test="member-table"]', timeout=8000)

        page.click('button[data-test="member-add"]')
        page.wait_for_selector('div[data-test="member-dialog"]', timeout=5000)
        page.click('[data-test="member-user-select"]')
        page.locator('[data-test="member-user-select"] input').fill(member_name)
        user_item = page.locator('.tp-member-user-popper .el-select-dropdown__item', has_text=member_name).first
        user_item.click()
        sel_text = page.locator('[data-test="member-user-select"]').inner_text()
        assert member_name in sel_text, f"应已选中用户: {sel_text}"
        with page.expect_response(lambda r: r.request.method == "POST" and "/members" in r.url):
            page.click('button[data-test="member-submit"]')
        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
        page.click('.el-tabs__item:has-text("成员管理")')
        page.wait_for_selector('[data-test="member-table"] tbody tr', timeout=8000)
        expect(page.locator('[data-test="member-table"] tbody tr').first).to_be_visible(timeout=8000)
        texts = page.locator('[data-test="member-table"] tbody tr').all_inner_texts()
        assert any(member_name in t for t in texts), f"reload 后新成员应存在: {texts}"

        row = page.locator('[data-test="member-table"] tbody tr').filter(has_text=member_name)
        row.locator('button:has-text("删除")').click()
        page.wait_for_selector(".el-message-box", timeout=5000)
        with page.expect_response(lambda r: r.request.method == "DELETE" and "/members/" in r.url):
            page.click(".el-message-box__btns button:has-text('删除')")
        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
        page.click('.el-tabs__item:has-text("成员管理")')
        page.wait_for_selector('[data-test="member-table"] tbody tr', timeout=8000)
        texts = page.locator('[data-test="member-table"] tbody tr').all_inner_texts()
        assert not any(member_name in t for t in texts), f"移除后成员应消失: {texts}"

        assert_no_errors(collectors, "project member add")
    finally:
        api_client.delete(f"/users/{uid}")
