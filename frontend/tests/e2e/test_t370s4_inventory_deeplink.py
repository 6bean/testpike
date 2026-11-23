# -*- coding: utf-8 -*-
"""Interface inventory deep-link consolidation (AH W1c stage 4).

Real browser, real chain (admin bridge -> Feign -> core), zero mocks. Every
count badge on an inventory row is a working door (design red line: badges
must be clickable, filters travel through deep links, AG §12a-4: destinations
reuse their existing keyword query param — no new grammar, no route aliases).

Assertion doors:
1. case badge -> /cases?projectId=&keyword=<path>: the keyword box lands
 prefilled with the raw endpoint path and the table shows the case whose
 title carries that path (substring ILIKE is the destination's own rule);
2. requests badge -> /api-test?projectId=&keyword=<path>: the request-pick
 filter box carries the path and the option list cuts to the matching
 request only (2 seeded requests, 1 hit);
3. mock badge -> /mock/endpoints?projectId=&keyword=<path>: the search box
 carries the path and the serving table shows the seeded stub row;
4. version compare drawer: an edited re-upload (POST /orders gains one query
 parameter) lands as changed=1 from the server-side ledger, and expanding
 the changed row renders both sides' field panels side by side (parsed
 rows, never a JSON dump);
5. uncovered working list: clicking the red coverage number turns the
 server-side filter on, the chip is visible, and the table row count
 equals the uncovered number the card serves (core judgment, not a
 client-side re-count); closing the chip restores the full window.

Sandbox project only (named E2E-deeplink-*); teardown deletes the project.
"""
import json as _json
import re

import httpx
from playwright.sync_api import expect

from conftest import (API_BASE, BASE_URL, ADMIN_PASSWORD, ADMIN_USERNAME,
                      api_login, assert_no_errors, sql_query)

INVENTORY_URL = f"{BASE_URL}/#/api-inventory"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def _spec_v1():
    """Three endpoints; one of them (/toys) stays a bare contract line so the
    uncovered working list has a truthful member regardless of asset joins."""
    return {
        "openapi": "3.0.1",
        "info": {"title": "E2E Deeplink Contract", "version": "1.0.0"},
        "paths": {
            "/e2e-dl-pets/{petId}": {
                "get": {
                    "operationId": "getPet", "summary": "get pet",
                    "parameters": [{"name": "petId", "in": "path", "required": True,
                                    "schema": {"type": "integer"}}],
                    "responses": {"200": {"description": "ok"}},
                }
            },
            "/e2e-dl-orders": {
                "post": {
                    "operationId": "createOrder", "summary": "create order",
                    "responses": {"201": {"description": "created"}},
                }
            },
            "/e2e-dl-toys/{toyId}": {
                "get": {
                    "operationId": "getToy", "summary": "get toy",
                    "parameters": [{"name": "toyId", "in": "path", "required": True,
                                    "schema": {"type": "integer"}}],
                    "responses": {"200": {"description": "ok"}},
                }
            },
        },
    }


def _spec_v2():
    """Same three paths; POST /e2e-dl-orders gains one query parameter, so the
    anchored diff is exactly one changed endpoint (no added/removed noise)."""
    doc = _spec_v1()
    doc["paths"]["/e2e-dl-orders"]["post"]["parameters"] = [{
        "name": "channel", "in": "query", "required": False,
        "schema": {"type": "string"},
    }]
    return doc


