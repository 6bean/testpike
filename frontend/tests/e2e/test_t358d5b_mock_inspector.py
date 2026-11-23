# -*- coding: utf-8 -*-
"""First E2E for the mock traffic inspector page (AG batch, .

Real browser, no mocks; the three assertion doors:
1. inspector journey — create project + mock space through the live API (uniq
 suffix isolation), open #/mock/inspector with an explicit ?projectId (AK URL
 priority), watch the space appear, the load-state bar admit honestly that the
 plane never loaded a rule set (no fake green), the empty ledger speak plain
 words, and reload keep the selection through the URL query;
2. help button anchors to the dedicated API-mocking chapter of domain T (the
 multi-chapter anchor posture proven in the UI-fix batch);
3. the sidebar renders the new API-Mocking group and its inspector leaf.

All data is created and torn down for real; assert_no_errors bundles the 4xx/5xx,
console error/warn and browser-issues zero-tolerance gates.
"""
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, inject_token, sql_query

# AN-4: the traffic inspector lives on the per-space workbench tab.
SPACES_URL = f"{BASE_URL}/#/mock/spaces"


def _wb(sid, pid, tab="inspector"):
    return f"{SPACES_URL}/{sid}?projectId={pid}&tab={tab}"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={
        "projectId": pid, "name": name, "spaceKey": key,
        "alias": None, "envId": None, "basePathMode": "prefix",
        "authMode": "none", "rateLimitRps": None, "quotaDaily": None, "priority": 100,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"space create failed: {r.text[:300]}"
    return r.json()["data"]


def test_t358d5b_inspector_journey(logged_in, collectors, api_client, uniq):
    """Full inspector journey: real data -> honest health bar -> empty ledger -> reload."""
    page = logged_in
    pid = _mk_project(api_client, f"Inspector {uniq}", f"insp{uniq}")
    space_name = f" insp-space-{uniq}"
    space_key = f"insp{uniq}"
    sid = _mk_space(api_client, pid, space_name.strip(), space_key)
    try:
        # AN-4: the traffic screen is the workbench's inspector tab —
        # the space is carried by the URL path, no page-level space combo.
        page.goto(_wb(sid, pid), wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-workbench-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="mock-inspector-page"]')).to_be_visible(timeout=15000)
        # embedded contract: the retired page-level selector is gone (the tab
        # bar is the single switch), and the tab query rode the deep link.
        expect(page.locator('[data-test="mock-inspector-space-select"]')).to_have_count(0)
        assert "tab=inspector" in page.url, page.url

        # : the deep link lands in the global header context. The header
        # resolves the query projectId into its selection, so the trigger
        # shows the project name right away (assertion corrected 2026-10-21:
        # the old #pid id-fallback only covered a stale-snapshot window that
        # no longer exists once the visible-project list is fetched live).
        expect(page.locator('[data-test="project-ctx-trigger"]')).to_contain_text(
            f"Inspector {uniq}", timeout=10000)

        # load-state bar reports honestly. Resident posture since the AG close
        # is a plane pinned to the fixture space, so the deterministic healthy
        # wording is the serving-space state (assertion corrected 2026-10-21:
        # the old never-loaded wording only held while no plane ran at all).
        # The long timeout lets a sibling test's plane restart window settle
        # instead of catching the transient offline wording.
        health = page.locator('[data-test="mock-inspector-health"]')
        expect(health).to_be_visible(timeout=10000)
        expect(health).to_contain_text("正在服务空间", timeout=120000)

        # three filter spectra present; the fresh space has no traffic — honest empty
        expect(page.locator('[data-test="mock-inspector-filter"]')).to_be_visible()
        page.locator('[data-test="mock-inspector-filter"] .el-radio-button').nth(2).click()
        expect(page.locator('[data-test="mock-inspector-empty"]')).to_contain_text(
            "没有流量记录", timeout=10000)

        # reload keeps the selection — the URL query is the single carried
        # context: the workbench stays on the traffic tab and names its space
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="project-ctx-trigger"]')).to_contain_text(
            f"Inspector {uniq}", timeout=10000)
        expect(page.locator('[data-test="mock-workbench-page"] .page-title')).to_contain_text(
            f"insp-space-{uniq}", timeout=8000)
        assert "tab=inspector" in page.url, page.url

        assert_no_errors(collectors, "T358d5b inspector journey")
    finally:
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {sid}")
        api_client.delete(f"/projects/{pid}/purge")


def test_t358d5b_help_anchor_mock_chapter(logged_in, collectors):
    """The mock help entry anchors to the API-mocking chapter of domain T.

 AN-4: the screening leaves collapsed into the workbench. R23 stage-2:
 the page-side help icon retired with the title shell — the single topbar
 dot (data-test=header-help-btn) resolves the same leaf anchor (single
 source = menuStructure).
 """
    page = logged_in
    page.goto(SPACES_URL, wait_until="domcontentloaded")
    expect(page.locator('[data-test="header-help-btn"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="header-help-btn"]').first.click()
    drawer = page.locator('[data-test="help-drawer"]')
    expect(drawer).to_be_visible(timeout=8000)
    body = drawer.locator("h2.help-doc-title").first
    # the spaces page anchors to its own chapter (V269 domain-T chapter set)
    expect(body).to_contain_text("仿真空间", timeout=8000)
    # negative pin: must NOT fall back to the first chapter of the domain
    expect(body).not_to_contain_text("接口测试：")
    page.keyboard.press("Escape")
    assert_no_errors(collectors, "T358d5b help anchor chapter")


def test_t358d5b_menu_group_visible(logged_in, collectors):
    """API-Mocking group renders its two surviving leaves ."""
    page = logged_in
    group = page.locator('[data-test="group-mock"]')
    expect(group).to_be_visible(timeout=10000)
    group.locator("> .el-sub-menu__title").first.click()
    expect(page.locator('[data-test="menu-mock-spaces"]')).to_be_visible(timeout=8000)
    expect(page.locator('[data-test="menu-mock-endpoints"]')).to_be_visible(timeout=8000)
    # the four retired screening leaves must be gone from the menu surface
    for retired in ("menu-mock-docs", "menu-mock-scenarios",
                    "menu-mock-recording", "menu-mock-inspector"):
        expect(page.locator(f'[data-test="{retired}"]')).to_have_count(0)
    assert_no_errors(collectors, "T358d5b mock menu group visible")
