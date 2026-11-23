
import time

import pytest

from conftest import (
    assert_no_errors,
    pick_project_in_header,
    purge_project,
    seed_project,
    whitelist_expected_reject,
)
from playwright.sync_api import expect
from test_immediate_test_tails import _open_exchange_detail

pytestmark = pytest.mark.timeout(300)

EDIT_DIALOG = '[data-test="login-state-edit-dialog"]'
SUBMIT = '[data-test="login-state-create-submit"]'


def _jwt(exp_epoch: int) -> str:
    import base64
    import json

    def seg(obj):
        raw = json.dumps(obj, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")

    return f"{seg({'alg': 'none', 'typ': 'JWT'})}.{seg({'exp': exp_epoch})}.edit-journey"


def register_state(api_client, pid, name, cred_key, refs=None):
    r = api_client.post("/session-states", json={
        "projectId": pid,
        "stateName": name,
        "kind": "token",
        "credKey": cred_key,
        "secret": _jwt(int(time.time()) + 7 * 86400),
        "scopeOrigins": ["https://app.example.com"],
        "accountLabel": "初始账号",
        "extraAuthRefs": refs or [],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def read_state(api_client, state_id):
    r = api_client.get(f"/session-states/{state_id}")
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def row_of(page, name: str):
    row = page.locator('[data-test="login-state-table"] .el-table__row').filter(has_text=name)
    expect(row).to_have_count(1, timeout=15000)
    return row.first


def dialog_field(page, label_text: str):
    return (
        page.locator(f'{EDIT_DIALOG} .el-form-item')
        .filter(has_text=label_text)
        .locator("input")
        .first
    )


def pick_cred_option(page, idx: int, cred_key: str):
    sel = page.locator(f'[data-test="login-state-extra-ref-cred-{idx}"]')
    sel.click()
    option = page.locator(
        ".el-select-dropdown:visible .el-select-dropdown__item"
    ).filter(has_text=cred_key).first
    expect(option).to_be_visible(timeout=8000)
    option.click()


def extra_row_count(page):
    n = 0
    while page.locator(f'[data-test="login-state-extra-ref-row-{n}"]').count() > 0:
        n += 1
    return n


def wait_refs(api_client, state_id, want_len, timeout_s=10):
    """Poll the ledger read-back until the combined list has `want_len` rows.

    The browser submit and this reader share the same committed state; a
    single read can still land between an EP re-render click miss and the
    request, so the assertion waits the UI out instead of racing it.
    """
    deadline = time.time() + timeout_s
    last = None
    while time.time() < deadline:
        last = read_state(api_client, state_id)["extraAuthRefs"]
        if len(last) == want_len:
            return last
        time.sleep(0.4)
    return last


def test_login_state_edit_journey(logged_in, collectors, api_client, uniq):
    page = logged_in
    project_name = f"登录态编辑谱{uniq}"
    pid = seed_project(api_client, uniq, "LSED", display_name=project_name)
    name_a = f"lsedit-a-{uniq}"
    name_b = f"lsedit-b-{uniq}"
    key_a = f"lsekeya{uniq}"
    key_b = f"lsekeyb{uniq}"
    id_a = None
    try:
        id_a = register_state(api_client, pid, name_a, key_a, refs=[
            {"credKey": key_a, "into": "header", "headerName": "X-First"},
            {"credKey": key_a, "into": "header", "headerName": "X-Second"},
        ])
        register_state(api_client, pid, name_b, key_b)

        pick_project_in_header(page, project_name)
        page.goto(page.url.split("#")[0] + "#/login-states")
        page.wait_for_timeout(600)
        page.locator('[data-test="login-state-keyword-input"]').fill(name_a)
        page.locator('[data-test="login-state-query-btn"]').click()
        row = row_of(page, name_a)
        expect(row).to_contain_text("关联 2 个凭据", timeout=10000)

        # --- 1/2. edit door opens prefilled; unchanged save stays disabled
        row.locator('[data-test="login-state-edit-btn"]').click()
        expect(page.locator(EDIT_DIALOG)).to_be_visible(timeout=10000)
        expect(dialog_field(page, "登录态名称")).to_have_value(name_a)
        expect(dialog_field(page, "引用键")).to_have_value(key_a)
        assert extra_row_count(page) == 2, "stored combined headers arrive as editable rows"
        expect(page.locator(SUBMIT)).to_be_disabled()  # R28 baseline guard

        # rename collision through the same door: refused, names the anchor
        dialog_field(page, "登录态名称").fill(name_b)
        expect(page.locator(SUBMIT)).to_be_enabled()
        page.locator(SUBMIT).click()
        err = page.locator(".el-message--error:visible").filter(has_text=name_b)
        expect(err.last).to_be_visible(timeout=10000)
        page.locator('[data-test="login-state-create-cancel"]').click()
        after_failed_rename = read_state(api_client, id_a)
        assert after_failed_rename["stateName"] == name_a, "refused rename changes nothing"

        # --- 3. metadata + header-row edit lands on the same row
        row = row_of(page, name_a)
        row.locator('[data-test="login-state-edit-btn"]').click()
        expect(page.locator(EDIT_DIALOG)).to_be_visible(timeout=10000)
        dialog_field(page, "账号说明").fill(f"改后账号-{uniq}")
        page.locator('[data-test="login-state-extra-ref-add"]').click()
        pick_cred_option(page, 2, key_a)
        page.locator('[data-test="login-state-extra-ref-name-2"]').fill("X-Third")
        expect(page.locator(SUBMIT)).to_be_enabled()
        page.locator(SUBMIT).click()
        ok = page.locator(".el-message--success:visible").filter(has_text="已更新")
        expect(ok.last).to_be_visible(timeout=10000)
        data = read_state(api_client, id_a)
        assert data["accountLabel"] == f"改后账号-{uniq}"
        assert data["id"] == id_a
        assert len(data["extraAuthRefs"]) == 3, "explicit list replaced the stored rows"

        # --- half-filled row is refused naming the missing part
        row = row_of(page, name_a)
        row.locator('[data-test="login-state-edit-btn"]').click()
        expect(page.locator(EDIT_DIALOG)).to_be_visible(timeout=10000)
        page.locator('[data-test="login-state-extra-ref-add"]').click()
        page.locator('[data-test="login-state-extra-ref-name-3"]').fill("X-HalfFilled")
        page.locator(SUBMIT).click()
        err2 = page.locator(".el-message--error:visible").filter(has_text="必须选择引用凭据")
        expect(err2.last).to_be_visible(timeout=10000)
        assert len(read_state(api_client, id_a)["extraAuthRefs"]) == 3

        # --- clearing every header row is a real intent (edit door replaces):
        # the half-filled row above is still in the form; remove it plus the
        # three stored rows (indices shrink as rows are removed).
        page.locator('[data-test="login-state-extra-ref-remove-3"]').click()
        for idx in (2, 1, 0):
            page.locator(f'[data-test="login-state-extra-ref-remove-{idx}"]').click()
        assert extra_row_count(page) == 0
        expect(page.locator(SUBMIT)).to_be_enabled()
        page.locator(SUBMIT).click()
        ok2 = page.locator(".el-message--success:visible").filter(has_text="已更新")
        expect(ok2.last).to_be_visible(timeout=10000)
        assert wait_refs(api_client, id_a, 0) == [], "explicit empty list clears"

        # --- the vault value survived every edit that sent a blank secret
        # Two intentional business refusals rode this spectrum's edit door:
        # the rename collision and the half-filled header row (each an HTTP
        # 400 on PUT /session-states/<id>). Their wording was asserted above
        # first; drop exactly those entries now — every other 4xx/console
        # line still fails the zero-tolerance gate.
        dropped = whitelist_expected_reject(collectors, f"/session-states/{id_a}", status=400)
        assert dropped == 2, f"预期恰两次拒件 400，实剔 {dropped}"
        assert_no_errors(collectors)
    finally:
        purge_project(api_client, pid)


def test_login_state_probe_post_body_direct(logged_in, collectors, api_client, uniq):
    """Probe door run-shape (11-19 user command): POST + JSON body straight at
    the target endpoint. Real chain, deterministic fixture on :8899:

    1. GET by default renders NO body field (same interaction rule as the
       vault test door);
    2. switching to POST opens the body editor; the run sends the template
       verbatim (Content-Type JSON) and the trail echoes the request line +
       template source;
    3. the session plaintext never surfaces in the drawer, and a VALID
       verdict stamps the verification clock.
    """
    page = logged_in
    project_name = f"登录态POST探测谱{uniq}"
    pid = seed_project(api_client, uniq, "LSPB", display_name=project_name)
    name = f"lspost-{uniq}"
    key = f"lspostkey{uniq}"
    token_value = _jwt(int(time.time()) + 7 * 86400)
    try:
        sid = register_state(api_client, pid, name, key)
        # register_state writes the fresh token as the vault material
        httpx_patch = api_client.put(f"/session-states/{sid}", json={
            "projectId": pid, "stateName": name, "kind": "token", "credKey": key,
            "secret": token_value, "scopeOrigins": ["https://app.example.com"]})
        assert httpx_patch.json().get("code") == "0", httpx_patch.text[:200]

        pick_project_in_header(page, project_name)
        page.goto(page.url.split("#")[0] + "#/login-states")
        page.wait_for_timeout(600)
        page.locator('[data-test="login-state-keyword-input"]').fill(name)
        page.locator('[data-test="login-state-query-btn"]').click()
        row = row_of(page, name)
        row.locator('[data-test="login-state-probe-btn"]').click()
        expect(page.locator('[data-test="login-state-probe-dialog"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="login-state-probe-body-wrap"]')).to_have_count(0)

        page.locator('[data-test="login-state-probe-url"]').fill("http://localhost:8899/api/login")
        page.locator('[data-test="login-state-probe-method"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item").filter(has_text="POST").first.click()
        body = page.locator('[data-test="login-state-probe-body"]')
        expect(body).to_be_visible(timeout=8000)
        body.fill('{"probe": "direct-post"}')
        page.locator('[data-test="login-state-probe-run"]').click()

        cursor = _open_exchange_detail(page, needle="请求体（模板原文")
        detail = page.locator(f'[data-test="trail-detail-{cursor}"]').inner_text()
        assert "POST http://localhost:8899/api/login" in detail, detail[:400]
        assert '{"probe": "direct-post"}' in detail
        drawer = page.locator('[data-test="task-tail-drawer"]').inner_text()
        assert token_value not in drawer, "会话明文严禁进过程流"
        expect(page.locator('[data-test="task-tail-drawer"]').get_by_text("判定：目标接受（2xx）")).to_be_visible(timeout=20000)
        page.locator('[data-test="login-state-probe-close"]').click()

        data = read_state(api_client, sid)
        assert data["lastVerifiedAt"], "VALID 盖章必须落库"
        assert_no_errors(collectors)
    finally:
        purge_project(api_client, pid)
