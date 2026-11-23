
import json
import os
import re
import time

import pytest
from conftest import BASE_URL, assert_no_errors, sql_query
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(280)

FIXTURE = "http://127.0.0.1:8899"
HEADER_KEY = "X-T442-E2E"
HEADER_VALUE = "t442-hdr"
SAVE_AS = "t442tok"


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} business failure: {body.get('msg')}"
    return body["data"]


def _config_row(api_client, key):
    rows = _ok(api_client.get("/configs"), "configs list") or []
    return next((r for r in rows if r["configKey"] == key), None)


def _set_config(api_client, key, value):
    row = _config_row(api_client, key)
    assert row is not None, f"config key missing: {key}"
    _ok(api_client.put(f"/configs/{row['id']}", json={
        "configKey": key, "configValue": value,
        "groupName": row.get("groupName") or "系统",
        "valueType": row.get("valueType") or "string",
        "encryptFlag": row.get("encryptFlag", 0),
        "description": row.get("description", "")}), f"set {key}={value}")


@pytest.fixture()
def t442_env(api_client, uniq):
    """Switch the default engine to K6, build project + env(8899) + whitelist."""
    prev = (_config_row(api_client, "perf.default-engine") or {}).get("configValue")
    _set_config(api_client, "perf.default-engine", "K6")
    pid = _ok(api_client.post("/projects", json={
        "name": f"T442 参数槽项目{uniq}", "code": f"TP{uniq[:5].upper()}"}), "create project")
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"T442 参数槽环境{uniq}", "envType": 2, "baseUrl": FIXTURE,
        "configJson": "{}", "endTypes": "web", "browsers": "chromium",
        "versionSnapshot": ""}), "create env")
    _ok(api_client.post("/perf/whitelist", json={
        "projectId": pid, "envId": eid, "maxConcurrency": 50,
        "allowedModes": ["HEADER_MARK"]}), "whitelist")
    state = {"run_id": None}
    yield {"pid": pid, "eid": eid, "uniq": uniq, "state": state}
    rid = state["run_id"]
    if rid:
        api_client.post(f"/perf/runs/{rid}/stop?projectId={pid}")
        sql_query("DELETE FROM tp_exec_task WHERE task_type=5 "
                  f"AND action_json::text LIKE '%\"runId\":{rid}%'")
    api_client.delete(f"/projects/{pid}/purge?force=true")
    if prev:
        _set_config(api_client, "perf.default-engine", prev)


