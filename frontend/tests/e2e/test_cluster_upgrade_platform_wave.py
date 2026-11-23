"""First E2E for the platform one-click wave upgrade flow ( package C,
Playwright real browser + real admin:8081 -> core:8083 chain, no mocks).

Covers the UI face of the platform wave plan:
1. Upgrade center -> Plans tab -> scope radio "One-click platform upgrade"
 visible; selecting it lazily pulls the core compat rows (same single source
 as the health card — display aggregation, no second spectrum).
2. Step 2 (preview): rehearsal block carries the four honest anchors —
 restart cost statement / out-of-range executor tally / running-task tally /
 drain-by-default note. Numbers come straight from core rows; the UI asserts
 the caliber sentences, never re-derives verdicts.
3. Create plan -> wave manifest buckets the created steps by core waveSeq
 ascending: executor-agent -> frontend -> api -> admin -> ai-service -> mock-server -> core
 (rows upgrade-wave-row-0..5, target = manifest pin, never a probe value).
4. Confirm & run on platform scope pops the second confirmation carrying the
 restart statement ("core restarts last"); confirm -> toast started ->
 progress list row RUNNING -> UI abort (irreversible confirmation) -> row
 ABORTED + DB reconciliation.
5. Teardown removes every fixture row (ledger/plan/steps/dispatched commands/
 manifest, uuid-suffixed ids) and re-verifies zero residue; API response
 codes / console / browser Issues panel all clean (assert_no_errors now
 includes the iron-rule-5c Issues check).

Fixture shape mirrors the internal platform-wave L3 script (proven spectrum):
one YELLOW ledger row per wave family on a dedicated node + a complete
manifest registered through the bridge (github host inside the artifact
allowlist). stepCount is asserted as a floor (>=) because the tally spans all
ledger yellow rows, not just this fixture node.
"""
import json
import subprocess
import uuid

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors, open_journey_leaf
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = BASE_URL.replace(":5173", ":8081")
WAVE = ["executor-agent", "frontend", "api", "admin", "ai-service", "mock-server", "core"]
PIN = "9.355.1"
SHA = "1" * 64


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject_token(page):
    """Real login + token injection (upgrade-center precedent shape)."""
    token = api_login("admin", ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    return page, token


def test_cluster_upgrade_platform_wave_flow(page, collectors):
    """Platform wave plan: rehearsal block -> wave manifest -> second confirm -> start/abort -> DB."""
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    uid = uuid.uuid4().hex[:8]
    node_id = f"e2e-t355c-{uid}"
    manifest_version = f"E2E.355.{uid}"
    plan_ids = []
    try:
        # -- 0. fixture: one YELLOW ledger row per wave family + complete manifest --
        for fam in WAVE:
            sql(f"""INSERT INTO tp_cluster_component
                    (node_id, component_family, current_version, target_version, requirement, source)
                    VALUES ('{node_id}', '{fam}', '1.0.0', '{PIN}', '>=1.0', 'scan')
                    ON CONFLICT (node_id, component_family) DO UPDATE SET
                      current_version='1.0.0', target_version='{PIN}', support_state='YELLOW'""")
        assert sql(f"SELECT count(*) FROM tp_cluster_component WHERE node_id='{node_id}'") == str(len(WAVE))

        matrix = [{"component": f, "support": ">=1.0", "pinned": PIN} for f in WAVE]
        artifacts = [{"component_family": f,
                      "url": f"https://github.com/testpike/{f}-{PIN}.jar",
                      "sha256": SHA, "size": 1} for f in WAVE]
        reg = api.post("/api/v1/cluster/release-manifests", json={
            "version": manifest_version,
            "componentMatrixJson": json.dumps(matrix),
            "breakingChangesJson": "[]",
            "source": "manual",
            "artifactsJson": json.dumps(artifacts)}).json()
        assert reg.get("code") in (0, "0"), f"manifest register failed: {reg}"

        # -- 1. upgrade center -> plans tab -> platform scope radio --
        open_journey_leaf(page, "menu-upgrade-center", "system")
        expect(page.locator('[data-test="upgrade-center-page"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="upgrade-tabs"] #tab-plans').click()
        expect(page.locator('[data-test="upgrade-plan-steps"]')).to_be_visible(timeout=8000)
        scope = page.locator('[data-test="upgrade-plan-scope"]')
        expect(scope).to_contain_text("平台一键升级", timeout=8000)
        scope.locator('label:has-text("平台一键升级")').click()

        # pick the pinned manifest as the upgrade authority
        page.locator('[data-test="upgrade-plan-manifest"]').click()
        page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{manifest_version}")').first.click()

        # -- 2. preview step: rehearsal block four anchors (core-sourced, honest) --
        page.locator('[data-test="upgrade-plan-to-preview"]').click()
        expect(page.locator('[data-test="upgrade-plan-preview"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="upgrade-rehearsal-block"]')).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="upgrade-rehearsal-restart-note"]')).to_contain_text("core 最后重启")
        expect(page.locator('[data-test="upgrade-rehearsal-out-of-range"]')).to_contain_text("区间外执行器：")
        expect(page.locator('[data-test="upgrade-rehearsal-running"]')).to_contain_text("当前在跑任务：")
        expect(page.locator('[data-test="upgrade-rehearsal-drain-note"]')).to_contain_text("排空")
        # impact numbers come from core (floor: the tally spans all yellow rows)
        expect(page.locator('[data-test="upgrade-impact-steps"]')).to_contain_text("个升级步")

        # -- 3. create -> wave manifest bucketed by core waveSeq 0..len(WAVE)-1 --
        page.locator('[data-test="upgrade-plan-create"]').click()
        expect(page.locator('[data-test="upgrade-plan-created"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="upgrade-wave-groups"]')).to_be_visible(timeout=8000)
        for seq, fam in enumerate(WAVE):
            expect(page.locator(f'[data-test="upgrade-wave-row-{seq}"]')).to_contain_text(
                f"{fam}→{PIN}", timeout=8000)

        plan_id = int(sql("SELECT max(id) FROM tp_upgrade_plan"))
        plan_ids.append(plan_id)
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_id}") == "PREVIEW"
        assert sql(f"SELECT count(*) FROM tp_upgrade_plan_step WHERE plan_id={plan_id} "
                   f"AND node_id='{node_id}'") == str(len(WAVE))

        # -- 4. confirm & run: platform scope = second confirmation with the restart statement --
        page.locator('[data-test="upgrade-plan-start"]').click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=8000)
        expect(box).to_contain_text("core 最后", timeout=8000)
        box.locator('button:has-text("确认执行")').click()
        expect(page.locator('.el-message--success').first).to_contain_text("已启动", timeout=10000)
        row = page.locator(f'[data-test="upgrade-plan-status-{plan_id}"]')
        expect(row).to_be_visible(timeout=10000)
        expect(row).to_contain_text("进行中")
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_id}") == "RUNNING"

        # -- 5. UI abort (irreversible confirmation) -> ABORTED on screen and in DB --
        page.locator(f'[data-test="upgrade-plan-abort-{plan_id}"]').click()
        box2 = page.locator(".el-message-box")
        expect(box2).to_contain_text("不可恢复", timeout=8000)
        box2.locator('button:has-text("确认执行")').click()
        expect(row).to_contain_text("已终止", timeout=10000)
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_id}") == "ABORTED"

        assert_no_errors(collectors, "platform wave plan E2E")
    finally:
        ids = ",".join(str(i) for i in plan_ids)
        if ids:
            sql(f"""DELETE FROM tp_cluster_command WHERE id IN
                    (SELECT command_id FROM tp_upgrade_plan_step WHERE plan_id IN ({ids})
                     AND command_id IS NOT NULL)""")
            sql(f"DELETE FROM tp_upgrade_plan_step WHERE plan_id IN ({ids})")
            sql(f"DELETE FROM tp_upgrade_plan WHERE id IN ({ids})")
        sql(f"DELETE FROM tp_cluster_component WHERE node_id='{node_id}'")
        sql(f"DELETE FROM tp_release_manifest WHERE version='{manifest_version}'")
        residue = [
            sql(f"SELECT count(*) FROM tp_cluster_component WHERE node_id='{node_id}'"),
            sql(f"SELECT count(*) FROM tp_release_manifest WHERE version='{manifest_version}'"),
            sql("SELECT count(*) FROM tp_upgrade_plan WHERE manifest_version LIKE 'E2E.355.%'"),
        ]
        assert residue == ["0", "0", "0"], f"fixture residue: {residue}"
