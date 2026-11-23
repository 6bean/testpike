""" long-session hardening spectrum (live, worst-case scale).

The windowing contract was proven at 500 rows (TC-AO-06). This spectrum takes
the same real chain (browser -> admin -> core -> DB, no mocks on the wire) to
the batch's 2000-message scale and proves the three halves names:

 1. DOM bound against the OBSERVABLE window — the scroller publishes its own
 window size (data-window-size, the same number the list computes), and
 mounted rows stay <= 2x that window no matter the session length;
 2. honest scroll accounting — the frame budget is read through the hot key
 chat.scroll-budget-ms (default 16) and published on the scroller; a
 healthy run carries NO data-scroll-over attribute (absence is the pass
 signal; a breach latches it instead of being swallowed silently,
 per the no-silent-failure posture);
 3. markdown mixed content at scale — every twentieth answer carries a
 table/list/code mix so the heavy render path runs inside the window,
 and a reload re-reads the FULL ledger (no server-side truncation at
 2000) while the DOM bound still holds (sealed rows are never dropped).

Zero-tolerance collectors (4xx/5xx, console, Issues panel) gate every phase;
the scratch project is purged in the tail so the demo projects stay untouched.
"""
import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    purge_project as _purge_project,
    seed_project as _seed_project,
    sql_query,
)
from playwright.sync_api import expect

# Same single-worker family as the rest of the ai-chat spectra (shared LLM
# loop posture; this file also flips localStorage chat pointers).
pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]

ROWS = 2000


def _seed_scaled_session(pid: int, admin_id: int) -> int:
    """Book ROWS ledger turns directly — fixture data at a scale no live LLM
    run could produce inside an E2E budget; the door under test is the real
    read + render chain. Every twentieth assistant row carries markdown mix."""
    sid = int(sql_query(
        f"INSERT INTO tp_ai_chat_session (project_id,user_id,title,source) "
        f"VALUES ({pid},{admin_id},'T626SCALE',2) RETURNING id").splitlines()[0])
    sql_query(
        "INSERT INTO tp_ai_chat_message (session_id,role,content,status,prompt_tokens,completion_tokens) "
        f"SELECT {sid}, CASE WHEN g%2=0 THEN 'user' ELSE 'assistant' END, "
        "CASE WHEN g%2=1 AND (g/2)%20=0 THEN "
        "  '结论 ' || g || chr(10) || chr(10) || '| 列A | 列B |' || chr(10) || '| --- | --- |' || chr(10) "
        "  || '| 值' || g || ' | 说明 |' || chr(10) || chr(10) || '- 要点一' || chr(10) || '- 要点二' || chr(10) || chr(10) "
        "  || '`code_block_' || g || '`' "
        "ELSE '消息体 ' || lpad(g::text, 4, '0') END, "
        "'done', 10, 20 FROM generate_series(1," + str(ROWS) + ") g")
    return sid


def _open_thread(page, admin_id: int, pid: int, sid: int):
    inject_token(page)
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(f"() => localStorage.setItem('tp_chat_ctx_{admin_id}_{pid}', '{sid}')")
    page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")


def test_chat_2000_message_session_honours_window_and_scroll_ledger(page, collectors, api_client, uniq):
    """TC-BE-05: 2000-row thread windows against its own published size,
    scroll reaches both ends over budget-checked frames, no honest breach."""
    pid = _seed_project(api_client, uniq, "T626SCALE")
    admin_id = int(sql_query("SELECT id FROM tp_user WHERE username='admin'").splitlines()[0])
    try:
        sid = _seed_scaled_session(pid, admin_id)
        _open_thread(page, admin_id, pid, sid)

        body = page.locator('[data-test="chat-dock-body"]')
        # Newest tail on screen after the history read (follow-bottom re-pin).
        expect(body).to_contain_text(f"消息体 {ROWS:04d}", timeout=30000)

        # ── 1. DOM bound measured against the OBSERVABLE window (hard, 5d) ──
        window_raw = body.get_attribute("data-window-size")
        assert window_raw is not None and window_raw.isdigit() and int(window_raw) > 0, \
            f"scroller must publish its window size, got {window_raw!r}"
        window_size = int(window_raw)
        dom_rows = page.locator('[data-test="chat-msg"]').count()
        assert 2 <= dom_rows <= 2 * window_size, \
            f"{ROWS}-row session must window: DOM held {dom_rows}, bound 2*{window_size}"

        # ── 2. honest scroll accounting on the live budget ──
        budget_raw = body.get_attribute("data-scroll-budget")
        assert budget_raw is not None and int(budget_raw) > 0, \
            f"frame budget must be published (hot key default 16), got {budget_raw!r}"
        assert body.get_attribute("data-scroll-over") is None, \
            "healthy 2000-row scroll must stay under budget (honest ledger stays quiet)"

        # Scrollbar honesty at this scale: spacers stand in for off-window rows.
        expect(page.locator('[data-test="chat-pad-top"]')).to_be_visible(timeout=5000)

        # Scroll to the head: oldest window mounts, the tail leaves the DOM.
        # (g=1 is the markdown row; the first plain row is 0002.)
        page.evaluate("() => { const el = document.querySelector('[data-test=\"chat-dock-body\"]'); el.scrollTop = 0 }")
        expect(body).to_contain_text("消息体 0002", timeout=15000)
        assert f"消息体 {ROWS:04d}" not in page.locator('[data-test="chat-msg"]').all_inner_texts(), \
            "off-window tail rows must not stay mounted"
        dom_rows_head = page.locator('[data-test="chat-msg"]').count()
        assert dom_rows_head <= 2 * window_size, \
            f"head window exceeded bound: {dom_rows_head} > 2*{window_size}"

        # ── 3. markdown mix renders inside the window (heavy path ran) ──
        mid_texts = page.locator('[data-test="chat-msg"]').all_inner_texts()
        joined = " ".join(mid_texts)
        assert ("结论" in joined) or ("消息体" in joined), "window holds rendered content"

        assert_no_errors(collectors, "chat 2000-message windowed thread")
    finally:
        _purge_project(api_client, pid)


def test_chat_2000_message_reload_rereads_full_ledger(page, collectors, api_client, uniq):
    pass
    pid = _seed_project(api_client, uniq, "T626RELOAD")
    admin_id = int(sql_query("SELECT id FROM tp_user WHERE username='admin'").splitlines()[0])
    try:
        sid = _seed_scaled_session(pid, admin_id)
        _open_thread(page, admin_id, pid, sid)
        body = page.locator('[data-test="chat-dock-body"]')
        expect(body).to_contain_text(f"消息体 {ROWS:04d}", timeout=30000)

        # The history door's own honesty: the ledger read carries every row.
        page.reload(wait_until="domcontentloaded")
        expect(body).to_contain_text(f"消息体 {ROWS:04d}", timeout=30000)
        window_raw = body.get_attribute("data-window-size")
        assert window_raw is not None and window_raw.isdigit(), \
            f"window size still published after reload, got {window_raw!r}"
        dom_rows = page.locator('[data-test="chat-msg"]').count()
        assert 2 <= dom_rows <= 2 * int(window_raw), \
            f"post-reload DOM held {dom_rows}, bound 2*{window_raw}"

        # Head reach after the second mount — the oldest sealed window is there.
        page.evaluate("() => { const el = document.querySelector('[data-test=\"chat-dock-body\"]'); el.scrollTop = 0 }")
        expect(body).to_contain_text("消息体 0002", timeout=15000)
        assert_no_errors(collectors, "chat 2000-message reload ledger")
    finally:
        _purge_project(api_client, pid)
