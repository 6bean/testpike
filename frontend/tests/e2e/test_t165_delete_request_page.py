
from playwright.sync_api import expect

from conftest import assert_no_errors, nav_to


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"删审项目_{uniq}", "code": f"DR{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"], f"删审项目_{uniq}"


def _mk_case(api, pid, uniq, title):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "priority": 0,
        "confidence": 2,
        "endType": "web",
        "steps": [{"stepNo": 1, "description": "打开页面 /login", "isAssert": 0}],
    })
    assert r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _mk_delete_request(api, pid, case_id, reason):
    pass
    r = api.post("/delete-requests",
                 json={"projectId": pid, "targetType": 1, "targetId": case_id, "reason": reason})
    body = r.json()
    assert r.status_code == 200 and body.get("code") == "0", f"建删除申请失败: {r.text[:300]}"
    return body["data"]


def _select_project(page, project_name):
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()


def test_t165_delete_request_page_render_and_empty(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    page.reload(wait_until="domcontentloaded")
    nav_to(page, "删除审批", "/delete-requests", '[data-test="delete-request-page"]', submenu="系统管理")
    expect(page.locator('[data-test="delete-request-page"]')).to_be_visible()
    table_or_empty = page.locator('[data-test="delreq-table"], [data-test="delreq-empty-no-project"]')
    expect(table_or_empty.first).to_be_visible(timeout=10000)
    assert_no_errors(collectors, "T165 删除审批页渲染")


def test_t165_approve_full_chain(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, uniq, "删审批准用例")
    req = _mk_delete_request(api_client, pid, case["id"], f"E2E 批准链测试_{uniq}")
    assert req["status"] == 2 and req["statusLabel"] == "待审批"

    try:
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "删除审批", "/delete-requests", '[data-test="delete-request-page"]', submenu="系统管理")
        _select_project(page, pname)

        row = page.locator(f'[data-test="delreq-table"] tbody tr.el-table__row:has-text("E2E 批准链测试_{uniq}")')
        expect(row).to_have_count(1, timeout=5000)
        expect(row).to_contain_text("待审批")

        row.locator('[data-test="ra-approve"]').click()
        expect(page.locator(".el-message-box")).to_be_visible()
        page.locator('.el-message-box button:has-text("批准")').click()
        expect(page.locator(".el-message").filter(has_text="已批准")).to_be_visible(timeout=5000)

        page.reload(wait_until="domcontentloaded")
        nav_to(page, "删除审批", "/delete-requests", '[data-test="delete-request-page"]', submenu="系统管理")
        _select_project(page, pname)
        page.locator('[data-test="delreq-status-filter"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="已批准").first.click()
        row = page.locator(f'[data-test="delreq-table"] tbody tr.el-table__row:has-text("E2E 批准链测试_{uniq}")')
        expect(row).to_have_count(1, timeout=5000)
        expect(row).to_contain_text("已批准")

        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_case_project(page, pname)
        page.locator('[data-test="case-recycle-open"]').click()
        drawer = page.locator('[data-test="case-recycle-drawer"]')
        expect(drawer).to_be_visible()
        rrow = drawer.locator(f'[data-test="recycle-table"] tbody tr.el-table__row:has-text("{case["title"]}")')
        expect(rrow).to_have_count(1, timeout=5000)

        assert_no_errors(collectors, "T165 approve 全链")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_t165_reject_zero_side_effect(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, uniq, "删审拒绝用例")
    req = _mk_delete_request(api_client, pid, case["id"], f"E2E 拒绝链测试_{uniq}")

    try:
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "删除审批", "/delete-requests", '[data-test="delete-request-page"]', submenu="系统管理")
        _select_project(page, pname)

        row = page.locator(f'[data-test="delreq-table"] tbody tr.el-table__row:has-text("E2E 拒绝链测试_{uniq}")')
        expect(row).to_have_count(1, timeout=5000)

        row.locator('[data-test="ra-reject"]').click()
        dlg = page.locator(".el-message-box")
        expect(dlg).to_be_visible()
        dlg.locator('button:has-text("拒绝")').click()
        expect(dlg.locator(".el-message-box__errormsg")).to_contain_text("不能为空", timeout=3000)

        dlg.locator("input, textarea").first.fill(f"仍在追溯链_{uniq}")
        dlg.locator('button:has-text("拒绝")').click()
        expect(page.locator(".el-message").filter(has_text="已拒绝")).to_be_visible(timeout=5000)

        page.reload(wait_until="domcontentloaded")
        nav_to(page, "删除审批", "/delete-requests", '[data-test="delete-request-page"]', submenu="系统管理")
        _select_project(page, pname)
        page.locator('[data-test="delreq-status-filter"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="已拒绝").first.click()
        row = page.locator(f'[data-test="delreq-table"] tbody tr.el-table__row:has-text("E2E 拒绝链测试_{uniq}")')
        expect(row).to_have_count(1, timeout=5000)
        expect(row).to_contain_text("已拒绝")
        expect(row).to_contain_text(f"仍在追溯链_{uniq}")

        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        _select_case_project(page, pname)
        expect(page.locator('[data-test="case-table"]')).to_contain_text(case["title"])

        assert_no_errors(collectors, "T165 reject 零副作用")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def _select_case_project(page, project_name):
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
