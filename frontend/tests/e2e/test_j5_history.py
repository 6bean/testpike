
import pytest
from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    FIXTURE_LOGIN_REQ,
    ai_generate_api,
    assert_no_errors,
    save_script_params,
    trial_run_api,
    wait_case_status,
)


pytestmark = pytest.mark.timeout(1200)

FIXTURES_URL = "http://localhost:8899/"


def _mk_project(api, uniq):
    name = f"J5执行历史_{uniq}"
    code = f"J5{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _mk_env(api, pid):
    pass
    r = api.post(f"/projects/{pid}/envs", json={"name": "本地演示", "envType": 1, "baseUrl": FIXTURES_URL})
    assert r.status_code == 200 and r.json().get("code") == "0", f"配置环境失败: {r.text[:300]}"
    return r.json()["data"]


def _review_pass(api, case_id):
    r = api.get(f"/cases/{case_id}")
    assert r.status_code == 200, f"用例详情失败: {r.text[:300]}"
    ver = r.json()["data"]["version"]
    r = api.post(f"/cases/{case_id}/review", json={"action": "pass", "version": ver})
    assert r.status_code == 200 and r.json().get("code") == "0", f"评审通过失败: {r.text[:300]}"


def _trial_run(api, case_id, project_id) -> int:
    return trial_run_api(api, case_id, project_id)




def test_j5_case_exec_history(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in

    pid, _ = _mk_project(api_client, uniq)
    env_id = _mk_env(api_client, pid)
    t = ai_generate_api(pid, FIXTURE_LOGIN_REQ)
    case_id = t["caseId"]
    _review_pass(api_client, case_id)
    sp = save_script_params(api_client, pid, env_id, case_id, phase="fail")
    _trial_run(api_client, case_id, pid)
    wait_case_status(api_client, case_id, 7)
    save_script_params(api_client, pid, env_id, case_id, phase="ok")
    _trial_run(api_client, case_id, pid)
    wait_case_status(api_client, case_id, 3)

    page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-history-block"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="case-history-count"]')).to_contain_text("2 次", timeout=10000)
    expect(page.locator('[data-test="case-history-table"] tbody tr')).to_have_count(2)
    rows = page.locator('[data-test="case-history-table"] tbody tr')
    expect(rows.nth(0).locator('[data-test="history-status"]')).to_contain_text("成功")
    expect(rows.nth(1).locator('[data-test="history-status"]')).to_contain_text("失败")
    expect(rows.nth(0).locator('[data-test="history-source"]')).to_contain_text("试运行")
    expect(rows.nth(0).locator('[data-test="history-env"]')).to_contain_text(FIXTURES_URL)
    expect(rows.nth(0).locator('[data-test="history-error"]')).to_have_count(0)
    expect(rows.nth(1).locator('[data-test="history-error"]')).to_be_visible()
    fail_reason = rows.nth(1).locator('[data-test="history-error"]').inner_text()
    assert fail_reason.strip(), "失败原因不应为空"
    assert rows.nth(0).locator('[data-test="history-time"]').inner_text() != "-"

    if sp["fail_mode"] == "dead-url":
        shot = rows.nth(1).locator('[data-test="history-screenshot"]').first
        expect(shot).to_be_visible(timeout=10000)
        shot.click()
        expect(page.locator(".el-image-viewer__wrapper")).to_be_visible(timeout=5000)
        page.keyboard.press("Escape")
        expect(page.locator(".el-image-viewer__wrapper")).to_have_count(0, timeout=5000)
    else:
        expect(rows.nth(1).locator('[data-test="history-screenshot"]')).to_have_count(0)

    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-history-block"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="case-history-count"]')).to_contain_text("2 次", timeout=10000)
    expect(page.locator('[data-test="case-history-table"] tbody tr')).to_have_count(2)
    expect(page.locator('[data-test="case-history-table"] tbody tr').nth(1)
          .locator('[data-test="history-status"]')).to_contain_text("失败")

    assert_no_errors(collectors, "TC-J5-02 执行历史 UI 全链路")
