

import datetime

from conftest import (
    assert_no_errors,
    inject_user_token,
    nav_to,
    reload_boot_snapshot,
    sql_query,
)
from playwright.sync_api import expect


def _to_report_center(page):
    """ half B: the report-center leaf merged into the /report export tab.

 nav_to clicks the sidebar (hash-only goto has an SPA init race, pitfall #1)
 then switches the page-level tab; rc-* assertion anchors survive embedded.
 """
    nav_to(page, "统计报表", "/report", '[data-test="report-page-tabs"]', submenu="结果与缺陷")
    page.locator('[data-test="report-tab-export"]').first.click()
    expect(page.locator('[data-test="report-center-page"]')).to_be_visible(timeout=15000)


def _last_week_window() -> tuple[str, str]:
    pass
    today = datetime.datetime.now().date()
    monday = today - datetime.timedelta(days=today.weekday() + 7)
    sunday = monday + datetime.timedelta(days=7)
    return monday.isoformat(), sunday.isoformat()


def _make_project(api, uniq: str) -> tuple[int, str]:
    name = f"O10报表_{uniq}"
    r = api.post("/projects", json={"name": name, "code": f"O10R{uniq[:6].upper()}"})
    assert r.status_code == 200, f"创建项目失败: {r.text[:200]}"
    return r.json()["data"], name


def _cleanup_project(pid: int) -> None:
    sql_query(f"DELETE FROM tp_report WHERE scope_type = 1 AND scope_id = {pid}")
    sql_query(f"DELETE FROM tp_user_project WHERE project_id = {pid}")
    sql_query(f"DELETE FROM tp_project WHERE id = {pid}")


def _select_project(page, name: str) -> None:
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{name}")').first
    expect(option).to_be_visible(timeout=10000)
    option.click()



def test_rc_page_smoke_detail_export(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid, name = _make_project(api_client, uniq)
    week_from, week_to = _last_week_window()
    try:
        r = api_client.post("/report-center/generate", json={
            "reportType": 1, "scopeType": 1, "scopeId": pid,
            "periodStart": f"{week_from}T00:00", "periodEnd": f"{week_to}T00:00"})
        assert r.status_code == 200, f"generate 失败: {r.text[:200]}"
        rid = r.json()["data"]["id"]

        # The project was created out-of-band after login, so the boot-time
        # header snapshot lacks it — refresh before the dropdown interaction.
        reload_boot_snapshot(page)
        _to_report_center(page)
        _select_project(page, name)
        table = page.locator('[data-test="rc-table"]')
        expect(table).to_be_visible()
        row = page.locator('[data-test="rc-row-scope"]').filter(has_text=name).first
        expect(row).to_be_visible(timeout=10000)
        assert page.locator('[data-test="rc-generate-open"]').count() == 1

        page.locator('[data-test="rc-detail-btn"]').first.click()
        drawer = page.locator('[data-test="rc-detail-drawer"]')
        expect(drawer).to_be_visible(timeout=10000)
        versions = drawer.locator('[data-test="rc-caliber-version"]')
        expect(versions.first).to_have_text("v1.0", timeout=10000)
        expect(drawer.locator('[data-test="rc-ai-quality"]')).to_be_visible()

        sql_query(
            "UPDATE tp_report SET payload = jsonb_set(payload, '{calibers,pass-rate,version}',"
            " '\"v9.9\"') WHERE id = " + str(rid))
        page.keyboard.press("Escape")
        expect(drawer).to_be_hidden(timeout=10000)
        page.locator('[data-test="rc-detail-btn"]').first.click()
        expect(drawer).to_be_visible(timeout=10000)
        pass_rate_cell = drawer.locator('[data-test="rc-caliber-table"] tr').filter(
            has_text="通过率").first
        pass_rate_cell.locator('[data-test="caliber-hover"]').hover()
        tip = page.locator('.el-popper').filter(has_text="通过率 v9.9=").first
        expect(tip).to_be_visible(timeout=5000)
        page.keyboard.press("Escape")
        expect(drawer).to_be_hidden(timeout=10000)

        with page.expect_download() as csv_info:
            page.locator('[data-test="rc-export-csv"]').first.click()
        csv_dl = csv_info.value
        assert csv_dl.suggested_filename.endswith(".csv") and "周报_" in csv_dl.suggested_filename
        with page.expect_download() as pdf_info:
            page.locator('[data-test="rc-export-pdf"]').first.click()
        assert pdf_info.value.suggested_filename.endswith(".pdf")

        assert_no_errors(collectors, "报表中心 ADMIN 主链")
    finally:
        _cleanup_project(pid)



def test_rc_permission_legs(page, collectors, api_client, uniq):
    pid, name = _make_project(api_client, uniq)
    week_from, week_to = _last_week_window()
    r = api_client.post("/report-center/generate", json={
        "reportType": 1, "scopeType": 1, "scopeId": pid,
        "periodStart": f"{week_from}T00:00", "periodEnd": f"{week_to}T00:00"})
    assert r.status_code == 200, f"generate 失败: {r.text[:200]}"
    user = f"m4rc{uniq[:6]}"
    pwd = f"Rc{uniq}!2026"
    r = api_client.post("/users", json={
        "username": user, "password": pwd,
        "displayName": f"M4RC测试{uniq[:6]}", "role": 1})
    assert r.status_code == 200, f"创建用户失败: {r.text[:200]}"
    try:
        inject_user_token(page, user, pwd)
        _to_report_center(page)
        expect(page.locator('[data-test="rc-table"]')).to_be_visible()
        assert page.locator('[data-test="rc-generate-open"]').count() == 0
        expect(page.locator('[data-test="rc-table"] [data-test="empty-state"]')).to_be_visible()

        core_uid = sql_query(f"SELECT id FROM tp_user WHERE username='{user}'")
        sql_query(f"INSERT INTO tp_user_project (project_id, user_id, role) VALUES ({pid}, {core_uid}, 1)")
        inject_user_token(page, user, pwd)
        _to_report_center(page)
        _select_project(page, name)
        expect(page.locator('[data-test="rc-row-scope"]').filter(has_text=name).first)\
            .to_be_visible(timeout=10000)
        assert_no_errors(collectors, "报表中心 权限链路")
    finally:
        _cleanup_project(pid)
        sql_query(f"DELETE FROM tp_user_role WHERE user_id IN"
                  f" (SELECT id FROM tp_user WHERE username='{user}')")
        sql_query(f"DELETE FROM tp_user WHERE username='{user}'")
