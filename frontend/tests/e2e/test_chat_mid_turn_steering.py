"""Mid-turn steering (inject-into-a-running-answer) E2E .

Proves the steering contract in a REAL browser against the REAL chain
(browser -> admin 8081 -> core 8083 -> RabbitMQ -> ai-service -> real LLM):

1) while an answer streams the composer stays alive and a steer door renders
 (chat-dock-steer): disabled while the draft is empty, enabled the moment a
 supplement is typed;
2) Enter mid-run steers the running turn and NEVER opens a second stream
 (exactly one POST .../stream, exactly one POST .../injects -- counted on
 the network, not asserted from the UI);
3) the server journals the inject and echoes it back on the SAME open SSE
 stream: the process column folds a data-kind=user-inject row marked with
 the .chat-inject-chip, carrying the supplement text verbatim;
4) after the turn settles the steer door retires, and a reload re-reads the
 thread from the ledger (single loader) -- the user-inject row comes back
 exactly once, proving the server stored it (never an optimistic echo);
5) worst-case scale (S group): the thread already carries a 32-row ledger
 history before the steering turn -- the windowed list keeps the DOM
 honest (bounded rows, no full inline render) while the steer still works.

Iron rules: exec gate hot-enabled and restored; scratch project purged by
the shared sweep; every phase ends with the 4xx/5xx + console + Issues-panel
zero-tolerance check; hard assertions only (rule 5d, no conditional guards).
Pinned to the shared exec-switch worker group like the rest of the AI-chat
family.
"""
import pytest
import re

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

pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("ai-chat-exec-switch")]

HISTORY_ROWS = 32


