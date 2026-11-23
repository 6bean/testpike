
import httpx
import pytest
import subprocess
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, attach_monitors, mk_tester, add_member, nav_to, row_cmd

CORE_BASE = "http://localhost:8083"

PLAN_MENU = "执行计划"


def goto_plans(page):
    pass
    nav_to(page, PLAN_MENU, "/plans", '[data-test="plan-table"]', submenu="执行测试")


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


def create_plan_dataset(api, uniq):
    pass
    code = f"L1E{uniq[:6]}"
    r = api.post("/projects", json={"name": f"L1计划项目{uniq}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]

    env_ids = []
    for name, etype in ((f"测试环境{uniq}", 2), (f"预发环境{uniq}", 3)):
        r = api.post(f"/projects/{pid}/envs", json={
            "name": name, "envType": etype,
            "baseUrl": f"https://{name}.tp-local.test",
        })
        assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
        env_ids.append(r.json()["data"])

    case_ids = []
    for title, phase, prio in (("登录冒烟L1", 1, 0), ("支付下单L1", 2, 1), ("优惠券回归L1", 3, 1)):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"{title}{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": prio, "confidence": 2,
            "endType": "web", "status": 3, "phase": phase,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
        })
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])

    tester_id, _ = mk_tester(api, uniq, prefix="l1")
    add_member(api, pid, tester_id)

    subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc",
         f"UPDATE tp_test_case SET last_exec_result = 1 WHERE id = {case_ids[0]}"],
        check=True, capture_output=True, text=True)
    for cid in case_ids:
        r = httpx.post(f"{CORE_BASE}/internal/scripts", json={
            "caseId": cid, "schemaVersion": "v2",
            "actionJson": f'{{"caseId": {cid}, "setup": [], "steps": []}}', "generatedBy": 1,
        }, timeout=10)
        assert r.status_code == 200 and r.json().get("code") == "0", f"脚本落库失败: {r.text[:300]}"
    return pid, env_ids, case_ids, tester_id


def cleanup_project(api, pid):
    api.delete(f"/projects/{pid}")


def select_visible_option(page, text):
    pass
    return page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{text}"):visible').first


def test_l1_plan_wizard_create_reload(page, collectors, api_client, uniq):
    pass
    pid, env_ids, case_ids, tester_id = create_plan_dataset(api_client, uniq)
    try:
        inject_token(page)
        # Header global context : the in-page project selector is retired —
        # deep-link ?projectId lands the list on this project directly.
        page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-list-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="plan-create-btn"]')).to_be_enabled(timeout=5000)

        page.locator('[data-test="plan-create-btn"]').click()
        expect(page.get_by_text("新建执行计划")).to_be_visible(timeout=5000)

        page.locator('[data-test="plan-wizard-name"]').fill(f"回归计划{uniq}")
        page.locator('[data-test="plan-wizard-version"]').fill("v2.0.0")
        page.locator('[data-test="plan-wizard-next"]').click()

        expect(page.locator('[data-test="plan-wizard-envs"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="plan-wizard-envs"]').click()
        select_visible_option(page, f"测试环境{uniq}").click()
        select_visible_option(page, f"预发环境{uniq}").click()
        page.keyboard.press("Escape")
        page.locator('[data-test="plan-wizard-phases"]').click()
        for ph in ("冒烟", "功能", "回归"):
            select_visible_option(page, ph).click()
        page.keyboard.press("Escape")
        page.locator('[data-test="plan-wizard-priorities"]').click()
        for pr in ("P0", "P1"):
            select_visible_option(page, pr).click()
        page.keyboard.press("Escape")
        page.locator('[data-test="plan-wizard-next"]').click()

        expect(page.locator('[data-test="plan-wizard-smoke"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="plan-wizard-submit"]').click()

        expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="plan-detail-name"]')).to_contain_text(f"回归计划{uniq}")
        expect(page.locator('[data-test="plan-detail-casecount"]')).to_contain_text("3")
        expect(page.locator('[data-test="plan-case-table"] tbody tr')).to_have_count(3)
        expect(page.locator('[data-test="plan-detail-status"]')).to_contain_text("草稿")

        # reload persistence: return to the list via the deep link (?projectId) →
        # plan still present → re-enter the detail page.
        page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-table"] tbody tr')).to_have_count(1, timeout=10000)
        page.locator('[data-test="plan-name-link"]').first.click()
        expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="plan-case-table"] tbody tr')).to_have_count(3)

        assert_no_errors(collectors, "L1 计划向导创建 + reload 持久化")
    finally:
        cleanup_project(api_client, pid)


