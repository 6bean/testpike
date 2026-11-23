""" E2E: final-effective preview inside the request editor.

Faces under test (≤2-click reach from the rail): a saved request of a collection
that carries default headers + bearer auth defaults opens its editor with the
preview toggle enabled; expanding it renders the core-resolver verdict as chips
(request-owned row vs collection-inherited row, auth inherited with the owning
set name echoed). An unsaved new request keeps the toggle disabled with the
plain needs-save hint instead of a local guess. After a full reload the toggle
refetches from the server (no stale in-memory fake).

Chain: real API seed -> header project context -> rail collection card ->
request row (auto-opens) -> toggle -> chip assertions -> reload -> reopen ->
DB-side already covered by L3; cleanup via project purge.

Known traps: a) inject_token AFTER seeding (auth /me snapshot); b) EP dropdown
anchors are text-based per house pattern; c) el-dialog leftover DOM — use
visibility (offsetParent) semantics via expect(...) which auto-waits visible.
"""
import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    pick_apitest_collection,
    pick_project_in_header,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)

COLL_HEADERS = '[{"name":"X-T412-Trace","value":"on"}]'
REQ_HEADERS = '[{"name":"X-T412-Own","value":"from-request"}]'


def _seed(api_client, uniq):
    r = api_client.post("/projects", json={
        "name": f"T412c预览项目{uniq}", "code": f"T412C{uniq[:6].upper()}", "tags": ""})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    data = r.json()["data"]
    pid = data["id"] if isinstance(data, dict) else data

    r = api_client.post("/apitest/collections", json={
        "projectId": pid, "parentId": 0, "name": f"T412c预览接口集{uniq}", "tags": "",
        "headersJson": COLL_HEADERS,
        "authJson": '{"type":"bearer","token":"@cred/t412c-e2e"}'})
    assert r.json().get("code") == "0", f"建接口集失败: {r.text[:300]}"
    cid = r.json()["data"]["id"]

    r = api_client.post("/apitest/requests", json={
        "collectionId": cid, "name": f"T412c预览请求{uniq}", "method": "GET",
        "protocol": "http", "urlPath": "/ping", "headersJson": REQ_HEADERS,
        "bodyText": "", "authJson": "{}", "assertionsJson": "[]"})
    assert r.json().get("code") == "0", f"建请求失败: {r.text[:300]}"
    return pid, cid


def _open_request(page, uniq):
    """Landing -> header context -> collection card (auto-opens first request)."""
    page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
    pick_project_in_header(page, f"T412c预览项目{uniq}")
    pick_apitest_collection(page, f"T412c预览接口集{uniq}")


def _reload_landing(page, uniq):
    """Full F5-equivalent: same-hash goto is a no-op for the SPA router, so a
    real reload is the only faithful way back to the auto-open state."""
    page.reload(wait_until="domcontentloaded")
    pick_project_in_header(page, f"T412c预览项目{uniq}")
    pick_apitest_collection(page, f"T412c预览接口集{uniq}")


def _assert_preview_rows(page, uniq):
    toggle = page.locator('[data-test="apitest-effective-toggle"]')
    expect(toggle).to_be_enabled(timeout=10000)
    toggle.click()

    own = page.locator('[data-test="apitest-effective-header-row"]').filter(
        has_text="X-T412-Own").first
    expect(own).to_be_visible(timeout=10000)
    expect(own.locator('[data-test="apitest-effective-source-chip"]')).to_contain_text("请求自身")
    expect(own.locator(".ap-effective-value")).to_have_text("from-request")

    inherited = page.locator('[data-test="apitest-effective-header-row"]').filter(
        has_text="X-T412-Trace").first
    expect(inherited).to_be_visible()
    expect(inherited.locator('[data-test="apitest-effective-source-chip"]')).to_contain_text("接口集默认")

    expect(page.locator('[data-test="apitest-effective-auth-chip"]')).to_contain_text("继承自接口集")
    expect(page.locator('[data-test="apitest-effective-auth"]')).to_contain_text(
        f"默认值来自接口集「T412c预览接口集{uniq}」")


def test_t412c_effective_preview_reach_and_persist(page, collectors, api_client, uniq):
    pass
    pid, _cid = _seed(api_client, uniq)
    try:
        inject_token(page)

        # Face 1: saved request — toggle enabled, chips render the core verdict.
        _open_request(page, uniq)
        _assert_preview_rows(page, uniq)

        # Face 2: unsaved new request — disabled toggle + needs-save hint (no fake).
        page.locator('[data-test="apitest-new-request-btn"]').click()
        expect(page.locator('[data-test="apitest-effective-toggle"]')).to_be_disabled()
        expect(page.locator('[data-test="apitest-effective-needs-save"]')).to_be_visible()

        # Face 3: full reload — reopen the saved request and expand again; the
        # refetch must reproduce the same verdict from the server.
        _reload_landing(page, uniq)
        _assert_preview_rows(page, uniq)

        assert_no_errors(collectors, "T412c effective preview")
    finally:
        r = api_client.delete(f"/projects/{pid}/purge?force=true")
        assert r.status_code == 200, f"清理项目失败: {r.text[:200]}"
