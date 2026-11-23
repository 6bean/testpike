"""One-key undo spectrum .

The undo button sits permanently on the thread actions strip: whether the
session actually holds an accepted case write is core-side knowledge, so the
client copies zero judgment (no second grey-out rule, refusals echo core's
own sentence — same zero-judgment door as export/sediment).

Spectrum:
 1. before any accept the button is clickable and core's refusal is echoed
 verbatim in an error toast;
 2. an update-kind CASE_WRITE draft is parked on the sealed answer and
 accepted through the real confirm door (ticket + apply, admin -> core)
 — that creates the undo anchor;
 3. clicking undo names the fresh card id in its toast and rolls NOTHING
 back (the case keeps its accepted version);
 4. after a reload the undo card is visible as a pending fold on the newest
 sealed answer with its confirm gate intact — undo never becomes a second
 write path (the confirmation door stays the only writer).

Data hygiene: scratch project via the real API, case via /cases, everything
purged by project id in finally — no pollution of the regression demo spaces.
"""
import json
import os
import re
import time

import pytest
from conftest import (BASE_URL, _set_sys_config_value, assert_no_errors,
                      hard_goto, inject_token, land_draft_frame, sql_query,
                      whitelist_expected_reject)
from conftest import purge_project as _purge_project
from conftest import seed_project as _seed_project
from playwright.sync_api import expect
from test_ai_chat_page import _wait_streaming_message

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

CORE_BASE = os.environ.get("TP_E2E_CORE_BASE", "http://localhost:8083")


def _wait_until(fn, timeout: int, label: str):
    """Poll a truthy condition with an honest timeout message."""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if last:
            return last
        time.sleep(1)
    raise AssertionError(f"timed out waiting for {label}: last={last!r}")


