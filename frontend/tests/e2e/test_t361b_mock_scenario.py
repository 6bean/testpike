# -*- coding: utf-8 -*-
"""E2E for the stateful scenario surface .

The scenario drawer and cards moved off the docs page to the standalone
/mock/scenarios surface with an addressable detail page ( information
architecture). This file keeps the original three doors on the new surface:
1. docs page = honest summary bar (counts + a jump to the list, no inline
 graph wall any more); the jump carries the space/project context;
2. scenario create journey — project + published space built through the live
 API, a two-state machine created through the list page's drawer (states +
 initial + transition rows), the row renders with its lane tag and health
 tag, and the detail page draws one SVG graph whose node/edge counts match
 the declaration;
3. reload keeps truth (the machine row lives in the DB — the row and the
 detail graph survive a reload);
4. the core save gate stays the single legality point: a dangling transition
 is pre-warned by the drawer aside and refused in plain words by the server,
 the refusal toast is visible and no row lands.

Rows are torn down for real; assert_no_errors bundles the 4xx/5xx, console
error/warn and browser-issues zero-tolerance gates.
"""
import json

from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, sql_query

SPACES_URL = f"{BASE_URL}/#/mock/spaces"


def _wb(sid, pid, tab):
    """ AN-4: scenario list = the workbench scenarios tab of a space."""
    return f"{SPACES_URL}/{sid}?projectId={pid}&tab={tab}"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={"projectId": pid, "name": name, "spaceKey": key})
    assert r.status_code == 200 and r.json().get("code") == "0", f"space create failed: {r.text[:300]}"
    return r.json()["data"]


