
from __future__ import annotations

import time

from conftest import (
    assert_no_errors,
    open_journey_leaf,
    pick_enum_option,
    pick_project_in_header,
    purge_project,
    seed_project,
    sql_query,
    whitelist_expected_reject,
)
from playwright.sync_api import expect, Page

from test_immediate_test_tails import (
    _ledger_row,
    _open_exchange_detail,
    _wait_tail_settled,
    expect_success_toast,
)

CRED_KEY_LABEL = "引用键"
SECRET_LABEL = "凭据值"
TARGET_BASE = "http://localhost:8083/actuator/health"


def _dialog_field(page: Page, label_text: str):
    return (page.locator('[data-test="login-state-create-dialog"] .el-form-item')
            .filter(has_text=label_text)
            .locator("input, textarea")
            .first)


def _seed_vault_credential(api_client, pid: int, name: str, cred_key: str, secret: str):
    r = api_client.post(f"/appauto/credentials?projectId={pid}",
                        json={"projectId": pid, "name": name,
                              "credKey": cred_key, "secret": secret})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]


def _pick_credential_option(page: Page, idx: int, cred_key: str):
    # Single-sourced enum-select picking (R40): the shared helper carries the
    # stale-popper retry this dialog needs (two credential selects, one
    # options list). Semantic success is pinned later by the ledger cell
    pick_enum_option(page, f"login-state-extra-ref-cred-{idx}", cred_key)


def _open_create_dialog(page: Page, state_name: str, main_cred_key: str, main_secret: str):
    page.locator('[data-test="login-state-create-btn"]').click()
    expect(page.locator('[data-test="login-state-create-dialog"]')).to_be_visible(timeout=10000)
    page.fill('[data-test="login-state-create-name"]', state_name)
    pick_enum_option(page, "login-state-create-kind", "token")
    _dialog_field(page, CRED_KEY_LABEL).fill(main_cred_key)
    _dialog_field(page, SECRET_LABEL).fill(main_secret)


