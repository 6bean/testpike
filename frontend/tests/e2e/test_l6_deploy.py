
import os
import subprocess
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, mk_tester, add_member, row_cmd

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


def setup_env_dataset(api, uniq):
    pass
    code = f"L6E{uniq[:6]}"
    r = api.post("/projects", json={"name": f"L6部署项目{uniq}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"部署环境{uniq[:6]}", "envType": 2, "baseUrl": f"https://t-{uniq[:6]}.tp-local.test"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_id = r.json()["data"]
    tester, _ = mk_tester(api, uniq, prefix="l6t")
    add_member(api, pid, tester)
    return pid, env_id, tester


def cleanup_project(api, pid):
    api.delete(f"/projects/{pid}")


def test_l6_deploy_record_persist(page, collectors, api_client, uniq):
    pass
    pid, env_id, _ = setup_env_dataset(api_client, uniq)
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="env-table"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="env-deploy-none"]').first).to_be_visible(timeout=10000)

        row_cmd(page, page.locator('[data-test="env-table"] tbody tr').first, "deploy")
        expect(page.locator('[data-test="deploy-dialog"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="deploy-version-input"]').fill(f"v2.0.1-{uniq[:6]}")
        page.locator('[data-test="deploy-submit"]').click()
        expect(page.locator(".el-message--success").first).to_contain_text("部署记录已保存", timeout=10000)

        expect(page.locator('[data-test="env-deploy-version"]').first).to_contain_text(
            f"v2.0.1-{uniq[:6]}", timeout=10000)

        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="env-table"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="env-deploy-version"]').first).to_contain_text(
            f"v2.0.1-{uniq[:6]}", timeout=10000)
        expect(page.locator('[data-test="env-table"]').first).to_contain_text("系统管理员", timeout=10000)

        assert_no_errors(collectors, "L6 记录部署")
    finally:
        cleanup_project(api_client, pid)


def test_l6_deploy_batch_attribution(page, collectors, api_client, uniq):
    pass
    pid, env_id, _ = setup_env_dataset(api_client, uniq)
    try:
        sql(f"INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, "
            f"env_version_snapshot, type, schedule_type, created_by) "
            f"VALUES ({pid}, {env_id}, 'B1-{uniq[:6]}', '批次一', 6, 'v1.0.0', 1, 0, 1)")
        sql(f"INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, "
            f"env_version_snapshot, type, schedule_type, created_by) "
            f"VALUES ({pid}, {env_id}, 'B2-{uniq[:6]}', '批次二', 6, 'v1.1.0', 1, 0, 1)")
        r = api_client.put(f"/envs/{env_id}/deploy-info", json={"version": "v1.1.0"})
        assert r.status_code == 200 and r.json().get("code") == "0", f"记录部署失败: {r.text[:300]}"

        inject_token(page)
        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="env-table"]')).to_be_visible(timeout=15000)
        row_cmd(page, page.locator('[data-test="env-table"] tbody tr').first, "deploy")
        expect(page.locator('[data-test="deploy-dialog"]')).to_be_visible(timeout=10000)

        expect(page.locator('[data-test="deploy-batch-table"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="deploy-batch-table"] [data-test="deploy-batch-matched"]')
               ).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="deploy-batch-table"] [data-test="deploy-batch-unmatched"]')
               ).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="deploy-batch-table"]')).to_contain_text("v1.1.0")
        expect(page.locator('[data-test="deploy-batch-table"]')).to_contain_text("v1.0.0")
        expect(page.locator('[data-test="deploy-current-version"]')).to_contain_text("v1.1.0")

        assert_no_errors(collectors, "L6 批次归因对照")
    finally:
        cleanup_project(api_client, pid)


def test_l6_deploy_permission_403(api_client, uniq):
    pass
    pid, env_id, tester = setup_env_dataset(api_client, uniq)
    try:
        r = httpx.post(f"{API_BASE}/auth/login",
                       json={"username": f"l6ttester_{uniq}", "password": "Test@12345"}, timeout=10)
        assert r.status_code == 200 and r.json().get("code") == "0", f"tester 登录失败: {r.text[:300]}"
        h2 = {"Authorization": f"Bearer {r.json()['data']['accessToken']}"}

        r = httpx.put(f"{API_BASE}/envs/{env_id}/deploy-info",
                      json={"version": "v9.0.0"}, headers=h2, timeout=10)
        assert r.status_code == 403 and r.json().get("code") == "40301", f"期望 403: {r.status_code} {r.text[:300]}"
        assert "仅项目负责人" in r.json().get("msg", ""), f"人话提示: {r.json().get('msg')}"

        r = httpx.get(f"{API_BASE}/envs/{env_id}/deploy-info", headers=h2, timeout=10)
        assert r.status_code == 200 and r.json().get("code") == "0", f"成员查询应 200: {r.text[:300]}"

        r = httpx.put(f"{API_BASE}/envs/{env_id}/deploy-info",
                      json={"version": "  "}, headers=api_client.headers, timeout=10)
        assert r.status_code == 400, f"版本空应 400: {r.status_code} {r.text[:300]}"

        code_b = f"L6B{uniq[:6]}"
        r = api_client.post("/projects", json={"name": f"L6项目B{uniq}", "code": code_b})
        pid_b = r.json()["data"]
        r = api_client.post("/users", json={
            "username": f"l6b_{uniq}", "password": "Test@12345", "displayName": f"项目B用户{uniq[:6]}", "role": 1})
        uid_b = r.json()["data"]
        add_member(api_client, pid_b, uid_b)
        r = httpx.post(f"{API_BASE}/auth/login",
                       json={"username": f"l6b_{uniq}", "password": "Test@12345"}, timeout=10)
        h3 = {"Authorization": f"Bearer {r.json()['data']['accessToken']}"}
        r = httpx.get(f"{API_BASE}/envs/{env_id}/deploy-info", headers=h3, timeout=10)
        assert r.status_code == 403 and r.json().get("code") == "40301", \
            f"跨项目查询应 403: {r.status_code} {r.text[:300]}"
        api_client.delete(f"/projects/{pid_b}")
    finally:
        cleanup_project(api_client, pid)
