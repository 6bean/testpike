# -*- coding: utf-8 -*-
"""V240 serving inventory E2E — the project-wide flat endpoint list.

Real browser, no mocks. Assertion doors:
1. two spaces (one published, one draft) + rules created through the live API
   land in ONE table on /mock/endpoints with the composed serving URL per row
   (space routing handle travels — same path under two spaces = two rows);
2. serving state column tells draft apart from published (honest, not hidden);
3. the 7-day impact count renders a truthful 0 ("no traffic") for a never-hit
   rule — a fabricated hit number would be worse;
4. the space column jumps into that space's docs page carrying the context;
5. method + keyword filters hit the SERVER (draft a keyword that only exists
   on page 2 of a >pageSize inventory? covered by keyword over a 3-rule set:
   the filtered total shrinks truthfully), pagination window stays bounded;
6. help anchor + sidebar leaf inside the API-mocking group.

Rows are torn down for real; assert_no_errors bundles the 4xx/5xx, console
error/warn and browser-issues zero-tolerance gates.
"""
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, sql_query

ENDPOINTS_URL = f"{BASE_URL}/#/mock/endpoints"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={"projectId": pid, "name": name, "spaceKey": key})
    assert r.status_code == 200 and r.json().get("code") == "0", f"space create failed: {r.text[:300]}"
    return r.json()["data"]


def _publish(api, space_id, version=0):
    r = api.post(
        f"/mock/spaces/{space_id}/transition",
        json={"targetStatus": 1, "expectedVersion": version},
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"publish failed: {r.text[:300]}"


def _mk_stub(api, space_id, name, method, path):
    r = api.post(
        f"/mock/spaces/{space_id}/stubs",
        json={"name": name, "method": method, "pathMatchType": "exact",
              "pathPattern": path, "responseJson": "{\"status\":200,\"body\":\"ok\"}"},
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"stub create failed: {r.text[:300]}"
    return r.json()["data"]


def test_v240a_inventory_page_journey(logged_in, collectors, api_client, uniq):
    """Cross-space rows + composed URL + honest zero-hit + filters + jump."""
    page = logged_in
    pid = _mk_project(api_client, f"InvE2E {uniq}", f"inv{uniq}")
    # space A published (priority 10), space B draft (priority 5 -> routes first)
    space_a = _mk_space(api_client, pid, f"inv-alpha-{uniq}", f"inv{uniq}a")
    space_b = _mk_space(api_client, pid, f"inv-beta-{uniq}", f"inv{uniq}b")
    _publish(api_client, space_a)
    stub_a1 = _mk_stub(api_client, space_a, "orders get", "GET", "/orders")
    stub_a2 = _mk_stub(api_client, space_a, "orders post", "POST", "/orders")
    _mk_stub(api_client, space_b, "beta health", "GET", "/orders")  # same path, other handle

    try:
        page.goto(f"{ENDPOINTS_URL}?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-endpoints-page"]')).to_be_visible(timeout=15000)

        table = page.locator('[data-test="mock-endpoints-table"]')
        expect(table).to_be_visible(timeout=10000)
        rows = page.locator('[data-test="mock-endpoints-page"] .el-table__row')
        expect(rows).to_have_count(3, timeout=10000)

        # composed URLs carry the routing handle (never an invented host, never
        # a bare path — identity travels per row)
        body_text = page.locator('[data-test="mock-endpoints-page"]').inner_text()
        assert f"/mocks/inv{uniq}a/orders" in body_text
        assert f"/mocks/inv{uniq}b/orders" in body_text

        # published rows show a truthful 7-day count of 0 marked as no-traffic
        hits = page.locator('[data-test="mock-endpoints-hits"]')
        expect(hits.first).to_contain_text("0", timeout=5000)

        # server-side method filter: POST leaves exactly one row.
        # EP2: option click TOGGLES the selection — the first POST click picks
        # it (dropdown auto-closes), clearing is done through the clearable
        # suffix icon, never by clicking the option again (that reselects and
        # the old double-toggle recipe produced the phantom 1-row count).
        def _pick_method(value):
            msel = page.locator('[data-test="mock-endpoints-filter-method"]')
            msel.click()
            page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{value}")').first.click()

        def _clear_method():
            msel = page.locator('[data-test="mock-endpoints-filter-method"]')
            msel.hover()
            clear = msel.locator(".el-select__clear")
            if clear.count():
                clear.first.click()

        _pick_method("POST")
        expect(rows).to_have_count(1, timeout=10000)
        expect(rows.first).to_contain_text("/orders")

        # keyword filter over the full inventory (clears method first)
        _clear_method()
        expect(rows).to_have_count(3, timeout=10000)
        search = page.locator('[data-test="mock-endpoints-search"]')
        search.fill("beta health")
        page.locator('[data-test="mock-endpoints-search-go"]').click()
        expect(rows).to_have_count(1, timeout=10000)

        # status filter: published-only drops the draft space row
        search.fill("")
        page.locator('[data-test="mock-endpoints-search-go"]').click()
        expect(rows).to_have_count(3, timeout=10000)
        ssel = page.locator('[data-test="mock-endpoints-filter-status"]')
        ssel.click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:has-text("已发布")').first.click()
        expect(rows).to_have_count(2, timeout=10000)

        # space column jumps into the docs page carrying the space context.
        # AN-4 contract: the jump lands on the per-space workbench route
        # /mock/spaces/{id}?tab=docs — the space id rides the PATH now (the
        # old ?spaceId= query era predates the workbench merge), so the
        # assertion follows the current addressing, not the retired query key.
        page.locator('[data-test="mock-endpoints-space-jump"]').first.click()
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)
        assert f"/mock/spaces/{space_a}" in page.url or f"/mock/spaces/{space_b}" in page.url

        # ---- V240 follow-up (user ruling "act where you find"): the write
        # doors live on THIS page too — edit renames through the shared drawer
        # and lands in the rule source; the space jump happened above, so
        # return to the inventory first.
        page.goto(f"{ENDPOINTS_URL}?projectId={pid}", wait_until="domcontentloaded")
        rows_all = page.locator('[data-test="mock-endpoints-page"] .el-table__row')
        expect(rows_all).to_have_count(3, timeout=15000)
        page.locator('[data-test="mock-endpoints-act-edit"]').first.click()
        drawer = page.locator('[data-test="mock-docs-create-drawer"]')
        expect(drawer).to_be_visible(timeout=5000)
        name_input = drawer.locator('[data-test="mock-docs-f-name-input"]')
        expect(name_input).not_to_have_value("", timeout=5000)
        name_input.fill("renamed from inventory")
        drawer.locator('[data-test="mock-docs-f-save"]').click()
        expect(page.locator(".el-message--success:visible")).to_contain_text("已更新", timeout=8000)
        renamed = sql_query(
            "SELECT COUNT(*) FROM tp_mock_stub WHERE name = 'renamed from inventory'")
        assert renamed == "1", f"edit from the inventory page must land in the rule source: {renamed}"

        assert_no_errors(collectors, "v240 inventory journey")
    finally:
        for space_id in (space_a, space_b):
            sql_query(f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}")
            sql_query(f"DELETE FROM tp_mock_scenario WHERE space_id = {space_id}")
            sql_query(f"DELETE FROM tp_mock_recording WHERE space_id = {space_id}")
            sql_query(f"DELETE FROM tp_mock_ai_job WHERE space_id = {space_id}")
            sql_query(f"DELETE FROM tp_mock_hit_log WHERE space_id = {space_id}")
            sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
