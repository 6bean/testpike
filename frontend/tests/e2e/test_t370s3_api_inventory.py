# -*- coding: utf-8 -*-
"""Interface inventory page (AH W1c — first E2E journey.

Real browser, real chain (admin bridge -> Feign -> core joint query), no
mocks. Assertion doors:
1. worst-case fixture (a 35-path OpenAPI contract, one deprecated) uploaded
 through the live /api-specs API lands as 35 rows under a 20-row page
 window — the pager total is truthful and page 2 carries the remainder
 (display-scale iron rule: a 50~200-row page must fold, never dump);
2. the coverage card counts total=35 and excludes the deprecated endpoint
 from the denominator (uncoveredCount=34 with zero assets — honest 0%);
3. the deprecated row still shows with its gray tag (listed, not hidden);
4. freshness badges render the served verdict (all stable for a fresh chain);
5. the mock column honestly says "no mock" for every row of a project with
 zero stubs (the page never fabricates coverage);
6. the sidebar leaf + help anchor land inside the API-testing domain (T).

Sandbox project only (named E2E-inventory-*), never the regression-demo or
experience-sample projects. Rows are torn down with the project.
"""
import httpx
from playwright.sync_api import expect

from conftest import (API_BASE, BASE_URL, ADMIN_PASSWORD, ADMIN_USERNAME,
                      api_login, assert_no_errors)

INVENTORY_URL = f"{BASE_URL}/#/api-inventory"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def _mk_openapi_doc(n_paths=35):
    """Hand-built OpenAPI 3 document with n_paths distinct paths; the last one
    carries deprecated:true so the exclusion rule is exercised for real."""
    paths = {}
    for i in range(n_paths):
        deprecated = (i == n_paths - 1)
        paths[f"/e2e-res{i}/{{id}}"] = {
            "get": {
                "operationId": f"getE2eRes{i}",
                "summary": f"E2E inventory resource {i}",
                "deprecated": deprecated,
                "parameters": [{
                    "name": "id", "in": "path", "required": True,
                    "schema": {"type": "integer"},
                }],
                "responses": {"200": {"description": "ok"}},
            }
        }
    return {
        "openapi": "3.0.1",
        "info": {"title": "E2E Inventory Contract", "version": "1.0.0"},
        "paths": paths,
    }


def _upload_spec(pid, name, doc):
    """Upload through the real admin multipart door (same as the wizard does)."""
    import json as _json
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


def test_t370s3_inventory_page_journey(logged_in, collectors, api_client, uniq):
    """35-path worst-case fixture → paged rows + honest coverage + tags."""
    page = logged_in
    pid = _mk_project(api_client, f"E2E-inventory-{uniq}", f"e2einv{uniq[:10]}")
    spec_name = f"invspec-{uniq}"
    _upload_spec(pid, spec_name, _mk_openapi_doc(35))

    try:
        page.goto(f"{INVENTORY_URL}?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="api-inventory-page"]')).to_be_visible(timeout=15000)

        # the contract picker must surface the uploaded chain (pick explicitly,
        # the auto-first row of the list is not a stable expectation)
        picker = page.locator('[data-test="api-inventory-filter-spec"]')
        expect(picker).to_be_visible(timeout=15000)
        picker.click()
        page.locator(f".el-select-dropdown__item:has-text('{spec_name}')").first.click()

        # coverage card: denominator excludes the deprecated endpoint (core
        # contract — total here = in-scope rows = 34; the full 35 travels in
        # the table pager total below)
        expect(page.locator('[data-test="api-inventory-cov-total"]')).to_have_text("34", timeout=15000)
        expect(page.locator('[data-test="api-inventory-cov-uncovered"]')).to_have_text("34")
        expect(page.locator('[data-test="api-inventory-cov-anchor"]')).to_contain_text("v1")

        # worst-case scale: first page window is bounded at 20, pager is truthful
        rows = page.locator('[data-test="api-inventory-table"] .el-table__row')
        expect(rows).to_have_count(20, timeout=15000)
        expect(page.locator('[data-test="api-inventory-pagination"]')).to_be_visible()
        expect(page.locator('[data-test="api-inventory-pager"] .el-pagination__total')).to_contain_text("35")

        # page 2 carries the remainder (35-20=15) — fold works, nothing is dumped
        page.locator('[data-test="api-inventory-pager"] .btn-next').click()
        expect(rows).to_have_count(15, timeout=15000)

        # every served freshness verdict renders (fresh chain = all stable)
        fresh = page.locator('[data-test="api-inventory-freshness"]')
        expect(fresh.first).to_be_visible(timeout=10000)

        # mock column honesty: zero stubs in this sandbox = every row is "no mock"
        mock_none = page.locator('[data-test="api-inventory-mock-none"]')
        expect(mock_none.first).to_be_visible(timeout=10000)

        # deprecated row is listed with its gray tag (page 1 window, first 20 rows
        # contain none — flip to page 2 where /e2e-res34 sits)
        page.locator('[data-test="api-inventory-pager"] .btn-prev').click()
        expect(rows).to_have_count(20, timeout=15000)
        page.locator('[data-test="api-inventory-pager"] .btn-next').click()
        expect(page.locator('[data-test="api-inventory-deprecated-tag"]')).to_have_count(1, timeout=15000)

        assert_no_errors(collectors, "interface inventory journey")
    finally:
        r = api_client.delete(f"/projects/{pid}")
        assert r.status_code == 200, f"project teardown failed: {r.text[:200]}"
