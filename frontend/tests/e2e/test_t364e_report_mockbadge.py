# -*- coding: utf-8 -*-
"""First E2E for the batch report mock-serving badge .

Real browser + real admin -> Feign -> core -> api-protocol -> PG chain (no
mocks): the claim/result recipe mirrors the internal L3 mock-count script.

Assertion doors:
1. Batch A (one passed task carrying a non-empty mockNotes audit bucket, one
 plain fail): after finalization the report card header shows
 [data-test=batch-report-mock-badge] whose text contains the count 1;
2. clicking the badge lands on /mock/inspector with projectId prefilled
 (deep-link selects immediately, §12a pre-filter contract);
3. Batch B (plain: passes with empty mockNotes and with the key absent):
 report card renders but the badge never appears (no mock under pass);
4. reload keeps the badge (snapshot column direct read, not frontend state);
5. zero-tolerance gates: 4xx/5xx, console error/warn, browser Issues
 (assert_no_errors bundles all three).

Fixtures are purged for real; the live runner is paused around the fake
executor claims (same boundary as test_i7_batch_report.py).
"""
import re
import time

import httpx
import pytest
from playwright.sync_api import expect

from conftest import (
    ADMIN_PASSWORD,
    BASE_URL,
    api_login,
    assert_no_errors,
    executor_runner_control,
    probe_executor_runner_unit,
)

pytestmark = pytest.mark.timeout(420)

EXECUTOR_API = "http://localhost:8082"


def sql(q):
    import subprocess
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject_token(page):
    """Real login + token injection (i7 pattern)."""
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
                raise AssertionError("inject_token did not land on the workspace")
    raise AssertionError("unreachable")


