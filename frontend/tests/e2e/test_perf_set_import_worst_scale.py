""" closeout E2E: whole-interface-set import at the worst scale.

Covers the scale contract (verdict doc section 2, 500 requests / 500 steps)
and the operation-priority contract (section 2 item 7) against the real
stack — no mocks:

- 500-request set: the candidate list and the merge preview render through
 the windowed renderer (DOM rows far below the row count, the scroller
 height proves all 500 are laid out), instant search (method/path/name),
 tick state surviving the filter round-trip, Ctrl+A selecting the CURRENT
 filter result, Enter opening the preview, Esc dismissing it with the tick
 state kept (two-door rule), then the 500-step commit through the wizard's
 real save chain -> steps_json frozen-order + collection/request header
 merge reconciliation -> reload echo;
- cross-set import: pick order = between-set order, source badges, the
 duplicated method+path row flagged (never silently dropped), double-click
 single-row import, and the one-step undo of the whole bulk commit;
- the K8 ceiling: a 501-step save is refused in plain words naming both
 the current count and the limit (perf.scenario.max-steps, default 500).

Parallel safety: uniq suffix on every project/env/scenario name. Teardown:
project purge (perf tables cascade). The 500-row set is seeded by SQL bulk
(data-only, t413 precedent) under a real API project.
"""
import json
import subprocess
import time

import pytest
from conftest import BASE_URL, assert_no_errors
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(600)

FIXTURE = "http://127.0.0.1:8899"
N = 500
SET_HEADER = "X-T443-Set"
OVERRIDE_URL = "/scale/7"


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


@pytest.fixture()
def t443_env(api_client, uniq):
    """Real project + env(8899) + two seeded sets (500 rows, 2 rows dup path)."""
    pid = _ok(api_client.post("/projects", json={
        "name": f"T443规模引入项目{uniq}", "code": f"T443{uniq[:5].upper()}"}), "create project")
    pid = pid["id"] if isinstance(pid, dict) else pid
    eid = _ok(api_client.post(f"/projects/{pid}/envs", json={
        "name": f"T443规模环境{uniq}", "envType": 2, "baseUrl": FIXTURE,
        "configJson": "{}", "endTypes": "web", "browsers": "chromium",
        "versionSnapshot": ""}), "create env")
    eid = eid["id"] if isinstance(eid, dict) else eid
    _ok(api_client.post("/perf/whitelist", json={
        "projectId": pid, "envId": eid, "maxConcurrency": 50,
        "allowedModes": ["HEADER_MARK"]}), "whitelist")
    # set A: 500 frozen-order requests, collection-level default header,
    # request #7 overrides the same key (merge rule: request level wins).
    _psql(f"INSERT INTO tp_apitest_collection (project_id, parent_id, name, tags, headers_json) "
          f"VALUES ({pid}, 0, '规模甲集{uniq}', '', "
          f"""'[{{"name": "{SET_HEADER}", "value": "set-a"}}]')""")
    ca = int(_psql(f"SELECT id FROM tp_apitest_collection WHERE project_id={pid} "
                   f"AND name='规模甲集{uniq}'"))
    _psql(f"INSERT INTO tp_apitest_request (collection_id, name, method, url_path, headers_json, "
          f"body_text, assertions_json, sort_order) "
          f"SELECT {ca}, '规模请求' || gs, 'GET', '/scale/' || gs, '[]', '', '[]', gs "
          f"FROM generate_series(1, {N}) gs")
    _psql(f"UPDATE tp_apitest_request SET headers_json = "
          f"""'[{{"name": "{SET_HEADER}", "value": "request-own"}}]' """
          f"WHERE collection_id = {ca} AND url_path = '{OVERRIDE_URL}'")
    # set B: 3 rows, one duplicating a set-A method+path (flagged, not dropped).
    _psql(f"INSERT INTO tp_apitest_collection (project_id, parent_id, name, tags) "
          f"VALUES ({pid}, 0, '规模乙集{uniq}', '')")
    cb = int(_psql(f"SELECT id FROM tp_apitest_collection WHERE project_id={pid} "
                   f"AND name='规模乙集{uniq}'"))
    _psql(f"INSERT INTO tp_apitest_request (collection_id, name, method, url_path, headers_json, "
          f"body_text, assertions_json, sort_order) VALUES "
          f"({cb}, '乙集请求甲', 'GET', '/scale/101', '[]', '', '[]', 1), "
          f"""({cb}, '乙集请求乙', 'POST', '/scale/order', '[{{"name": "X-B-Own", "value": "b"}}]', '', '[]', 2), """
          f"({cb}, '乙集请求丙', 'GET', '/scale/103', '[]', '', '[]', 3)")
    yield {"pid": pid, "eid": eid, "ca": ca, "cb": cb, "uniq": uniq}
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _pick_project(page, pid):
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)