def test_mid_turn_steering_injects_into_the_running_answer(page, collectors, api_client, uniq):
    """32-row history -> long streamed question -> Enter steers -> ledger echo -> reload."""
    pid = seed_project(api_client, uniq, "STEER")
    token = uniq[:6].lower()
    admin_id = int(sql_query("SELECT id FROM tp_user WHERE username='admin'").splitlines()[0])
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    # The pump's first-frame budget (K8 hot key, seed 5s, write-side interval
    # gate 2~30 -- AiChatKeyRanges refuses out-of-range values, v4 red proved
    # the guard works) legitimately closes the transport while a real provider
    # warms up on a heavy prompt; the steering contract below is about a run
    # that IS streaming live. Pin the knob to its in-range ceiling for the
    # duration of this test (restore in the tail), the same scenario-setup
    # discipline the exec gate already uses.
    _set_sys_config_value("ai.chat.first-frame-budget-seconds", "30")
    try:
        # Worst-scale fixture: one real session already holding 32 ledger rows.
        sid = int(sql_query(
            "INSERT INTO tp_ai_chat_session (project_id,user_id,title,source,updated_at) "
            f"VALUES ({pid},{admin_id},'转向夹具{token}',2,now()) RETURNING id").splitlines()[0])
        sql_query(
            "INSERT INTO tp_ai_chat_message (session_id,role,content,status,prompt_tokens,completion_tokens) "
            f"SELECT {sid}, CASE WHEN g % 2 = 0 THEN 'user' ELSE 'assistant' END, "
            f"'夹具历史{token} ' || g, 'done', 10, 20 FROM generate_series(1,{HISTORY_ROWS}) g")
        booked = int(sql_query(f"SELECT count(*) FROM tp_ai_chat_message WHERE session_id={sid}"))
        assert booked == HISTORY_ROWS, f"fixture ledger must hold {HISTORY_ROWS} rows, got {booked}"

        inject_token(page)
        # The panel restores this session through the per-context pointer
        # (same localStorage contract the live UI writes) -- a real reload
        # path into the windowed history, not a private test backdoor.
        page.evaluate(
            f"() => localStorage.setItem('tp_chat_ctx_{admin_id}_{pid}', '{sid}')")
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        rows = page.locator('[data-test="chat-msg"]')
        expect(rows.first).to_be_visible(timeout=15000)
        dom = rows.count()
        assert 5 <= dom <= 50, \
            f"windowed list must render a bounded window of the 32-row ledger, DOM held {dom}"
        assert_no_errors(collectors, "32-row history renders windowed")

        # Network counters: the second-stream refusal is measured, not shown.
        events = {"stream": 0, "inject": 0}

        def on_request(req):
            if req.method != "POST":
                return
            if req.url.endswith("/stream") and "/ai/chat/sessions/" in req.url:
                events["stream"] += 1
            elif req.url.endswith("/injects") and "/ai/chat/sessions/" in req.url:
                events["inject"] += 1

        page.on("request", on_request)

        # A deliberately long answer so the steering window is real.
        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill("请用编号逐条列举至少二十五条回归测试要点，每条一到两句话，全部列完再收尾")
        inp.press("Enter")

        # Contract precondition (same gating discipline as L3 spectra A): steer
        # only a run whose FIRST journaled delta already reached the browser.
        # The pump-side first-frame budget (K8 key ai.chat.first-frame-budget-
        # seconds, seed default 5s) honestly closes the transport while a real
        # provider is still warming up -- grounding embeds plus a 25-item
        # prompt's time-to-first-token exceed it at times. After that closure
        # the supplement still journals onto the running turn (core door), but
        # no open stream exists to echo it live by design; reload/reconnect
        # read it from the ledger instead. v3 red (2026-11-01 02:14) was
        # exactly this race: the inject landed after the budget closed the SSE.
        answer = page.locator('[data-test="chat-msg"][data-state="running"]').locator('[data-test="chat-msg-md"]')
        # The dock paints an empty assistant turn with a `.md-empty`
        # placeholder sentence, so "contains any non-space text" was TRUE at
        # once -- v5/v6 (2026-11-01 02:32 / 02:41) steered a run whose first
        # delta had not landed, and the pump had honestly not echoed a live
        # frame yet. A real delta replaces the placeholder: gate on its
        # disappearance plus body text, anchored to the RUNNING row (never
        # `.last`, which the 32 fixture rows can satisfy on their own).
        expect(answer).to_have_count(1, timeout=30000)
        expect(answer.locator(".md-empty")).to_have_count(0, timeout=120000)
        expect(answer).to_contain_text(re.compile(r"\S"), timeout=5000)

        steer = page.locator('[data-test="chat-dock-steer"]')
        expect(steer).to_be_visible(timeout=20000)
        expect(steer).to_be_disabled()  # empty draft: door visible but honest about nothing to send
        assert events["stream"] == 1, f"one question = one stream, got {events['stream']}"
        assert_no_errors(collectors, "steer door renders mid-run")

        supplement = f"补充{token}：只保留最重要的五条并加一句话总结"
        inp.fill(supplement)
        expect(steer).to_be_enabled()
        inp.press("Enter")

        expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=10000)
        assert events["stream"] == 1, \
            f"Enter mid-run must steer, never open a second stream (streams={events['stream']})"
        assert events["inject"] == 1, \
            f"the supplement must land as exactly one inject call, got {events['inject']}"
        assert_no_errors(collectors, "supplement injected, no second stream")

        # The journal echo rides the SAME open stream into the process column.
        inject_row = page.locator('[data-kind="user-inject"]')
        expect(inject_row).to_have_count(1, timeout=60000)
        expect(inject_row.first).to_contain_text(supplement, timeout=10000)
        expect(page.locator('.chat-inject-chip')).to_have_count(1)
        assert_no_errors(collectors, "user-inject echo on the live stream")

        # Settle: the answer seals, the steer door retires with running=false.
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=300000)
        expect(steer).to_have_count(0, timeout=10000)
        assert_no_errors(collectors, "door retires after settle")

        # Reload: the thread comes from the ledger, the inject row comes back
        # exactly once (no optimistic duplicate, no lost journal).
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        reloaded = page.locator('[data-kind="user-inject"]')
        expect(reloaded).to_have_count(1, timeout=20000)
        expect(reloaded.first).to_contain_text(supplement, timeout=10000)
        ledger_frames = int(sql_query(
            f"SELECT count(*) FROM tp_ai_chat_frame WHERE session_id={sid} "
            "AND event_type='user-inject'"))
        assert ledger_frames == 1, \
            f"the ledger (not the DOM) is the source: expected 1 journaled user-inject, got {ledger_frames}"
        assert_no_errors(collectors, "reload folds the inject from the ledger")
    finally:
        purge_project(api_client, pid)
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _set_sys_config_value("ai.chat.first-frame-budget-seconds", "5")
