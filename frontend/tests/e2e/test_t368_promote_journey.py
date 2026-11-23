# -*- coding: utf-8 -*-
"""First full-journey E2E for sandbox promotion .

Real browser + real admin -> Feign -> core -> api-protocol -> live runner ->
Postgres chain (no mocks, no fake executor): a tuned sandbox request is run
green through the real executor, promoted into a regression case through the
UI, and the created case is then executed inside a suite-driven batch whose
report renders on the same chain. Double-direction lineage and reload
persistence are asserted on both sides.

Journey doors:
1. debug leg (API, real chain): POST /apitest/runs reaches status=1 through
 the live runner (tp_apitest_run reconciled by SQL);
2. promote leg (browser): deep link /#/api-test?projectId&requestId ->
 apitest-promote-btn -> confirm dialog -> success toast naming the case
 number -> apitest-promoted-badge visible;
3. lineage forward: badge click lands /cases/{id} with case-promoted-origin
 visible and the automation-target chip honestly saying API (promoted
 cases carry automation_target=api — the chip must not label them web);
4. lineage back: leave to dashboard and re-enter the same deep link — the
 badge is still there (promoted_case_id persisted, not frontend state);
5. suite leg: POST /suites + /suites/{id}/run -> plan/batch rows -> the live
 runner executes the promoted case task_type=1 -> batch reaches 6/7;
6. report leg (browser): /#/batches/{bid} shows batch-report-card with
 passCount>=1 — the promoted case proves itself on the same chain;
7. zero-tolerance gates: 4xx/5xx, console error/warn, browser Issues
 (assert_no_errors bundles all three).

Data is purged for real (project purge covers suites/plans/apitest assets);
the live runner keeps running throughout — unlike the fake-claim suites, this
journey needs the actual executor.
"""
import re
import time

import pytest
from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    TESTER_PASSWORD,
    assert_no_errors,
    inject_token,
    open_apitest_datasource_item,
)

pytestmark = pytest.mark.timeout(600)

LIVE_BASE = "http://127.0.0.1:8899"


def sql(q):
    import subprocess
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def wait_run_status(run_id, secs=120):
    """Poll the sandbox run to a terminal verdict (1=passed 2=assert-failed 3=exec-error)."""
    deadline = time.time() + secs
    while time.time() < deadline:
        st = sql(f"SELECT status FROM tp_apitest_run WHERE id={run_id}")
        if st in ("1", "2", "3"):
            return st
        time.sleep(2)
    return "timeout"


def wait_batch_final(bid, secs=180):
    deadline = time.time() + secs
    st = ""
    while time.time() < deadline:
        st = sql(f"SELECT status FROM tp_exec_batch WHERE id={bid}")
        if st in ("6", "7"):
            return st
        time.sleep(3)
    return st


