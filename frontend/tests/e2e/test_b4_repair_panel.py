
import re
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import api_client
from conftest import assert_no_errors, logged_in, sql_query, uniq  # noqa: F401

pytestmark = pytest.mark.timeout(180)

CORE = "http://localhost:8083"


def _mk_project(api, uid):
    r = api.post("/projects", json={"name": f"B4修复项目_{uid}", "code": f"B4{uid[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uid):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"登录功能验证_{uid}",
        "precondition": f"前置：已登录系统（{uid}）",
        "expectedResult": f"预期：操作成功（{uid}）",
        "priority": 0,
        "confidence": 1,
        "endType": "web",
        "status": 1,
        "steps": [{"stepNo": 1, "description": "打开页面 /login", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _seed_repair(pid, case_id, outcome, proposal, fail_note):
    pass
    r = httpx.post(f"{CORE}/internal/script-repairs", timeout=30,
                   json={"projectId": pid, "caseId": case_id, "sourceTaskId": None,
                         "trialTaskId": None, "proposalSummary": proposal,
                         "outcome": outcome, "failNote": fail_note})
    assert r.status_code == 200, f"造种失败: {r.status_code} {r.text[:200]}"
    assert r.json().get("code") == "0", f"造种业务失败: {r.text[:200]}"
    return r.json()["data"]


def _open_detail(page, case_id):
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")


def _repair_rows(page):
    return page.locator('[data-test="repair-block"] [data-test="repair-outcome"]')


def test_b4_repair_panel_and_gate(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq)
        _seed_repair(pid, case_id, 1, "把按钮定位从 .btn-login 改为 [data-test=login]", None)
        _seed_repair(pid, case_id, 2, "候选定位集重试", "试运行仍失败：元素不可点击（被遮罩）")
        _seed_repair(pid, case_id, 3, None, "24h 轮次已达上限，转人工")

        _open_detail(page, case_id)
        expect(page.locator('[data-test="repair-block"]')).to_be_visible(timeout=10000)
        expect(_repair_rows(page)).to_have_count(3)
        panel_text = page.locator('[data-test="repair-block"]').inner_text()
        assert "通过已落新版本" in panel_text, f"outcome=1 标签缺失: {panel_text[:400]}"
        assert "未过交人工" in panel_text, f"outcome=2 标签缺失: {panel_text[:400]}"
        assert "闸外跳过" in panel_text, f"outcome=3 标签缺失: {panel_text[:400]}"
        assert "第 1 轮" in panel_text, f"轮次渲染缺失: {panel_text[:400]}"
        assert "试运行仍失败" in panel_text

        _open_detail(page, case_id)
        expect(_repair_rows(page)).to_have_count(3)

        base = page.url.split("#")[0]
        page.goto(f"{base}#/configs", wait_until="domcontentloaded")
        card = page.locator('[data-test="repair-gate-card"]')
        expect(card).to_be_visible(timeout=10000)
        assert "is-checked" not in (card.locator('[data-test="repair-gate-enabled"]').first.get_attribute("class") or "")
        card.locator('[data-test="repair-gate-enabled"]').click()
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=5000)
        page.goto(f"{base}#/configs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="repair-gate-card"] [data-test="repair-gate-enabled"]')
               .first).to_have_class(re.compile(r"is-checked"), timeout=5000)

        rounds = page.locator('[data-test="repair-gate-rounds"]')
        expect(rounds.locator("input")).to_have_value("3")
        rounds.locator(".el-input-number__increase").click()
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=5000)
        page.goto(f"{base}#/configs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="repair-gate-rounds"] input')).to_have_value("4", timeout=5000)

        assert sql_query(
            "SELECT config_value FROM tp_sys_config WHERE config_key='ai.repair.enabled'") == "1"
        assert sql_query(
            "SELECT config_value FROM tp_sys_config WHERE config_key='ai.repair.rounds'") == "4"
    finally:
        sql_query("UPDATE tp_sys_config SET config_value='0' WHERE config_key='ai.repair.enabled'")
        sql_query("UPDATE tp_sys_config SET config_value='3' WHERE config_key='ai.repair.rounds'")
        if case_id:
            sql_query(f"DELETE FROM tp_script_repair WHERE case_id = {case_id}")
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")

    assert_no_errors(collectors, "B4 修复记录面板与 K8 开关卡")


def test_b4_repair_empty_state(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq)
        _open_detail(page, case_id)
        expect(page.locator('[data-test="repair-block"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="repair-empty"]')).to_be_visible()
        assert "暂无修复记录" in page.locator('[data-test="repair-empty"]').inner_text()
        assert_no_errors(collectors, "B4 修复记录空态")
    finally:
        if case_id:
            sql_query(f"DELETE FROM tp_script_repair WHERE case_id = {case_id}")
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")
