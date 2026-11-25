# -*- coding: utf-8 -*-

import json
import re

import httpx
from playwright.sync_api import expect

from conftest import (API_BASE, BASE_URL, assert_no_errors,
                      open_apitest_datasource_item, sql_query)

SPEC = {
    "openapi": "3.0.1",
    "info": {"title": "E2E订单契约直出", "version": "1.0.0"},
    "paths": {
        "/orders/{id}": {
            "get": {
                "operationId": "getOrder",
                "summary": "查询订单",
                "responses": {"200": {"description": "ok", "content": {
                    "application/json": {"schema": {"type": "object", "properties": {
                        "orderId": {"type": "string"}}}}}}},
            },
            "trace": {"operationId": "ping", "responses": {"200": {"description": "ok"}}},
        },
        "/orders": {
            "post": {"operationId": "createOrder", "summary": "创建订单",
                     "responses": {"201": {"description": "created", "content": {
                         "application/json": {"schema": {"type": "object", "properties": {
                             "id": {"type": "integer"}}}}}}}},
        },
    },
}


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project failed: {r.text[:300]}"
    return r.json()["data"]


def _upload_spec(api, pid, name, spec):
    """Real multipart upload (admin -> core channel A parse) — no UI dependency here."""
    r = httpx.post(
        f"{API_BASE}/api-specs",
        params={"projectId": pid, "name": name},
        files={"file": (f"{name}.json", json.dumps(spec, ensure_ascii=False), "application/json")},
        headers=api.headers,
        timeout=30,
    )
    body = r.json()
    assert body.get("code") == "0", f"spec upload failed: {r.text[:300]}"
    return body["data"]


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={"projectId": pid, "name": name, "spaceKey": key})
    assert r.status_code == 200 and r.json().get("code") == "0", f"space failed: {r.text[:300]}"
    return r.json()["data"]


def _publish(api, space_id):
    r = api.post(f"/mock/spaces/{space_id}/transition",
                 json={"targetStatus": 1, "expectedVersion": 0})
    assert r.status_code == 200 and r.json().get("code") == "0", f"publish failed: {r.text[:300]}"


def _stub_paths(api, space_id):
    r = api.get(f"/mock/spaces/{space_id}/stubs")
    body = r.json()
    assert str(body.get("code")) == "0", f"stub list failed: {r.text[:300]}"
    return body["data"]


