""" A-13 scenario-delete E2E (real browser, real admin→core delete chain, no mock).

Coverage (strict-testing §3/§8 + D2 ruling + confirmation-card naming iron law):
- the row delete action reads core's impact ledger and the card names the real
 counts (finished runs kept, approval tickets unlinked) — no generic "are you sure";
- a scenario with a run in flight shows the blocked wording and the confirm
 button is honestly disabled (front refusal), the door itself still refuses
 server-side (single-point gate);
- after a successful delete: the row leaves the list, reload keeps it gone
 (persistence), the finished run survives with a NULL scenario link and its
 project/audit rows keep their trail (approval unlinked, not deleted);
- all API codes 200 / zero console error / zero Vue warn / zero browser issues.

Fixture note: project + env + scenario through the real API (unique names, never
the regression-demo project); run/approval rows SQL-seeded (same posture as
test_perf_scenario_trend — a real launch would hand the run to the live executor
and race the UI assertions). teardown = project purge (perf table family cascade).
"""
import json

import pytest
from conftest import BASE_URL, assert_no_errors, sql_query
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} 业务失败: {body.get('msg')}"
    return body["data"]


@pytest.fixture()
def delete_env(api_client, uniq):
    """Real project + env + one scenario carrying 2 finished runs and 1 pending approval."""
    pid = _ok(api_client.post("/projects", json={
        "name": f"删除E2E项目{uniq}", "code": f"DL{uniq[:5].upper()}"}), "建项目")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"删除E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083"}), "建环境")
    sid = _ok(api_client.post(f"/perf/scenarios?projectId={pid}", json={
        "projectId": pid, "name": f"删除场景{uniq}", "envId": eid,
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 5, "durationSeconds": 30},
        "loginStrategy": "PER_VUSER", "driverLimit": None,
        "steps": [{"action": "http.request", "method": "GET",
                   "url": "http://localhost:8083/actuator/health"}],
        "slaJson": None}), "建场景")

    snapshot = json.dumps({
        "name": f"删除场景{uniq}", "pressureModel": "CONSTANT_RPS",
        "pressureConfig": '{"targetRps":5,"durationSeconds":30}',
        "loginStrategy": "PER_VUSER",
        "steps": '[{"action":"http.request","method":"GET","url":"http://localhost:8083/actuator/health"}]',
        "sla": {"breakerErrorRate": 0.05}}).replace("'", "''")

    def _mk_run(status: int, minutes_ago: int = 0) -> int:
        return int(sql_query(
            "INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot, "
            f"started_at, finished_at) VALUES ({sid}, {pid}, {status}, '{snapshot}'::jsonb, "
            f"now() - interval '{60 + minutes_ago} minutes', "
            f"now() - interval '{minutes_ago} minutes') RETURNING id").splitlines()[0])

    run_a = _mk_run(2, 30)
    run_b = _mk_run(2, 10)
    # applicant FK = the admin user resolved live (never a hardcoded id)
    admin_id = sql_query("SELECT id FROM tp_user WHERE username = 'admin'").splitlines()[0].strip()
    aid = int(sql_query(
        "INSERT INTO tp_perf_approval (project_id, env_id, requested_concurrency, "
        "isolation_mode, reason, applicant_id, status, scenario_id) VALUES ("
        f"{pid}, {eid}, 100, 'SHADOW_TABLE', '删除E2E审批单{uniq}', {admin_id}, 2, {sid}) "
        "RETURNING id").splitlines()[0])
    yield pid, eid, sid, {"runA": run_a, "runB": run_b, "approval": aid}
    api_client.delete(f"/projects/{pid}/purge?force=true")


@pytest.fixture()
def delete_env_with_flight(api_client, uniq):
    """Same shape but one run stays in flight — the refusal spectrum."""
    pid = _ok(api_client.post("/projects", json={
        "name": f"拒删E2E项目{uniq}", "code": f"RF{uniq[:5].upper()}"}), "建项目")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"拒删E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083"}), "建环境")
    sid = _ok(api_client.post(f"/perf/scenarios?projectId={pid}", json={
        "projectId": pid, "name": f"拒删场景{uniq}", "envId": eid,
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 5, "durationSeconds": 30},
        "loginStrategy": "PER_VUSER", "driverLimit": None,
        "steps": [{"action": "http.request", "method": "GET",
                   "url": "http://localhost:8083/actuator/health"}],
        "slaJson": None}), "建场景")
    snapshot = json.dumps({"name": "x"}).replace("'", "''")
    running = int(sql_query(
        "INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot, "
        f"started_at) VALUES ({sid}, {pid}, 1, '{snapshot}'::jsonb, now()) RETURNING id"
    ).splitlines()[0])
    yield pid, sid, running
    # the door refuses while the run is in flight; settle it before the purge
    sql_query(f"UPDATE tp_perf_run SET status = 3, finished_at = now(), "
              f"stop_reason = 'E2E cleanup' WHERE id = {running}")
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _land(page, pid):
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-scenario-table"]')).to_be_visible(timeout=15000)


