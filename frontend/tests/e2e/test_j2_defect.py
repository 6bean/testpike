
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
    name = f"J2缺陷闭环_{uniq}"
    code = f"J2{uniq[:6].upper()}"
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




def _link_defect(api, case_id, key) -> dict:
    r = api.post("/defects", json={"caseId": case_id, "defectKey": key, "defectStatus": "open"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"关联缺陷失败: {r.text[:300]}"
    return r.json()["data"]


def _close_defect(api, defect_id, version) -> dict:
    r = api.put(f"/defects/{defect_id}/status", json={"defectStatus": "closed", "version": version})
    assert r.status_code == 200 and r.json().get("code") == "0", f"回填 closed 失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_closed_defect(api, case_id, key):
    pass
    d = _link_defect(api, case_id, key)
    return _close_defect(api, d["id"], d["version"])


def test_j2_defect_closure_full_flow(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in

    pid, _ = _mk_project(api_client, uniq)
    env_id = _mk_env(api_client, pid)
    t = ai_generate_api(pid, FIXTURE_LOGIN_REQ)
    case_id = t["caseId"]
    _review_pass(api_client, case_id)
    save_script_params(api_client, pid, env_id, case_id, phase="fail")
    _trial_run(api_client, case_id, pid)
    wait_case_status(api_client, case_id, 7)

    defect_key = f"JIRA-J2-{uniq[:6].upper()}"
    page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-defect-block"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="detail-status"]')).to_contain_text("执行失败")
    expect(page.locator('[data-test="case-defect-empty"]')).to_be_visible()
    expect(page.locator('[data-test="rerun-hint-card"]')).to_have_count(0)

    page.locator('[data-test="case-defect-link-btn"]').click()
    expect(page.locator('[data-test="defect-link-dialog"]')).to_be_visible()
    page.locator('[data-test="defect-key-input"]').fill(defect_key)
    page.locator('[data-test="defect-link-submit"]').click()

    expect(page.locator('[data-test="case-defect-table"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="case-defect-count"]')).to_contain_text("1 条")
    expect(page.locator('[data-test="defect-key-cell"]').first).to_contain_text(defect_key)

    page.locator('[data-test="defect-status-select"]').first.click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible", has_text="已关闭").first.click()
    expect(page.locator('[data-test="rerun-hint-card"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="rerun-hint-text"]')).to_contain_text(defect_key)
    expect(page.locator('[data-test="defect-closed-badge"]').first).to_contain_text("已闭环")

    r = api_client.get("/notifications?page=1&size=5")
    assert r.status_code == 200, f"查询通知失败: {r.text[:300]}"
    assert defect_key in r.text, f"DEFECT_CLOSED 站内信应含缺陷 key: {r.text[:500]}"

    page.locator('[data-test="rerun-case-btn"]').click()
    expect(page.locator('[data-test="detail-status"]')).to_contain_text("执行中", timeout=10000)
    wait_case_status(api_client, case_id, 7)

    save_script_params(api_client, pid, env_id, case_id, phase="ok")
    _trial_run(api_client, case_id, pid)
    wait_case_status(api_client, case_id, 3)

    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="detail-status"]')).to_contain_text("已通过", timeout=10000)
    expect(page.locator('[data-test="case-defect-table"]')).to_be_visible()
    expect(page.locator('[data-test="case-defect-count"]')).to_contain_text("1 条")
    expect(page.locator('[data-test="rerun-hint-card"]')).to_be_visible()

    assert_no_errors(collectors, "TC-J2-02 缺陷闭环全链路")
