
import json
import os
import pathlib
import re
import subprocess
import time

import httpx
import pytest
from conftest import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    inject_token,
    launch_provider_stub,
    open_journey_leaf,
    pick_project_in_header,
    sql_query,
)
from conftest import (
    purge_project as _purge_project,
)
from conftest import (
    seed_project as _seed_project,
)
from playwright.sync_api import expect

# All dialog tests in this file flip the one global switch ai.chat.exec-enabled
# around their runs; under -n 4 a sibling worker's reset-to-false can land while
# another test's SSE turn is still streaming, surfacing a spurious "switch off"
# error frame (observed 2026-10-27). Pin the file to one worker like the
# o6_notif_readall / mock-plane-live precedents.
pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

DEAD_PROJECT_ID = 99999999  # far above any live tp_project row (observed max ~1.43M)
NO_PROJECT_HINT = "请先在顶栏选择项目，再开始对话"
CORE_BASE = os.environ.get("TP_CORE_BASE", "http://localhost:8083")
# Same baseline/proposal pair the L3 door-walk fixture lands (scripts/
# l3-aichat-draft-confirm.py): one stale locator swapped for the real button.
E2E_BASE_SCRIPT = ('{"version":"v2","steps":[{"action":"goto","target":{"url":"https://demo.local/login"}},'
                   '{"action":"click","target":{"css":"#old-anchor","chain":["css"]}}]}')
E2E_REPAIRED_SCRIPT = ('{"version":"v2","steps":[{"action":"goto","target":{"url":"https://demo.local/login"}},'
                       '{"action":"click","target":{"css":"#login-btn","chain":["css"]}}]}')


