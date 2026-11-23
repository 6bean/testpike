"""Interface pass-through change draft card E2E (AI generic pass-through P1, front end).

Chain: project through the admin API -> real login in the browser, header project switch -> one real
streamed dialog turn (real orchestrator, real ledger; the streaming assistant block is the anchor) ->
the draft itself is journaled through core's own frame door, the same write path the orchestrator
uses, never a direct SQL insert -> card surface assertions (family title, catalog entry, owning
project, one line per declared argument, and zero wrong-object case link) -> the change switch opened
through the real settings chain -> the two-click confirm/apply -> receipt assertions plus a real
database row read back -> switch restored and every scratch row swept in the tail.

Why a browser spectrum at all: this card IS the one-click confirmation door. Before the fix the kind
fell into the generic fallback, so the human was approving an interface change through a door titled
in script-repair words, with unparsed keys and a case-centric receipt. A source contract pins the
shape; only the page proves the door can be seen and clicked.

Pinned pitfalls: a) source ./.env with set -a before running (ADMIN_INIT_PASSWORD); b) every port
comes from the environment (TP_E2E_CORE_BASE / TP_E2E_API_BASE / TP_E2E_BASE_URL) - hard-coding the
shared slot dials another chain's jar instead of this branch (the pass-through door already tripped
over the same trap and is why dial-time authority resolution exists); c) the card renders from the
ledger, so the model never decides the card shape ( precedent: assembly non-emptiness is pinned
server-side, the card is pinned in the browser); d) settings writes go through the admin PUT with the
full row as GET returned it, never SQL - a direct database write skips the cache the running services
read, and the probe would then assert a state no live process can see (this batch hit exactly that).
"""
import json
import os
import time

import httpx
import pytest
from conftest import (API_BASE, BASE_URL, assert_no_errors, inject_token,
                      land_draft_frame, pick_project_in_header, sql_query)
from playwright.sync_api import expect
from test_ai_chat_page import _purge_project, _seed_project, _wait_streaming_message

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

CORE_BASE = os.environ.get("TP_E2E_CORE_BASE", "http://localhost:8083")
WRITE_SWITCH = "ai.api-access.write.enabled"
WRITE_ENTRY = "POST /internal/tags"


def _config_row(api_client, key):
    """Find a settings row through the page's own list door (its default settings group)."""
    import urllib.parse
    r = api_client.get("/configs?groupName=" + urllib.parse.quote("系统"))
    rows = (r.json().get("data") or []) if r.status_code == 200 else []
    return next((c for c in rows if c.get("configKey") == key), None)


def _set_write_switch(api_client, value):
    """Hot-update the switch the way the settings page does, then prove the readback.

    Returning the readback instead of a boolean is deliberate: a silently no-op setup step makes
    every later spectrum measure a state the probe never created (this batch hit exactly that when a
    shared helper mis-unpacked its transport and the switch never actually turned on).
    """
    row = _config_row(api_client, WRITE_SWITCH)
    assert row, f"{WRITE_SWITCH} row not listed through GET /api/v1/configs (group=系统)"
    payload = {**row, "configValue": str(value)}
    r = api_client.put(f"/configs/{row['id']}", json=payload)
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:200]
    after = _config_row(api_client, WRITE_SWITCH)
    assert after and after.get("configValue") == str(value), \
        f"switch readback {after.get('configValue') if after else None} != {value}"