def seed_case(api_client, pid, tag, suffix):
    r = api_client.post("/cases", json={
        "projectId": pid, "title": f"T364E报告用例{tag}{suffix}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2,
        "endType": "web", "status": 3,
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
    assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    cid = r.json()["data"]["id"]
    sql(f"""INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by)
             VALUES ({cid}, 'v2', '{{"caseId":{cid},"steps":[]}}', 2, 1)
             ON CONFLICT (case_id) DO NOTHING""")
    return cid


def run_batch(api_client, eid, bid, payloads):
    """start -> claim loop -> result per payload (real protocol, no mocks)."""
    r = api_client.post(f"/batches/{bid}/start", json={})
    assert r.json().get("code") == "0", f"启动失败: {r.text[:300]}"
    api_p = httpx.Client(base_url=EXECUTOR_API, timeout=15)
    got = 0
    deadline = time.time() + 90
    while time.time() < deadline and got < len(payloads):
        api_p.post(f"/internal/executors/heartbeat?executorId={eid}",
                   json={"runningTasks": got})
        t = (api_p.post("/internal/tasks/claim",
                        json={"executorId": eid}).json().get("data") or {}).get("task")
        if not t:
            time.sleep(2)
            continue
        body = {"taskId": t["taskId"], "claimToken": t["claimToken"],
                "durationMs": 1200, **payloads[got]}
        rr = api_p.post("/internal/tasks/result", json=body).json()
        assert (rr.get("data") or {}).get("accepted") is True, f"回传被拒: {rr}"
        got += 1
    assert got == len(payloads), f"90s 内未领满 {len(payloads)} 任务"
    st = ""
    for _ in range(15):
        st = sql(f"SELECT status FROM tp_exec_batch WHERE id={bid}")
        if st in ("6", "7"):
            break
        time.sleep(2)
    assert st in ("6", "7"), f"批次应收口，实为 {st}"


def test_t364e_report_mock_badge(page, collectors, api_client, uniq):
    suffix = uniq[:6]
    runner_unit = probe_executor_runner_unit()
    executor_runner_control(runner_unit, "stop")
    pid = bid_a = bid_b = None
    try:
        r = api_client.post("/projects", json={"name": f"T364E报告项目{suffix}",
                                               "code": f"E4P{suffix}"})
        assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
        pid = r.json()["data"]
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": f"T364E报告环境{suffix}", "envType": 2,
            "baseUrl": f"https://t364e-{suffix}.tp-local.test", "endTypes": "web",
            "versionSnapshot": f"e4rep-{suffix}"})
        assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
        env_id = r.json()["data"]

        # Batch A cases: mock-served pass + plain fail
        cases_a = [seed_case(api_client, pid, tag, suffix) for tag in ("甲", "乙")]
        r = api_client.post("/batches", json={
            "projectId": pid, "name": f"T364E mock批{suffix}", "envId": env_id,
            "caseIds": cases_a})
        assert r.json().get("code") == "0", f"建批A失败: {r.text[:300]}"
        bid_a = r.json()["data"]["id"]

        # Batch B cases: empty-array mockNotes + legacy payload without the key
        cases_b = [seed_case(api_client, pid, tag, suffix) for tag in ("丙", "丁")]
        r = api_client.post("/batches", json={
            "projectId": pid, "name": f"T364E 普批{suffix}", "envId": env_id,
            "caseIds": cases_b})
        assert r.json().get("code") == "0", f"建批B失败: {r.text[:300]}"
        bid_b = r.json()["data"]["id"]

        reg = httpx.post(f"{EXECUTOR_API}/internal/executors/register", timeout=15,
                         json={"name": f"t364e-{suffix}", "endTypes": "web",
                               "browser": "chromium", "capacity": 4,
                               "executorVersion": "1.0.0", "osInfo": "e2e"}).json()
        eid = reg["data"]["executorId"]
        assert eid, f"执行器注册失败: {reg}"

        mock_note = {"path": "/api/orders", "mode": "stub", "status": 200}
        run_batch(api_client, eid, bid_a, [
            {"result": "pass", "mockNotes": [mock_note]},
            {"result": "fail", "errorType": 2, "errorDetail": "E2E mock-badge fail sample"},
        ])
        run_batch(api_client, eid, bid_b, [
            {"result": "pass", "mockNotes": []},
            {"result": "pass"},
        ])

        assert sql(f"SELECT mock_task_cnt FROM tp_batch_report WHERE batch_id={bid_a}") == "1"
        assert sql(f"SELECT mock_task_cnt FROM tp_batch_report WHERE batch_id={bid_b}") == "0"

        inject_token(page)

        # ---- Batch A: badge visible with count text ----
        page.goto(f"{BASE_URL}/#/batches/{bid_a}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-report-card"]')).to_be_visible(timeout=15000)
        badge = page.locator('[data-test="batch-report-mock-badge"]')
        expect(badge).to_be_visible(timeout=15000)
        expect(badge).to_contain_text(re.compile(r"(?<!\d)1(?!\d)"))

        # ---- click jumps to the traffic screen with projectId prefilled.
        # AN-4: the badge door pushes the retired inspector bookmark (no
        # space id), the functional redirect lands on the spaces list carrying
        # the tab intent — the list IS the honest entry point now.
        badge.click()
        page.wait_for_function(
            "() => location.hash.startsWith('#/mock/spaces?')", timeout=15000)
        cur = page.evaluate("() => location.hash")
        assert f"projectId={pid}" in cur, f"深链未带项目上下文: {cur}"
        assert "tab=inspector" in cur, f"tab 意图未随跳转承接: {cur}"
        expect(page.locator('[data-test="mock-spaces-page"]')).to_be_visible(timeout=15000)

        # ---- Batch B (plain): report card renders, badge never appears ----
        page.goto(f"{BASE_URL}/#/batches/{bid_b}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-report-card"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-report-mock-badge"]')).to_have_count(0)

        # ---- reload persistence on Batch A (snapshot direct read) ----
        # Same-route id swap does not remount the detail component (stale data,
        # i7 precedent): detour through the dashboard for a fresh mount.
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.goto(f"{BASE_URL}/#/batches/{bid_a}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-report-card"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-report-mock-badge"]')).to_be_visible(timeout=15000)

        assert_no_errors(collectors, "T364E 报告 mock 角标 呈现/跳检视器/普批负谱/reload")
    finally:
        executor_runner_control(runner_unit, "start")
        for bid in (bid_a, bid_b):
            if bid:
                sql(f"UPDATE tp_exec_task SET status = 7 WHERE batch_id = {bid} AND status IN (1,2)")
        sql(f"DELETE FROM tp_executor WHERE name LIKE 't364e-{suffix}%'")
        if pid:
            api_client.delete(f"/projects/{pid}")
            r = api_client.delete(f"/projects/{pid}/purge?force=true")
            assert r.json().get("code") == "0", f"purge 失败: {r.text[:200]}"
        assert sql(f"SELECT count(*) FROM tp_exec_batch WHERE project_id={pid}") == "0"
