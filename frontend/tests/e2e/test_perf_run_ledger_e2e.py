
import json
import subprocess
import uuid

import pytest
from conftest import BASE_URL, assert_no_errors, pick_project_in_header
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

N_LINKED = 26     # runs still attached to the live scenario
N_DECOUPLED = 12  # A-13 history rows (scenario_id NULL, snapshot-frozen name)


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} business failure: {body.get('msg')}"
    return body["data"]


def _psql(q):
    r = subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
                        "-d", "testpike", "-tAc", q],
                       capture_output=True, text=True, check=False)
    assert r.returncode == 0, f"sql failed: {r.stderr[:200]}"
    return r.stdout.strip()


def _pick(page, data_test, keyword):
    """Open an el-select by data-test and click the visible option containing keyword."""
    page.locator(f'[data-test="{data_test}"]').click()
    opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                       has_text=keyword)
    expect(opt.first).to_be_visible(timeout=10000)
    opt.first.click()


@pytest.fixture()
def ledger_env(api_client):
    """Real project/env/scenario + 38 SQL-seeded runs (worst-scale window).

    Distribution (all totals asserted against SQL, not against the fixture):
      linked 26 rows (status 4x6 / 2x6 / 3x6 / 1x8, CANDIDATE x4 + VOID x3);
      decoupled 12 rows (status 4 GOLDEN x5 + status 4 plain x3 + status 3 x3 +
      status 2 x1, snapshot-frozen names). uq_perf_run_golden_per_scenario
      forbids a second GOLDEN on one scenario, so all five goldens are A-13
      history rows; every golden is status 4, making the status+mark
      intersection window exactly 5 rows.
    """
    uniq = uuid.uuid4().hex[:8]
    pid = _ok(api_client.post("/projects", json={
        "name": f"台账页E2E项目{uniq}", "code": f"LG{uniq[:5].upper()}"}), "create project")
    pid = pid["id"] if isinstance(pid, dict) else pid
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"台账页E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083"}), "create env")
    eid = eid["id"] if isinstance(eid, dict) else eid
    sid = _ok(api_client.post(f"/perf/scenarios?projectId={pid}", json={
        "projectId": pid, "name": f"台账页场景{uniq}", "envId": eid,
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 5, "durationSeconds": 30},
        "loginStrategy": "PER_VUSER", "driverLimit": None,
        "steps": [{"action": "http.request", "method": "GET",
                   "url": "http://localhost:8083/actuator/health"}],
        "slaJson": None}), "create scenario")
    sid = sid["id"] if isinstance(sid, dict) else sid

    rows = []  # (scenario_id|None, status, mark, snapshot_name_or_None)
    # linked rows: statuses 4x6 + 2x6 + 1x8 + 3x6 interleaved, marks CANDIDATE4 VOID3
    linked_cycle = [4, 4, 4, 4, 4, 4, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1,
                    3, 3, 3, 3, 3, 3]
    linked_marks = ["CANDIDATE"] * 4 + ["VOID"] * 3 + [""] * (N_LINKED - 7)
    for i, st in enumerate(linked_cycle):
        rows.append((sid, st, linked_marks[i], None))
    # decoupled rows: 5 GOLDEN at status 4, then 2x4 / 3x4 / 2x3 with names
    decoupled = ([(4, "GOLDEN")] * 5 + [(4, ""), (4, ""), (4, ""),
                                        (3, ""), (3, ""), (3, ""), (2, "")])
    for j, (st, mk) in enumerate(decoupled):
        rows.append((None, st, mk, f"脱钩场景{j}号{uniq}"))

    values = []
    for k, (scen, st, mk, name) in enumerate(rows):
        snap = {"name": name or f"台账页场景{uniq}", "pressureModel": "CONSTANT_RPS",
                "pressureConfig": '{"targetRps":5,"durationSeconds":30}'}
        snap_txt = json.dumps(snap, ensure_ascii=False).replace("'", "''")
        scen_sql = "NULL" if scen is None else str(scen)
        # ck_perf_run_mark allows only the three mark values or NULL — an
        # "unmarked" row must be a real NULL, never an empty string.
        mark_sql = "NULL" if not mk else f"'{mk}'"
        finished = "NULL" if st == 1 else f"now() - interval '{10 + k} minutes'"
        values.append(f"({scen_sql}, {pid}, {st}, '{snap_txt}'::jsonb, {mark_sql}, "
                      f"now() - interval '{30 + k} minutes', {finished})")
    _psql("INSERT INTO tp_perf_run (scenario_id, project_id, status, "
          "pressure_snapshot, mark, started_at, finished_at) VALUES "
          + ",".join(values))

    yield pid, sid, uniq
    api_client.delete(f"/projects/{pid}/purge?force=true")


