# -*- coding: utf-8 -*-
"""First E2E for the AI run-insight card .

Real chain, zero mocks on the business path: browser POST -> admin(8081) ->
core PerfAiSwitchGate + insight orchestration -> ai-service perf insight ->
live LLM provider. Assertion doors:
1. closed switch: one click lands an in-page tinted refusal band naming the
 exact config key (never a dialog), no second copy of the wording;
2. double-click guard: while a request is in flight the door is loading+disabled
 (R22: exactly one model hop per click);
3. open switch: the band turns into either a real read-out (non-empty text) or
 the ai leg's honest refusal — both truthful terminals, never an invented
 explanation; the read-out is transient (tp_ai_call_log grows by the hops,
 no run/scenario rows are written by the door);
4. sweep: marker-tagged fixture, gate restored to the factory closed value.
Parallel safety: uniq throwaway project (demo/sample projects never touched).
"""
import time
import uuid

import pytest
from conftest import BASE_URL, assert_no_errors, sql_query
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("perf-ai-gate")]

GATE_KEY = "ai.perf.insight"
MARKER = "e2et380b11c"


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} business failure: {body.get('msg')}"
    return body["data"]


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


def _strip_expected_gate403(collectors):
    """Drop only this test's own closed-gate refusal sites (designed behaviour;
 the console line carries no URL, same shape as the -2c/ai-draft
 precedent). Everything else still fails the run. The URL keeps its
 ?projectId query, so the path (query stripped) is what ends in insight."""
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not ("/api/v1/perf/runs/" in e["url"]
               and e["url"].split("?", 1)[0].endswith("/insight")
               and e["status"] == 403)]
    collectors["console_errors"] = [
        c for c in collectors["console_errors"] if "status of 403" not in c]


