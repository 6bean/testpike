
import json
import time

import pytest
from chat_spectrum_support import (
    BASE_URL,
    assert_no_errors,
    db_open_count,
    inject_token,
    purge_project,
    seed_project,
    sql_query,
    wait_session_opened,
    wait_streaming_message,
)
from playwright.sync_api import expect

# Two live streamed turns (suspend + resume) on the shared LLM loop.
pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]

RESUME_COMMAND = "继续"  # GoalPlanLedger.RESUME_COMMAND — single source


def _goal_plan_rows(sid):
    """Newest ledger row — the same (plan_version DESC, revision DESC) door
    the reload fold uses (chat_spectrum_support.db_open_count is its sibling;
    a suspended turn may re-propose and bump the revision on version 1)."""
    stored = sql_query(
        "SELECT steps_json FROM tp_ai_chat_goal_plan"
        f" WHERE session_id={sid}"
        " ORDER BY plan_version DESC, revision DESC LIMIT 1")
    return json.loads(stored) if stored else []


def test_clarify_suspension_journals_ledger_and_resumes(page, collectors, api_client, uniq):
    """Ambiguous plan-first ask -> suspend -> ledger in DB -> reload card -> resume run."""
    pid = seed_project(api_client, uniq, "CLARIFYRESUME")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        # Layer 1 — the run must genuinely suspend: the ask both names a
        # multi-step goal (so the model proposes a plan through plan.propose)
        # and requires the missing sorting scope to be raised through the
        # structured clarify channel. Prose-only questions keep the turn on
        # the normal finish door, so the ask pins the tool by its FC name
        # (chat.clarify) and forbids asking in plain text — measured live
        # twice: an unstipulated ask let the model fold the question into
        # the plan-confirmation sentence and the card never rendered.
        page.locator('[data-test="chat-dock-input"]').fill(
            f"探针{uniq}：先用计划工具列出你的执行计划（步骤清单），"
            "再用澄清卡片（chat.clarify 工具）向我提问「这批用例按什么口径分组」并停下等我回答；"
            "提问必须走澄清卡片工具，不要只在正文里问，也不要在回答之前开始执行")
        page.locator('[data-test="chat-dock-input"]').press("Enter")

        sid, _msg_id = wait_streaming_message(90, pid)

        # Layer 2 — the suspension posture: a clarify card renders from the
        # run's own journaled frame (never parked by the fixture here), and
        # the turn ends without the model executing anything.
        card = page.locator('[data-test="chat-clarify-card"]')
        expect(card.first).to_be_visible(timeout=180000)
        assert_no_errors(collectors, "clarify suspension renders its own card")

        # Layer 3 — the fix's hard assertion: the proposed ledger is journaled
        # even though the run suspended. Pre-fix this row simply did not exist.
        # Retry window: the suspend-lane persist is fire-and-forget soft.
        rows = []
        for _ in range(60):
            rows = _goal_plan_rows(sid)
            if rows:
                break
            time.sleep(1)
        open_now = db_open_count(sid)
        assert open_now >= 1, (
            f"suspended run proposed a plan but journaled no ledger rows "
            f"(session {sid}); the goal card would reload empty — INV5 defect")

        # Layer 4 first pins the CLOCK, not the snapshot: the suspend lane
        # persists fire-and-forget, and a re-proposal can bump the newest
        # revision while the turn is still streaming (measured live: the
        # ledger read 4 then 5 mid-stream while the sealed card folded 7 —
        # every version of that mismatch is the same read race, not a
        # ledger/card divergence). Wait for the assistant row to seal, then
        # read the newest row once — the door the reload fold will use.
        deadline = time.time() + 240
        sealed = ""
        while time.time() < deadline:
            sealed = sql_query("SELECT status FROM tp_ai_chat_message"
                               f" WHERE session_id={sid} AND role='assistant'"
                               " ORDER BY id DESC LIMIT 1")
            if sealed and sealed != "streaming":
                break
            time.sleep(1)
        assert sealed and sealed != "streaming", \
            f"suspend turn never sealed (status {sealed!r}) — cannot compare card to ledger"
        open_now = db_open_count(sid)

        # Layer 4 — reload through a real navigation: the card survives with
        # header counts equal to the sealed ledger's open steps (worst-scale
        # fold is pinned by the goal-progress spectrum; equality is here).
        page.reload(wait_until="domcontentloaded")
        goal = page.locator('[data-test="chat-goal-card"]')
        expect(goal).to_be_visible(timeout=20000)
        expect(goal.locator('[data-test="chat-goal-count"]')).to_contain_text(str(open_now))
        assert_no_errors(collectors, "goal card reload keeps the suspended ledger")

        # Layer 5 — resume: the whole-prompt equality door opens a fresh
        # streamed run (the answer-from-nothing posture is the defect).
        page.locator('[data-test="chat-dock-input"]').fill(RESUME_COMMAND)
        page.locator('[data-test="chat-dock-input"]').press("Enter")
        wait_streaming_message(120, pid)
        # The resumed run must see the OPEN tail, not an empty history: the
        # ledger row is still the source after the resume turn opens.
        assert db_open_count(sid) >= 1
        assert_no_errors(collectors, "resume command reopens a streamed run on the open ledger")
    finally:
        purge_project(api_client, pid)