def test_chat_page_reachable_from_menu_and_mounts_kernel(logged_in, collectors):
    """Menu leaf reaches the page; the shared kernel mounts with its empty state."""
    open_journey_leaf(logged_in, "menu-ai-chat", "aiCapabilities")
    logged_in.wait_for_url("**#/ai/chat", timeout=10000)
    expect(logged_in.locator('[data-test="ai-chat-page"]')).to_be_visible(timeout=10000)
    expect(logged_in.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
    # Empty state names the journey (start asking, process first) — not a blank box.
    body = logged_in.locator('[data-test="chat-dock-body"]')
    expect(body).to_contain_text(re.compile("开始提问"), timeout=10000)
    assert_no_errors(collectors, "chat page menu journey mounts kernel")


STUB_PORT = int(os.environ.get("MOCK_LLM_CHATSTREAM_PORT", "18095"))
STUB_HITS = "/tmp/testpike-t500-chatstub-hits.jsonl"
# Provider-stub script shape : 6 model paragraphs folded from 24+
# tiny thinking fragments + 1 tail paragraph = 7 reason entries; the run also
# lands >=1 discrete state frame (grounding summary) -> >=8 process lines and
# >=7 reason items once expanded. The ledger keeps the pre-fold granularity
# (one frame per SSE chunk), asserted separately.
STUB_REASON_ITEMS = 7


def _start_chatstream_stub(hits_file: str):
    """Launch the provider-position stub and wait for readiness.

 Readiness detection is the shared launcher (debt): raw TCP probe +
 proc-death check, a held port named in the error instead of a false
 "failed to start" over a live listener.
 """
    # Fresh hit ledger per run (the zero-hit guard below reads this file).
    try:
        os.remove(hits_file)
    except FileNotFoundError:
        pass
    return launch_provider_stub("mock-llm-chatstream.py", STUB_PORT,
                                {"MOCK_LLM_HITS": hits_file})


def wire_chatstream_stub(pid: int, uniq: str):
    """Bind a fresh scratch project to the provider-position chatstream stub.

    Wired through the real routing chain: the tp_llm_config row is created via
    core's internal endpoint (so the api_key lands AES-encrypted; a raw SQL
    row would decrypt-fail and the router would silently fall back to the
    .env endpoint), then tp_project.llm_config_id points at it
    (ProjectLlmRouter project priority). Returns the stub process; the caller
    terminates it. Extracted for the second consumer (requirement dual-card
    spectrum) — no inline copies (extract-common law).
    """
    cfg = httpx.post(f"{CORE_BASE}/internal/llm-configs",
                     headers={"X-Operator-Id": "1"}, timeout=10,
                     json={"name": f"对话流桩端点-{uniq}", "provider": "openai",
                           "modelName": "chatstream-stub", "apiKey": "e2e-st...cret",
                           "baseUrl": f"http://127.0.0.1:{STUB_PORT}/v1",
                           "status": 1, "isDefault": 0, "modelType": 1,
                           "description": "对话流供应商位桩"}).json()
    assert cfg.get("code") == "0", f"llm config row creation failed: {cfg}"
    llm_cfg_id = int(cfg["data"]["id"])
    sql_query(f"UPDATE tp_project SET llm_config_id={llm_cfg_id} WHERE id={pid}")
    return _start_chatstream_stub(STUB_HITS)


def test_chat_page_deep_link_streams_real_answer_with_usage(page, collectors, api_client, uniq):
    """Valid ?projectId deep link: segment lands, door records source=2, stream seals.

 : the project is bound to the provider-position chatstream stub
 (real routing chain tp_project.llm_config_id -> core /internal/brief ->
 ProjectLlmRouter source=project), so the thinking-fragment traffic shape
 is deterministic and the fold spectrum is observable in the controlled
 environment (iron rule 5e). Process-area assertions are hard (iron rule
 5d): the toggle MUST exist, MUST be collapsed at seal, and the expanded
 list MUST show exactly the folded paragraph count — never an
 `if count > 0` silent skip.
 """
    # Seeded before login on purpose: the header option source is the injected
    # /users/me snapshot (hash navigation never re-fetches it).
    pid = _seed_project(api_client, uniq, "CHATSTREAM")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    stub = None
    try:
        # Provider-position stub wired through the real project routing
        # chain (single-source helper, second consumer: dual-card spectrum).
        stub = wire_chatstream_stub(pid, uniq)

        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        page.locator('[data-test="chat-dock-input"]').fill("只回答两个字：收到")
        page.locator('[data-test="chat-dock-input"]').press("Enter")
        # Running state shows the stop door (the UI never sits idle-blind).
        expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
        # Sealed turn: send door back, answer non-empty, usage line present.
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=120000)
        turn = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        expect(turn).to_contain_text(re.compile(r"\S"), timeout=10000)
        expect(turn.locator(".chat-msg-usage")).to_contain_text("tokens", timeout=10000)

        # ── hard assertions (iron rule 5d, no conditional wrapper) ──
        # Sealed turn: the process area auto-collapses to its summary row
        # (S-group default-collapsed at scale; the live unfold served the stream).
        toggle = turn.locator('[data-test="chat-process-toggle"]')
        expect(toggle).to_have_count(1, timeout=10000)
        expect(toggle).to_have_attribute("aria-expanded", "false", timeout=10000)
        # Expanded: the folded paragraphs are exactly the stub's model
        # paragraphs (7 reason entries) — the fragment waterfall (24+ tiny
        # thinking pieces as one item each) must never come back.
        toggle.click()
        expect(toggle).to_have_attribute("aria-expanded", "true", timeout=10000)
        reason_items = turn.locator('[data-test="chat-dock-process"] '
                                    'li.chat-process-item[data-kind="reason"]')
        expect(reason_items).to_have_count(STUB_REASON_ITEMS, timeout=10000)
        for i in range(STUB_REASON_ITEMS):
            expect(reason_items.nth(i)).to_contain_text(re.compile(r"\S"), timeout=5000)
        # Total process lines = 7 folded paragraphs + the run's discrete
        # state/trace frames (bounded band — a hard assertion, not a
        # conditional skip; the pre-fold waterfall would read >=31 lines).
        all_items = turn.locator('[data-test="chat-dock-process"] li.chat-process-item')
        n_all = all_items.count()
        assert STUB_REASON_ITEMS <= n_all <= STUB_REASON_ITEMS + 6, \
            f"process lines out of folded band: {n_all} (expected 7~13)"

        # The stub was truly hit (ProjectLlmRouter source=project): a silent
        # fallback to the .env endpoint would let a real provider answer and
        # this spectrum would test nothing (mock-llm-nlstep L3 lesson).
        with open(STUB_HITS, encoding="utf-8") as f:
            assert sum(1 for _ in f) >= 1, "provider-position stub got zero hits"

        # Ledger granularity is display-only folding: every provider frame
        # still landed as its own reason row (wire/ledger untouched).
        n_reason_frames = int(sql_query(
            "SELECT count(*) FROM tp_ai_chat_frame f "
            "JOIN tp_ai_chat_message m ON f.message_id=m.id "
            "JOIN tp_ai_chat_session s ON m.session_id=s.id "
            f"WHERE s.project_id={pid} AND f.event_type='reason'"))
        assert n_reason_frames >= 31, \
            f"ledger must keep per-fragment reason frames, got {n_reason_frames}"

        # The standalone door stamps source=2 on the session row (one enum,
        # never faked from the dock value).
        src = sql_query("SELECT source FROM tp_ai_chat_session WHERE project_id=" + str(pid) + " LIMIT 1")
        assert src == "2", f"standalone page must open the session door with source=2, got {src}"
        assert_no_errors(collectors, "chat page deep link real stream")
    finally:
        if stub is not None:
            stub.terminate()
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        try:
            cfg_id = sql_query(f"SELECT llm_config_id FROM tp_project WHERE id={pid}")
        except Exception:  # noqa: BLE001
            cfg_id = ""
        _purge_project(api_client, pid)
        if cfg_id and cfg_id.strip().isdigit():
            sql_query(f"DELETE FROM tp_llm_config WHERE id={int(cfg_id)}")


def test_chat_page_stop_door_aborts_live_turn(page, collectors, api_client, uniq):
    """Stop button aborts a long answer mid-flight: turn leaves running state."""
    pid = _seed_project(api_client, uniq, "CHATSTOP")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        page.locator('[data-test="chat-dock-input"]').fill("请逐字复述从1数到500，每个数字占一行，不要停。")
        page.locator('[data-test="chat-dock-input"]').press("Enter")
        expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
        # Let a couple of deltas land, then pull the plug through the stop door.
        turn = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        expect(turn).to_contain_text(re.compile(r"\d"), timeout=120000)
        page.locator('[data-test="chat-dock-stop"]').click()

        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=15000)
        expect(page.locator('[data-test="chat-msg"][data-role="assistant"][data-state="running"]')).to_have_count(0)
        # The partial answer stays on screen (honesty: nothing is erased).
        expect(turn).to_contain_text(re.compile(r"\d"), timeout=10000)
        assert_no_errors(collectors, "chat page stop door aborts turn")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)


