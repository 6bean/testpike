"""Device-ledger visibility E2E for the real-device lane .

Playwright real browser + real backend through this chain's slot lane
(conftest resolves BASE_URL/API_BASE from the slot env — never hard-coded
ports, never mocked).

Coverage, all through the live plane:
1. Sidebar entry reaches the device wall page on this lane.
2. The executor-auto-discovered emulator row (serial emulator-5554) is
 visible inside a fresh project's segment — the system-level bucket is part
 of every project's list by the visibility contract — with its health
 badge rendered as the GREEN ("success") tag.
3. RELOAD keeps the row visible (deep-linked segment re-lands, page state is
 reconstructable).
4. Zero-tolerance monitors stay armed for the whole run: HTTP 4xx/5xx,
 console error/warning and the browser Issues panel are asserted by
 conftest fixtures (collectors + assert_no_errors + CDP Audits probe).

The auto-ingested emulator row is shared live data: this spec only reads it
and never edits/deletes it. Its own throwaway project is purged by id.
"""
import uuid

import httpx
import pytest
from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    API_BASE,
    BASE_URL,
    api_login,
    assert_no_errors,
    open_journey_leaf,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

AUTO_SERIAL = "emulator-5554"


def _row_for_serial(page, serial):
    return page.locator('[data-test="device-wall-table"] tbody tr').filter(
        has_text=serial
    )


def test_device_ledger_shows_auto_discovered_emulator(page, collectors):
    """Auto-discovered emulator is visible in the wall segment with a GREEN badge."""
    # Real admin login through the API, token injected into the SPA session.
    token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(
        "(t) => { localStorage.setItem('tp_access_token', t); "
        "localStorage.setItem('tp_onboarding_dismissed', '1') }",
        token,
    )
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(
        timeout=15000
    )

    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API_BASE.rsplit("/api/v1", 1)[0], timeout=30, headers=h)
    suffix = uuid.uuid4().hex[:6]
    pid = api.post(
        "/api/v1/projects",
        json={"name": f"ledger-e2e-{suffix}", "code": f"LDG{suffix}"},
    ).json()["data"]
    assert pid, "throwaway project was not created"

    try:
        # 1. Sidebar entry is reachable on this chain's lane.
        open_journey_leaf(page, "menu-device-wall", "appauto")
        expect(page.locator('[data-test="device-wall-page"]')).to_be_visible(
            timeout=10000
        )

        # 2. Deep-link the project segment (dashboard bounce forces remount,
        #    same landing trick as the device wall spec).
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.goto(
            f"{BASE_URL}/#/appauto/devices?projectId={pid}",
            wait_until="domcontentloaded",
        )
        expect(page.locator('[data-test="device-wall-table"]')).to_be_visible(
            timeout=10000
        )

        # The auto-discovered row exists in the ledger before asserting the UI
        # (DB truth first: the page must show what the plane actually holds).
        db_row = sql_query(
            "select health_state from tp_appauto_device "
            f"where serial='{AUTO_SERIAL}' and project_id is null"
        )
        assert db_row == "GREEN", (
            f"expected an auto-discovered GREEN emulator row, got '{db_row}' — "
            "is the executor lane offline?"
        )

        row = _row_for_serial(page, AUTO_SERIAL)
        expect(row.first).to_be_visible(timeout=10000)

        # Health badge rendered as the GREEN (success) tag on that very row.
        badge = row.first.locator('[data-test="device-wall-health-tag"]')
        expect(badge.first).to_be_visible(timeout=5000)
        assert "el-tag--success" in (badge.first.get_attribute("class") or ""), (
            "health badge is not rendered with the GREEN success tag form"
        )
        # Shared-bucket provenance marker is shown for the system-level row.
        expect(
            row.first.locator('[data-test="device-wall-shared-badge"]').first
        ).to_be_visible(timeout=5000)

        # 3. RELOAD persistence — the deep-linked segment re-lands on the row.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="device-wall-table"]')).to_be_visible(
            timeout=10000
        )
        expect(_row_for_serial(page, AUTO_SERIAL).first).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "device-ledger-visibility")
    finally:
        r = api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
        assert r.status_code == 200, f"project purge failed: {r.status_code}"
        assert (
            sql_query(
                f"select count(*) from tp_appauto_device where project_id={pid}"
            )
            == "0"
        )
        # The shared auto-discovered row must survive the cleanup untouched.
        assert (
            sql_query(f"select count(*) from tp_appauto_device where serial='{AUTO_SERIAL}'")
            == "1"
        )
