
import os
import socket
import subprocess
import sys
import time
import uuid

import pytest
from conftest import (
    assert_no_errors,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
PAGE_SELECTOR = '[data-test="mcp-page"]'


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


def _mcp_row(page, name):
    return page.locator('[data-test="mcp-table"] tbody tr').filter(has_text=name).first


def _create_via_ui(page, name, endpoint, transport_label="streamable-http", auth=None):
    page.click('[data-test="mcp-create-open"]')
    dialog = page.locator('[data-test="mcp-form-dialog"]')
    expect(dialog).to_be_visible()
    dialog.locator('[data-test="mcp-form-name"]').fill(name)
    dialog.locator('[data-test="mcp-form-endpoint"]').fill(endpoint)
    dialog.locator('[data-test="mcp-form-transport"]').click()
    option = page.locator('.tp-mcp-form-transport-popper .el-select-dropdown__item',
                          has_text=transport_label).first
    expect(option).to_be_visible()
    option.click()
    if auth is not None:
        dialog.locator('[data-test="mcp-form-auth"]').fill(auth)
    dialog.locator('[data-test="mcp-form-submit"]').click()



def test_t169_mcp_page_smoke(logged_in, collectors):
    page = logged_in
    nav_to(page, "扩展集成", "/extensions", PAGE_SELECTOR, submenu="AI 能力")
    expect(page.locator('[data-test="mcp-table"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="mcp-create-open"]')).to_be_visible()
    assert_no_errors(collectors, "MCP 服务页冒烟")



def test_t169_mcp_crud_test_flow(logged_in, collectors, mock_mcp):
    page = logged_in
    suffix = uuid.uuid4().hex[:8]
    name = f"e2e-mcp-{suffix}"
    endpoint = f"http://localhost:{mock_mcp}/mcp"
    nav_to(page, "扩展集成", "/extensions", PAGE_SELECTOR, submenu="AI 能力")

    _create_via_ui(page, name, endpoint, auth="Bearer e2e-secret")
    row = _mcp_row(page, name)
    expect(row).to_be_visible(timeout=8000)
    assert "未测试" in row.inner_text(), f"初始健康态应为未测试: {row.inner_text()}"

    stored = sql_query(
        "SELECT auth_enc, enabled, health_status FROM tp_mcp_server "
        f"WHERE name='{name}'")
    assert stored, f"DB 未找到 MCP 行: {name}"
    auth_enc, db_enabled, db_health = (c.strip() for c in stored.split("|"))
    assert "e2e-secret" not in auth_enc, "auth 明文泄漏进 DB"
    assert db_enabled == "1" and db_health == "0", f"初始列值异常: {stored}"

    nav_to(page, "扩展集成", "/extensions", PAGE_SELECTOR, submenu="AI 能力")
    row = _mcp_row(page, name)
    expect(row).to_be_visible(timeout=8000)
    assert endpoint in row.inner_text(), f"endpoint 回显缺失: {row.inner_text()}"
    assert "e2e-secret" not in row.inner_text(), "页面泄漏 auth 明文"

    row = _mcp_row(page, name)
    row.locator('[data-test="ra-edit"]').click()
    dialog = page.locator('[data-test="mcp-form-dialog"]')
    expect(dialog).to_be_visible()
    auth_input = dialog.locator('[data-test="mcp-form-auth"]')
    expect(auth_input).to_be_visible()
    assert "不修改" in (auth_input.get_attribute("placeholder") or ""), \
        "auth 编辑占位应说明「留空=不修改」"
    assert "e2e-secret" not in auth_input.input_value(), "编辑表单回填了 auth 明文"
    new_name = f"{name}-edit"
    dialog.locator('[data-test="mcp-form-name"]').fill(new_name)
    dialog.locator('[data-test="mcp-form-submit"]').click()
    row = _mcp_row(page, new_name)
    expect(row).to_be_visible(timeout=8000)
    assert sql_query(
        "SELECT auth_enc FROM tp_mcp_server "
        f"WHERE name='{new_name}'").strip() == auth_enc.strip(), \
        "编辑 auth 留空应保留原密文"

    row = _mcp_row(page, new_name)
    with page.expect_response(
        lambda r: "/api/v1/mcp-servers/" in r.url and r.request.method == "POST"
        and "/test" in r.url and r.status == 200, timeout=15000
    ):
        row.locator('[data-test="ra-test"]').click()
    probe = page.locator('[data-test="mcp-probe-dialog"]')
    expect(probe).to_be_visible(timeout=12000)
    assert "连接成功" in probe.inner_text(), f"测试结果人话缺失: {probe.inner_text()[:200]}"
    checks = probe.locator('[data-test="mcp-tool-check"]')
    expect(checks.first).to_be_visible()
    assert checks.count() == 5, f"工装 normal 模式应 5 工具: {checks.count()}"
    assert probe.locator('[data-test="mcp-tool-check"].is-checked').count() == 0, \
        "握手快照默认全拒（G5 白名单），勾选面初始应零勾选"

    checks.filter(has_text="jira_get_issue").first.click()
    checks.filter(has_text="kb_search").first.click()
    probe.locator('[data-test="mcp-tools-save"]').click()
    expect(page.locator('.el-message--success').last).to_contain_text("已保存", timeout=8000)
    probe.locator('[data-test="mcp-probe-close"]').click()
    expect(probe).to_be_hidden()

    def _enabled_map():
        import json as _json
        raw = sql_query(
            "SELECT tools_json::text FROM tp_mcp_server "
            f"WHERE name='{new_name}'").strip()
        return {t["name"]: t["enabled"] for t in _json.loads(raw)}

    en = _enabled_map()
    assert en.get("jira_get_issue") and en.get("kb_search"), f"勾选未落库: {en}"
    assert not any(en[t] for t in ("testrail_list_cases", "shell_exec", "db_drop_table")), \
        f"未勾项应仍全拒: {en}"

    nav_to(page, "扩展集成", "/extensions", PAGE_SELECTOR, submenu="AI 能力")
    row = _mcp_row(page, new_name)
    row.locator('[data-test="ra-more"]').click()
    page.locator('[data-test="ra-tools"]:visible').click()
    sel = page.locator('[data-test="mcp-probe-dialog"]')
    expect(sel).to_be_visible()
    assert "工具勾选" in sel.inner_text(), f"勾选弹窗标题缺失: {sel.inner_text()[:150]}"
    sel_checks = sel.locator('[data-test="mcp-tool-check"]')
    assert sel.locator('[data-test="mcp-tool-check"].is-checked').count() == 2, \
        f"reload 后勾选回显丢失: {sel_checks.all_inner_texts()}"

    for label in ("jira_get_issue", "kb_search"):
        sel_checks.filter(has_text=label).first.click()
    sel.locator('[data-test="mcp-tools-save"]').click()
    expect(page.locator('.el-message--success').last).to_contain_text("已保存", timeout=8000)
    sel.locator('[data-test="mcp-probe-close"]').click()
    en2 = _enabled_map()
    assert not any(en2.values()), f"清空保存应回全拒: {en2}"

    row = _mcp_row(page, new_name)
    expect(row).to_contain_text("健康", timeout=8000)
    tools_db = sql_query(
        "SELECT health_status FROM tp_mcp_server "
        f"WHERE name='{new_name}'")
    assert tools_db.strip() == "1", f"health 未落 1: {tools_db}"
    snapshot = sql_query(
        "SELECT tools_json::text FROM tp_mcp_server "
        f"WHERE name='{new_name}'").strip()
    assert snapshot and snapshot != "None", f"tools_json 快照未落库: {snapshot[:200]}"
    assert "kb_search" in snapshot, f"工装 normal 模式工具名未在快照中: {snapshot[:200]}"

    row = _mcp_row(page, new_name)
    row.locator('[data-test="mcp-enabled-switch"]').click()
    expect(page.locator('.el-message--success').last).to_be_visible(timeout=5000)
    assert sql_query(
        "SELECT enabled FROM tp_mcp_server "
        f"WHERE name='{new_name}'").strip() == "0", "toggle 未落库"
    nav_to(page, "扩展集成", "/extensions", PAGE_SELECTOR, submenu="AI 能力")
    row = _mcp_row(page, new_name)
    expect(row.locator('[data-test="mcp-enabled-switch"].is-checked')).to_have_count(0, timeout=8000)

    row.locator('[data-test="ra-more"]').click()
    page.locator('[data-test="ra-delete"]:visible').click()
    confirm = page.locator('.el-message-box:visible')
    expect(confirm).to_be_visible()
    confirm.get_by_role("button", name="删除").click()
    nav_to(page, "扩展集成", "/extensions", PAGE_SELECTOR, submenu="AI 能力")
    expect(_mcp_row(page, new_name)).to_have_count(0, timeout=8000)
    assert not sql_query(
        f"SELECT id FROM tp_mcp_server WHERE name='{new_name}'"), "DB 残留未清"

    assert_no_errors(collectors, "MCP 服务 CRUD+握手测试全链")



def test_t169_mcp_endpoint_reject(logged_in, collectors):
    page = logged_in
    suffix = uuid.uuid4().hex[:8]
    name = f"e2e-mcp-bad-{suffix}"
    nav_to(page, "扩展集成", "/extensions", PAGE_SELECTOR, submenu="AI 能力")
    _create_via_ui(page, name, "not-a-url")
    toast = page.locator('.el-message--error').last
    expect(toast).to_be_visible(timeout=5000)
    assert "端点" in toast.inner_text(), f"core 人话文案未透传: {toast.inner_text()}"
    assert not sql_query(
        f"SELECT id FROM tp_mcp_server WHERE name='{name}'"), "非法端点不应落库"
    expect(page.locator('[data-test="mcp-form-dialog"] .el-dialog__headerbtn')).to_be_visible()
    page.locator('[data-test="mcp-form-dialog"] .el-dialog__headerbtn').click()
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not (e["status"] == 400 and "/api/v1/mcp-servers" in e["url"])
    ]
    collectors["console_errors"] = [
        e for e in collectors["console_errors"] if "status of 400" not in e
    ]
    assert_no_errors(collectors, "MCP 非法端点拒绝")
