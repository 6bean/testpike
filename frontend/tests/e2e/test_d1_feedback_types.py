
from playwright.sync_api import expect

from conftest import BASE_URL, TESTER_PASSWORD, api_login, assert_no_errors, mk_tester, nav_to


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"D1项目_{uniq}", "code": f"D1P{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uniq, title, status=1):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "precondition": f"前置：已登录（{uniq}）",
        "expectedResult": f"预期：成功（{uniq}）",
        "priority": 1,
        "confidence": 2,
        "endType": "web",
        "status": status,
        "steps": [{"stepNo": 1, "description": "打开 /login", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _setup_queue(api, pid, tester_id, cases):
    pass
    r = api.post(f"/projects/{pid}/members", json={"userId": tester_id, "role": 2})
    assert r.status_code == 200 and r.json().get("code") == "0", f"负责人入项失败: {r.text[:300]}"
    for c in cases:
        rr = api.post(f"/cases/{c['id']}/assign", json={"assigneeId": tester_id})
        assert rr.status_code == 200 and rr.json().get("code") == "0", f"分配失败: {rr.text[:300]}"


def _purge(api, pid, tester_id):
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


def _enter_workbench_quick(page, pid_name, case_no):
    pass
    nav_to(page, "评审工作台", "/review", '[data-test="review-page"]', submenu="设计用例")
    _select_project(page, pid_name)
    expect(page.locator('[data-test="review-table"] tbody tr.el-table__row').first).to_be_visible()
    page.locator('[data-test="review-quick-toggle"]').click()
    expect(page.locator('[data-test="review-quick-active"]')).to_contain_text(case_no)


def test_d1_workbench_reject_with_types_persist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    pname = f"D1项目_{uniq}"
    c1 = _mk_case(api_client, pid, uniq, "驳回带类型")
    tester_id, _ = mk_tester(api_client, uniq, "d1a")
    try:
        _setup_queue(api_client, pid, tester_id, [c1])
        _login_as(page, f"d1atester_{uniq}")
        _enter_workbench_quick(page, pname, c1["caseNo"])

        page.keyboard.press("x")
        dlg = page.locator('[data-test="review-reason-dialog"]')
        expect(dlg).to_be_visible()
        dlg.locator('[data-test="review-reason-input"]').fill(f"步骤与选择器都不对（{uniq}）")
        dlg.locator('[data-test="problem-type-step_inaccurate"]').click()
        dlg.locator('[data-test="problem-type-selector_issue"]').click()
        dlg.locator('[data-test="review-reason-submit"]').click()
        expect(page.locator(".el-message").filter(has_text="已拒绝")).to_be_visible(timeout=5000)

        r = api_client.get(f"/cases/{c1['id']}")
        assert r.json()["data"]["status"] == 5, "用例应为已拒绝(5)"
        logs = api_client.get(f"/cases/{c1['id']}/review-logs").json()["data"]
        reject_logs = [lg for lg in logs if lg["action"] == "拒绝"]
        assert reject_logs, "评审日志缺拒绝记录"
        assert set(["步骤不准确", "选择器问题"]) <= set(reject_logs[0]["feedbackTypes"]), \
            f"日志类型列不含勾选两类: {reject_logs[0]['feedbackTypes']}"

        ctx = page.locator('[data-test="review-ctx-panel"]')
        expect(ctx.locator('[data-test="ctx-log-types"]')).to_be_visible(timeout=5000)
        expect(ctx).to_contain_text("步骤不准确")
        expect(ctx).to_contain_text("选择器问题")

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="project-ctx-select"]')).to_be_visible(timeout=15000)
        _select_project(page, pname)
        _row(page, c1["caseNo"]).click()
        ctx2 = page.locator('[data-test="review-ctx-panel"]')
        expect(ctx2.locator('[data-test="ctx-log-types"]')).to_be_visible(timeout=5000)
        expect(ctx2).to_contain_text("步骤不准确")

        assert_no_errors(collectors, "D1 拒绝带类型持久化")
    finally:
        _purge(api_client, pid, tester_id)


def test_d1_gate_blocks_missing_types(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    pname = f"D1项目_{uniq}"
    c1 = _mk_case(api_client, pid, uniq, "缺类型被拦")
    tester_id, _ = mk_tester(api_client, uniq, "d1b")
    try:
        _setup_queue(api_client, pid, tester_id, [c1])
        _login_as(page, f"d1btester_{uniq}")
        _enter_workbench_quick(page, pname, c1["caseNo"])

        page.keyboard.press("r")
        dlg = page.locator('[data-test="review-reason-dialog"]')
        expect(dlg).to_be_visible()
        dlg.locator('[data-test="review-reason-input"]').fill("缺少异常场景")
        dlg.locator('[data-test="review-reason-submit"]').click()
        expect(page.locator(".el-message").filter(has_text="请选择问题类型")).to_be_visible(timeout=5000)
        expect(dlg).to_be_visible()
        st = api_client.get(f"/cases/{c1['id']}").json()["data"]["status"]
        assert st == 1, f"未勾类型提交不应改变状态（当前 {st}）"

        dlg.locator('[data-test="problem-type-logic_missing"]').click()
        dlg.locator('[data-test="review-reason-submit"]').click()
        expect(page.locator(".el-message").filter(has_text="已需修改")).to_be_visible(timeout=5000)
        assert api_client.get(f"/cases/{c1['id']}").json()["data"]["status"] == 4

        assert_no_errors(collectors, "D1 前端闸拦未选类型")
    finally:
        _purge(api_client, pid, tester_id)


def test_d1_pass_skips_type_dialog(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    pname = f"D1项目_{uniq}"
    c1 = _mk_case(api_client, pid, uniq, "通过不带类型")
    tester_id, _ = mk_tester(api_client, uniq, "d1c")
    try:
        _setup_queue(api_client, pid, tester_id, [c1])
        _login_as(page, f"d1ctester_{uniq}")
        _enter_workbench_quick(page, pname, c1["caseNo"])

        page.keyboard.press("Enter")
        box = page.locator(".el-message-box")
        expect(box).to_be_visible()
        expect(page.locator('[data-test="review-reason-dialog"]')).to_have_count(0)
        box.locator('button:has-text("通过")').click()
        expect(page.locator(".el-message").filter(has_text="已通过")).to_be_visible(timeout=5000)
        assert api_client.get(f"/cases/{c1['id']}").json()["data"]["status"] == 3

        logs = api_client.get(f"/cases/{c1['id']}/review-logs").json()["data"]
        pass_logs = [lg for lg in logs if lg["action"] == "通过"]
        assert pass_logs and not pass_logs[0].get("feedbackTypes"), \
            f"通过日志不应带类型: {pass_logs[0].get('feedbackTypes') if pass_logs else None}"

        assert_no_errors(collectors, "D1 通过不弹类型框")
    finally:
        _purge(api_client, pid, tester_id)


def test_d1_api_reject_requires_types_400(api_client):
    pass
    import uuid as _uuid
    suffix = _uuid.uuid4().hex[:8]
    r = api_client.post("/projects", json={"name": f"D1API项目_{suffix}", "code": f"D1A{suffix[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0"
    pid = r.json()["data"]
    try:
        c = _mk_case(api_client, pid, suffix, "API缺类型")
        ver = api_client.get(f"/cases/{c['id']}").json()["data"]["version"]
        rr = api_client.post(f"/cases/{c['id']}/review",
                             json={"action": "reject", "version": ver, "remark": "缺类型提交"})
        assert rr.status_code == 400, f"缺类型拒绝应 400，实得 {rr.status_code}: {rr.text[:200]}"
        body = rr.json()
        assert str(body.get("code")) in ("40001", "400"), f"业务码应为参数校验类: {body}"
        assert "请选择问题类型" in (body.get("msg") or ""), f"人话点名缺失: {body}"
        assert api_client.get(f"/cases/{c['id']}").json()["data"]["status"] == 1
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
