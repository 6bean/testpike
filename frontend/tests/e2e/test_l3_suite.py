
import httpx
import pytest
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, attach_monitors, mk_tester, add_member, nav_to

SUITE_MENU = "回归集"


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
                raise AssertionError(
                    "inject_token 注入后未进入工作台（my-project-table 不可见）——"
                    "疑似 WSL 网络层抖动致 /users/me 失败被守卫踢回登录；已重试一次仍失败"
                )
    raise AssertionError("unreachable")


def goto_suites(page, pid):
    """Suites merged into the case-workbench tab ( AN-5: /suites redirects with
 query kept); header context = deep link ?projectId (in-page selector retired)."""
    page.goto(f"{BASE_URL}/#/cases?projectId={pid}&tab=suites", wait_until="domcontentloaded")
    expect(page.locator('[data-test="suite-table"]')).to_be_visible(timeout=15000)


def select_visible_option(page, text):
    pass
    return page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{text}"):visible').first


def create_suite_dataset(api, uniq, archived=False):
    pass
    code = f"L3E{uniq[:6]}"
    r = api.post("/projects", json={"name": f"L3回归集项目{uniq}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]

    env_ids = []
    for name, etype in ((f"测试环境A{uniq[:6]}", 2), (f"预发环境B{uniq[:6]}", 3)):
        r = api.post(f"/projects/{pid}/envs", json={
            "name": name, "envType": etype, "baseUrl": f"https://t-{uniq[:6]}.tp-local.test"})
        assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
        env_ids.append(r.json()["data"])

    case_ids = []
    for i in range(3):
        status = 9 if archived and i == 2 else 3
        r = api.post("/cases", json={
            "projectId": pid, "title": f"回归用例{i}{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": status, "phase": 3,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
        })
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])

    a1, _ = mk_tester(api, uniq, prefix="l3a")
    a2, _ = mk_tester(api, uniq, prefix="l3b")
    add_member(api, pid, a1)
    add_member(api, pid, a2)
    return pid, env_ids, case_ids, [a1, a2]


def cleanup_project(api, pid):
    api.delete(f"/projects/{pid}")


def test_l3_suite_one_click_run(page, collectors, api_client, uniq):
    pass
    pid, env_ids, case_ids, assignees = create_suite_dataset(api_client, uniq)
    try:
        r = api_client.post("/suites", json={
            "projectId": pid, "name": f"发版核心回归{uniq[:6]}",
            "caseIds": case_ids, "envIds": env_ids, "defaultAssigneeIds": assignees,
            "cronExpr": "0 30 2 * * 1", "status": 1})
        assert r.json().get("code") == "0", f"建回归集失败: {r.text[:300]}"
        suite = r.json()["data"]

        inject_token(page)
        goto_suites(page, pid)
        expect(page.locator('[data-test="suite-create-btn"]')).to_be_enabled(timeout=5000)
        expect(page.locator('[data-test="suite-table"] tbody tr')).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="suite-name-link"]').first).to_contain_text(f"发版核心回归{uniq[:6]}")
        expect(page.locator('[data-test="suite-table"] tbody tr').first).to_contain_text("3")
        expect(page.locator('[data-test="suite-status-tag"]').first).to_contain_text("启用")

        page.locator('[data-test="suite-name-link"]').first.click()
        expect(page.locator('[data-test="suite-detail-drawer"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="suite-detail-drawer"]')).to_contain_text("0 30 2 * * 1")
        expect(page.locator('[data-test="suite-detail-drawer"]')).to_contain_text("启用")
        page.keyboard.press("Escape")

        page.locator('[data-test="suite-run-btn"]').first.click()
        expect(page.get_by_text("一键生成计划/批次", exact=True)).to_be_visible(timeout=5000)
        page.locator('.el-message-box__btns button:has-text("一键运行")').click()
        expect(page.get_by_text("已生成计划「", exact=False).first).to_be_visible(timeout=15000)
        expect(page.get_by_text("2 个批次").first).to_be_visible(timeout=5000)

        # reload persistence: lastRunAt shown (?projectId rides the deep link)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}&tab=suites", wait_until="domcontentloaded")
        expect(page.locator('[data-test="suite-table"]')).to_be_visible(timeout=10000)
        row = page.locator('[data-test="suite-table"] tbody tr').first
        expect(row).to_contain_text(f"发版核心回归{uniq[:6]}", timeout=10000)
        expect(row.locator('[data-test="suite-status-tag"]')).to_contain_text("启用")

        r = api_client.get(f"/plans?projectId={pid}&keyword={uniq[:6]}")
        plans = [p for p in r.json()["data"]["list"] if f"发版核心回归{uniq[:6]}" in p["name"]]
        assert len(plans) >= 1, f"一键生成后计划未找到: {r.text[:300]}"
        plan = plans[0]
        assert plan["caseCount"] == 3, f"计划用例数: {plan['caseCount']}"
        assert plan["status"] == 3, f"计划状态应执行中（已生成批次）: {plan['status']}"
        r = api_client.get(f"/plans/{plan['id']}/progress")
        assert r.json()["data"]["total"] == 3, f"进度 total: {r.json()['data']['total']}"

        assert_no_errors(collectors, "L3 回归集一键执行")
    finally:
        cleanup_project(api_client, pid)


