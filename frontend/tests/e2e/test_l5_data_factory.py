
import json
import os
import subprocess
import threading
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, mk_tester, add_member, row_cmd

DB_USER = os.environ.get("DB_USERNAME", "testpike")
FACTORY_MENU = "自动造数"


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


def start_order_server():
    pass
    import http.server

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            body = json.dumps({"code": "0", "data": [
                {"orderNo": f"ORD-E2E-{uuid.uuid4().hex[:6]}"},
                {"orderNo": f"ORD-E2E-{uuid.uuid4().hex[:6]}"}],
                "extra": 1}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):  # noqa: A002
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def setup_factory_dataset(api, uniq, order_url=None):
    pass
    code = f"L5E{uniq[:6]}"
    r = api.post("/projects", json={"name": f"L5造数项目{uniq}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"造数环境{uniq[:6]}", "envType": 2, "baseUrl": f"https://t-{uniq[:6]}.tp-local.test"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_id = r.json()["data"]
    tester, _ = mk_tester(api, uniq, prefix="l5t")
    add_member(api, pid, tester)
    content = json.dumps({
        "poolType": "订单", "method": "GET", "url": order_url,
        "headers": {}, "body": {}, "extractExpr": "$.data[*].orderNo"}, ensure_ascii=False)
    r = api.post("/data-factories", json={
        "projectId": pid, "envId": env_id, "type": 1, "name": f"订单造数{uniq[:6]}", "content": content})
    assert r.json().get("code") == "0", f"建造数脚本失败: {r.text[:300]}"
    fid = r.json()["data"]["id"]
    return pid, env_id, fid


def cleanup_project(api, pid):
    api.delete(f"/projects/{pid}")


def test_l5_factory_run_history_persist(page, collectors, api_client, uniq):
    pass
    server = start_order_server()
    order_url = f"http://127.0.0.1:{server.server_port}/orders"
    pid, env_id, fid = setup_factory_dataset(api_client, uniq, order_url)
    try:
        inject_token(page)
        # T396b posture: ride the header project context via deep link
        # (in-page selector retired); reload refreshes the visible-domain snapshot.
        page.goto(f"{BASE_URL}/#/data-factories?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="datafactory-list-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="datafactory-table"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="datafactory-name-link"]').first).to_contain_text(f"订单造数{uniq[:6]}")

        row_cmd(page, page.locator('[data-test="datafactory-table"] tbody tr').first, "run")
        expect(page.locator(".el-message-box")).to_be_visible(timeout=5000)
        page.locator('.el-message-box button:has-text("执行")').click()
        expect(page.locator(".el-message--success")).to_contain_text("入池 2 条", timeout=15000)

        row_cmd(page, page.locator('[data-test="datafactory-table"] tbody tr').first, "history")
        expect(page.locator('[data-test="datafactory-history-drawer"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="datafactory-history-table"]')).to_contain_text("造了 2 条", timeout=10000)
        page.keyboard.press("Escape")
        expect(page.locator('[data-test="datafactory-history-drawer"]')).to_be_hidden(timeout=5000)

        pool = sql(f"SELECT count(*) FROM tp_data_pool_item WHERE env_id = {env_id} AND type = '订单'")
        assert pool == "2", f"数据池应 2 条，实际 {pool}"
        row = sql(f"SELECT value, value_hash FROM tp_data_pool_item WHERE env_id = {env_id} LIMIT 1")
        assert "ORD-E2E" not in row.split("|")[0], "value 应为加密非明文"
        assert len(row.split("|")[1]) == 64, "value_hash 应为 64 位 hex"

        # T396b posture: ride the header project context via deep link
        # (in-page selector retired); reload refreshes the visible-domain snapshot.
        page.goto(f"{BASE_URL}/#/data-factories?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="datafactory-list-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="datafactory-name-link"]').first).to_contain_text(f"订单造数{uniq[:6]}", timeout=15000)

        page.locator('[data-test="datafactory-name-link"]').first.click()
        expect(page.locator('[data-test="datafactory-detail-drawer"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="datafactory-content-preview"]')).to_contain_text("poolType")
        page.keyboard.press("Escape")

        assert_no_errors(collectors, "L5 执行/历史/持久化")
    finally:
        server.shutdown()
        server.server_close()
        cleanup_project(api_client, pid)


def test_l5_factory_create_edit_delete(page, collectors, api_client, uniq):
    pass
    server = start_order_server()
    order_url = f"http://127.0.0.1:{server.server_port}/orders"
    pid, env_id, fid = setup_factory_dataset(api_client, uniq, order_url)
    new_name = f"SQL造号{uniq[:6]}"
    try:
        inject_token(page)
        # T396b posture: ride the header project context via deep link
        # (in-page selector retired); reload refreshes the visible-domain snapshot.
        page.goto(f"{BASE_URL}/#/data-factories?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="datafactory-list-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="datafactory-table"]')).to_be_visible(timeout=15000)

        page.locator('[data-test="datafactory-create-btn"]').click()
        expect(page.locator('[data-test="datafactory-dialog"]')).to_be_visible()
        page.locator('[data-test="datafactory-form-name"]').fill(new_name)
        page.locator('[data-test="datafactory-form-type"]').get_by_text("SQL 造数").click()
        page.locator('[data-test="datafactory-form-env"]').click()
        page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("造数环境{uniq[:6]}"):visible').first.click()
        sql_content = (f'{{"poolType":"账号","sql":"INSERT INTO tp_project_env '
                       f"(project_id, name, env_type, base_url) VALUES ({pid}, 'E2E环境{uniq[:6]}', 3, "
                       f"'https://e2e-{uniq[:6]}.tp-local.test') RETURNING name\"}}")
        page.locator('[data-test="datafactory-form-content"]').fill(sql_content)
        page.locator('[data-test="datafactory-form-submit"]').click()
        expect(page.locator(".el-message--success")).to_contain_text("创建成功", timeout=15000)

        expect(page.locator('[data-test="datafactory-name-link"]').first).to_contain_text(new_name, timeout=15000)
        # T396b posture: ride the header project context via deep link
        # (in-page selector retired); reload refreshes the visible-domain snapshot.
        page.goto(f"{BASE_URL}/#/data-factories?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="datafactory-list-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="datafactory-name-link"]').first).to_contain_text(new_name, timeout=15000)

        row_cmd(page, page.locator('[data-test="datafactory-table"] tbody tr').first, "run")
        expect(page.locator(".el-message-box")).to_be_visible(timeout=5000)
        page.locator('.el-message-box button:has-text("执行")').click()
        expect(page.locator(".el-message--success").last).to_contain_text("入池 1 条", timeout=15000)

        row_cmd(page, page.locator('[data-test="datafactory-table"] tbody tr').first, "edit")
        expect(page.locator('[data-test="datafactory-dialog"]')).to_be_visible()
        page.locator('[data-test="datafactory-form-status"]').get_by_text("停用").click()
        page.locator('[data-test="datafactory-form-submit"]').click()
        expect(page.locator(".el-message--success").last).to_contain_text("已保存", timeout=15000)
        expect(page.locator('[data-test="datafactory-status-tag"]').first).to_contain_text("停用", timeout=15000)

        row_cmd(page, page.locator('[data-test="datafactory-table"] tbody tr').first, "delete")
        expect(page.locator(".el-message-box")).to_be_visible(timeout=5000)
        page.locator('.el-message-box button:has-text("删除")').click()
        expect(page.locator(".el-message--success").last).to_contain_text("已删除", timeout=15000)
        expect(page.locator('[data-test="datafactory-name-link"]').first).not_to_contain_text(new_name, timeout=15000)

        assert_no_errors(collectors, "L5 新建/编辑/删除")
    finally:
        server.shutdown()
        server.server_close()
        cleanup_project(api_client, pid)


def test_l5_factory_fail_and_permission(api_client, uniq):
    pass
    server = start_order_server()
    pid, env_id, fid = setup_factory_dataset(api_client, uniq, f"http://127.0.0.1:{server.server_port}/orders")
    try:
        bad = json.dumps({"poolType": "订单", "method": "GET", "url": "http://127.0.0.1:1/orders",
                          "headers": {}, "body": {}, "extractExpr": "$.data[*].orderNo"})
        r = api_client.post("/data-factories", json={
            "projectId": pid, "envId": env_id, "type": 1, "name": f"坏脚本{uniq[:6]}", "content": bad})
        bad_id = r.json()["data"]["id"]
        pool_before = sql(f"SELECT count(*) FROM tp_data_pool_item WHERE env_id = {env_id}")
        r = api_client.post(f"/data-factories/{bad_id}/run")
        run = r.json()["data"]
        assert run["status"] == 3 and "执行失败" in run.get("message", ""), f"失败 run 断言: {run}"
        pool_after = sql(f"SELECT count(*) FROM tp_data_pool_item WHERE env_id = {env_id}")
        assert pool_before == pool_after, "IT-L5-01：失败不得写池"

        r = api_client.put(f"/data-factories/{fid}", json={"name": f"订单造数{uniq[:6]}",
                                                           "content": "{}", "status": 2})
        assert r.json().get("code") == "0", f"停用失败: {r.text[:200]}"
        r = api_client.post(f"/data-factories/{fid}/run")
        assert r.status_code in (400, 409) and "已停用" in r.json().get("msg", ""), \
            f"停用后执行应 409: {r.text[:200]}"
        api_client.put(f"/data-factories/{fid}", json={"name": f"订单造数{uniq[:6]}",
                                                       "content": "{}", "status": 1})

        tester_user = f"l5ttester_{uniq}"
        with httpx.Client(base_url=API_BASE, timeout=15) as c:
            r = c.post("/auth/login", json={"username": tester_user, "password": "Test@12345"})
            assert r.json().get("code") == "0", f"tester 登录失败: {r.text[:200]}"
            mt = r.json()["data"]["accessToken"]
            mh = {"Authorization": f"Bearer {mt}"}
            r = c.post("/data-factories", headers=mh, json={
                "projectId": pid, "envId": env_id, "type": 1, "name": f"越权{uniq[:6]}", "content": bad})
            assert r.status_code == 403 and r.json().get("code") == "40301", \
                f"普通成员创建应 403: {r.text[:200]}"
            r = c.post(f"/data-factories/{fid}/run", headers=mh)
            assert r.json().get("code") == "0", f"普通成员执行应成功: {r.text[:200]}"
    finally:
        server.shutdown()
        server.server_close()
        cleanup_project(api_client, pid)