def test_chat_composer_send_button_display_preference_round_trip(page, collectors, api_client, uniq):
    pass
    pid = _seed_project(api_client, uniq, "CHATPREF")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        # Factory default: no send button on the page surface, placeholder names
        # the Enter contract, and Enter sends the question for real.
        expect(page.locator('[data-test="chat-dock-send"]')).to_have_count(0)
        inp = page.locator('[data-test="chat-dock-input"]')
        expect(inp).to_have_attribute("placeholder", re.compile("Enter 发送"))
        inp.click()
        page.keyboard.type("只回答两个字：偏好")
        page.keyboard.press("Enter")
        expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)

        # The settings entry shows the button; the door label is the object-name
        page.locator('[data-test="chat-dock-settings"]').click()
        expect(page.locator('[data-test="chat-send-switch"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="chat-send-switch"]').click()
        send = page.locator('[data-test="chat-dock-send"]')
        expect(send).to_be_visible(timeout=5000)
        expect(send).to_have_text("发送")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="chat-dock-send"]')).to_be_visible(timeout=5000)
        assert_no_errors(collectors, "chat composer send-button preference")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)


def test_chat_page_dead_deeplink_stays_honestly_absent(page, collectors):
    """Dead ?projectId: no session row opened, the send door stays honestly shut
    on BOTH composer shapes (button hidden by default + Enter refused; with the
    optional send button shown, the door is visibly disabled)."""
    inject_token(page)
    page.goto(f"{BASE_URL}/#/ai/chat?projectId={DEAD_PROJECT_ID}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

    # Honest absence = the input names the missing header segment; the default
    # composer hides the send button, and Enter must not fire a send either.
    inp = page.locator('[data-test="chat-dock-input"]')
    expect(inp).to_have_attribute("placeholder", NO_PROJECT_HINT, timeout=10000)
    expect(page.locator('[data-test="chat-dock-send"]')).to_have_count(0)
    inp.fill("越权探针：这个项目并不存在")
    inp.press("Enter")
    page.wait_for_timeout(1500)

    # The optional send button (settings entry, off by default) is shown and
    # visibly disabled — the refusal is pointed, not silent.
    page.locator('[data-test="chat-dock-settings"]').click()
    expect(page.locator('[data-test="chat-send-switch"]')).to_be_visible(timeout=5000)
    page.locator('[data-test="chat-send-switch"]').click()
    expect(page.locator('[data-test="chat-dock-send"]')).to_be_disabled(timeout=5000)

    # Nothing was ever booked against the non-existent project.
    rows = int(sql_query(
        "SELECT count(*) FROM tp_ai_chat_session WHERE project_id=" + str(DEAD_PROJECT_ID)))
    assert rows == 0, f"dead deep link must not open a session, found {rows} rows"
    assert_no_errors(collectors, "chat page dead deep link honest absence")


def test_chat_dock_from_any_page_carries_whitelist_context(page, collectors, api_client, uniq):
    """TC-AO-05 dock spectrum: open the bubble over a list page that carries
 filter noise (?tab&keyword); the session row's context_json must hold ONLY
 the navigation identifiers (route + segment project) — never the keyword."""
    pid = _seed_project(api_client, uniq, "CHATCTX")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}&tab=runs&keyword=leakcheck",
                  wait_until="domcontentloaded")
        expect(page.locator('[data-test="ai-chat-fab"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="ai-chat-fab"]').click()
        expect(page.locator('[data-test="ai-chat-dock"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="chat-dock-input"]').fill("只回答两个字：位置")
        page.locator('[data-test="chat-dock-input"]').press("Enter")
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=120000)

        row = sql_query(
            "SELECT context_json::text FROM tp_ai_chat_session WHERE project_id=" + str(pid) + " LIMIT 1")
        assert row and row != "{}", f"dock session must persist a context, got {row!r}"
        assert "/cases" in row, f"context must carry the route, got {row!r}"
        assert f'"projectId":{pid}' in row.replace(" ", ""), f"context carries the slice, got {row!r}"
        assert "leakcheck" not in row and "runs" not in row, \
            f"filter noise leaked into the prompt context: {row!r}"
        assert_no_errors(collectors, "chat dock whitelist context")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)


def test_chat_page_history_survives_reload(page, collectors, api_client, uniq):
    """TC-AO-05 door: one real streamed answer, then a browser reload shows the
    same sealed thread (localStorage session pointer + the ledger history door —
    the answer body is rebuilt server-side from the delta frames)."""
    pid = _seed_project(api_client, uniq, "CHATHIST")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        question = "只回答两个字：历史"
        page.locator('[data-test="chat-dock-input"]').fill(question)
        page.locator('[data-test="chat-dock-input"]').press("Enter")
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=120000)
        turn = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        expect(turn).to_contain_text(re.compile(r"\S"), timeout=10000)
        # Seal before reloading: the ledger door lists sealed rows only (core
        # listSealedMessages filters streaming out — documented replay honesty,
        # see ChatFrameServiceImpl and chatHistoryMapper). Reloading mid-stream
        # would honestly show the question alone, so this journey waits for the
        # done frame (usage line) the same way the cite spectrum does.
        expect(turn.locator(".chat-msg-usage")).to_contain_text("tokens", timeout=120000)
        sessions_after_first = int(sql_query(
            f"SELECT count(*) FROM tp_ai_chat_session WHERE project_id={pid}"))

        # Reload: the thread must come back from the ledger, not a fresh empty panel.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-dock-body"]')).to_contain_text(question, timeout=15000)
        reloaded = page.locator('[data-test="chat-msg"]')
        expect(reloaded).to_have_count(2, timeout=10000)
        expect(reloaded.nth(1).locator(".chat-msg-usage")).to_contain_text("tokens", timeout=10000)
        # The reload read history — it never opened a second session behind the user's back.
        assert int(sql_query(f"SELECT count(*) FROM tp_ai_chat_session WHERE project_id={pid}")) \
            == sessions_after_first, "reload must reuse the persisted session"
        assert_no_errors(collectors, "chat page history survives reload")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)


