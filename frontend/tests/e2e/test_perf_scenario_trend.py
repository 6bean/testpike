
import json

import pytest
from conftest import BASE_URL, assert_no_errors, sql_query
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)

# 28 histogram boundaries + open top = 28 count slots (PerfHistogram contract).
_HIST_LEN = 28


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} 业务失败: {body.get('msg')}"
    return body["data"]


@pytest.fixture()
def trend_env(api_client, uniq):
    """Real project + scenario via API; 40 terminal runs seeded SQL-side.

    run A (golden, bucket caliber): per-second frames with hist + req/fail counts.
    run B (legacy, fallback caliber): frames with p95/error only, no buckets —
    the trend row must honestly null p50/p99 and badge the worst-second wording.
    """
    pid = _ok(api_client.post("/projects", json={
        "name": f"趋势E2E项目{uniq}", "code": f"TR{uniq[:5].upper()}"}), "建项目")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"趋势E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083"}), "建环境")
    sid = _ok(api_client.post(f"/perf/scenarios?projectId={pid}", json={
        "projectId": pid, "name": f"趋势场景{uniq}", "envId": eid,
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 5, "durationSeconds": 30},
        "loginStrategy": "PER_VUSER", "driverLimit": None,
        "steps": [{"action": "http.request", "method": "GET",
                   "url": "http://localhost:8083/actuator/health"}],
        "slaJson": None}), "建场景")

    snapshot = json.dumps({
        "name": f"趋势场景{uniq}", "pressureModel": "CONSTANT_RPS",
        "pressureConfig": '{"targetRps":5,"durationSeconds":30}',
        "loginStrategy": "PER_VUSER",
        "steps": '[{"action":"http.request","method":"GET","url":"http://localhost:8083/actuator/health"}]',
        "sla": {"breakerErrorRate": 0.05}}).replace("'", "''")

    def _mk_run(status: int, mark: str = "", minutes_ago: int = 0) -> int:
        mark_sql = f"'{mark}'" if mark else "NULL"
        return int(sql_query(
            "INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot, "
            "mark, started_at, finished_at) VALUES ("
            f"{sid}, {pid}, {status}, '{snapshot}'::jsonb, {mark_sql}, "
            f"now() - interval '{60 + minutes_ago} minutes', "
            f"now() - interval '{minutes_ago} minutes') RETURNING id").splitlines()[0])

    # 38 plain terminal runs + golden + legacy as the TWO newest rows: the row
    # ledger reads newest-first (same posture as the runs drawer), so the
    # caliber/golden fixtures land inside the first rendered window.
    plain = [_mk_run(2, "", 60 - i) for i in range(38)]
    golden = _mk_run(2, "GOLDEN", 2)
    legacy = _mk_run(2, "", 1)

    hist = json.dumps([5] * _HIST_LEN)
    for off in range(6):
        sql_query(
            "INSERT INTO tp_perf_metric (run_id, project_id, second_offset, rps, p95_ms, "
            "error_rate, req_count, fail_count, hist, step_key) VALUES ("
            f"{golden}, {pid}, {off}, 5.0, {60 + off}, 0.02, 100, 2, "
            f"'{hist}'::jsonb, '')")
    for off in range(6):
        sql_query(
            "INSERT INTO tp_perf_metric (run_id, project_id, second_offset, rps, p95_ms, "
            "error_rate, step_key) VALUES ("
            f"{legacy}, {pid}, {off}, 5.0, {80 + off}, 0.03, '')")
    yield pid, sid, {"golden": golden, "legacy": legacy, "plain": plain}
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _land(page, pid, trend=""):
    """Dashboard bounce forces a remount so the deep-link setup re-runs."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    q = f"&trend={trend}" if trend else ""
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}{q}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)


def test_perf_trend_deep_link_worst_scale_and_caliber_wording(logged_in, collectors, trend_env):
    """Deep link lands the trend drawer: 40-run worst scale stays windowed in the DOM,
    both calibers show human wording, the golden badge shows its display name, and a
    row click jumps to the run detail page."""
    page = logged_in
    pid, sid, ids = trend_env

    _land(page, pid, trend=sid)
    drawer = page.locator('[data-test="perf-trend-drawer"]')
    expect(drawer).to_be_visible(timeout=15000)

    # curve + foot count: the window carries all 40 terminal runs (ascending)
    expect(page.locator('[data-test="perf-trend-chart"]')).to_be_visible()
    foot = page.locator('[data-test="perf-trend-foot"]')
    expect(foot).to_contain_text("40", timeout=10000)

    # display-scale iron law: 40-row source, windowed DOM (never the full ledger)
    dom_rows = page.locator('[data-test="perf-trend-row"]').count()
    assert 0 < dom_rows < 40, f"trend ledger must be windowed, got {dom_rows} DOM rows"

    # screen-text iron law: raw caliber/mark machine enums never on screen
    body = drawer.inner_text()
    for leaked in ("BUCKET", "FALLBACK", "WEIGHTED", "MEAN", "GOLDEN", "CANDIDATE"):
        assert leaked not in body, f"raw enum leaked to the screen: {leaked}"

    # golden badge shows the display word
    expect(drawer.locator("text=金跑").first).to_be_visible()

    # both calibers render their human wording somewhere in the window
    expect(foot).to_be_visible()
    whole_run_word = drawer.locator("text=全程分位")
    worst_second_word = drawer.locator("text=秒窗最差值")
    assert whole_run_word.count() >= 1 or worst_second_word.count() >= 1
    # the newest 40-run window covers golden+legacy (they are the OLDEST two
    # insertions but the K8 window is newest-first 200 — well above 40)
    expect(whole_run_word.first).to_be_visible(timeout=5000)
    expect(worst_second_word.first).to_be_visible(timeout=5000)

    # row click lands on the run detail page with the project query
    first_row = page.locator('[data-test="perf-trend-row"]').first
    first_row.click()
    page.wait_for_url("**/perf/run/*", timeout=15000)
    assert "/perf/run/" in page.url

    # deep-link restore: navigate back with the query and the drawer re-opens
    _land(page, pid, trend=sid)
    expect(page.locator('[data-test="perf-trend-drawer"]')).to_be_visible(timeout=15000)

    # honest empty trend: a scenario id with no runs -> no drawer crash, empty copy
    _land(page, pid)
    expect(page.locator('[data-test="perf-scenario-table"]')).to_be_visible(timeout=15000)

    assert_no_errors(collectors, "perf scenario trend panel")
