# -*- coding: utf-8 -*-
"""First E2E for the declarative mock binding on cases and suites .

Real browser + real admin->core chain, no mocks. The assertion doors:
1. case editor journey — API-create a project, a published mock space and a
 draft mock space, open #/cases/:id, enter edit: the picker teaches the
 draft space as unbindable (disabled option + plain-word reason) while the
 published space carries its live rule/traffic thumbnail; bind -> saveDraft
 -> reload -> re-enter edit and the binding is prefilled back (persistence
 proof, API mirror asserts mockSpaceId landed in the DB);
2. unbind — pick the explicit "no mock" option -> save -> the column goes back
 to null (the three-state payload contract the core gate owns);
3. suite dialog journey — create through the page dialog with the published
 space bound -> row persists -> reload -> edit prefills the binding ->
 switch to another... kept lean: unbind via the dialog -> update clears it.

Rows are torn down for real; assert_no_errors bundles the 4xx/5xx, console
error/warn and browser-issues zero-tolerance gates.
"""
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, sql_query


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={"projectId": pid, "name": name, "spaceKey": key})
    assert r.status_code == 200 and r.json().get("code") == "0", f"space create failed: {r.text[:300]}"
    return r.json()["data"]


def _publish_space(api, pid, space_id):
    # read the live version from the list projection (never assume — the
    # create/transition contract owns versioning server-side)
    r = api.get(f"/mock/spaces?projectId={pid}")
    row = next(s for s in r.json()["data"] if s["id"] == space_id)
    r = api.post(f"/mock/spaces/{space_id}/transition",
                 json={"targetStatus": 1, "expectedVersion": row["version"]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"space publish failed: {r.text[:300]}"


def _mk_case(api, pid, uniq):
    r = api.post("/cases", json={
        "projectId": pid, "title": f"MockBindingCase_{uniq}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2,
        "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开页面", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"case create failed: {r.text[:300]}"
    return r.json()["data"]["id"]


def _open_picker(w):
    w.locator('[data-test="mock-space-select"] .el-select__wrapper').click()


def _option(page, space_id):
    # the popper is teleported to body — search at page level, :visible filters
    # stale hidden poppers
    return page.locator(f'.el-select-dropdown:visible [data-test="mock-space-option-{space_id}"]').first


def test_t364d_case_bind_unbind_persists(logged_in, collectors, api_client, uniq):
    """Case editor: draft space taught unbindable, bind published -> reload
    prefill -> unbind -> DB null."""
    page = logged_in
    pid = _mk_project(api_client, f"T364d绑定项目{uniq}", f"t364d{uniq[:6]}")
    pub_id = _mk_space(api_client, pid, f"PubSpace{uniq[:6]}", f"pub{uniq[:6]}")
    draft_id = _mk_space(api_client, pid, f"DraftSpace{uniq[:6]}", f"drf{uniq[:6]}")
    _publish_space(api_client, pid, pub_id)
    case_id = _mk_case(api_client, pid, uniq)
    try:
        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="detail-case-no"]')).to_be_visible(timeout=15000)

        page.locator('[data-test="detail-edit"]').click()
        item = page.locator('[data-test="detail-edit-mock-space-item"]')
        expect(item).to_be_visible(timeout=8000)

        # picker teaches the space shapes up front: published option carries
        # the rule/traffic thumbnail, draft option is disabled unbindable
        _open_picker(item)
        pub_opt = _option(page, pub_id)
        expect(pub_opt).to_be_visible(timeout=8000)
        expect(pub_opt).to_have_attribute("data-bindable", "true")
        expect(pub_opt.locator('[data-test="space-thumbnail"]')).to_contain_text("规则数", timeout=5000)
        draft_opt = _option(page, draft_id)
        expect(draft_opt).to_have_attribute("data-bindable", "false")
        expect(draft_opt).to_contain_text("草稿·发布后可挂", timeout=5000)

        pub_opt.click()
        expect(item.locator('[data-test="mock-space-select"]')).to_contain_text(
            f"PubSpace{uniq[:6]}", timeout=5000)

        # wait for the real PUT to complete before reading the DB (the
        # case-no anchor is visible in both modes — no sync signal there)
        with page.expect_response(lambda r: "/api/v1/cases/" in r.url and r.request.method == "PUT", timeout=15000):
            page.locator('[data-test="detail-save-draft"]').click()

        # persistence door 1: the DB column (real chain, not a toast)
        r = api_client.get(f"/cases/{case_id}")
        assert r.json()["data"]["mockSpaceId"] == pub_id, \
            f"binding lost server-side: {r.json()['data'].get('mockSpaceId')}"

        # persistence door 2: reload + re-enter edit -> prefilled back
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="detail-case-no"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="detail-edit"]').click()
        item = page.locator('[data-test="detail-edit-mock-space-item"]')
        expect(item.locator('[data-test="mock-space-select"]')).to_contain_text(
            f"PubSpace{uniq[:6]}", timeout=10000)

        # unbind through the explicit "no mock" option -> column null
        _open_picker(item)
        page.locator('.el-select-dropdown:visible [data-test="mock-space-option-unbound"]').first.click()
        with page.expect_response(lambda r: "/api/v1/cases/" in r.url and r.request.method == "PUT", timeout=15000):
            page.locator('[data-test="detail-save-draft"]').click()
        r = api_client.get(f"/cases/{case_id}")
        assert r.json()["data"]["mockSpaceId"] is None, "unbind did not clear the column"

        assert_no_errors(collectors, "T364d case bind/unbind")
    finally:
        # cleanup order for the mock-binding FK cycle: cases/suites reference
        # the space, the space references the project. NULL the binding columns
        # (same shape the MockSpaceReferenceGate blocks on retire), drop the
        # spaces, then purge owns the rest (case family incl. tp_case_step).
        sql_query(f"UPDATE tp_test_case SET mock_space_id = NULL WHERE project_id = {pid}")
        sql_query(f"UPDATE tp_regression_suite SET mock_space_id = NULL WHERE project_id = {pid}")
        sql_query(f"DELETE FROM tp_mock_space WHERE project_id = {pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_t364d_suite_dialog_bind_persists(logged_in, collectors, api_client, uniq):
    """Suite dialog: create with the published space bound -> reload -> edit
    prefills -> unbind via dialog -> update clears it."""
    page = logged_in
    pid = _mk_project(api_client, f"T364d套件项目{uniq}", f"t364s{uniq[:6]}")
    pub_id = _mk_space(api_client, pid, f"SuiteSpace{uniq[:6]}", f"ssp{uniq[:6]}")
    _publish_space(api_client, pid, pub_id)
    env_ids = []
    r = api_client.post(f"/projects/{pid}/envs", json={
        "name": f"E{uniq[:6]}", "envType": 2, "baseUrl": f"https://s-{uniq[:6]}.tp-local.test"})
    assert r.json().get("code") == "0", f"env create failed: {r.text[:300]}"
    env_ids.append(r.json()["data"])
    case_id = _mk_case(api_client, pid, uniq)
    try:
        page.goto(f"{BASE_URL}/#/suites?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="suite-list-page"]')).to_be_visible(timeout=15000)

        page.locator('[data-test="suite-create-btn"]').click()
        dialog = page.locator('[data-test="suite-dialog"]')
        expect(dialog).to_be_visible(timeout=8000)
        dialog.locator('[data-test="suite-form-name"]').fill(f"MockBindingSuite{uniq[:6]}")
        # pick the case + env through their multi-selects
        for anchor, opt_text in (("suite-form-cases", f"MockBindingCase_{uniq}"),
                                 ("suite-form-envs", f"E{uniq[:6]}")):
            dialog.locator(f'[data-test="{anchor}"]').click()
            page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{opt_text}")').first.click()
            page.keyboard.press("Escape")

        # bind the published space via the shared picker
        bind_item = dialog.locator('[data-test="suite-form-mock-space-item"]')
        expect(bind_item).to_be_visible(timeout=8000)
        _open_picker(bind_item)
        _option(page, pub_id).click()

        with page.expect_response(lambda r: "/api/v1/suites" in r.url and r.request.method == "POST", timeout=15000):
            dialog.locator('[data-test="suite-form-submit"]').click()
        expect(page.locator('[data-test="suite-table"] tbody tr')).to_have_count(1, timeout=10000)

        r = api_client.get(f"/suites?projectId={pid}&page=1&size=20")
        rows = r.json()["data"]["list"]
        suite = rows[0]
        assert suite["mockSpaceId"] == pub_id, f"suite binding lost server-side: {suite}"

        # reload -> edit prefills the binding (persistence door)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="suite-table"] tbody tr')).to_have_count(1, timeout=10000)
        page.locator('[data-test="suite-edit-btn"]').first.click()
        dialog = page.locator('[data-test="suite-dialog"]')
        expect(dialog).to_be_visible(timeout=8000)
        expect(dialog.locator('[data-test="suite-form-mock-space-item"] [data-test="mock-space-select"]')).to_contain_text(
            f"SuiteSpace{uniq[:6]}", timeout=10000)

        # unbind in the dialog -> update must clear (full-write 0 sentinel);
        # the popper teleports to body — search at page level like _option
        _open_picker(dialog.locator('[data-test="suite-form-mock-space-item"]'))
        page.locator('.el-select-dropdown:visible [data-test="mock-space-option-unbound"]').first.click()
        with page.expect_response(lambda r: "/api/v1/suites/" in r.url and r.request.method == "PUT", timeout=15000):
            dialog.locator('[data-test="suite-form-submit"]').click()
        expect(page.locator('[data-test="suite-table"] tbody tr')).to_have_count(1, timeout=10000)
        r = api_client.get(f"/suites?projectId={pid}&page=1&size=20")
        rows = r.json()["data"]["list"]
        assert rows[0]["mockSpaceId"] is None, "dialog unbind did not clear the column"

        assert_no_errors(collectors, "T364d suite dialog bind/unbind")
    finally:
        # same FK-cycle break as the case test: NULL bindings -> drop spaces
        # -> purge owns suites/cases/project
        sql_query(f"UPDATE tp_test_case SET mock_space_id = NULL WHERE project_id = {pid}")
        sql_query(f"UPDATE tp_regression_suite SET mock_space_id = NULL WHERE project_id = {pid}")
        sql_query(f"DELETE FROM tp_mock_space WHERE project_id = {pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
