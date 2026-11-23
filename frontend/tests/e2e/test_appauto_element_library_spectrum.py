"""Element-library page spectrum (Playwright real browser + real backend, no mocks).

Covers the P1 element-library management page end to end under the worst-scale
floor (30 rows > default page size 20), the URL page-state contract
(keyword/locatorType/page/pageSize round-trip across reload) and full CRUD
persistence verified after reload:
1. Deep link lands the project segment; page 1 renders 20 rows, page 2 renders 10.
2. Pagination state is restored straight from the URL after a reload.
3. The type filter drives a real row-set change and survives reload.
4. Create through the dialog -> reload -> row persists (DOM and API double-check).
5. Rename via the edit dialog -> reload -> persists; delete via the confirm box
 -> disappears from both DOM and the paged API.
6. Three-class zero tolerance: 4xx/5xx, console error/warning and browser Issues
 panel are asserted by assert_no_errors (collectors mounts issues_monitor).

Data isolation: every name carries a uuid suffix (xdist-safe); the scratch
project and all elements are purged in teardown through the real API.
"""
import uuid

import httpx
import pytest
from conftest import (ADMIN_PASSWORD, API_BASE, BASE_URL, api_login,
                      assert_no_errors)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(420)

PAGE = "[data-test='element-library-page']"
ROWS_SEL = "[data-test='element-table'] .el-table__body tbody tr"


def _token_headers():
    return {"Authorization": f"Bearer {api_login('admin', ADMIN_PASSWORD)}"}


def _inject_token(page, token):
    """Real login + token injection (same landing posture as device-wall)."""
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(
        "(t) => { localStorage.setItem('tp_access_token', t); "
        "localStorage.setItem('tp_onboarding_dismissed', '1'); }", token)
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator("[data-test='my-project-table']").first).to_be_visible(timeout=15000)


def _list_count(pid, h, keyword=None, locator_type=None):
    params = {"projectId": pid, "page": 1, "pageSize": 100}
    if keyword:
        params["keyword"] = keyword
    if locator_type:
        params["locatorType"] = locator_type
    r = httpx.get(f"{API_BASE}/appauto/elements", params=params, headers=h, timeout=10)
    body = r.json().get("data") or {}
    return body.get("list") or []


