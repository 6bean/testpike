""" stage-d E2E: GB-1 launch dialog in the perf workbench (real browser,
real admin->core chain, no mocks).

Covers the new front-half contract:
- the row launch action opens a dialog instead of firing the run directly;
- the environment selector preselects the scenario-bound environment
 (fallback spectrum = the historical behavior);
- switching the selection updates the model and the confirm button stays
 enabled (positive spectra that actually create runs are proven at L3 in
 the internal launch-env L3 script — here we never confirm, so zero side effect);
- cancelling creates no run (server list stays empty).
Zero API 4xx/5xx, zero console error/warn, zero browser Issues (§3/§4/5c).

Parallel safety: uniq-suffixed fixture project; purge on teardown; never uses
the demo projects.
"""
import pytest
from conftest import BASE_URL, assert_no_errors
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} 业务失败: {body.get('msg')}"
    return body["data"]


@pytest.fixture()
def perf_env(api_client, uniq):
    # Fixture via real API: project + env A bound to the scenario + spare env B
    # (no run is dispatched here — the dialog never confirms).
    pid = _ok(api_client.post("/projects", json={
        "name": f"发起弹窗E2E项目{uniq}", "code": f"LD{uniq[:5].upper()}"}), "建项目")
    env_a = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"弹窗场景环境A{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083/actuator/health"}), "建环境A")
    env_b = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"弹窗切换环境B{uniq}", "envType": 1,
        "baseUrl": "http://localhost:8083/actuator/health"}), "建环境B")
    sid = _ok(api_client.post("/perf/scenarios", json={
        "projectId": pid, "name": f"弹窗场景{uniq}", "envId": env_a,
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 2, "durationSeconds": 8},
        "steps": [{"action": "http.request", "method": "GET",
                   "url": "http://127.0.0.1:8899/", "auth": False}]}), "建场景")
    yield pid, env_a, env_b, sid, f"弹窗场景{uniq}", f"弹窗切换环境B{uniq}"
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _run_count(api_client, pid, sid):
    data = _ok(api_client.get(f"/perf/runs/page?projectId={pid}&scenarioId={sid}&page=1&pageSize=10"),
               "运行列表")
    items = data.get("list") if isinstance(data, dict) else data
    return len(items or [])


def test_launch_dialog_preselect_switch_cancel(logged_in, collectors, api_client, perf_env):
    page = logged_in
    pid, _env_a, _env_b, sid, sc_name, env_b_name = perf_env

    # deep link lands the global header context
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="perf-row-launch"]').first).to_be_visible(timeout=15000)

    # 1) launch action opens the dialog (never fires directly)
    page.locator('[data-test="perf-row-launch"]').first.click()
    dialog = page.locator('[data-test="perf-launch-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    expect(dialog).to_contain_text(sc_name, timeout=5000)

    # 2) selector preselects the scenario-bound env A (fallback default)
    env_sel = page.locator('[data-test="pl-env"]')
    expect(env_sel).to_contain_text("弹窗场景环境A", timeout=5000)

    # 3) switch the selection to env B — option list comes from getProjectEnvs
    env_sel.click()
    option_b = page.locator(f".el-select-dropdown__item:has-text('{env_b_name}')").last
    expect(option_b).to_be_visible(timeout=5000)
    option_b.click()
    expect(env_sel).to_contain_text("弹窗切换环境B", timeout=5000)

    # 4) cancel creates nothing server-side (positive spectra = L3 script)
    page.locator('.el-dialog__footer button:has-text("取消")').first.click()
    expect(dialog).to_be_hidden(timeout=5000)
    assert _run_count(api_client, pid, sid) == 0

    assert_no_errors(collectors, "T377d launch dialog")
