
import pytest
from conftest import BASE_URL, assert_no_errors, inject_token, nav_to
from playwright.sync_api import expect

SWITCH = '[data-test="project-inspect-gate-switch"]'

pytestmark = pytest.mark.xdist_group("g4_inspect_gate")


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"G4闸_{uniq}", "code": f"G4G{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    data = r.json()["data"]
    return (data if isinstance(data, int) else data["id"]), f"G4闸_{uniq}"


def _echo_flag(api, pid):
    return api.get(f"/projects/{pid}").json()["data"].get("pageInspectEnabled")


def _search_project_row(page, name):
    page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
    expect(page.locator('[data-test="project-table"]')).to_be_visible(timeout=15000)
    page.fill('input[data-test="project-search"]', name)
    page.press('input[data-test="project-search"]', "Enter")
    row = page.locator('[data-test="project-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=10000)
    return row


def test_g4_project_inspect_switch_roundtrip(page, collectors, api_client, uniq):
    pass
    pid, name = _mk_project(api_client, uniq)
    try:
        assert _echo_flag(api_client, pid) == 0, "新建项目页面探查默认应为关（V110 default 0）"
        inject_token(page)
        nav_to(page, "项目管理", "/projects", '[data-test="project-table"]', submenu="系统管理")
        row = _search_project_row(page, name)
        row.locator('[data-test="ra-edit"]').click()
        sw = page.locator(SWITCH)
        expect(sw).to_be_visible(timeout=5000)
        assert "is-checked" not in (sw.get_attribute("class") or ""), "初始应为关态"
        sw.click()
        assert "is-checked" in (sw.get_attribute("class") or ""), "点击后应翻为开"
        with page.expect_response(
                lambda r: r.request.method == "PUT" and f"/api/v1/projects/{pid}" in r.url):
            page.click('button[data-test="project-submit"]')
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=5000)
        assert _echo_flag(api_client, pid) == 1, "PUT 后 GET 回显应为 1（真落库）"
        row = _search_project_row(page, name)
        row.locator('[data-test="ra-edit"]').click()
        expect(sw).to_be_visible(timeout=5000)
        assert "is-checked" in (sw.get_attribute("class") or ""), "重开弹窗应为开态（回显持久化）"
        sw.click()
        page.click('button[data-test="project-submit"]')
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=5000)
        assert _echo_flag(api_client, pid) == 0, "往返回关后 GET 应为 0"
        assert_no_errors(collectors, "G4 项目级页面探查开关往返")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
