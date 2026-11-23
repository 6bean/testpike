
import json
import re

import pytest
from conftest import assert_no_errors, inject_token, nav_to, sql_query
from playwright.sync_api import expect

pytestmark = pytest.mark.xdist_group("r3-chain-verify")

KEY_THRESHOLD = "audit.chain-verify.async-threshold-rows"
GATE = "tp.audit_retention_allowed"


def _threshold_config(api):
    rows = api.get("/configs").json()["data"]
    row = next(x for x in rows if x["configKey"] == KEY_THRESHOLD)
    return row


def _set_threshold(api, value):
    row = _threshold_config(api)
    r = api.put(f"/configs/{row['id']}", json={
        "configKey": KEY_THRESHOLD, "configValue": str(value),
        "groupName": row["groupName"], "valueType": row["valueType"],
        "encryptFlag": row["encryptFlag"], "description": row["description"]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"阈值热更失败: {r.text[:200]}"


def _gated(sql):
    pass
    sql_query(f"BEGIN; SELECT set_config('{GATE}', 'on', true); {sql.rstrip(';')}; COMMIT;")


def _tamper_oldest_row():
    pass
    raw = sql_query("SELECT id || chr(9) || COALESCE(after_json::text, '<NULL>') FROM tp_audit_log"
                    " WHERE object_type <> 'audit_chain' AND after_json IS NOT NULL"
                    " ORDER BY id ASC LIMIT 1")
    row_id, text = raw.split("\t", 1)
    original = "NULL" if text == "<NULL>" else "'" + text.replace("'", "''") + "'::jsonb"
    tampered = {"status": "E2E篡改", "marker": "r3-e2e"}
    _gated(f"UPDATE tp_audit_log SET after_json = '{json.dumps(tampered)}'::jsonb"
           f" WHERE id = {row_id}")
    return int(row_id), original


def test_r3_chain_verify_banner(page, collectors, api_client):
    pass
    api = api_client
    tid = None
    original = "'{}'::jsonb"
    try:
        _set_threshold(api, 10_000_000)

        inject_token(page)
        nav_to(page, "审计日志", "/audit-logs", '[data-test="audit-page"]', submenu="系统管理")

        page.locator('[data-test="audit-chain-verify"]').click()
        ok = page.locator('[data-test="audit-verify-ok"]')
        expect(ok).to_be_visible(timeout=30000)
        expect(ok).to_contain_text("哈希链完整")
        m = re.search(r"哈希链完整（(\d+) 条，校验于", ok.inner_text())
        assert m and int(m.group(1)) > 0, f"绿标应含校验条数>0: {ok.inner_text()}"

        tid, original = _tamper_oldest_row()
        page.locator('[data-test="audit-chain-verify"]').click()
        broken = page.locator('[data-test="audit-verify-broken"]')
        expect(broken).to_be_visible(timeout=30000)
        text = broken.inner_text()
        assert f"链断裂于第 {tid} 条" in text, f"红标应报断裂行号 {tid}: {text}"
        assert "E2E篡改" not in text and "marker" not in text, f"断裂结论不得泄露链内容: {text}"

        _gated(f"UPDATE tp_audit_log SET after_json = {original} WHERE id = {tid}")
        tid = None
        page.locator('[data-test="audit-chain-verify"]').click()
        expect(ok).to_be_visible(timeout=30000)
        expect(ok).to_contain_text("哈希链完整")

        assert_no_errors(collectors, "R3 链校验横幅")
    finally:
        if tid is not None:
            _gated(f"UPDATE tp_audit_log SET after_json = {original} WHERE id = {tid}")
        _set_threshold(api, 100_000)
