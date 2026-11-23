# -*- coding: utf-8 -*-

import json

from conftest import BASE_URL, assert_no_errors, nav_to, sql_query
from playwright.sync_api import expect

TPL = (
    "name: {name}\n"
    "industry: POS\n"
    "version: 1.0.0\n"
    "content_pack: v15-e2e-pack\n"
    "default_skills:\n  - V15 收银断言规范\n"
    "param_template:\n  currency: TWD\n"
    "project_types:\n  - pos\n"
)


def _go_last_page(page):
    """Walk the plugin pagination bar to the last page (layout carries
    prev/next arrows; newest rows sort last under the id-asc catalog).

    Two-branch semantics (5d): the bar renders only once the catalog exceeds
    one page (v-if list.length > pageSize, S-group worst-scale design), so a
    resident catalog at/below the page size legitimately has no pager — then
    the newest row is already on screen. Wait for first-row render before
    reading pager presence (post-upload refresh race), and pin the two branches
    explicitly instead of a bare conditional skip."""
    table = page.locator('[data-test="plugin-list-table"] tbody tr').first
    expect(table).to_be_visible(timeout=10000)  # list settled: v-if resolved
    pager = page.locator('[data-test="plugin-pagination"]')
    if pager.count() == 0:
        assert page.locator('[data-test="plugin-list-table"] tbody tr').count() <= 20, \
            "pager absent while more than one page of rows is rendered"
        return
    expect(pager).to_be_visible(timeout=8000)
    nxt = pager.locator(".btn-next")
    for _ in range(10):  # bounded walk; catalog pages never exceed this in tests
        if nxt.get_attribute("aria-disabled") == "true" or "is-disabled" in (nxt.get_attribute("class") or ""):
            return
        nxt.click()
        page.wait_for_timeout(300)  # table re-render window after page flip


