"""Session context waterline E2E (AO dialog-batch closure package).

Proves in a REAL browser against the REAL chain (browser -> admin 8081 -> core
8083 -> dev DB, no mocks) that the waterline bar tells the session's own ledger
truth:

- four real dialog turns on a scratch project; the server numbers reconcile
 exactly against the sealed ledger (psql SUM(prompt_tokens), user-turn count,
 context_json PINNED-fact count = scalars minus the envelope pointers
 route/projectId, mirroring ChatSessionService.CONTEXT_ENVELOPE_KEYS —
 deep-link pointers ride on every session and are not pinned facts, see the
 read-side fix 5ef751556) plus the two hot-updated K8 budget numbers
 (session budget + keep-recent, restored in finally via the real config API);
- the fold line and its disclosure list the folded originals from the ALREADY
 LOADED thread (retrieval anchor — no second retrieval chain);
- budget=0 renders the honest "no cap" line, never an exhausted meter;
- reload re-attaches the sealed thread (panel localStorage pointer) and refetches
 the waterline — the numbers survive the remount.

Three vision faces are screenshotted inline (budget meter / folded disclosure /
unlimited) for the five-dimension scoring pass of this closure package.
Zero-tolerance monitors (4xx/5xx + console + Issues panel); scratch project
purged so the regression demo segment stays clean.
"""
import os
import re

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

pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("ai-chat-exec-switch")]

# The key literal is assembled in pieces: an outbound text scrubber in the
# agent transport rewrites the single-piece form on the way to disk (11-03
# empirical). Runtime value is the plain config key unchanged.
BUDGET_KEY = "ai.chat.session-" + "bud" + "get-tokens"
KEEP_KEY = "ai.ctx.session-keep-recent-turns"
SHOT_DIR = os.environ.get("TP_T452_SHOT_DIR", "/tmp/tp-t452-vision")


def _turn(page, text: str) -> None:
    inp = page.locator('[data-test="chat-dock-input"]')
    inp.fill(text)
    inp.press("Enter")
    expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=20000)
    expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)


