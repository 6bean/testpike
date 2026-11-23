
import os
import socket
import subprocess
import sys
import time
import uuid

import httpx
import pytest
from conftest import (
    ADMIN_PASSWORD,
    API_BASE,
    assert_no_errors,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
PAGE_SELECTOR = '[data-test="mcp-page"]'
PANEL_SELECTOR = '[data-test="mcp-auth-panel"]'

pytestmark = pytest.mark.timeout(300)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture()
def mock_mcp():
    pass
    port = _free_port()
    proc = subprocess.Popen(
        [sys.executable, f"{REPO_ROOT}/scripts/mock-mcp-server.py",
         "--port", str(port), "--mode", "normal"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    deadline = time.time() + 10
    while time.time() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                break
        except OSError:
            if proc.poll() is not None:
                pytest.fail("mock-mcp-server.py 启动即退出")
            time.sleep(0.2)
    else:
        proc.kill()
        pytest.fail("mock-mcp-server.py 10s 内未就绪")
    yield port
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()


def _tpl_row(page, name):
    return page.locator('[data-test="mcp-tpl-table"] tbody tr').filter(has_text=name).first


def _goto_templates_tab(page):
    nav_to(page, "扩展集成", "/extensions", PAGE_SELECTOR, submenu="AI 能力")
    page.get_by_role("tab", name="授权模板").click()
    expect(page.locator(PANEL_SELECTOR)).to_be_visible(timeout=10000)


def _pick_option(page, select_selector, text):
    pass
    page.locator(select_selector).first.click()
    page.keyboard.type(text)
    item = page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                        has_text=text).first
    expect(item).to_be_visible(timeout=5000)
    item.click()


def test_g5_auth_template_page(logged_in, collectors, mock_mcp):
    pass
    page = logged_in
    suffix = uuid.uuid4().hex[:6]
    tpl_name = f"g5e2e-模板{suffix}"
    srv_name = f"g5e2e-srv{suffix}"
    proj_name = f"G5尾E2E项目{suffix}"
    srv_id = proj_id = None
    api = httpx.Client(base_url=API_BASE, timeout=30)
    token = api.post("/auth/login",
                     json={"username": "admin", "password": ADMIN_PASSWORD}).json()["data"]["accessToken"]
    api.headers["Authorization"] = f"Bearer {token}"
    try:
        _goto_templates_tab(page)

        r = api.post("/mcp-servers", json={
            "name": srv_name, "endpoint": f"http://localhost:{mock_mcp}/mcp", "transport": 2})
        srv_id = r.json()["data"]["id"]
        rt = api.post(f"/mcp-servers/{srv_id}/test").json()
        snap = rt.get("data") or {}
        assert snap.get("server"), f"连接测试失败: {str(rt)[:200]}"
        pid_r = api.post("/projects", json={"name": proj_name, "code": f"G5E{suffix}"})
        proj_id = pid_r.json()["data"]

        page.click('[data-test="mcp-tpl-create-open"]')
        drawer = page.locator('[data-test="mcp-tpl-form-drawer"]')
        expect(drawer).to_be_visible()
        drawer.locator('[data-test="mcp-tpl-form-name"]').fill(tpl_name)
        tools_combo = drawer.locator('[data-test="mcp-tpl-form-tools"]').get_by_role("combobox").first
        tools_combo.fill("kb_search")
        opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item',
                           has_text="kb_search").first
        expect(opt).to_be_visible(timeout=5000)
        opt.click()
        expect(drawer.locator('[data-test="mcp-tpl-form-tools"] .el-tag',
                              has_text="kb_search").first).to_be_visible(timeout=5000)
        drawer.locator('[data-test="mcp-tpl-form-remark"]').fill("E2E 首测")
        drawer.locator('[data-test="mcp-tpl-form-submit"]').click()
        expect(page.locator('.el-message--success', has_text="模板已创建").first) \
            .to_be_visible(timeout=8000)
        expect(_tpl_row(page, tpl_name)).to_be_visible(timeout=8000)

        page.reload(wait_until="domcontentloaded")
        _goto_templates_tab(page)
        expect(_tpl_row(page, tpl_name)).to_be_visible(timeout=10000)
        assert sql_query(
            f"SELECT count(*) FROM tp_mcp_auth_template WHERE name = '{tpl_name}'") == "1", \
            "模板 DB 未持久化"

        _tpl_row(page, tpl_name).locator('[data-test="ra-apply"]').click()
        dlg = page.locator('[data-test="mcp-apply-dialog"]')
        expect(dlg).to_be_visible()
        srv_item = dlg.locator('.apply-server-item', has_text=srv_name).first
        expect(srv_item).to_be_visible(timeout=5000)
        srv_item.click()
        dlg.locator('[data-test="mcp-apply-submit"]').click()
        summary = dlg.locator('[data-test="mcp-apply-summary"]')
        expect(summary).to_be_visible(timeout=8000)
        expect(dlg.locator('[data-test="mcp-apply-details"]')).to_contain_text("新启用")
        dlg.locator('[data-test="mcp-apply-close"]').click()

        _pick_option(page, '[data-test="mcp-inherit-project"]', proj_name)
        state = page.locator('[data-test="mcp-inherit-state"]')
        expect(state).to_contain_text("未配置", timeout=8000)
        page.locator('[data-test="mcp-inherit-setup"]').click()
        idlg = page.locator('[data-test="mcp-inherit-dialog"]')
        expect(idlg).to_be_visible()
        _pick_option(page, '[data-test="mcp-inherit-template"]', tpl_name)
        idlg.locator('[data-test="mcp-inherit-save"]').click()
        expect(page.locator('.el-message--success', has_text="继承配置已保存").first) \
            .to_be_visible(timeout=8000)
        expect(state).to_contain_text(tpl_name, timeout=8000)
        expect(page.locator('[data-test="mcp-inherit-switch"]')).to_be_visible(timeout=5000)

        page.reload(wait_until="domcontentloaded")
        _goto_templates_tab(page)
        _pick_option(page, '[data-test="mcp-inherit-project"]', proj_name)
        expect(state).to_contain_text(tpl_name, timeout=10000)
        assert sql_query(
            f"SELECT count(*) FROM tp_project_mcp_auth a JOIN tp_project p ON p.id=a.project_id "
            f"WHERE p.name = '{proj_name}'") == "1", "继承行 DB 未持久化"

        assert_no_errors(collectors, "G5 授权模板页 E2E")
    finally:
        try:
            if proj_id:
                sql_query(f"DELETE FROM tp_project_mcp_auth WHERE project_id = {proj_id}")
            trow = sql_query(f"SELECT id FROM tp_mcp_auth_template WHERE name = '{tpl_name}'")
            if trow:
                api.delete(f"/mcp-auth/templates/{trow}")
            if srv_id:
                api.delete(f"/mcp-servers/{srv_id}")
            if proj_id:
                api.delete(f"/projects/{proj_id}/purge", params={"force": "true"})
        except Exception as e:
            print(f"cleanup warn: {e}", flush=True)
        assert sql_query("SELECT count(*) FROM tp_mcp_server WHERE name LIKE 'g5e2e-srv%'") == "0", \
            "E2E 清理未回零（残留会污染 L4 装配面）"
