
import re
import time

import httpx
import pytest
from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    API_BASE,
    BASE_URL,
    FIXTURE_LOGIN_REQ,
    ai_generate_api,
    assert_no_errors,
    ensure_screenshot_mode_every_step,
    first_env_id,
    nav_to,
    save_script_params,
    script_step_count,
    trial_run_browser,
    wait_trial_success,
)
from playwright.sync_api import expect

# AI terminal-state wait now floors at 900s (see conftest ai_generate_api:
# measured stage-2 script generation 447-646s), plus trial-run/UI-chain
# margin, so the file-level pytest-timeout goes 1200 -> 1500.
# Note: default 180 would cut long chains (full-suite 389s evidence).
pytestmark = pytest.mark.timeout(1500)


FIXTURES_URL = "http://localhost:8899/"


def _login() -> str:
    r = httpx.post(f"{API_BASE}/auth/login", json={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}, timeout=10)
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:300]}"
    return r.json()["data"]["accessToken"]


def _mk_project(api, uniq, with_env=True):
    pass
    name = f"F8项目_{uniq}"
    code = f"F8{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    if with_env:
        r = api.post(f"/projects/{pid}/envs", json={"name": "本地演示", "envType": 1, "baseUrl": FIXTURES_URL})
        assert r.json().get("code") == "0", f"配置环境失败: {r.text[:300]}"
    return pid, name


def _trigger_generate_api(pid, requirement):
    pass
    t = ai_generate_api(pid, requirement)
    return t, t["caseId"]


def _ensure_fixtures_server():
    pass
    try:
        r = httpx.get(FIXTURES_URL + "login", timeout=3)
        assert r.status_code == 200 and "试运行演示门户" in r.text
    except Exception as e:
        raise AssertionError(
            f"试运行目标页服务不可达（{FIXTURES_URL}login）: {e}——需先启动 "
            "`cd executor && python3 serve_fixtures.py`（F-8 前置）") from e


def test_f8_executor_list(logged_in, collectors):
    pass
    page = logged_in
    _ensure_fixtures_server()

    nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")

    expect(page.locator('[data-test="executor-list"]')).to_be_visible(timeout=15000)
    local = page.locator('[data-test="executor-card"]', has_text="executor-local-01").first
    expect(local).to_be_visible(timeout=5000)
    expect(local.locator('[data-test="executor-status"]')).to_contain_text(
        re.compile("空闲|运行中"))
    expect(local).to_contain_text("并发上限")
    expect(local).to_contain_text("2")
    expect(local.locator('[data-test="executor-heartbeat"]')).to_contain_text("最近心跳")

    expect(page.locator('[data-test="exec-task-empty"]')).to_contain_text("试运行")

    assert_no_errors(collectors, "F-8 执行器列表")


def test_f8_trial_run_full_flow(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    _ensure_fixtures_server()
    pid, _pname = _mk_project(api_client, uniq)

    _t, case_id = _trigger_generate_api(pid, FIXTURE_LOGIN_REQ)
    assert _t.get("caseId") == case_id
    save_script_params(api_client, pid, first_env_id(api_client, pid), case_id)

    page.reload(wait_until="domcontentloaded")
    expect(page.locator(".dashboard-root")).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="detail-script"]')).to_contain_text("已生成", timeout=15000)
    trial_btn = page.locator('[data-test="detail-trial-run"]')
    expect(trial_btn).to_be_enabled()

    # Re-assert capture posture right before the claim: a concurrent chain's
    # session-teardown may have restored mode=3 on this shared config plane.
    ensure_screenshot_mode_every_step()
    trial_run_browser(page)
    wait_trial_success(page)

    timeline = page.locator('[data-test="exec-timeline"]')
    expect(timeline).to_be_visible()
    expect(timeline.locator("tbody tr")).to_have_count(script_step_count(api_client, case_id))
    expect(timeline).to_contain_text("成功")

    shots = page.locator('[data-test="exec-screenshots"]')
    expect(shots).to_be_visible()
    img = shots.locator(".shot-img img").first
    expect(img).to_be_visible(timeout=15000)
    src = img.get_attribute("src")
    assert src and "X-Amz-Signature" in src, f"截图 URL 应为预签名 GET: {src}"

    expect(page.locator('[data-test="exec-error-block"]')).to_have_count(0)

    expect(page.locator('[data-test="trial-run-again"]')).to_be_visible()

    assert_no_errors(collectors, "F-8 试运行全链路")


def test_f8_trial_run_no_script_hint(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, _pname = _mk_project(api_client, uniq, with_env=False)
    page.reload(wait_until="domcontentloaded")
    expect(page.locator(".dashboard-root")).to_be_visible(timeout=15000)

    r = api_client.post("/cases", json={"projectId": pid, "title": f"无脚本用例_{uniq}", "priority": 1, "confidence": 2})
    assert r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]

    page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible()
    expect(page.locator('[data-test="detail-script"]')).to_contain_text("暂无自动化脚本", timeout=15000)

    page.locator('[data-test="detail-trial-run"]').click()
    expect(page.locator(".el-message").filter(has_text="还没有可执行的脚本")).to_be_visible(timeout=5000)
    reqs = collectors.get("requests", [])
    assert not any("trial-run" in r["url"] for r in reqs), f"无脚本不应发起试运行请求: {reqs}"

    assert_no_errors(collectors, "F-8 无脚本提示")


def test_f8_execution_center_tasks(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    _ensure_fixtures_server()
    pid, pname = _mk_project(api_client, uniq)

    _t, case_id = _trigger_generate_api(pid, FIXTURE_LOGIN_REQ)
    token = _login()
    h = {"Authorization": f"Bearer {token}"}
    envs = httpx.get(f"{API_BASE}/projects/{pid}/envs", headers=h, timeout=10).json()["data"]
    save_script_params(api_client, pid, envs[0]["id"], case_id)
    r = httpx.post(f"{API_BASE}/cases/{case_id}/trial-run", json={"envId": envs[0]["id"]}, headers=h, timeout=10)
    assert r.json().get("code") == "0", f"试运行失败: {r.text[:300]}"
    etid = r.json()["data"]["taskId"]
    for _ in range(60):
        time.sleep(2)
        tsk = httpx.get(f"{API_BASE}/executors/tasks/{etid}", headers=h, timeout=10).json()["data"]
        if tsk["status"] >= 3:
            break

    page.reload(wait_until="domcontentloaded")
    nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=pname).first
    expect(option).to_be_visible(timeout=5000)
    option.click()

    expect(page.locator('[data-test="exec-task-table"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="exec-task-table"] tbody tr')).to_have_count(1)
    row = page.locator('[data-test="exec-task-table"] tbody tr').first
    expect(row).to_contain_text(f"#{etid}")
    expect(row).to_contain_text("成功")

    page.locator('[data-test="exec-task-detail"]').first.click()
    expect(page.locator('[data-test="exec-detail-dialog"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="exec-detail-dialog"] [data-test="exec-task-status"]')).to_contain_text("成功")
    expect(page.locator('[data-test="exec-detail-dialog"] [data-test="exec-timeline"] tbody tr')).to_have_count(
        script_step_count(api_client, case_id))
    expect(page.locator('[data-test="exec-detail-dialog"] [data-test="exec-screenshots"] .shot-img img').first).to_be_visible(timeout=15000)

    assert_no_errors(collectors, "F-8 执行中心任务列表")