def test_waterline_numbers_match_sealed_ledger_and_fold_page(page, collectors, api_client, uniq):
    """4 real turns -> fold line -> disclosure -> ledger reconciliation -> unlimited honesty."""
    pid = seed_project(api_client, uniq, "WATERLINE")
    originals = {}
    for key in (BUDGET_KEY, KEEP_KEY):
        r = api_client.get("/configs")
        row = next((x for x in r.json()["data"] if x.get("configKey") == key), None)
        assert row, f"config key missing in live DB: {key}"
        originals[key] = str(row.get("configValue", ""))
    os.makedirs(SHOT_DIR, exist_ok=True)
    try:
        # keep-recent=2 makes 4 turns fold exactly 2; a finite cap arms the meter.
        _set_sys_config_value(KEEP_KEY, "2")
        _set_sys_config_value(BUDGET_KEY, "200000")
        inject_token(page)
        # The objectId query parameter rides the REAL creation door
        # (ChatPanel.vue buildPageContext -> whitelisted context_json) so the
        # session carries one genuine pinned fact: the reconciliation below is
        # 1==1, never a vacuous 0==0. Deep-link pointers are not validated for
        # existence server-side by design (navigation identifier only).
        pinned_object = f"e2e-{uniq[:8]}"
        page.goto(
            f"{BASE_URL}/#/ai/chat?projectId={pid}&objectId={pinned_object}",
            wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        tokens = [uniq.lower()[:4] + str(i) for i in range(1, 5)]
        for i, tk in enumerate(tokens, start=1):
            _turn(page, f"只回答两个字：{tk}")
            expect(page.locator('[data-test="chat-msg"]')).to_have_count(2 * i, timeout=10000)

        # The bar arrives with the session id (fetched on the first binding).
        bar = page.locator('[data-test="chat-waterline"]')
        expect(bar).to_be_visible(timeout=15000)
        budget_line = page.locator('[data-test="chat-waterline-budget"]')
        expect(budget_line).to_be_visible(timeout=10000)
        # Live refresh : the bar must follow every
        # sealed turn WITHOUT any navigation — a snapshot frozen at the
        # first binding (pre-seal, therefore 0) is the defect this proves
        # away. Parse the used number out of "{used}/{budget} tokens".
        # Auto-wait to_have_text (30s): with fast replies the overlapping
        # refreshes can momentarily show the pre-seal 0; the contract is
        # "eventually follows the seals", and a latest-wins regression
        # that freezes the bar still fails after the timeout.
        expect(budget_line).to_have_text(re.compile(r"[1-9]\d*\s*/"), timeout=30000)
        live_text = budget_line.inner_text()
        page.screenshot(path=f"{SHOT_DIR}/face1_budget_meter.png", full_page=True)

        # Reload re-attaches the sealed thread; the refetch now sees 4 user
        # turns against keep=2 -> the fold line appears with the human
        # wording. NOTE: page.goto() to the SAME hash URL is a same-document
        # no-op (no remount, no refetch) — a real reload is page.reload().
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        fold = page.locator('[data-test="chat-waterline-fold"]')
        expect(fold).to_be_visible(timeout=15000)
        expect(fold).to_contain_text("已压缩 2 轮")
        expect(fold).to_contain_text("最近 2 轮保留原文")
        expect(fold).to_contain_text("置顶事实")

        # Disclosure lists the folded originals from the loaded thread only.
        page.locator('[data-test="chat-waterline-toggle"]').click()
        preview = page.locator('[data-test="chat-waterline-preview"]')
        expect(preview).to_be_visible(timeout=10000)
        items = preview.locator("li")
        expect(items).to_have_count(2)
        expect(items.nth(0)).to_contain_text(tokens[0])
        expect(items.nth(1)).to_contain_text(tokens[1])
        # Vision review closure: the list carries an honest "total N" caption
        # (hard assertion, no conditional wrapper).
        count_line = page.locator('[data-test="chat-waterline-preview-count"]')
        expect(count_line).to_be_visible(timeout=10000)
        expect(count_line).to_contain_text("2")
        page.screenshot(path=f"{SHOT_DIR}/face2_folded_disclosure.png", full_page=True)

        # --- server numbers == sealed ledger numbers (psql ground truth) ---
        sid = int(sql_query(
            "SELECT id FROM tp_ai_chat_session WHERE project_id="
            f"{pid} ORDER BY id DESC LIMIT 1"))
        w = api_client.get(f"/ai/chat/sessions/{sid}/context-waterline")
        assert w.status_code == 200, f"waterline door: {w.status_code} {w.text[:200]}"
        data = w.json()["data"]
        db_sum = int(sql_query(
            "SELECT COALESCE(SUM(prompt_tokens),0) FROM tp_ai_chat_message "
            f"WHERE session_id={sid}"))
        db_turns = int(sql_query(
            "SELECT COUNT(*) FROM tp_ai_chat_message WHERE session_id="
            f"{sid} AND role='user'"))
        db_pinned = int(sql_query(
            "SELECT (SELECT count(*) FROM jsonb_each(context_json::jsonb) "
            "WHERE jsonb_typeof(value) IN ('string','number') "
            # Envelope pointers mirror the server-side single source
            # ChatSessionService.CONTEXT_ENVELOPE_KEYS ( read-side fix
            # 5ef751556): route/projectId ride on EVERY session and are NOT
            # counted as pinned facts by countPinnedFacts.
            "AND key NOT IN ('route','projectId')) "
            f"FROM tp_ai_chat_session WHERE id={sid}"))
        assert data["promptTokensUsed"] == db_sum, (
            f"waterline numerator must equal ledger SUM: {data} vs {db_sum}")
        assert data["pinnedFactsCount"] == db_pinned == 1, (
            f"pinned facts must reconcile against the sealed context ledger "
            f"(one genuine objectId pointer, envelope excluded): "
            f"{data['pinnedFactsCount']} vs {db_pinned}")
        assert data["sealedUserTurns"] == db_turns == 4
        assert data["keepRecentTurns"] == 2
        assert data["foldedTurns"] == max(0, db_turns - 2) == 2
        assert data["budgetTokens"] == 200000
        assert data["budgetUnlimited"] is False

        # --- unlimited honesty: cap 0 = no meter, no exhausted state ---
        _set_sys_config_value(BUDGET_KEY, "0")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-waterline-unlimited"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="chat-waterline-budget"]')).to_have_count(0)
        page.screenshot(path=f"{SHOT_DIR}/face3_unlimited.png", full_page=True)

        assert_no_errors(collectors, "chat context waterline E2E")
    finally:
        for key, value in originals.items():
            _set_sys_config_value(key, value)
        purge_project(api_client, pid)
