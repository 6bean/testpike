
import re
import time

import pytest
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, inject_token, nav_to, row_cmd



def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"N9E2E_{uniq}", "code": f"N9E{uniq.upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, title, status=1):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "预期",
        "priority": 2, "confidence": 2, "endType": "web", "phase": 1, "source": 2, "status": status,
        "steps": [{"stepNo": 1, "description": "步骤一", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _rename_case(api, cid, new_title, version, tries=8):
    pass
    for _ in range(tries):
        r = api.put(f"/cases/{cid}", json={"title": new_title, "version": version})
        if r.status_code == 200 and r.json().get("code") == "0":
            return
        g = api.get(f"/cases/{cid}")
        version = g.json()["data"]["version"]
        time.sleep(0.3)
    pytest.fail(f"修改用例标题始终失败（version 冲突未收敛）: {r.text[:300]}")


def _nav_cases(page, tag):
    pass
    nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
    page.locator("[data-test=project-ctx-select]").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"N9E2E_{tag}").first.click()
    rows_or_empty = page.locator('[data-test="case-table"] tbody tr, [data-test="case-empty"]').first
    expect(rows_or_empty).to_be_visible(timeout=10000)


def _visible_dialog(page, test_id):
    return page.locator(f'[data-test="{test_id}"]:visible')



def test_n9_back_preserves_filter_state(page, collectors, api_client, uniq):
    api = api_client
    tag = uniq
    pid = _mk_project(api, tag)
    try:
        c1 = _mk_case(api, pid, f"N9返回保留甲_{tag}")
        c2 = _mk_case(api, pid, f"N9返回保留乙_{tag}")
        inject_token(page)
        _nav_cases(page, tag)

        page.locator('[data-test="case-filter-keyword"]').fill("N9返回保留甲")
        page.locator('[data-test="case-search"]').click()
        rows = page.locator('[data-test="case-table"] tbody tr')
        expect(rows.filter(has_text=f"N9返回保留甲_{tag}")).to_have_count(1, timeout=10000)
        expect(rows).to_have_count(1, timeout=10000)

        row_cmd(page, page.locator('[data-test="case-table"] tbody tr').first, "detail")
        expect(page.locator('[data-test="detail-back"]')).to_be_visible(timeout=10000)

        _rename_case(api, c1["id"], f"N9返回保留甲改_{tag}", c1["version"])

        with page.expect_response(
            lambda r: "/api/v1/cases" in r.url and r.request.method == "GET", timeout=10000
        ):
            page.locator('[data-test="detail-back"]').click()

        expect(page.locator('[data-test="case-filter-keyword"]')).to_have_value(
            "N9返回保留甲", timeout=10000
        )
        expect(rows.filter(has_text=f"N9返回保留甲改_{tag}")).to_have_count(1, timeout=10000)
        expect(rows).to_have_count(1, timeout=10000)

        assert_no_errors(collectors, "N9 返回保留筛选")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")



def test_n9_reload_preserves_project(page, collectors, api_client, uniq):
    api = api_client
    tag = uniq
    pid_a = _mk_project(api, f"A{tag}")
    pid_b = _mk_project(api, f"B{tag}")
    try:
        inject_token(page)
        _nav_cases(page, f"B{tag}")
        expect(page.locator('[data-test="project-ctx-select"]')).to_contain_text(
            f"N9E2E_B{tag}", timeout=10000
        )
        expect(page).to_have_url(re.compile(rf"projectId={pid_b}"), timeout=5000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-table"], [data-test="case-empty"]').first).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="project-ctx-select"]')).to_contain_text(
            f"N9E2E_B{tag}", timeout=10000
        )

        assert_no_errors(collectors, "N9 reload 保留项目")
    finally:
        api.delete(f"/projects/{pid_a}/purge?force=true")
        api.delete(f"/projects/{pid_b}/purge?force=true")



def test_n9_browser_back_preserves(page, collectors, api_client, uniq):
    api = api_client
    tag = uniq
    pid = _mk_project(api, tag)
    try:
        _mk_case(api, pid, f"N9浏览器返回_{tag}")
        inject_token(page)
        _nav_cases(page, tag)

        page.locator('[data-test="case-filter-keyword"]').fill("N9浏览器返回")
        page.locator('[data-test="case-search"]').click()
        expect(page.locator('[data-test="case-table"] tbody tr')).to_have_count(1, timeout=10000)

        row_cmd(page, page.locator('[data-test="case-table"] tbody tr').first, "detail")
        expect(page.locator('[data-test="detail-back"]')).to_be_visible(timeout=10000)

        page.go_back()
        expect(page.locator('[data-test="case-filter-keyword"]')).to_have_value(
            "N9浏览器返回", timeout=10000
        )
        expect(page.locator('[data-test="case-table"] tbody tr')).to_have_count(1, timeout=10000)

        assert_no_errors(collectors, "N9 浏览器返回")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")



def test_n9_case_draft_preserved(page, collectors, api_client, uniq):
    api = api_client
    tag = uniq
    pid = _mk_project(api, tag)
    try:
        inject_token(page)
        _nav_cases(page, tag)

        page.locator('[data-test="case-create"]').click()
        dlg = _visible_dialog(page, "case-create-dialog")
        expect(dlg).to_be_visible(timeout=10000)
        dlg.locator('[data-test="case-create-title"]').fill(f"N9草稿甲_{tag}")
        dlg.locator('.el-dialog__footer button:has-text("取消")').click()
        expect(_visible_dialog(page, "case-create-dialog")).to_have_count(0, timeout=5000)

        page.locator('[data-test="case-create"]').click()
        dlg = _visible_dialog(page, "case-create-dialog")
        expect(dlg).to_be_visible(timeout=10000)
        expect(dlg.locator('[data-test="case-create-title"]')).to_have_value(
            f"N9草稿甲_{tag}", timeout=5000
        )

        dlg.locator('[data-test="case-create-submit"]').click()
        expect(page.locator(".el-message--success").last).to_be_visible(timeout=10000)
        expect(_visible_dialog(page, "case-create-dialog")).to_have_count(0, timeout=5000)
        expect(page.locator('[data-test="case-table"] tbody tr').filter(has_text=f"N9草稿甲_{tag}")).to_have_count(1, timeout=10000)

        page.locator('[data-test="case-create"]').click()
        dlg = _visible_dialog(page, "case-create-dialog")
        expect(dlg).to_be_visible(timeout=10000)
        expect(dlg.locator('[data-test="case-create-title"]')).to_have_value("", timeout=5000)
        page.keyboard.press("Escape")
        expect(_visible_dialog(page, "case-create-dialog")).to_have_count(0, timeout=5000)

        assert_no_errors(collectors, "N9 用例草稿保留")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")



def test_n9_unsaved_close_confirm(page, collectors, api_client, uniq):
    api = api_client
    tag = uniq
    pid = _mk_project(api, tag)
    try:
        inject_token(page)
        _nav_cases(page, tag)

        page.locator('[data-test="case-create"]').click()
        dlg = _visible_dialog(page, "case-create-dialog")
        expect(dlg).to_be_visible(timeout=10000)
        dlg.locator('[data-test="case-create-title"]').fill(f"N9确认甲_{tag}")

        dlg.locator(".el-dialog__headerbtn").click()
        box = page.locator(".el-message-box:visible")
        expect(box).to_be_visible(timeout=5000)
        expect(box).to_contain_text("未保存")
        dlg = _visible_dialog(page, "case-create-dialog")
        expect(dlg).to_be_visible(timeout=3000)

        box.get_by_role("button", name="继续编辑").click()
        expect(page.locator(".el-message-box")).to_have_count(0, timeout=5000)
        expect(dlg.locator('[data-test="case-create-title"]')).to_have_value(
            f"N9确认甲_{tag}", timeout=5000
        )

        dlg.locator(".el-dialog__headerbtn").click()
        box = page.locator(".el-message-box:visible")
        expect(box).to_be_visible(timeout=5000)
        box.get_by_role("button", name="丢弃草稿").click()
        expect(_visible_dialog(page, "case-create-dialog")).to_have_count(0, timeout=5000)

        page.locator('[data-test="case-create"]').click()
        dlg = _visible_dialog(page, "case-create-dialog")
        expect(dlg).to_be_visible(timeout=10000)
        expect(dlg.locator('[data-test="case-create-title"]')).to_have_value("", timeout=5000)
        page.keyboard.press("Escape")

        assert_no_errors(collectors, "N9 未保存关闭确认")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")



def test_n9_suite_draft_preserved(page, collectors, api_client, uniq):
    api = api_client
    tag = uniq
    pid = _mk_project(api, tag)
    try:
        inject_token(page)
        # AN-5: /suites leaf retired → regression suites ride the case
        # workbench tab; header context = deep link ?projectId.
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}&tab=suites", wait_until="domcontentloaded")
        expect(page.locator('[data-test="suite-create-btn"]')).to_be_enabled(timeout=15000)

        page.locator('[data-test="suite-create-btn"]').click()
        dlg = _visible_dialog(page, "suite-dialog")
        expect(dlg).to_be_visible(timeout=10000)
        dlg.locator('[data-test="suite-form-name"]').fill(f"N9套件草稿_{tag}")
        dlg.locator('.el-dialog__footer button:has-text("取消")').click()
        expect(_visible_dialog(page, "suite-dialog")).to_have_count(0, timeout=5000)

        page.locator('[data-test="suite-create-btn"]').click()
        dlg = _visible_dialog(page, "suite-dialog")
        expect(dlg).to_be_visible(timeout=10000)
        expect(dlg.locator('[data-test="suite-form-name"]')).to_have_value(
            f"N9套件草稿_{tag}", timeout=5000
        )
        dlg.locator('.el-dialog__footer button:has-text("取消")').click()
        expect(_visible_dialog(page, "suite-dialog")).to_have_count(0, timeout=5000)

        assert_no_errors(collectors, "N9 套件草稿保留")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")



