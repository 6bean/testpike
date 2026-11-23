
from __future__ import annotations

import json
import time

import httpx

from conftest import (
    _set_sys_config_value,
    assert_no_errors,
    get_sys_config_value,
    mock_plane_kill,
    mock_plane_start,
    mock_traffic_port,
    open_journey_leaf,
    pick_enum_option,
    pick_project_in_header,
    seed_project,
    s3_bucket,
    s3_client,
    sql_query,
)
from playwright.sync_api import expect, Page

BODY_BUDGET_KEY = "session.probe.body-max-bytes"


def expect_success_toast(page, text: str):
    # Element Plus replays stale toasts; match only visible nodes (ledger spectrum idiom)
    toasts = page.locator(".el-message--success:visible").filter(has_text=text)
    expect(toasts.last).to_be_visible(timeout=15000)

TARGET_BASE = "http://localhost:8083/actuator/health"
CRED_KEY_LABEL = "引用键"
SECRET_LABEL = "凭据值"


def _dialog_field(page: Page, label_text: str):
    return (page.locator(".el-form-item")
            .filter(has_text=label_text)
            .locator("input, textarea")
            .first)


def _ledger_row(page: Page, name: str):
    row = page.locator('[data-test="login-state-table"] .el-table__row').filter(has_text=name)
    expect(row).to_have_count(1, timeout=15000)
    return row.first


def _wait_tail_settled(page: Page, timeout_s: int = 25):
    """The drawer runs sync server-side; wait until the closing line lands."""
    deadline = time.time() + timeout_s
    tail = page.locator('[data-test="task-tail-drawer"]')
    expect(tail).to_be_visible(timeout=10000)
    while time.time() < deadline:
        if "结束：" in tail.inner_text():
            return tail
        page.wait_for_timeout(400)
    raise AssertionError("尾流抽屉未在预算内收到结束行")


