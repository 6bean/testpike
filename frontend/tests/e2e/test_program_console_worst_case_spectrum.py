

from playwright.sync_api import Page, expect

from conftest import BASE_URL, mk_tester, purge_project, seed_project

PROGRAM_COUNT = 35       # worst-case listing scale for the program ledger


def _api_create_program(api, name, description="e2e fixture"):
    r = api.post("/programs", json={"name": name, "description": description})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _api_archive(api, program_id):
    r = api.put(f"/programs/{program_id}/state?state=0")
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]


def _api_delete_program(api, program_id):
    r = api.delete(f"/programs/{program_id}")
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]


def _open_programs(page: Page) -> None:
    page.goto(f"{BASE_URL}/#/programs", wait_until="domcontentloaded")
    expect(page.locator('[data-test="program-list-page"]')).to_be_visible(timeout=10000)


def _search_box(page: Page):
    # Element Plus passes $attrs through to the native input, so the anchor
    # element itself is the input to type into.
    return page.locator('[data-test="program-search"]').first


def _rows_of(page: Page):
    return page.locator('[data-test="program-table"] .el-table__body .el-table__row')


def _row_named(page: Page, name: str):
    return page.locator('[data-test="program-table"] .el-table__row', has_text=name)


def _open_row_more(page: Page, name: str, item_anchor: str) -> None:
    """Open the row 'more' dropdown and click one collapsed menu item.

    The lifecycle actions (archive/restore/delete) ride the dropdown since
    the vision collapse fix; the item popper renders at body level.
    """
    _row_named(page, name).locator('[data-test="program-more-btn"]').click()
    page.locator(f'[data-test="{item_anchor}"]').click()


def _segment(page: Page, index: int):
    return page.locator('[data-test="program-state-seg"] .el-radio-button').nth(index)


def _confirm_box(page: Page):
    box = page.locator(".el-message-box")
    expect(box).to_be_visible(timeout=5000)
    box.locator(".el-button--primary").click()


def test_program_list_holds_at_35_rows_with_instant_search(
        logged_in, page: Page, collectors, api_client, uniq):
    """35 fixture programs: the worst-scale posture is paging containment
    (20/page client-side, honest total shown), keyword search narrows the
    family and keeps paging usable; the state segments partition exactly."""
    prefix = f"集粗{uniq}"
    PAGE_SIZE = 20  # mirrors ProgramListView's client-side page size
    created = [_api_create_program(api_client, f"{prefix}-{i:02d}") for i in range(PROGRAM_COUNT)]
    try:
        _open_programs(page)
        _search_box(page).fill(prefix)
        expect(_rows_of(page)).to_have_count(PAGE_SIZE, timeout=15000)
        pag = page.locator('[data-test="program-pagination"]')
        expect(pag).to_be_visible()
        expect(pag).to_contain_text(str(PROGRAM_COUNT))
        pag.locator(".el-pager li").nth(1).click()
        expect(_rows_of(page)).to_have_count(PROGRAM_COUNT - PAGE_SIZE, timeout=10000)
        pag.locator(".el-pager li").first.click()
        expect(_rows_of(page)).to_have_count(PAGE_SIZE, timeout=10000)

        _search_box(page).fill(f"{prefix}-07")
        expect(_rows_of(page)).to_have_count(1, timeout=10000)
        expect(_row_named(page, f"{prefix}-07")).to_be_visible()

        _search_box(page).fill(f"{prefix}-0")  # suffixes 00..09
        expect(_rows_of(page)).to_have_count(10, timeout=10000)

        _api_archive(api_client, created[0])
        _api_archive(api_client, created[1])
        # The ledger loads once per mount and there is no server push: an
        # archive done outside the browser surfaces after a reload.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="program-list-page"]')).to_be_visible(timeout=10000)
        _search_box(page).fill(prefix)
        expect(_rows_of(page)).to_have_count(PAGE_SIZE, timeout=10000)
        expect(page.locator('[data-test="program-pagination"]')).to_contain_text(
            str(PROGRAM_COUNT - 2))
        _segment(page, 1).click()  # archived segment
        expect(_rows_of(page)).to_have_count(2, timeout=10000)
        _segment(page, 2).click()  # all
        expect(page.locator('[data-test="program-pagination"]')).to_contain_text(
            str(PROGRAM_COUNT))
        pag.locator(".el-pager li").nth(1).click()
        expect(_rows_of(page)).to_have_count(PROGRAM_COUNT - PAGE_SIZE, timeout=10000)
    finally:
        for pid in created:
            _api_delete_program(api_client, pid)