def test_l1_plan_assign_publish_genbatch_progress(page, collectors, api_client, uniq):
    pass
    pid, env_ids, case_ids, tester_id = create_plan_dataset(api_client, uniq)
    try:
        r = api_client.post("/plans", json={
            "projectId": pid, "name": f"执行计划{uniq}", "targetVersion": "v2.0.0",
            "envIds": env_ids,
            "caseScope": {"phases": [1, 2, 3], "priorities": [0, 1], "keyword": None, "tagIds": []},
            "entryCriteria": {"requireSmokePass": True},
            "exitCriteria": {"minPassRate": 0.95, "noOpenDefects": True},
            "planStart": "2026-09-01", "planEnd": "2026-09-10",
        })
        assert r.json().get("code") == "0", f"API 建计划失败: {r.text[:300]}"
        plan_id = r.json()["data"]["id"]

        inject_token(page)
        # Header global context : deep-link ?projectId lands the list here.
        page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-table"] tbody tr')).to_have_count(1, timeout=10000)

        page.locator('[data-test="plan-name-link"]').first.click()
        expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="plan-case-table"] tbody tr')).to_have_count(3)

        page.locator('[data-test="plan-detail-assign"]').click()
        expect(page.locator('[data-test="plan-assign-table"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="plan-assign-select"]').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible').filter(has_text="l1").first.click()
        row_checkboxes = page.locator('[data-test="plan-assign-table"] .el-table__body-wrapper .el-checkbox')
        for i in range(3):
            row_checkboxes.nth(i).click()
        page.locator('[data-test="plan-assign-submit"]').click()
        expect(page.get_by_text("已分配 3 条用例")).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="plan-case-table"] tbody tr').first).to_contain_text(f"l1测试员{uniq[:6]}")

        page.locator('[data-test="plan-detail-publish"]').click()
        expect(page.get_by_text("确认发布计划")).to_be_visible(timeout=5000)
        page.locator(".el-message-box__btns button:has-text('确定')").click()
        expect(page.locator('[data-test="plan-detail-status"]')).to_contain_text("已发布", timeout=8000)

        page.locator('[data-test="plan-detail-genbatch"]').click()
        expect(page.get_by_text("确认按环境为计划")).to_be_visible(timeout=5000)
        page.locator(".el-message-box__btns button:has-text('确定')").click()
        expect(page.get_by_text("已按 2 个环境生成 2 个批次，共 3 个执行任务")).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="plan-detail-status"]')).to_contain_text("执行中", timeout=8000)

        expect(page.locator('[data-test="plan-progress-card"]')).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="plan-progress-summary"]')).to_contain_text("3")
        expect(page.locator('[data-test="plan-case-table"] th:has-text("批次任务")').first).to_be_visible()
        first_row = page.locator('[data-test="plan-case-table"] tbody tr').first
        expect(first_row.locator("td").nth(5)).not_to_have_text("-")

        page.goto(f"{BASE_URL}/#/plans/{plan_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-detail-status"]')).to_contain_text("执行中", timeout=10000)

        assert_no_errors(collectors, "L1 分配/发布/生成批次/进度")
    finally:
        cleanup_project(api_client, pid)


def test_l1_plan_delete_draft_and_cancel(page, collectors, api_client, uniq):
    pass
    pid, env_ids, case_ids, tester_id = create_plan_dataset(api_client, uniq)
    try:
        r = api_client.post("/plans", json={
            "projectId": pid, "name": f"待删草稿{uniq}", "envIds": [env_ids[0]],
            "caseScope": {"phases": [2], "priorities": [1], "keyword": None, "tagIds": []},
            "entryCriteria": {"requireSmokePass": True},
            "exitCriteria": {"minPassRate": 0.95, "noOpenDefects": True},
        })
        assert r.json().get("code") == "0", f"建草稿失败: {r.text[:300]}"
        draft_id = r.json()["data"]["id"]
        r = api_client.post("/plans", json={
            "projectId": pid, "name": f"待取消计划{uniq}", "envIds": [env_ids[0]],
            "caseScope": {"phases": [2], "priorities": [1], "keyword": None, "tagIds": []},
            "entryCriteria": {"requireSmokePass": True},
            "exitCriteria": {"minPassRate": 0.95, "noOpenDefects": True},
        })
        plan_id = r.json()["data"]["id"]

        r = api_client.get(f"/plans/{plan_id}/cases")
        assert r.json().get("code") == "0", f"查计划用例失败: {r.text[:300]}"
        plan_case_ids = [c["caseId"] for c in r.json()["data"]]
        assert len(plan_case_ids) >= 1, "计划内应至少拉入 1 条用例"
        r = api_client.post(f"/plans/{plan_id}/assign", json={"caseIds": plan_case_ids, "assigneeId": tester_id})
        assert r.json().get("code") == "0", f"发布前分配执行人失败: {r.text[:300]}"

        inject_token(page)
        # Header global context : deep-link ?projectId lands the list here.
        page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-table"] tbody tr')).to_have_count(2, timeout=10000)

        draft_row = page.locator(f'[data-test="plan-table"] tbody tr:has-text("待删草稿{uniq}")')
        row_cmd(page, draft_row.first, "delete")
        expect(page.get_by_text("计划已删除")).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="plan-table"] tbody tr')).to_have_count(1, timeout=8000)

        cancel_row = page.locator(f'[data-test="plan-table"] tbody tr:has-text("待取消计划{uniq}")')
        row_cmd(page, cancel_row.first, "publish")
        expect(page.get_by_text("确认发布计划")).to_be_visible(timeout=5000)
        page.locator(".el-message-box__btns button:has-text('确定')").click()
        expect(cancel_row.locator('[data-test="plan-status-tag"]')).to_contain_text("已发布", timeout=8000)

        cancel_row.locator('[data-test="plan-name-link"]').click()
        expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="plan-detail-cancel"]').click()
        expect(page.get_by_text("确认取消计划")).to_be_visible(timeout=5000)
        page.locator(".el-message-box__btns button:has-text('确定')").click()
        expect(page.locator('[data-test="plan-detail-status"]')).to_contain_text("已取消", timeout=8000)

        page.goto(f"{BASE_URL}/#/plans/{plan_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-detail-status"]')).to_contain_text("已取消", timeout=10000)

        assert_no_errors(collectors, "L1 草稿删除 + 发布后取消")
    finally:
        cleanup_project(api_client, pid)
