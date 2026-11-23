
import os
import subprocess
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, mk_tester, add_member, wait_tasks_settled, pick_project_in_header

DB_USER = os.environ.get("DB_USERNAME", "testpike")


def inject_token(page):
    pass
    from conftest import ADMIN_PASSWORD, api_login
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
                raise AssertionError(
                    "inject_token 注入后未进入工作台（my-project-table 不可见）——"
                    "疑似 WSL 网络层抖动致 /users/me 失败被守卫踢回登录；已重试一次仍失败"
                )
    raise AssertionError("unreachable")


def sql(q):
    full = subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", DB_USER,
                           "-d", "testpike", "-tAc", q], capture_output=True, text=True)
    if full.returncode != 0:
        raise RuntimeError(f"SQL 失败: {q} -> {full.stderr[:300]}")
    return full.stdout.strip()


def setup_plan_dataset(api, uniq, with_plan=True):
    pass
    code = f"L7B{uniq[:6]}"
    r = api.post("/projects", json={"name": f"L7看板项目{uniq}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    env_ids = []
    for etype, tag in ((2, "t"), (3, "p")):
        r = api.post(f"/projects/{pid}/envs", json={
            "name": f"看板环境{tag}{uniq[:6]}", "envType": etype,
            "baseUrl": f"https://{tag}-{uniq[:6]}.tp-local.test"})
        assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
        env_ids.append(r.json()["data"])
    case_ids = []
    for title, phase, prio in (("看板冒烟", 1, 0), ("看板功能", 2, 1), ("看板回归A", 3, 1), ("看板回归B", 3, 2)):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"{title}{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": prio, "confidence": 2,
            "endType": "web", "status": 3, "phase": phase,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])
    tester, _ = mk_tester(api, uniq, prefix="l7t")
    add_member(api, pid, tester)
    plan_id = None
    if with_plan:
        sql(f"UPDATE tp_test_case SET last_exec_result = 1 WHERE id = {case_ids[0]}")
        for cid in case_ids:
            action = '{"caseId": %d, "setup": [], "steps": []}' % cid
            sql(f"INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
                f"VALUES ({cid}, 'v2', '{action}', 1, 1) ON CONFLICT (case_id) DO NOTHING")
        r = api.post("/plans", json={
            "projectId": pid, "name": f"看板计划{uniq[:6]}", "targetVersion": "v2.1.0",
            "envIds": env_ids,
            "caseScope": {"phases": [1, 2, 3], "priorities": [0, 1, 2], "keyword": None, "tagIds": []},
            "entryCriteria": {"requireSmokePass": True},
            "exitCriteria": {"minPassRate": 0.95, "noOpenDefects": True},
            "planStart": "2026-09-01", "planEnd": "2026-09-10"})
        assert r.json().get("code") == "0" and r.json()["data"]["caseCount"] == 4, f"建计划失败: {r.text[:300]}"
        plan_id = r.json()["data"]["id"]
        r = api.post(f"/plans/{plan_id}/assign", json={"caseIds": case_ids, "assigneeId": tester})
        assert r.json().get("data") == 4, f"分配失败: {r.text[:300]}"
        r = api.post(f"/plans/{plan_id}/publish")
        assert r.json().get("code") == "0", f"发布失败: {r.text[:300]}"
        r = api.post(f"/plans/{plan_id}/generate-batches")
        gb = r.json()["data"]
        assert gb["batchCount"] == 2 and gb["taskCount"] == 4, f"生成批次失败: {r.text[:300]}"
        wait_tasks_settled(plan_id)
        sql(f"UPDATE tp_plan_case SET status = 3 WHERE plan_id = {plan_id} AND case_id = {case_ids[0]}")
        sql(f"UPDATE tp_plan_case SET status = 4 WHERE plan_id = {plan_id} AND case_id = {case_ids[1]}")
        sql(f"UPDATE tp_plan_case SET status = 2 WHERE plan_id = {plan_id} AND case_id = {case_ids[2]}")
        sql(f"UPDATE tp_plan_case SET status = 1 WHERE plan_id = {plan_id} AND case_id = {case_ids[3]}")
        sql(f"UPDATE tp_exec_task SET status = 3 WHERE case_id = {case_ids[0]}")
        sql(f"UPDATE tp_exec_task SET status = 4 WHERE case_id = {case_ids[1]}")
        sql(f"UPDATE tp_exec_task SET status = 2 WHERE case_id = {case_ids[2]}")
        sql(f"UPDATE tp_exec_task SET status = 2 WHERE case_id = {case_ids[3]}")
    return pid, plan_id, case_ids, tester


