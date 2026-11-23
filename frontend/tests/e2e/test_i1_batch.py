
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to, pick_project_in_header

import subprocess

BATCH_MENU = "执行批次"


def goto_batches(page):
    pass
    nav_to(page, BATCH_MENU, "/batches", '[data-test="batch-table"]', submenu="执行测试")


def inject_token(page):
    pass
    from conftest import ADMIN_PASSWORD, api_login
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台（/users/me 疑似网络抖动被踢回登录）")
    raise AssertionError("unreachable")


def select_visible_option(page, text):
    return page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{text}"):visible').first


def sql(q):
    subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)


def create_batch_dataset(api, uniq):
    pass
    code = f"I1E{uniq[:6]}"
    r = api.post("/projects", json={"name": f"I1批次项目{uniq}", "code": code})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"Web测试环境{uniq}", "envType": 2,
        "baseUrl": f"https://i1e-{uniq[:6]}.tp-local.test", "endTypes": "web"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_id = r.json()["data"]
    case_ids = []
    for title in ("登录用例", "下单用例", "退款用例"):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"{title}{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])
    return pid, env_id, case_ids


def cleanup_project(api, pid):
    pass
    api.delete(f"/projects/{pid}")
    api.delete(f"/projects/{pid}/purge?force=true")


def pick_project(page, uniq):
    # Assertion fix : batch page carries no per-page selector since
    # the IA pass — switch the global header project context instead.
    pick_project_in_header(page, f"I1批次项目{uniq}")
    expect(page.locator('[data-test="batch-create-btn"]')).to_be_enabled(timeout=5000)


