
import subprocess
import uuid

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors, open_journey_leaf
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = BASE_URL.replace(":5173", ":8081")


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject_token(page):
    pass
    token = api_login("admin", ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    return page, token


def test_cluster_upgrade_plan_flow(page, collectors):
    pass
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    uid = uuid.uuid4().hex[:8]
    node_id = f"e2e-t321-{uid}"

    plan_ids = []
    try:
        sql(f"""INSERT INTO tp_cluster_component
                (node_id, component_family, current_version, target_version, requirement, support_state)
                VALUES ('{node_id}', 'executor-agent', '1.0.0', '2.0.0', '>=1.0.0', 'YELLOW')""")
        assert sql(f"SELECT count(*) FROM tp_cluster_component WHERE node_id='{node_id}'") == "1"

        open_journey_leaf(page, "menu-upgrade-center", "system")
        expect(page.locator('[data-test="upgrade-center-page"]')).to_be_visible(timeout=10000)
        tab = page.locator('[data-test="upgrade-tabs"] #tab-plans')
        expect(tab).to_be_visible(timeout=8000)
        tab.click()
        steps = page.locator('[data-test="upgrade-plan-steps"]')
        expect(steps).to_be_visible(timeout=8000)
        expect(steps).to_contain_text("选范围")
        expect(steps).to_contain_text("预览确认")
        expect(steps).to_contain_text("进度")

        form = page.locator('[data-test="upgrade-plan-form"]')
        expect(form).to_be_visible(timeout=8000)
        page.locator('[data-test="upgrade-plan-scope"] label:has-text("节点 agent 升级")').click()

        page.locator('[data-test="upgrade-plan-to-preview"]').click()
        preview = page.locator('[data-test="upgrade-plan-preview"]')
        expect(preview).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="upgrade-impact-caliber"]')).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="upgrade-impact-nodes"]')).to_contain_text("1", timeout=8000)
        expect(page.locator('[data-test="upgrade-impact-steps"]')).to_contain_text("1")

        page.locator('[data-test="upgrade-plan-create"]').click()
        expect(page.locator('[data-test="upgrade-plan-created"]')).to_be_visible(timeout=10000)
        created = page.locator('[data-test="upgrade-created-steps"]')
        expect(created).to_contain_text(node_id, timeout=8000)
        plan_a = int(sql("SELECT max(id) FROM tp_upgrade_plan"))
        plan_ids.append(plan_a)
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_a}") == "PREVIEW"
        assert sql(f"SELECT count(*) FROM tp_upgrade_plan_step WHERE plan_id={plan_a}") == "1"

        page.locator('[data-test="upgrade-plan-start"]').click()
        expect(page.locator('.el-message--success').first).to_contain_text("已启动", timeout=10000)
        progress = page.locator('[data-test="upgrade-plan-progress"]')
        expect(progress).to_be_visible(timeout=8000)
        row_a = page.locator(f'[data-test="upgrade-plan-status-{plan_a}"]')
        expect(row_a).to_be_visible(timeout=10000)
        expect(row_a).to_contain_text("进行中")
        expect(page.locator(f'[data-test="upgrade-plan-abort-{plan_a}"]')).to_be_visible(timeout=8000)

        page.locator(f'[data-test="upgrade-plan-abort-{plan_a}"]').click()
        box = page.locator(".el-message-box")
        expect(box).to_contain_text("不可恢复", timeout=8000)
        box.locator('button:has-text("确认执行")').click()
        expect(page.locator('.el-message--success').first).to_contain_text("已终止", timeout=10000)
        expect(row_a).to_contain_text("已终止", timeout=10000)
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_a}") == "ABORTED"

        rb = api.post("/api/v1/cluster/upgrade-plans", json={
            "scope": "node", "engineCodes": [], "canaryNodeIds": [], "batchSize": 1})
        assert rb.status_code == 200, f"建计划 HTTP {rb.status_code}"
        assert rb.json()["code"] == "0", f"建计划业务码 {rb.json()}"
        plan_b = rb.json()["data"]["id"]
        plan_ids.append(plan_b)
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_b}") == "PREVIEW"

        page.locator('[data-test="upgrade-plans-refresh"]').click()
        row_b = page.locator(f'[data-test="upgrade-plan-status-{plan_b}"]')
        expect(row_b).to_be_visible(timeout=10000)
        expect(row_b).to_contain_text("预览")
        expect(page.locator(f'[data-test="upgrade-plan-abort-{plan_b}"]')).to_have_count(0)

        rj = api.post(f"/api/v1/cluster/upgrade-plans/{plan_b}/abort")
        body = rj.json()
        assert rj.status_code == 400 and body["code"] != "0", f"PREVIEW 终止应业务拒，实际 {rj.status_code} {body}"
        assert "当前状态" in (body.get("msg") or ""), f"人话拒缺失：{body}"
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_b}") == "PREVIEW"

        rs = api.post(f"/api/v1/cluster/upgrade-plans/{plan_b}/start")
        assert rs.json()["code"] == "0", f"start 失败 {rs.json()}"
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_b}") == "RUNNING"
        assert sql(f"SELECT count(*) FROM tp_upgrade_plan_step WHERE plan_id={plan_b}"
                   " AND phase='canary'") == "1"
        ra = api.post(f"/api/v1/cluster/upgrade-plans/{plan_b}/abort")
        assert ra.json()["code"] == "0", f"abort 失败 {ra.json()}"
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_b}") == "ABORTED"

        assert_no_errors(collectors, "upgrade plan 三步流 E2E")
    finally:
        ids = ",".join(str(i) for i in plan_ids)
        if ids:
            sql(f"""DELETE FROM tp_cluster_command WHERE id IN
                    (SELECT command_id FROM tp_upgrade_plan_step WHERE plan_id IN ({ids})
                     AND command_id IS NOT NULL)""")
            sql(f"DELETE FROM tp_upgrade_plan_step WHERE plan_id IN ({ids})")
            sql(f"DELETE FROM tp_upgrade_plan WHERE id IN ({ids})")
        sql(f"DELETE FROM tp_cluster_component WHERE node_id='{node_id}'")