def cleanup_project(api, pid):
    api.delete(f"/projects/{pid}")


def test_l7_board_page(page, collectors, api_client, uniq):
    pass
    pid, plan_id, _, _ = setup_plan_dataset(api_client, uniq)
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/plans/board", wait_until="domcontentloaded")
        expect(page.locator('[data-test="board-page"]')).to_be_visible(timeout=15000)

        # Assertion fix : board carries no per-page selector since
        # the IA pass — switch the global header project context instead.
        pick_project_in_header(page, f"L7看板项目{uniq}")
        expect(page.locator('[data-test="board-table"]')).to_be_visible(timeout=15000)

        expect(page.locator('[data-test="board-stat-total"]')).to_contain_text("1", timeout=10000)
        expect(page.locator('[data-test="board-stat-running"]')).to_contain_text("1", timeout=10000)
        expect(page.locator('[data-test="board-stat-rate"]')).to_contain_text("50.0%", timeout=10000)

        expect(page.locator('[data-test="board-table"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="board-table"]').first).to_contain_text(
            f"看板计划{uniq[:6]}", timeout=10000)
        expect(page.locator('[data-test="board-status-tag"]').first).to_contain_text("执行中", timeout=10000)
        expect(page.locator('[data-test="board-rate-text"]').first).to_contain_text("50.0%", timeout=10000)

        page.locator('[data-test="board-plan-link"]').first.click()
        expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="plan-detail-name"]').first).to_contain_text(
            f"看板计划{uniq[:6]}", timeout=10000)

        # Back to the board after reload → data persisted (header context survives
        # via localStorage; re-pin it to this test's project)
        page.goto(f"{BASE_URL}/#/plans/board", wait_until="domcontentloaded")
        expect(page.locator('[data-test="board-page"]')).to_be_visible(timeout=15000)
        pick_project_in_header(page, f"L7看板项目{uniq}")
        expect(page.locator('[data-test="board-table"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="board-table"]').first).to_contain_text(
            f"看板计划{uniq[:6]}", timeout=10000)
        expect(page.locator('[data-test="board-rate-text"]').first).to_contain_text("50.0%", timeout=10000)

        assert_no_errors(collectors, "L7 看板页")
    finally:
        cleanup_project(api_client, pid)


def test_l7_board_dims_and_summary(api_client, uniq):
    pass
    pid, plan_id, case_ids, _ = setup_plan_dataset(api_client, uniq)
    try:
        r = api_client.get(f"/plans/{plan_id}/progress")
        assert r.status_code == 200, f"progress 失败: {r.text[:300]}"
        pv = r.json()["data"]
        assert pv["total"] == 4 and pv["passed"] == 1 and pv["failed"] == 1 \
            and pv["running"] == 1 and pv["notRun"] == 1 and pv["skipped"] == 0, f"状态计数错误: {pv}"
        assert abs(pv["passRate"] - 0.5) < 1e-6, f"通过率错误: {pv['passRate']}"
        assert any(d["label"].startswith("l7t测试员") for d in pv["byAssignee"]), f"人员维度: {pv['byAssignee']}"
        assert any("冒烟" in d["label"] and d["passed"] == 1 for d in pv["byPhase"]), f"阶段维度: {pv['byPhase']}"
        assert len(pv["byEnv"]) == 2, f"环境维度应 2 行: {pv['byEnv']}"
        assert all(d["total"] == 2 for d in pv["byEnv"]), f"环境行各 2 条: {pv['byEnv']}"

        r = api_client.post("/plans", json={
            "projectId": pid, "name": f"草稿计划{uniq[:6]}", "targetVersion": "v2.1.0",
            "envIds": [], "caseScope": {"phases": [3], "priorities": [2], "keyword": None, "tagIds": []},
            "entryCriteria": {"requireSmokePass": False},
            "exitCriteria": {"minPassRate": 0.9, "noOpenDefects": False},
            "planStart": "2026-09-05", "planEnd": "2026-09-12"})
        assert r.status_code == 400, f"envIds 空应 400: {r.text[:300]}"
        r = api_client.post("/plans", json={
            "projectId": pid, "name": f"草稿计划{uniq[:6]}", "targetVersion": "v2.1.0",
            "envIds": [sql(f"SELECT id FROM tp_project_env WHERE project_id = {pid} LIMIT 1")],
            "caseScope": {"phases": [3], "priorities": [2], "keyword": None, "tagIds": []},
            "entryCriteria": {"requireSmokePass": False},
            "exitCriteria": {"minPassRate": 0.9, "noOpenDefects": False},
            "planStart": "2026-09-05", "planEnd": "2026-09-12"})
        assert r.json().get("code") == "0" and r.json()["data"]["caseCount"] == 1, f"建草稿计划失败: {r.text[:300]}"
        draft_id = r.json()["data"]["id"]

        r = api_client.get(f"/plans/progress-summary?projectId={pid}")
        assert r.status_code == 200, f"summary 失败: {r.text[:300]}"
        rows = r.json()["data"]
        assert len(rows) == 2, f"应 2 个计划: {rows}"
        r1 = next(x for x in rows if x["planId"] == plan_id)
        assert r1["statusLabel"] == "执行中" and r1["caseCount"] == 4 and r1["passed"] == 1 \
            and r1["failed"] == 1 and r1["running"] == 1 and r1["notRun"] == 1 \
            and abs(r1["passRate"] - 0.5) < 1e-6, f"P1 汇总错误: {r1}"
        r2 = next(x for x in rows if x["planId"] == draft_id)
        assert r2["statusLabel"] == "草稿" and r2["caseCount"] == 1 and r2["notRun"] == 1 \
            and r2["passRate"] == 0.0, f"P2 汇总错误: {r2}"
    finally:
        cleanup_project(api_client, pid)