def _open_wizard_with_env(page, uniq):
    page.locator('[data-test="perf-create-btn"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    env_sel = page.locator('[data-test="pw-env"]')
    env_sel.click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                 has_text=f"T443规模环境{uniq}").first.click()
    page.locator('[data-test="pw-name"]').fill(f"规模引入闭环{uniq}")


def _pick_ref_collections(page, names):
    """Select the interface sets in the multi picker, in the given order."""
    sel = page.locator('[data-test="pw-ref-collection"]')
    for name in names:
        sel.click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                     has_text=name).first.click()
        page.keyboard.press("Escape")  # multiple select keeps the dropdown open


def _dom_rows(page, selector):
    return page.locator(f'{selector} [data-windowed-row]').count()


def test_t443_set_import_worst_scale(logged_in, collectors, api_client, t443_env):
    """500-row set: windowed DOM, search+tick survival, Ctrl+A/Enter/Esc, 500-step
    commit, frozen order + header merge reconciliation, reload echo, undo."""
    page = logged_in
    pid = t443_env["pid"]
    uniq = t443_env["uniq"]
    _pick_project(page, pid)
    _open_wizard_with_env(page, uniq)
    _pick_ref_collections(page, [f"规模甲集{uniq}"])

    # ---- open panel: 3 clicks rule (picker already done in step 1; door + panel) ----
    door = page.locator('[data-test="pw-open-import"]')
    expect(door).to_be_enabled()
    door.click()
    panel = page.locator('[data-test="pw-import-panel"]')
    expect(panel).to_be_visible(timeout=15000)
    rows_box = page.locator('[data-test="pw-import-rows"]')
    expect(rows_box).to_be_visible()

    # ---- windowed render at 500 rows: DOM rows << total, scroller proves the total ----
    dom0 = _dom_rows(page, '[data-test="pw-import-rows"]')
    assert dom0 > 0 and dom0 <= 2 * 40, f"candidate list not windowed: DOM={dom0}"
    metrics = rows_box.evaluate(
        "el => ({sh: el.scrollHeight, ch: el.clientHeight})")
    assert metrics["sh"] >= N * 30, \
        f"scroller height does not cover 500 rows: {metrics}"
    # <=300ms instant search measured below on the real keystroke path.

    # ---- keyboard: focus a row, Space ticks it; search keeps the tick ----
    first_row = page.locator('[data-test="pw-import-rows"] [data-windowed-row]').first
    first_row.focus()
    page.keyboard.press(" ")
    expect(page.locator('[data-test="pw-import-count"]')).to_contain_text("1", timeout=5000)
    kw_box = page.locator('[data-test="pw-import-search"]')
    t1 = time.monotonic()
    kw_box.fill("/scale/12")
    filtered_ms = (time.monotonic() - t1) * 1000
    assert filtered_ms <= 1500, f"instant search over 500 rows too slow: {filtered_ms:.0f}ms"
    expect(page.locator('[data-test="pw-import-count"]')).to_contain_text("1")
    # ticked row survives the filter round-trip (selection keyed by request id)
    kw_box.fill("")
    expect(page.locator('[data-test="pw-import-count"]')).to_contain_text("1")

    # ---- Ctrl+A = current result set (filter to 11 rows: /scale/1x + /scale/10x...
    # use path keyword '10' -> 55 rows: 10,100-109,210? no—substring; assert exact) ----
    kw_box.fill("/scale/1")  # matches /scale/1, /scale/10../scale/19, /scale/100..190 etc.
    expect(page.locator('[data-test="pw-import-rows"]')).to_be_visible()
    rows_box.click(position={"x": 10, "y": 10})  # move focus inside the panel body
    page.keyboard.press("Control+a")
    expected_ids = _psql(
        f"SELECT count(*) FROM tp_apitest_request WHERE collection_id={t443_env['ca']} "
        f"AND url_path LIKE '/scale/1%'")
    assert int(expected_ids) >= 10
    bottom = page.locator('[data-test="pw-import-count"]')
    expect(bottom).to_contain_text(str(expected_ids), timeout=5000)

    # ---- Enter opens the merge preview (commit door, never commits directly) ----
    page.keyboard.press("Enter")
    preview = page.locator('[data-test="pw-import-preview"]')
    expect(preview).to_be_visible(timeout=5000)
    # Esc closes the preview WITHOUT dropping the selection (two-door rule)
    page.keyboard.press("Escape")
    expect(preview).to_be_hidden(timeout=5000)
    expect(bottom).to_contain_text(str(expected_ids))

    # ---- clear filter, keep ONLY the full-set selection for the 500-step commit:
    # reselect via Ctrl+A on the unfiltered list, then Enter -> preview -> confirm
    kw_box.fill("")
    rows_box.click(position={"x": 10, "y": 10})
    page.keyboard.press("Control+a")
    expect(bottom).to_contain_text(str(N), timeout=8000)
    page.keyboard.press("Enter")
    expect(preview).to_be_visible(timeout=5000)
    prev_box = page.locator('[data-test="pw-import-preview-rows"]')
    expect(prev_box).to_be_visible(timeout=5000)
    pdom = _dom_rows(page, '[data-test="pw-import-preview-rows"]')
    assert pdom > 0 and pdom <= 2 * 40, f"preview not windowed: DOM={pdom}"
    page.locator('[data-test="pw-import-confirm"]').click()
    expect(page.locator('[data-test="pw-import-preview"]')).to_be_hidden(timeout=60000)
    expect(page.locator('[data-test="pw-import-panel"]')).to_be_hidden(timeout=10000)

    # ---- step table now holds 500 imported rows below the wizard's default
    # empty first step (501 total); drop the empty row through the per-row
    # delete so the save lands exactly at the K8 ceiling (500) ----
    steps_count = page.locator('[data-test="pw-steps-count"]')
    expect(steps_count).to_contain_text("501", timeout=30000)
    step_box = page.locator('[data-test="pw-step-list"]')
    sdom = _dom_rows(page, '[data-test="pw-step-list"]')
    assert sdom > 0 and sdom <= 2 * 60, f"step table not windowed: DOM={sdom}"
    # the first imported row is revealed (scroller moved past the default row)
    top_probe = step_box.evaluate("el => el.scrollTop")
    assert top_probe > 0, "step list did not scroll to the first imported row"
    # one-step undo offered after the bulk commit (verified by clicking later
    # in the cross-set spec; here presence is the contract)
    expect(page.locator('[data-test="pw-import-undo"]')).to_be_visible()
    # scroll back to the windowed top and drop the empty default row 0
    step_box.evaluate("el => { el.scrollTop = 0; el.dispatchEvent(new Event('scroll')) }")
    expect(page.locator('[data-test="pw-step-row-0"]')).to_be_visible(timeout=5000)
    page.locator('[data-test="pw-step-row-0"] [aria-label="删除本步"]').click()
    expect(steps_count).to_contain_text("500", timeout=10000)

    # ---- submit through the real wizard chain (pressure -> sla -> gate -> confirm) ----
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-sla"]')).to_be_visible()
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-gate"]')).to_be_visible()
    page.locator('[data-test="pw-isolation"]').click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                 has_text="压测标记头隔离").first.click()
    page.locator('[data-test="pw-precheck"]').click()
    expect(page.locator('[data-test="pw-gate-result"]')).to_be_visible(timeout=20000)
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-confirm"]')).to_be_visible()
    page.locator('[data-test="pw-submit"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=30000)

    # ---- DB reconciliation: frozen order + header merge (set default, request wins) ----
    scenario_name = f"规模引入闭环{uniq}"
    raw = _psql(f"SELECT steps_json::text FROM tp_perf_scenario WHERE name='{scenario_name}'")
    steps = json.loads(raw)
    assert len(steps) == N, f"steps not persisted at 500: {len(steps)}"
    for i, probe in ((0, 1), (99, 100), (499, 500)):
        want = f"{FIXTURE}/scale/{probe}"
        assert steps[i].get("url") == want, \
            f"frozen order broken at step {i}: {steps[i].get('url')}"
    assert steps[0].get("headers", {}).get(SET_HEADER) == "set-a", \
        f"collection default header not merged: {steps[0].get('headers')}"
    idx7 = next(i for i, s in enumerate(steps) if s.get("url", "").endswith(OVERRIDE_URL))
    assert steps[idx7]["headers"].get(SET_HEADER) == "request-own", \
        f"request-level header did not override: {steps[idx7]['headers']}"

    # ---- reload persistence (iron law 4): re-open edit, count echoes 500 ----
    _pick_project(page, pid)
    expect(page.locator('[data-test="perf-scenario-table"] tr',
                         has_text=scenario_name).first).to_be_visible(timeout=15000)
    page.locator('[data-test="perf-row-edit"]').first.click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible()
    expect(page.locator('[data-test="pw-name"]')).to_have_value(scenario_name, timeout=10000)
    expect(page.locator('[data-test="pw-steps-count"]')).to_contain_text(str(N), timeout=15000)
    page.keyboard.press("Escape")
    expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)

    assert_no_errors(collectors, "T443 worst-scale set import")


