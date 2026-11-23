
import httpx
from conftest import (
    ADMIN_PASSWORD,
    API_BASE,
    BASE_URL,
    api_login,
    assert_no_errors,
    nav_to,
    pick_project_in_header,
    sql_query,
)
from playwright.sync_api import expect

BATCH_MENU = "执行批次"


def goto_batches(page):
    nav_to(page, BATCH_MENU, "/batches", '[data-test="batch-table"]', submenu="执行测试")


def inject_token(page):
    token = api_login("admin", ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)


def select_visible_option(page, text):
    return page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{text}"):visible').first


def create_browser_dataset(api, uniq, browsers):
    pass
    r = api.post("/projects", json={"name": f"H3浏览器项目{uniq}", "code": f"H3B{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"矩阵环境{uniq}", "envType": 2,
        "baseUrl": f"https://h3b-{uniq[:6]}.tp-local.test",
        "endTypes": "web", "browsers": browsers})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_id = r.json()["data"]
    case_ids = []
    for title in ("登录用例", "下单用例"):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"{title}{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])
    return pid, env_id, case_ids


def cleanup_project(api, pid):
    api.delete(f"/projects/{pid}")
    api.delete(f"/projects/{pid}/purge?force=true")


def pick_project(page, uniq):
    # Assertion fix : batch page carries no per-page selector since
    # the IA pass — switch the global header project context instead.
    pick_project_in_header(page, f"H3浏览器项目{uniq}")
    expect(page.locator('[data-test="batch-create-btn"]')).to_be_enabled(timeout=5000)


def open_create_with_env(page, uniq, batch_name):
    page.locator('[data-test="batch-create-btn"]').click()
    expect(page.locator('[data-test="batch-create-dialog"]')).to_be_visible(timeout=5000)
    page.locator('[data-test="batch-form-name"]').fill(batch_name)
    page.locator('[data-test="batch-form-env"]').click()
    select_visible_option(page, f"矩阵环境{uniq}").click()
    page.keyboard.press("Escape")
    case_rows = page.locator('[data-test="batch-case-table"] .el-table__body tbody tr')
    expect(case_rows).to_have_count(2, timeout=10000)
    case_rows.nth(0).locator('.el-checkbox').click()
    case_rows.nth(1).locator('.el-checkbox').click()


def test_h3_batch_browser_select_persist(page, collectors, api_client, uniq):
    pass
    pid, _env, _cases = create_browser_dataset(api_client, uniq, "chromium,firefox")
    try:
        inject_token(page)
        goto_batches(page)
        pick_project(page, uniq)

        open_create_with_env(page, uniq, f"FF批次{uniq[:6]}")
        page.locator('[data-test="batch-form-browser"]').click()
        ff_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible').filter(has_text="Firefox").first
        expect(ff_opt).to_be_visible(timeout=5000)
        wk_opts = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible').filter(has_text="WebKit")
        expect(wk_opts).to_have_count(0)
        ff_opt.click()
        page.locator('[data-test="batch-form-submit"]').click()
        expect(page.locator('.el-message--success').first).to_contain_text("草稿批次创建成功", timeout=10000)
        expect(page.locator('[data-test="batch-table"]')).to_contain_text(f"FF批次{uniq[:6]}")

        page.locator('[data-test="batch-name-link"]').first.click()
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="batch-meta"]')).to_contain_text("firefox", timeout=10000)

        detail_url = page.url
        page.goto(detail_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-meta"]')).to_contain_text("firefox", timeout=10000)

        row = sql_query(
            f"select browser from tp_exec_batch b join tp_project p on b.project_id=p.id "
            f"where p.id={pid} and b.type=1")
        assert row.strip() == "firefox", f"DB browser 非 firefox: {row!r}"

        assert_no_errors(collectors, "H3 批次浏览器选择持久化")
    finally:
        cleanup_project(api_client, pid)


def test_h3_batch_browser_default_and_matrix_guard(page, collectors, api_client, uniq):
    pass
    pid, env_id, case_ids = create_browser_dataset(api_client, uniq, "chromium,firefox")
    try:
        r = httpx.post(f"{API_BASE}/batches", headers=api_client.headers, json={
            "projectId": pid, "name": f"越权浏览器{uniq[:6]}", "envId": env_id,
            "caseIds": case_ids, "browser": "webkit"}, timeout=15)
        body = r.json()
        assert body.get("code") != "0", f"超矩阵竟被接受: {r.text[:300]}"
        msg = body.get("msg", "")
        assert "webkit" in msg and "不包含" in msg, f"400 人话未点名矩阵: {msg!r}"

        inject_token(page)
        goto_batches(page)
        pick_project(page, uniq)

        open_create_with_env(page, uniq, f"缺省批次{uniq[:6]}")
        page.locator('[data-test="batch-form-submit"]').click()
        expect(page.locator('.el-message--success').first).to_contain_text("草稿批次创建成功", timeout=10000)
        page.locator('[data-test="batch-name-link"]').first.click()
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="batch-meta"]')).to_contain_text("不指定（缺省 Chromium）", timeout=10000)
        row = sql_query(
            f"select coalesce(browser,'<null>') from tp_exec_batch b join tp_project p on b.project_id=p.id "
            f"where p.id={pid} and b.type=1")
        assert row.strip() == "<null>", f"缺省链路 DB browser 应为 NULL（启动链路解析）: {row!r}"

        assert_no_errors(collectors, "H3 浏览器缺省链路与矩阵闸")
    finally:
        cleanup_project(api_client, pid)
