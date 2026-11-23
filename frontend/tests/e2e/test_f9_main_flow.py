
import os
import re
import time

import httpx
import pytest
from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    BASE_URL,
    FIXTURE_LOGIN_REQ,
    add_member,
    assert_detail_opened,
    assert_no_errors,
    first_env_id,
    mk_tester,
    nav_to,
    save_script_params,
    script_step_count,
    trial_run_browser,
    wait_trial_success,
)
from playwright.sync_api import expect


pytestmark = pytest.mark.timeout(900)

FIXTURES_URL = "http://localhost:8899/"


def _mk_project_with_env(api, uniq):
    pass
    name = f"F9项目_{uniq}"
    code = f"F9{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={"name": "本地演示", "envType": 1, "baseUrl": FIXTURES_URL})
    assert r.json().get("code") == "0", f"配置环境失败: {r.text[:300]}"
    return pid, name


def _select_project(page, test_id, project_name):
    page.locator(f'[data-test="{test_id}"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
    expect(page.locator(f'[data-test="{test_id}"]')).to_contain_text(project_name, timeout=5000)


def _land_ai_gen_context(page, pid, project_name):
    """T396b posture: the generate wizard inherits the header project context —
    deep-link the id (URL priority chain), reload to refresh the visible-domain
    snapshot, then assert the read-only ownership echo carries the name."""
    page.goto(f"{BASE_URL}/#/ai/generate?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    echo = page.locator('[data-test="owner-echo"]').first
    expect(echo).to_be_visible(timeout=10000)
    expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(project_name, timeout=10000)


def test_f9_main_flow_full(page, api_client, collectors, uniq):
    pass
    r = httpx.get(FIXTURES_URL + "login", timeout=3)
    assert r.status_code == 200 and "试运行演示门户" in r.text, "serve_fixtures 未启动"

    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.fill('input[data-test="login-username"]', ADMIN_USERNAME)
    page.fill('input[data-test="login-password"]', os.environ.get("ADMIN_INIT_PASSWORD", ADMIN_PASSWORD))
    page.click('button[data-test="login-submit"]')
    page.wait_for_url("**/#/dashboard", timeout=15000)
    expect(page.locator(".dashboard-root")).to_be_visible(timeout=10000)
    assert page.evaluate("() => !!localStorage.getItem('tp_access_token')"), "登录后应有 token"

    pid, pname = _mk_project_with_env(api_client, uniq)
    tester_id, tester_nick = mk_tester(api_client, uniq, "f9")
    add_member(api_client, pid, tester_id)
    page.reload(wait_until="domcontentloaded")
    expect(page.locator(".dashboard-root")).to_be_visible(timeout=15000)

    # T396b: the wizard rides the header context (in-page selector retired) —
    # land the deep link and assert the ownership echo instead of picking a dropdown.
    _land_ai_gen_context(page, pid, pname)
    expect(page.locator('[data-test="ai-gen-form"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="ai-gen-requirement"]').fill(FIXTURE_LOGIN_REQ)
    page.locator('[data-test="ai-gen-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="生成任务已提交")).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="ai-gen-task-status"]')).to_contain_text("已完成", timeout=480000)
    page.locator('[data-test="ai-gen-open-case"]').click()
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10000)
    assert_detail_opened(page)
    case_no = page.locator('[data-test="detail-case-no"]').inner_text().strip()

    expect(page.locator('[data-test="detail-script"]')).to_contain_text("schema v2", timeout=15000)
    expect(page.locator('[data-test="detail-script"]')).to_contain_text("已生成")

    m = re.search(r"/#/cases/(\d+)", page.url)
    assert m, f"详情页 URL 应锚定 caseId: {page.url}"
    save_script_params(api_client, pid, first_env_id(api_client, pid), int(m.group(1)))

    trial_run_browser(page)
    wait_trial_success(page)
    timeline = page.locator('[data-test="exec-timeline"]')
    expect(timeline).to_be_visible()
    expect(timeline.locator("tbody tr")).to_have_count(
        script_step_count(api_client, int(m.group(1))))
    expect(timeline).to_contain_text("成功")
    img = page.locator('[data-test="exec-screenshots"] .shot-img img').first
    expect(img).to_be_visible(timeout=15000)
    src = img.get_attribute("src")
    assert src and "X-Amz-Signature" in src, f"截图应为预签名 URL: {src}"
    page.locator('[data-test="trial-dialog-close"]').click()
    expect(page.locator('[data-test="trial-result-dialog"]')).to_be_hidden(timeout=5000)

    r = api_client.get(f"/cases?projectId={pid}&page=1&size=50")
    detail = [c for c in r.json()["data"]["list"] if c["caseNo"] == case_no][0]
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, "project-ctx-select", pname)
    page.locator('[data-test="case-filter-keyword"]').fill(case_no)
    page.locator('[data-test="case-search"]').click()
    row = page.locator(f'[data-test="case-table"] tbody tr.el-table__row:has-text("{case_no}")')
    expect(row).to_be_visible(timeout=10000)
    row.locator(".el-table__expand-icon").click()
    expect(page.locator(f'[data-test="case-expand-{detail["id"]}"]')).to_be_visible(timeout=5000)
    page.locator(f'[data-test="case-expand-{detail["id"]}"] [data-test="case-assign"]').click()
    expect(page.locator('[data-test="assign-dialog"]')).to_be_visible(timeout=5000)
    page.locator('[data-test="assign-member-select"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=tester_nick).first.click()
    page.locator('[data-test="assign-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="分配成功")).to_be_visible(timeout=5000)
    r = api_client.get(f"/cases/{detail['id']}")
    assert r.json()["data"]["assigneeId"] == tester_id, f"assigneeId 应为 {tester_id}"

    page.goto(f"{BASE_URL}/#/cases/{detail['id']}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="detail-review-pass"]').click()
    expect(page.locator(".el-message-box")).to_be_visible(timeout=5000)
    page.locator('.el-message-box__btns button:has-text("通过")').click()
    expect(page.locator(".el-message").filter(has_text="已通过")).to_be_visible(timeout=5000)
    expect(page.locator('[data-test="detail-status"]')).to_contain_text("已通过", timeout=5000)
    r = api_client.get(f"/cases?projectId={pid}&page=1&size=50")
    detail2 = [c for c in r.json()["data"]["list"] if c["caseNo"] == case_no][0]
    assert detail2["status"] == 3, f"状态应为已通过(3): {detail2}"

    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    _select_project(page, "project-ctx-select", pname)
    page.locator('[data-test="case-filter-keyword"]').fill(case_no)
    page.locator('[data-test="case-search"]').click()
    row = page.locator(f'[data-test="case-table"] tbody tr.el-table__row:has-text("{case_no}")')
    expect(row).to_be_visible(timeout=10000)
    expect(row).to_contain_text("已通过")
    expect(row).to_contain_text(tester_nick)

    assert_no_errors(collectors, "F-9 主链路（T29 出口）")
