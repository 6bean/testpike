"""Dialog thread structure E2E .

Proves the message-structure contract in a REAL browser against the REAL chain
(browser -> admin 8081 -> core 8083 -> RabbitMQ -> ai-service -> real LLM):

1) one streamed turn renders the structural trio: a day divider on the first
 turn of the day, a locale role tag on every turn (question/answer words),
 and the two sides differ geometrically (question capsule sits right of the
 answer card — measured from live bounding boxes, not classes);
2) the clock label rides the ledger instant, not the wall clock: both turns'
 created_at are pinned to a past timestamp through the ledger, a reload then
 must show that pinned date in the divider and that pinned time in the
 labels — the Date.now fallback would honestly show "today" and prove the
 core->admin->ai-service->FE createdAt chain broken.

Iron rules: exec gate hot-enabled and restored; scratch project purged by the
shared sweep; every phase ends with the 4xx/5xx + console + Issues-panel
zero-tolerance check. Pinned to the shared exec-switch worker group like the
rest of the AI-chat family.
"""
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

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

# Pinned ledger instant (TIMESTAMP without tz -> Java LocalDateTime -> ISO ->
# browser-local parse keeps the same wall time; no timezone drift by design).
PAST_DAY = "2026-10-20"
PAST_DATE_SQL = f"{PAST_DAY} 08:00:00"
PAST_ANSWER_SQL = f"{PAST_DAY} 08:05:00"


def test_chat_thread_dividers_role_tags_and_sides_survive_the_ledger(page, collectors, api_client, uniq):
    """Stream one turn -> structural trio -> pin ledger timestamps -> reload keeps them."""
    pid = seed_project(api_client, uniq, "THREAD")
    token = uniq[:6].lower()
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill(f"只回答两个字：结构{token}")
        inp.press("Enter")
        expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)

        rows = page.locator('[data-test="chat-msg"]')
        expect(rows).to_have_count(2, timeout=10000)
        asked = rows.nth(0)
        answered = rows.nth(1)

        # 1a) Day divider: exactly one, on the FIRST turn of the day only.
        dividers = page.locator('[data-test="chat-day-divider"]')
        expect(dividers).to_have_count(1, timeout=10000)
        # Fresh live turn: the call-site stamp is "now", so today's bucket word.
        expect(dividers.first).to_contain_text("今天", timeout=5000)

        # 1b) Locale role tags on every turn (question/answer words).
        expect(asked.locator('[data-test="chat-msg-role"]')).to_have_text("问")
        expect(answered.locator('[data-test="chat-msg-role"]')).to_have_text("答")

        # 1c) Sides differ geometrically: the question capsule sits right of the
        # answer card (measured rects; class names alone would pass on a broken CSS).
        ask_box = asked.locator(".chat-msg-main").bounding_box()
        ans_box = answered.locator(".chat-msg-main").bounding_box()
        assert ask_box and ans_box, "bubble main boxes must be measurable"
        assert ask_box["x"] > ans_box["x"], (
            f"question capsule must sit right of the answer card "
            f"(asked x={ask_box['x']}, answered x={ans_box['x']})"
        )
        assert asked.get_attribute("data-side") == "user"
        assert answered.get_attribute("data-side") == "assistant"

        # 1d) Live clock label renders (2-digit hour:minute).
        expect(asked.locator('[data-test="chat-msg-time"]')).to_contain_text(
            re.compile(r"\d{2}:\d{2}"), timeout=5000)

        assert_no_errors(collectors, "thread structure live turn")

        # 2) Pin the ledger instants to a past day, reload, and require the
        # divider + clock labels to READ FROM THE LEDGER. A createdAt chain
        # broken anywhere (core/admin/ai-service/FE) would fall back to
        # Date.now() and honestly show today's date instead.
        sid = sql_query(f"SELECT id FROM tp_ai_chat_session WHERE project_id={pid} LIMIT 1")
        assert sid, "the streamed turn must have opened a session row"
        sql_query(
            f"UPDATE tp_ai_chat_message SET created_at='{PAST_DATE_SQL}' "
            f"WHERE session_id={sid} AND role='user'; "
            f"UPDATE tp_ai_chat_message SET created_at='{PAST_ANSWER_SQL}' "
            f"WHERE session_id={sid} AND role='assistant';"
        )

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-dock-body"]')).to_be_visible(timeout=15000)
        rows = page.locator('[data-test="chat-msg"]')
        expect(rows).to_have_count(2, timeout=10000)

        dividers = page.locator('[data-test="chat-day-divider"]')
        expect(dividers).to_have_count(1, timeout=10000)
        # The divider must show a calendar date built from the pinned instant
        # (zh renders month/day followed by the weekday, en "Tue, 10/20" — any
        # locale's numeric date form matches). A broken createdAt chain falls
        # back to Date.now() and would honestly show the today bucket word.
        divider_text = (dividers.first.inner_text() or "").strip()
        assert "今天" not in divider_text, f"divider fell back to now: {divider_text!r}"
        assert re.search(r"\d{1,2}([/月.-])\d{1,2}", divider_text), (
            f"divider must render the pinned calendar date, got {divider_text!r}"
        )

        asked = rows.nth(0)
        answered = rows.nth(1)
        expect(asked.locator('[data-test="chat-msg-time"]')).to_contain_text("08:00", timeout=5000)
        expect(answered.locator('[data-test="chat-msg-time"]')).to_contain_text("08:05", timeout=5000)

        # The reloaded thread keeps the same structural contract.
        expect(asked.locator('[data-test="chat-msg-role"]')).to_have_text("问")
        expect(answered.locator('[data-test="chat-msg-role"]')).to_have_text("答")
        ask_box = asked.locator(".chat-msg-main").bounding_box()
        ans_box = answered.locator(".chat-msg-main").bounding_box()
        assert ask_box["x"] > ans_box["x"], "sides must keep differing after the ledger rebuild"

        assert_no_errors(collectors, "thread structure reload from ledger")

        # 3) Yesterday bucket branch : the DAY_MS branch had
        # no fixture instance until now — pin the user turn to yesterday 08:00
        # and require the divider to read the locale yesterday word (the same
        # session-library single source), proving age === DAY_MS is reachable.
        import datetime
        yesterday = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
        sql_query(
            f"UPDATE tp_ai_chat_message SET created_at='{yesterday} 08:00:00' "
            f"WHERE session_id={sid} AND role='user';"
        )
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-dock-body"]')).to_be_visible(timeout=15000)
        rows = page.locator('[data-test="chat-msg"]')
        expect(rows).to_have_count(2, timeout=10000)
        # Two calendar days on the ledger -> two dividers, first one yesterday's
        # bucket word, the second one the spaced "date weekday" wording (never
        # glued digits-to-weekday without a gap).
        dividers = page.locator('[data-test="chat-day-divider"]')
        expect(dividers).to_have_count(2, timeout=10000)
        expect(dividers.nth(0)).to_contain_text("昨天", timeout=5000)
        past_text = (dividers.nth(1).inner_text() or "").strip()
        assert re.search(r"\d{1,2}([/月.-])\d{1,2}\s+\S", past_text), (
            f"past-day divider must space the numeric date from the weekday word, got {past_text!r}"
        )

        assert_no_errors(collectors, "thread structure yesterday bucket")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        purge_project(api_client, pid)
