"""Suggestion-chip live spectrum .

Real chain, no mocks on the wire: a real logged-in browser opens the chat dock
on a scratch project and sends one harmless question so a live SSE run streams.
The suggestion payload is parked onto that streaming block through core's own
internal ledger door (POST /internal/ai-chat/sessions/{sid}/frames, the same
sink ChatRunOrchestrator.appendDoneThenClose journals through, payload shape
copied 1:1 from that method: messageId/promptTokens/completionTokens/suggest).

Why park the done frame instead of waiting for the run's own seal: the admin
pump closes the browser's stream right after forwarding a terminal frame
(ChatStreamBridge: done/error each close the stream). A parked done therefore
becomes the tail the dock folds — exactly the posture a gated live done has.
The run's own done lands later in the ledger and never reaches the UI; the
chips are a live-only surface by design the reload door is honestly
empty, same posture as guide/chart/plan/clarify).

The browser then walks the chip band's whole user story:

 1. the strip renders with one button per proposal (hard counts, iron rule
 5d — never an if-wrap), labelled, capped at three;
 2. ONE click hands the sentence to the regular send door: the ledger is the
 source, the newest user message must carry the chip text verbatim (the
 composer fills and sends through the same door a typed question uses —
 no private auto-run channel exists, the write-chain confirm door is
 untouched because the chip text is the user's OWN next message);
 3. the chips retire once a fresher row takes the tail (the strip must be
 gone while the follow-up run streams);
 4. negative shape: a parked done whose suggest is malformed (four entries)
 folds to an EMPTY zone — the strip is physically absent, never partial.

Every phase ends with the 4xx/5xx + console + Issues-panel zero-tolerance
check; the session-scoped browser runs --headless=new with the probe
self-test (conftest), so the Issues monitor is proven live, not silent.
The scratch project is purged in the tail so the demo projects stay untouched.
"""
import time

import pytest
from chat_spectrum_support import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    park_suggest_done,
    purge_project,
    seed_project,
    send_unambiguous,
    sql_query,
    wait_streaming_message,
)
from playwright.sync_api import expect

# Shares the single-worker group of the ai-chat family: this spectrum drives
# live streamed turns on the shared LLM loop.
pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]

CHIP_A = "把这次整理结果生成为用例草稿"
CHIP_B = "对相关需求点发起评审"
CHIP_C = "汇总本项目的执行失败清单"
BAD_CHIP = "第四条不该出现的建议"


def test_chat_suggest_chips_one_click_send(page, collectors, api_client, uniq):
    """Park a suggest-carrying done -> chips render -> one click lands the
    verbatim sentence in the ledger as the user's own next message."""
    pid = seed_project(api_client, uniq, "SUGGEST")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        # Turn 1 streams a real SSE run with a self-contained prompt; the
        # parked done ends the browser's stream while the run seals in the
        # ledger (fixture posture, documented in the module header).
        send_unambiguous(page, f"探针自检（编号{uniq}）：请用一两句话说明你能提供哪些测试协助，"
                               "不要向我提问，也不要调用任何工具")
        sid, msg_id = wait_streaming_message(90, pid)
        park_suggest_done(sid, msg_id, [CHIP_A, CHIP_B, CHIP_C])

        # 1. The strip renders one button per proposal (hard counts).
        strip = page.locator('[data-test="chat-suggest-strip"]')
        expect(strip).to_be_visible(timeout=60000)
        expect(strip.locator("button.chat-suggest-chip")).to_have_count(3)
        expect(page.locator('[data-test="chat-suggest-chip-0"]')).to_contain_text(CHIP_A)
        expect(page.locator('[data-test="chat-suggest-chip-2"]')).to_contain_text(CHIP_C)
        assert_no_errors(collectors, "suggest chips render the journaled proposals")

        # 2. ONE click = the regular send door. The ledger (not the DOM) is
        # the source: the newest user message must carry the chip verbatim.
        _parked_before_click = sql_query(
            "SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
            f"{sid} AND role='user'")
        page.locator('[data-test="chat-suggest-chip-1"]').click()
        deadline = time.time() + 60
        composed = ""
        while time.time() < deadline:
            newest = sql_query(
                "SELECT id FROM tp_ai_chat_message WHERE session_id="
                f"{sid} AND role='user' ORDER BY id DESC LIMIT 1")
            composed = sql_query(
                f"SELECT content FROM tp_ai_chat_message WHERE id={newest}") if newest else ""
            if CHIP_B in composed:
                break
            time.sleep(1.0)
        assert CHIP_B in composed, f"the clicked chip never reached the ledger: {composed!r}"
        after = int(sql_query("SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
                              f"{sid} AND role='user'"))
        assert after == int(_parked_before_click) + 1, \
            "the chip click composed more than one user message (expected exactly one)"

        # 3. A fresher row takes the tail: the strip is gone while the
        # follow-up run streams (hard absence, not an if-wrap).
        expect(page.locator('[data-test="chat-suggest-strip"]')).to_have_count(0, timeout=30000)
        assert_no_errors(collectors, "one chip click sends through the regular door and retires")

        # Seal the follow-up turn before the tail check (honest wait on the
        # ledger, the UI already reflects it).
        deadline = time.time() + 240
        while time.time() < deadline:
            streaming = sql_query("SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
                                  f"{sid} AND status='streaming'")
            if int(streaming) == 0:
                break
            time.sleep(2.0)
        assert_no_errors(collectors, "suggest chips spectrum tail (sealed turns)")
    finally:
        purge_project(api_client, pid)


def test_chat_suggest_chips_malformed_shape_is_honest_empty(page, collectors, api_client, uniq):
    """A suggest member the shape gate rejects (four entries) folds to the
    empty zone: the strip never renders partially, never at all."""
    pid = seed_project(api_client, uniq, "SUGGESTBAD")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        send_unambiguous(page, f"探针自检（编号{uniq}）：请用一两句话说明测试协助范围，"
                               "不要向我提问，也不要调用任何工具")
        sid, msg_id = wait_streaming_message(90, pid)
        park_suggest_done(sid, msg_id, [CHIP_A, CHIP_B, CHIP_C, BAD_CHIP])

        # The parked done still seals the block (the dock reached its tail),
        # yet the malformed proposal set folds to nothing.
        deadline = time.time() + 60
        while time.time() < deadline:
            done = sql_query("SELECT count(*) FROM tp_ai_chat_frame WHERE message_id="
                             f"{msg_id} AND event_type='done'")
            if int(done) >= 1:
                break
            time.sleep(0.5)
        page.wait_for_timeout(1500)  # the stream tail lands after the ledger row
        expect(page.locator('[data-test="chat-suggest-strip"]')).to_have_count(0)
        assert_no_errors(collectors, "malformed suggest proposal set folds to an empty zone")
    finally:
        purge_project(api_client, pid)
