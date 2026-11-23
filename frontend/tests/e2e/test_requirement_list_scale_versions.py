"""Requirement ledger page first-delivery E2E (AL batch frontend package).

Covers the two acceptance faces that the older CRUD spec never touched:
- Worst-case scale: 32 ledger rows in one project, page size 10 -> the
 pagination bar must be present, switch pages and keep the visible segment
 bounded (S-group scale contract, no unbounded inline rendering).
- Version-chain dialog: row action "versions" consumes
 GET /requirements/{id}/versions and renders the snapshots in descending
 version order with the target title echoed (read-only surface; the
 corrupted-flag branch is server data we do not fabricate here).

Real browser + real admin->core chain only; every assertion is unconditional
(no `if count>0` wrapping), all traffic goes through the shared monitors
(4xx/5xx, console error/warning, Issues panel) attached by conftest.
"""
from playwright.sync_api import expect

from conftest import assert_no_errors, nav_to, pick_project_in_header, row_cmd

_PAGE_SIZE = 10  # RequirementListView default `size = ref(10)`


def _mk_project(api, uniq):
    name = f"RList{uniq[:8]}"
    r = api.post("/projects", json={"name": name, "code": f"RL{uniq[:6].upper()}"})
    assert r.status_code == 200, f"project create failed: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"project create business failure: {body.get('msg')}"
    return body["data"], name


def _mk_requirement(api, pid, title):
    r = api.post(f"/projects/{pid}/requirements",
                 json={"source": 3, "title": title})
    assert r.status_code == 200, f"requirement create failed: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"requirement create business failure: {body.get('msg')}"
    return body["data"]


def _open_req_page(page):
    nav_to(page, "需求点管理", "/requirements", '[data-test="req-page"]', submenu="设计用例")
    expect(page.locator('[data-test="project-ctx-select"]')).to_be_visible(timeout=10000)


def _data_rows(page):
    return page.locator('[data-test="req-table"] tbody tr.el-table__row')


def test_requirement_list_worst_case_scale(logged_in, api_client, collectors, uniq):
    """32 rows -> bounded page segment, working pagination and keyword filter, persistence on reload."""
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    marker = f"marker{uniq[:8]}"

    for i in range(31):
        _mk_requirement(api_client, pid, f"{uniq}-req-{i:02d}")
    anchor = _mk_requirement(api_client, pid, f"{uniq}-{marker}")

    # Fresh /users/me so the header context can land on the new project (R30 posture).
    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    pick_project_in_header(page, pname)

    # The ledger segment for a brand-new project shows exactly the fixture rows:
    # first page is bounded to the configured page size, total says 32.
    expect(_data_rows(page)).to_have_count(_PAGE_SIZE, timeout=10000)
    total_cell = page.locator('[data-test="req-pagination"] .el-pagination__total')
    expect(total_cell).to_contain_text("32")

    first_page_titles = [t.inner_text() for t in _data_rows(page).locator(".req-title").all()]

    # Page 2 must render a different segment, still bounded.
    page.locator('[data-test="req-pagination"] .number').filter(
        has_text="2").first.click()
    expect(_data_rows(page)).to_have_count(_PAGE_SIZE, timeout=10000)
    second_page_titles = [t.inner_text() for t in _data_rows(page).locator(".req-title").all()]
    assert set(first_page_titles).isdisjoint(second_page_titles), \
        "page 2 repeated page-1 rows -> pagination not switching the slice"

    # Keyword filter narrows to exactly the marker row (S-group instant search).
    page.locator('[data-test="req-filter-keyword"]').fill(marker)
    page.locator('[data-test="req-search"]').click()
    expect(_data_rows(page)).to_have_count(1, timeout=10000)
    expect(page.locator(".req-title")).to_contain_text(marker)

    # Clearing restores the bounded first page (32 total, 10 visible).
    page.locator('[data-test="req-filter-keyword"]').fill("")
    page.locator('[data-test="req-search"]').click()
    expect(_data_rows(page)).to_have_count(_PAGE_SIZE, timeout=10000)
    expect(total_cell).to_contain_text("32")

    # Reload persistence: the segment survives a real document re-entry.
    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    expect(_data_rows(page)).to_have_count(_PAGE_SIZE, timeout=10000)
    expect(total_cell).to_contain_text("32")

    assert_no_errors(collectors, "requirement list worst-case scale")


def test_requirement_version_history_dialog(logged_in, api_client, collectors, uniq):
    """Create -> edit -> edit -> status-change writes 4 snapshots; dialog renders them descending."""
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    req = _mk_requirement(api_client, pid, f"{uniq}-chain")

    version = req.get("version", 1)
    for step in (1, 2):
        r = api_client.put(f"/projects/{pid}/requirements/{req['id']}", json={
            "title": f"{uniq}-chain-edit{step}", "expectedVersion": version})
        assert r.status_code == 200, f"edit {step} failed: {r.status_code} {r.text[:300]}"
        body = r.json()
        assert body.get("code") == "0", f"edit {step} business failure: {body.get('msg')}"
        version = body["data"].get("version", version + 1)
    r = api_client.put(f"/projects/{pid}/requirements/{req['id']}", json={
        "status": 2, "expectedVersion": version})
    assert r.status_code == 200, f"status change failed: {r.status_code} {r.text[:300]}"
    assert r.json().get("code") == "0", f"status change business failure: {r.json().get('msg')}"
    expected_snapshots = version + 1  # create(1) + two edits + one status change

    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    pick_project_in_header(page, pname)

    row = _data_rows(page).filter(
        has=page.locator(f'[data-test="req-title-{req["id"]}"]')).first
    expect(row).to_be_visible(timeout=10000)

    with page.expect_response(
            lambda resp: f"/requirements/{req['id']}/versions" in resp.url, timeout=15000) as got:
        row_cmd(page, row, "versions")
    versions_response = got.value
    assert versions_response.status == 200, \
        f"versions endpoint answered {versions_response.status}: {versions_response.text()[:300]}"

    dialog = page.locator('[data-test="req-version-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)
    expect(dialog.locator('[data-test="req-version-target"]')).to_contain_text(f"{uniq}-chain-edit2")

    version_rows = dialog.locator('[data-test="req-version-table"] tbody tr.el-table__row')
    expect(version_rows).to_have_count(expected_snapshots, timeout=10000)
    shown = [int(version_rows.nth(i).locator("td").nth(0).inner_text().strip())
             for i in range(expected_snapshots)]
    assert shown == sorted(shown, reverse=True), \
        f"version chain not descending: {shown}"
    assert shown[0] == expected_snapshots, f"head snapshot missing: {shown}"

    # Close the dialog and leave no traffic error behind (monitors below cover it).
    dialog.get_by_role("button", name="关闭", exact=True).click()
    expect(dialog).to_be_hidden(timeout=10000)

    assert_no_errors(collectors, "requirement version history dialog")