def _upload_plugin(page, collectors, uniq):
    pass
    name = f"v15-e2e-{uniq}"
    page.goto(f"{BASE_URL}/#/plugins", wait_until="domcontentloaded")
    btn = page.locator('[data-test="plugin-upload-open"]').first
    expect(btn).to_be_visible(timeout=10000)
    btn.click()
    dialog = page.locator('[data-test="file-upload-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    path = f"/tmp/{name}.yaml"
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(TPL.format(name=name))
    dialog.locator('[data-test="file-upload-drop"] input[type="file"]').set_input_files(path)
    dialog.locator('[data-test="file-upload-submit"]').click()
    # Worst-scale paging : the catalog renders 20 rows a
    # page and this newest registration sorts to the LAST page (id asc), so
    # jump there before asserting the row — a real user does the same.
    _go_last_page(page)
    row = page.locator('[data-test="plugin-list-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=10000)
    plugin_id = int(sql_query(
        f"SELECT id FROM tp_plugin WHERE name='{name}' AND source=2").strip())
    assert plugin_id > 0
    return name, plugin_id


def _purge(name):
    pass
    sql_query(
        "DELETE FROM tp_project_plugin WHERE plugin_id IN "
        f"(SELECT id FROM tp_plugin WHERE name='{name}' AND source=2)")
    sql_query(f"DELETE FROM tp_plugin WHERE name='{name}' AND source=2")


def _open_drawer(page, plugin_id):
    # AN-5: menu leaf retired into /extensions. The host lands on its
    # default tab, so click through to the plugins tab before the row action.
    nav_to(page, "扩展集成", "/extensions", '[data-test="extensions-tabs"]', submenu="AI 能力")
    page.click('[data-test="extensions-tab-plugins"]')
    page.wait_for_selector('[data-test="plugin-list-page"]', timeout=10000)
    # Newest registrations sort to the last page under worst-scale paging —
    # follow the same user journey before opening the row drawer.
    _go_last_page(page)
    page.click(f'[data-test="plugin-view-open-{plugin_id}"]')
    drawer = page.locator('[data-test="plugin-detail-drawer"]')
    expect(drawer).to_be_visible(timeout=8000)
    return drawer


def _api_plugin_update(api_client, plugin_id, revision, version, name):
    pass
    content = TPL.format(name=name).replace("version: 1.0.0", f"version: {version}")
    r = api_client.put(f"/plugins/{plugin_id}", json={"revision": revision, "content": content})
    return r


def test_v15_plugin_detail_echo(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    name, plugin_id = _upload_plugin(page, collectors, uniq)
    try:
        resp = api_client.post("/projects", {"name": f"V15项目{uniq}", "code": f"V15{uniq.upper()}"[:20]})
        pid = resp.json()["data"]
        r = api_client.post(f"/projects/{pid}/plugins/{plugin_id}/apply", json={"confirm": True})
        assert r.json()["code"] == "0", f"装配失败: {r.text[:200]}"

        drawer = _open_drawer(page, plugin_id)
        desc = drawer.locator('[data-test="plugin-detail-desc"]')
        expect(desc).to_contain_text("v15-e2e-pack", timeout=8000)
        expect(desc).to_contain_text("V15 收银断言规范")
        expect(desc).to_contain_text("currency")
        expect(drawer.locator('[data-test="plugin-detail-revision"]')).to_contain_text("r0")
        applied = drawer.locator('[data-test="plugin-applied-projects-table"]')
        expect(applied).to_contain_text(f"V15项目{uniq}", timeout=8000)
        assert_no_errors(collectors, "V15 detail 回显")
        api_client.delete(f"/projects/{pid}")
    finally:
        _purge(name)


def test_v15_plugin_edit_persist(logged_in, collectors, uniq):
    pass
    page = logged_in
    name, plugin_id = _upload_plugin(page, collectors, uniq)
    try:
        drawer = _open_drawer(page, plugin_id)
        drawer.locator('[data-test="plugin-edit-open"]').click()
        version_input = drawer.locator('[data-test="plugin-edit-form"] input[data-test="plugin-field-version"]')
        expect(version_input).to_be_visible(timeout=8000)
        expect(version_input).to_have_value("1.0.0")
        version_input.fill("2.0.0")
        drawer.locator('[data-test="plugin-edit-submit"]').click()
        expect(page.locator(".el-message--success").filter(has_text="已保存")).to_be_visible(timeout=8000)

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        drawer2 = _open_drawer(page, plugin_id)
        expect(drawer2.locator('[data-test="plugin-detail-desc"]')).to_contain_text("2.0.0", timeout=8000)
        expect(drawer2.locator('[data-test="plugin-detail-revision"]')).to_contain_text("r1")

        row = sql_query(
            "SELECT version, manifest->>'version', revision FROM tp_plugin "
            f"WHERE id={plugin_id}").strip().split("|")
        assert row == ["2.0.0", "2.0.0", "1"], f"DB 三向不一致: {row}"
        assert_no_errors(collectors, "V15 编辑持久化")
    finally:
        _purge(name)


def test_v15_plugin_conflict_409(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    name, plugin_id = _upload_plugin(page, collectors, uniq)
    try:
        drawer = _open_drawer(page, plugin_id)
        r = _api_plugin_update(api_client, plugin_id, 0, "1.1.0", name)
        assert r.json()["code"] == "0", f"API 预置编辑失败: {r.text[:200]}"

        drawer.locator('[data-test="plugin-edit-open"]').click()
        version_input = drawer.locator('[data-test="plugin-edit-form"] input[data-test="plugin-field-version"]')
        expect(version_input).to_be_visible(timeout=8000)
        version_input.fill("9.9.9")
        drawer.locator('[data-test="plugin-edit-submit"]').click()

        expect(page.locator(".el-message--error").filter(has_text="当前版本 1")).to_be_visible(timeout=8000)

        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not (e["status"] == 409 and e["url"].endswith(f"/api/v1/plugins/{plugin_id}"))
        ]
        collectors["console_errors"] = [
            e for e in collectors["console_errors"] if "status of 409" not in e
        ]

        db = sql_query(f"SELECT version, revision FROM tp_plugin WHERE id={plugin_id}").strip()
        assert db.split("|") == ["1.1.0", "1"], f"冲突提交污染了库: {db}"
        assert_no_errors(collectors, "V15 乐观锁 409")
    finally:
        _purge(name)


def test_v15_plugin_invalid_blocked(logged_in, collectors, uniq):
    pass
    page = logged_in
    name, plugin_id = _upload_plugin(page, collectors, uniq)
    try:
        drawer = _open_drawer(page, plugin_id)
        drawer.locator('[data-test="plugin-edit-open"]').click()
        name_input = drawer.locator('[data-test="plugin-edit-form"] input[data-test="plugin-field-name"]')
        expect(name_input).to_be_visible(timeout=8000)
        name_input.fill("")
        drawer.locator('[data-test="plugin-edit-submit"]').click()
        errors = drawer.locator('[data-test="plugin-edit-errors"]')
        expect(errors).to_contain_text("缺少必填键：name", timeout=8000)

        db = sql_query(f"SELECT name, revision FROM tp_plugin WHERE id={plugin_id}").strip()
        assert db.split("|") == [name, "0"], f"非法提交污染了库: {db}"
        assert_no_errors(collectors, "V15 非法拦截")
    finally:
        _purge(name)
