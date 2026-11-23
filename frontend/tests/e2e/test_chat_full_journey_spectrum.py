
import time

import pytest
from chat_spectrum_support import (
    BASE_URL,
    assert_no_errors,
    db_open_count,
    goal_steps_json,
    inject_token,
    journal_goal_ledger,
    park_clarify,
    park_suggest_done,
    purge_project,
    seed_project,
    send_unambiguous,
    sql_query,
    wait_assistant_sealed,
    wait_streaming_message,
)
from playwright.sync_api import expect

# Shares the single-worker group of the ai-chat family: this spectrum drives
# three live streamed turns on the shared LLM loop inside one session.
pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("ai-chat-exec-switch")]

ASK_TEXT = "帮我把这批用例整理一下"
Q_TEXT = "你想让我按哪种口径整理这批用例？"
OPT_A = "按功能模块分组"
OPT_B = "按优先级排序"
OPT_C = "按评审状态过滤"
MISSING_SLOT = "整理口径"
RESUME_TEXT = "继续"
CHIP_A = "把这次整理结果生成为用例草稿"
CHIP_B = "对相关需求点发起评审"
CHIP_C = "汇总本项目的执行失败清单"

OPEN_PENDING = 4
OPEN_BLOCKED = 1
OPEN_DONE = 2
RESUME_STEPS = 3


def _user_row_count(sid):
    return int(sql_query("SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
                         f"{sid} AND role='user'"))


def _newest_user_content(sid):
    newest = sql_query("SELECT id FROM tp_ai_chat_message WHERE session_id="
                       f"{sid} AND role='user' ORDER BY id DESC LIMIT 1")
    return sql_query(f"SELECT content FROM tp_ai_chat_message WHERE id={newest}") if newest else ""


def _await_user_message(sid, marker, deadline_s=60):
    """The ledger, not the DOM, is the source: a press must compose the text."""
    deadline = time.time() + deadline_s
    seen = ""
    while time.time() < deadline:
        seen = _newest_user_content(sid)
        if marker in seen:
            return seen
        time.sleep(1.0)
    raise AssertionError(f"{marker!r} never reached the ledger as the newest user message: {seen!r}")


