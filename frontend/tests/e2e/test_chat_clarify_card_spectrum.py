"""Clarification one-click answer card live spectrum .

Real chain, no mocks on the wire: a real logged-in browser opens the chat dock
on a scratch project and sends one harmless question so a live SSE run streams.
The clarification card fixture is parked onto that streaming block through
core's own internal ledger door (the same sink ChatRunOrchestrator.emitClarifyFrame
writes through, meta shape copied 1:1 from that method), exactly like the
plan-card and flow-guide deterministic-park spectrums. The browser then walks
the card's whole user story:

 1. the card renders the journaled question with one button per option
 (hard counts, iron rule 5d — never an if-wrap);
 2. ONE click answers: the click composes the follow-up message through the
 same send door a typed question uses, the ledger (not the DOM) is the
 source — the newest user message must carry the chosen option;
 3. the first press seals the card (receipt posture, every option disabled
 with a stated reason, a second click is physically impossible);
 4. the ledger ladder holds for the turn that asked: the clarify frame is
 journaled and the done frame of the same assistant block lands AFTER it.

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
    park_clarify,
    purge_project,
    seed_project,
    sql_query,
    wait_assistant_sealed,
    wait_streaming_message,
)
from playwright.sync_api import expect

# Shares the single-worker group of the ai-chat family: this spectrum drives
# two live streamed turns on the shared LLM loop.
pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]

Q_TEXT = "你想让我按哪种口径整理这批用例？"
OPT_A = "按功能模块分组"
OPT_B = "按优先级排序"
OPT_C = "按评审状态过滤"
MISSING_SLOT = "整理口径"


def test_chat_clarify_card_one_click_answer(page, collectors, api_client, uniq):
    """Park a clarify card -> one click answers via the send door -> seal holds."""
    pid = seed_project(api_client, uniq, "CLARIFY")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        # Turn 1 streams a real SSE run, but its prompt must be unambiguous
        # and self-contained: the live run owns the real clarify channel, so
        # an ambiguous ask journals its own clarify frame over the fixture
        # card mid-stream (measured live 03:06: the click then composed the
        # run's own option text, never OPT_B).
        page.locator('[data-test="chat-dock-input"]').fill(
            f"探针自检（编号{uniq}）：请用一两句话介绍你能提供的测试协助，"
            "不要向我提问，也不要调用任何工具")
        page.locator('[data-test="chat-dock-input"]').press("Enter")

        # Park the clarify card onto the streaming block through the ledger
        # door; the frame shape comes from chat_spectrum_support, the one
        # source that mirrors ChatRunOrchestrator.emitClarifyFrame (single
        # fold point parseClarifyCard in chatDockReducer).
        sid, msg_id = wait_streaming_message(90, pid)
        park_clarify(sid, msg_id, Q_TEXT, (OPT_A, OPT_B, OPT_C), MISSING_SLOT)

        # 1. The card renders the journaled question with one button per
        # option (hard counts).
        card = page.locator('[data-test="chat-clarify-card"]')
        expect(card).to_be_visible(timeout=30000)
        expect(page.locator('[data-test="chat-clarify-question"]')).to_contain_text(Q_TEXT)
        expect(card.locator("button.chat-clarify-option")).to_have_count(3)
        expect(card.locator('[data-test="chat-clarify-option-1"]')).to_contain_text(OPT_B)
        assert_no_errors(collectors, "clarify card renders the journaled question and options")

        # 2. ONE click answers. The doors stay shut while the run streams
        # (disabledRunning posture, measured live: clicking at t=streaming
        # waits 15s on a disabled button), so the spectrum first waits for
        # the turn to seal, then presses. The chosen option must land in the
        # newest user message of the ledger (the send door composes it, the
        # DOM alone is never the source).
        expect(card.locator('[data-test="chat-clarify-option-0"]')).to_be_enabled(timeout=240000)
        # Guard before pressing: the fixture card must still own the slot.
        # A live-run clarify frame arriving later would replace it silently
        # (the ledger, not the DOM, exposed the collision last window).
        try:
            expect(page.locator('[data-test="chat-clarify-question"]')).to_contain_text(
                Q_TEXT, timeout=5000)
        except AssertionError as exc:
            raise AssertionError(
                "the live run journaled its own clarify frame over the fixture "
                "card — the turn-1 prompt must not invite a real clarification"
            ) from exc
        card.locator('[data-test="chat-clarify-option-1"]').click()
        deadline = time.time() + 60
        composed = ""
        while time.time() < deadline:
            composed = sql_query(
                "SELECT content FROM tp_ai_chat_message WHERE session_id="
                f"{sid} AND role='user' ORDER BY id DESC LIMIT 1")
            if OPT_B in composed:
                break
            time.sleep(1.0)
        assert OPT_B in composed, f"the clicked option never reached the ledger: {composed!r}"

        # 3. The receipt posture seals the card: hint gone, receipt names the
        # chosen option, every option disabled — a second click is physically
        # impossible (disabled buttons carry a stated reason, never a silent
        # greying out).
        expect(page.locator('[data-test="chat-clarify-receipt"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="chat-clarify-receipt"]')).to_contain_text(OPT_B)
        expect(card.locator("button.chat-clarify-option")).to_have_count(3)
        for i in range(3):
            expect(card.locator(f'[data-test="chat-clarify-option-{i}"]')).to_be_disabled()
        assert_no_errors(collectors, "the first press seals the clarify card (receipt posture)")

        # 4. The ledger ladder for the asking turn: the clarify frame is
        # journaled and the done frame of the same assistant block lands
        # AFTER it (append-after-plan-before-done ladder).
        both = sql_query(
            "SELECT count(*) FROM tp_ai_chat_frame WHERE message_id="
            f"{msg_id} AND event_type IN ('clarify','done')")
        assert int(both) >= 1, "clarify frame missing from the ledger"
        done_after = sql_query(
            "SELECT count(*) FROM tp_ai_chat_frame f_done WHERE f_done.message_id="
            f"{msg_id} AND f_done.event_type='done' AND f_done.seq > "
            "(SELECT min(f_cl.seq) FROM tp_ai_chat_frame f_cl "
            f"WHERE f_cl.message_id={msg_id} AND f_cl.event_type='clarify')")
        assert int(done_after) == 1, "the done frame does not land after the clarify frame"

        # Both turns must seal before the tail is judged (the follow-up run
        # is a real streamed answer of the clicked option).
        wait_assistant_sealed(240, sid)

        assert_no_errors(collectors, "clarify follow-up turn streams and seals on the ledger")
    finally:
        purge_project(api_client, pid)
