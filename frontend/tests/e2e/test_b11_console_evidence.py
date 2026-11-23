
import json
import os
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
    assert_no_errors,
)

pytestmark = pytest.mark.timeout(600)

FIXTURES_URL = "http://localhost:8899"
CORE_BASE = "http://localhost:8083"


def _ensure_services():
    pass
    for url, name in ((f"{FIXTURES_URL}/net_page.html", "夹具服务 :8899"),
                      (f"{API_BASE.replace('/api/v1', '')}/actuator/health", "admin :8081"),
                      (f"{CORE_BASE}/actuator/health", "core :8083")):
        try:
            assert httpx.get(url, timeout=3).status_code == 200
        except Exception as e:  # noqa: BLE001
            raise AssertionError(
                f"{name} 不可达（{url}）: {e}——T192 E2E 前置=start-all-services.sh + "
                "cd executor && python3 serve_fixtures.py && python3 runner.py") from e


def _login() -> str:
    r = httpx.post(f"{API_BASE}/auth/login",
                   json={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}, timeout=10)
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:300]}"
    return r.json()["data"]["accessToken"]


def _unwrap(r):
    assert r.status_code == 200 and r.json().get("code") == "0", \
        f"{r.request.method} {r.request.url} 失败: {r.status_code} {r.text[:300]}"
    d = r.json()["data"]
    return d.get("id") if isinstance(d, dict) and "id" in d else d


def _doc(steps):
    return json.dumps({"version": "v2", "title": "T192 证据链用例", "endType": "web",
                       "steps": steps,
                       "env": {"ENV": "test", "PACK": "default", "DATA": "default"}},
                      ensure_ascii=False)


def _evidence_doc(conn_name):
    pass
    return _doc([
        {"action": "goto", "target": {"url": f"{FIXTURES_URL}/net_page.html"}},
        {"action": "click", "target": {"css": "#btn-export"}},
        {"action": "wait", "ms": 1500},
        {"action": "net.assert", "match": {"urlPattern": f"{FIXTURES_URL}/api/order"}},
        {"action": "goto", "target": {"url": f"{FIXTURES_URL}/console_page.html"}},
        {"action": "click", "target": {"css": "#btn-error"}},
        {"action": "expect", "target": {"text": "已触发错误"}},
        {"action": "console.assert", "mode": "warn", "level": ["error"]},
        {"action": "sql.assert", "conn": conn_name, "sql": "SELECT 1 AS c",
         "expect": {"type": "scalar", "matchType": "exact", "text": "1"}},
    ])


def _plain_doc():
    pass
    return _doc([
        {"action": "goto", "target": {"url": f"{FIXTURES_URL}/console_page.html"}},
        {"action": "click", "target": {"css": "#btn-warn"}},
        {"action": "expect", "target": {"text": "已触发告警"}},
    ])


def _mk_stack(api, uniq, conn_name=None):
    pass
    pid = _unwrap(api.post("/projects", json={"name": f"B11证据{uniq}",
                                              "code": f"B11E{uniq[:6].upper()}"}))
    env_id = _unwrap(api.post(f"/projects/{pid}/envs", json={
        "name": "开发", "envType": 1, "baseUrl": FIXTURES_URL, "versionSnapshot": "v1.0.0"}))
    case_id = _unwrap(api.post("/cases", json={
        "projectId": pid, "title": f"T192 四层证据用例 {uniq}", "precondition": "无",
        "expected": "OK", "priority": 1, "caseType": 2, "endType": "web",
        "status": 3, "scriptMode": 1}))
    if conn_name:
        from conftest import require_db_url
        url = require_db_url()
        pwd = os.environ.get("DB_PASSWORD", "")
        assert pwd, "DB_PASSWORD 未设置（.env 未 source？连接造数前置）"
        assert _unwrap(api.post(f"/projects/{pid}/env-connections", json={
            "envId": env_id, "name": conn_name, "dialect": "pg", "jdbcUrl": url,
            "username": os.environ.get("DB_USERNAME", "testpike"), "password": pwd,
            "readonly": True, "maxPool": 1}))
    return pid, env_id, case_id


