"""Multi-step plan card first E2E spectrum .

Real chain, no mocks on the wire: a real logged-in browser opens the chat
dock on a scratch project and sends one harmless question so a live SSE run
streams. The 3-step plan fixture is parked onto that streaming block through
core's own internal ledger door (the same sink the ai-service writes
through), exactly like the flow-guide deterministic-park spectrum .
The browser then walks the card's whole user story:

 1. the card renders with one tickable row per journaled step;
 2. unticking a step and moving another row up changes what the user will
 actually ask — the ledger (not the DOM) is the source: the newest user
 message must carry the ticked titles in the edited order and must NOT
 carry the unticked one;
 3. the first press seals the card (receipt posture, no second confirm);
 4. after both turns seal, a history reload shows an honest empty plan zone
 (the sealed row replays its answer text, never a re-clickable card).

Every phase ends with the 4xx/5xx + console + Issues-panel zero-tolerance
check; the session-scoped browser runs --headless=new with the probe
self-test (conftest), so the Issues monitor is proven live, not silent.
Iron rule 5d: existence/count assertions are hard (count==0 for the reload
zone is an assertion, never an if-wrap). The scratch project is purged in
the tail so the two demo projects stay untouched.
"""
import json
import time

import httpx
import pytest
from chat_spectrum_support import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    purge_project,
    seed_project,
    sql_query,
)
from playwright.sync_api import expect

# Shares the single-worker group of the ai-chat family: this spectrum drives
# two live streamed turns on the shared LLM loop.
pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]

CORE_BASE = "http://localhost:8083"

# Distinctive step titles: the composed message is asserted on the ledger,
# so an ordering/tick mix-up can never pass by coincidence.
STEP_T1 = "步骤甲{u}"
STEP_T2 = "步骤乙{u}"
STEP_T3 = "步骤丙{u}"


# R40: the ledger poll lives in chat_spectrum_support only; this re-export
# keeps every existing call site on the single shared implementation.
from chat_spectrum_support import wait_streaming_message as _wait_streaming_message


def test_chat_plan_card_render_edit_confirm_reload(page, collectors, api_client, uniq):
    """Park a 3-step plan -> tick/reorder -> confirm composes the question -> reload is honest."""
    t1 = STEP_T1.format(u=uniq)
    t2 = STEP_T2.format(u=uniq)
    t3 = STEP_T3.format(u=uniq)
    pid = seed_project(api_client, uniq, "PLANCARD")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        # Turn 1: one harmless question so a real SSE run streams.
        page.locator('[data-test="chat-dock-input"]').fill(f"请用一句话解释回归测试（探针{uniq}）")
        page.locator('[data-test="chat-dock-input"]').press("Enter")

        # Park the 3-step plan onto the streaming block through the ledger
        # door; the meta shape equals exactly what ChatRunOrchestrator.emitPlanFrame
        # journals (single fold point in chatDockReducer).
        sid, msg_id = _wait_streaming_message(90, pid)
        meta = {"steps": [{"index": i + 1, "title": t}
                          for i, t in enumerate((t1, t2, t3))]}
        landed = httpx.post(f"{CORE_BASE}/internal/ai-chat/sessions/{sid}/frames",
                            json={"messageId": msg_id, "frameType": "plan",
                                  "payload": "多步执行计划（共 3 步，确认后开始执行）：\n"
                                             + "\n".join(f"{i + 1}. {t}"
                                                         for i, t in enumerate((t1, t2, t3))),
                                  "metaJson": json.dumps(meta, ensure_ascii=False)},
                            timeout=30).json()
        assert landed.get("code") == "0", landed

        # 1. The card renders with one tickable row per journaled step (hard counts).
        card = page.locator('[data-test="chat-plan-card"]')
        expect(card).to_be_visible(timeout=30000)
        expect(card.locator("li.chat-plan-step")).to_have_count(3)
        for title in (t1, t2, t3):
            expect(card.locator("label.chat-plan-label").filter(has_text=title)).to_be_visible()
        assert_no_errors(collectors, "plan card renders the journaled 3-step plan")

        # 2. Untick the middle step, move the last row up TWO slots (each
        # click moves one row and the data-test index follows the current DOM
        card.locator("#chat-plan-ck-2").uncheck()
        card.locator('[data-test="chat-plan-up-2"]').click()
        expect(card.locator("li.chat-plan-step").nth(1).locator("label.chat-plan-label")).to_contain_text(t3)
        card.locator('[data-test="chat-plan-up-1"]').click()
        expect(card.locator("li.chat-plan-step").first.locator("label.chat-plan-label")).to_contain_text(t3)
        card.locator('[data-test="chat-plan-confirm"]').click()

        # The receipt posture seals the card: hint gone, checkbox disabled,
        # a second confirm is physically impossible.
        expect(page.locator('[data-test="chat-plan-receipt"]')).to_be_visible(timeout=10000)
        expect(card.locator("#chat-plan-ck-1")).to_be_disabled()

        # 3. The ledger is the source: the newest user message carries the
        # ticked titles in the EDITED order and never the unticked one.
        def _composed_user_question():
            return sql_query("SELECT content FROM tp_ai_chat_message WHERE session_id="
                             f"{sid} AND role='user' ORDER BY id DESC LIMIT 1")
        deadline = time.time() + 30
        composed = ""
        while time.time() < deadline:
            composed = _composed_user_question()
            if t3 in composed and t1 in composed:
                break
            time.sleep(0.5)
        assert t3 in composed and t1 in composed, f"edited question not journaled: {composed!r}"
        assert composed.index(t3) < composed.index(t1), \
            f"the move-up did not reach the composed question: {composed!r}"
        assert t2 not in composed, f"the unticked step leaked into the question: {composed!r}"
        assert_no_errors(collectors, "plan confirm composes the edited question through the send door")

        # Both turns must seal before the reload door is judged (the second
        # run is a real streamed answer of the composed question).
        deadline = time.time() + 240
        streaming = "1"
        while time.time() < deadline:
            streaming = sql_query("SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
                                  f"{sid} AND status='streaming'")
            if streaming == "0":
                break
            time.sleep(1.0)
        assert streaming == "0", "the composed-plan follow-up turn never sealed"

        # 4. History reload: sealed rows replay their ledger text, and the
        # plan zone stays honestly empty (no re-clickable card, iron rule 5d
        # — a hard zero-count assertion, never an if-wrap). A real document
        # reload is required: goto to the same hash URL is a same-document
        # navigation that would leave the live in-memory turns painted.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="chat-plan-card"]')).to_have_count(0)
        expect(page.get_by_text(t3, exact=False).first).to_be_visible(timeout=10000)
        assert_no_errors(collectors, "plan card reload replay keeps an honest empty plan zone")
    finally:
        purge_project(api_client, pid)