def test_chat_page_help_cite_card_opens_help_drawer(page, collectors, api_client, uniq):
    """ knife 3b: a grounded answer shows source cards inside the live turn;
 clicking a help-flavoured card opens the help drawer on that chapter.

 Asserted inside the live stream on purpose — the reload spectrum rebuilds
 the sealed body only (cite frames are not replayed into history), so the
 card journey must be exercised while the turn is still on the wire.
 """
    pid = _seed_project(api_client, uniq, "CHATCITE")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        # Same help-domain question the L3 spectrum grounds on (live-verified
        # to produce cite frames with a help domainCode anchor).
        page.locator('[data-test="chat-dock-input"]').fill(
            "从需求一键生成结构化用例的功能怎么使用？请说明入口菜单。")
        page.locator('[data-test="chat-dock-input"]').press("Enter")
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)

        turn = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        expect(turn.locator('[data-test="chat-dock-citations"]')).to_be_visible(timeout=10000)
        card = turn.locator('[data-test^="chat-cite-"]').first
        expect(card).to_be_visible(timeout=10000)
        card_title = card.locator(".chat-cite-title").inner_text().strip()
        assert card_title, "cite card must name the source chapter"

        card.click()
        drawer = page.locator('[data-test="help-drawer"]')
        expect(drawer).to_be_visible(timeout=10000)
        # The drawer lands on a chapter (anchor hit or the honest domain-first
        # fallback) — body title is the visible proof the jump served its goal.
        body_title = drawer.locator(".help-doc-title")
        expect(body_title).to_be_visible(timeout=10000)
        expect(drawer).to_contain_text(re.compile(r"\S"), timeout=10000)
        assert_no_errors(collectors, "chat page help cite card opens drawer")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)


def test_chat_composer_slash_popup_is_honest(page, collectors, api_client, uniq):
    """ knife 4a: "/" opens the command list, keyboard path picks a row,
 the locale template lands in the composer; a token with no match closes
 the popup silently (typed text never hijacks, no dead popup)."""
    pid = _seed_project(api_client, uniq, "CHATSLASH")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        inp = page.locator('[data-test="chat-dock-input"]')
        popup = page.locator('[data-test="chat-popup"]')

        # Empty token: every command of the word table is offered.
        inp.click()
        inp.fill("/")
        expect(popup).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="chat-slash-analyzeFailures"]')).to_be_visible(timeout=5000)

        # Keyboard path: ArrowDown moves, Enter accepts — no mouse involved.
        page.keyboard.press("ArrowDown")
        inp.press("Enter")
        expect(popup).to_have_count(0, timeout=5000)
        value = inp.input_value()
        assert value and value != "/", f"Enter must drop the template into the composer, got {value!r}"

        # Unmatched token: honest silence (popup gone, text untouched).
        inp.fill("/zzz-nomatch")
        expect(popup).to_have_count(0, timeout=5000)
        assert inp.input_value() == "/zzz-nomatch"
        assert_no_errors(collectors, "chat composer slash popup honest")
    finally:
        _purge_project(api_client, pid)


def test_chat_composer_mention_chip_rides_the_stream(page, collectors, api_client, uniq):
    """ knife 4a E3 red line: only an ACCEPTED CHIP rides the wire.
 Seeded case -> "@token" popup -> Enter makes the chip -> send -> the live
 POST /stream body carries refObjects(kind=case,id); typed "@text" that was
 never accepted is plain text (second send without a chip has no field)."""
    pid = _seed_project(api_client, uniq, "CHATMENTION")
    token = uniq[:6].lower()
    case = api_client.post("/cases", json={
        "projectId": pid, "title": f"提及目标{token}", "precondition": "已登录",
        "expectedResult": "命中候选", "priority": 2, "confidence": 3,
        "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开列表", "isAssert": 0}],
    }).json()["data"]
    case_id = case["id"]
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        inp = page.locator('[data-test="chat-dock-input"]')

        inp.click()
        inp.fill(f"@{token}")
        expect(page.locator('[data-test="chat-mention-case"]')).to_be_visible(timeout=15000)
        inp.press("Enter")
        # Chip is the single truth: it shows, the @fragment left the text, popup closed.
        expect(page.locator('[data-test="chat-chip-case"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="chat-popup"]')).to_have_count(0, timeout=5000)
        assert token not in inp.input_value(), "accepted mention fragment must leave the composer"

        inp.click()
        page.keyboard.type("只回答两个字：收到")
        with page.expect_request(
                lambda r: r.method == "POST" and r.url.endswith("/stream")) as req_info:
            page.keyboard.press("Enter")
        body = json.loads(req_info.value.post_data)
        refs = body.get("refObjects")
        assert isinstance(refs, list) and len(refs) == 1 \
            and refs[0]["kind"] == "case" and refs[0]["id"] == case_id, \
            f"chip must ride the stream payload, got {refs!r}"

        # Second question WITHOUT a chip: the field must not ride (typed-only
        # text never becomes a reference).
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=120000)
        inp.fill("只回答两个字：again")
        with page.expect_request(
                lambda r: r.method == "POST" and r.url.endswith("/stream")) as req2:
            page.keyboard.press("Enter")
        body2 = json.loads(req2.value.post_data)
        assert "refObjects" not in body2 or not body2.get("refObjects"), \
            f"no chip means no reference on the wire, got {body2.get('refObjects')!r}"
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=120000)
        assert_no_errors(collectors, "chat composer mention chip rides stream")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)


