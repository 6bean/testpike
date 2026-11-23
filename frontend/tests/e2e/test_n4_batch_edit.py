
import os
import uuid

import httpx
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, nav_to



def inject_token(page):
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
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def api_headers():
    from conftest import ADMIN_PASSWORD, api_login
    token = api_login("admin", ADMIN_PASSWORD)
    return {"Authorization": f"Bearer {token}"}


def test_n4_batch_edit(page, collectors):
    pass
    uniq = uuid.uuid4().hex[:8]
    h = api_headers()
    test_name = "N4 用例批量编辑"

    r = httpx.post(f"{API_BASE}/projects", json={"name": f"N4E2E_{uniq}", "code": f"N4E{uniq.upper()}"}, headers=h, timeout=10)
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]

    cids = []
    for i in range(3):
        r = httpx.post(f"{API_BASE}/cases", json={
            "projectId": pid, "title": f"批量编辑用例{i}_{uniq}", "precondition": "前置", "expectedResult": "预期",
            "phase": 1, "priority": 2, "confidence": 2, "endType": "web",
            "steps": [{"stepNo": 1, "description": "步骤一", "isAssert": 1}],
        }, headers=h, timeout=10)
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        cids.append(r.json()["data"]["id"])

    tids = []
    for name in ("甲", "乙"):
        r = httpx.post(f"{API_BASE}/tags", json={"projectId": pid, "name": f"标签{name}_{uniq}", "type": 4}, headers=h, timeout=10)
        assert r.json().get("code") == "0", f"建标签失败: {r.text[:300]}"
        tids.append(r.json()["data"]["id"])

    inject_token(page)
    nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
    page.locator("[data-test=project-ctx-select]").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"N4E2E_{uniq}").first.click()
    expect(page.locator('[data-test="case-table"] tbody tr')).to_have_count(3, timeout=15000)

    page.locator('[data-test="case-table"] tbody tr').nth(0).locator(".el-checkbox").click()
    page.locator('[data-test="case-table"] tbody tr').nth(1).locator(".el-checkbox").click()
    expect(page.locator("[data-test=case-batch-bar]")).to_be_visible(timeout=5000)
    page.locator("[data-test=case-batch-edit]").click()
    expect(page.locator("[data-test=batch-edit-dialog]")).to_be_visible(timeout=5000)

    page.locator("[data-test=batch-edit-phase]").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="功能").first.click()
    page.locator("[data-test=batch-edit-priority]").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="P1").first.click()
    page.locator("[data-test=batch-edit-tags]").click()
    for tag_name in (f"标签甲_{uniq}", f"标签乙_{uniq}"):
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=tag_name).first.click()
    page.keyboard.press("Escape")
    page.locator("[data-test=batch-edit-submit]").click()

    expect(page.locator(".el-message--success").first).to_contain_text("批量编辑成功", timeout=10000)

    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=15000)
    page.locator("[data-test=project-ctx-select]").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"N4E2E_{uniq}").first.click()
    expect(page.locator('[data-test="case-table"] tbody tr')).to_have_count(3, timeout=15000)
    expect(page.locator('[data-test="case-table"] tbody tr').nth(0)).to_contain_text("功能", timeout=10000)
    expect(page.locator('[data-test="case-table"] tbody tr').nth(1)).to_contain_text("功能", timeout=10000)
    expect(page.locator('[data-test="case-table"] tbody tr').nth(0)).to_contain_text("P1", timeout=10000)
    expect(page.locator('[data-test="case-table"] tbody tr').nth(0)).to_contain_text(f"标签甲_{uniq}", timeout=10000)
    expect(page.locator('[data-test="case-table"] tbody tr').nth(1)).to_contain_text(f"标签乙_{uniq}", timeout=10000)

    page.locator('[data-test="case-table"] tbody tr').nth(0).locator(".el-checkbox").click()
    page.locator('[data-test="case-table"] tbody tr').nth(2).locator(".el-checkbox").click()
    expect(page.locator("[data-test=case-batch-bar]")).to_be_visible(timeout=5000)
    r = httpx.delete(f"{API_BASE}/cases/{cids[2]}", headers=h, timeout=10)
    assert r.json().get("code") == "0", f"删除用例失败: {r.text[:300]}"

    page.locator("[data-test=case-batch-edit]").click()
    expect(page.locator("[data-test=batch-edit-dialog]")).to_be_visible(timeout=5000)
    page.locator("[data-test=batch-edit-phase]").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="回归").first.click()
    page.locator("[data-test=batch-edit-submit]").click()

    alert = page.locator(".el-message-box")
    expect(alert).to_be_visible(timeout=10000)
    expect(alert).to_contain_text("成功 1 条，失败 1 条", timeout=10000)
    expect(alert).to_contain_text("不存在或已被删除", timeout=10000)
    alert.locator("button:has-text('确定')").click()

    assert_no_errors(collectors, test_name)