def _publish(api, space_id, version):
    r = api.post(
        f"/mock/spaces/{space_id}/transition",
        json={"targetStatus": 1, "expectedVersion": version},
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"publish failed: {r.text[:300]}"


def _open_drawer(page):
    page.locator('[data-test="mock-scenarios-new"]').click()
    drawer = page.locator('[data-test="mock-scenarios-create-drawer"]')
    expect(drawer).to_be_visible(timeout=10000)
    return drawer


def _fill(drawer, test_attr, value):
    # el-input passes data-test down to the native input body — fill directly
    drawer.locator(f'[data-test="{test_attr}"]').fill(value)


def _add_state(drawer, index, name):
    if index > 0:
        drawer.locator('[data-test="mock-scenarios-f-add-state"]').click()
    _fill(drawer, f"mock-scenarios-f-state-{index}", name)


def _pick_edge_from(page, drawer, value):
    # el-select: data-test reaches the control body; the option list rides a
    # body-teleported popper, so pick through the page locator
    drawer.locator('[data-test="mock-scenarios-f-edge-from"]').first.click()
    option = page.locator(".el-select-dropdown__item").filter(has_text=value).first
    option.click()


def test_t361b_scenario_create_reload_and_refusal(logged_in, collectors, api_client, uniq):
    """Summary-bar jump -> drawer create -> detail graph -> reload persists -> dangling refused by core."""
    page = logged_in
    pid = _mk_project(api_client, f"ScenE2E {uniq}", f"sc{uniq}")
    # space keys ride the /mocks/ path: lowercase spectrum
    space_key = f"sc{uniq}".lower()
    space_id = _mk_space(api_client, pid, f"scen-space-{uniq}", space_key)
    _publish(api_client, space_id, 0)
    try:
        # --- door 1: docs page keeps only a summary bar with an honest empty
        # state and a jump that carries the space/project context ---
        # AN-4: the docs screen is reached as the workbench docs tab.
        page.goto(_wb(space_id, pid, "docs"), wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="mock-docs-scenario-box"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="mock-docs-scenario-empty"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="mock-docs-scenario-list"]').click()
        expect(page.locator('[data-test="mock-scenarios-page"]')).to_be_visible(timeout=15000)

        # AN-4: the list screen is the workbench scenarios tab; deep-link
        # with both ids wins (fresh reload re-fetches the visible fleet).
        page.goto(_wb(space_id, pid, "scenarios"),
                  wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        rows = page.locator('[data-test="mock-scenarios-row"]')
        expect(page.locator('[data-test="mock-scenarios-empty"]')).to_be_visible(timeout=10000)

        # --- door 2: create a two-state machine through the drawer; the entry
        # echoes the header project read-only (ownership iron rule 1) ---
        name = f"flow-{uniq}"
        drawer = _open_drawer(page)
        expect(page.locator('[data-test="owner-echo-value"]')).to_have_text(f"ScenE2E {uniq}")
        _fill(drawer, "mock-scenarios-f-name", name)
        _add_state(drawer, 0, "idle")
        _add_state(drawer, 1, "done")
        drawer.locator('[data-test="mock-scenarios-f-add-edge"]').click()
        _pick_edge_from(page, drawer, "idle")
        _fill(drawer, "mock-scenarios-f-edge-to-0", "done")
        drawer.locator('[data-test="mock-scenarios-save"]').click()

        expect(rows).to_have_count(1, timeout=10000)
        expect(rows.first).to_contain_text(name, timeout=5000)
        row = page.locator(".el-table__row").filter(has_text=name)
        expect(row).to_contain_text("会话泳道", timeout=5000)
        expect(row.locator('[data-test="mock-scenarios-healthy"]')).to_be_visible(timeout=5000)

        # --- the detail page is the one-graph-per-screen surface: 2 nodes,
        # 1 solid edge, the initial tag rides the declaration ---
        rows.first.click()
        expect(page.locator('[data-test="mock-scenario-detail-page"]')).to_be_visible(timeout=15000)
        graph = page.locator('svg[data-test="mock-scenario-detail-graph"]')
        expect(graph).to_have_count(1, timeout=10000)
        assert graph.locator("[data-node]").count() == 2, "graph node count != declared states"
        assert graph.locator('[data-edge="solid"]').count() == 1, "graph edge count != declared transitions"
        assert graph.locator('[data-node="idle"]').get_attribute("data-initial") == "true"

        # --- door 3: reload keeps truth — the machine row lives in the DB ---
        page.goto(_wb(space_id, pid, "scenarios"),
                  wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(rows).to_have_count(1, timeout=10000)
        rows.first.click()
        expect(page.locator('svg[data-test="mock-scenario-detail-graph"] [data-node]')).to_have_count(2)
        page.locator('[data-test="mock-scenario-detail-back"]').click()
        expect(page.locator('[data-test="mock-scenarios-page"]')).to_be_visible(timeout=15000)
        rows = sql_query(
            f"SELECT definition_json::text FROM tp_mock_scenario WHERE space_id = {space_id}")
        assert rows, "scenario row not persisted"
        definition = json.loads(rows)
        assert definition["states"] == ["idle", "done"], f"states drifted: {definition}"
        assert definition["initial"] == "idle", f"initial drifted: {definition}"

        # --- door 4: core save gate stays the single legality point — a
        # dangling transition is pre-warned by the drawer aside and refused by
        # the server in plain words ---
        drawer = _open_drawer(page)
        _fill(drawer, "mock-scenarios-f-name", f"bad-{uniq}")
        _add_state(drawer, 0, "idle")
        drawer.locator('[data-test="mock-scenarios-f-add-edge"]').click()
        _pick_edge_from(page, drawer, "idle")
        _fill(drawer, "mock-scenarios-f-edge-to-0", "ghost")
        expect(drawer.locator('[data-test="mock-scenarios-dangling-warn"]')).to_be_visible(timeout=5000)
        drawer.locator('[data-test="mock-scenarios-save"]').click()
        # the refusal arrives as a human-worded toast — never a stack dump
        toast = page.locator(".el-message--error")
        expect(toast.first).to_be_visible(timeout=10000)
        expect(toast.first).not_to_contain_text("Exception", timeout=5000)
        expect(toast.first).not_to_contain_text("{", timeout=5000)
        # and no row landed — the count stays one
        expect(page.locator('[data-test="mock-scenarios-row"]')).to_have_count(1, timeout=10000)
        page.keyboard.press("Escape")

        # Drop the deliberate 400 this test provoked (a dangling transition is
        # refused by the core save gate in plain words — that refusal IS the
        # acceptance door); every other 4xx/console error still trips the gate.
        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not (f"/api/v1/mock/spaces/{space_id}/scenarios" in e["url"] and e["status"] == 400)]
        collectors["console_errors"] = [
            c for c in collectors["console_errors"] if "status of 400" not in c]

        assert_no_errors(collectors, "T361b scenario create + reload + refusal")
    finally:
        sql_query(f"DELETE FROM tp_mock_scenario WHERE space_id = {space_id}")
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
        api_client.delete(f"/projects/{pid}/purge")