def test_perf_report_insight_card_gate_and_read_out(logged_in, api_client, collectors):
    """Closed-gate refusal band -> double-click guard -> open-gate one real hop."""
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    pid = _ok(api_client.post("/projects", json={
        "name": f"压测归因解读{uniq}", "code": f"RI{uniq[:5].upper()}"}), "建项目")
    pid = pid["id"] if isinstance(pid, dict) else pid
    run_id = None
    scen_id = None
    try:
        # marker-tagged terminal fixture run with aggregate frames (same shape
        # the L3 chain seeds — the judge needs frames to reach pass/fail)
        env_id = sql_query(
            f"SELECT id FROM tp_project_env WHERE project_id = {pid} ORDER BY id LIMIT 1") \
            or sql_query("SELECT id FROM tp_project_env ORDER BY id LIMIT 1")
        # psql -c prints the RETURNING row AND the command tag ("INSERT 0 1") —
        # the fixture helper reads the first line only (l3-chain precedent).
        def insert_id(stmt: str) -> int:
            return int(sql_query(stmt).splitlines()[0])

        scen_id = insert_id(
            f"INSERT INTO tp_perf_scenario(project_id, env_id, name, pressure_model, "
            f"pressure_config, steps_json) VALUES ({pid}, {env_id}, '{MARKER}', "
            f"'CONSTANT_RPS', '{{\"vus\":1}}', '[]') RETURNING id")
        run_id = insert_id(
            f"INSERT INTO tp_perf_run(scenario_id, project_id, status, pressure_snapshot) "
            f"VALUES ({scen_id}, {pid}, 3, "
            f"'{{\"fixture\":\"{MARKER}\",\"dslHash\":\"{MARKER}h\"}}') RETURNING id")
        sql_query("INSERT INTO tp_perf_metric(run_id, project_id, second_offset, rps, p95_ms, "
                  "error_rate, req_count, fail_count, error_class, step_key) VALUES "
                  f"({run_id}, {pid}, 0, 100, 500, 0.04, 100, 4, "
                  f"'{{\"timeout\":7,\"http5xx\":2}}', ''), "
                  f"({run_id}, {pid}, 1, 100, 600, 0.06, 100, 6, '{{\"timeout\":3}}', '')")

        page.goto(f"{BASE_URL}/#/perf/run/{run_id}?projectId={pid}",
                  wait_until="domcontentloaded")
        expect(page.locator('[data-test="perf-report"]')).to_be_visible(timeout=20000)

        # ---- door 1: closed switch -> tinted refusal band naming the key ----
        btn = page.locator('[data-test="perf-report-insight-btn"]')
        expect(btn).to_be_visible(timeout=8000)
        expect(btn).to_be_enabled(timeout=5000)
        btn.click()
        refuse = page.locator('[data-test="perf-report-insight-refuse"]')
        expect(refuse).to_be_visible(timeout=15000)
        expect(refuse).to_contain_text(GATE_KEY, timeout=5000)
        # the refusal is a band inside the report card, never a dialog
        assert page.locator(".el-dialog:visible").count() == 0
        assert page.locator(".el-message-box:visible").count() == 0

        # ---- open the switch through the real config chain (hot reload) ----
        _set_gate(api_client, "true")
        log_before = int(sql_query(
            "SELECT count(*) FROM tp_ai_call_log WHERE function = '压测归因解读'"))

        # ---- door 2: double-click ships exactly one request ----
        # Fresh counter scoped to the double-click: the door-1 click and this
        # leg's first click both sent requests, only THIS pair must collapse
        # into one. The second click is a raw event dispatch (force semantics)
        # because Element Plus loading marks the native button disabled — the
        # browser then blocks the click entirely, which is exactly the guard.
        dbl_seen = []
        page.on("request",
                lambda r: dbl_seen.append(r.url) if f"/runs/{run_id}/insight" in r.url else None)
        btn.click()
        expect(btn).to_be_disabled(timeout=5000)
        btn.dispatch_event("click")  # double-click attempt while in flight
        expect(page.locator('[data-test="perf-report-insight-card"]').last).to_be_visible(timeout=40000)
        insight_hops = [u for u in dbl_seen if f"/runs/{run_id}/insight" in u]
        assert len(insight_hops) == 1, \
            f"double-click shipped {len(insight_hops)} model requests (expected exactly 1)"

        # ---- door 3: real hop terminal = read-out text or honest refusal ----
        card = page.locator('[data-test="perf-report-insight-card"]').last
        text_loc = page.locator('[data-test="perf-report-insight-text"]')
        if text_loc.count():
            text = (text_loc.first.text_content() or "").strip()
            assert len(text) > 0, "read-out band rendered empty — an invented blank"
            log_end = int(sql_query(
                "SELECT count(*) FROM tp_ai_call_log WHERE function = '压测归因解读'"))
            assert log_end == log_before + 1, \
                f"cost anchor drift: call log {log_before} -> {log_end}"
        else:
            # provider-unreachable honesty branch: the ai leg's refusal rides
            # through untranslated — reported, never faked into a success.
            expect(refuse).to_be_visible(timeout=5000)
            body = (refuse.text_content() or "").strip()
            assert len(body) > 0, "neither a read-out nor an honest refusal rendered"

        # the door never writes run/scenario rows (read-out stays transient)
        assert int(sql_query(
            f"SELECT count(*) FROM tp_perf_scenario WHERE project_id = {pid}")) == 1
        assert int(sql_query(
            f"SELECT count(*) FROM tp_perf_run WHERE scenario_id = {scen_id}")) == 1

        _strip_expected_gate403(collectors)
        assert_no_errors(collectors, "perf report AI insight card")
    finally:
        try:
            _set_gate(api_client, "false")
        except Exception as e:  # noqa: BLE001
            print(f"!! gate restore failed (manual check): {e}")
        deadline = time.time() + 10
        while time.time() < deadline:
            if sql_query(f"SELECT config_value FROM tp_sys_config WHERE config_key = '{GATE_KEY}'") == "false":
                break
            time.sleep(1)
        try:
            if run_id:
                sql_query(f"DELETE FROM tp_perf_metric WHERE run_id = {run_id}")
                sql_query(f"DELETE FROM tp_perf_run WHERE id = {run_id}")
            if scen_id:
                sql_query(f"DELETE FROM tp_perf_scenario WHERE id = {scen_id}")
            api_client.delete(f"/projects/{pid}/purge?force=true")
        except Exception as e:  # noqa: BLE001
            print(f"!! fixture sweep failed (manual check): {e}")
        left = int(sql_query(
            f"SELECT count(*) FROM tp_perf_scenario WHERE name LIKE '%{MARKER}%'"))
        assert left == 0, f"fixture residue: {left} scenario rows"
