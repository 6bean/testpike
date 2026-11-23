
from conftest import BASE_URL, assert_no_errors
from playwright.sync_api import expect


def _make_project(api_client, uniq):
    pass
    resp = api_client.post("/projects", {"name": f"信息块项目{uniq}", "code": f"INF{uniq.upper()}"})
    body = resp.json()
    assert body["code"] == "0", f"创建项目失败: {body}"
    pid = body["data"]
    members = api_client.get(f"/projects/{pid}/members").json()["data"]
    admin_row = next(m for m in members if m["username"] == "admin")
    owner_id = admin_row["userId"]
    cur = api_client.get(f"/projects/{pid}").json()["data"]
    put = api_client.put(f"/projects/{pid}", {
        "name": cur["name"],
        "jiraProjectId": "TPINFO",
        "testOwnerId": owner_id,
        "devOwnerId": owner_id,
        "scriptStoragePath": "/data/scripts/info-e2e",
        "recordingStoragePath": "/data/recordings/info-e2e",
        "storageMode": 3,
    }).json()
    assert put["code"] == "0", f"六属性保存失败: {put}"
    return pid, owner_id


def _open_info_tab(page, pid, reload=False):
    if reload:
        page.reload(wait_until="domcontentloaded")
    else:
        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    page.wait_for_selector('[data-test="env-table"]', timeout=10000)
    page.click('.el-tabs__item:has-text("项目信息")')
    page.wait_for_selector('[data-test="project-info-descriptions"]', timeout=8000)


def _assert_info_fields(page):
    expect(page.locator('[data-test="pi-jira-key"]')).to_have_text("TPINFO")
    expect(page.locator('[data-test="pi-test-owner"]')).to_contain_text("admin")
    expect(page.locator('[data-test="pi-dev-owner"]')).to_contain_text("admin")
    expect(page.locator('[data-test="pi-script-path"]')).to_have_text("/data/scripts/info-e2e")
    expect(page.locator('[data-test="pi-recording-path"]')).to_have_text("/data/recordings/info-e2e")
    expect(page.locator('[data-test="pi-storage-mode"]')).to_contain_text("云存储")


def test_prj_info_block_display_and_reload_persist(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid, _ = _make_project(api_client, uniq)
    try:
        _open_info_tab(page, pid)
        _assert_info_fields(page)
        _open_info_tab(page, pid, reload=True)
        _assert_info_fields(page)
        assert_no_errors(collectors, "T169 项目信息展示块")
    finally:
        api_client.delete(f"/projects/{pid}")


def test_prj_info_block_defaults_empty(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    resp = api_client.post("/projects", {"name": f"信息空项目{uniq}", "code": f"INFE{uniq.upper()}"})
    pid = resp.json()["data"]
    try:
        _open_info_tab(page, pid)
        expect(page.locator('[data-test="pi-jira-key"]')).to_have_text("未设置")
        expect(page.locator('[data-test="pi-test-owner"]')).to_have_text("未设置")
        expect(page.locator('[data-test="pi-script-path"]')).to_have_text("系统默认")
        expect(page.locator('[data-test="pi-storage-mode"]')).to_contain_text("系统默认")
        assert_no_errors(collectors, "T169 项目信息空态")
    finally:
        api_client.delete(f"/projects/{pid}")