def _upload_spec(pid, name, doc):
    """Upload through the real admin multipart door (same bytes the wizard sends)."""
    token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)
    r = httpx.post(
        f"{API_BASE}/api-specs",
        headers={"Authorization": f"Bearer {token}"},
        files={"file": (f"{name}.json", _json.dumps(doc), "application/json")},
        data={"projectId": str(pid), "name": name},
        timeout=30,
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"spec upload failed: {r.text[:300]}"
    return r.json()["data"]


def _pick_spec(page, spec_name):
    picker = page.locator('[data-test="api-inventory-filter-spec"]')
    expect(picker).to_be_visible(timeout=15000)
    picker.click()
    page.locator(f".el-select-dropdown__item:has-text('{spec_name}')").first.click()


def test_t370s4_inventory_deeplinks(logged_in, collectors, api_client, uniq):
    """Badge doors + compare-changed side-by-side + uncovered chip, one journey."""
    page = logged_in
    pid = _mk_project(api_client, f"E2E-deeplink-{uniq}", f"dpl{uniq[:9]}")
    spec_name = f"dlspec-{uniq}"
    pet_path = "/e2e-dl-pets/{petId}"
    space_id = None

    try:
        _upload_spec(pid, spec_name, _spec_v1())

        # ── seed the three asset doors through their own real API doors ──
        # case whose title carries the raw endpoint path (destination filter is
        # substring ILIKE on case_no/title — the prefill must hit it as-is)
        rc = api_client.post("/cases", json={
            "projectId": pid, "title": f"覆盖 {pet_path} 冒烟用例", "precondition": "前置",
            "expectedResult": "通过", "priority": 1, "confidence": 2,
            "endType": "web", "phase": 2,
            "steps": [{"stepNo": 1, "description": "打开接口", "isAssert": 0}],
        })
        assert rc.status_code == 200 and rc.json().get("code") == "0", f"case seed failed: {rc.text[:300]}"

        col = api_client.post("/apitest/collections", json={
            "projectId": pid, "parentId": 0, "name": f"deeplink集{uniq}", "tags": ""})
        assert col.json().get("code") == "0", f"collection seed failed: {col.text[:300]}"
        col_id = col.json()["data"]["id"]

        def save_req(name, url_path):
            rr = api_client.post("/apitest/requests", json={
                "collectionId": col_id, "name": name, "method": "GET", "urlPath": url_path,
                "headersJson": "[]", "bodyText": "", "authJson": "{}",
                "assertionsJson": '[{"type":"status","op":"equals","expect":200}]'})
            assert rr.json().get("code") == "0", f"request {name} seed failed: {rr.text[:300]}"

        save_req("命中契约行的请求", pet_path)
        save_req("无关路径的请求", "/e2e-dl-nothing")

        sp = api_client.post("/mock/spaces", json={
            "projectId": pid, "name": f"deeplink-space-{uniq}", "spaceKey": f"dpl{uniq[:8]}"})
        assert sp.json().get("code") == "0", f"space seed failed: {sp.text[:300]}"
        space_id = sp.json()["data"]
        tp = api_client.post(f"/mock/spaces/{space_id}/transition",
                             json={"targetStatus": 1, "expectedVersion": 0})
        assert tp.json().get("code") == "0", f"space publish failed: {tp.text[:300]}"
        st = api_client.post(f"/mock/spaces/{space_id}/stubs", json={
            "name": f"dl-stub-{uniq}", "method": "GET", "pathMatchType": "exact",
            "pathPattern": pet_path, "matchersJson": "[]",
            "responseJson": _json.dumps({"status": 200, "body": _json.dumps({"ok": True})}),
            "source": "manual", "sourceRef": None,
        })
        assert st.json().get("code") == "0", f"stub seed failed: {st.text[:300]}"

        # ── inventory page, anchored on the single chain ──
        page.goto(f"{INVENTORY_URL}?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="api-inventory-page"]')).to_be_visible(timeout=15000)
        _pick_spec(page, spec_name)
        table = page.locator('[data-test="api-inventory-table"]')
        expect(table.locator("tbody tr")).to_have_count(3, timeout=15000)
        pet_row = table.locator("tbody tr").filter(has_text=pet_path).first

        # ── door 1: case badge -> /cases prefilled keyword hits the case ──
        pet_row.locator('[data-test="api-inventory-case-jump"]').click()
        expect(page).to_have_url(
            re.compile(r"/#/cases\?projectId=" + str(pid) + r"&keyword="), timeout=10000)
        kw_box = page.locator('[data-test="case-filter-keyword"]')
        expect(kw_box).to_have_value(pet_path, timeout=10000)
        case_table = page.locator('[data-test="case-table"]')
        expect(case_table.locator("tbody tr").first).to_be_visible(timeout=15000)
        expect(case_table).to_contain_text("覆盖")
        # the filter is honest: the unrelated-path request's name must not leak
        # into this project's case segment (only the seeded case exists anyway)
        expect(case_table.locator("tbody tr")).to_have_count(1, timeout=10000)

        # ── door 2: requests badge -> /api-test prefill cuts the pick list ──
        page.goto(f"{INVENTORY_URL}?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="api-inventory-page"]')).to_be_visible(timeout=15000)
        _pick_spec(page, spec_name)
        pet_row = page.locator('[data-test="api-inventory-table"] tbody tr').filter(has_text=pet_path).first
        pet_row.locator('[data-test="api-inventory-req-jump"]').click()
        expect(page).to_have_url(
            re.compile(r"/#/api-test\?projectId=" + str(pid) + r"&keyword="), timeout=10000)
        req_box = page.locator('[data-test="apitest-request-filter"]')
        expect(req_box).to_have_value(pet_path, timeout=10000)
        # the collection auto-picks its only ledger; the rail request list
        # (cut by the prefilled keyword) must show exactly the one request
        # whose path matches the keyword — anchor migration: the request
        # dropdown is retired, the rail list + keyword filter replaces it.
        rows = page.locator('[data-test^="apitest-request-row-"]')
        expect(rows).to_have_count(1, timeout=10000)
        expect(rows.first).to_contain_text("命中契约行的请求")

        # ── door 3: mock badge -> /mock/endpoints prefill shows the stub row ──
        page.goto(f"{INVENTORY_URL}?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="api-inventory-page"]')).to_be_visible(timeout=15000)
        _pick_spec(page, spec_name)
        pet_row = page.locator('[data-test="api-inventory-table"] tbody tr').filter(has_text=pet_path).first
        pet_row.locator('[data-test="api-inventory-mock-jump"]').first.click()
        expect(page).to_have_url(
            re.compile(r"/#/mock/endpoints\?projectId=" + str(pid) + r"&keyword="), timeout=10000)
        mock_box = page.locator('[data-test="mock-endpoints-search"]')
        expect(mock_box).to_have_value(pet_path, timeout=10000)
        # apply the prefilled keyword (the box carries it; enter triggers the
        # same server-side cut the badge promises)
        mock_box.press("Enter")
        serving = page.locator('[data-test="mock-endpoints-table"]')
        expect(serving).to_contain_text(pet_path, timeout=15000)

        # ── door 4: version compare drawer, changed row side by side ──
        page.goto(f"{INVENTORY_URL}?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="api-inventory-page"]')).to_be_visible(timeout=15000)
        _pick_spec(page, spec_name)
        _upload_spec(pid, spec_name, _spec_v2())
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="api-inventory-page"]')).to_be_visible(timeout=15000)
        _pick_spec(page, spec_name)
        # anchored freshness: the edited endpoint wears the changed badge
        orders_row = page.locator('[data-test="api-inventory-table"] tbody tr').filter(
            has_text="/e2e-dl-orders").first
        expect(orders_row.locator('[data-test="api-inventory-freshness"]')).to_have_text(
            "有变更", timeout=15000)
        page.locator('[data-test="api-inventory-compare-btn"]').click()
        drawer = page.locator('[data-test="api-inventory-compare-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        expect(drawer.locator(".cc-changed")).to_have_text("有变更 1", timeout=8000)
        collapse = drawer.locator('[data-test="api-inventory-compare-collapse"]')
        expect(collapse).to_be_visible(timeout=5000)
        collapse.locator(".el-collapse-item__header").first.click()
        expect(drawer.locator('[data-test="api-inventory-diff-prev"]')).to_be_visible(timeout=5000)
        expect(drawer.locator('[data-test="api-inventory-diff-next"]')).to_be_visible(timeout=5000)
        # side-by-side panels differ exactly by the added query parameter
        expect(drawer.locator('[data-test="api-inventory-diff-next"]')).to_contain_text("channel")
        page.keyboard.press("Escape")
        expect(drawer).not_to_be_visible(timeout=5000)

        # ── door 5: uncovered working list chip is truthful and reversible ──
        uncov_num = page.locator('[data-test="api-inventory-cov-uncovered"]')
        expect(uncov_num).to_be_visible(timeout=8000)
        uncov = int(uncov_num.inner_text())
        assert uncov >= 1, "bare /e2e-dl-toys line must keep the working list non-empty"
        page.locator('[data-test="api-inventory-uncovered-toggle"]').click()
        chip = page.locator('[data-test="api-inventory-uncovered-chip"]')
        expect(chip).to_be_visible(timeout=8000)
        rows = page.locator('[data-test="api-inventory-table"] tbody tr')
        expect(rows).to_have_count(uncov, timeout=10000)
        expect(page.locator('[data-test="api-inventory-table"]')).to_contain_text("/e2e-dl-toys")
        chip.locator(".el-tag__close").first.click()
        expect(chip).not_to_be_visible(timeout=8000)
        expect(rows).to_have_count(3, timeout=10000)

        assert_no_errors(collectors, "inventory deeplink journey")
    finally:
        # Mirror the sibling journey's teardown (t359c): stub/space rows precede
        # their parents, then the project purge sweeps cases/collections/specs.
        if space_id:
            sql_query(f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}")
            sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
        r = api_client.delete(f"/projects/{pid}/purge?force=true")
        assert r.status_code == 200 and r.json().get("code") == "0", f"project purge failed: {r.text[:200]}"