def test_n9_wizard_prev_step_keeps(page, collectors, api_client, uniq):
    api = api_client
    tag = uniq
    pid = _mk_project(api, tag)
    try:
        inject_token(page)
        # Header global context : deep-link ?projectId lands the plan list.
        page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-create-btn"]')).to_be_enabled(timeout=10000)

        page.locator('[data-test="plan-create-btn"]').click()
        expect(page.locator('[data-test="plan-wizard-name"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="plan-wizard-name"]').fill(f"N9向导计划_{tag}")
        page.locator('[data-test="plan-wizard-version"]').fill(f"v-{tag}")
        page.locator('[data-test="plan-wizard-next"]').click()

        expect(page.locator('[data-test="plan-wizard-envs"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="plan-wizard-prev"]').click()
        expect(page.locator('[data-test="plan-wizard-name"]')).to_have_value(
            f"N9向导计划_{tag}", timeout=5000
        )
        expect(page.locator('[data-test="plan-wizard-version"]')).to_have_value(
            f"v-{tag}", timeout=5000
        )
        page.locator('[data-test="plan-wizard-next"]').click()
        expect(page.locator('[data-test="plan-wizard-envs"]')).to_be_visible(timeout=10000)
        page.keyboard.press("Escape")

        assert_no_errors(collectors, "N9 向导上一步保留")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")
