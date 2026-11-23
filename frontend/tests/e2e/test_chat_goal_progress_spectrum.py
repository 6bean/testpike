
import pytest
from chat_spectrum_support import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    db_open_count,
    goal_steps_json,
    inject_token,
    journal_goal_ledger,
    purge_project,
    seed_project,
    send_unambiguous,
    sql_query,
    wait_assistant_sealed,
    wait_session_opened,
)
from playwright.sync_api import expect

# Shares the single-worker group of the ai-chat family: this spectrum drives
# one live streamed turn on the shared LLM loop before touching the ledger.
pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]

OPEN_PENDING = 30
OPEN_BLOCKED = 1
CLOSED_DONE = 3


def test_chat_goal_card_survives_reload_and_closes_honestly(
        page, collectors, api_client, uniq):
    """Stream -> journal a 34-step ledger -> reload keeps the folded card
    whose header alone matches the DB's open counts -> close the ledger ->
    reload shows no fake card."""
    pid = seed_project(api_client, uniq, "GOALCARD")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        # Turn 1: one harmless question so a real SSE run streams and seals.
        send_unambiguous(page, f"请用一句话解释回归测试（探针{uniq}）")
        sid = wait_session_opened(120, pid)
        wait_assistant_sealed(240, sid)

        # Phase A — reload with an open ledger: the card returns folded.
        journal_goal_ledger(sid, goal_steps_json(uniq, OPEN_PENDING, OPEN_BLOCKED, CLOSED_DONE))
        assert db_open_count(sid) == OPEN_PENDING + OPEN_BLOCKED
        page.reload(wait_until="domcontentloaded")
        card = page.locator('[data-test="chat-goal-card"]')
        expect(card).to_be_visible(timeout=20000)

        # default fold: the step list is hidden until asked, while the
        # header row answers everything with zero clicks (R33 — the card is
        # a state surface, not a wall of rows).
        step0 = card.locator('[data-test="chat-goal-step-0"]')
        expect(step0).not_to_be_visible()
        expect(card.locator('[data-test="chat-goal-count"]')).to_be_visible()
        expect(card.locator('[data-test="chat-goal-count"]')).to_contain_text(
            str(OPEN_PENDING + OPEN_BLOCKED))
        expect(card.locator('[data-test="chat-goal-badge-pending"]')).to_contain_text(
            str(OPEN_PENDING))
        expect(card.locator('[data-test="chat-goal-badge-blocked"]')).to_contain_text(
            str(OPEN_BLOCKED))
        current = card.locator('[data-test="chat-goal-current"]')
        expect(current).to_be_visible()
        expect(current).not_to_have_text("")
        expect(card.locator('[data-test="chat-goal-progress"]')).to_have_attribute(
            "role", "progressbar")

        # Expanded: one row per still-open step, hard count against the DB.
        card.locator('[data-test="chat-goal-toggle"]').click()
        rows = card.locator('[data-test^="chat-goal-step-"]')
        expect(rows).to_have_count(OPEN_PENDING + OPEN_BLOCKED)
        # The duplicated bottom summary is gone : the sentence
        # element never renders, and the card never asks the reader to type
        # a resume command .
        expect(card.locator('[data-test="chat-goal-sentence"]')).to_have_count(0)
        expect(card).not_to_contain_text("回复")
        # hard gate: the retired typing footer must render nowhere on
        # the page — exact retired sentence, zero count (iron rule 5d: a
        # count assertion, never a soft not-contain that tolerates markup
        # drift around the words).
        expect(page.get_by_text("回复「继续」即可接着执行")).to_have_count(0)
        for probe in (f"目标残步01{uniq}", f"目标阻步01{uniq}"):
            expect(rows.filter(has_text=probe).first).to_be_visible()
        # Blocked ledger rows speak the failure wording ( five-state
        # ( residual, : the per-step openStates channel must
        # survive the reload, not flatten everything to pending.)
        blocked_row = rows.filter(has_text=f"目标阻步01{uniq}").first
        expect(blocked_row.locator(".chat-goal-state")).to_have_text("失败")
        expect(card.locator(".chat-goal-state", has_text="待执行")).to_have_count(
            OPEN_PENDING)
        # Collapse again: rows hide, the header keeps telling the truth.
        card.locator('[data-test="chat-goal-toggle"]').click()
        expect(step0).not_to_be_visible()
        assert_no_errors(collectors, "goal card header answers the reload folded, detail counts on expand")

        # Phase B — the ledger closes (every step done WITH an evidence
        # anchor): the sealed row must not fake a card after a reload.
        journal_goal_ledger(sid, goal_steps_json(uniq, OPEN_PENDING, OPEN_BLOCKED,
                                                 CLOSED_DONE, all_done=True))
        assert db_open_count(sid) == 0
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="chat-goal-card"]')).to_have_count(0)
        assert_no_errors(collectors, "a fully closed ledger keeps an honest card-free reload")
    finally:
        purge_project(api_client, pid)


def test_chat_goal_auto_continuation_is_visible_without_typing(
        page, collectors, api_client, uniq):
    """ in-progress posture: one question, ZERO further user input, and
 the run carries the reader through the continuation — the batch-1 state
 frame and the honest cap-stop frame both surface in the process area,
 the retired typing footer never renders, and a reload replays the pair
 (the ledger is the source of truth, frames survive the document hop)."""
    CAP_KEY = "ai.chat.goal.max-auto-batches"
    pid = seed_project(api_client, uniq, "GOALAUTO")
    try:
        _set_sys_config_value(CAP_KEY, "1")
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        # Ask through the UI first (the page opens its own session — raw
        # inserts land on a session the browser never reads), then journal
        # the 40-step ledger before the turn seals: the auto loop is then
        # guaranteed to find open steps at seal time, and cap=1 bounds the
        # whole run to exactly one batch (no model-dependent finishing).
        send_unambiguous(page, f"请用一句话解释冒烟测试（探针{uniq}）")
        sid = wait_session_opened(120, pid)
        journal_goal_ledger(sid, goal_steps_json(uniq, pending=40,
                                                 blocked=0, done=0))
        assert db_open_count(sid) == 40
        wait_assistant_sealed(240, sid)
        assert sql_query(
            "SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
            f"{sid} AND role='user'") == "1"

        # The frame pair is in the DOM; the process area is collapsed BY
        # DEFAULT per message (R33 posture pinned by ), so one toggle
        # click must bring it up — expand, then hard-assert visibility.
        process = page.locator('[data-test="chat-dock-process"]')
        page.locator('[data-test="chat-process-toggle"]').click()
        expect(process.locator("text=自动接续第 1 批")).to_be_visible(timeout=30000)
        expect(process.locator("text=已达安全上限")).to_be_visible()
        expect(page.locator("text=回复「继续」即可接着执行")).to_have_count(0)
        assert_no_errors(collectors, "auto continuation frames read live")

        # Reload honesty (contract, chatHistoryMapper: sealed rows rebuild the
        # answer only — process lines are a live-turn surface, the persistent
        # surface is the ledger/card pinned by the test above). So the
        # reload posture asserted here is: the answer survives, and the
        # retired typing footer never appears from any door.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="chat-msg-md"]').first).to_be_visible()
        expect(page.locator("text=回复「继续」即可接着执行")).to_have_count(0)
        assert_no_errors(collectors, "the sealed answer survives the reload")
    finally:
        _set_sys_config_value(CAP_KEY, "3")
        purge_project(api_client, pid)