def test_chat_page_500_message_session_windows_the_thread(page, collectors, api_client, uniq):
    """TC-AO-06 worst-case scale: a 500-turn session renders at most one window
    of rows (spacers keep the scrollbar honest), and scrolling reaches both ends.

    Ledger rows are booked directly (fixture data at the scale no live LLM run
    could produce in an E2E budget); the RENDER + history door under test is the
    real chain browser -> admin -> core -> DB."""
    pid = _seed_project(api_client, uniq, "CHATSCALE")
    # psql -tA prints the statement's command tag after RETURNING rows
    # ("149\nINSERT 0 1") — take the first line, the id itself.
    admin_id = int(sql_query("SELECT id FROM tp_user WHERE username='admin'").splitlines()[0])
    try:
        sid = int(sql_query(
            f"INSERT INTO tp_ai_chat_session (project_id,user_id,title,source) "
            f"VALUES ({pid},{admin_id},'SCALE',2) RETURNING id").splitlines()[0])
        sql_query(
            "INSERT INTO tp_ai_chat_message (session_id,role,content,status,prompt_tokens,completion_tokens) "
            f"SELECT {sid}, CASE WHEN g%2=0 THEN 'user' ELSE 'assistant' END, "
            "'消息体 ' || lpad(g::text, 3, '0'), 'done', 10, 20 FROM generate_series(1,500) g")

        inject_token(page)
        # Same pointer the kernel writes after a live first send: tp_chat_ctx_<userId>_<projectId>.
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => localStorage.setItem('tp_chat_ctx_{admin_id}_{pid}', '{sid}')")
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")

        # Newest tail on screen (follow-bottom re-pin after the history read).
        expect(page.locator('[data-test="chat-dock-body"]')).to_contain_text("消息体 500", timeout=15000)
        dom_rows = page.locator('[data-test="chat-msg"]').count()
        assert dom_rows <= 60, f"500-row session must window, DOM held {dom_rows} rows"
        assert dom_rows >= 2, "the window is not empty"
        # Scrollbar honesty: spacers stand in for the rows off-window.
        expect(page.locator('[data-test="chat-pad-top"]')).to_be_visible(timeout=5000)

        # Scroll to the head: the oldest turn loads into the window, the tail leaves it.
        page.evaluate("() => { const el = document.querySelector('[data-test=\"chat-dock-body\"]'); el.scrollTop = 0 }")
        expect(page.locator('[data-test="chat-dock-body"]')).to_contain_text("消息体 001", timeout=10000)
        expect(page.locator('[data-test="chat-msg"]').first).to_contain_text("消息体 00", timeout=10000)
        assert "消息体 500" not in page.locator('[data-test="chat-msg"]').all_inner_texts(), \
            "off-window tail rows must not stay mounted"
        assert_no_errors(collectors, "chat page 500-message windowed thread")
    finally:
        _purge_project(api_client, pid)