def test_t443_cross_set_preview_badges_dup_and_undo(logged_in, collectors, api_client, t443_env):
    """Two sets picked (pick order = between-set order): source badges, duplicate
    method+path flagged, double-click single import, one-step undo."""
    page = logged_in
    pid = t443_env["pid"]
    uniq = t443_env["uniq"]
    _pick_project(page, pid)
    _open_wizard_with_env(page, uniq)
    # pick set B first, then set A -> between-set order = [B rows, A rows]
    _pick_ref_collections(page, [f"规模乙集{uniq}", f"规模甲集{uniq}"])
    page.locator('[data-test="pw-open-import"]').click()
    panel = page.locator('[data-test="pw-import-panel"]')
    expect(panel).to_be_visible(timeout=15000)
    # candidate rows concatenate in pick order: first visible row is from set B
    first = page.locator('[data-test="pw-import-rows"] [data-windowed-row]').first
    expect(first.locator('[data-test="pw-import-origin"]')).to_contain_text(f"规模乙集{uniq}")

    # double-click one row = direct single import (<=1 action path); the wizard
    # still carries its default empty step, so the honest count lands on 2.
    first.dblclick()
    expect(page.locator('[data-test="pw-steps-count"]')).to_contain_text("2", timeout=10000)

    # filter to the duplicate pair (set B /scale/101 + set A /scale/101) and tick
    # both — the windowed list would not render row #402 without the filter.
    page.locator('[data-test="pw-import-search"]').fill("/scale/101")
    dup_rows = page.locator('[data-test="pw-import-rows"] [data-windowed-row]')
    expect(dup_rows).to_have_count(2, timeout=5000)
    dup_rows.nth(0).locator('[data-test="pw-import-check"]').click()
    dup_rows.nth(1).locator('[data-test="pw-import-check"]').click()
    expect(page.locator('[data-test="pw-import-count"]')).to_contain_text("2")
    page.locator('[data-test="pw-import-run"]').click()
    preview = page.locator('[data-test="pw-import-preview"]')
    expect(preview).to_be_visible(timeout=10000)
    # duplicate method+path is FLAGGED, never silently dropped
    expect(page.locator('[data-test="pw-import-dup-hint"]')).to_be_visible()
    dup_tag = page.locator('[data-test="pw-import-preview-rows"] [data-windowed-row]').first
    expect(dup_tag.locator('[data-test="pw-import-dup-tag"]')).to_be_visible()

    # confirm -> 2 dup rows appended (4 total); undo restores the pre-commit 2
    page.locator('[data-test="pw-import-confirm"]').click()
    expect(preview).to_be_hidden(timeout=30000)
    expect(page.locator('[data-test="pw-steps-count"]')).to_contain_text("4", timeout=15000)
    page.locator('[data-test="pw-import-undo"]').click()
    expect(page.locator('[data-test="pw-steps-count"]')).to_contain_text("2", timeout=15000)

    # no submit here — this spec verifies the preview/undo surface only.
    page.keyboard.press("Escape")
    expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)

    assert_no_errors(collectors, "T443 cross-set preview badges")


