""" E2E re-verification for the ownership-echo pages the old S-3
scanner missed (i18n-blind create entries), on the real admin 8081 -> core 8083
chain (no mocks).

Iron-rule dual asserts (whole-site ruling 2026-10-14), exercised against two
representative pages of the cleanup set:
 1. no project context -> the primary write entry is disabled and its tooltip
 carries the plain-language "pick a project in the header" pointer;
 2. context selected via a deep link -> the create/generate dialog shows the
 read-only ownership echo whose value equals the header project name
 (single source, zero selector inside the dialog).

Data isolation: throwaway uuid-suffixed project purged in finally; never
touches the demo/regression projects.
"""
import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(240)

API = "http://localhost:8081/api/v1"


def _clear_ctx(page):
    page.evaluate(
        "() => { for (const k of Object.keys(localStorage)) "
        "{ if (k.startsWith('tp_proj_ctx_')) localStorage.removeItem(k); } }")


def _boot(page, token):
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(
        "() => { localStorage.setItem('tp_access_token', '%s'); "
        "localStorage.setItem('tp_onboarding_dismissed', '1'); }" % token)


def _make_project(api, token, tag):
    suffix = tag
    r = api.post("/projects", json={"name": f"T415c归属{suffix}", "code": f"T415C{suffix}"})
    assert r.status_code == 200, f"create project failed: {r.text[:200]}"
    return r.json()["data"], f"T415c归属{suffix}"


def test_owner_echo_testreport_generate_entry_and_dialog(page, collectors):
    """Report list page: gated generate entry + generate-dialog echo (S-3 cleanup set)."""
    token = api_login("admin", ADMIN_PASSWORD)
    _boot(page, token)
    suffix = "RPT" + _uid()
    with httpx.Client(base_url=API, timeout=15) as api:
        api.headers["Authorization"] = f"Bearer {token}"
        pid, pname = _make_project(api, token, suffix)
    try:
        _clear_ctx(page)
        page.goto(f"{BASE_URL}/#/test-report", wait_until="domcontentloaded")
        btn = page.locator('[data-test="report-generate-btn"]')
        expect(btn).to_be_visible(timeout=15000)
        expect(btn).to_be_disabled()
        btn.hover()
        tip = page.get_by_role("tooltip").filter(has_text="顶栏")
        expect(tip.first).to_be_visible(timeout=8000)

        page.goto(f"{BASE_URL}/#/test-report?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(btn).to_be_enabled(timeout=15000)
        btn.click()
        dialog = page.locator('[data-test="report-gen-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        echo = dialog.locator('[data-test="owner-echo"]')
        expect(echo).to_be_visible()
        expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(pname)
        expect(dialog.locator('[data-test="owner-echo"] .el-select')).to_have_count(0)
        assert_no_errors(collectors, "T415c owner echo test-report page")
    finally:
        _clear_ctx(page)
        with httpx.Client(base_url=API, timeout=30) as api:
            api.headers["Authorization"] = f"Bearer {token}"
            api.delete(f"/projects/{pid}/purge", params={"force": "true"})


def test_owner_echo_popup_rule_learn_dialog(page, collectors):
    """Popup-rule console: gated self-learn entry + learn-dialog echo (S-3 cleanup set)."""
    token = api_login("admin", ADMIN_PASSWORD)
    _boot(page, token)
    suffix = "POP" + _uid()
    with httpx.Client(base_url=API, timeout=15) as api:
        api.headers["Authorization"] = f"Bearer {token}"
        pid, pname = _make_project(api, token, suffix)
    try:
        page.goto(f"{BASE_URL}/#/appauto/popup-rules?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        btn = page.locator('[data-test="popup-rule-learn-btn"]')
        expect(btn).to_be_enabled(timeout=15000)
        btn.click()
        dialog = page.locator('[data-test="popup-rule-learn-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        echo = dialog.locator('[data-test="owner-echo"]')
        expect(echo).to_be_visible()
        expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(pname)
        assert_no_errors(collectors, "T415c owner echo popup-rules page")
    finally:
        _clear_ctx(page)
        with httpx.Client(base_url=API, timeout=30) as api:
            api.headers["Authorization"] = f"Bearer {token}"
            api.delete(f"/projects/{pid}/purge", params={"force": "true"})


def _uid() -> str:
    import uuid
    return uuid.uuid4().hex[:6].upper()
