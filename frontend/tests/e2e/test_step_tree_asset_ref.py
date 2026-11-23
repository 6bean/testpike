"""Step-editor shared-asset overlay E2E .

Chain: API-seeded owner project publishes a case-fragment head plus 33 filler
rows (worst-case scale, server catalog cap 200) -> consumer project case opens
the script tree dialog -> overlay lazy-loads the catalog on first dropdown open
-> picking a fragment row inserts its two steps through the shared selection
kernel and pins that concrete version row to the case -> save through the diff
dialog lands the steps in tp_script.action_json -> reload keeps both the step
count and the DB pin (iron rule 4) -> fixture purge with zero residue.

Deliberate scope note: this spec walks a single-asset pin per case. The pin
model currently keeps exactly one row per host (upsert moves the host row on a
different-asset pick), while the overlay permits repeated picks; multi-pin
semantics is an open product decision tracked in the chain bookkeeping, so no
assert here freezes either reading of that gap.

Field notes: a) the stack runner exports TP_E2E_BASE_URL/TP_E2E_API_BASE/
TP_E2E_CORE_BASE for the isolated chain ports; b) the success toast is pinned
by its message class, not by localized text, so the spec stays locale-proof;
c) every fixture name carries the uniq suffix and every row is cleaned by id
in finally (R37); d) the three zero-tolerance collectors (4xx/5xx, console,
CDP issues) come from conftest.
"""
import json
import os