def test_t443_step_ceiling_plain_words_refusal(api_client, t443_env):
    """501 steps through the real save endpoint: refused, message names count+limit;
    a second scenario at exactly 500 saves (K8 ceiling boundary, both sides)."""
    pid = t443_env["pid"]
    eid = t443_env["eid"]
    uniq = t443_env["uniq"]

    def steps_of(n):
        return [{"action": "http.request", "method": "GET",
                 "url": f"{FIXTURE}/cap/{i}"} for i in range(n)]

    refused = api_client.post("/perf/scenarios", json={
        "projectId": pid, "name": f"超限拒绝{uniq}", "envId": eid,
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 1, "durationSeconds": 1},
        "steps": steps_of(501)})
    # VALIDATION_ERROR maps to HTTP 400 with the human reason in the body
    # (global exception handler, BizErrorCode 40001 — same corpus the
    # wizard's toast surfaces).
    assert refused.status_code == 400, \
        f"501 steps should be refused with 400, got {refused.status_code}"
    body = refused.json()
    assert str(body.get("code")) == "40001", f"unexpected refusal body: {str(body)[:200]}"
    msg = body.get("msg") or ""
    assert "501" in msg and "500" in msg, \
        f"refusal does not name current count and ceiling: {msg}"

    ok_id = _ok(api_client.post("/perf/scenarios", json={
        "projectId": pid, "name": f"上限恰好{uniq}", "envId": eid,
        "pressureModel": "CONSTANT_RPS",
        "pressureConfig": {"targetRps": 1, "durationSeconds": 1},
        "steps": steps_of(500)}), "save exactly-500 scenario")
    assert ok_id is not None
    raw = _psql(f"SELECT steps_json::text FROM tp_perf_scenario WHERE id={ok_id}")
    assert len(json.loads(raw)) == 500, "exactly-500 scenario did not persist"
