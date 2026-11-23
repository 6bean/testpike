
import subprocess
import time
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors, open_journey_leaf

pytestmark = pytest.mark.timeout(300)


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject_token(page):
    pass
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page, token
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def test_h4_pool_view_page(page, collectors):
    pass
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    suffix = uuid.uuid4().hex[:6]
    pid = pool_id = bid = None
    api = httpx.Client(base_url=f"{BASE_URL.replace(':5173', ':8081')}", timeout=30, headers=h)
    try:
        open_journey_leaf(page, "menu-executor-pools", "system")
        expect(page.locator('[data-test="pool-page"]')).to_be_visible(timeout=10000)

        pid = api.post("/api/v1/projects",
                       json={"name": f"池E2E项目{suffix}", "code": f"H4E{suffix}"}).json()["data"]
        api.put(f"/api/v1/projects/{pid}",
                json={"name": f"池E2E项目{suffix}", "poolQuotaPct": 33}).json()
        tag = api.post("/api/v1/tags",
                       json={"projectId": pid, "name": f"池E2E{suffix}", "type": 7}).json()["data"]
        pool_id = tag["id"]

        page.locator('[data-test="pool-refresh"]').first.click()
        card = page.locator(f'[data-test="pool-card-{pool_id}"]')
        expect(card).to_be_visible(timeout=10000)
        expect(card).to_contain_text(f"池E2E{suffix}")

        proj_table = page.locator(f'[data-test="pool-projects-{pool_id}"]')
        expect(proj_table).to_be_visible(timeout=5000)
        expect(proj_table).to_contain_text("33%")
        expect(proj_table).to_contain_text("0")
        expect(proj_table).not_to_contain_text("-1")

        env = api.post(f"/api/v1/projects/{pid}/envs",
                       json={"name": f"池E2E环境{suffix}", "envType": 2,
                             "baseUrl": "http://localhost:8899", "endTypes": "web",
                             "versionSnapshot": f"build-{suffix}"}).json()["data"]
        cid = api.post("/api/v1/cases", headers=h, json={
            "projectId": pid, "title": f"池E2E任务{suffix}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3, "phase": 2,
            "steps": [{"stepNo": 1, "description": "占位", "isAssert": 1}]}).json()["data"]["id"]
        sql("INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
            f"VALUES ({cid}, 'v2', '{{\"steps\":[]}}', 1, 1) "
            "ON CONFLICT (case_id) DO UPDATE SET action_json = EXCLUDED.action_json")
        batch = api.post("/api/v1/batches", json={
            "projectId": pid, "name": f"池E2E批{suffix}", "envId": env,
            "caseIds": [cid], "poolTagId": pool_id}).json()["data"]
        bid = batch["id"] if isinstance(batch, dict) else batch
        api.post(f"/api/v1/batches/{bid}/start")

        page.locator('[data-test="pool-refresh"]').first.click()
        expect(card).to_contain_text("1", timeout=5000)
        expect(proj_table).to_contain_text("0 / 1", timeout=5000)

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.goto(f"{BASE_URL}/#/execution/pools", wait_until="domcontentloaded")
        expect(page.locator(f'[data-test="pool-card-{pool_id}"]')).to_be_visible(timeout=10000)
        expect(page.locator(f'[data-test="pool-projects-{pool_id}"]')).to_contain_text("0 / 1")

        b = api.get(f"/api/v1/batches/{bid}").json()["data"]
        api.post(f"/api/v1/batches/{bid}/cancel", params={"version": b["version"]})
        api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
        pool_id_gone = pool_id
        pool_id = None
        page.locator('[data-test="pool-refresh"]').first.click()
        expect(page.locator(f'[data-test="pool-card-{pool_id_gone}"]')).to_have_count(0, timeout=8000)
        time.sleep(1)
        left = sql(f"SELECT count(*) FROM tp_exec_task WHERE project_id={pid}")
        assert left == "0", f"任务残留 {left}"

        assert_no_errors(collectors, "H4 资源池视图页")
    finally:
        if pid:
            try:
                api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
            except Exception:
                pass
        if pool_id:
            try:
                sql(f"DELETE FROM tp_tag_dict WHERE id={pool_id}")
            except Exception:
                pass
        api.close()