def test_chat_repair_draft_card_two_click_journey(page, collectors, api_client, uniq):
    """ stage 3c the repair-draft card walked through the real browser.

 A live chat run streams over SSE while the fixture parks one confirmable
 draft frame onto the SAME streaming assistant block through the core
 internal ledger door (same land_draft channel as tools/verification/l3-aichat-draft-
 confirm fixture, reused per the adjudication — a deterministic card instead
 of gambling on the LLM emitting one). The browser then walks the two-click
 door on the rendered card: confirm (ticket + countdown) -> apply (receipt
 naming case and new version), proven against the DB (script version +1,
 draft row flips to applied). A reload keeps the conversation text (sealed
 ledger), and every phase ends with the 4xx/5xx + console + Issues-panel
 zero-tolerance check. Refusal spectra live on the L3 fixture — not repeated
 here (per the note, E2E proves the honoured browser path only).
 """
    pid = _seed_project(api_client, uniq, "DRAFTE2E")
    suffix = int(uniq[:6], 16) % 100000
    case = api_client.post("/cases", json={
        "projectId": pid, "title": f"草案卡旅程{uniq}", "precondition": "已登录",
        "expectedResult": "修复落版", "priority": 2, "confidence": 3,
        "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开登录页", "isAssert": 0}],
    })
    assert case.status_code == 200 and case.json().get("code") == "0", case.text[:300]
    cdata = case.json()["data"]
    case_id = cdata["id"] if isinstance(cdata, dict) else cdata
    saved = httpx.post(f"{CORE_BASE}/internal/scripts", json={
        "caseId": case_id, "schemaVersion": "v2", "actionJson": E2E_BASE_SCRIPT,
        "generatedBy": 1}, timeout=30)
    v0 = int((saved.json().get("data") or {}).get("version") or 0)
    assert v0 == 1, f"baseline script must land as version 1, got {saved.text[:200]}"

    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        question = f"草案卡旅程探针{uniq}"
        page.locator('[data-test="chat-dock-input"]').fill(question)
        page.locator('[data-test="chat-dock-input"]').press("Enter")

        # Find the run's streaming assistant block, then park one draft frame
        # on it (seq lands mid-stream: the SSE pump replays it to the card).
        deadline = time.time() + 60
        msg_id = ""
        while time.time() < deadline and not msg_id:
            sid = sql_query(
                "SELECT id FROM tp_ai_chat_session WHERE project_id="
                f"{pid} ORDER BY id DESC LIMIT 1")
            msg_id = sql_query("SELECT id FROM tp_ai_chat_message WHERE session_id="
                               f"{sid} AND role='assistant' AND status='streaming' "
                               "ORDER BY id DESC LIMIT 1") if sid else ""
            if not msg_id:
                time.sleep(0.5)
        assert msg_id, "no streaming assistant block appeared for the live run"
        sid = sql_query("SELECT session_id FROM tp_ai_chat_message WHERE id=" + msg_id)
        meta = json.dumps({"executionId": 900000 + suffix, "caseId": int(case_id),
                           "baseVersion": v0, "schemaVersion": "v2",
                           "model": "e2e-probe-model", "actionJson": E2E_REPAIRED_SCRIPT})
        landed = httpx.post(f"{CORE_BASE}/internal/ai-chat/sessions/{sid}/frames",
                            json={"messageId": int(msg_id), "frameType": "draft",
                                  "payload": f"定位器 {uniq} 已过期", "metaJson": meta},
                            timeout=30).json()
        assert landed.get("code") == "0", landed

        card = page.locator('[data-test="chat-draft-card"]')
        expect(card).to_be_visible(timeout=30000)
        # The human line of the draft frame rides the card summary verbatim.
        expect(card.locator('[data-test="chat-draft-summary"]')).to_contain_text(uniq, timeout=10000)
        # Field diff at the ADJUDICATED granularity (chatRepairDiff.ts header:
        # top-level action-JSON keys only — a nested step-by-step fold would be
        # a second copy of the version-line step-key contract). The changed
        # steps field renders as its own row, never a raw JSON dump.
        diff_row = card.locator('[data-test="chat-draft-diff"] .draft-diff-row[data-state="changed"]')
        expect(diff_row).to_have_count(1, timeout=10000)
        expect(diff_row).to_contain_text("steps", timeout=10000)

        # Click one: the one-shot ticket opens with its countdown echo.
        card.locator('[data-test="chat-draft-confirm"]').click()
        expect(card.locator('[data-test="chat-draft-apply"]')).to_be_visible(timeout=15000)
        expect(card.locator('[data-test="chat-draft-ttl"]')).to_contain_text(
            re.compile(r"\d+"), timeout=5000)

        # Click two: apply rides the ticket; the receipt names case + version (R22).
        card.locator('[data-test="chat-draft-apply"]').click()
        receipt = card.locator('[data-test="chat-draft-receipt"]')
        expect(receipt).to_contain_text(str(case_id), timeout=15000)
        expect(receipt).to_contain_text("第 2 版", timeout=10000)

        # Three-way agreement: the ledger says the same as the screen.
        assert sql_query(f"SELECT version FROM tp_script WHERE case_id={case_id}") == "2"
        assert sql_query("SELECT status FROM tp_ai_chat_draft WHERE message_id="
                         + msg_id) == "2"

        # Reload: the conversation text survives (sealed ledger read-back).
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-dock-body"]')).to_contain_text(question, timeout=15000)
        assert_no_errors(collectors, "chat repair-draft two-click journey")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)


def test_chat_mention_chip_send_completes_the_frame_chain(page, collectors, api_client, uniq):
    pass
    pid = _seed_project(api_client, uniq, "CHIPSPEC")
    token = uniq[:6].lower()
    api_client.post("/cases", json={
        "projectId": pid, "title": f"帧链目标{token}", "precondition": "已登录",
        "expectedResult": "帧链完整", "priority": 2, "confidence": 3,
        "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开列表", "isAssert": 0}],
    })
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        inp = page.locator('[data-test="chat-dock-input"]')

        inp.click()
        inp.fill(f"@{token}")
        expect(page.locator('[data-test="chat-mention-case"]')).to_be_visible(timeout=15000)
        inp.press("Enter")
        expect(page.locator('[data-test="chat-chip-case"]')).to_be_visible(timeout=5000)

        inp.click()
        page.keyboard.type("只回答两个字：收到")
        page.keyboard.press("Enter")
        # Sealed turn (send door back = done frame landed server-side).
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)
        turn = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        expect(turn).to_contain_text(re.compile(r"\S"), timeout=10000)
        # Knife-4d frame-chain asserts: the pinned chip verified, so NO named
        # refusal rode the turn, and the answer sealed with a usage line.
        assert turn.locator('[data-test="chat-msg-error"]').count() == 0, \
            "a same-project chip must never surface a refusal line"
        expect(turn.locator(".chat-msg-usage")).to_contain_text("tokens", timeout=10000)
        assert_no_errors(collectors, "chat mention chip full-chain frame seal")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)


# R40: the ledger poll lives in chat_spectrum_support only. The shared
# implementation returns the session row the streaming block was found under
# (identical value to the former per-module re-query). This re-export keeps
# every existing call site and downstream importer on the one source.
from chat_spectrum_support import wait_streaming_message as _wait_streaming_message


# Proposal body of the create card (case-field document, same vocabulary the
# core CaseWriteDraftStrategy lands: title/precondition/expectedResult/
# priority/confidence/endType/steps).
E2E_CASE_CREATE_BODY = json.dumps({
    "title": "登录成功后进入首页冒烟验证",
    "precondition": "存在有效测试账号",
    "expectedResult": "登录后 3 秒内进入首页工作台",
    "priority": 2, "confidence": 3, "endType": "web",
    "steps": [{"stepNo": 1, "description": "打开登录页", "isAssert": 0},
              {"stepNo": 2, "description": "输入账号密码并提交", "isAssert": 0},
              {"stepNo": 3, "description": "首页工作台可见", "isAssert": 1}],
}, ensure_ascii=False)