def test_perf_run_ledger_worst_scale_and_doors(logged_in, ledger_env, collectors):
    """Ledger: paging window, server filters, decoupled names, F15 restore, detail door."""
    page = logged_in
    pid, _sid, uniq = ledger_env
    total_sql = int(_psql(f"SELECT COUNT(*) FROM tp_perf_run WHERE project_id={pid}"))
    assert total_sql == N_LINKED + N_DECOUPLED, "fixture seeding lost rows"
    st4_sql = int(_psql(f"SELECT COUNT(*) FROM tp_perf_run WHERE project_id={pid} AND status=4"))
    gold_sql = int(_psql(f"SELECT COUNT(*) FROM tp_perf_run WHERE project_id={pid} AND mark='GOLDEN'"))
    both_sql = int(_psql(f"SELECT COUNT(*) FROM tp_perf_run WHERE project_id={pid} "
                         "AND status=4 AND mark='GOLDEN'"))

    pick_project_in_header(page, f"台账页E2E项目{uniq}")
    page.goto(f"{BASE_URL}/#/perf/runs", wait_until="domcontentloaded")
    table = page.locator('[data-test="perf-ledger-table"]')
    expect(table).to_be_visible(timeout=15000)

    # ownership echo (G clause 3): the subtitle names the current segment.
    expect(page.locator('[data-test="perf-ledger-subtitle"]')).to_contain_text(
        f"台账页E2E项目{uniq}", timeout=10000)

    # worst-scale window: page 1 = 20 rows, SQL total echoed by the pager.
    expect(page.locator('[data-test="perf-ledger-table"] .el-table__body-wrapper tbody tr'))\
        .to_have_count(20)
    expect(page.locator('[data-test="perf-ledger-pager"]')).to_contain_text(
        f"共 {total_sql} 条", timeout=10000)

    # A-13 face: default id-DESC puts the freshly seeded decoupled rows on
    # page 1; the ledger must name them by the snapshot-frozen scenario name.
    expect(page.get_by_text(f"脱钩场景0号{uniq}").first).to_be_visible(timeout=10000)

    # page 2 really comes from the server (remaining 18 rows).
    page.locator('[data-test="perf-ledger-pager"] button.btn-next').click()
    expect(page.locator('[data-test="perf-ledger-table"] .el-table__body-wrapper tbody tr'))\
        .to_have_count(total_sql - 20)

    # server-side status filter: narrowed total straight from SQL.
    _pick(page, "perf-ledger-filter-status", "人工停压")
    expect(page.locator('[data-test="perf-ledger-pager"]')).to_contain_text(
        f"共 {st4_sql} 条", timeout=10000)
    rows4 = page.locator('[data-test="perf-ledger-table"] tbody tr')
    expect(rows4).to_have_count(min(st4_sql, 20))
    # every visible status tag agrees with the filter
    tags = page.locator('[data-test="perf-ledger-table"] [data-test="perf-ledger-status-tag"]')
    for i in range(tags.count()):
        expect(tags.nth(i)).to_have_text("人工停压")

    # intersection: + GOLDEN -> exactly the both_sql window, tags agree.
    _pick(page, "perf-ledger-filter-mark", "金跑")
    expect(page.locator('[data-test="perf-ledger-pager"]')).to_contain_text(
        f"共 {both_sql} 条", timeout=10000)
    expect(page.locator('[data-test="perf-ledger-table"] tbody tr')).to_have_count(both_sql)
    mtags = page.locator('[data-test="perf-ledger-table"] [data-test="perf-ledger-mark-tag"]')
    assert mtags.count() == both_sql
    expect(mtags.first).to_have_text("金跑")
    assert gold_sql >= both_sql  # sanity against the fixture math

    # F15 restoration: filters + page rode the query; reload re-lands the state.
    url = page.url
    assert "status=4" in url and "mark=GOLDEN" in url, f"query lost page state: {url}"
    page.goto(url, wait_until="domcontentloaded")
    table2 = page.locator('[data-test="perf-ledger-table"]')
    expect(table2).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="perf-ledger-pager"]')).to_contain_text(
        f"共 {both_sql} 条", timeout=10000)
    # EP 2.14 renders the chosen label in the wrapper's selected-item slot
    # (the inner input stays empty — to_have_value would assert a stale 1.x
    # contract), so the restore check reads the visible select text instead.
    expect(page.locator('[data-test="perf-ledger-filter-status"]'))\
        .to_contain_text("人工停压", timeout=10000)
    expect(page.locator('[data-test="perf-ledger-filter-mark"]'))\
        .to_contain_text("金跑", timeout=10000)

    # clear filters -> full window back (unfiltered total).
    # EP 2.14: the clear icon only materializes when the wrapper is hovered
    # (the inner input is covered by the selected-item layer, hovering it
    # directly times out — pointer events land on the wrapper text).
    mark_sel = page.locator('[data-test="perf-ledger-filter-mark"]')
    mark_sel.hover()
    mark_sel.locator(".el-select__clear").click()
    # only the mark door clears — status=4 still narrows the window, so the
    # honest expectation is the status-only total (not the unfiltered one).
    expect(page.locator('[data-test="perf-ledger-pager"]')).to_contain_text(
        f"共 {st4_sql} 条", timeout=10000)

    # detail door: the top row of the CURRENT window (status=4, id-DESC, page 1)
    # is predictable from SQL — el-table-column attrs don't reach the rendered
    # cells, so the row id is taken from the server ordering, not scraped.
    expected_first = int(_psql(f"SELECT id FROM tp_perf_run WHERE project_id={pid} "
                               "AND status=4 ORDER BY id DESC LIMIT 1"))
    first_row = page.locator('[data-test="perf-ledger-table"] tbody tr').first
    expect(first_row).to_be_visible(timeout=10000)
    page.locator('[data-test="perf-ledger-open-detail"]').first.click()
    expect(page).to_have_url(
        f"{BASE_URL}/#/perf/run/{expected_first}?projectId={pid}", timeout=15000)
    assert_no_errors(collectors, "perf run ledger E2E")
