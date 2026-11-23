"""Worst-scale fixture E2E for the API-test workbench (display-scale iron rule).

Worst-case shape of the interface-set page: 30 collections x 200 requests in
the target collection (the rail renders the fully-loaded in-memory segment with
card filters — the <=2-click/1-search promise is measured against that shape).

Chain: SQL bulk seed (uniquely named project, t292b5 _sql precedent) -> header
context picks the project -> collection filter finds the target card (1 search
+ 1 click) -> request filter finds request #200 (1 search + 1 click) -> the
workspace head echoes the request name. Cleanup: project purge?force=true plus
a zero-residue assertion.

Known traps: a) source .env before running; b) seeding is data-only through
SQL — no dispatch side effects; c) the header visible-project snapshot loads
at login, so seeding happens before inject_token and needs no extra reload;
d) 200-row first paint is covered by expect auto-wait, no hard sleeps.
"""
import subprocess

import pytest
from conftest import BASE_URL, assert_no_errors, inject_token, pick_apitest_request, sql_query
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(240)

COLLECTIONS = 30
REQUESTS_IN_TARGET = 200


def _sql(q):
    r = subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
                        "-d", "testpike", "-tAc", q],
                       capture_output=True, text=True)
    assert r.returncode == 0, f"sql failed: {r.stderr[:200]}"
    return r.stdout.strip()


def _seed_scale_project(api_client, uniq):
    """Real API project + SQL bulk collections/requests (data-only, no dispatch)."""
    r = api_client.post("/projects", json={"name": f"T413规模项目{uniq}", "code": f"T413{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    data = r.json()["data"]
    pid = data["id"] if isinstance(data, dict) else data
    _sql(f"INSERT INTO tp_apitest_collection (project_id, parent_id, name, tags) "
         f"SELECT {pid}, 0, '规模接口集' || g, '' FROM generate_series(1, {COLLECTIONS}) g")
    _sql(f"INSERT INTO tp_apitest_request (collection_id, name, method, url_path, headers_json, "
         f"body_text, assertions_json) "
         f"SELECT c.id, '规模请求' || gs, 'GET', '/scale/' || gs, '[]', '', '[]' "
         f"FROM tp_apitest_collection c, generate_series(1, {REQUESTS_IN_TARGET}) gs "
         f"WHERE c.project_id = {pid} AND c.name = '规模接口集27'")
    return pid


def test_t413_worst_scale_two_click_reach(page, collectors, api_client, uniq):
    """30 collections x 200 requests: one search + one click reaches the target (<=2-click rule)."""
    pid = _seed_scale_project(api_client, uniq)
    proj_name = f"T413规模项目{uniq}"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="api-test-page"]')).to_be_visible(timeout=15000)

        # header context lands the scale project (single pick)
        page.locator('[data-test="project-ctx-select"]').click()
        page.locator(
            f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{proj_name}"):visible'
        ).first.click()

        # one search cuts 30 collections down to the target card
        col_filter = page.locator('[data-test="apitest-collection-filter"]')
        col_filter.fill("规模接口集27")
        card = page.locator('[data-test^="apitest-collection-card-"]').filter(has_text="规模接口集27")
        expect(card).to_have_count(1, timeout=10000)
        card.first.click()

        # the rail shows the target collection's segment through the virtual
        # window : DOM row count stays at the window size while
        # the scrollbar stays honest for all 200 rows (scrollHeight covers
        # the full source; scrolling to the end reveals the tail).
        req_filter = page.locator('[data-test="apitest-request-filter"]')
        rows = page.locator('[data-test^="apitest-request-row-"]')
        expect(rows.first).to_be_visible(timeout=15000)
        # all 200 rows really loaded: the scrollbar spans the full source
        # (windowed DOM never holds them, so height is the honest signal)
        page.wait_for_function(
            """() => { const el = document.querySelector('[data-test="apitest-request-rows"]');
            return el && el.scrollHeight >= 200 * 30; }""", timeout=15000
        )
        req_filter.fill("规模请求200")
        row = page.locator('[data-test^="apitest-request-row-"]')
        expect(row).to_have_count(1, timeout=10000)
        pick_apitest_request(page, "规模请求200")
        head = page.locator('[data-test="apitest-workspace-head"]')
        expect(head).to_contain_text("规模请求200", timeout=10000)

        # windowed paint among the full 200: far fewer DOM rows than the
        # source, yet the bottom-most row is reachable by real scrolling
        req_filter.fill("")
        expect(rows.first).to_be_visible(timeout=10000)
        dom_count = rows.count()
        assert dom_count < REQUESTS_IN_TARGET, f"未虚拟化：DOM 仍全量 {dom_count} 行"
        box = page.locator('[data-test="apitest-request-rows"]')
        page.wait_for_function(
            """() => { const el = document.querySelector('[data-test="apitest-request-rows"]');
            return el && el.scrollHeight >= 200 * 30; }"""
        )
        box.evaluate("el => { el.scrollTop = el.scrollHeight }")
        expect(page.locator('[data-test^="apitest-request-row-"]').filter(has_text="规模请求200")).to_be_visible(
            timeout=10000
        )

        assert_no_errors(collectors, "T413 最坏规模 30集×200请求 ≤2击/1搜")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
        left = int(sql_query(f"SELECT count(*) FROM tp_apitest_collection WHERE project_id = {pid}") or "0")
        assert left == 0, f"规模夹具残留集合: {left}"