def test_t368_promote_journey(page, collectors, api_client, uniq):
    suffix = uniq[:6]
    pid = col_id = req_id = run_id = case_id = suite_id = bid = None
    try:
        # ── Arrange: project + env + collection + one tuned request (real API) ──
        r = api_client.post("/projects", json={"name": f"T368旅程项目{suffix}", "code": f"P36{suffix}"})
        assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
        d = r.json()["data"]
        pid = d["id"] if isinstance(d, dict) else d

        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": f"T368旅程环境{suffix}", "envType": 2, "baseUrl": LIVE_BASE,
            "configJson": "{}", "endTypes": "web", "browsers": "chromium",
            "versionSnapshot": f"p36-{suffix}"})
        assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
        env = r.json()["data"]
        env_id = env["id"] if isinstance(env, dict) else env

        r = api_client.post("/apitest/collections", json={
            "projectId": pid, "parentId": 0, "name": f"T368集合{suffix}", "tags": ""})
        assert r.json().get("code") == "0", f"建集合失败: {r.text[:300]}"
        col_id = r.json()["data"]["id"]

        assertions = ('[{"type":"status","op":"equals","expect":200},'
                      '{"type":"body","path":"$.code","op":"contains","expect":0}]')
        r = api_client.post("/apitest/requests", json={
            "collectionId": col_id, "name": f"登录调通{suffix}", "method": "POST",
            "urlPath": "/api/login",
            "headersJson": '[{"name":"Content-Type","value":"application/json"}]',
            "bodyText": '{"username":"e2e","password":"***"}', "authJson": "{}",
            "assertionsJson": assertions})
        assert r.json().get("code") == "0", f"建请求失败: {r.text[:300]}"
        req_id = r.json()["data"]["id"]

        # ── Door 1: debug leg through the LIVE runner (no fake claim here) ──
        r = api_client.post("/apitest/runs", json={"requestId": req_id, "envId": env_id})
        assert r.json().get("code") == "0", f"发起调试失败: {r.text[:300]}"
        run_id = r.json()["data"]["id"]
        st = wait_run_status(run_id)
        err = sql(f"SELECT COALESCE(error_msg,'') FROM tp_apitest_run WHERE id={run_id}")[:200]
        assert st == "1", f"调试运行未绿（status={st} err={err}）——真 runner 链异常"

        # ── Door 2: promote through the UI (deep link with projectId+requestId) ──
        # stage 4: the cross-domain exits fold into the workspace-header
        # "More" dropdown — the item is reachable only after opening it (2 clicks).
        inject_token(page)
        page.goto(f"{BASE_URL}/#/api-test?projectId={pid}&requestId={req_id}",
                  wait_until="domcontentloaded")
        assert page.locator('[data-test="apitest-promoted-badge"]').count() == 0, \
            "未升格即出现徽标（badge 状态不干净）"
        open_apitest_datasource_item(page, "apitest-promote-btn",
                                     menu_anchor="apitest-more-actions-menu")
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=10000)
        expect(box).to_contain_text("变成回归用例")
        page.locator('.el-message-box__btns button.el-button--primary').click()
        expect(page.locator(".el-message--success").first).to_contain_text(
            "已生成回归用例", timeout=15000)
        badge = page.locator('[data-test="apitest-promoted-badge"]')
        expect(badge).to_be_visible(timeout=10000)

        # DB reconciliation of the promotion product (source=6 / api / sourceRef)
        case_id = int(sql(f"SELECT promoted_case_id FROM tp_apitest_request WHERE id={req_id}"))
        assert case_id > 0, "升格回写徽标列未落库"
        row = sql(f"SELECT source || '/' || COALESCE(automation_target,'null') || '/'"
                  f" || COALESCE(source_ref,'null') FROM tp_test_case WHERE id={case_id}")
        assert row == f"6/api/apitest:{req_id}", f"升格用例落库对账不符: {row}"

        # ── Door 3: lineage forward — badge click lands the case detail ──
        badge.click()
        page.wait_for_url(f"**/#/cases/{case_id}", timeout=15000)
        origin = page.locator('[data-test="case-promoted-origin"]')
        expect(origin).to_be_visible(timeout=15000)
        expect(origin).to_contain_text(f"沙盒请求 #{req_id}")
        chip = page.locator('[data-test="detail-automation-target"]')
        expect(chip).to_be_visible(timeout=10000)
        # Honest labelling: an api-target case must not be shown as a web case
        expect(chip).to_contain_text("接口", timeout=10000)

        # reload keeps both sides (persisted, not frontend state)
        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-promoted-origin"]')).to_be_visible(timeout=15000)

        # ── Door 4: lineage back — leave and re-enter the same deep link ──
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        page.goto(f"{BASE_URL}/#/api-test?projectId={pid}&requestId={req_id}",
                  wait_until="domcontentloaded")
        expect(page.locator('[data-test="apitest-promoted-badge"]')).to_be_visible(timeout=20000)

        # ── Door 5: suite leg — one-click plan/batch through the real runner ──
        # The promoted case lands pending review (status=1); the batch gate N6
        # honestly skips un-reviewed cases, so review it through the real
        # product route (pass on the current version) before the suite run.
        r = api_client.get(f"/cases/{case_id}")
        ver = r.json()["data"]["version"]
        r = api_client.post(f"/cases/{case_id}/review",
                            json={"action": "pass", "version": ver})
        assert r.json().get("code") == "0", f"评审通过失败: {r.text[:300]}"
        assert sql(f"SELECT status FROM tp_test_case WHERE id={case_id}") == "3", \
            "评审通过后用例状态未到已通过(3)"

        r = api_client.post("/users", json={"username": f"t368run{suffix}",
                                            "password": TESTER_PASSWORD,
                                            "displayName": f"T368执行人{suffix}", "role": 1})
        assert r.json().get("code") == "0", f"建执行人失败: {r.text[:300]}"
        uid = r.json()["data"]
        r = api_client.post(f"/projects/{pid}/members", json={"userId": uid, "role": 1})
        assert r.json().get("code") == "0", f"加成员失败: {r.text[:300]}"

        r = api_client.post("/suites", json={
            "projectId": pid, "name": f"T368回归集{suffix}", "caseIds": [case_id],
            "envIds": [env_id], "defaultAssigneeIds": [uid], "status": 1})
        assert r.json().get("code") == "0", f"建回归集失败: {r.text[:300]}"
        suite_id = r.json()["data"]["id"]

        r = api_client.post(f"/suites/{suite_id}/run", json={})
        body = r.json()
        assert body.get("code") == "0", f"一键生成失败: {r.text[:300]}"
        res = body["data"]
        assert res["batchCount"] >= 1 and res["taskCount"] >= 1, f"批次/任务未生成: {res}"
        plan_id = res["planId"]
        bid = int(sql(f"SELECT id FROM tp_exec_batch WHERE plan_id={plan_id} LIMIT 1"))
        assert bid > 0, "计划未挂批次"

        # ── Door 6: report leg — the live runner finishes, report card proves it ──
        st = wait_batch_final(bid)
        assert st in ("6", "7"), f"批次未在 180s 内收口（status={st}）——查 tp_exec_task 流转分型"
        # Task status domain: 1 pending 2 running 3 success 4 failed 5 timeout
        # 6 skipped — the promoted case must have a real success row.
        passed = sql(f"SELECT count(*) FROM tp_exec_task WHERE batch_id={bid} AND status=3")
        assert int(passed) >= 1, "升格用例批内未通过（期望≥1 条 status=3 成功）"

        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-report-card"]')).to_be_visible(timeout=15000)
        # Non-zero pass counter: the promoted case proved itself on the report.
        expect(page.locator('[data-test="batch-report-pass"]')).to_have_text(
            re.compile(r"[1-9]\d*"), timeout=15000)

        assert_no_errors(collectors, "T368 promote full journey")
    finally:
        # Purge for real through the product route (covers suites/plans/batches/
        # tasks/cases/scripts/apitest assets), then reconcile residue to zero.
        try:
            if bid:
                sql(f"UPDATE tp_exec_task SET status = 4 WHERE batch_id = {bid} AND status IN (1,2)")
            if pid:
                r = api_client.delete(f"/projects/{pid}/purge?force=true")
                assert r.json().get("code") == "0", f"purge 失败: {r.text[:200]}"
                for tbl in ("tp_exec_batch", "tp_regression_suite", "tp_exec_plan",
                            "tp_apitest_collection"):
                    left = sql(f"SELECT count(*) FROM {tbl} WHERE project_id={pid}")
                    assert left == "0", f"purge 后 {tbl} 残留 {left}"
        except Exception as cleanup_error:  # noqa: BLE001 — surface, never swallow
            print(f"[t368 cleanup] {cleanup_error}")
