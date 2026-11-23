
import re

import httpx
import pytest
from conftest import (
    ADMIN_PASSWORD,
    API_BASE,
    BASE_URL,
    assert_no_errors,
    inject_user_token,
    open_apitest_datasource_item,
    pick_project_in_header,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)


def _api_ok(r):
    assert r.status_code == 200 and str(r.json().get("code")) == "0", f"API 失败: {r.text[:300]}"
    return r.json()["data"]


def _select_option(page, anchor, option_text):
    page.locator(f'[data-test="{anchor}"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=option_text).first
    expect(option).to_be_visible(timeout=10000)
    option.click()


def _open_subscription_drawer(page, project_name):
    expect(page.locator('[data-test="api-test-page"]')).to_be_visible(timeout=15000)
    # anchor migration: the page-level project selector is retired — the
    # header global context is the only project switch, and the subscription
    # door now lives inside the rail data-source dropdown.
    pick_project_in_header(page, project_name)
    open_apitest_datasource_item(page, "apitest-subscription-btn")
    panel = page.locator('[data-test="spec-subscription-panel"]')
    expect(panel).to_be_visible(timeout=5000)
    return panel


def test_w1c_spec_subscription_panel(page, collectors, api_client, uniq):
    pass
    pid = _api_ok(api_client.post("/projects", json={
        "name": f"W1C订阅项目{uniq}", "code": f"W1C{uniq[:6].upper()}"}))
    project_name = f"W1C订阅项目{uniq}"
    sub_name = f"w1c订阅{uniq}"
    sub_url = "http://localhost:8899/login.html"
    try:
        inject_user_token(page, "admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        panel = _open_subscription_drawer(page, project_name)

        expect(panel).to_contain_text("还没有订阅源", timeout=10000)

        panel.locator('[data-test="sub-create-btn"]').click()
        form = page.locator('[data-test="sub-form"]')
        expect(form).to_be_visible(timeout=5000)
        form.locator('[data-test="sub-name-input"]').fill(sub_name)
        form.locator('[data-test="sub-url-input"]').fill(sub_url)
        form.locator('[data-test="sub-interval-input"] input').first.fill("120")
        form.locator('[data-test="sub-form-save-btn"]').click()
        row = panel.locator('[data-test="sub-table"] tbody tr').first
        expect(row).to_contain_text(sub_name, timeout=15000)
        expect(row).to_contain_text("120")

        row.locator('[data-test="sub-edit-btn"]').click()
        expect(form).to_be_visible(timeout=5000)
        name_input = form.locator('[data-test="sub-name-input"]')
        expect(name_input).to_have_value(sub_name, timeout=5000)
        edited = f"{sub_name}改"
        name_input.fill(edited)
        form.locator('[data-test="sub-form-save-btn"]').click()
        expect(row).to_contain_text(edited, timeout=15000)

        sw = row.locator('[data-test="sub-enabled-switch"]')
        expect(sw).to_have_class(re.compile(r"is-checked"), timeout=5000)
        sw.click()
        expect(sw).not_to_have_class(re.compile(r"is-checked"), timeout=5000)
        sw.click()
        expect(sw).to_have_class(re.compile(r"is-checked"), timeout=5000)

        panel.locator('[data-test="sub-create-btn"]').click()
        form.locator('[data-test="sub-name-input"]').fill(f"bad{uniq}")
        form.locator('[data-test="sub-url-input"]').fill("ftp://x/y.json")
        form.locator('[data-test="sub-form-save-btn"]').click()
        expect(page.locator(".el-message--error")).to_contain_text("只支持 http/https", timeout=10000)
        page.locator('[data-test="sub-form-back-btn"]').click()
        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"] if "subscriptions" not in str(e.get("url", ""))]
        collectors["console_errors"] = [
            c for c in collectors["console_errors"] if "status of 40" not in c]

        r_dup = api_client.post("/api-specs/subscriptions", json={
            "projectId": pid, "name": edited, "url": sub_url, "checkIntervalMinutes": 120})
        assert r_dup.status_code == 409 or str(r_dup.json().get("code")) not in ("0",), \
            f"同名重建立面未拒绝: {r_dup.text[:200]}"

        panel.locator('[data-test="sub-close-btn"]').click()
        page.reload(wait_until="domcontentloaded")
        panel = _open_subscription_drawer(page, project_name)
        expect(panel.locator('[data-test="sub-table"] tbody tr').first).to_contain_text(edited, timeout=15000)
        db_row = sql_query(
            f"SELECT name || '|' || url || '|' || check_interval_minutes FROM tp_spec_subscription WHERE project_id = {pid}")
        assert db_row == f"{edited}|{sub_url}|120", f"DB 行与 UI 不符: {db_row}"

        panel.locator('[data-test="sub-table"] tbody tr').first.locator('[data-test="sub-delete-btn"]').click()
        page.locator(".el-message-box__btns button").last.click()
        expect(panel).to_contain_text("还没有订阅源", timeout=10000)
        assert sql_query(f"SELECT count(*) FROM tp_spec_subscription WHERE project_id = {pid}") == "0", "删除未落库"

        assert_no_errors(collectors, "W1c 契约订阅管理面板全链")
    finally:
        sql_query(f"DELETE FROM tp_spec_subscription WHERE project_id = {pid}")
        sql_query(f"DELETE FROM tp_api_spec WHERE project_id = {pid}")
        sql_query(f"DELETE FROM tp_api_spec_preview WHERE project_id = {pid}")
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")


_MIN_OPENAPI = (
    '{"openapi":"3.0.1","info":{"title":"mini","version":"1.0"},'
    '"paths":{"/demo/ping":{"get":{"responses":{"200":{"description":"ok"}}}}}}'
)


def test_w1c_spec_subscription_program_source(page, collectors, api_client, uniq):
    pass
    consumer_name = f"W1PS消费{uniq}"
    provider_name = f"W1PS提供{uniq}"
    sub_name = f"w1ps订阅{uniq}"
    spec_name = f"mini{uniq[:8].lower()}"
    pid = _api_ok(api_client.post("/projects", json={
        "name": consumer_name, "code": f"W1PSC{uniq[:6].upper()}"}))
    pid_provider = _api_ok(api_client.post("/projects", json={
        "name": provider_name, "code": f"W1PSP{uniq[:6].upper()}"}))
    prg = _api_ok(api_client.post("/programs", json={
        "name": f"W1PS集{uniq}", "description": "t287 e2e program"}))
    _api_ok(api_client.post(f"/programs/{prg}/projects/{pid}"))
    _api_ok(api_client.post(f"/programs/{prg}/projects/{pid_provider}"))
    r_up = httpx.post(f"{API_BASE}/api-specs", headers=api_client.headers,
                      data={"projectId": str(pid_provider), "name": spec_name},
                      files={"file": (f"{spec_name}.json",
                                      _MIN_OPENAPI.encode(), "application/json")},
                      timeout=20)
    assert r_up.status_code == 200 and str(r_up.json().get("code")) == "0", \
        f"提供方契约上传失败: {r_up.text[:300]}"
    try:
        inject_user_token(page, "admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        panel = _open_subscription_drawer(page, consumer_name)

        panel.locator('[data-test="sub-create-btn"]').click()
        form = page.locator('[data-test="sub-form"]')
        expect(form).to_be_visible(timeout=5000)
        form.locator('[data-test="sub-name-input"]').fill(sub_name)
        form.locator('[data-test="sub-source-kind-radio"] .el-radio').filter(
            has_text="同集提供方契约").click()
        _select_option(page, "sub-provider-project-select", provider_name)
        _select_option(page, "sub-provider-spec-select", spec_name)
        form.locator('[data-test="sub-interval-input"] input').first.fill("90")
        form.locator('[data-test="sub-form-save-btn"]').click()
        row = panel.locator('[data-test="sub-table"] tbody tr').first
        expect(row).to_contain_text(sub_name, timeout=15000)
        src = row.locator('[data-test="sub-source-provider"]')
        expect(src).to_contain_text("集内契约", timeout=5000)
        expect(src).to_contain_text(spec_name)

        panel.locator('[data-test="sub-close-btn"]').click()
        page.reload(wait_until="domcontentloaded")
        panel = _open_subscription_drawer(page, consumer_name)
        expect(panel.locator('[data-test="sub-table"] tbody tr').first).to_contain_text(
            sub_name, timeout=15000)
        db_row = sql_query(
            "SELECT source_kind || '|' || coalesce(url,'~') || '|' || "
            "provider_project_id || '|' || provider_spec_name "
            f"FROM tp_spec_subscription WHERE project_id = {pid} AND name = '{sub_name}'")
        assert db_row == f"program_spec|~|{pid_provider}|{spec_name}", f"DB 行不符: {db_row}"

        for i in range(99):
            r = api_client.post("/api-specs/subscriptions", json={
                "projectId": pid, "name": f"w1psx{i:03d}{uniq}",
                "url": "http://localhost:8899/openapi-fixture.json",
                "checkIntervalMinutes": 240})
            assert r.status_code == 200 and str(r.json().get("code")) == "0", \
                f"规模化补行 {i} 失败: {r.text[:200]}"
        page.reload(wait_until="domcontentloaded")
        panel = _open_subscription_drawer(page, consumer_name)
        expect(panel.locator('[data-test="sub-table"] tbody tr')).to_have_count(10, timeout=15000)
        pager = panel.locator(".el-pagination")
        expect(pager).to_be_visible(timeout=5000)
        expect(pager.locator(".el-pagination__total")).to_contain_text("100")
        pager.locator(".el-pager li").filter(has_text="2").first.click()
        expect(panel.locator('[data-test="sub-table"] tbody tr')).to_have_count(10, timeout=15000)

        assert_no_errors(collectors, "W1c 同集提供方契约订阅全链")
    finally:
        sql_query(f"DELETE FROM tp_spec_subscription WHERE project_id = {pid}")
        sql_query(f"DELETE FROM tp_api_spec WHERE project_id = {pid_provider}")
        sql_query(f"DELETE FROM tp_api_spec_preview WHERE project_id = {pid_provider}")
        api_client.delete(f"/programs/{prg}/projects/{pid}")
        api_client.delete(f"/programs/{prg}/projects/{pid_provider}")
        api_client.delete(f"/programs/{prg}")
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
        api_client.delete(f"/projects/{pid_provider}")
        api_client.delete(f"/projects/{pid_provider}/purge?force=true")
        assert sql_query(
            f"SELECT count(*) FROM tp_spec_subscription WHERE project_id IN ({pid},{pid_provider})"
        ) == "0", "订阅行残留"
        assert sql_query(
            f"SELECT count(*) FROM tp_api_spec WHERE project_id = {pid_provider}") == "0", "契约残留"