def _save_script(case_id, action_json):
    pass
    r = httpx.post(f"{CORE_BASE}/internal/scripts", timeout=20,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": action_json, "generatedBy": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", \
        f"save 脚本失败: {r.status_code} {r.text[:300]}"


def _run_to_terminal(api, case_id, env_id, deadline_s=300):
    pass
    r = api.post(f"/cases/{case_id}/trial-run", json={"envId": env_id})
    assert r.status_code == 200 and r.json().get("code") == "0", \
        f"试运行失败: {r.text[:300]}"
    task_id = r.json()["data"]["taskId"]
    h = {"Authorization": f"Bearer {_login()}"}
    deadline = time.time() + deadline_s
    detail = None
    while time.time() < deadline:
        detail = httpx.get(f"{API_BASE}/executors/tasks/{task_id}",
                           headers=h, timeout=10).json()["data"]
        if detail and detail["status"] in (3, 4, 5, 6):
            return task_id, detail
        time.sleep(2)
    raise AssertionError(f"任务 {task_id} {deadline_s}s 未到终态: {detail}"
                         "（runner 未启动/被并行会话抢占？）")


def _open_task_detail(page, task_id, executor_id):
    pass
    r = httpx.get(f"{API_BASE}/executors", headers={"Authorization": f"Bearer {_login()}"},
                  timeout=10)
    ex_name = next(e["name"] for e in r.json()["data"] if e["id"] == executor_id)
    card = (page.locator('[data-test="executor-card"]', has_text=ex_name)
            .filter(has_not_text=re.compile("离线|Offline")))
    page.goto(f"{BASE_URL}#/execution", wait_until="domcontentloaded")
    expect(card).to_be_visible()
    card.locator('[data-test="executor-tasks-btn"]').click()
    expect(page.locator('[data-test="exec-tasks-title"]')).to_be_visible()
    page.get_by_text("已执行", exact=False).first.click()
    row = page.locator('[data-test="exec-tasks-table"] tbody tr', has_text=f"#{task_id}")
    expect(row).to_have_count(1)
    row.locator('[data-test="exec-tasks-detail"]').click()
    expect(page.locator('[data-test="exec-detail-dialog"]')).to_be_visible()


def test_b11_console_evidence_display(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    _ensure_services()
    pid = None
    try:
        conn = f"DB_T192_{uniq[:6].upper()}"
        pid, env_id, case_id = _mk_stack(api_client, uniq, conn_name=conn)
        _save_script(case_id, _evidence_doc(conn))
        task_id, detail = _run_to_terminal(api_client, case_id, env_id)
        assert detail["status"] == 3, \
            f"证据用例应执行成功（warn 档仅记录不判败）: status={detail['status']} " \
            f"error={detail.get('errorDetail')}"

        ext = json.loads(detail["resultExt"])
        assert any("Uncaught" in v["text"] and v["level"] == "error"
                   for v in ext["consoleViolations"]), ext
        assert any("/api/order" in h["url"] and h["status"] == 404
                   for h in ext["netHits"]), ext
        assert any("sql.assert" in s for s in ext["dbDigest"]), ext

        _open_task_detail(page, task_id, detail["executorId"])
        dialog = page.locator('[data-test="exec-detail-dialog"]')
        expect(dialog.locator('[data-test="exec-evidence"]')).to_be_visible()
        expect(dialog.locator('[data-test="evidence-console"]')).to_contain_text(
            "Uncaught TypeError")
        expect(dialog.locator('[data-test="evidence-net"]')).to_contain_text("/api/order")
        expect(dialog.locator('[data-test="evidence-net"]')).to_contain_text("404")
        expect(dialog.locator('[data-test="evidence-db"]')).to_contain_text("sql.assert")
        expect(dialog.locator('[data-test="exec-console-log"]')).to_contain_text(
            "Uncaught TypeError")
        dialog.locator('[data-test="exec-detail-close"]').click()

        _open_task_detail(page, task_id, detail["executorId"])
        expect(page.locator('[data-test="exec-evidence"]')).to_be_visible()
        expect(page.locator('[data-test="evidence-console"]')).to_contain_text(
            "Uncaught TypeError")
        page.locator('[data-test="exec-detail-close"]').click()

        assert_no_errors(collectors, "B11 四层证据展示")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")


def test_b11_evidence_absent_zero_drift(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    _ensure_services()
    pid = None
    try:
        pid, env_id, case_id = _mk_stack(api_client, uniq)
        _save_script(case_id, _plain_doc())
        task_id, detail = _run_to_terminal(api_client, case_id, env_id)
        assert detail["status"] == 3, f"纯 UI 用例应成功: {detail.get('errorDetail')}"
        assert not detail.get("resultExt"), \
            f"零证据任务 resultExt 必空: {detail.get('resultExt')}"

        _open_task_detail(page, task_id, detail["executorId"])
        expect(page.locator('[data-test="exec-evidence"]')).to_have_count(0)
        expect(page.locator('[data-test="exec-console-log"]')).to_be_visible()
        page.locator('[data-test="exec-detail-close"]').click()

        assert_no_errors(collectors, "B11 证据零漂移负例")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")
