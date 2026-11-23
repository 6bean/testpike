# -*- coding: utf-8 -*-
"""SE1 searchability digestion E2E (plan-wizard selectors).

Proves the plan wizard selectors fixed by the site-wide selector-searchability
digestion behave under worst-scale data, not just compile `filterable`:
the case-tag scope select is fed the same 42-option fixture as and the
environment select runs at its honest domain ceiling (one env per type,
types 1..4, ProjectEnvServiceImpl 40904 — same correction as . One
typed substring must collapse each to the single needle option, pinning the
"1 search / <=2 clicks reachable" contract on real browser + real backend
chain (admin 8081 -> Feign -> core 8083 -> real DB; no mocks).

Three exempt fixed enums (status/phases/priorities) are machine-checked by the
vitest digest contract; they are deliberately not exercised here because a
fixed enum has no worst scale to feed.

Shared helpers are imported from the module (single source per the
no-duplicated-logic law), data isolation via unique scratch project purged
in finally.
"""
from playwright.sync_api import expect

from conftest import (
    assert_no_errors,
    inject_token,
    nav_to,
    pick_project_in_header,
    purge_project,
    seed_project,
)
from test_selector_digest_search import (
    POOL_FILLERS,
    TAG_FILLERS,
    _filter_to_needle,
)

PLAN_MENU = "执行计划"


def _seed_plan_wizard_project(api_client, uniq):
    """Scratch project with worst-scale tags + the full 4-type env ceiling."""
    name = f"计划摘要{uniq}"
    pid = seed_project(api_client, uniq, "PDIG", display_name=name)
    s6 = uniq[:6]
    tag_needle = f"针标{s6}"
    pool_needle = f"池针{s6}"
    env_needle = f"环针{s6}"
    for label, tp in ((tag_needle, 4), (pool_needle, 7)):
        r = api_client.post("/tags", json={"projectId": pid, "name": label, "type": tp})
        assert r.json().get("code") == "0", f"create needle tag failed: {r.text[:200]}"
    for i in range(TAG_FILLERS):
        r = api_client.post("/tags", json={"projectId": pid, "name": f"标签{i:02d}{s6}", "type": 4})
        assert r.json().get("code") == "0", f"create filler tag failed: {r.text[:200]}"
    for i in range(POOL_FILLERS):
        r = api_client.post("/tags", json={"projectId": pid, "name": f"池伴{i:02d}{s6}", "type": 7})
        assert r.json().get("code") == "0", f"create filler pool tag failed: {r.text[:200]}"
    # Domain ceiling for the env select: exactly one env per type, types 1..4.
    for label, etype in ((env_needle, 3), (f"环开{s6}", 1), (f"环测{s6}", 2), (f"环生{s6}", 4)):
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": label, "envType": etype,
            "baseUrl": f"https://pdig-{s6}.tp-local.test", "endTypes": "web"})
        assert r.json().get("code") == "0", f"create env failed: {r.text[:200]}"
    return pid, name, {"tag": tag_needle, "env": env_needle}


def test_plan_wizard_env_and_tag_selectors_searchable_under_worst_scale(
        page, collectors, api_client, uniq):
    """Plan wizard step 2: tag scope (42 options) and env (domain cap 4) filter."""
    pid, pname, needles = _seed_plan_wizard_project(api_client, uniq)
    try:
        inject_token(page)
        nav_to(page, PLAN_MENU, "/plans", '[data-test="plan-list-page"]', submenu="执行测试")
        pick_project_in_header(page, pname)
        expect(page.locator('[data-test="plan-list-page"]')).to_be_visible(timeout=10000)

        page.locator('[data-test="plan-create-btn"]').click()
        dlg = page.locator('[data-test="plan-wizard-dialog"]')
        expect(dlg).to_be_visible(timeout=10000)
        # Step 1 only validates the required name; period/owner default fine.
        page.locator('[data-test="plan-wizard-name"]').fill(f"摘要计划{uniq[:6]}")
        page.locator('[data-test="plan-wizard-next"]').click()

        total_tags = TAG_FILLERS + POOL_FILLERS + 2  # 42 options, scale
        _filter_to_needle(page, "plan-wizard-tags", total_tags, needles["tag"], "针标")
        _filter_to_needle(page, "plan-wizard-envs", 4, needles["env"], "环针")

        assert_no_errors(collectors, "plan wizard env/tag selector searchability")
    finally:
        purge_project(api_client, pid)