def test_element_library_full_spectrum(page, collectors, uniq):
    """Full spectrum: worst scale, URL state restore, filter, CRUD persistence."""
    h = _token_headers()

    # -- arrange: real-API scratch project + 30 elements (worst-scale floor) --
    r = httpx.post(f"{API_BASE}/projects",
                   json={"name": f"elem-spec-{uniq}", "code": f"ELM{uniq[:6].upper()}"},
                   headers=h, timeout=10)
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    data = r.json()["data"]
    pid = data["id"] if isinstance(data, dict) else data

    seeded = []
    try:
        i = 0
        for kind, n in (("IMAGE", 20), ("OCR", 10)):
            for _ in range(n):
                name = f"E{uniq}-{i:02d}"
                cr = httpx.post(f"{API_BASE}/appauto/elements",
                                params={"projectId": pid},
                                json={"name": name, "locatorType": kind,
                                      "locatorConfig": {"source": kind},
                                      "confidenceThreshold": 0.85},
                                headers=h, timeout=10)
                assert cr.status_code == 200 and cr.json().get("code") == "0", cr.text[:300]
                seeded.append(cr.json()["data"])
                i += 1

        _inject_token(page, h["Authorization"].split(" ", 1)[1])
        page.goto(f"{BASE_URL}/#/appauto/elements?projectId={pid}",
                  wait_until="domcontentloaded")
        expect(page.locator(PAGE)).to_be_visible(timeout=15000)

        # -- 1. worst-scale pagination: 20 rows on page 1, 10 on page 2 ------
        expect(page.locator(ROWS_SEL)).to_have_count(20, timeout=10000)
        expect(page.locator("[data-test='element-pagination']")).to_be_visible()
        page.locator("[data-test='element-pagination'] .btn-next").first.click()
        expect(page.locator(ROWS_SEL)).to_have_count(10, timeout=10000)
        assert "page=2" in page.url, f"page state missing from URL: {page.url}"

        # -- 2. reload lands the same segment straight from the URL ------------
        deep = page.url
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.goto(deep, wait_until="domcontentloaded")
        expect(page.locator(ROWS_SEL)).to_have_count(10, timeout=10000)

        # -- 3. type filter narrows to the real OCR set and survives reload --
        page.locator("[data-test='element-keyword']").fill(f"E{uniq}-")
        page.locator("[data-test='element-type-filter']").click()
        page.locator(".el-select-dropdown__item", has_text="OCR").last.click()
        page.locator("[data-test='element-query-btn']").click()
        expect(page.locator(ROWS_SEL)).to_have_count(10, timeout=10000)
        assert "locatorType=OCR" in page.url, f"filter missing from URL: {page.url}"
        filt_url = page.url
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.goto(filt_url, wait_until="domcontentloaded")
        expect(page.locator("[data-test='element-type-filter']"))\
            .to_contain_text("OCR", timeout=10000)
        expect(page.locator(ROWS_SEL)).to_have_count(10, timeout=10000)

        # -- 4. create through the dialog, then reload persistence -----------
        page.locator("[data-test='element-reset-btn']").click()
        expect(page.locator(ROWS_SEL)).to_have_count(20, timeout=10000)
        new_name = f"Created{uniq}"
        page.locator("[data-test='element-create-btn']").click()
        expect(page.locator("[data-test='element-form-dialog']")).to_be_visible()
        page.locator("[data-test='element-form-name']").fill(new_name)
        page.locator("[data-test='element-form-type']").click()
        page.locator(".el-select-dropdown__item", has_text="COORD").last.click()
        page.locator("[data-test='element-form-config']").fill('{"x": 120, "y": 640}')
        page.locator("[data-test='element-form-confidence'] input").fill("0.9")
        page.locator("[data-test='element-variant-add']").click()
        page.locator("[data-test='element-variant-resolution']").fill("1080x2400")
        page.locator("[data-test='element-variant-artifact']").fill("art-variant-1")
        page.locator("[data-test='element-form-save']").click()
        expect(page.locator(".el-message--success").last).to_be_visible(timeout=8000)

        page.goto(f"{BASE_URL}/#/appauto/elements?projectId={pid}&keyword={new_name}",
                  wait_until="domcontentloaded")
        expect(page.locator("[data-test='element-name']")).to_have_count(1, timeout=10000)
        listed = _list_count(pid, h, keyword=new_name)
        assert len(listed) == 1 and listed[0]["name"] == new_name, str(listed)[:300]
        new_id = listed[0]["id"]

        # -- 5. rename via edit dialog -> reload persists --------------------
        renamed = f"Renamed{uniq}"
        page.locator("[data-test='element-edit-btn']").first.click()
        expect(page.locator("[data-test='element-form-dialog']")).to_be_visible()
        page.locator("[data-test='element-form-name']").fill(renamed)
        page.locator("[data-test='element-form-save']").click()
        expect(page.locator(".el-message--success").last).to_be_visible(timeout=8000)
        page.goto(f"{BASE_URL}/#/appauto/elements?projectId={pid}&keyword={renamed}",
                  wait_until="domcontentloaded")
        expect(page.locator("[data-test='element-name']")).to_have_count(1, timeout=10000)

        # -- 6. delete through the confirm box -> gone from DOM and API ------
        page.locator("[data-test='element-delete-btn']").first.click()
        page.locator(".el-message-box__btns .el-button--primary").click()
        expect(page.locator(".el-message--success").last).to_be_visible(timeout=8000)
        page.goto(f"{BASE_URL}/#/appauto/elements?projectId={pid}&keyword={renamed}",
                  wait_until="domcontentloaded")
        expect(page.locator("[data-test='element-name']")).to_have_count(0, timeout=10000)
        assert _list_count(pid, h, keyword=renamed) == [], "deleted element still in API"

        assert_no_errors(collectors, "element-library full spectrum")
    finally:
        # Teardown: sweep this spectrum's element rows through the real API
        # (idempotent), then close the ledger with the project PURGE door —
        # plain DELETE /projects/{id} is only the soft-delete archive door.
        for eid in seeded:
            httpx.delete(f"{API_BASE}/appauto/elements/{eid}", headers=h, timeout=10)
        if "new_id" in locals():
            httpx.delete(f"{API_BASE}/appauto/elements/{new_id}", headers=h, timeout=10)
        httpx.delete(f"{API_BASE}/projects/{pid}/purge?force=true", headers=h, timeout=15)
