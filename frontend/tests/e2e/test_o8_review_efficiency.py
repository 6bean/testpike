
from playwright.sync_api import expect

from conftest import BASE_URL, TESTER_PASSWORD, api_login, assert_no_errors, mk_tester, nav_to


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"O8项目_{uniq}", "code": f"O8P{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uniq, title, priority=1, confidence=2):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "precondition": f"前置：已登录（{uniq}）",
        "expectedResult": f"预期：成功（{uniq}）",
        "priority": priority,
        "confidence": confidence,
        "endType": "web",
        "status": 1,
        "steps": [{"stepNo": 1, "description": "打开 /login", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_requirement(api, pid, uniq, title):
    r = api.post(f"/projects/{pid}/requirements", json={"title": f"{title}_{uniq}", "source": 3})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建需求点失败: {r.text[:300]}"
    return r.json()["data"]


def _link_case(api, req_id, case_id):
    r = api.post(f"/requirements/{req_id}/cases", json={"caseIds": [case_id]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"关联用例失败: {r.text[:300]}"


def _setup_o8_queue(api, pid, tester_id, cases):
    pass
    r = api.post(f"/projects/{pid}/members", json={"userId": tester_id, "role": 2})
    assert r.status_code == 200 and r.json().get("code") == "0", f"负责人入项失败: {r.text[:300]}"
    for c in cases:
        rr = api.post(f"/cases/{c['id']}/assign", json={"assigneeId": tester_id})
        assert rr.status_code == 200 and rr.json().get("code") == "0", f"分配失败: {rr.text[:300]}"


def _purge(api, pid, tester_id):
    pass
    api.delete(f"/projects/{pid}/purge?force=true")
    api.delete(f"/users/{tester_id}")


def _select_project(page, project_name):
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()


def _login_as(page, username):
    pass
    token = api_login(username, TESTER_PASSWORD)
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)


def _row(page, case_no):
    return page.locator('[data-test="review-table"] tbody tr.el-table__row').filter(has_text=case_no).first


def test_o8_four_keys_and_ctx_panel(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    pname = f"O8项目_{uniq}"
    c1 = _mk_case(api_client, pid, uniq, "甲", priority=0)
    c2 = _mk_case(api_client, pid, uniq, "乙", priority=1)
    c3 = _mk_case(api_client, pid, uniq, "丙", priority=2)
    req = _mk_requirement(api_client, pid, uniq, "登录需求")
    _link_case(api_client, req["id"], c1["id"])
    tester_id, _ = mk_tester(api_client, uniq, "o8")
    try:
        _setup_o8_queue(api_client, pid, tester_id, (c1, c2, c3))
        _login_as(page, f"o8tester_{uniq}")
        nav_to(page, "评审工作台", "/review", '[data-test="review-page"]', submenu="设计用例")
        _select_project(page, pname)
        expect(page.locator('[data-test="review-table"] tbody tr.el-table__row')).to_have_count(3)

        _row(page, c2["caseNo"]).click()
        expect(page.locator('[data-test="review-ctx-panel"]')).to_be_visible()
        expect(page.locator('[data-test="ctx-case-no"]')).to_contain_text(c2["caseNo"])
        expect(page.locator('[data-test="ctx-coverage-status"]')).to_contain_text("未覆盖")
        expect(page.locator('[data-test="ctx-history-empty"]')).to_be_visible()

        page.locator('[data-test="review-quick-toggle"]').click()
        expect(page.locator('[data-test="review-quick-hint"]')).to_be_visible()
        expect(page.locator('[data-test="review-quick-active"]')).to_contain_text(c1["caseNo"])
        expect(page.locator('[data-test="ctx-coverage-status"]')).to_contain_text("已覆盖")
        expect(page.locator('[data-test="review-ctx-panel"]')).to_contain_text(f"登录需求_{uniq}")
        expect(page.locator('[data-test="ctx-history-empty"]')).to_be_visible()

        page.keyboard.press("Enter")
        box = page.locator(".el-message-box")
        expect(box).to_be_visible()
        box.locator('button:has-text("通过")').click()
        expect(page.locator(".el-message").filter(has_text="已通过")).to_be_visible(timeout=5000)
        assert api_client.get(f"/cases/{c1['id']}").json()["data"]["status"] == 3
        log0 = page.locator('[data-test^="ctx-log-"]').first
        expect(log0).to_contain_text("通过", timeout=5000)
        expect(log0).to_contain_text("o8测试员", timeout=2000)

        page.keyboard.press("j")
        expect(page.locator('[data-test="ctx-case-no"]')).to_contain_text(c2["caseNo"])
        expect(page.locator('[data-test="ctx-coverage-status"]')).to_contain_text("未覆盖")
        expect(page.locator('[data-test="ctx-history-empty"]')).to_be_visible()

        page.keyboard.press("s")
        expect(page.locator('[data-test="review-quick-active"]')).to_contain_text(c3["caseNo"])
        assert api_client.get(f"/cases/{c2['id']}").json()["data"]["status"] == 1, "S 跳过不应改变用例状态"

        page.keyboard.press("x")
        dlg = page.locator('[data-test="review-reason-dialog"]')
        expect(dlg).to_be_visible()
        dlg.locator('[data-test="review-reason-input"]').fill("步骤过于简单")
        dlg.locator('[data-test="problem-type-step_inaccurate"]').click()
        dlg.locator('[data-test="review-reason-submit"]').click()
        expect(page.locator(".el-message").filter(has_text="已拒绝")).to_be_visible(timeout=5000)
        assert api_client.get(f"/cases/{c3['id']}").json()["data"]["status"] == 5
        expect(page.locator('[data-test="ctx-case-no"]')).to_contain_text(c3["caseNo"])
        expect(page.locator('[data-test="review-ctx-panel"]')).to_contain_text("拒绝", timeout=5000)
        expect(page.locator('[data-test="review-ctx-panel"]')).to_contain_text("步骤过于简单")

        page.keyboard.press("b")
        expect(page.locator(".el-message").filter(has_text="请先勾选用例")).to_be_visible(timeout=5000)

        _row(page, c2["caseNo"]).locator(".el-checkbox").click()
        page.locator('[data-test="review-batch-pass"]').click()
        box = page.locator(".el-message-box")
        expect(box).to_contain_text("通过所选用例")
        expect(box).to_contain_text("1 条")
        box.locator('button:has-text("通过所选用例")').click()
        expect(page.locator(".el-message").filter(has_text="1 条成功")).to_be_visible(timeout=8000)
        assert api_client.get(f"/cases/{c2['id']}").json()["data"]["status"] == 3

        page.reload(wait_until="domcontentloaded")
        _login_as(page, f"o8tester_{uniq}")
        nav_to(page, "评审工作台", "/review", '[data-test="review-page"]', submenu="设计用例")
        _select_project(page, pname)
        page.locator('[data-test="review-filter-status"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="待评审").first.click()
        expect(page.locator('[data-test="review-table"] tbody tr.el-table__row')).to_have_count(0)

        assert_no_errors(collectors, "O8 四键+上下文侧栏")
    finally:
        _purge(api_client, pid, tester_id)


def test_o8_batch_pass_low_confidence_hint(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    pname = f"O8项目_{uniq}"
    low = _mk_case(api_client, pid, uniq, "低置信", priority=1, confidence=3)
    plain = _mk_case(api_client, pid, uniq, "普通", priority=2, confidence=1)
    tester_id, _ = mk_tester(api_client, uniq, "o8b")
    try:
        _setup_o8_queue(api_client, pid, tester_id, (low, plain))
        _login_as(page, f"o8btester_{uniq}")
        nav_to(page, "评审工作台", "/review", '[data-test="review-page"]', submenu="设计用例")
        _select_project(page, pname)
        rows = page.locator('[data-test="review-table"] tbody tr.el-table__row')
        expect(rows).to_have_count(2)

        page.locator('[data-test="review-table"] thead .el-checkbox').click()
        batch_btn = page.locator('[data-test="review-batch-pass"]')
        expect(batch_btn).to_be_visible()
        expect(batch_btn).to_contain_text("2")
        batch_btn.click()
        box = page.locator(".el-message-box")
        expect(box).to_contain_text("2 条")
        expect(box).to_contain_text("低置信度")
        box.locator('button:has-text("通过所选用例")').click()
        expect(page.locator(".el-message").filter(has_text="2 条成功")).to_be_visible(timeout=5000)
        assert api_client.get(f"/cases/{low['id']}").json()["data"]["status"] == 3, "低置信应通过"
        assert api_client.get(f"/cases/{plain['id']}").json()["data"]["status"] == 3, "普通应通过"
        expect(page.locator('[data-test="stat-todo"]')).to_have_text("0", timeout=5000)
        expect(page.locator('[data-test="stat-done"]')).to_have_text("2")

        page.reload(wait_until="domcontentloaded")
        _login_as(page, f"o8btester_{uniq}")
        nav_to(page, "评审工作台", "/review", '[data-test="review-page"]', submenu="设计用例")
        _select_project(page, pname)
        page.locator('[data-test="review-filter-status"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="待评审").first.click()
        expect(page.locator('[data-test="review-table"] tbody tr.el-table__row')).to_have_count(0)

        assert_no_errors(collectors, "O8 批量通过低置信度")
    finally:
        _purge(api_client, pid, tester_id)
