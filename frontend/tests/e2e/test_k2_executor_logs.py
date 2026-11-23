
import re
import time

import httpx
import pytest
from playwright.sync_api import expect

from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    API_BASE,
    BASE_URL,
    FIXTURE_LOGIN_REQ,
    ai_generate_api,
    assert_no_errors,
    save_script_params,
)


pytestmark = pytest.mark.timeout(1200)

FIXTURES_URL = "http://localhost:8899/"


def _ensure_fixtures_server():
    try:
        r = httpx.get(FIXTURES_URL + "login", timeout=3)
        assert r.status_code == 200 and "演示门户" in r.text
    except Exception as e:  # noqa: BLE001
        raise AssertionError(
            f"试运行目标页服务不可达（{FIXTURES_URL}login）: {e}——需先启动 "
            "`cd executor && python3 serve_fixtures.py`（K2 E2E 前置）") from e


def _login() -> str:
    r = httpx.post(f"{API_BASE}/auth/login",
                   json={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}, timeout=10)
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:300]}"
    return r.json()["data"]["accessToken"]


def _mk_project(api, uniq):
    name = f"K2执行器日志_{uniq}"
    code = f"K2{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_env(api, pid):
    r = api.post(f"/projects/{pid}/envs",
                 json={"name": "开发", "envType": 1, "baseUrl": FIXTURES_URL, "versionSnapshot": "v1.0.0"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"配置环境失败: {r.text[:300]}"
    return r.json()["data"]


def _ai_generate(api, pid):
    pass
    return ai_generate_api(pid, FIXTURE_LOGIN_REQ)["caseId"]


def _review_pass(api, case_id):
    r = api.get(f"/cases/{case_id}")
    assert r.status_code == 200, f"用例详情失败: {r.text[:300]}"
    ver = r.json()["data"]["version"]
    r = api.post(f"/cases/{case_id}/review", json={"action": "pass", "version": ver})
    assert r.status_code == 200 and r.json().get("code") == "0", f"评审通过失败: {r.text[:300]}"


def _run_one_task(api, pid, env_id):
    pass
    token = _login()
    h = {"Authorization": f"Bearer {token}"}
    case_id = _ai_generate(api, pid)
    _review_pass(api, case_id)
    save_script_params(api, pid, env_id, case_id)
    r = httpx.post(f"{API_BASE}/cases/{case_id}/trial-run", json={"envId": env_id}, headers=h, timeout=10)
    assert r.status_code == 200 and r.json().get("code") == "0", f"试运行失败: {r.text[:300]}"
    task_id = r.json()["data"]["taskId"]
    detail = None
    for _ in range(150):
        r = httpx.get(f"{API_BASE}/executors/tasks/{task_id}", headers=h, timeout=10)
        detail = r.json()["data"]
        if detail and detail["status"] in (3, 4, 5, 6):
            break
        time.sleep(2)
    assert detail and detail["status"] in (3, 4, 5, 6), f"任务 300s 未到终态: {detail}"
    return detail


def _online_executor_card(page, ex_name: str):
    pass
    return (
        page.locator('[data-test="executor-card"]', has_text=ex_name)
        .filter(has_not_text=re.compile("离线|Offline"))
    )


def test_k2_executor_logs_view(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    _ensure_fixtures_server()

    pid = _mk_project(api_client, uniq)
    env_id = _mk_env(api_client, pid)
    detail = _run_one_task(api_client, pid, env_id)
    task_id = detail["id"]
    ex_id = detail["executorId"]
    assert detail.get("log"), "真实执行任务应带 console 日志"

    token = _login()
    r = httpx.get(f"{API_BASE}/executors", headers={"Authorization": f"Bearer {token}"}, timeout=10)
    ex_name = next(e["name"] for e in r.json()["data"] if e["id"] == ex_id)
    page.goto(f"{BASE_URL}#/execution", wait_until="domcontentloaded")
    expect(page.locator(".page-root")).to_be_visible()
    target_card = _online_executor_card(page, ex_name)
    expect(target_card).to_be_visible()
    target_card.locator('[data-test="executor-tasks-btn"]').click()
    expect(page.locator('[data-test="exec-tasks-title"]')).to_be_visible()

    expect(page.locator('[data-test="exec-tab-done"]')).to_contain_text("已执行")
    page.get_by_text("已执行", exact=False).first.click()
    expect(page.locator('[data-test="exec-tasks-table"]')).to_be_visible()
    row = page.locator('[data-test="exec-tasks-table"] tbody tr', has_text=f"#{task_id}")
    expect(row).to_have_count(1)

    row.locator('[data-test="exec-tasks-detail"]').click()
    expect(page.locator('[data-test="exec-detail-dialog"]')).to_be_visible()
    expect(page.locator('[data-test="exec-console-log"]')).to_be_visible()
    expect(page.locator('[data-test="exec-console-log"]')).to_contain_text("console")
    expect(page.locator('[data-test="exec-console-log"] .console-log')).to_contain_text("演示门户")
    expect(page.locator('[data-test="exec-timeline"]')).to_be_visible()
    page.locator('[data-test="exec-detail-close"]').click()

    page.goto(f"{BASE_URL}#/execution", wait_until="domcontentloaded")
    expect(_online_executor_card(page, ex_name)).to_be_visible()
    _online_executor_card(page, ex_name).locator('[data-test="executor-tasks-btn"]').click()
    expect(page.locator('[data-test="exec-tasks-title"]')).to_be_visible()
    page.get_by_text("已执行", exact=False).first.click()
    expect(page.locator('[data-test="exec-tasks-table"] tbody tr', has_text=f"#{task_id}")).to_have_count(1)

    assert_no_errors(collectors, "K2 执行器日志视图")
