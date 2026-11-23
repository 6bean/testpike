# -*- coding: utf-8 -*-
"""SE1 searchability digestion hot-page E2E.

Proves the hot-page selectors fixed by the batch behave under real
data instead of only compiling ``filterable``: every target dropdown is fed a
real-API fixture at its honest worst scale and one typed substring must
collapse it to the single needle option (1 search / <=2 clicks reachable),
with hard exact-count assertions and no conditional wrappers.

Targets covered here (fixture-cheap pages):
- knowledge list: source enum (7 fixed values) + status enum (2 fixed values)
 — exact option count, one search collapses each to the needle.
- explore task create dialog (app start kind): env selector scoped by the page
 to envType 1/2 and the credential selector (12 seeded rows past the
 searchability trip line).
- data factory page: pool-tab env filter + create-dialog env selector (same
 4-type ceiling).

Deferred to follow-on tickets (heavy fixtures): api inventory compare version
chain (>=11 spec versions), case detail appauto profile door, mock scenario
space/edge pickers inside the scenario editor, orchestration builder operator
selectors mounted behind the api test tab.

Real browser + real backend chain (admin slot port -> Feign -> core slot port
-> slot DB; no mocks). Three-class zero tolerance (4xx/5xx, console
errors/warnings, browser Issues) via assert_no_errors. Data isolation: one
unique scratch project per test, purged in finally (no TRUNCATE of factory
tables).
"""
from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    assert_no_errors,
    hard_goto,
    inject_token,
    purge_project,
    seed_project,
)
from test_selector_digest_search import _filter_to_needle

# Env dropdowns share the domain ceiling: ProjectEnvServiceImpl enforces one
# env per type per project with type in 1..4, so the honest worst scale of an
# env selector is the full 4-type set (same posture as the earlier digest
# slices). Credentials ship 12 rows past the 10-option trip line.
CRED_FILLERS = 11

SOURCE_TOTAL = 7   # KNOWLEDGE_SOURCE_KEYS: TEST_CASE/FEEDBACK/STYLE/TEST_SPEC/DDL_DICT/PACK_KNOWLEDGE/TERM
STATUS_TOTAL = 2   # KNOWLEDGE_STATUS_KEYS: ACTIVE/INACTIVE


def _seed_hot_project(api_client, uniq):
    """Scratch project with the 4-type env ceiling + 12 credentials (real API)."""
    name = f"热度{uniq}"
    pid = seed_project(api_client, uniq, "HPSE", display_name=name)
    s6 = uniq[:6]
    env_needle = f"环针{s6}"
    for label, etype in ((env_needle, 3), (f"环开{s6}", 1), (f"环测{s6}", 2), (f"环生{s6}", 4)):
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": label, "envType": etype,
            "baseUrl": f"https://hpse-{s6}.tp-local.test", "endTypes": "web"})
        assert r.json().get("code") == "0", f"create env failed: {r.text[:200]}"
    cred_needle = f"凭针{s6}"
    r = api_client.post("/appauto/credentials", json={
        "projectId": pid, "name": cred_needle, "credKey": f"credn{s6}",
        "secret": "hpse-e2e-fake"})
    assert r.json().get("code") == "0", f"create credential failed: {r.text[:200]}"
    for i in range(CRED_FILLERS):
        r = api_client.post("/appauto/credentials", json={
            "projectId": pid, "name": f"凭伴{i:02d}{s6}", "credKey": f"credf{i:02d}{s6}",
            "secret": "hpse-e2e-fake"})
        assert r.json().get("code") == "0", f"create filler credential failed: {r.text[:200]}"
    return pid, name, {"env": env_needle, "dev": f"环开{s6}", "cred": cred_needle}


def test_knowledge_source_and_status_enums_searchable(page, collectors, api_client, uniq):
    """Knowledge list: 7-option source + 2-option status enums each collapse by one search."""
    pid = seed_project(api_client, uniq, "HPSE")
    try:
        inject_token(page)
        hard_goto(page, f"{BASE_URL}/#/knowledge?projectId={pid}")
        expect(page.locator('[data-test="kb-source-select"]')).to_be_visible(timeout=15000)
        _filter_to_needle(page, "kb-source-select", SOURCE_TOTAL, "风格指南", "风格")
        _filter_to_needle(page, "kb-status-select", STATUS_TOTAL, "生效中", "生效")
        assert_no_errors(collectors, "knowledge enum selectors searchability")
    finally:
        purge_project(api_client, pid)


def test_explore_create_dialog_env_and_cred_searchable(page, collectors, api_client, uniq):
    """Explore create dialog: env x4 ceiling + 12 credentials both filter to the needle."""
    pid, _pname, needles = _seed_hot_project(api_client, uniq)
    try:
        inject_token(page)
        hard_goto(page, f"{BASE_URL}/#/explore?projectId={pid}")
        expect(page.locator('[data-test="explore-list-page"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="explore-create"]').click()
        expect(page.locator('[data-test="explore-create-dialog"]')).to_be_visible(timeout=10000)
        # The env/cred selectors mount under the app start kind only (the web
        # branch renders the start-url input); the second radio-button keeps
        # the switch locale-proof.
        page.locator('[data-test="explore-kind"] .el-radio-button__inner').nth(1).click()
        # explore-env is domain-scoped by the page (envType 1/2 only), so the
        # honest worst scale here is 2 options — one search still collapses it.
        _filter_to_needle(page, "explore-env", 2, needles["dev"], "环开")
        _filter_to_needle(page, "explore-cred", CRED_FILLERS + 1, needles["cred"], "凭针")
        assert_no_errors(collectors, "explore create dialog searchability")
    finally:
        purge_project(api_client, pid)


def test_datafactory_pool_and_form_env_searchable(page, collectors, api_client, uniq):
    """Data factory: pool-tab env filter + create-dialog env selector both filter."""
    pid, _pname, needles = _seed_hot_project(api_client, uniq)
    try:
        inject_token(page)
        hard_goto(page, f"{BASE_URL}/#/data-factories?projectId={pid}")
        expect(page.locator('[data-test="datafactory-list-page"]')).to_be_visible(timeout=15000)
        # Pool pane is the second tab; fixed index keeps the contract locale-proof.
        page.locator(".el-tabs__item").nth(1).click()
        expect(page.locator('[data-test="pool-filter-env"]')).to_be_visible(timeout=10000)
        _filter_to_needle(page, "pool-filter-env", 4, needles["env"], "环针")
        page.locator('[data-test="datafactory-create-btn"]').click()
        expect(page.locator('[data-test="datafactory-form-env"]')).to_be_visible(timeout=10000)
        _filter_to_needle(page, "datafactory-form-env", 4, needles["env"], "环针")
        assert_no_errors(collectors, "data factory env selectors searchability")
    finally:
        purge_project(api_client, pid)
