# -*- coding: utf-8 -*-
"""SE1 searchability digestion E2E (monitor target-wizard selectors).

Proves the four TargetCreateWizard selectors fixed by the digest segment behave
under worst-scale data instead of only compiling `filterable`: the env select
runs at its honest domain ceiling (one env per type, types 1..4 — the same
correction as slices 1 and 3), the reference collection / request selects are
fed 31 real-API options each (past the 30+ searchability trip line), and the
DB connection select is fed 34 project-wide options (31 on the needle env +
3 on another env — the dropdown mirrors the load-time project snapshot, the
filter only narrows when an env was already picked at load). One typed
substring must collapse each to the single needle option, pinning the
"1 search / <=2 clicks reachable" contract on real browser + real backend
chain (admin 8081 -> Feign -> core 8083 -> real DB; no mocks).

Shared helpers come from the module (single-source law, no
duplicated logic). Data isolation via a unique scratch project purged in
finally. Connection seeding reads DB_PASSWORD from the environment —
never inlined (credential law).
"""
import os

from playwright.sync_api import expect

from conftest import (
    assert_no_errors,
    inject_token,
    nav_to,
    pick_project_in_header,
    purge_project,
    seed_project,
)
from test_selector_digest_search import _filter_to_needle

COLL_FILLERS = 30
REQ_FILLERS = 30
CONN_FILLERS = 30
OFF_ENV_CONNS = 3
ASSERTIONS = '[{"type":"status","op":"equals","expect":200}]'


def _seed_target_wizard_project(api_client, uniq):
    """Scratch project with worst-scale envs / collections / requests / connections."""
    name = f"向导摘要{uniq}"
    pid = seed_project(api_client, uniq, "TDIG", display_name=name)
    s6 = uniq[:6]
    needles = {
        "env": f"环针{s6}",
        "coll": f"针集{s6}",
        "req": f"针请{s6}",
        "conn": f"针连{s6}",
    }

    env_ids = {}
    for label, etype in ((needles["env"], 3), (f"环开{s6}", 1),
                         (f"环测{s6}", 2), (f"环生{s6}", 4)):
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": label, "envType": etype,
            "baseUrl": f"https://tdig-{s6}.tp-local.test", "endTypes": "web"})
        assert r.json().get("code") == "0", f"create env failed: {r.text[:200]}"
        d = r.json()["data"]
        env_ids[label] = d["id"] if isinstance(d, dict) else d

    for i in range(COLL_FILLERS):
        r = api_client.post("/apitest/collections", json={
            "projectId": pid, "parentId": 0,
            "name": f"集合伴{i:02d}{s6}", "tags": ""})
        assert r.json().get("code") == "0", f"create collection failed: {r.text[:200]}"
    r = api_client.post("/apitest/collections", json={
        "projectId": pid, "parentId": 0, "name": needles["coll"], "tags": ""})
    assert r.json().get("code") == "0", f"create needle collection failed: {r.text[:200]}"
    needle_coll_id = r.json()["data"]["id"]

    for i in range(REQ_FILLERS):
        r = api_client.post("/apitest/requests", json={
            "collectionId": needle_coll_id, "name": f"请求伴{i:02d}{s6}",
            "method": "GET", "urlPath": "/api/health", "headersJson": "[]",
            "bodyText": "", "authJson": "{}", "assertionsJson": ASSERTIONS})
        assert r.json().get("code") == "0", f"create request failed: {r.text[:200]}"
    r = api_client.post("/apitest/requests", json={
        "collectionId": needle_coll_id, "name": needles["req"],
        "method": "GET", "urlPath": "/api/health", "headersJson": "[]",
        "bodyText": "", "authJson": "{}", "assertionsJson": ASSERTIONS})
    assert r.json().get("code") == "0", f"create needle request failed: {r.text[:200]}"

    pwd = os.environ.get("DB_PASSWORD", "")
    assert pwd, "DB_PASSWORD not set (source .env before pytest; never inline credentials)"
    from conftest import require_db_url
    jdbc = require_db_url()
    needle_env = env_ids[needles["env"]]
    open_env = env_ids[f"环开{s6}"]
    conn_rows = [(needles["conn"], needle_env)]
    conn_rows += [(f"连接伴{i:02d}{s6}", needle_env) for i in range(CONN_FILLERS)]
    conn_rows += [(f"连外{i:02d}{s6}", open_env) for i in range(OFF_ENV_CONNS)]
    for label, env_id in conn_rows:
        r = api_client.post(f"/projects/{pid}/env-connections", json={
            "envId": env_id, "name": label, "dialect": "pg", "jdbcUrl": jdbc,
            "username": os.environ.get("DB_USERNAME", "testpike"),
            "password": pwd, "readonly": True, "maxPool": 1})
        assert r.json().get("code") == "0", f"create connection failed: {r.text[:200]}"
    return pid, name, needles


def test_target_wizard_selectors_searchable_under_worst_scale(
        page, collectors, api_client, uniq):
    """Target wizard: env (domain cap 4), ref collection (31), request (31), conn (34)."""
    pid, pname, needles = _seed_target_wizard_project(api_client, uniq)
    try:
        inject_token(page)
        nav_to(page, "监控中心", "/monitor", '[data-test="monitor-page"]', submenu="执行测试")
        pick_project_in_header(page, pname)
        expect(page.locator('[data-test="monitor-create-btn"]')).to_be_enabled(timeout=10000)

        page.locator('[data-test="monitor-create-btn"]').click()
        expect(page.locator('[data-test="monitor-wizard"]')).to_be_visible(timeout=10000)

        # Step 1 (ownership): the env select at its honest domain ceiling —
        # one env per type, types 1..4, no way to inflate past 4 options.
        _filter_to_needle(page, "wizard-env", 4, needles["env"], "环针")
        page.locator('[data-test="wizard-name"]').fill(f"摘要目标{uniq[:6]}")
        page.locator('[data-test="wizard-next"]').click()
        expect(page.locator('[data-test="wizard-step-probe"]')).to_be_visible(timeout=5000)

        # Step 2 (probe), HTTP branch: the reference-asset selects fed past
        # the trip line; picking the needle collection lazily loads its 31 requests.
        _filter_to_needle(page, "wizard-ref-collection", COLL_FILLERS + 1,
                          needles["coll"], "针集")
        _filter_to_needle(page, "wizard-ref-request", REQ_FILLERS + 1,
                          f"GET {needles['req']}", "针请")

        # Step 2 (probe), DB branch: the connection select shows the project
        # snapshot taken at dialog-open time (env picked after load = no
        # refetch, so 31 needle-env + 3 other-env = 34 options are visible).
        page.locator('[data-test="wizard-type"]').get_by_text("DB", exact=True).click()
        _filter_to_needle(page, "wizard-conn",
                          CONN_FILLERS + 1 + OFF_ENV_CONNS, needles["conn"], "针连")

        assert_no_errors(collectors, "target wizard selector searchability")
    finally:
        purge_project(api_client, pid)
