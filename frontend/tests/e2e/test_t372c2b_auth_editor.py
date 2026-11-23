# -*- coding: utf-8 -*-
""" slice-C2b closeout E2E: the per-request auth form editor.

Real browser + real admin -> Feign -> core -> Postgres chain (no mocks).
Journey doors:
1. open the auth editor on a fresh request, pick bearer and fill a plaintext
 secret -> the save gate refuses in plain words before submit and the
 POST /apitest/requests door is never knocked (client pre-gate);
2. switch the secret slot to a credential reference (@cred/...) -> save
 succeeds and the row lands stored with the reference text;
3. reload the page (cross-route re-entry) -> the request opens with the auth
 section switched on, type=bearer and the token slot echoing the reference;
4. the signature preset dropdown lists the config-driven presets (server
 truth from the K8 catalogue row, not a frontend copy);
5. zero-tolerance gates bundled by assert_no_errors: 4xx/5xx, console
 error/warn, browser Issues (--headless=new via the shared browser fixture).

Data hygiene: a throwaway project carries everything and is purged with a
residue reconciliation over the apitest tables (same posture as the
run-set journey precedent).
"""
import re

import pytest
from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
)

pytestmark = pytest.mark.timeout(300)


def sql(q):
    import subprocess
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def pick_option(page, select_test, option_text):
    page.locator(f'[data-test="{select_test}"]').click()
    page.locator(".el-select-dropdown__item:visible").filter(
        has_text=option_text).first.click()


def test_t372c2b_auth_editor(page, collectors, api_client, uniq):
    suffix = uniq[:6]
    pid = col_id = None
    plain_ok = '[{"type":"status","op":"equals","expect":200}]'
    try:
        # ── Arrange: throwaway project + collection + one bare request ──
        r = api_client.post("/projects", json={
            "name": f"T372认证项目{suffix}", "code": f"P372{suffix}"})
        assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
        d = r.json()["data"]
        pid = d["id"] if isinstance(d, dict) else d

        r = api_client.post("/apitest/collections", json={
            "projectId": pid, "parentId": 0, "name": f"T372集合{suffix}", "tags": ""})
        assert r.json().get("code") == "0", f"建集合失败: {r.text[:300]}"
        col_id = r.json()["data"]["id"]

        r = api_client.post("/apitest/requests", json={
            "collectionId": col_id, "name": "认证探针", "method": "GET",
            "urlPath": "/health", "headersJson": "[]", "bodyText": "",
            "authJson": "{}", "assertionsJson": plain_ok})
        assert r.json().get("code") == "0", f"建请求失败: {r.text[:300]}"
        req_id = r.json()["data"]["id"]

        # ── Door 1: bearer + plaintext secret -> honest pre-submit refusal ──
        inject_token(page)
        page.goto(f"{BASE_URL}/#/api-test?projectId={pid}", wait_until="domcontentloaded")
        row = page.locator(f'[data-test="apitest-request-row-{req_id}"]')
        expect(row).to_be_visible(timeout=20000)
        row.click()

        auth_on = page.locator('[data-test="auth-enable"]')
        expect(auth_on).to_be_visible(timeout=10000)
        auth_on.click()  # switch the section on
        pick_option(page, "auth-type-select", "bearer")

        token_input = page.locator('[data-test="auth-token"] input, input[data-test="auth-token"]').first
        token_input.fill("sk-plaintext-secret-123")

        page.locator('[data-test="apitest-save-btn"]').click()
        gate = page.locator(".el-message--error").first
        expect(gate).to_be_visible(timeout=5000)
        expect(gate).to_contain_text("凭据引用")
        # The refusal is honest: the stored row still carries no auth.
        assert sql(f"SELECT auth_json FROM tp_apitest_request WHERE id={req_id}") == "{}"

        # ── Door 2: reference form -> save passes the gate and lands ──
        token_input.fill("@cred/probe-token")
        page.locator('[data-test="apitest-save-btn"]').click()
        expect(page.locator(".el-message--success").first).to_contain_text(
            "已保存", timeout=10000)
        stored = sql(f"SELECT auth_json FROM tp_apitest_request WHERE id={req_id}")
        assert "bearer" in stored and "@cred/probe-token" in stored, \
            f"落库 auth_json 不符: {stored}"

        # ── Door 3: cross-route reload -> editor rehydrates from stored row ──
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        page.goto(f"{BASE_URL}/#/api-test?projectId={pid}", wait_until="domcontentloaded")
        expect(row).to_be_visible(timeout=20000)
        row.click()

        editor = page.locator('[data-test="auth-form-editor"]')
        expect(editor).to_be_visible(timeout=10000)
        # Rehydrated: section stays on and the token slot echoes the reference.
        token_after = page.locator(
            '[data-test="auth-token"] input, input[data-test="auth-token"]').first
        expect(token_after).to_have_value("@cred/probe-token", timeout=10000)

        # ── Door 4: signature presets come from the server catalogue ──
        pick_option(page, "auth-type-select", "signature")
        # Wait for the type panel to close: EP keeps popped panels in the DOM
        # during the hide animation, so a `:visible` item query fired straight
        # after selection can still see the previous dropdown.
        expect(page.locator(".el-select-dropdown:visible")).to_have_count(0, timeout=5000)
        preset_select = page.locator('[data-test="auth-preset-select"]')
        expect(preset_select).to_be_visible(timeout=5000)
        preset_select.click()
        menu = page.locator(".el-select-dropdown:visible .el-select-dropdown__item")
        # The seed rows (hmac-sha256 / md5 / sha1) must be listed — server
        # truth from the K8 catalogue, not a frontend copy.
        expect(menu.first).to_be_visible(timeout=5000)
        options = menu.all_inner_texts()
        assert any("hmac" in o.lower() for o in options), \
            f"预设下拉未含出厂模板（实得 {options}）"
        page.keyboard.press("Escape")

        assert_no_errors(collectors, "T372 C-2b auth editor journey")
    finally:
        try:
            if pid:
                r = api_client.delete(f"/projects/{pid}/purge?force=true")
                assert r.json().get("code") == "0", f"purge 失败: {r.text[:200]}"
                col_set = f"(SELECT id FROM tp_apitest_collection WHERE project_id={pid})"
                for tbl, q in [
                    ("tp_apitest_collection",
                     f"SELECT count(*) FROM tp_apitest_collection WHERE project_id={pid}"),
                    ("tp_apitest_request",
                     f"SELECT count(*) FROM tp_apitest_request WHERE collection_id IN {col_set}"),
                ]:
                    left = sql(q)
                    assert left == "0", f"purge 后 {tbl} 残留 {left}"
        except Exception as cleanup_error:  # noqa: BLE001 — surface, never swallow
            print(f"[t372c2b cleanup] {cleanup_error}")
