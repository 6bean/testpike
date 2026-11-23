
import re

import httpx
import pytest
from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    FIXTURE_LOGIN_REQ,
    ai_generate_api,
    assert_no_errors,
    save_script_params,
    wait_trial_success,
)


pytestmark = pytest.mark.timeout(1200)

FIXTURES_URL = "http://localhost:8899/"


def _ensure_fixtures_server():
    try:
        r = httpx.get(FIXTURES_URL + "login", timeout=3)
        assert r.status_code == 200 and "试运行演示门户" in r.text
    except Exception as e:
        raise AssertionError(
            f"试运行目标页服务不可达（{FIXTURES_URL}login）: {e}——需先启动 "
            "`cd executor && python3 serve_fixtures.py`（J6 E2E 前置）") from e


def _mk_project(api, uniq):
    name = f"J6执行环境_{uniq}"
    code = f"J6{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _mk_env(api, pid, name, env_type, base_url, version=None):
    body = {"name": name, "envType": env_type, "baseUrl": base_url}
    if version:
        body["versionSnapshot"] = version
    r = api.post(f"/projects/{pid}/envs", json=body)
    assert r.status_code == 200 and r.json().get("code") == "0", f"配置环境失败: {r.text[:300]}"
    return r.json()["data"]


def _review_pass(api, case_id):
    r = api.get(f"/cases/{case_id}")
    assert r.status_code == 200, f"用例详情失败: {r.text[:300]}"
    ver = r.json()["data"]["version"]
    r = api.post(f"/cases/{case_id}/review", json={"action": "pass", "version": ver})
    assert r.status_code == 200 and r.json().get("code") == "0", f"评审通过失败: {r.text[:300]}"


def test_j6_env_select_full_flow(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    _ensure_fixtures_server()
    pid, _pname = _mk_project(api_client, uniq)
    try:
        _j6_full_flow(page, api_client, collectors, uniq, pid)
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def _j6_full_flow(page, api_client, collectors, uniq, pid):

    _mk_env(api_client, pid, "开发", 1, "http://dev.internal.local", "v0.9.0")
    env_test = _mk_env(api_client, pid, "测试", 2, FIXTURES_URL, "v2.1.0")
    _mk_env(api_client, pid, "预发", 3, "http://staging.internal.local", "v2.2.0")
    _mk_env(api_client, pid, "生产", 4, "https://prod.example.com", "v1.0.0")

    t = ai_generate_api(pid, FIXTURE_LOGIN_REQ)
    case_id = t["caseId"]
    _review_pass(api_client, case_id)
    save_script_params(api_client, pid, env_test, case_id)

    page.reload(wait_until="domcontentloaded")
    expect(page.locator(".dashboard-root")).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="detail-script"]')).to_contain_text("已生成", timeout=15000)
    expect(page.locator('[data-test="detail-trial-run"]')).to_be_enabled()

    page.locator('[data-test="detail-trial-run"]').click()
    expect(page.locator('[data-test="env-select-dialog"]')).to_be_visible(timeout=10000)

    page.locator('[data-test="env-select"]').click()
    options = page.locator(".el-select-dropdown:visible .el-select-dropdown__item")
    expect(options).to_have_count(4, timeout=5000)
    names = [options.nth(i).inner_text() for i in range(4)]
    assert any("开发" in n for n in names), f"应有开发环境: {names}"
    assert any("测试" in n for n in names), f"应有测试环境: {names}"
    assert any("预发" in n for n in names), f"应有预发环境: {names}"
    prod_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("生产")')
    expect(prod_opt).to_have_class(re.compile("is-disabled"), timeout=5000)
    assert "生产默认禁止执行" in prod_opt.inner_text(), "生产环境应有禁用标注"

    page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("测试")').first.click()
    page.locator('[data-test="env-select-confirm"]').click()
    expect(page.locator('[data-test="trial-result-dialog"]')).to_be_visible(timeout=10000)
    wait_trial_success(page)
    page.locator('[data-test="trial-dialog-close"]').click()

    expect(page.locator('[data-test="history-env"]').first).to_contain_text("v2.1.0", timeout=15000)

    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="history-env"]').first).to_contain_text("v2.1.0", timeout=15000)

    page.locator('[data-test="detail-trial-run"]').click()
    expect(page.locator('[data-test="env-select-dialog"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="env-select"]').click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("测试")').first.click()
    page.locator('[data-test="env-select-confirm"]').click()
    expect(page.locator('[data-test="trial-result-dialog"]')).to_be_visible(timeout=10000)

    assert_no_errors(collectors, "J6 执行环境选择全链路")


def test_j6_env_collection_filter(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    _ensure_fixtures_server()
    pid, _pname = _mk_project(api_client, uniq)
    try:
        _j6_collection_filter(page, api_client, collectors, uniq, pid)
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def _j6_collection_filter(page, api_client, collectors, uniq, pid):
    _mk_env(api_client, pid, "开发", 1, "http://dev-b.local", "v0.9.0")
    env_test_b = _mk_env(api_client, pid, "测试", 2, FIXTURES_URL, "v2.1.0")
    _mk_env(api_client, pid, "预发", 3, "http://staging-b.local", "v2.2.0")

    t = ai_generate_api(pid, FIXTURE_LOGIN_REQ)
    case_id = t["caseId"]
    _review_pass(api_client, case_id)
    save_script_params(api_client, pid, env_test_b, case_id)

    page.reload(wait_until="domcontentloaded")
    expect(page.locator(".dashboard-root")).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="detail-script"]')).to_contain_text("已生成", timeout=15000)

    page.locator('[data-test="detail-trial-run"]').click()
    expect(page.locator('[data-test="env-select-dialog"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="env-select"]').click()
    options = page.locator(".el-select-dropdown:visible .el-select-dropdown__item")
    expect(options).to_have_count(3, timeout=5000)
    names = [options.nth(i).inner_text() for i in range(3)]
    assert not any("生产" in n for n in names), f"项目 B 不应有生产环境选项: {names}"

    page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("测试")').first.click()
    page.locator('[data-test="env-select-confirm"]').click()
    expect(page.locator('[data-test="trial-result-dialog"]')).to_be_visible(timeout=10000)
    wait_trial_success(page)

    assert_no_errors(collectors, "J6 项目 B 环境集合过滤")
