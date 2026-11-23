""" knife c-3b: CF-1 ceiling card on the perf report page (both spectra).

Spectrum A (LADDER): a staircase run with three declared steps, the third breaching
the pinned P95 gate — the report page must show the ceiling card with a three-row
tier table, a one-screen conclusion naming the first breaching step and the
SLO-safe watermark, and the caliber note (nominal-rate disclosure). The admin->core
API contract is probed in the same test (breachStep/lastPassStep/safeWatermarkRps
recomputed from the same pinned snapshot the page renders — never a second head).

Spectrum B (CONSTANT): a non-ladder run honestly carries no ceiling row — the card
must be absent (zero DOM), not an empty shell.

Runs are SQL-built fixtures (same posture as test_perf_run_detail_e2e: launch via
POST would dispatch perf-v1 tasks and race the live executor; the report reads
frames from the DB, which is the surface under test here). Cleanup purges the
throwaway project through the real API.
"""

import json

import pytest
from conftest import BASE_URL, assert_no_errors, sql_query
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} business failure: {body.get('msg')}"
    return body["data"]


def _snapshot(model: str, pressure_config: dict) -> str:
    """Launch-shaped pinned snapshot (name/pressureModel/pressureConfig/steps/sla)."""
    return json.dumps({
        "name": f"寻顶夹具{model}",
        "pressureModel": model,
        "pressureConfig": json.dumps(pressure_config),
        "loginStrategy": "PER_VUSER",
        "steps": '[{"action":"http.request","method":"GET","url":"http://localhost:8083/actuator/health"}]',
        "sla": {"p95Ms": 100},
    }).replace("'", "''")


@pytest.fixture()
def ceiling_runs(api_client, uniq):
    """One LADDER run (steps 20/30/40 nominal, P95 50/60/200 -> step 3 breaches)
    plus one CONSTANT run sharing the same pinned sla."""
    pid = _ok(api_client.post("/projects", json={
        "name": f"寻顶卡E2E项目{uniq}", "code": f"CL{uniq[:5].upper()}"}), "create project")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"寻顶卡E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083"}), "create env")
    sid = _ok(api_client.post(f"/perf/scenarios?projectId={pid}", json={
        "projectId": pid, "name": f"寻顶卡场景{uniq}", "envId": eid,
        "pressureModel": "LADDER",
        "pressureConfig": {"startRps": 10, "stepRps": 10, "stepSeconds": 5, "steps": 3},
        "loginStrategy": "PER_VUSER", "driverLimit": None,
        "steps": [{"action": "http.request", "method": "GET",
                   "url": "http://localhost:8083/actuator/health"}],
        "slaJson": None}), "create scenario")

    def _mk_run(snapshot: str) -> int:
        return int(sql_query(
            "INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot, "
            "stop_reason, started_at, finished_at) VALUES ("
            f"{sid}, {pid}, 4, '{snapshot}'::jsonb, '夹具终态运行', "
            "now() - interval '20 seconds', now()) RETURNING id").splitlines()[0])

    ladder = _mk_run(_snapshot("LADDER",
                               {"startRps": 10, "stepRps": 10, "stepSeconds": 5, "steps": 3}))
    constant = _mk_run(_snapshot("CONSTANT_RPS", {"targetRps": 5, "durationSeconds": 15}))

    # Ladder frames: 3 steps x 5s, per-second P95 rising 50 -> 60 -> 200 (gate p95Ms=100
    # breaches only the third step; fallback caliber reads the worst second per step).
    rows = []
    for sec in range(15):
        step = sec // 5
        p95 = (50, 60, 200)[step]
        rows.append(f"({ladder}, {pid}, {sec}, 20, {p95}, 0.01, 20, 0)")
    sql_query("INSERT INTO tp_perf_metric (run_id, project_id, second_offset, rps, "
              "p95_ms, error_rate, req_count, fail_count) VALUES " + ",".join(rows))
    sql_query("INSERT INTO tp_perf_metric (run_id, project_id, second_offset, rps, "
              "p95_ms, error_rate, req_count, fail_count) VALUES " + ",".join(
                  f"({constant}, {pid}, {sec}, 5, 40, 0.01, 5, 0)" for sec in range(12)))

    yield pid, ladder, constant
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _open_run_page(page, pid, run_id):
    # Dashboard bounce first: query-only navigation would reuse a mounted component.
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf/run/{run_id}?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-run-page"]')).to_be_visible(timeout=15000)


def test_t377c3b_ceiling_card_ladder_and_constant(logged_in, collectors, api_client, ceiling_runs):
    page = logged_in
    pid, ladder, constant = ceiling_runs

    # ---- L3 contract probe (real admin->core chain, pinned-snapshot single source) ----
    report = _ok(api_client.get(f"/perf/runs/{ladder}/report?projectId={pid}"), "ladder report")
    ceil = report["ceiling"]
    assert ceil is not None and ceil["applicable"] is True, "LADDER report must carry a ceiling row"
    assert [t["step"] for t in ceil["tiers"]] == [1, 2, 3]
    assert ceil["breachStep"] == 3 and ceil["lastPassStep"] == 2, \
        f"ceiling indices drift: {ceil['breachStep']}/{ceil['lastPassStep']}"
    # Watermark = last passing step's nominal (10 + 10*2 = 30) x ratio key (default 0.8) = 24.
    assert abs(ceil["safeWatermarkRps"] - 24.0) < 0.01, f"watermark: {ceil['safeWatermarkRps']}"
    assert ceil["kneeStep"] == 3, "latency knee must flag the third step (growth 140 > 2x10)"
    assert "寻顶结论" in ceil["conclusion"] and "SLO 安全水位" in ceil["conclusion"]
    const_report = _ok(api_client.get(f"/perf/runs/{constant}/report?projectId={pid}"),
                       "constant report")
    assert const_report["ceiling"] is None, "non-LADDER report must honestly omit the ceiling row"

    # ---- Spectrum A: the card renders in a real browser ----
    _open_run_page(page, pid, ladder)
    expect(page.locator('[data-test="perf-report"]')).to_be_visible(timeout=20000)
    card = page.locator('[data-test="perf-ceiling-card"]')
    expect(card).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="perf-ceiling-conclusion"]')).to_contain_text("寻顶结论")
    expect(page.locator('[data-test="perf-ceiling-conclusion"]')).to_contain_text("首次破线")
    expect(page.locator('[data-test="perf-ceiling-conclusion"]')).to_contain_text("SLO 安全水位")
    expect(page.locator('[data-test="perf-ceiling-knee"]')).to_be_visible()
    tiers = page.locator('[data-test="perf-ceiling-table"] .el-table__body tr.el-table__row')
    expect(tiers).to_have_count(3)
    # Breach row is the third; the tint classes mirror the core indices (no recompute).
    expect(tiers.nth(2)).to_contain_text("破线")
    expect(tiers.nth(0)).to_contain_text("通过")
    expect(page.locator('[data-test="perf-ceiling-caliber"]')).to_contain_text("名义速率")

    # ---- Spectrum B: CONSTANT run shows no card (never an empty shell) ----
    _open_run_page(page, pid, constant)
    expect(page.locator('[data-test="perf-report"]')).to_be_visible(timeout=20000)
    assert page.locator('[data-test="perf-ceiling-card"]').count() == 0, \
        "non-LADDER report rendered a ceiling card (fabricated row?)"

    assert_no_errors(collectors, "T377 c-3b ceiling card both spectra")