def test_i1_batch_create_edit_reload(page, collectors, api_client, uniq):
    pass
    pid, env_id, case_ids = create_batch_dataset(api_client, uniq)
    try:
        inject_token(page)
        goto_batches(page)
        expect(page.locator('[data-test="batch-list-page"]')).to_be_visible(timeout=15000)
        pick_project(page, uniq)
        expect(page.locator('[data-test="batch-table"]')).to_be_visible()

        page.locator('[data-test="batch-create-btn"]').click()
        expect(page.locator('[data-test="batch-create-dialog"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="batch-form-name"]').fill(f"回归批次{uniq[:6]}")
        page.locator('[data-test="batch-form-env"]').click()
        select_visible_option(page, f"Web测试环境{uniq}").click()
        page.keyboard.press("Escape")
        case_rows = page.locator('[data-test="batch-case-table"] .el-table__body tbody tr')
        expect(case_rows).to_have_count(3, timeout=10000)
        case_rows.nth(0).locator('.el-checkbox').click()
        case_rows.nth(1).locator('.el-checkbox').click()
        expect(page.locator('[data-test="batch-picked-count"]')).to_contain_text("2")
        page.locator('[data-test="batch-form-submit"]').click()
        expect(page.locator('.el-message--success').first).to_contain_text("草稿批次创建成功", timeout=10000)

        expect(page.locator('[data-test="batch-table"] tbody tr')).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="batch-status-tag"]').first).to_contain_text("草稿")
        expect(page.locator('[data-test="batch-table"]')).to_contain_text(f"回归批次{uniq[:6]}")

        page.locator('[data-test="batch-name-link"]').first.click()
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="batch-case-list"] tbody tr')).to_have_count(2, timeout=10000)
        expect(page.locator('[data-test="batch-case-result"]').first).to_contain_text("未执行")

        page.locator('[data-test="batch-add-case-btn"]').click()
        expect(page.locator('[data-test="batch-add-dialog"]')).to_be_visible(timeout=5000)
        add_rows = page.locator('[data-test="batch-add-table"] .el-table__body tbody tr')
        expect(add_rows).to_have_count(3, timeout=10000)
        add_rows.nth(2).locator('.el-checkbox').click()
        page.locator('[data-test="batch-add-submit"]').click()
        expect(page.locator('.el-message--success').first).to_contain_text("已添加", timeout=10000)
        expect(page.locator('[data-test="batch-case-list"] tbody tr')).to_have_count(3, timeout=10000)

        page.locator('[data-test^="batch-remove-"]').first.click()
        page.locator('.el-message-box__btns button.el-button--primary').click()
        expect(page.locator('[data-test="batch-case-list"] tbody tr')).to_have_count(2, timeout=10000)

        detail_url = page.url
        page.goto(detail_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-case-list"] tbody tr')).to_have_count(2, timeout=10000)

        page.goto(f"{BASE_URL}/#/batches", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-list-page"]')).to_be_visible(timeout=15000)
        pick_project(page, uniq)
        expect(page.locator('[data-test="batch-table"] tbody tr')).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="batch-table"]')).to_contain_text("回归批次")

        assert_no_errors(collectors, "I1 批次建批+编辑+reload 持久化")
    finally:
        cleanup_project(api_client, pid)


def test_i1_batch_template_save_reuse(page, collectors, api_client, uniq):
    pass
    pid, env_id, case_ids = create_batch_dataset(api_client, uniq)
    try:
        r = api_client.post("/batches", json={
            "projectId": pid, "name": f"模板源批{uniq[:6]}", "envId": env_id,
            "caseIds": case_ids[:2]})
        assert r.json().get("code") == "0", f"API 建批失败: {r.text[:300]}"

        inject_token(page)
        goto_batches(page)
        pick_project(page, uniq)
        expect(page.locator('[data-test="batch-table"] tbody tr')).to_have_count(1, timeout=10000)

        page.locator('[data-test="batch-save-template-btn"]').first.click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=5000)
        box.locator("input").fill(f"标准模板{uniq[:6]}")
        box.locator("button.el-button--primary").click()
        expect(page.locator('.el-message--success').first).to_contain_text("模板已保存", timeout=10000)

        page.locator('[data-test="batch-template-btn"]').click()
        expect(page.locator('[data-test="batch-template-dialog"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="batch-template-table"] tbody tr')).to_have_count(1, timeout=5000)
        page.locator('[data-test="batch-tpl-reuse-btn"]').first.click()
        expect(page.locator('[data-test="batch-reuse-dialog"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="batch-reuse-env"]').click()
        select_visible_option(page, f"Web测试环境{uniq}").click()
        page.locator('[data-test="batch-reuse-submit"]').click()
        expect(page.locator('.el-message--success').first).to_contain_text("已按模板建批", timeout=10000)

        page.goto(f"{BASE_URL}/#/batches", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-list-page"]')).to_be_visible(timeout=15000)
        pick_project(page, uniq)
        expect(page.locator('[data-test="batch-table"] tbody tr')).to_have_count(2, timeout=10000)
        expect(page.locator('[data-test="batch-table"]')).to_contain_text(f"标准模板{uniq[:6]}（复用）")

        page.locator('[data-test="batch-name-link"]', has_text="（复用）").first.click()
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="batch-case-list"] tbody tr')).to_have_count(2, timeout=10000)
        expect(page.locator('[data-test="batch-meta"]')).to_be_visible()

        assert_no_errors(collectors, "I1 模板保存+复用建批")
    finally:
        cleanup_project(api_client, pid)


def test_i1_batch_non_draft_locked(page, collectors, api_client, uniq):
    pass
    pid, env_id, case_ids = create_batch_dataset(api_client, uniq)
    try:
        r = api_client.post("/batches", json={
            "projectId": pid, "name": f"锁定批{uniq[:6]}", "envId": env_id,
            "caseIds": case_ids[:2]})
        bid = r.json()["data"]["id"]
        sql(f"UPDATE tp_exec_batch SET status = 4 WHERE id = {bid}")

        inject_token(page)
        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text("执行中")
        expect(page.locator('[data-test="batch-draft-tip"]')).to_contain_text("不可再编辑")
        expect(page.locator('[data-test="batch-add-case-btn"]')).to_have_count(0)
        expect(page.locator('[data-test^="batch-remove-"]')).to_have_count(0)

        assert_no_errors(collectors, "I1 非草稿批次锁定")
    finally:
        cleanup_project(api_client, pid)