def test_chat_dock_width_drag_memory_reset(page, collectors, api_client, uniq):
    """ stage 2 spectrum 1: the grab strip truly drives the drawer width.

 Dragging the 4px strip left grows the dock, an oversized drag stops at the
 640 ceiling (the clamp is not decoration), the value survives a reload via
 the per-user localStorage bucket, double-click returns the factory 420 and
 the focused strip steps 16px per arrow key (X3: a drag is never the only
 path). Geometry only — no LLM, the exec switch stays at its factory value.
 """
    pid = _seed_project(api_client, uniq, "DOCKW")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}", wait_until="domcontentloaded")
        fab = page.locator('[data-test="ai-chat-fab"]')
        expect(fab).to_be_visible(timeout=15000)
        fab.click()
        dock = page.locator('[data-test="ai-chat-dock"]')
        expect(dock).to_be_visible(timeout=10000)
        strip = page.locator('[data-test="chat-dock-resize"]')
        expect(strip).to_be_visible(timeout=5000)

        def drawer_width(expected):
            # The drawer animates its width; wait until the transition settles
            # INSIDE the expected tolerance (deterministic) instead of sampling
            # two consecutive frames (a mid-transition coincidence can look
            # "stable"). Timeout then reports the last measured width honestly.
            try:
                page.wait_for_function(
                    """([exp, tol]) => {
                        const d = document.querySelector('[data-test="ai-chat-dock"]');
                        return !!d && Math.abs(d.getBoundingClientRect().width - exp) <= tol;
                    }""",
                    arg=[expected, 2.0], timeout=10000)
            except Exception:
                box = dock.bounding_box()
                raise AssertionError(
                    f"dock width never settled at {expected} (last measured {box and box['width']})")
            box = dock.bounding_box()
            assert box, "the open dock must have a box"
            return box["width"]

        base = drawer_width(420)
        assert 400 <= base <= 440, f"factory width ~420 expected, got {base}"

        # Grab the strip and drag it 100px to the left: the dock grows to match.
        box = strip.bounding_box()
        mid_y = box["y"] + box["height"] / 2
        page.mouse.move(box["x"] + 2, mid_y)
        page.mouse.down()
        page.mouse.move(box["x"] + 2 - 100, mid_y, steps=5)
        page.mouse.up()
        grown = drawer_width(520)
        assert 515 <= grown <= 525, f"drag 100px left must grow 420 to ~520, got {grown}"

        # Clamp honesty: an oversized drag stops at the 640 ceiling, never past.
        box = strip.bounding_box()
        mid_y = box["y"] + box["height"] / 2
        page.mouse.move(box["x"] + 2, mid_y)
        page.mouse.down()
        page.mouse.move(box["x"] - 900, mid_y, steps=6)
        page.mouse.up()
        drawer_width(640)  # settle into the ceiling before measuring
        clamped = drawer_width(640)
        assert abs(clamped - 640) <= 2, f"width must clamp at 640, got {clamped}"

        key = page.evaluate(
            "() => Object.keys(localStorage).find(k => k.startsWith('tp_chat_dock_width:')) || null")
        assert key, "a drag must persist the width under the tp_chat_dock_width bucket"

        # Reload: the preference is read back — reopening shows the same width.
        page.reload(wait_until="domcontentloaded")
        expect(fab).to_be_visible(timeout=15000)
        fab.click()
        expect(dock).to_be_visible(timeout=10000)
        assert abs(drawer_width(640) - 640) <= 2, \
            f"reload must restore the persisted width (localStorage={page.evaluate('() => localStorage.getItem(arguments[0])', key)}), got {drawer_width(640)}"

        # Double-click the strip: factory default 420 returns.
        page.locator('[data-test="chat-dock-resize"]').dblclick()
        reset = drawer_width(420)
        assert abs(reset - 420) <= 2, f"double-click resets to 420, got {reset}"

        # Keyboard equivalence (X3): the focused strip shrinks 16px per ArrowLeft.
        page.locator('[data-test="chat-dock-resize"]').focus()
        page.keyboard.press("ArrowLeft")
        stepped = drawer_width(404)
        assert abs(stepped - 404) <= 2, f"ArrowLeft must step -16px, got {stepped}"

        assert_no_errors(collectors, "chat dock width drag memory reset")
    finally:
        _purge_project(api_client, pid)


def test_chat_dock_click_outside_keeps_the_stream_alive(page, collectors, api_client, uniq):
    """ stage 2 spectrum 2 (D2): collapse is never an interrupt.

 A long real answer streams over the full chain (browser -> admin -> core ->
 MQ -> ai-service -> LLM, zero mocks). Clicking outside the dock AND the
 bubble collapses the panel; the bubble wears the running halo while the
 turn keeps working; reopening shows the SAME thread strictly further along
 than before the collapse (nothing was destroyed, nothing re-asked) until
 the turn seals with its usage line — zero error frames, zero lost frames.
 """
    pid = _seed_project(api_client, uniq, "DOCKOUT")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}", wait_until="domcontentloaded")
        fab = page.locator('[data-test="ai-chat-fab"]')
        expect(fab).to_be_visible(timeout=15000)
        fab.click()
        dock = page.locator('[data-test="ai-chat-dock"]')
        expect(dock).to_be_visible(timeout=10000)

        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill("请逐字复述从1数到500，每个数字占一行，不要停。")
        inp.press("Enter")
        expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
        turn = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        expect(turn).to_contain_text(re.compile(r"\d"), timeout=120000)
        partial = len(turn.text_content() or "")
        assert partial > 0, "the live turn must already carry deltas before the collapse"

        # Click outside dock AND bubble (page background, left edge): collapse.
        page.mouse.click(8, 500)
        expect(dock).to_be_hidden(timeout=5000)
        expect(fab).to_be_visible(timeout=5000)
        halo = page.locator('[data-test="chat-fab-running"]')
        expect(halo).to_be_visible(timeout=5000)

        # Reopen through the bubble: the same thread, further along than before.
        fab.click()
        expect(dock).to_be_visible(timeout=10000)
        body = page.locator('[data-test="chat-dock-body"]')
        expect(body).to_contain_text("数到500", timeout=10000)
        reopened = len(turn.text_content() or "")
        assert reopened >= partial, \
            f"collapse must not rewind or rebuild the thread ({partial} -> {reopened})"

        # Seal: the stream finishes its work the same as without the collapse.
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)
        assert turn.locator('[data-test="chat-msg-error"]').count() == 0, \
            "a collapsed-then-reopened turn must never surface an error frame"
        expect(turn.locator(".chat-msg-usage")).to_contain_text("tokens", timeout=10000)
        # Sealed: the running halo leaves the bubble again (mirror, not latch).
        page.mouse.click(8, 500)
        expect(dock).to_be_hidden(timeout=5000)
        expect(page.locator('[data-test="chat-fab-running"]')).to_have_count(0, timeout=10000)
        assert_no_errors(collectors, "chat dock click outside keeps stream")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)