import httpx
import pytest
from step_tree_overlay_probe import expand_asset_ref_overlay
from conftest import (
    assert_no_errors,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(420)

BASE = os.environ.get("TP_E2E_BASE_URL", "http://localhost:5173")
CORE = os.environ.get("TP_E2E_CORE_BASE", "http://localhost:8083")


def _api_ok(r):
    assert r.status_code == 200 and str(r.json().get("code")) == "0", f"API failure: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uid):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"asset-ref case {uid}",
        "precondition": f"published fragment in pool ({uid})",
        "expectedResult": "fragment steps insert into the tree and the pin lands",
        "priority": 1, "confidence": 1, "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "open page", "isAssert": 0},
                  {"stepNo": 2, "description": "type query", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"case seed failed: {r.text[:300]}"
    return r.json()["data"]["id"]


def _save_script(case_id):
    doc = {
        "version": "v2", "caseId": case_id, "title": "asset-ref scenario", "endType": "web",
        "steps": [
            {"stepId": 1, "action": "goto", "url": "http://localhost:8899/search"},
            {"stepId": 2, "action": "input", "target": {"text": "search-box", "chain": ["text"]},
             "value": "init"},
        ],
        "env": {"ENV": "dev"},
    }
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": json.dumps(doc, ensure_ascii=False), "generatedBy": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"script seed failed: {r.text[:200]}"


def _frag_payload():
    return json.dumps([
        {"action": "goto", "url": "http://localhost:8899/frag-a"},
        {"action": "input", "target": {"text": "frag-field", "chain": ["text"]},
         "value": "frag-value"},
    ], ensure_ascii=False)


def test_step_tree_asset_ref_insert_and_pin(logged_in, api_client, collectors, uniq):
    """Overlay: lazy catalog -> fragment pick -> step insert + case pin -> save -> reload."""
    page = logged_in
    oid = _api_ok(api_client.post("/projects", json={"name": f"YRefOwner{uniq}", "code": f"YRO{uniq[:6]}"}))
    cid = _api_ok(api_client.post("/projects", json={"name": f"YRefCons{uniq}", "code": f"YRC{uniq[:6]}"}))
    frag_name = f"yfrag-{uniq[:8]}"
    try:
        # ---- seed: one published fragment head + worst-case scale fillers ----
        frag = _api_ok(api_client.post("/shared-assets", json={
            "projectId": oid, "kind": "case_fragment",
            "name": frag_name, "payloadJson": _frag_payload()}))
        frag_id = frag["id"]
        for i in range(31):
            _api_ok(api_client.post("/shared-assets", json={
                "projectId": oid, "kind": "case_fragment",
                "name": f"yfill-{i:02d}-{uniq[:4]}", "payloadJson": "[]"}))
        _api_ok(api_client.post("/shared-assets", json={
            "projectId": oid, "kind": "login_recipe",
            "name": f"ylogin-{uniq[:4]}", "payloadJson": '{"label":"seed","assemblies":[]}'}))
        _api_ok(api_client.post("/shared-assets", json={
            "projectId": oid, "kind": "action_component",
            "name": f"yact-{uniq[:4]}", "payloadJson": '{"action":"click"}'}))

        case_id = _mk_case(api_client, cid, uniq)
        _save_script(case_id)

        # ---- open the script tree dialog on the consumer case ----
        page.goto(f"{BASE}/#/cases/{case_id}", wait_until="domcontentloaded")
        page.locator('[data-test="detail-script-tree-edit"]').click()
        dialog = page.locator('[data-test="step-tree-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        rows = dialog.locator('[data-test^="step-tree-row-"]')
        expect(rows).to_have_count(2, timeout=15000)

        # ---- select the input step, open the lazy catalog popper and prove
        # the seeded fragment option renders with its kind groups — shared
        # DOM-contract probe, single source with the vision capture harness ----
        popper, opt = expand_asset_ref_overlay(page, dialog, rows, frag_id, frag_name)

        # ---- pick: two steps land after the selected row, pin registers on the case ----
        opt.click()
        expect(rows).to_have_count(4, timeout=5000)
        assert sql_query(
            f"SELECT count(*) FROM tp_asset_reference "
            f"WHERE referrer_kind = 'case' AND referrer_id = {case_id}") == "1", "case pin must land exactly once"
        pin_row = sql_query(
            f"SELECT asset_row_id || '/' || referrer_project_id FROM tp_asset_reference "
            f"WHERE referrer_kind = 'case' AND referrer_id = {case_id}")
        assert pin_row == f"{frag_id}/{cid}", f"pin points at the wrong row/project: {pin_row}"

        # ---- save through the diff dialog, then assert the landed script ----
        dialog.locator('[data-test="step-tree-save"]').click()
        diff = page.locator('[data-test="step-tree-diff-dialog"]')
        expect(diff).to_be_visible(timeout=8000)
        page.locator('[data-test="step-tree-diff-confirm"]').click()
        expect(page.locator(".el-message--success")).to_be_visible(timeout=8000)
        aj = sql_query(f"SELECT action_json FROM tp_script WHERE case_id = {case_id}")
        doc = json.loads(aj)
        steps = doc["steps"]
        assert len(steps) == 4, f"saved script must hold 4 steps, got {len(steps)}"
        assert steps[2].get("url") == "http://localhost:8899/frag-a", f"inserted goto step wrong: {steps[2]}"
        assert steps[3].get("value") == "frag-value", f"inserted input step wrong: {steps[3]}"

        # ---- reload keeps steps and the pin (iron rule 4) ----
        page.goto(f"{BASE}/#/cases/{case_id}", wait_until="domcontentloaded")
        page.locator('[data-test="detail-script-tree-edit"]').click()
        expect(dialog).to_be_visible(timeout=10000)
        expect(dialog.locator('[data-test^="step-tree-row-"]')).to_have_count(4, timeout=15000)
        assert sql_query(
            f"SELECT count(*) FROM tp_asset_reference "
            f"WHERE referrer_kind = 'case' AND referrer_id = {case_id}") == "1", "pin lost on reload"

        assert_no_errors(collectors, "step-editor shared-asset overlay full chain")
    finally:
        sql_query(f"DELETE FROM tp_asset_reference WHERE referrer_project_id = {cid}")
        sql_query(f"DELETE FROM tp_asset WHERE project_id = {oid}")
        for pid in (oid, cid):
            api_client.delete(f"/projects/{pid}/purge?force=true")
        assert sql_query(f"SELECT count(*) FROM tp_asset WHERE project_id IN ({oid},{cid})") == "0"
        assert sql_query(
            f"SELECT count(*) FROM tp_asset_reference WHERE referrer_project_id = {cid}") == "0"