def test_l7_board_permission(api_client, uniq):
    pass
    pid_a, plan_id, _, _ = setup_plan_dataset(api_client, uniq)
    pid_b = None
    try:
        r = api_client.post("/projects", json={"name": f"L7其他项目{uniq}", "code": f"L7Q{uniq[:6]}"})
        pid_b = r.json()["data"]
        r = api_client.post(f"/projects/{pid_b}/envs", json={
            "name": f"看板环境B{uniq[:6]}", "envType": 2,
            "baseUrl": f"https://b-{uniq[:6]}.tp-local.test"})
        env_b = r.json()["data"]
        r = api_client.post("/cases", json={
            "projectId": pid_b, "title": f"看板B用例{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3, "phase": 3,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
        cb = r.json()["data"]["id"]
        r = api_client.post("/plans", json={
            "projectId": pid_b, "name": f"B计划{uniq[:6]}", "targetVersion": "v1.0.0",
            "envIds": [env_b],
            "caseScope": {"phases": [3], "priorities": [1], "keyword": None, "tagIds": []},
            "entryCriteria": {"requireSmokePass": False},
            "exitCriteria": {"minPassRate": 0.9, "noOpenDefects": False},
            "planStart": "2026-09-01", "planEnd": "2026-09-10"})
        assert r.json().get("code") == "0", f"建 B 计划失败: {r.text[:300]}"
        p3 = r.json()["data"]["id"]

        rows_b = api_client.get(f"/plans/progress-summary?projectId={pid_b}").json()["data"]
        assert len(rows_b) == 1 and rows_b[0]["planId"] == p3, f"B 汇总应仅 P3: {rows_b}"
        rows_a = api_client.get(f"/plans/progress-summary?projectId={pid_a}").json()["data"]
        assert all(x["planId"] != p3 for x in rows_a), "A 汇总不应含 B 计划（IT-L7-01）"

        r = api_client.post("/users", json={
            "username": f"l7x{uniq[:6]}", "password": "Test@12345",
            "displayName": f"跨项目用户{uniq[:6]}", "email": f"l7x{uniq[:6]}@test.local",
            "phone": "", "role": 1})
        assert r.json().get("code") == "0", f"建跨项目用户失败: {r.text[:300]}"
        uid_x = r.json()["data"]
        add_member(api_client, pid_b, uid_x)
        login = httpx.post(f"{API_BASE}/auth/login",
                           json={"username": f"l7x{uniq[:6]}", "password": "Test@12345"}, timeout=10)
        assert login.status_code == 200 and login.json().get("code") == "0", \
            f"跨项目用户登录失败: {login.status_code} {login.text[:200]}"
        token_b = login.json()["data"]["accessToken"]
        hb = {"Authorization": f"Bearer {token_b}"}
        r = httpx.get(f"{API_BASE}/plans/progress-summary?projectId={pid_a}", headers=hb, timeout=10)
        assert r.status_code == 403 and r.json().get("code") == "40301", \
            f"非成员查 A 看板应 403: {r.status_code} {r.text[:200]}"
        r = httpx.get(f"{API_BASE}/plans/{plan_id}/progress", headers=hb, timeout=10)
        assert r.status_code == 403 and r.json().get("code") == "40301", \
            f"非成员查 A 进度应 403: {r.status_code} {r.text[:200]}"
    finally:
        cleanup_project(api_client, pid_a)
        if pid_b:
            cleanup_project(api_client, pid_b)
