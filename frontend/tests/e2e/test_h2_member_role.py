
import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, add_member, assert_no_errors, mk_tester, sql_query


PWD = "Test@12345"


@pytest.fixture
def h2_project(api_client, uniq):
    pass
    name = f"H2成员{uniq}"
    code = f"H2E{uniq.upper()}"
    resp = api_client.post("/projects", json={"name": name, "code": code})
    assert resp.status_code == 200 and resp.json().get("code") == "0", f"建项目失败: {resp.text[:300]}"
    pid = resp.json()["data"]
    uid, nick = mk_tester(api_client, uniq, prefix="h2")
    add_member(api_client, pid, uid)
    yield pid, uid, nick, code
    api_client.delete(f"/projects/{pid}/purge?force=true")
    api_client.delete(f"/users/{uid}")


def _member_row(page, nick):
    return page.locator('[data-test="member-table"] tbody tr').filter(has_text=nick)


def _open_members_tab(page, pid):
    page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    expect(page.locator('.el-tabs__item:has-text("成员管理")')).to_be_visible(timeout=10000)
    page.click('.el-tabs__item:has-text("成员管理")')
    page.wait_for_selector('[data-test="member-table"]', timeout=10000)


def test_h2_set_owner_demote_persist(logged_in, collectors, api_client, h2_project, uniq):
    pass
    page = logged_in
    pid, uid, nick, code = h2_project

    _open_members_tab(page, pid)
    row = _member_row(page, nick)
    expect(row).to_be_visible(timeout=8000)
    expect(row.locator('[data-test="member-set-owner"]')).to_be_visible()
    expect(row).to_contain_text("成员")

    demote_btns = page.locator('[data-test="member-demote-owner"]')
    expect(demote_btns.first).to_be_visible(timeout=5000)
    assert demote_btns.count() == 1, f"初始应仅一名负责人: {demote_btns.count()}"
    expect(demote_btns.first).to_be_disabled()

    row.locator('[data-test="member-set-owner"]').click()
    box = page.locator(".el-message-box")
    expect(box).to_be_visible(timeout=5000)
    expect(box).to_contain_text(nick)
    with page.expect_response(
        lambda r: r.request.method == "PUT"
        and f"/projects/{pid}/members/{uid}" in r.url
        and '"role":2' in (r.request.post_data or "")
    ):
        box.locator(".el-message-box__btns button.el-button--primary").click()
    toast = page.locator(".el-message--success").filter(has_text="设为项目负责人")
    expect(toast).to_be_visible(timeout=8000)
    expect(_member_row(page, nick)).to_contain_text("项目负责人", timeout=8000)
    assert sql_query(
        f"SELECT role FROM tp_user_project WHERE project_id={pid} AND user_id={uid}"
    ) == "2", "DB role 应为 2（设负责人落库）"

    _open_members_tab(page, pid)
    expect(_member_row(page, nick)).to_contain_text("项目负责人", timeout=8000)

    row2 = _member_row(page, nick)
    demote = row2.locator('[data-test="member-demote-owner"]')
    expect(demote).to_be_visible(timeout=5000)
    expect(demote).to_be_enabled()
    demote.click()
    box = page.locator(".el-message-box")
    expect(box).to_be_visible(timeout=5000)
    expect(box).to_contain_text(nick)
    with page.expect_response(
        lambda r: r.request.method == "PUT"
        and f"/projects/{pid}/members/{uid}" in r.url
        and '"role":1' in (r.request.post_data or "")
    ):
        box.locator(".el-message-box__btns button.el-button--primary").click()
    toast2 = page.locator(".el-message--success").filter(has_text="降为普通成员")
    expect(toast2).to_be_visible(timeout=8000)
    assert sql_query(
        f"SELECT role FROM tp_user_project WHERE project_id={pid} AND user_id={uid}"
    ) == "1", "DB role 应为 1（降成员落库）"

    _open_members_tab(page, pid)
    row3 = _member_row(page, nick)
    expect(row3.locator('[data-test="member-set-owner"]')).to_be_visible(timeout=8000)

    assert_no_errors(collectors, "H2 成员角色 设负责人/降成员")


def test_h2_purge_zero_residue(api_client, h2_project, uniq):
    pass
    pid, uid, nick, code = h2_project
    r = api_client.delete(f"/projects/{pid}/purge?force=true")
    assert r.status_code == 200, f"purge 失败: {r.text[:200]}"
    api_client.delete(f"/users/{uid}")
    assert sql_query(
        f"SELECT count(*) FROM tp_project WHERE code LIKE 'H2E{uniq.upper()}%'"
    ) == "0"
    assert sql_query(
        f"SELECT count(*) FROM tp_user_project WHERE user_id={uid}"
    ) == "0"
    assert sql_query(
        f"SELECT count(*) FROM tp_user WHERE username LIKE 'h2tester_{uniq}' AND deleted = 0"
    ) == "0"