def _open_wizard(page, pid, space_id):
    """Deep-link into api-test with the project, open the wizard, pick spec+space."""
    page.goto(f"{BASE_URL}/#/api-test?projectId={pid}", wait_until="domcontentloaded")
    # IA: the contract-to-mock door lives in the rail datasource dropdown —
    # the item is only reachable after the trigger is opened (public helper).
    open_apitest_datasource_item(page, "apitest-stub-direct-btn")
    dialog = page.locator('[data-test="stub-direct-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    # step 1: pick the contract row (radio in the table) and the space
    dialog.locator('[data-test="stub-direct-spec-table"] tbody tr').first.click()
    select = dialog.locator('[data-test="stub-direct-space-select"]')
    select.click()
    option = page.locator(".el-select-dropdown__item").filter(has_text=f"e2e-t360c-space")
    expect(option.first).to_be_visible(timeout=5000)
    option.first.click()
    preview = dialog.locator('[data-test="stub-direct-preview-btn"]')
    expect(preview).to_be_enabled(timeout=5000)
    preview.click()
    return dialog


def test_t360c_stub_direct_full_journey(logged_in, collectors, api_client, uniq):
    """Wizard journey: preview & select -> write -> DB reconcile -> idempotent re-run -> docs deep-link."""
    page = logged_in
    pid = _mk_project(api_client, f"T360C {uniq}", f"t360c{uniq[:6]}")
    spec = _upload_spec(api_client, pid, f"t360c-spec-{uniq}", SPEC)
    assert len(spec["endpoints"]) == 3  # GET+POST served; mapper itself drops TRACE later
    space_id = _mk_space(api_client, pid, f"e2e-t360c-space-{uniq}", f"t360c{uniq[:6]}")
    _publish(api_client, space_id)

    try:
        dialog = _open_wizard(page, pid, space_id)

        # step 2: two served endpoints as draft cards (TRACE never mapped)
        cards = dialog.locator('[data-test="stub-direct-card"]')
        expect(cards).to_have_count(2, timeout=10000)
        texts = cards.all_inner_texts()
        joined = " | ".join(texts)
        assert "/orders/{id}" in joined, f"template path card missing: {joined[:300]}"
        assert "TRACE" not in joined, f"unserved verb leaked into drafts: {joined[:300]}"
        expect(dialog.locator('[data-test="stub-direct-source-tag"]').first).to_be_visible()

        # uncheck the template one -> only POST /orders gets written
        cards.filter(has_text="/orders/{id}").locator(".el-checkbox").first.click()
        import_btn = dialog.locator('[data-test="stub-direct-import-btn"]')
        expect(import_btn).to_be_enabled(timeout=5000)
        import_btn.click()

        # step 3: honest created count
        done = dialog.locator('[data-test="stub-direct-done-created"]')
        expect(done).to_be_visible(timeout=15000)
        assert "1" in (done.text_content() or ""), f"created text: {done.text_content()}"
        skipped_note = dialog.locator('[data-test="stub-direct-done-skipped"]')
        expect(skipped_note).to_have_count(0)

        # persistence via the real stub list API (save chain, provenance kept)
        stubs = _stub_paths(api_client, space_id)
        assert len(stubs) == 1, f"expected 1 stub, got {len(stubs)}"
        assert stubs[0]["method"] == "POST" and stubs[0]["pathPattern"] == "/orders"
        assert stubs[0]["source"] == "openapi" and stubs[0]["sourceRef"] == f"spec:{spec['id']}"

        # Idempotence: re-open. Rows already covered sit outside the advice
        # set, so the default selection is only the missing endpoint; the
        # journey opts into the overwrite run explicitly via check-all.
        dialog.locator('[data-test="stub-direct-close-btn"]').click()
        dialog2 = _open_wizard(page, pid, space_id)
        expect(dialog2.locator('[data-test="stub-direct-card"]')).to_have_count(2, timeout=10000)
        dialog2.locator('[data-test="stub-direct-check-all"]').click()
        import2 = dialog2.locator('[data-test="stub-direct-import-btn"]')
        expect(import2).to_be_enabled(timeout=5000)
        import2.click()
        done2 = dialog2.locator('[data-test="stub-direct-done-created"]')
        expect(done2).to_be_visible(timeout=15000)
        assert "1" in (done2.text_content() or ""), f"second run created: {done2.text_content()}"
        skip2 = dialog2.locator('[data-test="stub-direct-done-skipped"]')
        expect(skip2).to_be_visible(timeout=5000)
        stubs2 = _stub_paths(api_client, space_id)
        assert len(stubs2) == 2, f"re-import should add exactly the missing one, got {len(stubs2)}"

        # docs deep-link door from step 3 — AN-4: the wizard pushes the
        # workbench tab URL (projectId + tab, space id in the path)
        dialog2.locator('[data-test="stub-direct-view-docs"]').click()
        expect(page).to_have_url(
            re.compile(r"/#/mock/spaces/\d+\?projectId=\d+(&|$)"), timeout=15000)
        assert "tab=docs" in page.url, page.url
        docs_page = page.locator('[data-test="mock-docs-page"]')
        expect(docs_page).to_be_visible(timeout=10000)
        # the space was pre-filtered through the query deep-link; both rules
        # render as doc cards carrying the contract-direct origin badge
        expect(page.get_by_text("契约直出").first).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "T360c stub-direct wizard journey")
    finally:
        sql_query(f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}")
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
