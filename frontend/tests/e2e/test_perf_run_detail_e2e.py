
import json

import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    nav_to,
    open_apitest_datasource_item,
    pick_apitest_collection,
    pick_apitest_request,
    pick_project_in_header,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} 业务失败: {body.get('msg')}"
    return body["data"]


@pytest.fixture()
def perf_runs(api_client, uniq):
    pass
    pid = _ok(api_client.post("/projects", json={
        "name": f"运行页E2E项目{uniq}", "code": f"RU{uniq[:5].upper()}"}), "建项目")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"运行页E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083"}), "建环境")
    sid = _ok(api_client.post(f"/perf/scenarios?projectId={pid}", json={
        "projectId": pid, "name": f"运行页场景{uniq}", "envId": eid,
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 5, "durationSeconds": 30},
        "loginStrategy": "PER_VUSER", "driverLimit": None,
        "steps": [{"action": "http.request", "method": "GET",
                   "url": "http://localhost:8083/actuator/health"}],
        "slaJson": None}), "建场景")
    snapshot = json.dumps({
        "name": f"运行页场景{uniq}", "pressureModel": "CONSTANT_RPS",
        "pressureConfig": '{"targetRps":5,"durationSeconds":30}',
        "loginStrategy": "PER_VUSER",
        "steps": '[{"action":"http.request","method":"GET","url":"http://localhost:8083/actuator/health"}]',
        "sla": {"breakerErrorRate": 0.05}}).replace("'", "''")
    # GA-5 : r1's snapshot carries the pinned target version the
    # launch writes today (tp_project_env.version_snapshot frozen as targetVersion);
    # r3 stays without it, so the page exercises both faces — named version and
    # honest "not recorded".
    snapshot_v = json.dumps({
        "name": f"运行页场景{uniq}", "pressureModel": "CONSTANT_RPS",
        "pressureConfig": '{"targetRps":5,"durationSeconds":30}',
        "loginStrategy": "PER_VUSER",
        "steps": '[{"action":"http.request","method":"GET","url":"http://localhost:8083/actuator/health"}]',
        "sla": {"breakerErrorRate": 0.05},
        "targetVersion": "v9.9-e2e"}).replace("'", "''")

    def _mk_run(status: int, stop_reason: str, snap: str = "") -> int:
        return int(sql_query(
            "INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot, "
            "stop_reason, started_at, finished_at) VALUES ("
            f"{sid}, {pid}, {status}, '{snap or snapshot}'::jsonb, '{stop_reason}', "
            "now() - interval '10 seconds', "
            + ("NULL" if status == 1 else "now()") + ") RETURNING id").splitlines()[0])

    r1 = _mk_run(4, "操作人人工停压（夹具预置对比候选）", snapshot_v)
    r2 = _mk_run(4, "操作人人工停压（夹具预置对比候选）")
    r3 = _mk_run(1, "")
    # U-8 golden lane: r2 is pinned GOLDEN through the real marking
    # endpoint, so the report's one-click "compare with golden" button lands on it.
    _ok(api_client.post(f"/perf/runs/{r2}/mark?projectId={pid}&mark=GOLDEN"), "金跑打标")
    # b6 frozen/data-loss face: r1 carries real per-second frames plus one
    # data-loss event row, so the terminal page shows the frozen annotation and
    # escalates the tail-loss truth on the page body (U-6 contract in a browser).
    _frames = ",".join(
        f"({r1}, {pid}, {i}, {5 + i % 3}, {40 + i * 2}, 0.01, 20, 0)"
        for i in range(12))
    sql_query("INSERT INTO tp_perf_metric (run_id, project_id, second_offset, rps, "
              "p95_ms, error_rate, req_count, fail_count) VALUES " + _frames)
    sql_query("INSERT INTO tp_perf_sample (run_id, project_id, second_offset, "
              "event_kind, detail) VALUES "
              f"({r1}, {pid}, 11, 'data-loss', '末段 3 秒采样未在停压前落库（夹具）')")
    yield pid, sid, (r1, r2), r3
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _pick(page, data_test, keyword):
    pass
    sel = page.locator(f'[data-test="{data_test}"]')
    sel.click()
    opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text=keyword)
    expect(opt.first).to_be_visible(timeout=10000)
    opt.first.click()