def test_login_state_probe_tail(logged_in, api_client, uniq):
    page: Page = logged_in
    project_name = f"尾流探测谱{uniq}"
    pid = seed_project(api_client, uniq, "ITTP", display_name=project_name)
    state_name = f"tail-probe-{uniq}"
    cred_key = f"tail_probe_cred_{uniq}"
    secret_token = f"eyJhbGciOi.tail-{uniq}.sig-TOPSECRET"

    open_journey_leaf(page, "menu-login-states", "dataAssets")
    pick_project_in_header(page, project_name)
    page.locator('[data-test="login-state-create-btn"]').click()
    expect(page.locator('[data-test="login-state-create-dialog"]')).to_be_visible(timeout=10000)
    page.fill('[data-test="login-state-create-name"]', state_name)
    pick_enum_option(page, "login-state-create-kind", "token")
    _dialog_field(page, CRED_KEY_LABEL).fill(cred_key)
    _dialog_field(page, SECRET_LABEL).fill(secret_token)
    page.locator('[data-test="login-state-create-submit"]').click()
    expect_success_toast(page, "已登记")

    # capture-time columns ride the real object store + ledger row
    object_key = f"login-state/e2e/{uniq}.json"
    s3_client().put_object(Bucket=s3_bucket(), Key=object_key, Body=secret_token.encode())
    sql_query(
        "UPDATE tp_login_state SET state_object_key = '" + object_key + "',"
        " scope_origins = CAST('[\"" + TARGET_BASE + "\"]' AS jsonb)"
        " WHERE state_name = '" + state_name + "'")

    row = _ledger_row(page, state_name)
    row.locator('[data-test="login-state-probe-btn"]').click()
    dialog = page.locator('[data-test="login-state-probe-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)
    page.locator('[data-test="login-state-probe-run"]').click()

    tail = _wait_tail_settled(page)
    trail = tail.inner_text()
    assert TARGET_BASE in trail, "目标地址必须可读"
    assert "HTTP 2" in trail, "服务器应答状态必须在尾流呈现"
    assert "判定：" in trail and "探测结束：" in trail
    # The outbound session header lives in the folded request-head frame
    # : expand it through the shared door,
    # then assert the masked form. Header names are lower-cased by
    req_cursor = _open_exchange_detail(page, needle="请求头（")
    req_text = page.locator(f'[data-test="trail-detail-{req_cursor}"]').inner_text()
    assert "authorization" in req_text, "递交的会话头必须在尾流呈现"
    assert "（总长度 " in req_text, "会话头值必须以长度掩码形式打码"
    assert secret_token not in req_text, "展开帧同样严禁会话真值"
    assert secret_token not in trail, "会话真值严禁进入过程流"

    stamped = sql_query("SELECT count(*) FROM tp_login_state WHERE state_name = '" + state_name
                        + "' AND last_verified_at IS NOT NULL")
    assert stamped == "1", "有效判定必须盖验证时刻"
    page.locator('[data-test="login-state-probe-close"]').click()


def test_credential_vault_test_now_tail(logged_in, api_client, uniq):
    page: Page = logged_in
    project_name = f"尾流凭据谱{uniq}"
    pid = seed_project(api_client, uniq, "ITTC", display_name=project_name)
    name = f"tail-vault-{uniq}"
    cred_key = f"tail_vault_{uniq}"
    secret = "user1:pw-TAILPLAIN-9f3c"

    open_journey_leaf(page, "menu-credential-vault", "dataAssets")
    pick_project_in_header(page, project_name)
    page.locator('[data-test="credential-create-btn"]').click()
    dialog = page.locator('[data-test="credential-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)
    dialog.locator('[data-test="credential-form-name"]').fill(name)
    dialog.locator('[data-test="credential-form-key"]').fill(cred_key)
    dialog.locator('[data-test="credential-form-secret"]').fill(secret)
    dialog.locator('[data-test="credential-dialog-save"]').click()
    expect_success_toast(page, "已建档")

    row = page.locator('[data-test="credential-table"] .el-table__row').filter(has_text=name)
    expect(row).to_have_count(1, timeout=15000)
    row.first.locator('[data-test="credential-test-btn"]').click()
    form = page.locator('[data-test="credential-test-dialog"]')
    expect(form).to_be_visible(timeout=10000)
    page.locator('[data-test="credential-test-url"]').fill(TARGET_BASE)
    page.locator('[data-test="credential-test-method"]').click()
    page.locator(".el-select-dropdown__item").filter(has_text="GET").last.click()
    page.locator('[data-test="credential-test-run"]').click()

    tail = _wait_tail_settled(page)
    trail = tail.inner_text()
    assert TARGET_BASE in trail
    assert "HTTP 2" in trail, "应答必须在尾流呈现"
    assert secret not in trail, "保险箱明文严禁进入过程流"
    assert "验证结束：" in trail
    page.locator('[data-test="credential-test-close"]').click()


# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------

STUB_COOKIE_PREFIX = "trail_stub"
LANE_COOKIE_NAME = "tp_mock_session"


def _open_exchange_detail(page: Page, needle: str = "响应头") -> str:
    pass
    toggles = page.locator('[data-test^="trail-toggle-"]')
    expect(toggles.first).to_be_visible(timeout=15000)
    for i in range(min(toggles.count(), 12)):
        cursor = str(toggles.nth(i).get_attribute("data-test")).rsplit("-", 1)[1]
        block = page.locator(f'[data-test="trail-detail-{cursor}"]')
        if block.count() > 0 and needle in block.inner_text():
            return cursor
        toggles.nth(i).click()
        opened = page.locator(f'[data-test="trail-detail-{cursor}"]')
        expect(opened).to_be_visible(timeout=5000)
        if needle in opened.inner_text():
            return cursor
        toggles.nth(i).click()
    raise AssertionError(f"尾窗里没找到带「{needle}」的交换步骤行")


def _assert_read_doors(page: Page, uniq: str, stub_body_pad: str):
    cursor = _open_exchange_detail(page)
    detail = page.locator(f'[data-test="trail-detail-{cursor}"]')
    detail_text = detail.inner_text()

    cookie_lines = detail.locator('[data-test="trail-reveal-response:set-cookie"]')
    expect(cookie_lines).to_have_count(2, timeout=10000)
    assert "（总长度" in detail_text, "敏感头必须成行打码而非整块隐去"

    cookie_lines.first.click()
    revealed = detail.locator('[data-test="trail-revealed-response:set-cookie"]')
    expect(revealed).to_have_count(2, timeout=10000)
    first_value = revealed.nth(0).inner_text()
    second_value = revealed.nth(1).inner_text()
    assert STUB_COOKIE_PREFIX in first_value and uniq in first_value, \
        f"第 1 行应为 stub 头真值，实得 {first_value!r}"
    assert LANE_COOKIE_NAME in second_value, \
        f"第 2 行应为会话车道头真值，实得 {second_value!r}"

    full_btn = page.locator(f'[data-test="trail-full-{cursor}"]')
    expect(full_btn).to_be_visible(timeout=10000)
    full_btn.click()
    body_block = page.locator(f'[data-test="trail-full-text-{cursor}"]')
    expect(body_block).to_be_visible(timeout=10000)
    more_btn = page.locator(f'[data-test="trail-full-more-{cursor}"]')
    expect(more_btn).to_have_count(1, timeout=10000)
    clicks = 0
    body_sel = f'[data-test="trail-full-text-{cursor}"]'
    while more_btn.count() > 0 and more_btn.first.is_visible():
        before_len = page.eval_on_selector(body_sel, "el => el.textContent.length")
        more_btn.first.click()
        clicks += 1
        assert clicks <= 30, "续页在预算内必须收敛"
        # one page lands per click: wait for this page's text to grow before
        # offering the button another click (no hammering while a page is in
        # flight — the block grows and the finished button retires honestly)
        page.wait_for_function(
            "([sel, before]) => { const el = document.querySelector(sel);"
            " return el !== null && el.textContent.length > before; }",
            arg=[body_sel, before_len], timeout=10000)
    expect(page.locator(f'[data-test="trail-full-text-{cursor}"]')).to_contain_text(
        stub_body_pad[:200], timeout=10000)
    expect(body_block).to_contain_text("已读取全部")
    assert body_block.inner_text().count(stub_body_pad[:50]) >= 3, \
        "分页拼接后的完整正文必须远超打码预览预算（续页真实拼齐）"

    page.context.grant_permissions(["clipboard-read", "clipboard-write"])
    page.locator(f'[data-test="trail-copy-{cursor}"]').click()
    clipboard = page.evaluate("() => navigator.clipboard.readText()")
    assert "（总长度" in clipboard, "复制内容应保持打码形态"
    assert STUB_COOKIE_PREFIX not in clipboard, "明文严禁经复制口出站"
    assert LANE_COOKIE_NAME + "=" not in clipboard, "车道头明文同样禁入复制口"


def test_trail_read_doors_both_gates(logged_in, api_client, collectors, uniq):
    pass
    page: Page = logged_in
    project_name = f"读门双谱{uniq}"
    pid = seed_project(api_client, uniq, "ITRD", display_name=project_name)

    space_key = f"t663d-{uniq}".lower()
    pad = "A" * 21000
    stub_body = '{"pad":"' + pad + '"}'
    r = api_client.post("/mock/spaces", json={
        "projectId": pid, "name": f"读门空间{uniq}", "spaceKey": space_key,
        "basePathMode": "prefix", "authMode": "none", "priority": 10})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    space_id = r.json()["data"]
    r = api_client.post(f"/mock/spaces/{space_id}/stubs", json={
        "name": f"tail-door-{uniq}", "method": "GET",
        "pathMatchType": "exact", "pathPattern": "/trail/echo",
        "responseJson": json.dumps({
            "status": 200, "bodyMode": "template",
            "headers": {"Content-Type": "application/json",
                        "Set-Cookie": f"{STUB_COOKIE_PREFIX}={uniq}; Path=/"},
            "body": stub_body}),
        "enabled": True})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    r = api_client.post(f"/mock/spaces/{space_id}/transition",
                        json={"targetStatus": 1, "expectedVersion": 0})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    target = f"http://localhost:18086/mocks/{space_key}/trail/echo"

    mock_plane_kill()
    plane = mock_plane_start(space_key)
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            probe_r = httpx.get(target, timeout=5)
            if any(v.startswith(STUB_COOKIE_PREFIX)
                   for v in probe_r.headers.get_list("set-cookie")):
                break
        except httpx.HTTPError:
            pass
        time.sleep(1)
    else:
        raise AssertionError(f"仿真面换载本谱空间未在预算内生效: {target}")

    prev_body = get_sys_config_value(BODY_BUDGET_KEY)
    assert prev_body, "V569 预算键未落库（主槽基线漂移）"
    _set_sys_config_value(BODY_BUDGET_KEY, "64")
    try:
        state_name = f"doors-probe-{uniq}"
        cred_key = f"doors_probe_cred_{uniq}"
        secret_token = f"eyJhbGciOi.doors-{uniq}.sig-DOORSTOPSECRET"
        open_journey_leaf(page, "menu-login-states", "dataAssets")
        pick_project_in_header(page, project_name)
        page.locator('[data-test="login-state-create-btn"]').click()
        expect(page.locator('[data-test="login-state-create-dialog"]')).to_be_visible(timeout=10000)
        page.fill('[data-test="login-state-create-name"]', state_name)
        pick_enum_option(page, "login-state-create-kind", "token")
        _dialog_field(page, CRED_KEY_LABEL).fill(cred_key)
        _dialog_field(page, SECRET_LABEL).fill(secret_token)
        page.locator('[data-test="login-state-create-submit"]').click()
        expect_success_toast(page, "已登记")
        object_key = f"login-state/e2e/{uniq}-door.json"
        s3_client().put_object(Bucket=s3_bucket(), Key=object_key, Body=secret_token.encode())
        sql_query("UPDATE tp_login_state SET state_object_key = '" + object_key
                  + "', scope_origins = CAST('[\"" + target + "\"]' AS jsonb)"
                    " WHERE state_name = '" + state_name + "'")
        row = _ledger_row(page, state_name)
        row.locator('[data-test="login-state-probe-btn"]').click()
        expect(page.locator('[data-test="login-state-probe-dialog"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="login-state-probe-run"]').click()
        _wait_tail_settled(page)
        _assert_read_doors(page, uniq, pad)
        page.locator('[data-test="login-state-probe-close"]').click()

        name = f"doors-vault-{uniq}"
        vault_key = f"doors_vault_{uniq}"
        secret = "user1:pw-DOORPLAIN-7a2d"
        open_journey_leaf(page, "menu-credential-vault", "dataAssets")
        pick_project_in_header(page, project_name)
        page.locator('[data-test="credential-create-btn"]').click()
        dialog = page.locator('[data-test="credential-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        dialog.locator('[data-test="credential-form-name"]').fill(name)
        dialog.locator('[data-test="credential-form-key"]').fill(vault_key)
        dialog.locator('[data-test="credential-form-secret"]').fill(secret)
        dialog.locator('[data-test="credential-dialog-save"]').click()
        expect_success_toast(page, "已建档")
        crow = page.locator('[data-test="credential-table"] .el-table__row').filter(has_text=name)
        expect(crow).to_have_count(1, timeout=15000)
        crow.first.locator('[data-test="credential-test-btn"]').click()
        expect(page.locator('[data-test="credential-test-dialog"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="credential-test-url"]').fill(target)
        page.locator('[data-test="credential-test-method"]').click()
        page.locator(".el-select-dropdown__item").filter(has_text="GET").last.click()
        page.locator('[data-test="credential-test-run"]').click()
        _wait_tail_settled(page)
        _assert_read_doors(page, uniq, pad)
        page.locator('[data-test="credential-test-close"]').click()

        assert_no_errors(collectors, "trail read doors both gates")
    finally:
        _set_sys_config_value(BODY_BUDGET_KEY, prev_body)
        mock_plane_kill()
