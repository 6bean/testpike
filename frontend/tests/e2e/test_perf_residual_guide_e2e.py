""" GB-4 knife 2: residual-cleanup guide block on the perf report page.

Spectrum A (declared modes): two terminal runs, one pinning NS_ACCOUNT with a
bound dataset ledger row (recycle roster names the dataset + account columns +
row count) and one pinning HEADER_MARK (sample-verification guidance naming the
X-Load-Test mark header). The report page must show the guide block whose
sections/boundary note are the backend-produced strings verbatim — the browser
assembles none of them (single-source contract pinned by the vitest layout test).

Spectrum B (undeclared): a run whose pinned snapshot carries no isolation mode
honestly shows no guide block at all (zero DOM, never an empty shell).

Runs are SQL-built fixtures (same posture as test_t377c3b_ceiling_card_e2e:
launch via POST would dispatch perf-v1 tasks and race the live executor; the
report reads the pinned snapshot + frames from the DB, the surface under test).
The ledger row is a plain INSERT — the report read path never touches object
storage (only the launcher does, and this test never launches).
"""

import json

import pytest
from conftest import BASE_URL, assert_no_errors, sql_query
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(240)


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} business failure: {body.get('msg')}"
    return body["data"]


def _snapshot(extra: dict) -> str:
    base = {
        "name": "残留巡检夹具",
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": json.dumps({"targetRps": 5, "durationSeconds": 10}),
        "loginStrategy": "PER_VUSER",
        "steps": '[{"action":"http.request","method":"GET","url":"http://localhost:8083/actuator/health"}]',
        "sla": {"p95Ms": 100},
    }
    base.update(extra)
    return json.dumps(base).replace("'", "''")


@pytest.fixture()
def residual_runs(api_client, uniq):
    pid = _ok(api_client.post("/projects", json={
        "name": f"残留巡检E2E项目{uniq}", "code": f"RG{uniq[:5].upper()}"}), "create project")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"残留巡检E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083"}), "create env")
    sid = _ok(api_client.post(f"/perf/scenarios?projectId={pid}", json={
        "projectId": pid, "name": f"残留巡检场景{uniq}", "envId": eid,
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 5, "durationSeconds": 10},
        "loginStrategy": "PER_VUSER", "driverLimit": None,
        "steps": [{"action": "http.request", "method": "GET",
                   "url": "http://localhost:8083/actuator/health"}],
        "slaJson": None}), "create scenario")

    # Dataset ledger row (report read path only queries this table by id).
    dataset_id = int(sql_query(
        "INSERT INTO tp_perf_dataset (project_id, name, file_name, object_key, "
        "columns_json, row_count, size_bytes) VALUES ("
        f"{pid}, '压测账号池{uniq}', 'acct.csv', 'e2e-residual/{uniq}/acct.csv', "
        "'[\"username\",\"password\",\"mobile\"]'::jsonb, 42, 1024) RETURNING id"
    ).splitlines()[0])

    def _mk_run(snapshot: str) -> int:
        return int(sql_query(
            "INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot, "
            "stop_reason, started_at, finished_at) VALUES ("
            f"{sid}, {pid}, 4, '{snapshot}'::jsonb, '夹具终态运行', "
            "now() - interval '20 seconds', now()) RETURNING id").splitlines()[0])

    ns_run = _mk_run(_snapshot({"isolationMode": "NS_ACCOUNT", "datasetId": dataset_id}))
    mark_run = _mk_run(_snapshot({"isolationMode": "HEADER_MARK"}))
    plain_run = _mk_run(_snapshot({}))

    for rid in (ns_run, mark_run, plain_run):
        sql_query("INSERT INTO tp_perf_metric (run_id, project_id, second_offset, rps, "
                  "p95_ms, error_rate, req_count, fail_count) VALUES " + ",".join(
                      f"({rid}, {pid}, {sec}, 5, 40, 0.01, 5, 0)" for sec in range(8)))

    yield pid, ns_run, mark_run, plain_run, dataset_id
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _open_run_page(page, pid, run_id):
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf/run/{run_id}?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-run-page"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="perf-report-caliber"]')).to_be_visible(timeout=20000)


def test_ns_account_run_shows_recycle_roster(page, logged_in, collectors, residual_runs):
    """NS_ACCOUNT + ledger row: mode badge + section naming dataset/columns/count."""
    pid, ns_run, _mark, _plain, _ds = residual_runs
    _open_run_page(page, pid, ns_run)

    block = page.locator('[data-test="perf-residual-guide"]')
    expect(block).to_be_visible()
    expect(page.locator('[data-test="perf-residual-mode"]')).to_contain_text("命名空间账号隔离")
    sections = page.locator('[data-test="perf-residual-section"]')
    expect(sections).to_have_count(1)
    text = sections.first.inner_text()
    # Backend-produced prose lands verbatim: dataset name + row count + account columns.
    assert "压测账号池" in text and "42" in text, f"roster text: {text}"
    assert "username" in text and "mobile" in text, f"account columns: {text}"
    expect(page.locator('[data-test="perf-residual-boundary"]')).to_contain_text("被测侧就位")
    assert_no_errors(collectors, "GB-4 NS_ACCOUNT recycle roster")


def test_header_mark_run_shows_sample_verification(page, logged_in, collectors, residual_runs):
    """HEADER_MARK: guidance names the station-wide mark header X-Load-Test: 1."""
    pid, _ns, mark_run, _plain, _ds = residual_runs
    _open_run_page(page, pid, mark_run)

    block = page.locator('[data-test="perf-residual-guide"]')
    expect(block).to_be_visible()
    expect(page.locator('[data-test="perf-residual-mode"]')).to_contain_text("压测标记头隔离")
    expect(page.locator('[data-test="perf-residual-section"]').first).to_contain_text("X-Load-Test: 1")
    assert_no_errors(collectors, "GB-4 HEADER_MARK sample verification")


def test_undeclared_mode_honestly_absent(page, logged_in, collectors, residual_runs):
    """No pinned isolation mode = no guide block at all (zero DOM, no shell)."""
    pid, _ns, _mark, plain_run, _ds = residual_runs
    _open_run_page(page, pid, plain_run)

    expect(page.locator('[data-test="perf-report-caliber"]')).to_be_visible()
    assert page.locator('[data-test="perf-residual-guide"]').count() == 0, \
        "undeclared run must not render a guide shell"
    assert_no_errors(collectors, "GB-4 undeclared honest absence")
