# -*- coding: utf-8 -*-
"""Action-surface E2E for the inspector promote door .

Real chain only (iron rule: nothing mocked): browser -> admin 8081 bridge ->
core 8083 (/internal/mock/hit/promote) and mock-server 18085/18086 (rule pin,
stub answer, hit recorder) -> RabbitMQ testpike.mock-hit -> core consumer ->
tp_mock_hit_log. The L3 script (the internal L3 promote script) covers the
refusal spectra; this file owns the human-facing journey on the page:

1. deep link /#/mock/inspector?projectId=&spaceId= lands selected ( fix,
 reload pins the header context);
2. a real answered stub hit shows up in the live ledger;
3. row click opens the snapshot drawer -> promote button ENABLED (response
 plane present) -> click files the rule, plain-word success toast;
4. re-click is the honest idempotent info toast (nothing duplicated);
5. #/mock/docs : expand the resource group -> the promoted
 card carries the 'record' origin badge (mock-docs-source-tag);
6. reload keeps the card truth (DB-persisted, source=record).

Teardown purges every fixture row and restores the resident mock-server form.
assert_no_errors bundles the 4xx/5xx + console error/warn + browser-issues
zero-tolerance gates.
"""
import os
import time

import httpx
import pytest
from playwright.sync_api import expect

from conftest import (BASE_URL, assert_no_errors, core_base_url, mock_mgmt_port,
                      mock_plane_kill, mock_plane_start, mock_traffic_port, sql_query)

pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("mock-plane-live")]

# Slot-aware addressing resolved by the conftest single source (same rule as
# the recording journey); defaults keep the main-slot run unchanged.
CORE = core_base_url()
MGMT = mock_mgmt_port()
TRAFFIC = mock_traffic_port()
SPACES_URL = f"{BASE_URL}/#/mock/spaces"


def _wb(sid, pid, tab):
    """ AN-4: the inspector/docs screens ride the space workbench tab."""
    return f"{SPACES_URL}/{sid}?projectId={pid}&tab={tab}"


def _start_mock_server(space_key, interval_ms=800):
    return mock_plane_start(space_key, interval_ms=interval_ms)


def _wait_http(url, code=200, deadline_s=240):
    deadline = time.time() + deadline_s
    while time.time() < deadline:
        try:
            if httpx.get(url, timeout=5).status_code == code:
                return True
        except Exception:
            pass
        time.sleep(2)
    return False


