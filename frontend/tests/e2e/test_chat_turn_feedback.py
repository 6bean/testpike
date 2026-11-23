"""Per-turn thumbs feedback E2E .

Proves the rating loop in a REAL browser against the REAL chain
(browser -> admin 8081 -> core 8083 -> RabbitMQ -> ai-service -> real LLM):

1) after one streamed answer the thumbs doors render ONLY under the sealed
 assistant row (the question capsule has none);
2) clicking 👎 opens the one-sentence complaint door; submitting it writes the
 ledger (checked against the real DB row, not a toast);
3) a reload folds the rating AND the complaint back from the ledger — the
 screen shows what the server stored (never an optimistic-only claim);
4) switching to 👍 clears the complaint on the LEDGER (the mixed signal never
 survives a reload either);
5) the clear-rating link drops the rating to 0 in the ledger.

Iron rules: exec gate hot-enabled and restored; scratch project purged by the
shared sweep; every phase ends with the 4xx/5xx + console + Issues-panel
zero-tolerance check. Pinned to the shared exec-switch worker group like the
rest of the AI-chat family.
"""
import pytest
from conftest import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    inject_token,
    purge_project,
    seed_project,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]


def _assistant_message_id(pid: int) -> str:
    sid = sql_query(f"SELECT id FROM tp_ai_chat_session WHERE project_id={pid} ORDER BY id DESC LIMIT 1")
    assert sid, "the streamed turn must have opened a session row"
    mid = sql_query(f"SELECT id FROM tp_ai_chat_message WHERE session_id={sid} "
                    f"AND role='assistant' ORDER BY id DESC LIMIT 1")
    return mid


def test_thumbs_feedback_round_trip_survives_reload(page, collectors, api_client, uniq):
    """Stream -> dislike + sentence -> reload shows ledger truth -> switch clears it."""
    pid = seed_project(api_client, uniq, "FEEDBACK")
    token = uniq[:6].lower()
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill(f"只回答两个字：评价{token}")
        inp.press("Enter")
        expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)

        rows = page.locator('[data-test="chat-msg"]')
        expect(rows).to_have_count(2, timeout=10000)
        asked, answered = rows.nth(0), rows.nth(1)

        # 1) Doors render under the sealed answer only (running rows had none;
        #    a user capsule never carries a rating surface).
        expect(answered.locator('[data-test="chat-feedback"]')).to_have_count(1, timeout=10000)
        expect(asked.locator('[data-test="chat-feedback"]')).to_have_count(0)
        assert_no_errors(collectors, "feedback doors render")

        # 2) 👎 opens the complaint door; the sentence lands in the LEDGER.
        answered.locator('[data-test="chat-feedback-down"]').click()
        reason_box = page.locator('[data-test="chat-feedback-reason"]')
        expect(reason_box).to_be_visible(timeout=5000)
        reason = f"引用过期{token}"
        # The el-input data-test rides through to the inner textarea itself
        # (established E2E fact), so fill the handle directly.
        page.locator('[data-test="chat-feedback-reason-input"]').fill(reason)
        page.locator('[data-test="chat-feedback-reason-send"]').click()
        expect(page.locator('[data-test="chat-feedback-reason"]')).to_have_count(0, timeout=10000)
        expect(answered.locator('[data-test="chat-feedback-reason-text"]')).to_contain_text(
            reason, timeout=10000)

        mid = _assistant_message_id(pid)
        ledger = sql_query(f"SELECT feedback || '|' || coalesce(feedback_reason,'~') "
                           f"FROM tp_ai_chat_message WHERE id={mid}")
        assert ledger == f"-1|{reason}", f"ledger must carry the -1 rating plus sentence, got {ledger!r}"
        assert_no_errors(collectors, "dislike with complaint")

        # 3) Reload folds the ledger back: the pressed state AND the sentence
        #    come from the server, never from a lost local click.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-msg"]')).to_have_count(2, timeout=15000)
        answered = page.locator('[data-test="chat-msg"]').nth(1)
        down = answered.locator('[data-test="chat-feedback-down"]')
        expect(down).to_have_attribute("aria-pressed", "true", timeout=10000)
        expect(answered.locator('[data-test="chat-feedback-reason-text"]')).to_contain_text(reason)
        assert_no_errors(collectors, "reload folds the rating")

        # 4) Switching to 👍 clears the complaint — on the screen AND the ledger.
        answered.locator('[data-test="chat-feedback-up"]').click()
        expect(answered.locator('[data-test="chat-feedback-reason-text"]')).to_have_count(0, timeout=10000)
        ledger = sql_query(f"SELECT feedback || '|' || coalesce(feedback_reason,'~') "
                           f"FROM tp_ai_chat_message WHERE id={mid}")
        assert ledger == "1|~", f"stale complaint must clear with the switch, got {ledger!r}"
        assert_no_errors(collectors, "switch clears the sentence")

        # 5) The clear-rating link writes 0 back to the ledger.
        answered.locator('[data-test="chat-feedback-clear"]').click()
        expect(answered.locator('[data-test="chat-feedback-up"]')).to_have_attribute(
            "aria-pressed", "false", timeout=10000)
        ledger = sql_query(f"SELECT feedback FROM tp_ai_chat_message WHERE id={mid}")
        assert ledger == "0", f"clear link must write 0, got {ledger!r}"
        assert_no_errors(collectors, "clear rating")
    finally:
        purge_project(api_client, pid)
        _set_sys_config_value("ai.chat.exec-enabled", "true")