def test_chat_full_journey_clarify_resume_and_extend(page, collectors, api_client, uniq):
    """Six segments in one live conversation, two hand-offs, two clicks."""
    pid = seed_project(api_client, uniq, "JOURNEY")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        # Segment 1 — the request is missing a slot. The prompt that opens the
        # run stays self-contained on purpose: the live run owns the real
        # clarify channel, and a prompt that invites a genuine clarification
        # would journal its own frame over the card below .
        send_unambiguous(page, f"探针自检（编号{uniq}）：{ASK_TEXT}，"
                               "请用一两句话说明你会怎么做，不要向我提问，也不要调用任何工具")
        sid, msg_id = wait_streaming_message(90, pid)
        park_clarify(sid, msg_id, Q_TEXT, (OPT_A, OPT_B, OPT_C), MISSING_SLOT)

        card = page.locator('[data-test="chat-clarify-card"]')
        expect(card).to_be_visible(timeout=30000)
        expect(page.locator('[data-test="chat-clarify-question"]')).to_contain_text(Q_TEXT)
        expect(card.locator("button.chat-clarify-option")).to_have_count(3)
        assert_no_errors(collectors, "journey segment 1: the missing slot asks one question")

        # Segment 2 — ONE click answers, and the card seals into a receipt.
        rows_before = _user_row_count(sid)
        expect(card.locator('[data-test="chat-clarify-option-0"]')).to_be_enabled(timeout=240000)
        card.locator('[data-test="chat-clarify-option-1"]').click()
        assert OPT_B in _await_user_message(sid, OPT_B)
        assert _user_row_count(sid) == rows_before + 1, \
            "the one-click answer composed more than one user message"
        expect(page.locator('[data-test="chat-clarify-receipt"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="chat-clarify-receipt"]')).to_contain_text(OPT_B)
        for i in range(3):
            expect(card.locator(f'[data-test="chat-clarify-option-{i}"]')).to_be_disabled()
        assert_no_errors(collectors, "journey segment 2: one press answers and seals the card")

        # Segment 3 — the run continues: the follow-up turn seals, so the
        # session now owns two sealed assistant blocks (no dead end at the
        # question).
        wait_assistant_sealed(240, sid)
        sealed = int(sql_query("SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
                               f"{sid} AND role='assistant' AND status<>'streaming'"))
        assert sealed >= 2, f"the conversation stalled at the clarify turn: {sealed} sealed blocks"
        assert_no_errors(collectors, "journey segment 3: the follow-up turn streams and seals")

        # Segment 4 — the goal ledger is the outstanding work: an open ledger
        # survives a reload with the ledger's own count and the resume door.
        journal_goal_ledger(sid, goal_steps_json(uniq, OPEN_PENDING, OPEN_BLOCKED, OPEN_DONE))
        assert db_open_count(sid) == OPEN_PENDING + OPEN_BLOCKED
        page.reload(wait_until="domcontentloaded")
        goal = page.locator('[data-test="chat-goal-card"]')
        expect(goal).to_be_visible(timeout=20000)
        expect(goal.locator('[data-test="chat-goal-count"]')).to_contain_text(
            str(OPEN_PENDING + OPEN_BLOCKED))
        expect(goal.locator('[data-test^="chat-goal-step-"]')).to_have_count(
            OPEN_PENDING + OPEN_BLOCKED)
        # The compact state surface retired the duplicated resume
        # sentence: hard-zero count, never a soft not-contain.
        expect(goal.locator('[data-test="chat-goal-sentence"]')).to_have_count(0)
        assert_no_errors(collectors, "journey segment 4: the open ledger survives the reload")

        # Segment 5 — the user continues the conversation from the open ledger:
        # one message opens a fresh live run (the resume door is the composer
        # itself, not a footer sentence the product no longer renders).
        assistant_rows_before = int(sql_query(
            "SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
            f"{sid} AND role='assistant'"))
        send_unambiguous(page, f"{RESUME_TEXT}（探针{uniq}）：请用一两句话给出下一步建议，"
                               "不要向我提问，也不要调用任何工具")
        _, resume_msg_id = wait_streaming_message(120, pid)
        assert int(sql_query("SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
                             f"{sid} AND role='assistant'")) == assistant_rows_before + 1, \
            "the follow-up request did not open a fresh assistant run"
        assert_no_errors(collectors, "journey segment 5: a follow-up request opens a real run")

        # Segment 6 — the next step is one click. The done frame carrying the
        # suggestions is parked while the resume run is still streaming, so it
        # becomes the tail the dock folds exactly like a gated live one
        # (ChatStreamBridge closes the browser stream on a terminal frame).
        park_suggest_done(sid, resume_msg_id, [CHIP_A, CHIP_B, CHIP_C])
        strip = page.locator('[data-test="chat-suggest-strip"]')
        expect(strip).to_be_visible(timeout=30000)
        expect(strip.locator("button.chat-suggest-chip")).to_have_count(3)
        chip_rows_before = _user_row_count(sid)
        page.locator('[data-test="chat-suggest-chip-1"]').click()
        assert CHIP_B in _await_user_message(sid, CHIP_B)
        assert _user_row_count(sid) == chip_rows_before + 1, \
            "the one-click suggestion composed more than one user message"
        expect(page.locator('[data-test="chat-suggest-strip"]')).to_have_count(0, timeout=30000)
        assert_no_errors(collectors, "journey segment 6: one chip click takes the next step")

        # Every step done WITH an evidence anchor: a reload must not fake a
        # goal card (hard zero, never an if-wrap).
        journal_goal_ledger(sid, goal_steps_json(uniq, RESUME_STEPS, 0, 0, all_done=True))
        assert db_open_count(sid) == 0
        wait_assistant_sealed(240, sid)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="chat-goal-card"]')).to_have_count(0)
        assert_no_errors(collectors, "journey tail: a closed ledger keeps an honest card-free reload")
    finally:
        purge_project(api_client, pid)
