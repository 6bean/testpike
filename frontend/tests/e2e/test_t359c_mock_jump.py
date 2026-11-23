# -*- coding: utf-8 -*-

import json
import re

from playwright.sync_api import expect

from conftest import (BASE_URL, assert_no_errors,
                      open_apitest_datasource_item, sql_query)

SPACES_URL = f"{BASE_URL}/#/mock/spaces"


def _wb(sid, pid, tab="docs"):
    """ AN-4 workbench spectrum for a known space."""
    return f"{SPACES_URL}/{sid}?projectId={pid}&tab={tab}"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={"projectId": pid, "name": name, "spaceKey": key})
    assert r.status_code == 200 and r.json().get("code") == "0", f"space create failed: {r.text[:300]}"
    return r.json()["data"]


def _publish(api, space_id, version=0):
    r = api.post(
        f"/mock/spaces/{space_id}/transition",
        json={"targetStatus": 1, "expectedVersion": version},
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"publish failed: {r.text[:300]}"


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "通过",
        "priority": 1, "confidence": 2, "endType": "web", "phase": 2,
        "steps": [{"stepNo": 1, "description": "登录系统", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"case create failed: {r.text[:300]}"
    d = r.json()["data"]
    return d["id"] if isinstance(d, dict) else d


def _mk_stub(api, space_id, name, path, source, source_ref=None):
    r = api.post(f"/mock/spaces/{space_id}/stubs", json={
        "name": name,
        "method": "GET",
        "pathMatchType": "exact",
        "pathPattern": path,
        "matchersJson": json.dumps([]),
        "responseJson": json.dumps({"status": 200, "body": json.dumps({"ok": True})}),
        "source": source,
        "sourceRef": source_ref,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"stub create failed: {r.text[:300]}"
    return r.json()["data"]


def test_t359c_case_list_jump_origin_back(logged_in, collectors, api_client, uniq):
    """Case list -> docs with case-flip context; origin bar shows the sender; back keeps the filter."""
    page = logged_in
    pid = _mk_project(api_client, f"JumpA {uniq}", f"jpa{uniq}")
    space_id = _mk_space(api_client, pid, f"jumpa-space-{uniq}", f"jpa{uniq}".lower())
    _publish(api_client, space_id)
    try:
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}", wait_until="domcontentloaded")
        btn = page.locator('[data-test="case-view-in-mock"]')
        expect(btn).to_be_visible(timeout=15000)
        btn.click()

        # AN-4 two-stage landing: the sender door (/mock/docs?…&source=)
        # has no space id, so the redirect drops the visitor on the spaces
        # list WITH the tab+source intent; opening the row forwards both.
        # (Query key order is insertion-driven — assert membership, not a prefix.)
        expect(page).to_have_url(re.compile(r"/#/mock/spaces\?"), timeout=10000)
        landed = page.evaluate("() => location.hash")
        assert f"projectId={pid}" in landed and "source=case-flip" in landed and "tab=docs" in landed, landed
        page.locator('[data-test="mock-spaces-open-workbench"]').first.click()
        expect(page).to_have_url(
            re.compile(rf"/#/mock/spaces/{space_id}\?projectId={pid}"), timeout=10000)
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=10000)
        bar = page.locator('[data-test="mock-docs-origin-bar"]')
        expect(bar).to_be_visible(timeout=10000)
        expect(bar).to_contain_text("用例升格", timeout=5000)

        page.locator('[data-test="mock-docs-origin-back"]').click()
        # the ride key (source) is dropped on the way back; the project filter stays
        expect(page).to_have_url(
            re.compile(rf"/#/cases\?projectId={pid}(&|$)"), timeout=10000)
        assert "source=" not in page.url, f"ride key lingered on the way back: {page.url}"

        assert_no_errors(collectors, "T359c case-list jump + origin back")
    finally:
        sql_query(f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}")
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_t359c_apitest_jump_openapi_badge(logged_in, collectors, api_client, uniq):
    """api-test -> docs with openapi context; sender badge + card badge render; back returns."""
    page = logged_in
    pid = _mk_project(api_client, f"JumpB {uniq}", f"jpb{uniq}")
    space_id = _mk_space(api_client, pid, f"jumpb-space-{uniq}", f"jpb{uniq}".lower())
    _publish(api_client, space_id)
    _mk_stub(api_client, space_id, f"spec-rule-{uniq}", "/api/e2e-b", "openapi", "spec-e2e")
    try:
        page.goto(f"{BASE_URL}/#/api-test?projectId={pid}", wait_until="domcontentloaded")
        # / IA: the door lives inside the rail datasource dropdown —
        # open the menu first (public helper, same pose as the stub-direct door).
        open_apitest_datasource_item(page, "apitest-view-in-mock")

        # AN-4 two-stage landing (same spectra as the case-flip door):
        # list carries tab+source, opening the row forwards them.
        expect(page).to_have_url(re.compile(r"/#/mock/spaces\?"), timeout=10000)
        landed = page.evaluate("() => location.hash")
        assert f"projectId={pid}" in landed and "source=openapi" in landed and "tab=docs" in landed, landed
        page.locator('[data-test="mock-spaces-open-workbench"]').first.click()
        expect(page).to_have_url(
            re.compile(rf"/#/mock/spaces/{space_id}\?projectId={pid}"), timeout=10000)
        bar = page.locator('[data-test="mock-docs-origin-bar"]')
        expect(bar).to_be_visible(timeout=10000)
        expect(bar).to_contain_text("契约直出", timeout=5000)

        card = page.locator('[data-test="mock-docs-card"]')
        expect(card).to_have_count(1, timeout=10000)
        expect(card.locator('[data-test="mock-docs-source-tag"]')).to_contain_text(
            "契约直出", timeout=5000)
        # an openapi rule knows its door: the return affordance is rendered
        expect(card.locator('[data-test="mock-docs-source-jump"]')).to_be_visible(timeout=5000)

        page.locator('[data-test="mock-docs-origin-back"]').click()
        expect(page).to_have_url(
            re.compile(rf"/#/api-test\?projectId={pid}(&|$)"), timeout=10000)

        assert_no_errors(collectors, "T359c api-test jump + openapi badge")
    finally:
        sql_query(f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}")
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_t359c_case_flip_card_door_to_detail(logged_in, collectors, api_client, uniq):
    """A case-flip rule with a numeric sourceRef opens that case's detail page."""
    page = logged_in
    pid = _mk_project(api_client, f"JumpC {uniq}", f"jpc{uniq}")
    space_id = _mk_space(api_client, pid, f"jumpc-space-{uniq}", f"jpc{uniq}".lower())
    _publish(api_client, space_id)
    case_id = _mk_case(api_client, pid, f"升格来源用例 {uniq}")
    _mk_stub(api_client, space_id, f"flip-rule-{uniq}", "/api/e2e-c", "case-flip", str(case_id))
    try:
        # AN-4: a URL-carried spaceId lands straight on the workbench
        # tab (the retired /mock/docs path redirects functionally).
        page.goto(_wb(space_id, pid), wait_until="domcontentloaded")
        card = page.locator('[data-test="mock-docs-card"]')
        expect(card).to_have_count(1, timeout=15000)
        expect(card.locator('[data-test="mock-docs-source-tag"]')).to_contain_text(
            "用例升格", timeout=5000)
        card.locator('[data-test="mock-docs-source-jump"]').click()

        expect(page).to_have_url(f"{BASE_URL}/#/cases/{case_id}", timeout=10000)
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(
            f"升格来源用例 {uniq}", timeout=5000)

        assert_no_errors(collectors, "T359c case-flip card door -> case detail")
    finally:
        sql_query(f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}")
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_t359c_fake_spaceid_falls_back_honestly(logged_in, collectors, api_client, uniq):
    """A spaceId outside the loaded list never wins — the workbench resolves it
 to a plain-word missing state pointing back to the list (never a guessed
 space; AN-4 consumption law)."""
    page = logged_in
    pid = _mk_project(api_client, f"JumpD {uniq}", f"jpd{uniq}")
    space_id = _mk_space(api_client, pid, f"jumpd-space-{uniq}", f"jpd{uniq}".lower())
    _publish(api_client, space_id)
    try:
        page.goto(f"{SPACES_URL}/999999?projectId={pid}&tab=docs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-workbench-page"]')).to_be_visible(timeout=15000)

        # honest consumption: no guess-to-first-space, a named missing state
        missing = page.locator('[data-test="mock-workbench-space-missing"]')
        expect(missing).to_be_visible(timeout=10000)
        expect(missing).to_contain_text("仿真空间")
        # and the door back to the list works — the real space waits there
        missing.get_by_role("button").click()
        expect(page.locator('[data-test="mock-spaces-page"]')).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "T359c fake spaceId honest fallback")
    finally:
        sql_query(f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}")
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