def _load_state():
    return httpx.get(f"http://localhost:{MGMT}/load-state", timeout=5).json() or {}


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={"projectId": pid, "name": name, "spaceKey": key})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def test_t362d_inspector_promote_journey(logged_in, collectors, api_client, uniq):
    """Answered hit -> drawer -> one-click promote -> toast -> idempotent
    re-click -> docs page badge visible -> reload persistence."""
    pid = _mk_project(api_client, f"PromE2E {uniq}", f"pe{uniq[:6].upper()}")
    space_key = f"prom{uniq}".lower()
    space_id = _mk_space(api_client, pid, f"prom-space-{uniq}", space_key)
    r = api_client.post(f"/mock/spaces/{space_id}/transition",
                        json={"targetStatus": 1, "expectedVersion": 0})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]

    # One answered stub: the hit under test freezes THIS shape into a rule.
    stub = {
        "name": f"prom-e2e order {uniq}", "method": "GET",
        "pathMatchType": "template", "pathPattern": "/orders/{id}",
        "responseJson": ("{\"status\": 201, \"bodyMode\": \"template\","
                         " \"headers\": {\"Content-Type\": \"application/json\"},"
                         " \"body\": \"{\\\"orderId\\\":\\\"{{mock.pathVar.id}}\\\","
                         "\\\"frozen\\\":true}\"}"),
        "enabled": True,
    }
    r = api_client.post(f"/mock/spaces/{space_id}/stubs", json=stub)
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]

    mock_proc = None
    try:
        mock_plane_kill()
        mock_proc = _start_mock_server(space_key)
        assert _wait_http(f"http://localhost:{MGMT}/actuator/health"), "mock mgmt not up"
        deadline = time.time() + 60
        while time.time() < deadline:
            st = _load_state()
            if st.get("pinnedSpaceKey") == space_key and st.get("everLoaded"):
                break
            time.sleep(1)
        else:
            pytest.fail(f"rule loader never pinned the fixture space: {_load_state()}")

        # --- real traffic through the plane: one answered hit for the ledger ---
        r = httpx.get(f"http://localhost:{TRAFFIC}/mocks/{space_key}/orders/42", timeout=6)
        assert r.status_code == 201 and "42" in r.text, r.text[:120]

        # --- deep link lands the header context ---
        page = logged_in
        page.goto(_wb(space_id, pid, "inspector"),
                  wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-inspector-page"]')).to_be_visible(timeout=15000)

        # The answered row must surface through MQ -> core consumer -> the live
        # ledger (poll checkbox on by default; wait generously for the batch).
        hits = page.locator('[data-test="mock-inspector-hits"] .el-table__row')
        expect(hits.filter(has_text="/orders/42")).to_have_count(1, timeout=60000)
        row = hits.filter(has_text="/orders/42").first
        expect(row.locator('[data-test="mock-inspector-verdict"]')).to_have_text("命中")

        # --- row click opens the snapshot drawer with the promote door ---
        row.click()
        drawer = page.locator('[data-test="mock-inspector-detail"]')
        expect(drawer).to_be_visible(timeout=10000)
        expect(drawer).to_contain_text("/orders/42")
        promote = page.locator('[data-test="mock-inspector-promote"]')
        expect(promote).to_be_enabled()

        # --- first click files the rule: plain-word success toast ---
        promote.click()
        ok_toast = page.locator(".el-message--success").last
        expect(ok_toast).to_be_visible(timeout=10000)
        expect(ok_toast).to_contain_text("已创建 1 条")

        # DB truth immediately after the click (no phantom toast on failure).
        promoted = sql_query(
            "SELECT count(*) FROM tp_mock_stub WHERE space_id="
            f"{space_id} AND source='record'")
        assert promoted == "1", f"DB record-source rows {promoted} != 1"

        # --- re-click: honest idempotent info toast, nothing duplicated ---
        # Close the open drawer first (its overlay intercepts row clicks).
        page.keyboard.press("Escape")
        expect(drawer).to_be_hidden(timeout=10000)
        row.click()
        expect(drawer).to_be_visible(timeout=10000)
        page.locator('[data-test="mock-inspector-promote"]').click()
        info_toast = page.locator(".el-message--info").last
        expect(info_toast).to_be_visible(timeout=10000)
        expect(info_toast).to_contain_text("未重复创建")
        promoted = sql_query(
            "SELECT count(*) FROM tp_mock_stub WHERE space_id="
            f"{space_id} AND source='record'")
        assert promoted == "1", f"re-click duplicated rules: {promoted}"

        # --- docs page: the promoted card carries the record origin badge ---
        # grouped view: groups default to CLOSED at worst scale; V240
        # auto-expands genuinely small spaces (<=8 rules & <=5 groups). The
        # promote fixture space has 2 rules, so it lands EXPANDED — a blind
        # head click would collapse it (toggle parity, same posture as
        # test_v240b's status-driven helper). Open only when closed.
        def ensure_group_open(name: str) -> None:
            head = page.locator(f'[data-test="mock-docs-stub-group-head-{name}"]')
            expect(head).to_be_visible(timeout=15000)
            if "true" not in (head.get_attribute("aria-expanded") or ""):
                head.click()
                expect(head).to_have_attribute("aria-expanded", "true", timeout=5000)

        page.goto(_wb(space_id, pid, "docs"),
                  wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)
        ensure_group_open("orders")
        card = page.locator('[data-test="mock-docs-card"]').filter(
            has_text="/orders/42")
        expect(card).to_have_count(1, timeout=15000)
        expect(card.locator('[data-test="mock-docs-source-tag"]')).to_have_text("录制生成")

        # --- reload keeps the card truth (persisted, not a toast illusion) ---
        page.reload(wait_until="domcontentloaded")
        ensure_group_open("orders")
        expect(card).to_have_count(1, timeout=15000)
        expect(card.locator('[data-test="mock-docs-source-tag"]')).to_have_text("录制生成")

        assert_no_errors(collectors, "T362d inspector promote journey")
    finally:
        try:
            sql_query(f"DELETE FROM tp_mock_hit_log WHERE space_id={space_id}")
            sql_query(f"DELETE FROM tp_mock_stub WHERE space_id={space_id}")
            sql_query(f"DELETE FROM tp_mock_space WHERE id={space_id}")
            api_client.delete(f"/projects/{pid}/purge")
        except Exception as e:
            print(f"cleanup-warn rows: {e}")
        mock_plane_kill()
        # Restore the resident plane pinned to the fixture space (same posture
        # as test_t362c) and wait for it to answer again before handing the
        # worker to the next test.
        _start_mock_server(os.environ.get("MOCK_RESIDENT_SPACE_KEY",
                                          "ivo-1dc2693b-e"), interval_ms=3000)
        if not _wait_http(f"http://localhost:{MGMT}/actuator/health"):
            print("cleanup-warn: restored mock-server never became healthy")
