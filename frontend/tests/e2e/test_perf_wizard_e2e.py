
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
def perf_env(api_client, uniq):
    pass
    pid = _ok(api_client.post("/projects", json={
        "name": f"压测E2E项目{uniq}", "code": f"PU{uniq[:5].upper()}"}), "建项目")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"压测E2E环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083/actuator/health"}), "建环境")
    yield pid, eid, f"压测E2E项目{uniq}"
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _pick_project(page, pid):
    """: page-level project selector removed — the ?projectId deep link lands
 the global header context. Dashboard bounce forces a remount (query-only
 navigation reuses the component, so the deep-link setup would not re-run)."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)


def test_perf_wizard_create_persist_edit_runs(logged_in, collectors, api_client, uniq, perf_env):
    pass
    page = logged_in
    pid, _eid, _proj_name = perf_env
    scenario_name = f"基线恒定压测{uniq}"

    nav_to(page, "压测工作台", "/perf", '[data-test="perf-page"]', submenu="执行测试")

    _pick_project(page, pid)
    # U-9 shipped the three-step guide card (perf-empty-guide) as the honest
    # empty state, replacing the legacy generic empty-state block — the
    # assertion follows the current contract (no blank page is ever legal).
    expect(page.locator('[data-test="perf-scenario-table"], [data-test="perf-empty-guide"]')).to_be_visible(timeout=10000)

    page.locator('[data-test="perf-create-btn"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()

    expect(page.locator('[data-test="pw-step-target"]')).to_be_visible()
    env_sel = page.locator('[data-test="pw-env"]')
    env_sel.click()
    env_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="压测E2E环境")
    expect(env_opt.first).to_be_visible(timeout=10000)
    env_opt.first.click()
    page.locator('[data-test="pw-name"]').fill(scenario_name)
    page.locator('[data-test="pw-step-url"]').fill("http://localhost:8083/actuator/health")
    page.locator('[data-test="pw-next"]').click()

    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible()
    expect(page.locator('[data-test="pw-estimate"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()

    expect(page.locator('[data-test="pw-step-sla"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()

    expect(page.locator('[data-test="pw-step-gate"]')).to_be_visible()
    page.locator('[data-test="pw-precheck"]').click()
    banner = page.locator('[data-test="pw-gate-result"]')
    expect(banner).to_be_visible(timeout=10000)
    assert banner.inner_text().strip(), "预检横幅无正文（闸名/理由缺失）"
    # CF-8 explainable ladder: one traffic-light row per gate door (row count follows the
    # server ladder; no client-side estimate, no assertion on which specific door blocks)
    ladder = page.locator('[data-test="pw-gate-ladder"]')
    expect(ladder).to_be_visible(timeout=10000)
    rows = page.locator('[data-test^="pw-gate-step-"]')
    assert rows.count() >= 4, f"ladder rows missing (expect one row per gate door, got {rows.count()})"
    for i in range(rows.count()):
        assert rows.nth(i).inner_text().strip(), f"ladder row {i} has no text"
    page.locator('[data-test="pw-next"]').click()

    expect(page.locator('[data-test="pw-step-confirm"]')).to_be_visible()
    page.locator('[data-test="pw-submit"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=10000)

    row = page.locator('[data-test="perf-scenario-table"] tr', has_text=scenario_name)
    expect(row.first).to_be_visible(timeout=10000)

    nav_to(page, "压测工作台", "/perf", '[data-test="perf-page"]', submenu="执行测试")
    _pick_project(page, pid)
    expect(page.locator('[data-test="perf-scenario-table"] tr', has_text=scenario_name).first).to_be_visible(timeout=10000)

    rows = _ok(api_client.get(f"/perf/scenarios?projectId={pid}"), "场景列表")
    assert any(s["name"] == scenario_name for s in rows), f"服务端列表缺场景: {rows}"

    page.locator('[data-test="perf-row-edit"]').first.click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    expect(page.locator('[data-test="pw-name"]')).to_have_value(scenario_name, timeout=10000)
    page.keyboard.press("Escape")
    expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)

    page.locator('[data-test="perf-row-runs"]').first.click()
    expect(page.locator('[data-test="perf-runs-drawer"]')).to_be_visible(timeout=10000)
    page.keyboard.press("Escape")

    from conftest import sql_query
    cnt = sql_query(f"SELECT count(*) FROM tp_perf_scenario WHERE project_id={pid} AND name='{scenario_name}'")
    assert cnt == "1", f"DB 场景行数异常: {cnt}"

    assert_no_errors(collectors, "压测工作台五步向导建场景+预检+持久化+编辑回填")


def test_perf_summary_budget_row_is_server_single_source(logged_in, collectors, api_client, uniq, perf_env):
    """ U-5 summary card: the dispatch-budget row shows the server-computed number.

 Cross-check posture: the wizard reads gate.budgetMs from the real
 admin→core precheck response (PerfBudgetPolicy single source; the front end
 never recomputes the hot margin). The same payload is replayed over the API
 in this test, and the page row must equal the server value — so a stale
 client-side formula would fail here. Default wizard pressure is
 CONSTANT_RPS targetRps=50 × durationSeconds=60.
 """
    page = logged_in
    pid, eid, _proj_name = perf_env

    _pick_project(page, pid)
    page.locator('[data-test="perf-create-btn"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()

    expect(page.locator('[data-test="pw-step-target"]')).to_be_visible()
    env_sel = page.locator('[data-test="pw-env"]')
    env_sel.click()
    env_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="压测E2E环境")
    expect(env_opt.first).to_be_visible(timeout=10000)
    env_opt.first.click()
    page.locator('[data-test="pw-name"]').fill(f"摘要卡预算谱{uniq}")
    page.locator('[data-test="pw-step-url"]').fill("http://localhost:8083/actuator/health")
    page.locator('[data-test="pw-next"]').click()

    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-sla"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-gate"]')).to_be_visible()
    page.locator('[data-test="pw-precheck"]').click()
    expect(page.locator('[data-test="pw-gate-result"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="pw-next"]').click()

    # ---- summary card budget row present and honest ----
    expect(page.locator('[data-test="pw-step-confirm"]')).to_be_visible()
    card = page.locator('[data-test="pw-summary-card"]')
    expect(card).to_be_visible()
    assert "派发预算" in card.inner_text(), "摘要卡缺派发预算行（行名应点名服务端单源）"
    budget_row = page.locator('[data-test="pw-sum-budget"]')
    expect(budget_row).to_be_visible()
    row_text = budget_row.inner_text()
    # gate surviving into the confirm step must feed the real server number here —
    # the honest fallback note on this path would mean the precheck verdict got
    # dropped between steps again (the defect shape fixed in this segment).
    assert "未申报" not in row_text, f"预算行回落退避文案（预检结论未带入确认步）: {row_text}"

    # ---- server truth: replay the identical precheck payload over the real API ----
    decision = _ok(api_client.post("/perf/gate/precheck", json={
        "projectId": pid, "envId": eid, "concurrency": 50,
        "isolationMode": "SHADOW_TABLE",
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 50, "durationSeconds": 60},
    }), "L3 预检镜像")
    budget_ms = decision.get("budgetMs")
    margin = decision.get("budgetMarginMinutes")
    assert budget_ms and budget_ms > 0, f"服务端未下发预算（旧响应形态?）: {decision}"
    expected_minutes = round(budget_ms / 60000)
    assert f"{expected_minutes} 分钟" in row_text, \
        f"页面预算≠服务端值: row={row_text!r} server={budget_ms}ms→{expected_minutes}min"
    assert f"宽限 {margin} 分钟" in row_text, \
        f"页面宽限分钟≠服务端热更值: row={row_text!r} margin={margin}"

    page.keyboard.press("Escape")
    assert_no_errors(collectors, "T374 U-5 摘要卡派发预算服务端单源")


# ---------- knife b3 tail: the four new pressure models round-trip ----------

def _fill_num(page, test_id, value):
    """el-input-number commits on Enter (typing alone is not the model value)."""
    box = page.locator(f'[data-test="{test_id}"] input')
    box.click()
    box.fill(str(value))
    box.press("Enter")


def _walk_to_submit(page, scenario_name, tune_pressure):
    """Drive the five-step wizard to submit with a per-model pressure-step callback."""
    page.locator('[data-test="perf-create-btn"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    expect(page.locator('[data-test="pw-step-target"]')).to_be_visible()
    env_sel = page.locator('[data-test="pw-env"]')
    env_sel.click()
    env_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="压测E2E环境")
    expect(env_opt.first).to_be_visible(timeout=10000)
    env_opt.first.click()
    page.locator('[data-test="pw-name"]').fill(scenario_name)
    page.locator('[data-test="pw-step-url"]').fill("http://localhost:8083/actuator/health")
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible()
    tune_pressure(page)
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-sla"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-gate"]')).to_be_visible()
    page.locator('[data-test="pw-precheck"]').click()
    expect(page.locator('[data-test="pw-gate-result"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-confirm"]')).to_be_visible()
    page.locator('[data-test="pw-submit"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=10000)


def _db_config(_api, pid, name):
    """Server truth for one scenario row: (pressure_model, pressure_config keys)."""
    import json

    from conftest import sql_query
    row = sql_query(
        f"SELECT pressure_model || '|' || pressure_config::text FROM tp_perf_scenario "
        f"WHERE project_id={pid} AND name='{name}'")
    assert row, f"DB 缺场景行: {name}"
    model, cfg_raw = row.split("|", 1)
    return model, set(json.loads(cfg_raw).keys()), json.loads(cfg_raw)


@pytest.mark.timeout(300)
def test_perf_pressure_models_four_new_shapes_save_e2e(logged_in, collectors, api_client, uniq, perf_env):
    """ knife b3: FIXED_VU/LADDER/BUSINESS_CURVE/SOAK save through the real wizard.

 Each shape saves one scenario via the browser and the DB row is asserted
 against the server REQUIRED_KEYS shape (PerfScenarioServiceImpl): exact key
 sets, no stray zeros. The curve shape is the worst scale: all 24 hour weights
 filled, then reopened in edit to prove openWith rides every key back.
 FIXED_VU loops-only proves the honest estimate line (no wall-clock claim).
 """
    page = logged_in
    pid, _eid, _proj_name = perf_env
    from conftest import sql_query

    _pick_project(page, pid)
    # U-9 shipped the three-step guide card (perf-empty-guide) as the honest
    # empty state, replacing the legacy generic empty-state block — the
    # assertion follows the current contract (no blank page is ever legal).
    expect(page.locator('[data-test="perf-scenario-table"], [data-test="perf-empty-guide"]')).to_be_visible(timeout=10000)

    # ---- FIXED_VU loops-only: vu + loops only, duration zeroed (key set must drop it) ----
    fv_name = f"闭环池按循环{uniq}"
    _walk_to_submit(page, fv_name, lambda p: (
        p.locator('[data-test="pw-model-fixed-vu"]').click(),
        _fill_num(p, "pw-loops", 20),
        _fill_num(p, "pw-fv-duration", 0),
    ))
    # loops-only must land in the DB without a durationSeconds key at all
    model, keys, cfg = _db_config(api_client, pid, fv_name)
    assert model == "FIXED_VU" and keys == {"vu", "loops"}, f"FIXED_VU 键谱失真: {model} {keys}"
    assert cfg["loops"] == 20 and cfg["vu"] > 0, f"FIXED_VU 值失真: {cfg}"

    # ---- LADDER: four required keys, defaults all positive ----
    ld_name = f"阶梯寻量{uniq}"
    _walk_to_submit(page, ld_name, lambda p: p.locator('[data-test="pw-model-ladder"]').click())
    model, keys, cfg = _db_config(api_client, pid, ld_name)
    assert model == "LADDER" and keys == {"startRps", "stepRps", "stepSeconds", "steps"}, \
        f"LADDER 键谱失真: {model} {keys}"

    # ---- SOAK: targetRps + durationHours ----
    sk_name = f"长时浸泡{uniq}"
    _walk_to_submit(page, sk_name, lambda p: p.locator('[data-test="pw-model-soak"]').click())
    model, keys, _cfg = _db_config(api_client, pid, sk_name)
    assert model == "SOAK" and keys == {"targetRps", "durationHours"}, f"SOAK 键谱失真: {model} {keys}"

    # ---- BUSINESS_CURVE worst scale: expand, fill all 24 hour weights ----
    bc_name = f"业务曲线全天{uniq}"

    def _tune_curve(p):
        p.locator('[data-test="pw-model-curve"]').click()
        # collapsed by default (worst-scale iron law) — 24 controls stay hidden
        assert p.locator('[data-test="pw-curve-grid"]').is_visible() is False, "curve grid 默认应收起"
        p.locator('[data-test="pw-curve-toggle"]').click()
        expect(p.locator('[data-test="pw-curve-grid"]')).to_be_visible()
        for h in range(24):
            _fill_num(p, f"pw-curve-h{h}", h + 1)
        # SE-2: filled hours stay visible as chips even before collapsing again
        chips = p.locator('[data-test="pw-curve-chips"]')
        expect(chips).to_be_visible()
        assert "已配置" in chips.inner_text(), f"标签文案缺时段点名: {chips.inner_text()}"

    _walk_to_submit(page, bc_name, _tune_curve)
    model, keys, cfg = _db_config(api_client, pid, bc_name)
    expected_keys = {"peakRps", "durationSeconds"} | {f"curve{h}" for h in range(24)}
    assert model == "BUSINESS_CURVE" and keys == expected_keys, \
        f"BUSINESS_CURVE 键谱失真（应 26 键）: {sorted(keys)}"
    assert all(cfg[f"curve{h}"] == h + 1 for h in range(24)), f"curve 值失真: {cfg}"

    # ---- reload + edit: openWith rides all 24 weights back (reload-persistence rule) ----
    _pick_project(page, pid)
    row = page.locator('[data-test="perf-scenario-table"] tr', has_text=bc_name)
    expect(row.first).to_be_visible(timeout=10000)
    row.first.locator('[data-test="perf-row-edit"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()  # env/name/steps pre-filled on edit
    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible()
    checked = page.locator('[data-test="pw-model-curve"] input:checked')
    assert checked.count() == 1, "编辑回显未选中 BUSINESS_CURVE 形态"
    chips = page.locator('[data-test="pw-curve-chips"]')
    expect(chips).to_be_visible()
    chip_text = chips.inner_text()
    assert "已配置" in chip_text and "0" in chip_text and "23" in chip_text, \
        f"回显标签缺首末时段点名: {chip_text}"
    page.locator('[data-test="pw-curve-toggle"]').click()
    expect(page.locator('[data-test="pw-curve-grid"]')).to_be_visible()
    expect(page.locator('[data-test="pw-curve-h0"] input')).to_have_value("1")
    expect(page.locator('[data-test="pw-curve-h23"] input')).to_have_value("24")
    page.keyboard.press("Escape")
    expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)

    # ---- FIXED_VU loops-only estimate honesty re-check on the saved row edit ----
    page.locator('[data-test="perf-scenario-table"] tr', has_text=fv_name).first \
        .locator('[data-test="perf-row-edit"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    est_text = page.locator('[data-test="pw-estimate"]').inner_text()
    assert "循环" in est_text and "20" in est_text, f"loops-only 预计行未诚实点名: {est_text}"
    page.keyboard.press("Escape")

    # tail count: exactly the four saved shapes ride the fixture project
    cnt = sql_query(f"SELECT count(*) FROM tp_perf_scenario WHERE project_id={pid}")
    assert cnt == "4", f"四形态保存后行数异常: {cnt}"

    assert_no_errors(collectors, "T377 四新形态保存谱+曲线 24 键回显")


def test_perf_purge_cleans_perf_tables(api_client, uniq):
    pass
    pid = _ok(api_client.post("/projects", json={
        "name": f"压测清理E2E{uniq}", "code": f"PC{uniq[:5].upper()}"}), "建项目")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"清理环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8083/actuator/health"}), "建环境")
    sid = _ok(api_client.post("/perf/scenarios", json={
        "projectId": pid, "name": f"清理场景{uniq}", "envId": eid,
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 10, "durationSeconds": 30},
        "loginStrategy": "PER_VUSER", "driverLimit": None,
        "steps": [{"action": "http.request", "method": "GET",
                   "url": "http://localhost:8083/actuator/health"}],
        "slaJson": None}), "建场景")
    wid = _ok(api_client.post("/perf/whitelist", json={
        "projectId": pid, "envId": eid, "maxConcurrency": 50,
        "allowedModes": ["SHADOW_TABLE"]}), "建白名单")

    r = api_client.delete(f"/projects/{pid}/purge?force=true")
    _ok(r, "项目 purge")

    from conftest import sql_query
    for table, col in (("tp_perf_scenario", "project_id"), ("tp_perf_run", "project_id"),
                       ("tp_perf_metric", "project_id"), ("tp_perf_sample", "project_id"),
                       ("tp_perf_whitelist", "project_id"), ("tp_perf_approval", "project_id")):
        left = sql_query(f"SELECT count(*) FROM {table} WHERE {col}={pid}")
        assert left == "0", f"purge 后 {table} 残留 {left} 行（pid={pid} sid={sid} wid={wid}）"


def test_perf_wizard_engine_option_e2e(logged_in, collectors, api_client, uniq, perf_env):
    pass
    page = logged_in
    pid, _eid, _proj_name = perf_env
    scenario_name = f"引擎钉版压测{uniq}"

    nav_to(page, "压测工作台", "/perf", '[data-test="perf-page"]', submenu="执行测试")
    _pick_project(page, pid)
    page.locator('[data-test="perf-create-btn"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()

    page.locator('[data-test="pw-env"]').click()
    env_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="压测E2E环境")
    expect(env_opt.first).to_be_visible(timeout=10000)
    env_opt.first.click()
    page.locator('[data-test="pw-name"]').fill(scenario_name)
    page.locator('[data-test="pw-step-url"]').fill("http://localhost:8083/actuator/health")
    page.locator('[data-test="pw-next"]').click()

    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible()
    eng = page.locator('[data-test="pw-engine"]')
    eng.click()
    opts = page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
    expect(opts).to_have_count(3, timeout=10000)
    texts = " ".join(opts.nth(i).inner_text() for i in range(3)).lower()
    assert "jmeter" in texts and "k6" in texts and "gatling" in texts, f"目录选项值域异常: {texts}"
    page.keyboard.press("Escape")

    eng.click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="JMeter").first.click()
    caveat = page.locator('[data-test="pw-engine-caveat"]')
    expect(caveat).to_be_visible(timeout=5000)
    assert "Precise Throughput Timer" in caveat.inner_text(), "JMETER 披露口径未原文直显"
    eng.click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="k6").first.click()
    expect(page.locator('[data-test="pw-engine-caveat"]')).to_have_count(0)
    eng.click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="JMeter").first.click()
    expect(page.locator('[data-test="pw-engine-caveat"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-sla"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-gate"]')).to_be_visible()
    page.locator('[data-test="pw-precheck"]').click()
    expect(page.locator('[data-test="pw-gate-result"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-confirm"]')).to_be_visible()
    page.locator('[data-test="pw-submit"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=10000)

    from conftest import sql_query
    eng_db = sql_query(f"SELECT engine FROM tp_perf_scenario WHERE project_id={pid} AND name='{scenario_name}'")
    assert eng_db == "JMETER", f"engine 落库异常: {eng_db}"

    nav_to(page, "压测工作台", "/perf", '[data-test="perf-page"]', submenu="执行测试")
    _pick_project(page, pid)
    row = page.locator('[data-test="perf-scenario-table"] tr', has_text=scenario_name)
    expect(row.first).to_be_visible(timeout=10000)
    row.first.locator('[data-test="perf-row-edit"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    expect(page.locator('[data-test="pw-name"]')).to_have_value(scenario_name, timeout=10000)
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-engine"]')).to_contain_text("JMeter", timeout=5000)
    expect(page.locator('[data-test="pw-engine-caveat"]')).to_be_visible()

    page.locator('[data-test="pw-engine"]').hover()
    clear = page.locator('[data-test="pw-engine"] .el-select__clear')
    expect(clear).to_be_visible(timeout=5000)
    clear.click()
    expect(page.locator('[data-test="pw-engine-caveat"]')).to_have_count(0)
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-sla"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-gate"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-confirm"]')).to_be_visible()
    page.locator('[data-test="pw-submit"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=10000)
    eng_after = sql_query(f"SELECT COALESCE(engine,'NULL') FROM tp_perf_scenario WHERE project_id={pid} AND name='{scenario_name}'")
    assert eng_after in ("NULL", ""), f"清除未回落默认路由: {eng_after}"

    assert_no_errors(collectors, "向导引擎钉版：目录三选项+caveat 直显+落库+回填+清除回落")


def test_perf_wizard_schedule_section_e2e(logged_in, collectors, api_client, uniq, perf_env):
    """ B-8 scheduled-firing wizard section: switch gating, server-previewed
 fire times, the client mirror of the enable-without-cron refusal, and the
 schedule triple round-trip through save + reload + edit prefill (iron law 4).
 """
    page = logged_in
    pid, _eid, _proj = perf_env
    name = f"定时触发旅程{uniq}"
    cron = "0 30 2 * * *"

    _pick_project(page, pid)
    page.locator('[data-test="perf-create-btn"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()

    # step 1 target: env + name + url
    page.locator('[data-test="pw-env"]').click()
    opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="压测E2E环境")
    expect(opt.first).to_be_visible(timeout=10000)
    opt.first.click()
    page.locator('[data-test="pw-name"]').fill(name)
    page.locator('[data-test="pw-step-url"]').fill("http://localhost:8083/actuator/health")
    page.locator('[data-test="pw-next"]').click()

    # step 2 pressure model: defaults are legal
    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()

    # step 3 SLA page hosts the scheduled section — switch off = cron input disabled
    expect(page.locator('[data-test="pw-schedule-section"]')).to_be_visible()
    cron_input = page.locator('[data-test="pw-schedule-cron"]')
    expect(cron_input).to_be_disabled()
    expect(page.locator('[data-test="pw-schedule-preview"]')).to_be_disabled()
    expect(page.locator('[data-test="pw-schedule-fires"]')).to_contain_text("预览下次触发")

    # enable the switch -> cron editable, still disabled until a cron is typed
    page.locator('[data-test="pw-schedule-switch"]').click()
    expect(cron_input).to_be_enabled()
    expect(page.locator('[data-test="pw-schedule-preview"]')).to_be_disabled()

    # gate mirror: enabled switch without a cron must refuse the step advance
    # with the human-readable wording (client mirror of the server rule)
    page.locator('[data-test="pw-next"]').click()
    warn = page.locator('.el-message--warning')
    expect(warn).to_be_visible(timeout=5000)
    assert "cron" in warn.inner_text().lower(), f"gate wording not naming cron: {warn.inner_text()}"
    expect(page.locator('[data-test="pw-step-sla"]')).to_be_visible()

    # fill a valid cron and preview through the real admin->core door
    cron_input.fill(cron)
    expect(page.locator('[data-test="pw-schedule-preview"]')).to_be_enabled()
    page.locator('[data-test="pw-schedule-preview"]').click()
    fires = page.locator('[data-test="pw-schedule-fires"] li')
    expect(fires).to_have_count(3, timeout=10000)
    for i in range(3):
        assert fires.nth(i).inner_text().strip(), f"fire row {i} empty"

    # pin the scheduled env, then save
    page.locator('[data-test="pw-schedule-env"]').click()
    env_opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="压测E2E环境")
    expect(env_opt.first).to_be_visible(timeout=10000)
    env_opt.first.click()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-gate"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-confirm"]')).to_be_visible()
    page.locator('[data-test="pw-submit"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=10000)

    # server-side persistence of the schedule triple (iron law 4: re-read the source)
    from conftest import sql_query
    row = sql_query(
        f"SELECT schedule_cron, schedule_enabled, schedule_env_id FROM tp_perf_scenario "
        f"WHERE project_id={pid} AND name='{name}'")
    cols = row.split("|")
    assert cols[0] == cron and cols[1] == "1" and cols[2].strip(), f"schedule columns not persisted: {row}"

    # reload + edit prefill: switch on, cron and env echoed back
    nav_to(page, "压测工作台", "/perf", '[data-test="perf-page"]', submenu="执行测试")
    _pick_project(page, pid)
    edit_row = page.locator('[data-test="perf-scenario-table"] tr', has_text=name)
    expect(edit_row.first).to_be_visible(timeout=10000)
    edit_row.first.locator('[data-test="perf-row-edit"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    # walk to the scheduled step and verify the round-trip echoes
    page.locator('[data-test="pw-next"]').click()
    page.locator('[data-test="pw-next"]').click()
    expect(cron_input).to_have_value(cron, timeout=5000)
    import re as _re
    switch_cls = page.locator('[data-test="pw-schedule-switch"]').get_attribute("class") or ""
    assert _re.search(r"is-checked|checked", switch_cls), f"schedule switch not on after round-trip: {switch_cls}"
    page.keyboard.press("Escape")
    expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)

    assert_no_errors(collectors, "T378 B-8 向导定时执行区（开关门/预览/回弹/持久回显）")
