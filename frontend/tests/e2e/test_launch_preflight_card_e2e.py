""" E2E: the launch dialog's resource pre-flight card (CF-5 + CF-7).

Real browser over the real admin->core chain, no mocks. Contract under test:
- opening the launch action renders the read-only pre-flight card once the
 server readout lands (capacity wording comes from the dispatch path's own
 single sources; an offline fleet is itself a named spectrum — the card
 carries wording either way);
- the confirm button stays enabled with the card on screen (notice, not gate:
 rejection is owned by the launch transaction, never by this card);
- cancelling creates nothing server-side.
Zero API 4xx/5xx, zero console error/warn, zero browser Issues (5c).

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
def preflight_env(api_client, uniq):
    pid = _ok(api_client.post("/projects", json={
        "name": f"预检卡片E2E项目{uniq}", "code": f"PF{uniq[:5].upper()}"}), "建项目")
    env_id = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"预检环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083/actuator/health"}), "建环境")
    sid = _ok(api_client.post("/perf/scenarios", json={
        "projectId": pid, "name": f"预检场景{uniq}", "envId": env_id,
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 2, "durationSeconds": 8},
        "steps": [{"action": "http.request", "method": "GET",
                   "url": "http://127.0.0.1:8899/", "auth": False}]}), "建场景")
    yield pid, sid, f"预检场景{uniq}"
    api_client.delete(f"/projects/{pid}/purge?force=true")


def test_launch_dialog_shows_preflight_card_without_gating(logged_in, collectors, api_client, preflight_env):
    page = logged_in
    pid, sid, sc_name = preflight_env

    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="perf-row-launch"]').first).to_be_visible(timeout=15000)

    # dialog opens immediately (pre-flight fills asynchronously, never blocks it)
    page.locator('[data-test="perf-row-launch"]').first.click()
    dialog = page.locator('[data-test="perf-launch-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    expect(dialog).to_contain_text(sc_name, timeout=5000)

    # the pre-flight card renders with server-projected wording (engine line +
    # at least the capacity sentence — an offline fleet is itself named wording)
    card = page.locator('[data-test="perf-preflight-card"]')
    expect(card).to_be_visible(timeout=10000)
    expect(card).to_contain_text("压测引擎", timeout=5000)
    expect(card.locator(".perf-preflight__line").first).to_contain_text("压测", timeout=5000)

    # notice, not gate: the confirm button is enabled while the card is on screen
    confirm = dialog.locator('[data-test="perf-launch-confirm"]')
    expect(confirm).to_be_enabled(timeout=5000)

    # cancel creates nothing (positive launch spectra are proven at L3)
    dialog.locator('.el-dialog__footer button:has-text("取消")').first.click()
    expect(dialog).to_be_hidden(timeout=5000)
    data = _ok(api_client.get(f"/perf/runs/page?projectId={pid}&scenarioId={sid}&page=1&pageSize=10"),
               "运行列表")
    items = data.get("list") if isinstance(data, dict) else data
    assert len(items or []) == 0

    assert_no_errors(collectors, "T380 launch pre-flight card")
