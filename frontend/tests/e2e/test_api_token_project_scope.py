"""GA1 token project-scope browser spectrum .

Real browser + real admin->Feign->core->DB chain (strict-testing-standards;
no mocks). Three faces of the create form and the list echo it owes:
1. pinned form - tick a project in the dialog -> submit stores the ascending
 id string, the list row echoes the project name as a scope chip.
2. unrestricted - submit without any tick -> the unset guidance names the
 field and its consequence (parameter literal + revoke-and-recreate), and
 the DB column stays NULL (legacy zero-drift form) while the list row
 carries the honest "unrestricted" tag instead of a blank cell.
3. worst scale - the login snapshot holds >=30 visible projects: the picker
 is filterable and a typed name narrows the popper to the single match,
 which is then pinned through the search path (SE rule for >10 options).
All fixture rows are created through the real API door (conftest api_client,
single source) and purged at exit; the tokens end revoked (tp_api_token has
no delete door - revoking is the business terminal state, same discipline as
test_t169_api_token).
"""
import uuid

from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    assert_no_errors,
    inject_user_token,
    nav_to,
    purge_project,
    seed_project,
    sql_query,
)
from playwright.sync_api import expect

PAGE_ANCHOR = '[data-test="api-token-page"]'
DIALOG = '[data-test="token-create-dialog"]'
SCOPE_HELP = '[data-test="token-project-scope-hint"]'


def _open_token_page(page):
    nav_to(page, "API Token", "/api-tokens", PAGE_ANCHOR, submenu="系统管理")
    expect(page.locator('[data-test="token-table"]')).to_be_visible(timeout=10000)


def _row(page, name):
    return page.locator('[data-test="token-table"] tbody tr').filter(has_text=name).first


def _relogin(page):
    """Refresh the auth.projects snapshot (it is taken at login time)."""
    inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)


def _dismiss_dropdown(page, dialog):
    """Collapse any open select popper WITHOUT Escape: the dialog binds
    close-on-press-escape, so a stray Escape destroys the whole create form
    (empirical: first spec run failed 3/3 right after each Escape press with
    the dialog gone). A click on the dialog header is outside the popper and
    inert for the form."""
    dialog.locator(".el-dialog__header").click()


def _fill_name_and_scope(page, dialog, name):
    dialog.locator('[data-test="token-create-name"]').fill(name)
    scope_select = dialog.locator('[data-test="token-create-scopes"]')
    scope_select.click()
    option = page.locator('.tp-token-scope-popper .el-select-dropdown__item',
                          has_text="mcp.read").first
    expect(option).to_be_visible()
    option.click()
    _dismiss_dropdown(page, dialog)


def _expect_pinned_hint(dialog, n):
    expect(dialog.locator(SCOPE_HELP)).to_contain_text(f"已限定 {n} 个项目")


def _submit_and_close_plain(page, dialog):
    with page.expect_response(
        lambda r: "/api/v1/api-tokens" in r.url and r.request.method == "POST"
        and r.status == 200
    ):
        dialog.locator('[data-test="token-create-submit"]').click()
    plain = page.locator('[data-test="token-plaintext-dialog"]')
    expect(plain).to_be_visible()
    plain.locator('[data-test="token-plain-done"]').click()
    expect(plain).to_be_hidden()


def _open_create_dialog(page, name):
    page.click('[data-test="token-create-open"]')
    dialog = page.locator(DIALOG)
    expect(dialog).to_be_visible()
    _fill_name_and_scope(page, dialog, name)
    return dialog


def _pick_projects(page, dialog, option_texts):
    picker = dialog.locator('[data-test="token-create-project-scope"]')
    picker.click()
    for needle in option_texts:
        # Element Plus keeps filtered-out options in the DOM hidden via
        # v-show, so both the popper class and the item-level :visible
        # filter are required to read the live option set.
        hit = page.locator('.tp-token-project-scope-popper '
                           '.el-select-dropdown__item:visible',
                           has_text=needle).first
        expect(hit).to_be_visible(timeout=5000)
        hit.click()
    _dismiss_dropdown(page, dialog)


def _revoke(page, name):
    row = _row(page, name)
    expect(row).to_be_visible(timeout=8000)
    row.locator('[data-test="ra-revoke"]').click()
    confirm = page.locator('.el-message-box:visible')
    expect(confirm).to_be_visible()
    with page.expect_response(
        lambda r: "/api/v1/api-tokens" in r.url and r.request.method == "DELETE"
        and r.status == 200
    ):
        confirm.get_by_role("button", name="吊销").click()


