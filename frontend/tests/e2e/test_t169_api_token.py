
import uuid

from conftest import (
    BASE_URL,
    assert_no_errors,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect


def _token_row(page, name):
    return page.locator('[data-test="token-table"] tbody tr').filter(has_text=name).first



def test_api_token_page_smoke(logged_in, collectors):
    page = logged_in
    nav_to(page, "API Token", "/api-tokens", '[data-test="api-token-page"]', submenu="系统管理")
    expect(page.locator('[data-test="token-table"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="token-create-open"]')).to_be_visible()
    assert_no_errors(collectors, "API Token 页冒烟")



def test_api_token_create_revoke_flow(logged_in, collectors):
    page = logged_in
    page.context.grant_permissions(["clipboard-read", "clipboard-write"], origin=BASE_URL)
    suffix = uuid.uuid4().hex[:8]
    name = f"e2e-token-{suffix}"
    nav_to(page, "API Token", "/api-tokens", '[data-test="api-token-page"]', submenu="系统管理")

    page.click('[data-test="token-create-open"]')
    dialog = page.locator('[data-test="token-create-dialog"]')
    expect(dialog).to_be_visible()
    dialog.locator('[data-test="token-create-name"]').fill(name)
    scope_select = dialog.locator('[data-test="token-create-scopes"]')
    for scope in ("mcp.read", "mcp.case"):
        scope_select.click()
        option = page.locator('.tp-token-scope-popper .el-select-dropdown__item',
                               has_text=scope).first
        expect(option).to_be_visible()
        option.click()
    with page.expect_response(
        lambda r: "/api/v1/api-tokens" in r.url and r.request.method == "POST"
        and r.status == 200
    ):
        dialog.locator('[data-test="token-create-submit"]').click()

    plain_dialog = page.locator('[data-test="token-plaintext-dialog"]')
    expect(plain_dialog).to_be_visible()
    plaintext = plain_dialog.locator('[data-test="token-plaintext"]').inner_text()
    assert plaintext.startswith("tp_pat_"), f"明文前缀异常: {plaintext[:16]}"
    plain_dialog.locator('[data-test="token-copy"]').click()
    expect(plain_dialog.get_by_text("已复制")).to_be_visible()
    plain_dialog.locator('[data-test="token-plain-done"]').click()
    expect(plain_dialog).to_be_hidden()

    row = sql_query(
        "SELECT token_hash, scopes, revoked, name FROM tp_api_token "
        f"WHERE name='{name}'")
    assert row, f"DB 未找到 Token 行: {name}"
    stored_hash, stored_scopes, stored_revoked, _ = (c.strip() for c in row.split("|"))
    assert "tp_pat_" not in stored_hash and "tp_pat_" not in row, "明文泄漏进 DB"
    assert stored_scopes == "mcp.read,mcp.case", f"scopes 归一异常: {stored_scopes}"
    assert stored_revoked == "0", f"初始 revoked 应为 0: {row}"

    nav_to(page, "API Token", "/api-tokens", '[data-test="api-token-page"]', submenu="系统管理")
    token_row = _token_row(page, name)
    expect(token_row).to_be_visible(timeout=8000)
    assert "生效中" in token_row.inner_text(), f"状态回显缺失: {token_row.inner_text()}"
    assert stored_hash[:8] in token_row.inner_text(), \
        f"指纹前 8 位未回显: {token_row.inner_text()}"
    assert plaintext not in token_row.inner_text(), "列表页泄漏明文"

    token_row.locator('[data-test="ra-revoke"]').click()
    confirm = page.locator('.el-message-box:visible')
    expect(confirm).to_be_visible()
    assert "终态不可恢复" in confirm.inner_text(), "吊销确认文案缺人话说明"
    with page.expect_response(
        lambda r: "/api/v1/api-tokens" in r.url and r.request.method == "DELETE"
        and r.status == 200
    ):
        confirm.get_by_role("button", name="吊销").click()

    nav_to(page, "API Token", "/api-tokens", '[data-test="api-token-page"]', submenu="系统管理")
    token_row = _token_row(page, name)
    expect(token_row).to_be_visible(timeout=8000)
    assert "已吊销" in token_row.inner_text(), f"吊销态回显缺失: {token_row.inner_text()}"
    assert sql_query(
        f"SELECT revoked FROM tp_api_token WHERE name='{name}'").strip() == "1", \
        "DB revoked 未置 1"

    assert_no_errors(collectors, "API Token 创建吊销全链")



def test_api_token_scope_legend_and_guard(logged_in, collectors):
    pass
    page = logged_in
    nav_to(page, "API Token", "/api-tokens", '[data-test="api-token-page"]', submenu="系统管理")
    page.click('[data-test="token-create-open"]')
    dialog = page.locator('[data-test="token-create-dialog"]')
    expect(dialog).to_be_visible()

    legend = dialog.locator('[data-test="token-scope-help"]')
    expect(legend).to_be_visible()
    expect(legend.locator('.scope-help__name')).to_have_count(7)
    legend_text = legend.inner_text()
    for scope in ("mcp.read", "mcp.case", "mcp.mock", "mcp.doc",
                  "mcp.gen", "mcp.script", "mcp.exec"):
        assert scope in legend_text, f"图例缺少范围说明: {scope}"
    assert "默认不勾选" in legend_text, "图例缺默认不勾选指路句"

    posted: list[str] = []
    page.on("request", lambda r: posted.append(r.url)
            if "/api/v1/api-tokens" in r.url and r.method == "POST" else None)
    dialog.locator('[data-test="token-create-name"]').fill(f"e2e-legend-{uuid.uuid4().hex[:6]}")
    dialog.locator('[data-test="token-create-submit"]').click()
    toast = page.locator('.el-message:visible')
    expect(toast).to_be_visible()
    assert "请至少选择一个权限范围" in toast.inner_text(), \
        f"拒止文案未点名要求: {toast.inner_text()}"
    assert not posted, f"未勾选 scope 仍发起了创建请求: {posted}"
    expect(dialog).to_be_visible()

    assert_no_errors(collectors, "Token scope 图例与未勾选拦截")
