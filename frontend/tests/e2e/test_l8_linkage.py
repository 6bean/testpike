
import os
import subprocess
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, mk_tester, add_member

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


def setup_project(api, uniq, tag="L8"):
    pass
    code = f"{tag}P{uniq[:6]}"
    r = api.post("/projects", json={"name": f"{tag}联动项目{uniq}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"联动环境{uniq[:6]}", "envType": 2,
        "baseUrl": f"https://t-{uniq[:6]}.tp-local.test"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_id = r.json()["data"]
    case_ids = []
    for title, phase in (("联动冒烟", 1), ("联动功能", 2)):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"{title}{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3, "phase": phase,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])
    return pid, env_id, case_ids


def cleanup_project(api, pid):
    api.delete(f"/projects/{pid}")


def test_l8_pool_tab(page, collectors, api_client, uniq):
    pass
    pid, env_id, _ = setup_project(api_client, uniq)
    try:
        sql(f"INSERT INTO tp_data_pool_item (project_id, env_id, type, value, value_hash, status, created_by) "
            f"VALUES ({pid}, {env_id}, '订单', 'ORD-L8-{uniq[:4]}', 'h{uniq[:4]}', 2, 1) ON CONFLICT DO NOTHING")
        inject_token(page)

        # T396b posture: land on the freshly created project via the header
        # project context deep link (in-page selector retired) — the default
        # context project is the user's first project, not this test's data.
        page.goto(f"{BASE_URL}/#/data-factories?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="datafactory-tabs"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="datafactory-tabs"] .el-tabs__item:has-text("数据池")').click()
        expect(page.locator('[data-test="pool-table"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="pool-table"]').first).to_contain_text(
            f"ORD-L8-{uniq[:4]}", timeout=15000)

        expect(page.locator('[data-test="pool-value"]').first).to_contain_text(
            f"ORD-L8-{uniq[:4]}", timeout=10000)
        expect(page.locator('[data-test="pool-status-tag"]').first).to_contain_text("占用中", timeout=10000)

        page.locator('[data-test="pool-recycle-btn"]').first.click()
        expect(page.locator('[data-test="pool-status-tag"]').first).to_contain_text("空闲", timeout=10000)

        page.locator('[data-test="pool-abnormal-btn"]').first.click()
        expect(page.locator('[data-test="pool-status-tag"]').first).to_contain_text("已失效", timeout=10000)

        page.locator('[data-test="pool-recycle-btn"]').first.click()
        expect(page.locator('[data-test="pool-status-tag"]').first).to_contain_text("空闲", timeout=10000)
        page.goto(f"{BASE_URL}/#/data-factories?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="datafactory-tabs"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="datafactory-tabs"] .el-tabs__item:has-text("数据池")').click()
        expect(page.locator('[data-test="pool-table"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="pool-status-tag"]').first).to_contain_text("空闲", timeout=15000)

        page.locator('[data-test="pool-delete-btn"]').first.click()
        page.locator('.el-message-box button:has-text("删除")').click()
        expect(page.locator('[data-test="pool-table"] [data-test="empty-state"]')).to_be_visible(timeout=15000)

        assert_no_errors(collectors, "L8 数据池 tab")
    finally:
        cleanup_project(api_client, pid)


def test_l8_report_plan_filter(page, collectors, api_client, uniq):
    pass
    pid, env_id, case_ids = setup_project(api_client, uniq)
    try:
        sql(f"UPDATE tp_test_case SET last_exec_result = 1 WHERE id = {case_ids[0]}")
        for cid in case_ids:
            action = '{"caseId": %d, "setup": [], "steps": []}' % cid
            sql(f"INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
                f"VALUES ({cid}, 'v2', '{action}', 1, 1) ON CONFLICT (case_id) DO NOTHING")
        r = api_client.post("/plans", json={
            "projectId": pid, "name": f"联动计划{uniq[:6]}", "targetVersion": "v2.2.0",
            "envIds": [env_id],
            "caseScope": {"phases": [1, 2], "priorities": [1], "keyword": None, "tagIds": []},
            "entryCriteria": {"requireSmokePass": True},
            "exitCriteria": {"minPassRate": 0.95, "noOpenDefects": True},
            "planStart": "2026-09-01", "planEnd": "2026-09-30"})
        assert r.json().get("code") == "0" and r.json()["data"]["caseCount"] == 2, f"建计划失败: {r.text[:300]}"
        plan_id = r.json()["data"]["id"]
        r = api_client.post(f"/plans/{plan_id}/assign", json={"caseIds": case_ids, "assigneeId": None})
        tester, _ = mk_tester(api_client, uniq, prefix="l8t")
        add_member(api_client, pid, tester)
        r = api_client.post(f"/plans/{plan_id}/assign", json={"caseIds": case_ids, "assigneeId": tester})
        assert r.json().get("code") == "0", f"分配失败: {r.text[:300]}"
        r = api_client.post(f"/plans/{plan_id}/publish")
        assert r.json().get("code") == "0", f"发布失败: {r.text[:300]}"
        r = api_client.post(f"/plans/{plan_id}/generate-batches")
        gb = r.json()["data"]
        assert gb["batchCount"] == 1 and gb["taskCount"] == 2, f"生成批次失败: {r.text[:300]}"
        sql(f"UPDATE tp_exec_task SET status = 3, finished_at = now() WHERE case_id = {case_ids[0]} "
            f"AND batch_id IN (SELECT id FROM tp_exec_batch WHERE plan_id = {plan_id})")
        sql(f"UPDATE tp_exec_task SET status = 4, error_type = 1, error_detail = 'E2E 构造失败' "
            f"WHERE case_id = {case_ids[1]} "
            f"AND batch_id IN (SELECT id FROM tp_exec_batch WHERE plan_id = {plan_id})")
        inject_token(page)

        page.goto(f"{BASE_URL}/#/report?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="report-tabs"]')).to_be_visible(timeout=15000)
        page.locator('.el-tabs__item:has-text("执行统计")').click()
        expect(page.locator('[data-test="exec-stats"]')).to_be_visible(timeout=15000)

        page.locator('[data-test="project-ctx-select"]').click()
        page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:visible:has-text("联动项目{uniq[:6]}")').first.click()
        expect(page.locator('[data-test="exec-stats"]')).to_be_visible(timeout=15000)

        page.locator('[data-test="exec-plan-select"]').click()
        page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:visible:has-text("联动计划{uniq[:6]}")').first.click()
        expect(page.locator('[data-test="exec-pass"]')).to_contain_text("1", timeout=10000)
        expect(page.locator('[data-test="exec-fail"]')).to_contain_text("1", timeout=10000)

        assert_no_errors(collectors, "L8 报表计划维度")
    finally:
        cleanup_project(api_client, pid)


def test_l8_pool_permission(api_client, uniq):
    pass
    pid, env_id, _ = setup_project(api_client, uniq)
    pid_b = None
    try:
        sql(f"INSERT INTO tp_data_pool_item (project_id, env_id, type, value, value_hash, status, created_by) "
            f"VALUES ({pid}, {env_id}, '订单', 'ORD-L8-{uniq[:4]}', 'h{uniq[:4]}', 2, 1) ON CONFLICT DO NOTHING")

        tester, _ = mk_tester(api_client, uniq, prefix="l8p")
        add_member(api_client, pid, tester)
        login = httpx.post(f"{API_BASE}/auth/login",
                           json={"username": f"l8ptester_{uniq}", "password": "Test@12345"}, timeout=10)
        assert login.status_code == 200 and login.json().get("code") == "0", f"tester 登录失败: {login.text[:200]}"
        ht = {"Authorization": f"Bearer {login.json()['data']['accessToken']}"}
        r = httpx.put(f"{API_BASE}/data-factories/pool-items/1/status",
                      params={"projectId": pid, "status": 1}, headers=ht, timeout=10)
        assert r.status_code == 403 and r.json().get("code") == "40301", \
            f"普通成员改池应 40301: {r.status_code} {r.text[:200]}"

        r = api_client.post("/projects", json={"name": f"隔离项目{uniq}", "code": f"L8B{uniq[:6]}"})
        pid_b = r.json()["data"]
        rows = api_client.get(f"/data-factories/pool-items?projectId={pid_b}").json()["data"]
        assert rows["total"] == 0, f"项目 B 查 A 池应为空: {rows}"
        items = api_client.get(f"/data-factories/pool-items?projectId={pid}").json()["data"]["list"]
        item_id = items[0]["id"]
        from conftest import ADMIN_PASSWORD, api_login
        admin_token = api_login("admin", ADMIN_PASSWORD)
        ha = {"Authorization": f"Bearer {admin_token}"}
        r = httpx.put(f"{API_BASE}/data-factories/pool-items/{item_id}/status",
                      params={"projectId": pid_b, "status": 1}, headers=ha, timeout=10)
        assert r.status_code == 404, f"跨项目改池应 404: {r.status_code} {r.text[:200]}"

        r = api_client.get(f"/data-factories/pool-items?projectId={pid}")
        assert r.json().get("code") == "0" and r.json()["data"]["total"] == 1, f"负责人查池应 1 条: {r.text[:200]}"
    finally:
        cleanup_project(api_client, pid)
        if pid_b:
            cleanup_project(api_client, pid_b)
