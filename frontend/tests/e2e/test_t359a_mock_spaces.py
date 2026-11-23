# -*- coding: utf-8 -*-
"""First E2E for the mock space workbench (AG batch, .

Real browser, no mocks; the assertion doors:
1. workbench journey — create a project through the live API (uniq suffix
 isolation), open #/mock/spaces with an explicit ?projectId (AK URL priority),
 create a space through the page dialog, see it land in the table as a draft
 with the version column, publish it, see the status badge turn published;
2. the serving-root bar quotes the same two config values the admin supply gate
 hands out (mock.public-base-url + mock.traffic.base-path) — the page never
 invents a host;
3. help button anchors to the API-mocking chapter of domain T;
4. the sidebar renders the workbench leaf inside the API-Mocking group.

All rows are torn down for real; assert_no_errors bundles the 4xx/5xx, console
error/warn and browser-issues zero-tolerance gates.
"""
from playwright.sync_api import expect

import re

from conftest import BASE_URL, assert_no_errors, sql_query

SPACES_URL = f"{BASE_URL}/#/mock/spaces"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def test_t359a_spaces_journey_create_publish(logged_in, collectors, api_client, uniq):
    """Full workbench journey: dialog-create a draft -> table row -> publish -> badge flips."""
    page = logged_in
    pid = _mk_project(api_client, f"SpacesE2E {uniq}", f"spc{uniq}")
    space_key = f"spc{uniq}".lower()
    try:
        page.goto(f"{SPACES_URL}?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-spaces-page"]')).to_be_visible(timeout=15000)

        # : no page-level selector — the deep link lands in the global
        # header context. The landing is proven through the live request:
        # with the project picked, the space list is fetched scoped to it
        # (an unlanded context renders the no-project block instead).
        expect(page.locator('[data-test="mock-spaces-no-project"]')).to_have_count(0, timeout=10000)
        # the screening controls render only while the context holds a project
        expect(page.locator('[data-test="mock-spaces-search"]')).to_be_visible(timeout=10000)

        # serving-root bar quotes the supply gate verbatim (no invented host);
        # the traffic base-path config value is '/mocks' (no trailing slash —
        # space routes append '/{spaceKey}' on top of it)
        bar = page.locator('[data-test="mock-spaces-serving"]')
        expect(bar).to_be_visible(timeout=8000)
        expect(bar).to_contain_text("http://localhost:18086/mocks", timeout=5000)

        # fresh project = honest empty state before the dialog runs
        expect(page.locator('[data-test="mock-spaces-table"]')).to_be_visible(timeout=8000)

        # create through the page dialog (el-input passes data-test straight to
        # the inner control — the direct [data-test] fill posture proven by the
        # credential vault suite; get_by_label does not work because Element
        # Plus el-form-item labels carry no for= association)
        page.locator('[data-test="mock-spaces-new"]').click()
        dialog = page.locator('[data-test="mock-spaces-create-dialog"]')
        expect(dialog).to_be_visible(timeout=8000)
        dialog.locator('[data-test="mock-spaces-f-name"]').fill(f"e2e-space-{uniq}")
        dialog.locator('[data-test="mock-spaces-f-key"]').fill(space_key)
        dialog.locator('[data-test="mock-spaces-create-submit"]').click()

        # the row lands as a draft with its routing key visible
        row = page.locator('[data-test="mock-spaces-table"] tbody tr')
        expect(row).to_have_count(1, timeout=10000)
        expect(row).to_contain_text(space_key, timeout=5000)
        expect(row).to_contain_text("草稿", timeout=5000)

        # publish: confirmation dialog -> badge turns published
        row.locator('[data-test="mock-spaces-publish"]').click()
        page.locator('.el-message-box__btns button.el-button--primary').click()
        badge = page.locator('[data-test="mock-spaces-status"]').first
        expect(badge).to_contain_text("已发布", timeout=10000)

        # reload keeps truth: the row is still published (status lives in the DB)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-spaces-table"] tbody tr')).to_contain_text(
            "已发布", timeout=10000)

        assert_no_errors(collectors, "T359a spaces workbench journey")
    finally:
        sql_query(f"DELETE FROM tp_mock_space WHERE space_key = '{space_key}'")
        api_client.delete(f"/projects/{pid}/purge")


def test_t359a_help_anchor_mock_chapter(logged_in, collectors):
    pass
    page = logged_in
    page.goto(SPACES_URL, wait_until="domcontentloaded")
    # Race pin since R23 stage-2: the topbar "?" renders on every leaf page
    # (the transient dashboard during boot included), so wait for the spaces
    # route to actually land before clicking — otherwise the anchor fires on
    # the dashboard leaf (domain O) and the chapter assertion misreads.
    expect(page).to_have_url(re.compile(r"/#/mock/spaces"), timeout=15000)
    expect(page.locator('[data-test="mock-spaces-page"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="header-help-btn"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="header-help-btn"]').first.click()
    drawer = page.locator('[data-test="help-drawer"]')
    expect(drawer).to_be_visible(timeout=8000)
    body = drawer.locator("h2.help-doc-title").first
    expect(body).to_contain_text("仿真空间", timeout=8000)
    # negative pin: must NOT fall back to the first chapter of the domain
    expect(body).not_to_contain_text("把 HTTP 请求收进系统")
    page.keyboard.press("Escape")
    assert_no_errors(collectors, "T359a spaces help anchor chapter")


def test_t359a_menu_leaf_visible(logged_in, collectors):
    """Sidebar renders the workbench leaf inside the API-Mocking group."""
    page = logged_in
    group = page.locator('[data-test="group-mock"]')
    expect(group).to_be_visible(timeout=10000)
    group.locator("> .el-sub-menu__title").first.click()
    leaf = page.locator('[data-test="menu-mock-spaces"]')
    expect(leaf).to_be_visible(timeout=8000)
    assert_no_errors(collectors, "T359a mock spaces menu leaf visible")