def test_combined_headers_register_probe_reveal_audit(logged_in, api_client, collectors, uniq):
    page: Page = logged_in
    project_name = f"组合头旅程谱{uniq}"
    pid = seed_project(api_client, uniq, "LSW", display_name=project_name)
    try:
        # Distinctive tails sit past the first 8 chars, so the masked head
        # prefix can never satisfy (or falsely trip) a plaintext-absence claim.
        main_secret = f"mtok-{uniq}-MAINPLAIN-3a"
        secret_a = f"ca-{uniq}-APLAIN-7b"
        secret_b = f"cb-{uniq}-BPLAIN-5c"
        key_a = f"combo_a_{uniq}"
        key_b = f"combo_b_{uniq}"
        _seed_vault_credential(api_client, pid, f"组合凭据A{uniq}", key_a, secret_a)
        _seed_vault_credential(api_client, pid, f"组合凭据B{uniq}", key_b, secret_b)

        open_journey_leaf(page, "menu-login-states", "dataAssets")
        pick_project_in_header(page, project_name)

        state_name = f"combo-state-{uniq}"
        _open_create_dialog(page, state_name, f"combo_main_{uniq}", main_secret)
        page.locator('[data-test="login-state-extra-ref-add"]').click()
        page.locator('[data-test="login-state-extra-ref-add"]').click()
        # Row 0: no template -> the vault secret rides the header raw.
        _pick_credential_option(page, 0, key_a)
        page.locator('[data-test="login-state-extra-ref-name-0"]').fill("X-Session-Combo")
        # Row 1: prefix template -> "Bearer " + vault secret.
        _pick_credential_option(page, 1, key_b)
        page.locator('[data-test="login-state-extra-ref-name-1"]').fill("X-API-KEY")
        page.locator('[data-test="login-state-extra-ref-template-1"]').fill(
            f"Bearer {{{{CRED:{key_b}}}}}")
        page.locator('[data-test="login-state-create-submit"]').click()
        expect_success_toast(page, "已登记")

        row = _ledger_row(page, state_name)
        expect(row.locator('[data-test^="login-state-extra-count-"]')).to_contain_text(
            "关联 2 个凭据", timeout=10000)

        # Probe through the real dispatch assembly.
        row.locator('[data-test="login-state-probe-btn"]').click()
        expect(page.locator('[data-test="login-state-probe-dialog"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="login-state-probe-url"]').fill(TARGET_BASE)
        page.locator('[data-test="login-state-probe-run"]').click()

        tail = _wait_tail_settled(page)
        trail_text = tail.inner_text()
        assert TARGET_BASE in trail_text, "目标地址必须可读"
        assert "HTTP 2" in trail_text, "服务器应答状态必须在尾流呈现"

        # All three auth rows ride the request-head frame, every value masked;
        # no plaintext tail of any secret may surface before a gated reveal.
        req_cursor = _open_exchange_detail(page, needle="请求头（")
        detail = page.locator(f'[data-test="trail-detail-{req_cursor}"]')
        req_text = detail.inner_text()
        for name in ("authorization", "x-session-combo", "x-api-key"):
            assert name in req_text, f"递交请求头 {name} 必须在交换帧呈现"
        assert req_text.count("（总长度 ") >= 3, "三条鉴权头必须以长度掩码逐行打码"
        for plain in (main_secret, secret_a, secret_b):
            assert plain not in trail_text, "明文严禁未经解显门进入过程流"
            assert plain not in req_text, "明文严禁未经解显门进入交换帧"

        # The two gated reveal doors on the extra rows open with vault material.
        door_a = detail.locator('[data-test="trail-reveal-request:x-session-combo"]')
        expect(door_a).to_have_count(1, timeout=10000)
        door_a.click()
        shown_a = detail.locator('[data-test="trail-revealed-request:x-session-combo"]')
        expect(shown_a.first).to_contain_text(secret_a, timeout=10000)

        door_b = detail.locator('[data-test="trail-reveal-request:x-api-key"]')
        expect(door_b).to_have_count(1, timeout=10000)
        door_b.click()
        shown_b = detail.locator('[data-test="trail-revealed-request:x-api-key"]')
        expect(shown_b.first).to_contain_text(f"Bearer {secret_b}", timeout=10000)

        # Every reveal stamps one probe-trail/reveal audit row on the event.
        stamped = "0"
        deadline = time.time() + 15
        while time.time() < deadline:
            stamped = sql_query(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'probe-trail'"
                " AND action = 'reveal' AND object_id = '" + req_cursor + "'")
            if stamped == "2":
                break
            page.wait_for_timeout(500)
        assert stamped == "2", f"两次解显应各落一条审计（实得 {stamped}）"

        assert_no_errors(collectors, "T664 combined headers journey")
    finally:
        purge_project(api_client, pid)


def test_half_filled_extra_row_refused_with_remedy(logged_in, api_client, collectors, uniq):
    """R63: a header row without a credential is refused and names the door."""
    page: Page = logged_in
    project_name = f"组合头拒件谱{uniq}"
    pid = seed_project(api_client, uniq, "LSR", display_name=project_name)
    try:
        open_journey_leaf(page, "menu-login-states", "dataAssets")
        pick_project_in_header(page, project_name)

        state_name = f"combo-reject-{uniq}"
        _open_create_dialog(page, state_name, f"rej_main_{uniq}", f"rej-{uniq}-PLAIN-9d")
        page.locator('[data-test="login-state-extra-ref-add"]').click()
        # Header name only, no credential chosen -> must be refused at save.
        page.locator('[data-test="login-state-extra-ref-name-0"]').fill("X-Session-Bad")
        page.locator('[data-test="login-state-create-submit"]').click()

        refusal = page.locator(".el-message--error:visible, .el-message--warning:visible")
        expect(refusal.filter(has_text="必须选择引用凭据").first).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="login-state-create-dialog"]')).to_be_visible()
        landed = sql_query("SELECT count(*) FROM tp_login_state WHERE state_name = '"
                           + state_name + "'")
        assert landed == "0", "被拒登记不得落账"

        # One intentional business refusal (HTTP 400 on the register door):
        # dropped through the shared per-endpoint whitelist, and the count is
        # pinned so a never-triggered refusal can never pass silently.
        dropped = whitelist_expected_reject(collectors, "/session-states", status=400)
        assert dropped == 1, f"预期恰一次拒件 400，实剔 {dropped}"
        assert_no_errors(collectors, "T664 half-filled refusal")
    finally:
        purge_project(api_client, pid)
