
import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, inject_token, nav_to, pick_project_in_header

PROJ_SWITCH = '[data-test="project-feishu-gate-switch"]'
BATCH_SWITCH = '[data-test="batch-feishu-gate-switch"]'

pytestmark = pytest.mark.xdist_group("v6_feishu_gate")


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"V6闸_{uniq}", "code": f"V6G{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    data = r.json()["data"]
    return (data if isinstance(data, int) else data["id"]), f"V6闸_{uniq}"


def _echo_flag(api, pid):
    return api.get(f"/projects/{pid}").json()["data"].get("notifyFeishu")


def _search_project_row(page, name):
    page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
    expect(page.locator('[data-test="project-table"]')).to_be_visible(timeout=15000)
    page.fill('input[data-test="project-search"]', name)
    page.press('input[data-test="project-search"]', "Enter")
    row = page.locator('[data-test="project-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=10000)
    return row


def _inject_and_goto(page, menu_text, url_fragment, marker, submenu=None):
    pass
    inject_token(page)
    nav_to(page, menu_text, url_fragment, marker, submenu=submenu)


def test_v6_project_feishu_switch_roundtrip(page, collectors, api_client, uniq):
    pass
    pid, name = _mk_project(api_client, uniq)
    try:
        assert _echo_flag(api_client, pid) == 0, "新建项目默认应为关（V86 default 0）"
        _inject_and_goto(page, "项目管理", "/projects",
                         '[data-test="project-table"]', submenu="系统管理")
        row = _search_project_row(page, name)
        row.locator('[data-test="ra-edit"]').click()
        sw = page.locator(PROJ_SWITCH)
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
        assert "is-checked" in (sw.get_attribute("class") or ""), "reload 后重开弹窗应为开态"
        sw.click()
        page.click('button[data-test="project-submit"]')
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=5000)
        assert _echo_flag(api_client, pid) == 0, "往返回关后 GET 应为 0"
        assert_no_errors(collectors, "T237 项目级飞书开关往返")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_v6_batch_create_switch_payload(page, collectors, api_client, uniq):
    pass
    code = f"V6B{uniq[:6].upper()}"
    r = api_client.post("/projects", json={"name": f"V6批{uniq}", "code": code})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    try:
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": f"V6环境{uniq}", "envType": 2,
            "baseUrl": f"https://v6e-{uniq[:6]}.tp-local.test", "endTypes": "web"})
        assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
        r = api_client.post("/cases", json={
            "projectId": pid, "title": f"V6用例{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        _inject_and_goto(page, "执行批次", "/batches",
                         '[data-test="batch-list-page"]', submenu="执行测试")
        page.goto(f"{BASE_URL}/#/batches", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-list-page"]')).to_be_visible(timeout=15000)
        # Assertion fix : the per-page project selector was retired
        # with the IA pass — the header context is the single switch.
        pick_project_in_header(page, f"V6批{uniq}")
        expect(page.locator('[data-test="batch-create-btn"]')).to_be_enabled(timeout=5000)
        page.locator('[data-test="batch-create-btn"]').click()
        expect(page.locator('[data-test="batch-create-dialog"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="batch-form-name"]').fill(f"V6批{uniq[:6]}")
        bsw = page.locator(BATCH_SWITCH)
        expect(bsw).to_be_visible(timeout=5000)
        assert "is-checked" not in (bsw.get_attribute("class") or ""), "建批开关默认应为关"
        bsw.click()
        assert "is-checked" in (bsw.get_attribute("class") or ""), "点击后应翻为开"
        case_rows = page.locator('[data-test="batch-case-table"] .el-table__body tbody tr')
        expect(case_rows).to_have_count(1, timeout=10000)
        case_rows.nth(0).locator('.el-checkbox').click()
        page.locator('[data-test="batch-form-submit"]').click()
        expect(page.locator('.el-message--success').first).to_contain_text(
            "草稿批次创建成功", timeout=10000)
        r = api_client.get(f"/batches?projectId={pid}")
        rows = r.json()["data"]["list"]
        assert len(rows) == 1, f"批次列表应 1 行: {rows}"
        assert rows[0].get("notifyFeishu") == 1, \
            f"批级开关=1 应落库回显: {rows[0]}"
        page.goto(f"{BASE_URL}/#/batches", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-table"]')).to_contain_text(
            f"V6批{uniq[:6]}", timeout=10000)
        assert_no_errors(collectors, "T237 批级飞书开关建批")
    finally:
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