# ── 1. pinned form: checked project -> id string in DB, name echoed ────────

def test_token_pinned_project_scope_form(logged_in, collectors, api_client):
    page = logged_in
    suffix = uuid.uuid4().hex[:8]
    pid = seed_project(api_client, uuid.uuid4().hex, "E2EP",
                       display_name=f"e2e-scope-pinned-{suffix}")
    _relogin(page)  # the picker source is the login snapshot

    name = f"e2e-pinned-{suffix}"
    _open_token_page(page)
    dialog = _open_create_dialog(page, name)
    _pick_projects(page, dialog, [f"e2e-scope-pinned-{suffix}"])
    _expect_pinned_hint(dialog, 1)
    _submit_and_close_plain(page, dialog)

    stored = sql_query(
        "SELECT project_scope FROM tp_api_token "
        f"WHERE name='{name}'")
    assert stored, f"token row missing in DB: {name}"
    assert stored.strip() == str(pid), \
        f"project_scope should store the pinned id, got: {stored!r}"

    _open_token_page(page)
    cell = _row(page, name).inner_text()
    assert f"e2e-scope-pinned-{suffix}" in cell, \
        f"list row must echo the pinned project name, got: {cell!r}"
    assert "不限项目" not in cell, "pinned token must not read as unrestricted"

    _revoke(page, name)
    purge_project(api_client, pid)
    assert_no_errors(collectors, "token pinned project scope")


# ── 2. unrestricted form: no tick -> R32 unset guidance + honest tag ────────

def test_token_unrestricted_form_and_tag(logged_in, collectors):
    page = logged_in
    suffix = uuid.uuid4().hex[:8]
    name = f"e2e-free-{suffix}"

    _open_token_page(page)
    page.click('[data-test="token-create-open"]')
    dialog = page.locator(DIALOG)
    expect(dialog).to_be_visible()
    _fill_name_and_scope(page, dialog, name)

    # unset guidance names the field and its consequence (no self-referential
    # menu path: the hint already renders inside the create dialog)
    hint = dialog.locator(SCOPE_HELP)
    expect(hint).to_be_visible()
    hint_text = hint.inner_text()
    assert "受限项目" in hint_text and "吊销" in hint_text, \
        f"unset guidance must name the field and the consequence: {hint_text!r}"

    _submit_and_close_plain(page, dialog)

    stored = sql_query(
        "SELECT COALESCE(project_scope, '<NULL>') FROM tp_api_token "
        f"WHERE name='{name}'")
    assert stored.strip() == "<NULL>", \
        f"unpinned token must persist NULL (zero-drift legacy form), got: {stored!r}"

    _open_token_page(page)
    row = _row(page, name)
    expect(row.locator('[data-test="token-scope-unlimited"]')).to_be_visible()
    assert "不限项目" in row.inner_text(), "unrestricted tag wording missing"

    _revoke(page, name)
    assert_no_errors(collectors, "token unrestricted form and tag")


# ── 3. worst scale: >=30 visible projects, search narrows to one match ─────

def test_token_project_picker_search_at_worst_scale(logged_in, collectors, api_client):
    page = logged_in
    suffix = uuid.uuid4().hex[:8]
    target = f"e2e-ws-{suffix}-07"
    pids = []
    for i in range(30):
        pids.append(seed_project(
            api_client, uuid.uuid4().hex, "E2EW",
            display_name=f"e2e-ws-{suffix}-{i:02d}"))
    _relogin(page)  # snapshot must carry the >=30 visible projects

    name = f"e2e-ws-{suffix}"
    _open_token_page(page)
    dialog = _open_create_dialog(page, name)

    picker = dialog.locator('[data-test="token-create-project-scope"]')
    picker.click()
    picker.locator("input").fill(target)
    narrowed = page.locator('.tp-token-project-scope-popper '
                            '.el-select-dropdown__item:visible')
    expect(narrowed).to_have_count(1, timeout=5000)
    assert target in narrowed.first.inner_text(), \
        f"search must narrow to the typed project, got: {narrowed.first.inner_text()!r}"
    narrowed.first.click()
    _expect_pinned_hint(dialog, 1)
    _dismiss_dropdown(page, dialog)

    _submit_and_close_plain(page, dialog)

    _open_token_page(page)
    cell = _row(page, name).inner_text()
    assert target in cell, \
        f"row must echo the searched-and-pinned project name: {cell!r}"

    _revoke(page, name)
    for pid in pids:
        purge_project(api_client, pid)
    assert_no_errors(collectors, "token picker search at worst scale")
