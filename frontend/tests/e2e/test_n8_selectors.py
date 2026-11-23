
import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, inject_token, nav_to



def _mk_project(api, code, name):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "预期",
        "priority": 2, "confidence": 2, "endType": "web", "phase": 1, "source": 2, "status": 1,
        "steps": [{"stepNo": 1, "description": "步骤一", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_tester(api, username, display):
    r = api.post("/users", json={
        "username": username, "password": "Test@12345", "displayName": display,
        "email": f"{username}@test.local", "phone": "", "role": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用户失败: {r.text[:300]}"
    return r.json()["data"]


def _pick_dropdown(page, text):
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=text).first.click()


def test_n8_project_select_search(page, collectors, api_client, uniq):
    pass
    api = api_client
    pid_a = _mk_project(api, f"N8SA{uniq.upper()}", f"N8搜索甲_{uniq}")
    pid_b = _mk_project(api, f"N8SB{uniq.upper()}", f"N8搜索乙_{uniq}")
    _mk_case(api, pid_a, f"N8项目搜索用例_{uniq}")
    try:
        inject_token(page)
        nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
        sel = page.locator('[data-test="project-ctx-select"]')

        sel.click()
        sel.locator('input').fill(f"N8搜索甲_{uniq}")
        expect(
            page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"N8搜索甲_{uniq}")
        ).to_be_visible(timeout=10000)
        _pick_dropdown(page, f"N8搜索甲_{uniq}")

        expect(page.locator('[data-test="case-table"]')).to_contain_text(
            f"N8项目搜索用例_{uniq}", timeout=10000)

        sel.click()
        sel.locator('input').fill("不存在的项目zzz")
        expect(page.locator(".el-select-dropdown__empty").filter(has_text="暂无数据")).to_be_visible(timeout=10000)
        page.keyboard.press("Escape")

        assert_no_errors(collectors, "N8 项目选择器搜索")
    finally:
        api.delete(f"/projects/{pid_a}/purge?force=true")
        api.delete(f"/projects/{pid_b}/purge?force=true")


def test_n8_case_remote_search(page, collectors, api_client, uniq):
    pass
    api = api_client
    pid = _mk_project(api, f"N8C{uniq.upper()}", f"N8套件_{uniq}")
    for i in range(55):
        _mk_case(api, pid, f"N8远程用例{i:02d}_{uniq}")
    _mk_case(api, pid, f"N8唯一搜索目标_{uniq}")
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"N8环境_{uniq}", "envType": 2, "baseUrl": f"http://env-{uniq}.local:8080"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    suite_name = f"N8套件用例选择_{uniq}"
    try:
        inject_token(page)
        # AN-5: /suites leaf retired → case workbench tab; header
        # context deep link (?projectId) replaces the retired in-page selector.
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}&tab=suites", wait_until="domcontentloaded")
        expect(page.locator('[data-test="suite-create-btn"]')).to_be_enabled(timeout=10000)
        page.locator('[data-test="suite-create-btn"]').click()
        expect(page.locator('[data-test="suite-dialog"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="suite-form-name"]').fill(suite_name)

        case_sel = page.locator('[data-test="suite-form-cases"]')

        case_sel.click()
        case_sel.locator('input').fill("N8唯一搜索目标")
        expect(
            page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"N8唯一搜索目标_{uniq}")
        ).to_be_visible(timeout=10000)
        _pick_dropdown(page, f"N8唯一搜索目标_{uniq}")
        page.keyboard.press("Escape")

        case_sel.click()
        case_sel.locator('input').fill("N8远程用例")
        expect(page.locator('[data-test="case-select-loadmore"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="case-select-loadmore"]').click()
        expect(page.locator('[data-test="case-select-loadmore"]')).to_have_count(0, timeout=10000)
        page.keyboard.press("Escape")

        env_sel = page.locator('[data-test="suite-form-envs"]')
        env_sel.click()
        _pick_dropdown(page, f"N8环境_{uniq}")
        page.keyboard.press("Escape")

 # Submit → reload persistence (?projectId and tab=suites ride the URL deep link)
        page.locator('[data-test="suite-form-submit"]').click()
        expect(page.locator(".el-message--success").last).to_be_visible(timeout=10000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="suite-table"] tbody tr').first).to_be_visible(timeout=10000)
        assert suite_name in page.locator('[data-test="suite-table"]').inner_text(), \
            "reload 后套件行缺失（用例选择未持久化）"

        assert_no_errors(collectors, "N8 用例远程搜索")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")


def test_n8_filterable(page, collectors, api_client, uniq):
    pass
    api = api_client
    pid = _mk_project(api, f"N8F{uniq.upper()}", f"N8过滤_{uniq}")
    ua = _mk_tester(api, f"n8a_{uniq}", f"N8成员甲_{uniq}")
    ub = _mk_tester(api, f"n8b_{uniq}", f"N8成员乙_{uniq}")
    r = api.post(f"/projects/{pid}/members", json={"userId": ua, "role": 1})
    assert r.status_code == 200, f"加成员甲失败: {r.text[:200]}"
    r = api.post(f"/projects/{pid}/members", json={"userId": ub, "role": 1})
    assert r.status_code == 200, f"加成员乙失败: {r.text[:200]}"
    c1 = _mk_case(api, pid, f"N8过滤用例_{uniq}")
    r = api.post(f"/cases/{c1['id']}/assign", json={"assigneeId": ua})
    assert r.status_code == 200, f"分配失败: {r.text[:200]}"
    try:
        inject_token(page)
        nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
        page.locator('[data-test="project-ctx-select"]').click()
        _pick_dropdown(page, f"N8过滤_{uniq}")
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=10000)

        f = page.locator('[data-test="case-filter-assignee"]')
        f.click()
        f.locator('input').fill(f"N8成员乙_{uniq}")
        visible_items = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible")
        expect(visible_items.filter(has_text=f"N8成员乙_{uniq}")).to_be_visible(timeout=10000)
        expect(visible_items.filter(has_text=f"N8成员甲_{uniq}")).to_have_count(0)
        _pick_dropdown(page, f"N8成员乙_{uniq}")

        expect(page.locator('[data-test="case-table"] tbody tr').filter(
            has_text=f"N8过滤用例_{uniq}")).to_have_count(0, timeout=10000)

        f.click()
        page.locator('[data-test="case-filter-assignee"] .el-select__clear, [data-test="case-filter-assignee"] .el-input__clear').first.click()
        expect(page.locator('[data-test="case-table"] tbody tr').filter(has_text=f"N8过滤用例_{uniq}")).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "N8 成员过滤")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")
        api.delete(f"/users/{ua}")
        api.delete(f"/users/{ub}")
