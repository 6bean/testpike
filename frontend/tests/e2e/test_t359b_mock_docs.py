# -*- coding: utf-8 -*-
"""First E2E for the Mock-as-documentation page (AG batch, .

Real browser, no mocks; the assertion doors:
1. documentation journey — project + published space created through the live
 API, a stub created through the page's paste-a-curl box (parser pre-fills,
 the core save gate decides legality), the rule renders as a doc card with
 its path / matcher summary / response preview / composed serving URL;
2. reload keeps truth (the stub row lives in the DB);
3. copy-curl yields the serving-root composed URL, never an invented host;
4. help button anchors to the API-mocking chapter of domain T and the sidebar
 renders the docs leaf inside the API-Mocking group.

Rows are torn down for real; assert_no_errors bundles the 4xx/5xx, console
error/warn and browser-issues zero-tolerance gates.
"""
import json
import re

from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, sql_query

DOCS_URL = f"{BASE_URL}/#/mock/docs"
SPACES_URL = f"{BASE_URL}/#/mock/spaces"


def _wb(sid, pid, tab="docs"):
    """ AN-4 workbench spectrum: docs screen = the space workbench tab."""
    return f"{SPACES_URL}/{sid}?projectId={pid}&tab={tab}"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def _mk_space(api, pid, name, key):
    r = api.post(
        "/mock/spaces",
        json={"projectId": pid, "name": name, "spaceKey": key},
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"space create failed: {r.text[:300]}"
    return r.json()["data"]


def _publish(api, space_id, version):
    r = api.post(
        f"/mock/spaces/{space_id}/transition",
        json={"targetStatus": 1, "expectedVersion": version},
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"publish failed: {r.text[:300]}"


def test_t359b_docs_journey_paste_curl_and_card(logged_in, collectors, api_client, uniq):
    """Paste-curl fast create -> doc card renders the rule -> reload keeps it."""
    page = logged_in
    pid = _mk_project(api_client, f"DocsE2E {uniq}", f"doc{uniq}")
    space_key = f"doc{uniq}".lower()
    space_id = _mk_space(api_client, pid, f"docs-space-{uniq}", space_key)
    _publish(api_client, space_id, 0)
    try:
        page.goto(_wb(space_id, pid), wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)

        # : the deep link lands in the global header context. Freshly
        # created project = id-fallback (#pid) until the snapshot refreshes.
        expect(page.locator('[data-test="project-ctx-trigger"]')).to_contain_text(
            str(pid), timeout=10000)

        # honest empty state before the paste runs
        expect(page.locator('[data-test="mock-docs-empty"]')).to_be_visible(timeout=10000)

        # paste-a-curl: query params and the path both land as matcher skeleton
        box = page.locator('[data-test="mock-docs-curl-box"]')
        expect(box).to_be_visible(timeout=5000)
        box.locator('[data-test="mock-docs-curl-input"]').fill(
            f"curl -s 'http://localhost:18086/mocks/{space_key}/api/ping?page=2'"
        )
        preview = page.locator('[data-test="mock-docs-curl-preview"]')
        expect(preview).to_contain_text("/api/ping", timeout=5000)

        page.locator('[data-test="mock-docs-curl-create"]').click()
        card = page.locator('[data-test="mock-docs-card"]')
        expect(card).to_have_count(1, timeout=10000)
        expect(card).to_contain_text("GET", timeout=5000)
        expect(card).to_contain_text("/api/ping", timeout=5000)
        # matcher + response summaries render as plain words, not raw JSON dumps
        expect(card.locator('[data-test="mock-docs-card-matchers"]')).to_contain_text(
            "page", timeout=5000)
        expect(card.locator('[data-test="mock-docs-card-response"]')).to_contain_text(
            "200", timeout=5000)
        # the composed URL quotes the serving supply gate verbatim
        expect(card).to_contain_text(
            f"http://localhost:18086/mocks/{space_key}/api/ping", timeout=5000)

        # reload keeps truth: the stub row lives in the DB
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-card"]')).to_have_count(1, timeout=10000)

        rows = sql_query(
            f"SELECT matchers_json::text, response_json::text FROM tp_mock_stub "
            f"WHERE space_id = {space_id}")
        assert "page" in rows, f"matcher row not persisted: {rows[:200]}"

        assert_no_errors(collectors, "T359b mock docs journey")
    finally:
        sql_query(f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}")
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
        api_client.delete(f"/projects/{pid}/purge")


def test_t359b_docs_response_sample_block(logged_in, collectors, api_client, uniq):
    """A pasted HTTP response block becomes the documented static response."""
    page = logged_in
    pid = _mk_project(api_client, f"DocsE2E2 {uniq}", f"dc2{uniq}")
    space_key = f"dc2{uniq}".lower()
    space_id = _mk_space(api_client, pid, f"docs2-space-{uniq}", space_key)
    _publish(api_client, space_id, 0)
    try:
        page.goto(_wb(space_id, pid), wait_until="domcontentloaded")
        box = page.locator('[data-test="mock-docs-curl-box"]')
        expect(box).to_be_visible(timeout=15000)
        box.locator('[data-test="mock-docs-curl-input"]').fill(
            "\n".join([
                f"curl -X POST http://localhost:18086/mocks/{space_key}/api/order",
                "HTTP/1.1 201 Created",
                "Content-Type: application/json",
                "",
                '{"orderId":42}',
            ])
        )
        preview = page.locator('[data-test="mock-docs-curl-preview"]')
        expect(preview).to_contain_text("POST", timeout=5000)
        page.locator('[data-test="mock-docs-curl-create"]').click()
        card = page.locator('[data-test="mock-docs-card"]')
        expect(card).to_have_count(1, timeout=10000)
        expect(card.locator('[data-test="mock-docs-card-response"]')).to_contain_text(
            "201", timeout=5000)
        expect(card.locator('[data-test="mock-docs-card-response"]')).to_contain_text(
            "orderId", timeout=5000)

        body = sql_query(
            f"SELECT response_json::text FROM tp_mock_stub WHERE space_id = {space_id}")
        doc = json.loads(body.strip().split("\n")[-1])
        assert doc["status"] == 201 and "orderId" in doc["body"], f"payload drift: {body[:200]}"

        assert_no_errors(collectors, "T359b response sample")
    finally:
        sql_query(f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}")
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
        api_client.delete(f"/projects/{pid}/purge")


def test_t359b_docs_help_anchor_and_menu(logged_in, collectors):
    """Mock help anchors to the API-mocking chapter; the group shows two leaves.

 AN-4: the docs leaf retired into the workbench — a bare /mock/docs
 bookmark lands on the spaces list (tab intent carried). R23 stage-2: the
 page-side help icon retired with the title shell — the single topbar dot
 (data-test=header-help-btn) carries the chapter anchor; the group lists
 only the survivors.
 """
    page = logged_in
    page.goto(DOCS_URL, wait_until="domcontentloaded")
    expect(page).to_have_url(re.compile(r"/#/mock/spaces\?"), timeout=15000)
    expect(page.locator('[data-test="header-help-btn"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="header-help-btn"]').first.click()
    drawer = page.locator('[data-test="help-drawer"]')
    expect(drawer).to_be_visible(timeout=8000)
    title = drawer.locator("h2.help-doc-title").first
    # the spaces-list icon anchors to its own chapter (V269 domain-T set)
    expect(title).to_contain_text("仿真空间", timeout=8000)
    page.keyboard.press("Escape")

    group = page.locator('[data-test="group-mock"]')
    expect(group).to_be_visible(timeout=10000)
    leaf = page.locator('[data-test="menu-mock-spaces"]')
    # the group opens on click but re-clicking toggles it closed — expand only
    # when the leaf is not already visible (landing on this route usually
    # auto-opens its group)
    if not leaf.is_visible():
        group.locator("> .el-sub-menu__title").first.click()
    expect(leaf).to_be_visible(timeout=8000)
    expect(page.locator('[data-test="menu-mock-docs"]')).to_have_count(0)
    assert_no_errors(collectors, "T359b docs help anchor + menu leaf")