def _pick_perf_project(page, pid):
    """: page-level project selector removed — the ?projectId deep link lands
 the global header context. Dashboard bounce forces a remount (query-only
 navigation reuses the component)."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)


def test_perf_run_detail_stop_report_compare(logged_in, collectors, api_client, uniq, perf_runs):
    pass
    page = logged_in
    pid, _sid, (r1, r2), r3 = perf_runs

    nav_to(page, "压测工作台", "/perf", '[data-test="perf-page"]', submenu="执行测试")
    _pick_perf_project(page, pid)
    expect(page.locator('[data-test="perf-scenario-table"] tr').first).to_be_visible(timeout=10000)
    page.locator('[data-test="perf-row-runs"]').first.click()
    expect(page.locator('[data-test="perf-runs-drawer"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="perf-run-row"]').first.click()

    expect(page.locator('[data-test="perf-run-page"]')).to_be_visible(timeout=10000)
    page.wait_for_url(f"**/perf/run/{r3}**", timeout=10000)
    expect(page.locator('[data-test="perf-run-status"]')).to_have_text("运行中", timeout=10000)

    # GA-5 f-2: r3's snapshot carries no pinned build (the fixture env has no
    # version_snapshot) — the header must state that honestly, never a blank row.
    expect(page.locator('[data-test="perf-run-target-version"]')).to_contain_text(
        "被测版本：环境未记录构建版本", timeout=10000)

    expect(page.locator('[data-test="perf-run-chart"]')).to_be_visible()
    expect(page.locator('[data-test="perf-run-chart"]')).to_contain_text("尚未收到指标帧")

    page.locator('[data-test="perf-run-stop-btn"]').click()
    page.locator('.el-message-box__btns .el-button--primary').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="perf-run-status"]')).to_have_text("人工停压", timeout=20000)

    expect(page.locator(".perf-run__meta")).to_contain_text("人工停压", timeout=10000)

    expect(page.locator('[data-test="perf-report"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="perf-report-verdict"]')).to_be_visible()
    assert (page.locator('[data-test="perf-report-conclusion"]').inner_text()).strip(), \
        "报告结论行为空（红绿灯正文缺失）"

    # U-8 layer 2 bottleneck clues: r3 has zero frames and zero error classes —
    # the card must state the honest empty wording, never a blank fake.
    expect(page.locator('[data-test="perf-report-clues"]')).to_contain_text(
        "本次运行未采集到错误分类", timeout=10000)

    # U-8 leftover 1: a zero on the auth trio is explained on hover (a zero is
    # "no login step in this run", not a fault).
    expect(page.locator('[data-test="perf-report-auth-seconds"]')).to_have_text("0")
    page.locator('[data-test="perf-report-auth-seconds"]').hover()
    expect(page.locator('.el-popper[aria-hidden="false"]')).to_contain_text(
        "本次压测未启用登录认证环节", timeout=10000)

    # U-8 layer 4 copyable caliber footnote: button mounted and the text non-empty
    # (clipboard permission behavior is browser-env dependent; the write chain is
    # pinned by the vitest contract perfReportLayering, E2E only checks presence).
    expect(page.locator('[data-test="perf-report-caliber"]').locator(".perf-report__foot")).not_to_have_text("")
    expect(page.locator('[data-test="perf-report-copy-caliber"]')).to_be_visible()

    _pick(page, "perf-compare-select", f"#{r1}")
    page.locator('[data-test="perf-compare-btn"]').click()
    expect(page.locator('[data-test="perf-compare-table"]')).to_be_visible(timeout=15000)

    # GA-5 f-2 compare block: each side names its pinned build from its own
    # snapshot — A (r3) honest "not recorded", B (r1) the frozen version string.
    expect(page.locator('[data-test="perf-compare-version-a"]')).to_contain_text(
        "环境未记录构建版本", timeout=5000)
    expect(page.locator('[data-test="perf-compare-version-b"]')).to_contain_text("v9.9-e2e")

    # U-8 layer 3 golden lane: the fixture pins r2 as GOLDEN through the real
    # marking endpoint, so the report offers a one-click "compare with golden"
    # button and B lands on r2 without touching the dropdown.
    golden_btn = page.locator('[data-test="perf-compare-golden"]')
    expect(golden_btn).to_contain_text(f"与金跑 #{r2} 对比", timeout=10000)
    golden_btn.click()
    expect(page.locator(".perf-report__cmp-head")).to_contain_text(f"B #{r2}", timeout=15000)

    page.locator('[data-test="perf-run-events-btn"]').click()
    expect(page.locator('[data-test="perf-run-events-drawer"]')).to_be_visible(timeout=10000)
    # R67: the drawer read-out renders through the shared tail foundation, not a
    # view-local row list — the foundation's own window and empty wording prove it.
    tail_window = page.locator('[data-test="perf-run-events-drawer"] [data-test="task-tail-list"]')
    expect(tail_window).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="perf-run-events-drawer"]')).to_contain_text("暂无过程事件")
    page.keyboard.press("Escape")
    # Revisit door: leaving the page and returning keeps the tail window reachable
    # (a dashboard bounce forces a remount; the events button re-mounts the foundation).
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.goto(f"{BASE_URL}/#/perf/run/{r3}?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-run-page"]')).to_be_visible(timeout=15000)
    page.locator('[data-test="perf-run-events-btn"]').click()
    expect(tail_window).to_be_visible(timeout=10000)
    page.keyboard.press("Escape")

    nav_to(page, "压测工作台", "/perf", '[data-test="perf-page"]', submenu="执行测试")
    _pick_perf_project(page, pid)
    page.locator('[data-test="perf-row-runs"]').first.click()
    expect(page.locator('[data-test="perf-run-row"]').first).to_contain_text("人工停压", timeout=10000)
    page.keyboard.press("Escape")

    api = _ok(api_client.get(f"/perf/runs/{r3}?projectId={pid}"), "运行详情 API")
    assert api["status"] == 4, f"服务端运行态异常: {api['status']}"
    db = sql_query(f"SELECT status FROM tp_perf_run WHERE id={r3}")
    assert db == "4", f"DB 运行态异常: {db}"

    # b6 U-6 terminal freeze + data-loss escalation: deep-link r1 (the fixture
    # carries 12 frames plus one data-loss event). The frozen annotation names the
    # freeze second and the total collected seconds; the tail-loss event surfaces as
    # a page-body alert (never hidden in the drawer); the stop button must be absent
    # on a terminal run (U-7 terminal gate).
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.goto(f"{BASE_URL}/#/perf/run/{r1}?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-run-page"]')).to_be_visible(timeout=15000)
    # GA-5 f-2: r1's snapshot pins v9.9-e2e — the header names it verbatim.
    expect(page.locator('[data-test="perf-run-target-version"]')).to_contain_text(
        "被测版本：v9.9-e2e", timeout=10000)
    expect(page.locator('[data-test="perf-run-curve-frozen"]')).to_contain_text(
        "曲线定格于第 11 秒", timeout=10000)
    expect(page.locator('[data-test="perf-run-curve-frozen"]')).to_contain_text("全程共采集 12 秒")
    expect(page.locator('[data-test="perf-run-data-loss"]')).to_be_visible(timeout=5000)
    expect(page.locator('[data-test="perf-run-data-loss"]')).to_contain_text(
        "尾部采样缺失——末段曲线非完整数据")
    expect(page.locator('[data-test="perf-run-data-loss"]')).to_contain_text(
        "末段 3 秒采样未在停压前落库（夹具）")
    assert page.locator('[data-test="perf-run-stop-btn"]').count() == 0, \
        "终态页仍渲染停压钮（U-7 终态闸破坏）"

    assert_no_errors(collectors, "压测运行详情页停压+报告+对比+事件抽屉+持久化")


@pytest.fixture()
def apitest_asset(api_client, uniq):
    pass
    pid = _ok(api_client.post("/projects", json={
        "name": f"升格E2E项目{uniq}", "code": f"UP{uniq[:5].upper()}"}), "建项目")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"升格E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083"}), "建环境")
    coll = _ok(api_client.post("/apitest/collections", json={
        "projectId": pid, "parentId": 0, "name": f"升格集合{uniq}", "tags": ""}), "建集合")
    req_name = f"健康检查请求{uniq}"
    _ok(api_client.post("/apitest/requests", json={
        "collectionId": coll["id"], "name": req_name, "method": "GET",
        "urlPath": "/actuator/health", "headersJson": "[]", "bodyText": "",
        "authJson": None, "assertionsJson": "[]"}), "建请求")
    yield pid, eid, req_name
    api_client.delete(f"/projects/{pid}/purge?force=true")


def test_apitest_save_as_perf_upgrade_entry(logged_in, collectors, uniq, apitest_asset):
    pass
    page = logged_in
    _pid, _eid, req_name = apitest_asset

    # apitest_asset creates the project AFTER logged_in injected the token, so
    # the header visible-project snapshot is stale — a full reload re-runs
    # loadMe and the new project lands in the header option set.
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    nav_to(page, "API 测试", "/api-test", '[data-test="api-test-page"]', submenu="执行测试")
    # anchor migration: page-level selectors retired — header context picks
    # the project, rail cards pick the collection and the request row.
    pick_project_in_header(page, f"升格E2E项目{uniq}")
    pick_apitest_collection(page, f"升格集合{uniq}")
    pick_apitest_request(page, req_name)

    # Save-as entry (a dropdown item since the action-area consolidation):
    # enabled once all three ids are set; the workbench onMounted consumes the
    # asset-reference query and clears it via replace (asserting consumption =
    # wizard auto-open + prefill is the reliable oracle, not the transient query)
    open_apitest_datasource_item(page, "apitest-save-as-perf-btn",
                                 menu_anchor="apitest-more-actions-menu")
    page.wait_for_url("**/perf**", timeout=10000)
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible(timeout=10000)

    _pick(page, "pw-env", f"升格E2E环境{uniq}")
    expect(page.locator('[data-test="pw-name"]')).to_have_value(req_name, timeout=10000)
    expect(page.locator('[data-test="pw-step-url"]')).to_have_value(
        "http://localhost:8083/actuator/health", timeout=10000)

    page.locator('[data-test="perf-wizard"] .el-dialog__headerbtn').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)

    assert_no_errors(collectors, "API 用例另存压测场景升格入口+预填")