def test_l3_suite_archived_skip_and_crud(page, collectors, api_client, uniq):
    pass
    pid, env_ids, case_ids, assignees = create_suite_dataset(api_client, uniq, archived=True)
    try:
        r = api_client.post("/suites", json={
            "projectId": pid, "name": f"归档跳过回归{uniq[:6]}",
            "caseIds": [case_ids[0], case_ids[1], case_ids[2]], "envIds": [env_ids[0]],
            "defaultAssigneeIds": [assignees[0]], "cronExpr": None, "status": 1})
        assert r.json().get("code") == "0", f"建回归集失败: {r.text[:300]}"
        suite_id = r.json()["data"]["id"]

        inject_token(page)
        goto_suites(page, pid)
        expect(page.locator('[data-test="suite-table"] tbody tr')).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="suite-table"] tbody tr').first).to_contain_text("2")

        page.locator('[data-test="suite-run-btn"]').first.click()
        expect(page.get_by_text("一键生成计划/批次", exact=True)).to_be_visible(timeout=5000)
        page.locator('.el-message-box__btns button:has-text("一键运行")').click()
        expect(page.get_by_text("自动跳过").first).to_be_visible(timeout=15000)

        r = api_client.get(f"/plans?projectId={pid}&keyword={uniq[:6]}")
        plans = [p for p in r.json()["data"]["list"] if f"归档跳过回归{uniq[:6]}" in p["name"]]
        assert len(plans) >= 1, f"计划未找到: {r.text[:300]}"
        assert plans[0]["caseCount"] == 2, f"计划用例数应=2（归档跳过）: {plans[0]['caseCount']}"

        page.locator('[data-test="suite-edit-btn"]').first.click()
        expect(page.get_by_text("编辑回归集")).to_be_visible(timeout=5000)
        page.locator('[data-test="suite-form-name"]').fill(f"归档跳过回归（改名）{uniq[:6]}")
        page.locator('[data-test="suite-form-submit"]').click()
        expect(page.get_by_text("回归集已保存")).to_be_visible(timeout=5000)

        page.goto(f"{BASE_URL}/#/cases?projectId={pid}&tab=suites", wait_until="domcontentloaded")
        expect(page.locator('[data-test="suite-table"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="suite-name-link"]').first).to_contain_text("改名", timeout=10000)

        page.locator('[data-test="suite-delete-btn"]').first.click()
        expect(page.get_by_text("删除回归集", exact=True)).to_be_visible(timeout=5000)
        page.locator('.el-message-box button:has-text("删除")').click()
        expect(page.get_by_text("回归集已删除")).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="suite-table"] tbody tr')).to_have_count(0, timeout=10000)

        assert_no_errors(collectors, "L3 归档跳过 + 编辑删除")
    finally:
        cleanup_project(api_client, pid)
