# -*- coding: utf-8 -*-

import json as _json
import re

import httpx
from playwright.sync_api import expect

from conftest import (API_BASE, BASE_URL, ADMIN_PASSWORD, ADMIN_USERNAME,
                      api_login, assert_no_errors)

QUEUE_URL = f"{BASE_URL}/#/api-spec-change-analysis"
INVENTORY_URL = f"{BASE_URL}/#/api-inventory"
HEALTH_PATH = "/e2e-sq/health"
PENDING_ROWS = 32  # 33 uploads, the first lands no diff row; each later one adds exactly one


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def _doc(n_endpoints, drop_health=False):
    """Hand-built OpenAPI contract; drop_health removes /e2e-sq/health so the
    latest pair carries one honest removed endpoint."""
    paths = {}
    for i in range(n_endpoints):
        paths[f"/e2e-sq/res{i}/{{id}}"] = {
            "get": {
                "operationId": f"getSqRes{i}",
                "summary": f"E2E queue resource {i}",
                "responses": {"200": {"description": "ok"}},
            }
        }
    if not drop_health:
        paths["/e2e-sq/health"] = {
            "get": {"operationId": "sqHealth", "summary": "health probe",
                    "responses": {"200": {"description": "ok"}}}
        }
    return {
        "openapi": "3.0.1",
        "info": {"title": "E2E Queue Contract", "version": "1.0.0"},
        "paths": paths,
    }


def _upload(api, pid, name, doc):
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