def test_program_lifecycle_walkthrough_through_the_ui(
        logged_in, page: Page, collectors, api_client, uniq):
    """Create -> edit -> archive (only visible under the archived segment) ->
    restore -> delete, every step on real endpoints through confirm boxes."""
    name = f"生命周期集{uniq}"
    renamed = f"生命周期集{uniq}改"
    try:
        _open_programs(page)
        page.locator('[data-test="program-create-btn"]').click()
        dialog = page.locator('[data-test="program-create-dialog"]')
        expect(dialog).to_be_visible(timeout=5000)
        dialog.locator('[data-test="program-form-name"]').first.fill(name)
        dialog.locator('[data-test="program-form-desc"]').first.fill("由 E2E 首跑创建")
        dialog.locator('[data-test="program-form-submit"]').click()
        expect(page.locator('[data-test="program-create-dialog"]')).to_be_hidden(timeout=5000)
        _search_box(page).fill(name)
        expect(_row_named(page, name)).to_be_visible(timeout=5000)

        _row_named(page, name).locator('[data-test="program-edit-btn"]').click()
        edit = page.locator('[data-test="program-edit-dialog"]')
        expect(edit).to_be_visible(timeout=5000)
        edit.locator('[data-test="program-form-name"]').first.fill(renamed)
        edit.locator('[data-test="program-form-submit"]').click()
        _search_box(page).fill(renamed)
        expect(_row_named(page, renamed)).to_be_visible(timeout=5000)

        # lifecycle actions ride the collapsed "more" dropdown since the
        # vision collapse fix; open the menu then click the menu item (the
        # popper renders at body level, so address it through the page).
        _open_row_more(page, renamed, 'program-archive-btn')
        _confirm_box(page)
        expect(_row_named(page, renamed)).to_be_hidden(timeout=5000)

        _segment(page, 1).click()  # archived segment
        expect(_row_named(page, renamed)).to_be_visible(timeout=5000)
        _open_row_more(page, renamed, 'program-restore-btn')
        _confirm_box(page)
        _segment(page, 0).click()  # back to active
        expect(_row_named(page, renamed)).to_be_visible(timeout=5000)

        _open_row_more(page, renamed, 'program-delete-btn')
        _confirm_box(page)
        expect(_row_named(page, renamed)).to_be_hidden(timeout=5000)
    finally:
        # sweep any survivor so the fixture family never leaks into the ledger
        page.goto(f"{BASE_URL}/#/programs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="program-list-page"]')).to_be_visible(timeout=10000)
        _segment(page, 2).click()
        _search_box(page).fill(f"生命周期集{uniq}")
        while _rows_of(page).count() > 0:
            _open_row_more(page, f"生命周期集{uniq}", 'program-delete-btn')
            _confirm_box(page)
            _rows_of(page).first.wait_for(state="detached", timeout=5000)


def test_program_drawer_mounts_project_and_member_panels(
        logged_in, page: Page, collectors, api_client, uniq):
    """Drawer flow: attach one visible project (fresh /users/me snapshot),
    the mounted table gains the row, detach clears it; the members tab adds a
    tester through remote user search and revokes the grant."""
    program_name = f"挂载集{uniq}"
    pid = _api_create_program(api_client, program_name)
    proj_id = seed_project(api_client, uniq, "集挂载")
    _, tester_nick = mk_tester(api_client, uniq, prefix="pm")
    try:
        _open_programs(page)
        page.reload(wait_until="domcontentloaded")  # refresh auth snapshot so the new project is attachable
        _open_programs(page)
        _search_box(page).fill(program_name)
        expect(_row_named(page, program_name)).to_be_visible(timeout=10000)

        _row_named(page, program_name).locator('[data-test="program-projects-btn"]').click()
        expect(page.locator('[data-test="program-drawer-projects"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="program-attach-select"]').click()
        # pick the freshly seeded project: its option label carries the seed name.
        # Scoped to :visible — closed poppers of other selects keep stale
        # .el-select-dropdown__item nodes in the DOM and would match first.
        proj_option = page.locator(".el-select-dropdown__item:visible", has_text="集挂载")
        expect(proj_option.first).to_be_visible(timeout=5000)
        proj_option.first.click()
        page.locator('[data-test="program-attach-btn"]').click()
        detach_btn = page.locator(f'[data-test="program-detach-{proj_id}"]')
        expect(detach_btn).to_be_visible(timeout=5000)

        detach_btn.click()
        _confirm_box(page)
        expect(detach_btn).to_be_hidden(timeout=5000)

        page.locator('[data-test="program-drawer-tabs"] .el-tabs__item', has_text="成员").click()
        member_select = page.locator('[data-test="program-member-select"]')
        member_select.click()
        member_input = page.locator(
            '[data-test="program-member-select"] input, input[data-test="program-member-select"]')
        member_input.first.fill(tester_nick)
        item = page.locator(".el-select-dropdown__item:visible", has_text=tester_nick).first
        expect(item).to_be_visible(timeout=8000)
        item.click()
        page.locator('[data-test="program-member-add-btn"]').click()
        member_row = page.locator('[data-test="program-member-table"] .el-table__row', has_text=tester_nick)
        expect(member_row).to_be_visible(timeout=5000)
        member_row.locator('[data-test^="program-member-remove-"]').click()
        _confirm_box(page)
        expect(member_row).to_be_hidden(timeout=5000)
    finally:
        purge_project(api_client, proj_id)
        _api_delete_program(api_client, pid)
