
import httpx
import pytest
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, attach_monitors, mk_tester, add_member, nav_to, pick_project_in_header

DEFECT_MENU = "缺陷库"


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


def goto_defects(page):
    nav_to(page, DEFECT_MENU, "/defects", '[data-test="defect-list-page"]', submenu="结果与缺陷")


def select_visible_option(page, text):
    pass
    return page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{text}"):visible').first


def create_defect_dataset(api, uniq):
    pass
    code = f"L2E{uniq[:6]}"
    r = api.post("/projects", json={"name": f"L2缺陷项目{uniq}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]

    case_ids = []
    for title in (f"登录会话缺陷用例{uniq[:6]}", f"支付金额缺陷用例{uniq[:6]}"):
        r = api.post("/cases", json={
            "projectId": pid, "title": title, "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 7, "phase": 1,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
        })
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])

    fixer, _ = mk_tester(api, uniq, prefix="l2f")
    reporter, _ = mk_tester(api, uniq, prefix="l2r")
    add_member(api, pid, fixer)
    add_member(api, pid, reporter)
    return pid, case_ids, fixer, reporter


def cleanup_project(api, pid):
    api.delete(f"/projects/{pid}")


def test_l2_defect_full_flow(page, collectors, api_client, uniq):
    pass
    pid, case_ids, fixer, reporter = create_defect_dataset(api_client, uniq)
    try:
        inject_token(page)
        goto_defects(page)
        expect(page.locator('[data-test="defect-list-page"]')).to_be_visible(timeout=15000)

        # Pick the project (fix at close-out: the per-page selector was retired
        # in the IA pass — the header context is the single switch).
        pick_project_in_header(page, f"L2缺陷项目{uniq}")
        expect(page.locator('[data-test="defect-create-btn"]')).to_be_enabled(timeout=5000)

        page.locator('[data-test="defect-create-btn"]').click()
        expect(page.locator('[data-test="defect-form-title"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="defect-form-title"]').fill(f"登录后会话丢失{uniq[:6]}")
        page.locator('[data-test="defect-form-severity"]').click()
        select_visible_option(page, "P1（严重）").click()
        page.locator('[data-test="defect-form-assignee"]').click()
        select_visible_option(page, f"l2f测试员{uniq[:6]}").click()
        page.locator('[data-test="defect-form-case"]').click()
        select_visible_option(page, f"登录会话缺陷用例{uniq[:6]}").click()
        page.locator('[data-test="defect-form-external"]').fill("JIRA-888")
        page.locator('[data-test="defect-form-submit"]').click()

        expect(page.locator('[data-test="defect-table"] tbody tr')).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="defect-status-tag"]').first).to_contain_text("新建")
        expect(page.locator('[data-test="defect-severity-tag"]').first).to_contain_text("P1")

        page.goto(f"{BASE_URL}/#/defects", wait_until="domcontentloaded")
        expect(page.locator('[data-test="defect-table"]')).to_be_visible(timeout=10000)
        pick_project_in_header(page, f"L2缺陷项目{uniq}")
        expect(page.locator('[data-test="defect-table"] tbody tr')).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="defect-title-link"]').first).to_contain_text(f"登录后会话丢失{uniq[:6]}")

        page.locator('[data-test="defect-detail-btn"]').first.click()
        expect(page.locator('[data-test="defect-detail-drawer"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="defect-detail-status"]')).to_contain_text("新建")
        expect(page.locator('[data-test="defect-detail-title"]')).to_contain_text(f"登录后会话丢失{uniq[:6]}")
        page.keyboard.press("Escape")

        for target in ("处理中", "已修复", "验证中"):
            page.locator('[data-test="defect-transition-btn"]').first.click()
            expect(page.locator('[data-test="defect-transition-select"]')).to_be_visible(timeout=5000)
            page.locator('[data-test="defect-transition-select"]').click()
            select_visible_option(page, target).click()
            page.locator('[data-test="defect-transition-submit"]').click()
            expect(page.locator('[data-test="defect-status-tag"]').first).to_contain_text(target, timeout=10000)

        page.locator('[data-test="defect-transition-btn"]').first.click()
        page.locator('[data-test="defect-transition-select"]').click()
        select_visible_option(page, "已关闭").click()
        page.locator('[data-test="defect-transition-submit"]').click()
        expect(page.get_by_text("关闭/重新打开必须填写原因")).to_be_visible(timeout=5000)
        page.locator('[data-test="defect-transition-reason"]').fill("验证通过，会话已修复")
        page.locator('[data-test="defect-transition-submit"]').click()
        expect(page.locator('[data-test="defect-status-tag"]').first).to_contain_text("已关闭", timeout=10000)
        expect(page.locator('[data-test="defect-edit-btn"]')).to_have_count(0)
        expect(page.locator('[data-test="defect-transition-btn"]')).to_have_count(0)

        page.locator('[data-test="defect-create-btn"]').click()
        expect(page.locator('[data-test="defect-form-title"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="defect-form-title"]').fill(f"支付金额算错{uniq[:6]}")
        page.locator('[data-test="defect-form-severity"]').click()
        select_visible_option(page, "P0（致命）").click()
        page.locator('[data-test="defect-form-assignee"]').click()
        select_visible_option(page, f"l2f测试员{uniq[:6]}").click()
        page.locator('[data-test="defect-form-case"]').click()
        select_visible_option(page, f"支付金额缺陷用例{uniq[:6]}").click()
        page.locator('[data-test="defect-form-submit"]').click()
        expect(page.locator('[data-test="defect-table"] tbody tr')).to_have_count(2, timeout=10000)

        row = page.locator('[data-test="defect-table"] tbody tr').first
        expect(row.locator('[data-test="defect-title-link"]')).to_contain_text(f"支付金额算错{uniq[:6]}")
        expect(row.locator('[data-test="defect-severity-tag"]')).to_contain_text("P0")

        for target in ("处理中", "已修复", "验证中"):
            row.locator('[data-test="defect-transition-btn"]').click()
            page.locator('[data-test="defect-transition-select"]').click()
            select_visible_option(page, target).click()
            page.locator('[data-test="defect-transition-submit"]').click()
            expect(row.locator('[data-test="defect-status-tag"]')).to_contain_text(target, timeout=10000)

        row.locator('[data-test="defect-transition-btn"]').click()
        page.locator('[data-test="defect-transition-select"]').click()
        select_visible_option(page, "重新打开").click()
        page.locator('[data-test="defect-transition-reason"]').fill("复现步骤 2 仍失败")
        page.locator('[data-test="defect-transition-submit"]').click()
        expect(row.locator('[data-test="defect-status-tag"]')).to_contain_text("重新打开", timeout=10000)

        row.locator('[data-test="defect-edit-btn"]').click()
        expect(page.get_by_text("编辑缺陷")).to_be_visible(timeout=5000)
        page.locator('[data-test="defect-edit-title"]').fill(f"支付金额算错（降级）{uniq[:6]}")
        page.locator('[data-test="defect-edit-severity"]').click()
        select_visible_option(page, "P2（一般）").click()
        page.locator('[data-test="defect-edit-submit"]').click()
        expect(page.get_by_text("缺陷已保存")).to_be_visible(timeout=5000)

        page.goto(f"{BASE_URL}/#/defects", wait_until="domcontentloaded")
        expect(page.locator('[data-test="defect-table"]')).to_be_visible(timeout=10000)
        pick_project_in_header(page, f"L2缺陷项目{uniq}")
        row2 = page.locator('[data-test="defect-table"] tbody tr').first
        expect(row2.locator('[data-test="defect-title-link"]')).to_contain_text("降级", timeout=10000)
        expect(row2.locator('[data-test="defect-severity-tag"]')).to_contain_text("P2")

        assert_no_errors(collectors, "L2 缺陷全闭环 + 编辑持久化")
    finally:
        cleanup_project(api_client, pid)


def test_l2_defect_filter_and_case_summary(page, collectors, api_client, uniq):
    pass
    pid, case_ids, fixer, reporter = create_defect_dataset(api_client, uniq)
    try:
        r = api_client.post("/defect-library", json={
            "projectId": pid, "caseId": case_ids[0], "title": f"登录会话丢失{uniq[:6]}",
            "severity": 2, "assigneeId": fixer,
        })
        assert r.json().get("code") == "0", f"建缺陷1失败: {r.text[:300]}"
        d1 = r.json()["data"]
        r = api_client.post("/defect-library", json={
            "projectId": pid, "caseId": case_ids[1], "title": f"支付金额算错{uniq[:6]}",
            "severity": 1, "assigneeId": fixer,
        })
        assert r.json().get("code") == "0", f"建缺陷2失败: {r.text[:300]}"
        d2 = r.json()["data"]
        api_client.post(f"/defect-library/{d2['id']}/transition", json={"toStatus": 2, "version": d2["version"]})

        inject_token(page)
        goto_defects(page)
        pick_project_in_header(page, f"L2缺陷项目{uniq}")
        expect(page.locator('[data-test="defect-table"] tbody tr')).to_have_count(2, timeout=10000)

        page.locator('[data-test="defect-filter-severity"]').click()
        select_visible_option(page, "P0（致命）").click()
        expect(page.locator('[data-test="defect-table"] tbody tr')).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="defect-title-link"]').first).to_contain_text(f"支付金额算错{uniq[:6]}")

        page.locator('[data-test="defect-filter-keyword"]').fill(f"金额算错{uniq[:6]}")
        page.locator('[data-test="defect-filter-keyword"]').press("Enter")
        expect(page.locator('[data-test="defect-table"] tbody tr')).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="defect-title-link"]').first).to_contain_text(f"支付金额算错{uniq[:6]}")

        page.locator('[data-test="defect-filter-keyword"]').fill("")
        page.locator('[data-test="defect-filter-keyword"]').press("Enter")
        page.locator('[data-test="defect-filter-severity"]').hover()
        page.locator('[data-test="defect-filter-severity"] .el-select__clear').click()
        expect(page.locator('[data-test="defect-table"] tbody tr')).to_have_count(2, timeout=10000)

        page.locator('[data-test="defect-filter-status"]').click()
        select_visible_option(page, "新建").click()
        expect(page.locator('[data-test="defect-table"] tbody tr')).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="defect-title-link"]').first).to_contain_text(f"登录会话丢失{uniq[:6]}")

        page.goto(f"{BASE_URL}/#/cases/{case_ids[1]}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="builtin-defect-summary"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="builtin-defect-summary"]')).to_contain_text("内置缺陷库关联 1 条")
        expect(page.locator('[data-test="builtin-defect-open-tag"]')).to_contain_text("未关闭 1 条")

        page.goto(f"{BASE_URL}/#/cases/{case_ids[0]}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="builtin-defect-summary"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="builtin-defect-open-tag"]')).to_contain_text("未关闭 1 条")

        assert_no_errors(collectors, "L2 缺陷筛选 + 用例摘要")
    finally:
        cleanup_project(api_client, pid)
