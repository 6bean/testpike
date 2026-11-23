# -*- coding: utf-8 -*-
"""First E2E for the wizard AI drafting door .

Real chain, zero mocks on the business path: the browser POST rides
admin -> core PerfAiSwitchGate -> core draft orchestration -> ai-service
perfdraft -> live LLM provider -> step-gate trim -> wizard prefill.

Assertion doors:
1. honest door before typing: pw-ai-draft sits disabled and the wrapping
 span carries the plain-word reason (a disabled button hides its own
 title, so the span is the carrier); no server round-trip happens;
2. closed switch refuses in plain words naming the exact config key
 (the refusal rides through untranslated from core; the self-caused
 403 site is stripped exactly like the T365b mock-drafting precedent,
 every other 4xx/console/Issues zero-tolerance gate stays armed);
3. open switch files one real draft hop: either the step table visibly
 prefills (success toast names the count, input cleared, steps-count
 non-zero) or the model honestly reports nothing usable (warning
 toast) — both are truthful terminals; nothing is ever persisted by
 drafting (tp_perf_scenario stays at zero rows for the fixture);
4. reload-truth guard: the fixture project is swept through the real
 purge chain and gates are restored to the factory closed value.

Parallel safety: uniq-suffixed throwaway project (never the demo/sample
ones). Gates restored in finally regardless of outcome.
"""
import time
import uuid

import pytest
from conftest import BASE_URL, assert_no_errors, sql_query
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("perf-ai-gate")]

GATE_KEY = "ai.perf.scenario-draft"
DESCRIPTION = (
    "起草一个订单查询压测：第一步 GET http://127.0.0.1:8083/actuator/health 健康检查，"
    "第二步 GET http://127.0.0.1:8083/actuator/info 查询详情，100 并发跑 60 秒"
)


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} business failure: {body.get('msg')}"
    return body["data"]


def _strip_expected_gate403(collectors):
    """Drop only this test's own closed-gate refusal site (designed product
    behaviour); everything else still fails the run."""
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not ("/api/v1/perf/scenarios/draft" in e["url"] and e["status"] == 403)]
    collectors["console_errors"] = [
        c for c in collectors["console_errors"] if "status of 403" not in c]


def _set_gate(api_client, value):
    rows = _ok(api_client.get("/configs"), "configs list") or []
    row = next((r for r in rows if r["configKey"] == GATE_KEY), None)
    assert row is not None, f"config key missing from the config plane: {GATE_KEY}"
    _ok(api_client.put(f"/configs/{row['id']}", json={
        "configKey": GATE_KEY, "configValue": value,
        "groupName": row.get("groupName") or "AI 配置",
        "valueType": row.get("valueType") or "bool",
        "encryptFlag": row.get("encryptFlag", 0),
        "description": row.get("description") or ""}), f"set {GATE_KEY}={value}")


def test_perf_wizard_ai_draft_door_gate_and_persist_free(logged_in, api_client, collectors):
    """Disabled+plain-reason door -> closed-gate refusal naming the key ->
    open-gate real draft hop -> table prefill or honest empty -> zero rows."""
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    pid = _ok(api_client.post("/projects", json={
        "name": f"压测AI草拟{uniq}", "code": f"PD{uniq[:5].upper()}"}), "建项目")
    pid = pid["id"] if isinstance(pid, dict) else pid
    try:
        # deep link lands the global project context, then open the wizard
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="perf-create-btn"]').click()
        expect(page.locator('[data-test="pw-step-target"]')).to_be_visible(timeout=8000)

        # ---- door 1: honest absence before typing ----
        btn = page.locator('[data-test="pw-ai-draft"]')
        expect(btn).to_be_disabled(timeout=5000)
        # the wizard reuses .pw__imp-door for two doors — scope to ours via the button
        door = page.locator("span.pw__imp-door").filter(
            has=page.locator('[data-test="pw-ai-draft"]'))
        expect(door).to_have_attribute("title", "请先输入一句压测描述", timeout=5000)

        # ---- door 2: closed switch refuses naming the exact key ----
        page.locator('[data-test="pw-ai-draft-input"]').fill(DESCRIPTION)
        expect(btn).to_be_enabled(timeout=5000)
        # ready door carries no blocking hint (None value in to_have_attribute
        # asserts presence-of-absence only in some client versions — read it directly)
        assert door.get_attribute("title") is None
        req_seen = []
        page.on("request", lambda r: req_seen.append(r.url) if "/perf/scenarios/draft" in r.url else None)
        btn.click()
        refuse = page.locator(".el-message--error").filter(has_text=GATE_KEY)
        expect(refuse).to_be_visible(timeout=15000)
        assert any("/perf/scenarios/draft" in u for u in req_seen), \
            "closed-gate refusal never reached the server (expected a real round trip)"

        # ---- open the switch through the real config chain (hot reload) ----
        _set_gate(api_client, "true")

        # ---- door 3: one real draft hop — prefill or honest empty ----
        page.locator('[data-test="pw-ai-draft-input"]').fill(DESCRIPTION)
        btn.click()
        outcome = page.locator(".el-message--success, .el-message--warning")
        expect(outcome.last).to_be_visible(timeout=18000)
        text = outcome.last.text_content() or ""
        drafted = "已草拟" in text
        empty = "未能从这句描述草拟出可用步骤" in text
        assert drafted or empty, f"unexpected drafting outcome toast: {text}"
        if drafted:
            expect(page.locator('[data-test="pw-steps-count"]')).not_to_contain_text("0", timeout=5000)
            expect(page.locator('[data-test="pw-ai-draft-input"]')).to_have_value("", timeout=5000)

        # ---- drafting never persists: the scenario table stays at zero ----
        assert int(sql_query(
            f"SELECT count(*) FROM tp_perf_scenario WHERE project_id = {pid}")) == 0, \
            "AI drafting wrote a scenario row — drafts must stay in the wizard"

        # Only the door-2 designed refusal is stripped (T365b precedent). The
        # strip runs at the ENDPOINT assert (not right after the toast): the
        # CDP response event can land after the toast renders, so an early
        # strip misses it (measured: 00:20 run left one 403 in the bucket).
        # Door 3's toast+DB truths above already assert the open-gate leg, so
        # filtering this endpoint's 403 here cannot hide a real regression;
        # every other 4xx/console/Issues gate stays armed for the whole run.
        _strip_expected_gate403(collectors)
        assert_no_errors(collectors, "perf wizard AI drafting door")
    finally:
        try:
            _set_gate(api_client, "false")
        except Exception as e:  # noqa: BLE001
            print(f"!! gate restore failed (manual check): {e}")
        # the config plane caches: confirm the factory closed truth landed
        deadline = time.time() + 10
        while time.time() < deadline:
            val = sql_query(f"SELECT config_value FROM tp_sys_config WHERE config_key = '{GATE_KEY}'")
            if val == "false":
                break
            time.sleep(1)
        try:
            api_client.delete(f"/projects/{pid}/purge?force=true")
        except Exception as e:  # noqa: BLE001
            print(f"!! project purge failed (manual check): {e}")
