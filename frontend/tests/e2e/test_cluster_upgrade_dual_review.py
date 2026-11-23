
import json
import subprocess
import uuid

import httpx
import pytest
from conftest import (ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors,
                      inject_user_token, open_journey_leaf)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = BASE_URL.replace(":5173", ":8081")
WAVE = ["executor-agent", "frontend", "api", "admin", "ai-service", "mock-server", "core"]
KEY = "release.production.dual-review"
SHA = "1" * 64


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def account_expected_refusal(collectors, url_part, status):
    """One-shot accounting for an EXPECTED business refusal already asserted in the UI
    (toast text + response body + DB unchanged). Mirrors the _whitelist_409 precedent in
    test_f1_env_v13.py: the browser inherently logs \"Failed to load resource: <status>\"
    for any 4xx fetch response — that noise, plus this exact url+status record, are the
    only entries removed. Not a defect whitelist: the refusal is asserted end-to-end
    before this runs; any other 4xx/console entry still fails the test."""
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not (url_part in e["url"] and e["status"] == status)]
    collectors["console_errors"] = [
        e for e in collectors["console_errors"]
        if f"status of {status}" not in e]


def open_upgrade_plans(page):
    """Navigate to upgrade center -> plans tab (journey-menu precedent shape)."""
    open_journey_leaf(page, "menu-upgrade-center", "system")
    expect(page.locator('[data-test="upgrade-center-page"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="upgrade-tabs"] #tab-plans').click()
    expect(page.locator('[data-test="upgrade-plan-steps"]')).to_be_visible(timeout=8000)


def set_key(api, h, value):
    """Flip the dual-review K8 key through the live /configs surface (hot, no restart)."""
    lst = api.get("/api/v1/configs", headers=h).json()
    row = next((x for x in (lst.get("data") or []) if x.get("configKey") == KEY), None)
    payload = {"configKey": KEY, "configValue": value,
               "groupName": (row or {}).get("groupName", "机群与升级"),
               "valueType": (row or {}).get("valueType", "bool"),
               "encryptFlag": (row or {}).get("encryptFlag", 0),
               "description": (row or {}).get("description",
                               "生产双人复核开关（决策 E，默认 0=宽松）")}
    if row is None:
        return api.post("/api/v1/configs", headers=h, json=payload).json()
    return api.put(f"/api/v1/configs/{row['id']}", headers=h, json=payload).json()


def test_cluster_upgrade_dual_review(page, collectors):
    """Production-declared plan: UI wizard -> strict block -> self-review refusal
    -> second-person review -> reviewer column filled -> gate opens -> abort."""
    token = api_login("admin", ADMIN_PASSWORD)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    uid = uuid.uuid4().hex[:8]
    node_id = f"e2e-t355f-{uid}"
    manifest_version = f"E2E.355f.{uid}"
    reviewer = f"t355frev_{uid}"
    reviewer_pw = f"Rev@tp{uid}9"
    window = "2026-10-14 02:00–02:30"
    plan_id = None
    reviewer_created = False
    key_strict = False
    try:
        # -- 0. fixtures: temp reviewer (role 4) + ledger + manifest + strict key ON --
        created = api.post("/api/v1/users", json={
            "username": reviewer, "password": reviewer_pw,
            "displayName": "T355f review fixture", "email": None, "phone": None,
            "role": 4}).json()
        assert created.get("code") in (0, "0"), f"reviewer account failed: {created}"
        reviewer_created = True
        for fam in WAVE:
            sql(f"""INSERT INTO tp_cluster_component
                    (node_id, component_family, current_version, target_version, requirement, source)
                    VALUES ('{node_id}', '{fam}', '1.0.0', '9.355f.1', '>=1.0', 'scan')
                    ON CONFLICT (node_id, component_family) DO UPDATE SET
                      current_version='1.0.0', target_version='9.355f.1', support_state='YELLOW'""")
        matrix = [{"component": f, "support": ">=1.0", "pinned": "9.355f.1"} for f in WAVE]
        artifacts = [{"component_family": f,
                      "url": f"https://github.com/testpike/{f}-9.355f.jar",
                      "sha256": SHA, "size": 1} for f in WAVE]
        reg = api.post("/api/v1/cluster/release-manifests", json={
            "version": manifest_version,
            "componentMatrixJson": json.dumps(matrix),
            "breakingChangesJson": "[]",
            "source": "manual",
            "artifactsJson": json.dumps(artifacts)}).json()
        assert reg.get("code") in (0, "0"), f"manifest register failed: {reg}"
        flip = set_key(api, h, "1")
        assert flip.get("code") in (0, "0"), f"key flip failed: {flip}"
        key_strict = True

        # -- 1. admin UI: wizard with production declaration -> create PREVIEW --
        inject_user_token(page, "admin", ADMIN_PASSWORD)
        open_upgrade_plans(page)
        scope = page.locator('[data-test="upgrade-plan-scope"]')
        scope.locator('label:has-text("平台一键升级")').click()
        page.locator('[data-test="upgrade-plan-manifest"]').click()
        page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{manifest_version}")').first.click()
        page.locator('[data-test="upgrade-plan-production"]').click()
        # window field appearing = the production declaration flipped (v-if on the model;
        # the native checkbox input is visually hidden inside el-checkbox, so assert the
        # dependent control instead of to_be_checked)
        expect(page.locator('input[data-test="upgrade-plan-window"]')).to_be_visible(timeout=5000)
        page.fill('input[data-test="upgrade-plan-window"]', window)
        page.locator('[data-test="upgrade-plan-to-preview"]').click()
        expect(page.locator('[data-test="upgrade-plan-preview"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="upgrade-plan-create"]').click()
        expect(page.locator('[data-test="upgrade-plan-created"]')).to_be_visible(timeout=10000)
        plan_id = int(sql("SELECT max(id) FROM tp_upgrade_plan"))
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_id}") == "PREVIEW"
        assert sql(f"SELECT count(*) FROM tp_upgrade_plan WHERE id={plan_id} AND production_scope") == "1"
        assert sql(f"SELECT reviewed_by FROM tp_upgrade_plan WHERE id={plan_id}") == ""

        # -- 2. strict gate through the UI: start refused naming the missing review --
        page.locator('[data-test="upgrade-plan-start"]').click()
        box = page.locator(".el-message-box")
        expect(box).to_contain_text("core 最后", timeout=8000)
        with page.expect_response(
                lambda r: r.request.method == "POST"
                and f"/upgrade-plans/{plan_id}/start" in r.url and r.status == 400):
            box.locator('button:has-text("确认执行")').click()
        err = page.locator(".el-message--error").last
        expect(err).to_contain_text("复核", timeout=10000)
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_id}") == "PREVIEW"
        # refusal asserted end-to-end (toast text + HTTP 400 + row still PREVIEW) —
        # account for it exactly (url+status), same shape as the 409 precedent
        account_expected_refusal(collectors, f"/upgrade-plans/{plan_id}/start", 400)

        # -- 3. self-review refused: initiator clicks the row's review button --
        page.locator('[data-test="upgrade-plan-back"]').click()  # createdPlan -> reset to step 0
        page.locator('[data-test="upgrade-plan-open-list"]').click()
        progress = page.locator('[data-test="upgrade-plan-progress"]')
        expect(progress).to_be_visible(timeout=8000)
        review_btn = page.locator(f'[data-test="upgrade-plan-review-{plan_id}"]')
        expect(review_btn).to_be_visible(timeout=8000)
        with page.expect_response(
                lambda r: r.request.method == "POST"
                and f"/upgrade-plans/{plan_id}/review" in r.url and r.status == 400):
            review_btn.click()
        err2 = page.locator(".el-message--error").last
        expect(err2).to_contain_text("不能是发起人", timeout=10000)
        assert sql(f"SELECT reviewed_by FROM tp_upgrade_plan WHERE id={plan_id}") == ""
        account_expected_refusal(collectors, f"/upgrade-plans/{plan_id}/review", 400)

        # -- 4. second person reviews through the UI --
        inject_user_token(page, reviewer, reviewer_pw)
        open_upgrade_plans(page)
        page.locator('[data-test="upgrade-plan-open-list"]').click()
        btn2 = page.locator(f'[data-test="upgrade-plan-review-{plan_id}"]')
        expect(btn2).to_be_visible(timeout=10000)
        with page.expect_response(
                lambda r: r.request.method == "POST"
                and f"/upgrade-plans/{plan_id}/review" in r.url and r.status == 200):
            btn2.click()
        ok = page.locator(".el-message--success").last
        expect(ok).to_contain_text("双人复核已确认", timeout=10000)
        assert sql(f"SELECT reviewed_by FROM tp_upgrade_plan WHERE id={plan_id}") == reviewer
        # reviewed row: reviewer column filled + button gone (state-driven v-if)
        page.locator('[data-test="upgrade-plans-refresh"]').click()
        row = page.locator(f'[data-test="upgrade-plan-status-{plan_id}"]').locator("xpath=ancestor::tr[1]")
        expect(row).to_contain_text(reviewer, timeout=8000)
        expect(page.locator(f'[data-test="upgrade-plan-review-{plan_id}"]')).to_have_count(0)

        # -- 5. gate opens after window + review: API start -> RUNNING -> UI abort --
        st = api.post(f"/api/v1/cluster/upgrade-plans/{plan_id}/start").json()
        assert st.get("code") in (0, "0"), f"start after review failed: {st}"
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_id}") == "RUNNING"
        inject_user_token(page, "admin", ADMIN_PASSWORD)
        open_upgrade_plans(page)
        page.locator('[data-test="upgrade-plan-open-list"]').click()
        row_a = page.locator(f'[data-test="upgrade-plan-status-{plan_id}"]')
        expect(row_a).to_contain_text("进行中", timeout=10000)
        page.locator(f'[data-test="upgrade-plan-abort-{plan_id}"]').click()
        box2 = page.locator(".el-message-box")
        expect(box2).to_contain_text("不可恢复", timeout=8000)
        box2.locator('button:has-text("确认执行")').click()
        expect(page.locator(".el-message--success").last).to_contain_text("已终止", timeout=10000)
        assert sql(f"SELECT status FROM tp_upgrade_plan WHERE id={plan_id}") == "ABORTED"

        assert_no_errors(collectors, "upgrade dual-review consumer E2E")
    finally:
        if plan_id is not None:
            sql(f"""DELETE FROM tp_cluster_command WHERE id IN
                    (SELECT command_id FROM tp_upgrade_plan_step WHERE plan_id = {plan_id}
                     AND command_id IS NOT NULL)""")
            sql(f"DELETE FROM tp_upgrade_plan_step WHERE plan_id = {plan_id}")
            sql(f"DELETE FROM tp_upgrade_plan WHERE id = {plan_id}")
        sql(f"DELETE FROM tp_cluster_component WHERE node_id='{node_id}'")
        sql(f"DELETE FROM tp_release_manifest WHERE version='{manifest_version}'")
        if reviewer_created:
            sql(f"DELETE FROM tp_user_role WHERE user_id IN (SELECT id FROM tp_user WHERE username = '{reviewer}')")
            sql(f"DELETE FROM tp_user WHERE username = '{reviewer}'")
        if key_strict:
            sql(f"UPDATE tp_sys_config SET config_value='0' WHERE config_key='{KEY}'")
        left = [
            sql(f"SELECT count(*) FROM tp_upgrade_plan WHERE id={plan_id}" if plan_id is not None else "SELECT 0"),
            sql(f"SELECT count(*) FROM tp_cluster_component WHERE node_id='{node_id}'"),
            sql(f"SELECT count(*) FROM tp_release_manifest WHERE version='{manifest_version}'"),
            sql(f"SELECT count(*) FROM tp_user WHERE username='{reviewer}'"),
            sql(f"SELECT config_value FROM tp_sys_config WHERE config_key='{KEY}'"),
        ]
        assert left == ["0", "0", "0", "0", "0"], f"teardown residue: {left}"