def test_perf_scenario_delete_card_and_decoupled_history(logged_in, collectors, delete_env):
    """Delete card names the real ledger; the confirm keeps history decoupled
    (run rows survive with a NULL link, approval loses only the scenario leg)."""
    page = logged_in
    pid, _eid, sid, ids = delete_env

    _land(page, pid)
    row = page.locator('[data-test="perf-scenario-table"] tbody tr').first
    expect(row).to_contain_text("删除场景", timeout=15000)

    row.locator('[data-test="perf-row-delete"]').click()
    dialog = page.locator('[data-test="perf-delete-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)

    # the card waits for the real ledger and shows core's counts verbatim
    impact = page.locator('[data-test="perf-delete-impact"]')
    expect(impact).to_be_visible(timeout=10000)
    expect(impact).to_contain_text("2")   # two finished runs kept
    expect(impact).to_contain_text("1")   # one approval ticket unlinked
    # no blocked banner on a quiet scenario, confirm stays enabled
    expect(page.locator('[data-test="perf-delete-blocked"]')).to_have_count(0)
    expect(page.locator('[data-test="perf-delete-confirm"]')).to_be_enabled()

    page.locator('[data-test="perf-delete-confirm"]').click()
    expect(dialog).to_be_hidden(timeout=10000)
    expect(page.locator('[data-test="perf-scenario-table"] tbody tr')).to_have_count(0, timeout=10000)

    # reload: the deletion persisted — the segment now renders the empty guide
    # (the table only exists while rows do, so assert its absence + the guide)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="perf-scenario-table"]')).to_have_count(0, timeout=10000)

    # DB truth (D2): finished runs survive decoupled, approval keeps its trail
    rows = sql_query(
        f"SELECT status, scenario_id FROM tp_perf_run WHERE id IN ({ids['runA']}, {ids['runB']}) "
        "ORDER BY id")
    lines = [ln for ln in rows.splitlines() if ln.strip()]
    assert len(lines) == 2, f"finished runs must survive the delete: {rows}"
    for ln in lines:
        assert ln.split("|")[1].strip() in ("", "NULL"), f"run must be decoupled: {ln}"
    appr = sql_query(
        f"SELECT status, scenario_id FROM tp_perf_approval WHERE id = {ids['approval']}")
    parts = [p.strip() for p in appr.splitlines()[0].split("|")]
    assert parts[0] == "2", f"approval ticket must NOT be deleted: {appr}"
    assert parts[1] in ("", "NULL"), f"approval must drop the scenario leg: {appr}"
    gone = sql_query(f"SELECT count(*) FROM tp_perf_scenario WHERE id = {sid}")
    assert gone.strip() == "0", "scenario row must be gone"

    assert_no_errors(collectors, "perf scenario delete card")


def test_perf_scenario_delete_refused_while_run_in_flight(logged_in, collectors, delete_env_with_flight):
    """In-flight spectrum: the card names the blocker and disables confirm
    (honest refusal up front); the core door refuses too if anyone bypasses the UI."""
    page = logged_in
    pid, sid, running = delete_env_with_flight

    _land(page, pid)
    row = page.locator('[data-test="perf-scenario-table"] tbody tr').first
    row.locator('[data-test="perf-row-delete"]').click()
    dialog = page.locator('[data-test="perf-delete-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)

    blocked = page.locator('[data-test="perf-delete-blocked"]')
    expect(blocked).to_be_visible(timeout=10000)
    expect(blocked).to_contain_text("1")
    expect(page.locator('[data-test="perf-delete-confirm"]')).to_be_disabled()

    # server-side single point: a direct API delete is refused with a human reason
    import httpx
    from conftest import API_BASE
    token = page.evaluate("() => localStorage.getItem('tp_access_token')")
    resp = httpx.delete(f"{API_BASE}/perf/scenarios/{sid}?projectId={pid}",
                        headers={"Authorization": f"Bearer {token}"}, timeout=10)
    body = resp.json()
    assert body.get("code") != "0", f"door must refuse an in-flight scenario: {body}"
    assert "运行中" in (body.get("msg") or ""), f"refusal must name the blocker: {body}"
    alive = sql_query(f"SELECT count(*) FROM tp_perf_scenario WHERE id = {sid}")
    assert alive.strip() == "1", "refused delete must leave the row untouched"

    page.locator('[data-test="perf-delete-dialog"] button:has-text("取消")').first.click()
    expect(dialog).to_be_hidden(timeout=10000)

    assert_no_errors(collectors, "perf scenario delete refusal")