def test_api_access_write_draft_card_lands_through_the_page(page, collectors, api_client, uniq):
    tag_name = f"E2E直通标签{uniq}"
    pid = _seed_project(api_client, uniq, "APW")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    original = (_config_row(api_client, WRITE_SWITCH) or {}).get("configValue")
    try:
        _set_write_switch(api_client, "1")

        # A real streamed turn, so the card sits in a genuine conversation ledger.
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill(f"接口直通变更卡探针{uniq}")
        inp.press("Enter")
        sid, msg_id = _wait_streaming_message(90, pid)

        # The proposal itself travels the orchestrator's own frame door (same write path, minus the
        # model's choice of arguments — the card shape never depends on the model).
        meta = {
            "executionId": 940000 + (int(uniq[:6], 16) % 10000),
            "objectType": "API_ACCESS_WRITE",
            "toolName": "apiAccess.write",
            "model": "e2e-probe-model",
            "actionJson": json.dumps({"endpointKey": WRITE_ENTRY,
                                      "arguments": {"projectId": pid, "name": tag_name}},
                                     ensure_ascii=False),
            "summary": f"接口直通变更：{WRITE_ENTRY}",
            "schemaVersion": "v2",
        }
        draft_id = land_draft_frame(CORE_BASE, sid, msg_id,
                                    "接口直通变更草案：E2E 探针标签", meta)

        # ---- card surface -----------------------------------------------------
        card = page.locator('[data-test="chat-draft-card"]')
        expect(card).to_be_visible(timeout=30000)
        expect(card.locator('.draft-title')).to_have_text("接口变更草案", timeout=10000)
        fields = card.locator('[data-test="chat-draft-api-access-fields"]')
        expect(fields).to_be_visible(timeout=10000)
        text = fields.inner_text()
        assert "目标接口" in text and WRITE_ENTRY in text, f"entry line missing: {text}"
        assert "归属项目" in text and f"#{pid}" in text, f"project line missing: {text}"
        assert "name" in text and tag_name in text, f"declared argument line missing: {text}"
        # No wrong-object jump chip: this family's ledger target_id is the produced row (a tag),
        # and folding it as a case id would send the reviewer to an unrelated case.
        assert card.locator('[data-test="chat-draft-case-link"]').count() == 0, \
            "a pass-through card must not hang a case link on its target id"
        hint = card.locator('.draft-hint').first.inner_text()
        assert "应用草案" in hint and "接口" in hint, f"idle hint is not family wording: {hint}"

        # ---- the two-click door, then the real row ----------------------------
        card.locator('[data-test="chat-draft-confirm"]').click()
        apply_btn = card.locator('[data-test="chat-draft-apply"]')
        expect(apply_btn).to_be_visible(timeout=20000)
        apply_btn.click()
        receipt = card.locator('[data-test="chat-draft-receipt"]')
        expect(receipt).to_contain_text("已应用：接口", timeout=25000)
        receipt_text = receipt.inner_text()
        assert WRITE_ENTRY in receipt_text, f"receipt does not name the entry: {receipt_text}"
        assert "第 null 版" not in receipt_text and "用例 #" not in receipt_text, \
            f"receipt fell back to the case-centric template: {receipt_text}"

        rows = sql_query("SELECT json_agg(row_to_json(t)) FROM ("
                         "SELECT id, name, project_id FROM tp_tag_dict "
                         f"WHERE project_id={pid} AND name='{tag_name}') t")
        assert rows and rows != "null", f"the confirmed change wrote no real row: {rows}"
        produced = json.loads(rows)[0]
        assert f"#{produced['id']}" in receipt_text, \
            f"receipt does not name the produced row {produced['id']}: {receipt_text}"
        assert sql_query(
            "SELECT count(*) FROM tp_ai_chat_draft WHERE id=%s AND status=2" % draft_id) == "1", \
            "the ledger row must flip to applied through the same door the page used"
        # Audit keeps argument names only (values are not an audit payload).
        audit = sql_query("SELECT after_json::text FROM tp_audit_log "
                          "WHERE object_type='api_access' ORDER BY id DESC LIMIT 1")
        assert '"landed": true' in audit, f"latest audit is not a landed pass-through call: {audit[:200]}"
        assert tag_name not in audit, f"audit leaked an argument value: {audit[:200]}"

        assert_no_errors(collectors, "api-access write draft card")
    finally:
        if original is not None:
            try:
                _set_write_switch(api_client, original)
            except Exception:
                pass
        sql_query("DELETE FROM tp_tag_dict WHERE project_id=%s AND name LIKE 'E2E直通标签%%'" % pid)
        sql_query("DELETE FROM tp_ai_chat_draft WHERE session_id IN "
                  "(SELECT id FROM tp_ai_chat_session WHERE project_id=%s)" % pid)
        _purge_project(api_client, pid)
        time.sleep(0.3)
        assert sql_query(f"SELECT count(*) FROM tp_project WHERE id={pid}") == "0", \
            "scratch project must not survive the probe"
