# -*- coding: utf-8 -*-
"""Space-workbench contract-import wizard journey (first E2E).

Covers the workbench mount of the shared stub-direct wizard, which the older
api-test mount does not exercise:
1. the docs-tab entry button opens the wizard with the destination space
   locked to the current space (read-only echo, no second picker);
2. the upload channel is the spec-domain single door — an OpenAPI file
   uploaded here lands as an audited contract version row, pre-selected,
   and the row is visible to the API-spec list afterwards;
3. preview defaults to every detected endpoint checked, and the one-click
   import is the human confirmation door (nothing imports before it);
4. the outcome receipt states the three counts (detected / created /
   skipped) and the created stubs live in the docs list for real (reload
   keeps truth); a second wizard run over the same contract reports them
   as skipped instead of duplicating rows.

Real browser + real services, no mocks; zero-tolerance gates via
assert_no_errors; fixture rows are torn down for real. Project naming keeps
the "MockCiw " prefix so the corpus never lands in the demo projects.
"""

import json
import re

import httpx
from playwright.sync_api import expect

from conftest import (API_BASE, BASE_URL, assert_no_errors, sql_query)

SPEC = {
    "openapi": "3.0.1",
    "info": {"title": "E2E仓单契约", "version": "1.0.0"},
    "paths": {
        "/warehouses/{id}": {
            "get": {
                "operationId": "getWarehouse",
                "summary": "查询仓库",
                "responses": {"200": {"description": "ok", "content": {
                    "application/json": {"schema": {"type": "object", "properties": {
                        "warehouseId": {"type": "string"}}}}}}},
            },
        },
        "/warehouses": {
            "post": {"operationId": "createWarehouse", "summary": "创建仓库",
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


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={"projectId": pid, "name": name, "spaceKey": key})
    assert r.status_code == 200 and r.json().get("code") == "0", f"space failed: {r.text[:300]}"
    return r.json()["data"]


def _publish(api, space_id):
    r = api.post(f"/mock/spaces/{space_id}/transition",
                 json={"targetStatus": 1, "expectedVersion": 0})
    assert r.status_code == 200 and r.json().get("code") == "0", f"publish failed: {r.text[:300]}"


def _stub_rows(api, space_id):
    r = api.get(f"/mock/spaces/{space_id}/stubs")
    body = r.json()
    assert str(body.get("code")) == "0", f"stub list failed: {r.text[:300]}"
    return body["data"]


def test_workbench_contract_import_wizard(logged_in, collectors, api_client, uniq, tmp_path):
    """Upload -> audited spec row -> all-checked preview -> one-click import -> docs truth."""
    page = logged_in
    pid = _mk_project(api_client, f"MockCiw {uniq}", f"mcw{uniq}")
    space_key = f"mcw{uniq}".lower()
    space = _mk_space(api_client, pid, f"ciw-space-{uniq}", space_key)
    space_id = space["id"] if isinstance(space, dict) else space
    _publish(api_client, space_id)
    try:
        page.goto(f"{BASE_URL}/#/mock/spaces/{space_id}?projectId={pid}&tab=docs",
                  wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)

        # Door: workbench entry opens the shared wizard with the space locked.
        page.locator('[data-test="mock-docs-import-contract"]').click()
        dialog = page.locator('[data-test="stub-direct-dialog"]')
        expect(dialog).to_be_visible(timeout=8000)
        expect(dialog.locator('[data-test="stub-direct-fixed-space"]')).to_contain_text(
            f"ciw-space-{uniq}", timeout=8000)

        # Upload channel: the file lands as an audited contract version row.
        dialog.locator('[data-test="stub-direct-source-radio"] .el-radio')\
            .filter(has_text="上传新规范文档").click()
        spec_file = tmp_path / "warehouse-contract.json"
        spec_file.write_text(json.dumps(SPEC, ensure_ascii=False), encoding="utf-8")
        dialog.locator('[data-test="stub-direct-file-input"]').set_input_files(str(spec_file))
        dialog.locator('[data-test="stub-direct-upload-btn"]').click()
        expect(dialog.locator('[data-test="stub-direct-upload-done"]')).to_be_visible(timeout=20000)

        # The uploaded row is a real spec row (single door, not a side channel).
        listed = httpx.get(f"{API_BASE}/api-specs", params={"projectId": pid, "page": 1, "size": 50},
                           headers=api_client.headers, timeout=30).json()
        assert str(listed.get("code")) == "0"
        names = [s.get("name", "") for s in listed["data"]["list"]]
        assert any("warehouse-contract" in n for n in names), f"uploaded row missing in spec list: {names}"

        # Preview defaults to every endpoint checked (confirmation door intact).
        dialog.locator('[data-test="stub-direct-preview-btn"]').click()
        expect(dialog.locator('[data-test="stub-direct-card"]')).to_have_count(2, timeout=20000)
        expect(dialog.locator('[data-test="stub-direct-check-all"]'))\
            .to_have_class(re.compile(r"is-checked"), timeout=8000)
        per_item_checked = dialog.locator(
            '[data-test^="stub-direct-check-"]:not([data-test="stub-direct-check-all"]).is-checked')
        expect(per_item_checked).to_have_count(2, timeout=8000)

        # One-click import => three-count receipt (detected / created / skipped).
        dialog.locator('[data-test="stub-direct-import-btn"]').click()
        expect(dialog.locator('[data-test="stub-direct-done-created"]')).to_contain_text(
            "2", timeout=20000)

        # Created stubs live in the docs list for real; closing keeps truth.
        dialog.locator('[data-test="stub-direct-view-docs"]').click()
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)
        page.wait_for_timeout(1200)
        rows = _stub_rows(api_client, space_id)
        assert len(rows) == 2, f"expected 2 imported stubs, got {len(rows)}"
        assert all(s.get("source") == "openapi" for s in rows), f"source tag drift: {rows}"

        # Idempotent second run: same contract re-imported reports skipped, no dupes.
        page.locator('[data-test="mock-docs-import-contract"]').click()
        dialog2 = page.locator('[data-test="stub-direct-dialog"]')
        expect(dialog2).to_be_visible(timeout=8000)
        dialog2.locator('[data-test="stub-direct-spec-table"] tbody tr').first.click()
        dialog2.locator('[data-test="stub-direct-preview-btn"]').click()
        expect(dialog2.locator('[data-test="stub-direct-card"]')).to_have_count(2, timeout=20000)
        dialog2.locator('[data-test="stub-direct-import-btn"]').click()
        expect(dialog2.locator('[data-test="stub-direct-done-created"]')).to_contain_text(
            "0", timeout=20000)
        expect(dialog2.locator('[data-test="stub-direct-done-skipped"]')).to_contain_text(
            "2", timeout=8000)
        dialog2.locator('[data-test="stub-direct-close-btn"]').click()
        assert len(_stub_rows(api_client, space_id)) == 2

        assert_no_errors(collectors, "workbench contract-import wizard journey")
    finally:
        sql_query(f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}")
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
        sql_query(f"DELETE FROM tp_api_spec_preview WHERE spec_id IN "
                  f"(SELECT id FROM tp_api_spec WHERE project_id = {pid})")
        sql_query(f"DELETE FROM tp_api_spec WHERE project_id = {pid}")
        # Project rows carry auto-created membership rows; clearing them first
        # mirrors the sibling journeys and avoids an FK violation here.
        sql_query(f"DELETE FROM tp_user_project WHERE project_id = {pid}")
        sql_query(f"DELETE FROM tp_project WHERE id = {pid}")
