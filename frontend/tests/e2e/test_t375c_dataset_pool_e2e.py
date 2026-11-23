""" segment c-3: first real-browser journey for the wizard CSV data pool.

Covers (strict-testing §3/§8): upload → auto-select → column chips → engine-honest
strategy picker (supported enabled / unsupported disabled with a human reason) →
save → reload edit round-trip (dataset + strategy ride back) → purge tail cleanup.
Real admin→core chain, no mocks; uniq suffixes keep parallel runs and the shared
demo projects clean.
"""
import pytest
from conftest import BASE_URL, assert_no_errors, nav_to
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} 业务失败: {body.get('msg')}"
    return body["data"]


@pytest.fixture()
def pool_env(api_client, uniq):
    """Real API seeding: one throwaway project owning the dataset + scenario.

    Project names are user-visible Chinese data (i18n surface, not comments);
    the uniq suffix keeps parallel runs and the shared demo projects clean.
    """
    pid = _ok(api_client.post("/projects", json={
        "name": f"数据池E2E项目{uniq}", "code": f"DP{uniq[:5].upper()}"}), "建项目")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"数据池E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8899"}), "建环境")
    yield pid, eid, f"数据池E2E项目{uniq}"
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _pick_project(page, pid):
    """Deep-link landing . Dashboard bounce
 forces a remount so the query-driven setup re-runs."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)


def test_t375c_wizard_dataset_pool_roundtrip(logged_in, collectors, api_client, uniq, pool_env):
    """Wizard data pool: real CSV upload -> auto-select -> column chips ->
    honest strategy disable -> save -> reload edit round-trip."""
    page = logged_in
    pid, _eid, _proj = pool_env
    scenario_name = f"数据池回归场景{uniq}"
    dataset_name = f"用户数据池{uniq}"

    # alloc policy comes from the same core single source the picker renders
    policy = _ok(api_client.get("/perf/datasets/alloc-policy"), "策略矩阵")
    engine = policy["engine"]
    supported = set(policy["supportedModes"])
    all_modes = set(policy["allModes"])
    unsupported = sorted(all_modes - supported)

    nav_to(page, "压测工作台", "/perf", '[data-test="perf-page"]', submenu="执行测试")
    _pick_project(page, pid)

    page.locator('[data-test="perf-create-btn"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    expect(page.locator('[data-test="pw-step-target"]')).to_be_visible()

    # step 1 target: env + name + one GET step against the 8899 fixture site
    env_sel = page.locator('[data-test="pw-env"]')
    env_sel.click()
    env_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                           has_text="数据池E2E环境")
    expect(env_opt.first).to_be_visible(timeout=10000)
    env_opt.first.click()
    page.locator('[data-test="pw-name"]').fill(scenario_name)
    page.locator('[data-test="pw-step-url"]').fill("http://localhost:8899/index.html")

    # dataset upload: real CSV via the hidden file input -> toast -> auto-select
    page.locator('[data-test="pw-dataset-file"]').set_input_files({
        "name": f"{dataset_name}.csv",
        "mimeType": "text/csv",
        "buffer": ("username,password\n"
                   + f"u{uniq}_1,pwd{uniq}\n"
                   + f"u{uniq}_2,pwd{uniq}\n"
                   + f"u{uniq}_3,pwd{uniq}\n").encode(),
    })
    expect(page.locator('.el-message--success')).to_be_visible(timeout=15000)

    sel = page.locator('[data-test="pw-dataset-select"]')
    expect(sel).to_contain_text(dataset_name, timeout=15000)

    # column chips: both uploaded headers join the double-brace variable domain
    chips = page.locator('[data-test="pw-dataset-columns"]')
    expect(chips).to_be_visible(timeout=10000)
    expect(chips).to_contain_text("{{username}}", timeout=10000)
    expect(chips).to_contain_text("{{password}}", timeout=10000)

    # ledger page (display-scale iron law) proves the upload landed server-side
    rows = _ok(api_client.get(f"/perf/datasets/page?projectId={pid}&page=1&pageSize=20"),
               "数据集分页")
    assert any(d["name"] == dataset_name for d in rows["list"]), \
        f"服务端账本缺数据集: {rows}"

    # Honest strategy picker: all three modes render; unsupported = disabled +
    # engine named in human words; supported = enabled; the follow-server-default
    # radio is always present (its resolved value comes from the core single source).
    # Strategy labels are zh-CN literals anchored to their locale lines
    # (zh-CN.ts:5733-5735): has_text does substring matching, so the distinct
    # lead phrase pins each option without repeating the parenthetical tail.
    LABELS = {
        "SEQUENTIAL_EXCLUSIVE": "顺序独占",      # zh-CN.ts:5733
        "VUSER_EXCLUSIVE": "虚拟用户私有切片",    # zh-CN.ts:5734
        "CYCLE": "循环共享",                     # zh-CN.ts:5735
    }
    default_opt = page.locator('[data-test="pw-alloc-group"] .el-radio',
                               has_text="跟随系统默认")
    expect(default_opt.first).to_be_visible(timeout=10000)
    for mode in sorted(all_modes):
        label = LABELS[mode]
        opt = page.locator(f'[data-test="pw-alloc-option"]:has-text("{label}")')
        expect(opt.first).to_be_visible(timeout=10000)
        if mode in supported:
            expect(opt.first.locator("input")).to_be_enabled()
        else:
            expect(opt.first.locator("input")).to_be_disabled()
            # honest disable must name the engine in human words (zh-CN.ts:5846:
            # e.g. "current engine (Gatling) does not support ... switch perf.default-engine")
            expect(opt.first).to_contain_text("不支持", timeout=10000)
            expect(opt.first).to_contain_text(engine.capitalize().replace("K6", "k6"),
                                              timeout=10000)

    # choose the supported default and carry it through the save round-trip
    chosen = policy["defaultMode"] if policy["defaultMode"] in supported else sorted(supported)[0]
    page.locator(f'[data-test="pw-alloc-option"]:has-text("{LABELS[chosen]}")') \
        .first.click()
    expect(page.locator(f'[data-test="pw-alloc-option"]:has-text("{LABELS[chosen]}") input')
           .first).to_be_checked(timeout=5000)

    # step 1 -> 2: the target step passes validation into the pressure model step
    page.locator('[data-test="pw-next"]').click()

    # steps 2-4 ride the defaults (the gate verdict never blocks saving)
    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-sla"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-gate"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()

    # step 5: confirm and submit
    expect(page.locator('[data-test="pw-step-confirm"]')).to_be_visible()
    page.locator('[data-test="pw-submit"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=15000)
    row = page.locator('[data-test="perf-scenario-table"] tr', has_text=scenario_name)
    expect(row.first).to_be_visible(timeout=15000)

    # iron law 4: after reload, edit back-fill carries dataset + strategy keys
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)
    edit_row = page.locator('[data-test="perf-scenario-table"] tr', has_text=scenario_name)
    expect(edit_row.first).to_be_visible(timeout=15000)
    edit_row.first.locator('[data-test="perf-row-edit"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    expect(page.locator('[data-test="pw-name"]')).to_have_value(scenario_name, timeout=10000)
    expect(page.locator('[data-test="pw-dataset-select"]')).to_contain_text(dataset_name,
                                                                            timeout=15000)
    picked = page.locator(f'[data-test="pw-alloc-option"]:has-text("{LABELS[chosen]}")')
    expect(picked.first.locator("input")).to_be_checked(timeout=10000)
    page.keyboard.press("Escape")
    expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)

    # purge teardown owns the cascade; assert the rows exist before teardown
    cnt = _ok(api_client.get(f"/perf/scenarios?projectId={pid}"), "场景列表")
    assert any(s["name"] == scenario_name for s in cnt), f"服务端缺场景: {cnt}"

    assert_no_errors(collectors, "T375c wizard dataset upload + honest disable + edit round-trip")
