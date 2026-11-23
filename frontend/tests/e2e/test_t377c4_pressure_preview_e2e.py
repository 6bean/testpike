""" segment c-4 U-2 pressure-curve preview E2E (real browser + real admin chain, no mocks).

Covers (strict-testing §3 five checks):
- wizard step 2 renders the preview card (SVG polyline + caliber line) live on
 the default CONSTANT_RPS shape;
- switching models re-derives the picture (LADDER staircase differs; editing a
 parameter recalcs the path — no stale drawing);
- FIXED_VU loops-only (no wall-clock window) degrades honestly to the
 "nothing to preview" line instead of a fake zero-length curve;
- the caliber line names the vertical-axis unit (RPS negating the concurrent-
 users misreading; VU for the closed-pool model even without a drawn shape);
- all API response codes / console / Issues panel zero-tolerance (assert_no_errors).

Zero writes: the probe never submits; the fixture project is purge-cleaned and
a row count of 0 is asserted before teardown.
"""
import pytest
from conftest import BASE_URL, assert_no_errors

pytestmark = pytest.mark.timeout(180)


def _fill_num(page, test_id, value):
    """el-input-number commits on Enter (typing alone is not the model value)."""
    box = page.locator(f'[data-test="{test_id}"] input')
    box.click()
    box.fill(str(value))
    box.press("Enter")


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} 业务失败: {body.get('msg')}"
    return body["data"]


@pytest.fixture()
def perf_env(api_client, uniq):
    """Real-API fixture: project + env (same shape as test_perf_wizard_e2e, purge on teardown)."""
    pid = _ok(api_client.post("/projects", json={
        "name": f"压测预览E2E项目{uniq}", "code": f"PP{uniq[:5].upper()}"}), "建项目")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"压测预览E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083/actuator/health"}), "建环境")
    yield pid, eid, f"压测预览E2E项目{uniq}"
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _open_pressure_step(page, pid, uniq):
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    from playwright.sync_api import expect
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)
    page.locator('[data-test="perf-create-btn"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    env_sel = page.locator('[data-test="pw-env"]')
    env_sel.click()
    env_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="压测预览E2E环境")
    expect(env_opt.first).to_be_visible(timeout=10000)
    env_opt.first.click()
    page.locator('[data-test="pw-name"]').fill(f"预览探针{uniq}")
    page.locator('[data-test="pw-step-url"]').fill("http://localhost:8083/actuator/health")
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible()


@pytest.mark.timeout(240)
def test_t377c4_pressure_preview_reacts_and_stays_honest(logged_in, collectors, api_client, uniq, perf_env):
    """U-2: preview card draws per model, recalcs on parameter change, honest null for loops-only."""
    page = logged_in
    pid, _eid, _proj = perf_env
    from playwright.sync_api import expect
    _open_pressure_step(page, pid, uniq)

    # ---- default CONSTANT_RPS: preview card + svg + caliber line visible ----
    card = page.locator('[data-test="pw-pressure-preview"]')
    expect(card).to_be_visible()
    svg = page.locator('[data-test="pw-preview-svg"]')
    expect(svg).to_be_visible()
    d_flat = svg.locator("path").get_attribute("d")
    assert d_flat and d_flat.startswith("M"), f"恒定形态折线缺失: {d_flat!r}"
    caliber = page.locator('[data-test="pw-pressure-caliber"]')
    text = caliber.inner_text()
    assert "每秒请求数" in text and "并发用户数" in text, \
        f"口径纠偏行未点名 RPS 与非并发用户误读: {text!r}"

    # ---- LADDER switch: staircase path differs from the flat line ----
    page.locator('[data-test="pw-model-ladder"]').click()
    expect(page.locator('[data-test="pw-preview-svg"]')).to_be_visible()
    d_ladder = page.locator('[data-test="pw-preview-svg"] path').get_attribute("d")
    assert d_ladder != d_flat, "切 LADDER 后预览未重导（陈旧图=声明即所见破防）"

    # ---- parameter edit recalcs the picture live ----
    before = page.locator('[data-test="pw-preview-svg"] path').get_attribute("d")
    box = page.locator('[data-test="pw-ladder-steps"] input')
    box.click()
    box.fill("8")
    box.press("Enter")
    after = page.locator('[data-test="pw-preview-svg"] path').get_attribute("d")
    assert before != after, "级数变更后预览未重导"

    # ---- FIXED_VU loops-only: honest null (no window claim, no fake shape) ----
    page.locator('[data-test="pw-model-fixed-vu"]').click()
    loops = page.locator('[data-test="pw-loops"] input')
    loops.click()
    loops.fill("15")
    loops.press("Enter")
    dur = page.locator('[data-test="pw-fv-duration"] input')
    dur.click()
    dur.fill("0")
    dur.press("Enter")
    expect(page.locator('[data-test="pw-preview-none"]')).to_be_visible()
    assert page.locator('[data-test="pw-preview-svg"]').count() == 0, \
        "loops-only 仍画假零长曲线（诚实缺席破防）"
    # loops-only caliber keeps naming the VU axis (unit truth even without a shape)
    assert "VU" in page.locator('[data-test="pw-pressure-caliber"]').inner_text(), \
        "FIXED_VU 口径行未点名 VU 单位"

    # ---- restore a drawable shape before leaving (no half-dead card on exit) ----
    # NOTE: hour segments only render inside the declared window (server
    # PerfCurveSegments rule mirrored) — with the default 60s window even a
    # weight at hour 1 is honestly absent; widen the window first.
    page.locator('[data-test="pw-model-curve"]').click()
    _fill_num(page, "pw-curve-duration", 7200)
    page.locator('[data-test="pw-curve-toggle"]').click()
    expect(page.locator('[data-test="pw-curve-grid"]')).to_be_visible()
    _fill_num(page, "pw-curve-h1", 60)
    expect(page.locator('[data-test="pw-preview-svg"]')).to_be_visible()

    page.keyboard.press("Escape")
    expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)

    # zero writes: the probe must never have persisted a scenario
    from conftest import sql_query
    cnt = sql_query(f"SELECT count(*) FROM tp_perf_scenario WHERE project_id={pid}")
    assert cnt == "0", f"预览探针意外落库 {cnt} 行"

    assert_no_errors(collectors, "T377 c-4 U-2 压力曲线预览")