def _pick_project(page, pid):
    """ pattern: deep link lands the global header project context."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)


def _open_step_params(page, i):
    page.locator(f'[data-test="pw-step-params-toggle-{i}"]').click()
    panel = page.locator(f'[data-test="pw-step-params-{i}"]')
    expect(panel).to_be_visible()
    return panel


def _set_method(page, i, method):
    sel = page.locator(f'[data-test="pw-step-row-{i}"] .pw__step-method')
    sel.click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                 has_text=method).first.click()


def test_t442_step_params_fill_echo_and_compiled_slots(
        logged_in, collectors, api_client, t442_env):
    """Fill four slots -> submit -> reload edit echo -> steps_json rows -> dispatched artifact carries them."""
    page = logged_in
    pid = t442_env["pid"]
    uniq = t442_env["uniq"]
    scenario_name = f"参数槽闭环{uniq}"
    body_marker = f"t442body-{uniq}"

    _pick_project(page, pid)
    # U-9 shipped the three-step guide card (perf-empty-guide) as the honest
    # empty state — assertion follows the current contract.
    expect(page.locator('[data-test="perf-scenario-table"], [data-test="perf-empty-guide"]')
           ).to_be_visible(timeout=10000)

    # ---- wizard step 1: env / name / POST step 0 + the four parameter slots ----
    page.locator('[data-test="perf-create-btn"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    # vision rectification B (programmatic gate): the step table heads
    # itself with an honest step-count line, present from the first row on.
    expect(page.locator('[data-test="pw-steps-count"]')).to_contain_text("1")
    env_sel = page.locator('[data-test="pw-env"]')
    env_sel.click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                 has_text="T442 参数槽环境").first.click()
    page.locator('[data-test="pw-name"]').fill(scenario_name)
    _set_method(page, 0, "POST")
    page.locator('[data-test="pw-step-url"]').fill(f"{FIXTURE}/api/login")

    panel0 = _open_step_params(page, 0)
    assert panel0.is_visible()
    # vision rectification A: the toggle label tracks its own state
    # (verb + object when open, not the bare noun the first vision round flagged).
    toggle0 = page.locator('[data-test="pw-step-params-toggle-0"]')
    assert "收起" in toggle0.inner_text(), f"open-state label not state-linked: {toggle0.inner_text()}"
    page.locator('[data-test="pw-params-collapse-all"]').click()
    assert "展开" in toggle0.inner_text(), f"closed-state label not state-linked: {toggle0.inner_text()}"
    page.locator('[data-test="pw-params-expand-all"]').click()
    # headers: add one key/value pair through the draft row
    page.locator('[data-test="pw-param-hkey"]').fill(HEADER_KEY)
    page.locator('[data-test="pw-param-hvalue"]').last.fill(HEADER_VALUE)
    page.locator('[data-test="pw-param-hadd"]').click()
    existing_key = page.locator('[data-test="pw-step-params-0"] .pw__kv-row').first
    expect(existing_key.locator('input').first).to_have_value(HEADER_KEY)
    # body slot (POST enabled)
    page.locator('[data-test="pw-param-body"]').fill('{"user": "loadtest", "note": "' + body_marker + '"}')
    # expect.status slot (el-input-number commits on Enter)
    status_input = page.locator('[data-test="pw-param-status"] input')
    status_input.fill("201")
    status_input.press("Enter")
    # saveAs slot
    page.locator('[data-test="pw-param-saveas"]').fill(SAVE_AS)

    # ---- second row GET: body slot honestly disabled, note names the method (scope rule R25) ----
    page.locator('[data-test="pw-step-add"]').click()
    page.locator('[data-test="pw-step-url"]').nth(1).fill(f"{FIXTURE}/api/order/UNKNOWN")
    _open_step_params(page, 1)
    body1 = page.locator('[data-test="pw-param-body"]').nth(1)
    expect(body1).to_be_disabled()
    assert "GET" in (body1.get_attribute("placeholder") or ""), \
        f"GET disable note does not name the method: {body1.get_attribute('placeholder')}"
    # collapse-all / expand-all batch (scale contract)
    page.locator('[data-test="pw-params-collapse-all"]').click()
    expect(page.locator('[data-test="pw-step-params-0"]')).to_be_hidden()
    page.locator('[data-test="pw-params-expand-all"]').click()
    expect(page.locator('[data-test="pw-step-params-1"]')).to_be_visible()
    page.locator('[data-test="pw-params-collapse-all"]').click()

    # ---- pressure shape -> thresholds -> precheck -> submit (wizard legal defaults) ----
    expect(page.locator('[data-test="pw-step-target"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible()
    page.locator('[data-test="pw-estimate"]').is_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-sla"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-gate"]')).to_be_visible()
    # declare the isolation mode the whitelist actually allows (gate is fail-closed)
    page.locator('[data-test="pw-isolation"]').click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                 has_text="压测标记头隔离").first.click()
    page.locator('[data-test="pw-precheck"]').click()
    expect(page.locator('[data-test="pw-gate-result"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-confirm"]')).to_be_visible()
    page.locator('[data-test="pw-submit"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=10000)

    row = page.locator('[data-test="perf-scenario-table"] tr', has_text=scenario_name)
    expect(row.first).to_be_visible(timeout=10000)

    # ---- DB: steps_json carries the four real slots ----
    stored_raw = sql_query("SELECT steps_json::text FROM tp_perf_scenario "
                           f"WHERE name='{scenario_name}'")
    steps = json.loads(stored_raw)
    s0 = steps[0]
    assert s0["method"] == "POST", f"step0.method={s0.get('method')}"
    assert s0.get("headers", {}).get(HEADER_KEY) == HEADER_VALUE, \
        f"headers not persisted: {s0.get('headers')}"
    assert body_marker in json.dumps(s0.get("body")), f"body not persisted: {s0.get('body')}"
    assert (s0.get("expect") or {}).get("status") == 201, f"expect not persisted: {s0.get('expect')}"
    assert s0.get("saveAs") == SAVE_AS, f"saveAs not persisted: {s0.get('saveAs')}"

    # ---- reload persistence (iron law 4): re-enter page + edit echoes the four slots ----
    _pick_project(page, pid)
    expect(page.locator('[data-test="perf-scenario-table"] tr',
                        has_text=scenario_name).first).to_be_visible(timeout=10000)
    page.locator('[data-test="perf-row-edit"]').first.click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    expect(page.locator('[data-test="pw-name"]')).to_have_value(scenario_name, timeout=10000)
    # hydration resets the fold to collapsed (scale contract) - expand via expand-all
    page.locator('[data-test="pw-params-expand-all"]').click()
    expect(page.locator('[data-test="pw-step-params-0"]')).to_be_visible()
    # both step panels live in the DOM (v-show fold) -> anchor on the first row's slots
    expect(page.locator('[data-test="pw-param-hvalue"]').first).to_have_value(HEADER_VALUE)
    expect(page.locator('[data-test="pw-param-body"]').first).to_have_value(re.compile(re.escape(body_marker)))
    expect(page.locator('[data-test="pw-param-status"] input').first).to_have_value("201")
    expect(page.locator('[data-test="pw-param-saveas"]').first).to_have_value(SAVE_AS)
    page.keyboard.press("Escape")
    expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)

    # ---- run dispatch: the compiled artifact carries the real slots (L4 anchor) ----
    sid = json.loads(sql_query("SELECT json_build_object('id', id) FROM tp_perf_scenario "
                               f"WHERE name='{scenario_name}'"))["id"]
    ra = api_client.post(f"/perf/scenarios/{sid}/runs?projectId={pid}").json()
    assert str(ra.get("code")) == "0", f"launch rejected: {str(ra)[:200]}"
    run_id = (ra.get("data") or {}).get("id") if isinstance(ra.get("data"), dict) else ra.get("data")
    t442_env["state"]["run_id"] = run_id

    artifact_path = None
    like = '%"runId":' + str(run_id) + '%'
    deadline = time.time() + 30
    while time.time() < deadline and not artifact_path:
        raw = sql_query("SELECT action_json::text FROM tp_exec_task WHERE task_type=5 "
                        f"AND action_json::text LIKE '{like}' LIMIT 1")
        if raw:
            artifact_path = json.loads(raw).get("artifact")
        else:
            time.sleep(1)  # dispatch lands asynchronously (non-Playwright wait)
    # Corrected with source (same ruling as tools/verification/l4-perf-fullchain.py A2):
    # since the object-storage artifact segment the dispatch envelope carries a
    # The artifact's real usability (and its compiled content) is proven here by
    # the executor's own door: api /internal grant -> presigned GET -> unzip.
    assert artifact_path and "/perf-artifact/" in artifact_path \
        and artifact_path.endswith(".zip"), \
        f"dispatched artifact key malformed: artifact={artifact_path}"
    import io as _io
    import zipfile as _zip
    import httpx as _httpx
    api_base = os.environ.get("TP_API_BASE", "http://localhost:8082")
    g = _httpx.post(f"{api_base}/internal/perf/artifacts/download-grant",
                    json={"objectKey": artifact_path}, timeout=60)
    grant = (g.json().get("data") or {})
    dl_url = grant.get("downloadUrl")
    assert dl_url, f"grant issued no downloadUrl: {g.text[:200]}"
    resp = _httpx.get(str(dl_url), timeout=120)
    assert resp.status_code == 200, f"artifact download HTTP={resp.status_code}"
    with _zip.ZipFile(_io.BytesIO(resp.content)) as zf:
        js_names = [n for n in zf.namelist() if n.endswith(".js")]
        assert js_names, f"artifact zip has no k6 script: {zf.namelist()}"
        script = zf.read(js_names[0]).decode("utf-8")
    assert HEADER_KEY in script, "compiled artifact lacks the step header key"
    assert body_marker in script, "compiled artifact lacks the step body"
    assert "expectedStatuses: [201]" in script, "compiled artifact lacks expect.status"
    assert SAVE_AS in script, "compiled artifact lacks the saveAs capture"

    assert_no_errors(collectors, "T442 step parameter fill-echo-compile full chain")
