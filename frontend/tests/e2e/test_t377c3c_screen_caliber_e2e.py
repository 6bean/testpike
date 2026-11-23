

import re

import pytest
from conftest import BASE_URL, assert_no_errors, sql_query
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)

# Machine codes that must never reach a user-facing string.
BARE_KEY_RE = re.compile(r"perf-p95|perf-error-rate|perf-throughput")
JARGON_RE = re.compile(r"O10|D6 单源|PerfSlaJudge|注册表")


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} business failure: {body.get('msg')}"
    return body["data"]


def _snapshot() -> str:
    import json
    return json.dumps({
        "name": "屏显修复夹具LADDER",
        "pressureModel": "LADDER",
        "pressureConfig": json.dumps(
            {"startRps": 10, "stepRps": 10, "stepSeconds": 5, "steps": 3}),
        "loginStrategy": "PER_VUSER",
        "steps": '[{"action":"http.request","method":"GET",'
                 '"url":"http://localhost:8083/actuator/health"}]',
        "sla": {"p95Ms": 100, "errorRate": 0.005},
    }).replace("'", "''")


@pytest.fixture()
def breaching_ladder_run(api_client, uniq):
    """LADDER run whose third step breaches the P95 gate and whose overall error
    rate breaches 0.5% — both gate names must appear in screen strings."""
    pid = _ok(api_client.post("/projects", json={
        "name": f"屏显门禁修复项目{uniq}", "code": f"SC{uniq[:5].upper()}"}), "create project")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"屏显门禁修复环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083"}), "create env")
    sid = _ok(api_client.post(f"/perf/scenarios?projectId={pid}", json={
        "projectId": pid, "name": f"屏显门禁修复场景{uniq}", "envId": eid,
        "pressureModel": "LADDER",
        "pressureConfig": {"startRps": 10, "stepRps": 10, "stepSeconds": 5, "steps": 3},
        "loginStrategy": "PER_VUSER", "driverLimit": None,
        "steps": [{"action": "http.request", "method": "GET",
                   "url": "http://localhost:8083/actuator/health"}],
        "slaJson": None}), "create scenario")
    run_id = int(sql_query(
        "INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot, "
        "stop_reason, started_at, finished_at) VALUES ("
        f"{sid}, {pid}, 4, '{_snapshot()}'::jsonb, '夹具终态运行', "
        "now() - interval '20 seconds', now()) RETURNING id").splitlines()[0])
    rows = []
    for sec in range(15):
        step = sec // 5
        p95 = (50, 60, 200)[step]
        # Third step carries 1 failed request per second (5/100 = 5% > 0.5% gate),
        # so both the P95 and the error-rate gate names must reach the screen.
        fail = 1 if step == 2 else 0
        rows.append(f"({run_id}, {pid}, {sec}, 20, {p95}, {fail / 20:.3f}, 20, {fail})")
    sql_query("INSERT INTO tp_perf_metric (run_id, project_id, second_offset, rps, "
              "p95_ms, error_rate, req_count, fail_count) VALUES " + ",".join(rows))
    yield pid, run_id
    api_client.delete(f"/projects/{pid}/purge?force=true")


def test_t377c3c_screen_strings_carry_no_machine_codes(api_client, breaching_ladder_run):
    """L3 probe: every user-facing verdict/ceiling string on a breaching run is
    free of bare caliber codes and registry jargon."""
    pid, run_id = breaching_ladder_run
    report = _ok(api_client.get(f"/perf/runs/{run_id}/report?projectId={pid}"), "report")

    verdict = report["verdict"]
    screens = {
        "verdict.conclusion": verdict["conclusion"],
        "verdict.breachedGates": "，".join(verdict["breachedGates"]),
    }
    ceil = report.get("ceiling") or {}
    screens["ceiling.conclusion"] = ceil.get("conclusion", "")
    screens["ceiling.caliberNote"] = ceil.get("caliberNote", "")
    screens["ceiling.kneeDescription"] = ceil.get("kneeDescription", "")
    for tier in ceil.get("tiers", []):
        screens[f"tier{tier['step']}.breachedGates"] = "，".join(tier["breachedGates"])

    for name, text in screens.items():
        assert not BARE_KEY_RE.search(text), f"{name} leaks bare caliber code: {text}"
        assert not JARGON_RE.search(text), f"{name} leaks registry jargon: {text}"

    # Punctuation hygiene (vision re-score ): the caliber note is a
    # sentence — it must close with a full stop on the API screen string too.
    assert ceil.get("caliberNote", "").endswith("。"), ceil.get("caliberNote")

    # Gate names are still present — in plain words, not dropped.
    assert "压测响应时间 P95" in verdict["conclusion"], verdict["conclusion"]
    assert "压测错误率" in verdict["conclusion"], verdict["conclusion"]


def test_t377c3c_caliber_footnote_band_gap_at_least_8px(
        logged_in, collectors, breaching_ladder_run):
    """Browser DOM: tier table bottom -> caliber footnote top gap >= 8px, and the
    rendered gate column text has zero bare keys."""
    page = logged_in
    pid, run_id = breaching_ladder_run
    # Dashboard bounce first: query-only navigation would reuse a mounted component.
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf/run/{run_id}?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-report"]')).to_be_visible(timeout=20000)

    card = page.locator('[data-test="perf-ceiling-card"]')
    expect(card).to_be_visible(timeout=10000)
    gates = card.locator('.el-table__body').inner_text()
    assert not BARE_KEY_RE.search(gates), f"gate column renders bare keys: {gates}"
    assert "压测响应时间 P95" in gates, gates

    gap = page.evaluate(
        """() => {
            const table = document.querySelector(
                '[data-test="perf-ceiling-card"] [data-test="perf-ceiling-table"]');
            const note = document.querySelector('[data-test="perf-ceiling-caliber"]');
            return note.getBoundingClientRect().top - table.getBoundingClientRect().bottom;
        }""")
    assert gap >= 8, f"table->caliber footnote gap is {gap}px (< 8px, visual 粘连)"

    assert_no_errors(collectors, "T377 c-3c screen caliber hygiene")