def test_chat_one_key_undo_spectrum(page, collectors, api_client, uniq):
    """Undo button always present -> refusal echo -> accept -> undo card -> gate intact."""
    pid = _seed_project(api_client, uniq, "UNDOK")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _set_sys_config_value("ai.chat.case-draft.enabled", "true")
    try:
        # Existing case (update kind = the undoable anchor class).
        base_title = f"撤销谱基线用例{uniq}"
        r = api_client.post("/cases", json={
            "projectId": pid, "title": base_title,
            "precondition": "账号已注册", "expectedResult": "登录成功",
            "priority": 2, "confidence": 3, "endType": "web", "status": 1})
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        data = r.json()["data"]
        case_id = int(data["id"] if isinstance(data, dict) else data)
        v0 = int(sql_query(f"SELECT version FROM tp_test_case WHERE id={case_id}"))

        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        undo_btn = page.locator('[data-test="chat-undo-btn"]')

        # Leg 1: one live streamed turn (real orchestrator) opens the session;
        # before any accept the button is ENABLED (no client-side pre-check)
        # and core's own refusal sentence is echoed verbatim.
        page.locator('[data-test="chat-dock-input"]').fill(f"撤销谱探针{uniq}")
        page.locator('[data-test="chat-dock-input"]').press("Enter")
        sid, msg_id = _wait_streaming_message(60, pid)
        expect(undo_btn).to_be_enabled(timeout=15000)
        # The designed 400 is awaited through Playwright's own event pump before
        # the collector is drained: page-event callbacks are dispatched
        # asynchronously, so reading collectors right after the toast can see zero
        # entries (measured: r5 saw one, r6 saw none). The click rides inside the
        # expect_response block so the response can never land unclaimed.
        with page.expect_response(
                lambda r: r.url.endswith(f"/ai/chat/sessions/{sid}/undo") and r.status == 400,
                timeout=15000):
            undo_btn.click()
        expect(page.locator(".el-message--error").first).to_contain_text(
            "当前会话还没有已接受的用例写入", timeout=15000)
        # This refusal is the leg's designed outcome: core answers HTTP 400
        # with its own sentence because nothing was accepted yet. It is
        # exempted through the shared narrow per-endpoint helper (one source,
        # never a copied block) and the count is asserted, so a refusal that
        # never happened can not pass silently — every other 4xx still fails.
        assert whitelist_expected_reject(
            collectors, f"/ai/chat/sessions/{sid}/undo", status=400) == 1, \
            "exactly one designed refusal is expected on the undo door"

        # Wait for the assistant seal: the door refuses while the turn still
        # streams, and the undo card anchors on a SEALED assistant row.
        _wait_until(
            lambda: sql_query(
                f"SELECT status FROM tp_ai_chat_message WHERE id={msg_id}") != "streaming",
            90, "assistant row sealed before the confirm door")

        # Leg 2: park an update draft through the core frame door, accept it
        # through the REAL ticket+apply doors (admin -> core, no SQL writes).
        new_title = f"撤销谱更新用例{uniq}"
        meta = {"executionId": 0, "objectType": "CASE_WRITE",
                "caseId": case_id, "baseVersion": v0,
                "toolName": "case.update", "model": "e2e-probe-model",
                "actionJson": json.dumps({
                    "title": new_title, "precondition": "账号已注册",
                    "expectedResult": "登录成功", "priority": 2, "confidence": 3,
                    "endType": "web"}, ensure_ascii=False)}
        draft_id = int(land_draft_frame(CORE_BASE, sid, msg_id,
                                        f"更新用例草案：{new_title}", meta))
        tkt = api_client.post(f"/ai/chat/drafts/{draft_id}/confirm-token")
        assert tkt.status_code == 200, tkt.text[:300]
        tkt_body = tkt.json()
        ticket = (tkt_body.get("data") or {}).get("token")
        assert tkt_body.get("code") == "0" and ticket, tkt.text[:300]
        applied = api_client.post(f"/ai/chat/drafts/{draft_id}/apply",
                                  json={"confirmToken": ticket})
        assert applied.status_code == 200 and applied.json().get("code") == "0", \
            applied.text[:300]
        _wait_until(lambda: sql_query(
            f"SELECT status FROM tp_ai_chat_draft WHERE id={draft_id}") == "2",
            30, "draft reached applied through the real door")
        v1 = int(sql_query(f"SELECT version FROM tp_test_case WHERE id={case_id}"))
        assert v1 == v0 + 1, f"the accept must bump the case version: {v0} -> {v1}"

        # Leg 3: one click on undo — the toast names the fresh card id, and
        # nothing rolls back before the card is confirmed.
        undo_btn.click()
        expect(page.locator(".el-message").last).to_contain_text(
            re.compile(r"撤销确认卡 #\d+"), timeout=15000)
        card_id = int(_wait_until(lambda: sql_query(
            "SELECT id FROM tp_ai_chat_draft WHERE session_id=" + str(sid) +
            " AND object_type='CASE_ROLLBACK' AND status=1"
            " ORDER BY id DESC LIMIT 1"), 20, "pending undo card row"))
        assert card_id > 0
        assert int(sql_query(f"SELECT version FROM tp_test_case WHERE id={case_id}")) == v1, \
            "undo must not roll anything back before the card is confirmed"

        # Leg 4: reload-visible fold — the undo card rides the newest sealed
        # answer and keeps its confirm gate (the door stays the only writer).
        # The navigation has to be a REAL document load: a plain page.goto whose
        # target equals the current URL is a same-document navigation, so the
        # browser kept the live in-memory turns (which never saw the draft frame)
        # and the sealed-history door was never re-read — measured in run r4: zero
        # GETs on /sessions/{id}/messages. hard_goto is the shared single-source
        # helper for exactly this pitfall. The history read is awaited through
        # Playwright's own event pump (expect_request): a sleep-based poll of the
        # request collector never sees new entries, because page-event callbacks
        # are only delivered while a Playwright call is running (measured in r5,
        # where the door had answered while the sleep loop read an empty list).
        with page.expect_request(
                lambda r: f"/ai/chat/sessions/{sid}/messages" in r.url, timeout=20000):
            hard_goto(page, f"{BASE_URL}/#/ai/chat?projectId={pid}")
        card = page.locator('[data-test="chat-draft-card"]').filter(
            has_text="撤销上一次已接受的写入")
        expect(card).to_have_count(1, timeout=30000)
        expect(card.locator('[data-test="chat-draft-summary"]')).to_contain_text(
            f"回滚用例到版本 {v0}")
        gate = card.locator('[data-test="chat-draft-confirm"]')
        expect(gate).to_be_visible(timeout=15000)
        expect(gate).to_be_enabled(timeout=15000)
        assert int(sql_query(f"SELECT version FROM tp_test_case WHERE id={case_id}")) == v1, \
            "the visible gate stayed unclicked, so the case stays at its accepted version"

        assert_no_errors(collectors, "chat one-key undo spectrum")
    finally:
        _purge_project(api_client, pid)
