""" segment c-4 U-4 journey-card editor E2E (real browser + real admin chain, no mocks).

Covers (strict-testing §3 five checks):
- step 1 of the wizard shows the journey zone in its flat state with an
 honest enable door (no cards — the server save gate refuses a half-declared
 mix, so the UI door is the only entry and nothing renders before it);
- enabling puts every declared step into a link: one card with member chips,
 weight slider and share reading (chips+slider+donut, never a nested
 el-table — U-4 red line);
- the all-or-none invariant survives growth: adding a step keeps it inside a
 link instead of silently breaking the declaration;
- cancelling strips BOTH keys from every step and the scenario still saves
 (proven through the real admin→core chain, then read back from the server:
 zero transaction/weight keys persisted = the round trip is honest);
- all API response codes / console / Issues panel zero tolerance.

Writes go to the purge-cleaned fixture project only.
"""
import pytest
from playwright.sync_api import expect
from conftest import BASE_URL, assert_no_errors

pytestmark = pytest.mark.timeout(240)


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} 业务失败: {body.get('msg')}"
    return body["data"]


@pytest.fixture()
def perf_env(api_client, uniq):
    """Real-API fixture: project + env (same shape as the U-2 probe, purge on teardown)."""
    pid = _ok(api_client.post("/projects", json={
        "name": f"压测旅程E2E项目{uniq}", "code": f"PJ{uniq[:5].upper()}"}), "建项目")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"压测旅程E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083/actuator/health"}), "建环境")
    yield pid, eid
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _open_wizard(page, pid, uniq):
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)
    page.locator('[data-test="perf-create-btn"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    page.locator('[data-test="pw-env"]').click()
    env_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                           has_text="压测旅程E2E环境")
    expect(env_opt.first).to_be_visible(timeout=10000)
    env_opt.first.click()
    page.locator('[data-test="pw-name"]').fill(f"旅程探针{uniq}")
    page.locator('[data-test="pw-step-url"]').fill("http://localhost:8083/actuator/health")


def test_t377u4_journey_cards_declare_clear_and_persist(logged_in, collectors, api_client, uniq, perf_env):
    """U-4: enable→card with chips/slider/share, 2nd step joins, clear strips keys, save round-trips mix-free."""
    page = logged_in
    pid, _eid = perf_env

    _open_wizard(page, pid, uniq)

    # ---- flat state: the door exists, no cards render ----
    expect(page.locator("#pw-journey-label")).to_be_visible()
    assert page.locator('[data-test="pw-journey-card"]').count() == 0, \
        "未申报时仍渲染旅程卡（凭空捏造链路）"
    enable_btn = page.locator('[data-test="pw-journey-enable"]')
    expect(enable_btn).to_be_visible()

    # ---- enable: the single declared step joins a link = exactly one card ----
    enable_btn.click()
    cards = page.locator('[data-test="pw-journey-card"]')
    expect(cards).to_have_count(1)
    expect(cards.first.locator('[data-test="pw-journey-weight"]')).to_be_visible()
    expect(cards.first.locator('[data-test="pw-journey-members"]')).to_contain_text("第 1 步")
    assert "占比" in cards.first.inner_text(), "旅程卡缺配比读数（滑杆无参照=盲调）"

    # ---- invariant under growth: a new step joins a link, never a half-mix ----
    page.locator('[data-test="pw-step-add"]').click()
    page.locator('[data-test="pw-step-row-1"] [data-test="pw-step-url"]').fill(
        "http://localhost:8083/actuator/info")
    expect(cards).to_have_count(1)
    expect(cards.first.locator('[data-test="pw-journey-members"]')).to_contain_text("第 2 步")

    # ---- rename the link through its card name input (el-input falls the
    # data-test anchor onto the native input — scoped locator, no wrapper) ----
    name_box = page.locator('input[data-test="pw-journey-name"]')
    name_box.fill("浏览链路")
    name_box.press("Enter")
    # committed draft rides the group name (input value, not card textContent)
    expect(name_box).to_have_value("浏览链路")

    # ---- cancel: both keys leave every step (no orphan weight survives) ----
    page.locator('[data-test="pw-journey-clear"]').click()
    expect(page.locator('[data-test="pw-journey-enable"]')).to_be_visible()
    assert page.locator('[data-test="pw-journey-card"]').count() == 0

    # ---- walk to confirm and really save the flat declaration ----
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-gate"]')).to_be_visible()
    page.locator('[data-test="pw-precheck"]').click()
    expect(page.locator('[data-test="pw-gate-result"]')).to_be_visible(timeout=15000)
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-confirm"]')).to_be_visible()
    page.locator('[data-test="pw-submit"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=15000)

    # ---- server truth: the saved scenario carries zero mix keys (round trip honest) ----
    rows = _ok(api_client.get(f"/perf/scenarios?projectId={pid}"), "场景列表")
    saved = next((s for s in rows if s["name"] == f"旅程探针{uniq}"), None)
    assert saved is not None, f"服务端列表缺场景: {rows}"
    detail = _ok(api_client.get(f"/perf/scenarios/{saved['id']}?projectId={pid}"), "场景详情")
    steps_json = detail.get("stepsJson") or detail.get("steps_json") or "[]"
    assert "transaction" not in steps_json and '"weight"' not in steps_json, \
        f"取消申报后服务端仍残留配比键: {steps_json}"

    assert_no_errors(collectors, "U-4 旅程卡申报-取消-保存全链")