def test_chat_case_write_draft_card_lands_a_real_case(page, collectors, api_client, uniq):
    """ stage 4 close-out: the CASE_WRITE create card walked end to end.

 Same deterministic-card posture as the repair journey: a live chat run
 streams over SSE while one CASE_WRITE create draft frame is parked onto
 the streaming block through the core internal ledger door. The browser
 walks the two-click door (confirm ticket -> apply), the receipt names the
 NEW case id and its first version, the DB proves the case really landed
 (title + three steps + ledger target_id pinned back), and the case list
 under the header project segment shows it after reload — the dialog write
 becomes a first-class case, not a side table. Every phase ends with the
 4xx/5xx + console + Issues-panel zero-tolerance check.
 """
    pid = _seed_project(api_client, uniq, "CWDRafte2E")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _set_sys_config_value("ai.chat.case-draft.enabled", "true")
    new_case_id = ""
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        question = f"用例草案旅程探针{uniq}"
        page.locator('[data-test="chat-dock-input"]').fill(question)
        page.locator('[data-test="chat-dock-input"]').press("Enter")

        sid, msg_id = _wait_streaming_message(60, pid)
        meta = json.dumps({"executionId": 920000 + (int(uniq[:6], 16) % 10000),
                           "objectType": "CASE_WRITE", "caseId": 0, "baseVersion": 0,
                           "toolName": "case.create", "model": "e2e-probe-model",
                           "actionJson": E2E_CASE_CREATE_BODY}, ensure_ascii=False)
        landed = httpx.post(f"{CORE_BASE}/internal/ai-chat/sessions/{sid}/frames",
                            json={"messageId": msg_id, "frameType": "draft",
                                  "payload": "新建用例草案：登录成功后进入首页冒烟验证",
                                  "metaJson": meta}, timeout=30).json()
        assert landed.get("code") == "0", landed
        draft_id = sql_query(f"SELECT id FROM tp_ai_chat_draft WHERE message_id={msg_id}")
        assert draft_id, "create-proposal draft row must land before the card shows"

        card = page.locator('[data-test="chat-draft-card"]')
        expect(card).to_be_visible(timeout=30000)
        expect(card.locator('[data-test="chat-draft-summary"]')).to_contain_text("登录成功后进入首页冒烟验证")
        # Create shape = field lines + readable steps, never a raw JSON dump.
        fields = card.locator('[data-test="chat-draft-fields"]')
        expect(fields).to_be_visible(timeout=10000)
        expect(fields).to_contain_text("登录成功后进入首页冒烟验证")
        steps = card.locator('[data-test="chat-draft-steps"]')
        expect(steps).to_have_count(1, timeout=10000)
        expect(steps.locator('.draft-diff-row')).to_have_count(3, timeout=10000)

        # Click one: ticket + countdown; click two: apply lands the case.
        card.locator('[data-test="chat-draft-confirm"]').click()
        expect(card.locator('[data-test="chat-draft-apply"]')).to_be_visible(timeout=15000)
        card.locator('[data-test="chat-draft-apply"]').click()
        receipt = card.locator('[data-test="chat-draft-receipt"]')
        expect(receipt).to_contain_text("已创建用例", timeout=15000)
        expect(receipt).to_contain_text("第 1 版", timeout=10000)

        # The DB is the arbiter: the case really exists through the shared
        # save chain, three steps renumbered, ledger pinned target_id back.
        new_case_id = sql_query(
            "SELECT id FROM tp_test_case WHERE project_id="
            f"{pid} AND title='登录成功后进入首页冒烟验证' LIMIT 1")
        assert new_case_id, "confirmed create proposal must land a real tp_test_case row"
        assert "已创建用例 #" + new_case_id in (receipt.inner_text() or "")
        assert sql_query(f"SELECT count(*) FROM tp_case_step WHERE case_id={new_case_id}") == "3"
        draft_row = sql_query(f"SELECT target_id || '|' || status FROM tp_ai_chat_draft WHERE id={draft_id}")
        assert draft_row == f"{new_case_id}|2"

        # Reload, then the case list under the header project segment shows it
        # (the dialog write is a first-class case, visible where humans work).
        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        pick_project_in_header(page, pname)
        expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="case-table"]')).to_contain_text("登录成功后进入首页冒烟验证", timeout=15000)
        assert_no_errors(collectors, "chat case-write draft lands a real case")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)