def test_t371s4b_queue_page_journey(logged_in, collectors, api_client, uniq):
    """31-row worst-case queue + hint chip + removed jump + disposition reload."""
    page = logged_in
    pid = _mk_project(api_client, f"E2E-specqueue-{uniq}", f"sq{uniq[:9]}")
    spec_name = f"sqspec-{uniq}"

    # ── worst-case fixture: 34 contract uploads = 33 pending diff rows (>=30
    #    rows, display scale iron rule); the LAST upload drops /e2e-sq/health so
    #    the newest row carries one honest removed endpoint. The request+promote
    #    seed runs BEFORE the drop upload: impact lists close at analysis time,
    #    so the case must exist when the removed diff is computed.
    _upload(api_client, pid, spec_name, _doc(3))
    for k in range(4, 35):
        _upload(api_client, pid, spec_name, _doc(k))

    # ── seed the removed-endpoint impact through the real promote door ──
    col = api_client.post("/apitest/collections", json={
        "projectId": pid, "parentId": 0, "name": f"queue集{uniq}", "tags": ""})
    assert col.json().get("code") == "0", f"collection seed failed: {col.text[:300]}"
    col_id = col.json()["data"]["id"]
    rq = api_client.post("/apitest/requests", json={
        "collectionId": col_id, "name": f"健康探测 {HEALTH_PATH} 回归请求", "method": "GET",
        "urlPath": HEALTH_PATH, "headersJson": "[]", "bodyText": "",
        "authJson": "{}",
        "assertionsJson": '[{"type":"status","op":"equals","expect":200}]'})
    assert rq.json().get("code") == "0", f"request seed failed: {rq.text[:300]}"
    req_id = rq.json()["data"]["id"]
    pm = api_client.post(f"/apitest/requests/{req_id}/promote", json={})
    assert pm.json().get("code") == "0", f"promote failed: {pm.text[:300]}"

    # last upload drops the health endpoint (res set unchanged: 34 res paths)
    # -> newest row = exactly one removed diff with a closed impact list
    # (1 request + 1 promoted case, both through real doors)
    _upload(api_client, pid, spec_name, _doc(34, drop_health=True))

    # ── door 1: queue page under the worst-case fixture (paged, truthful) ──
    page.goto(f"{QUEUE_URL}?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="spec-change-page"]')).to_be_visible(timeout=15000)
    table = page.locator('[data-test="spec-change-table"]')
    expect(table).to_be_visible(timeout=15000)
    # 33 pending pairs total; page 1 shows at most the 20-window
    expect(page.locator('[data-test="spec-change-pagination"]')).to_be_visible(timeout=10000)
    expect(table.locator("tbody tr")).to_have_count(20, timeout=15000)
    expect(page.locator(".el-pagination__total")).to_contain_text(str(PENDING_ROWS), timeout=10000)
    # page 2 carries the remainder (server-side segment, not a client re-count)
    page.locator('[data-test="spec-change-pagination"] button.btn-next').click()
    expect(table.locator("tbody tr")).to_have_count(PENDING_ROWS - 20, timeout=15000)
    # back to page 1 through the pager itself (a same-URL hash goto would not
    # reload the SPA document and the window would silently stay on page 2)
    page.locator('[data-test="spec-change-pagination"] button.btn-prev').click()
    expect(table.locator("tbody tr")).to_have_count(20, timeout=15000)
    # newest pair first (ORDER BY id DESC): the health-dropping upload is the
    # top row and its removed impact is honest (1 case promoted via real door)
    top_row = table.locator("tbody tr").first
    expect(top_row.locator('[data-test="spec-change-count"]')).to_have_text("1", timeout=10000)

    # ── door 2: expand top row → removed hint + manual-batch jump ──
    top_row.locator("td .el-table__expand-icon").click()
    detail = page.locator('[data-test="spec-change-items"]')
    expect(detail).to_be_visible(timeout=10000)
    removed_row = detail.locator("tbody tr").filter(has_text="/e2e-sq/health").first
    expect(removed_row).to_be_visible(timeout=10000)
    expect(removed_row.locator('[data-test="spec-change-casecount"]')).to_have_text("1", timeout=10000)
    hint = removed_row.locator('[data-test="spec-change-removed-hint"]')
    expect(hint).to_contain_text("不自动建批", timeout=10000)

    removed_row.locator('[data-test="spec-change-goto-cases"]').click()
    expect(page).to_have_url(
        re.compile(r"/#/cases\?projectId=" + str(pid) + r"&keyword="), timeout=10000)
    kw_box = page.locator('[data-test="case-filter-keyword"]')
    expect(kw_box).to_have_value("/e2e-sq/health", timeout=10000)
    case_table = page.locator('[data-test="case-table"]')
    expect(case_table.locator("tbody tr")).to_have_count(1, timeout=15000)
    # the row title is the seeded request name (with the endpoint path inside);
    # assert on the seeded literal, not a shortened paraphrase of it
    expect(case_table).to_contain_text(f"健康探测 {HEALTH_PATH} 回归请求", timeout=10000)

    # ── door 3: inventory hint chip counts the WHOLE queue (33), not the window ──
    page.goto(f"{INVENTORY_URL}?projectId={pid}", wait_until="domcontentloaded")
    chip = page.locator('[data-test="api-inventory-review-chip"]')
    expect(chip).to_be_visible(timeout=15000)
    expect(chip).to_contain_text(str(PENDING_ROWS), timeout=10000)

    # ── door 4: disposition — blank note gates the button, note lands a trace ──
    page.goto(f"{QUEUE_URL}?projectId={pid}", wait_until="domcontentloaded")
    first_pending = page.locator('[data-test="spec-change-table"] tbody tr').filter(
        has_text="待复核").first
    first_pending.locator('[data-test="spec-change-handle-btn"]').click()
    dialog = page.locator('[data-test="spec-change-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)
    confirm = dialog.locator('[data-test="spec-change-dialog-confirm"]')
    expect(confirm).to_be_disabled()
    dialog.locator('[data-test="spec-change-note-input"]').fill("E2E 复核：端点差异已知悉")
    expect(confirm).to_be_enabled()
    confirm.click()
    expect(page.locator(".el-message--success")).to_be_visible(timeout=10000)

    # reload persistence: the disposition is a stored trace, not page state
    page.goto(f"{QUEUE_URL}?projectId={pid}", wait_until="domcontentloaded")
    handled_row = page.locator('[data-test="spec-change-table"] tbody tr').filter(
        has_text="E2E 复核：端点差异已知悉").first
    expect(handled_row).to_be_visible(timeout=15000)
    expect(handled_row.locator('[data-test="spec-change-handled"]')).to_be_visible(timeout=10000)

    # ── door 5: clear the queue → the inventory hint chip hides itself ──
    token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)
    hdr = {"Authorization": f"Bearer {token}"}
    while True:
        st = httpx.get(f"{API_BASE}/api-spec-change-analyses",
                       params={"projectId": pid, "status": 0, "page": 1, "size": 100},
                       headers=hdr, timeout=30)
        rows = (st.json().get("data") or {}).get("list") or []
        if not rows:
            break
        ids = [r["id"] for r in rows]
        hb = httpx.post(f"{API_BASE}/api-spec-change-analyses/handle-batch",
                        json={"ids": ids, "note": "E2E 清账批量处置"},
                        headers=hdr, timeout=30)
        assert hb.json().get("code") == "0", f"batch clear failed: {hb.text[:300]}"
    page.goto(f"{INVENTORY_URL}?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="api-inventory-page"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="api-inventory-review-chip"]')).to_have_count(0, timeout=15000)

    assert_no_errors(collectors, "T371 queue page journey")

    # teardown: rows die with the project segment
    api_client.delete(f"/projects/{pid}")
