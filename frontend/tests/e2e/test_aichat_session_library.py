"""Session-library E2E (AO chat surface; the stage-3 closing package).

Two spectra, both against the real chain browser -> admin 8081 -> core 8083 -> DB:

1) Journey on the standalone page's left column (the honest user flow):
   new conversation -> one real streamed answer (server auto-title from the
   question) -> the row shows in the library -> keyword search narrows
   server-side -> rename -> reload keeps the title -> archive (the confirm
   dialog names the title and the way back) -> the active face loses it, the
   archived face finds it. The dock surface (SAME shared component, whole-body
   list view) closes the spectrum: history entry switches the body, opening an
   archived row drives the kernel back to that thread.

2) Worst-case scale (S group): 60 sessions seeded directly into the ledger
   (no live LLM run could produce them inside an E2E budget; the door under
   test is SERVER paging + component windowing, both real chain). Asserts:
   the first window never loads the whole ledger, the load-more door names
   the server-side remainder, an instant keyword search cuts the set on the
   server (the remainder text proves the server total, not a client filter),
   clearing restores it, and a group head collapse hides exactly its rows.

Iron rules: exec gate hot-enabled for the stream and restored to the factory
value; scratch project purged by the shared three-table sweep (conftest);
every phase ends with the 4xx/5xx + console + Issues-panel zero-tolerance
check. The journey file pins to one xdist worker (shared global exec switch,
same posture as test_ai_chat_page.py).
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

ARCHIVED_VIEW = "返回进行中"
GROUP_TODAY = "今天"
GROUP_EARLIER = "更早"


def _open_side_library(page, pid):
    """Land on /ai/chat with the library column mounted."""
    inject_token(page)
    page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="chat-page-side"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="chat-session-list"]')).to_be_visible(timeout=10000)


def test_session_library_journey(page, collectors, api_client, uniq):
    """New -> streamed answer auto-titles -> search/rename -> archive -> archived face finds it."""
    pid = seed_project(api_client, uniq, "SESLIB")
    token = uniq[:6].lower()
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        _open_side_library(page, pid)

        # Empty library answers honestly (nothing booked for this fresh segment).
        expect(page.locator('[data-test="session-empty"]')).to_be_visible(timeout=10000)

        # New conversation + one real streamed question: the sealed turn opens
        # the session door and the server auto-titles it from the question.
        page.locator('[data-test="session-new"]').click()
        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill(f"只回答两个字：库{token}")
        inp.press("Enter")
        expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)

        # The column books the row the moment the session door opens (the page
        # re-reads the ledger on the kernel's session id) — the fresh row sits
        # in the today bucket whose badge counts exactly one session.
        row = page.locator('[data-test="session-row"]')
        expect(row).to_have_count(1, timeout=15000)
        expect(row.first).to_contain_text(token, timeout=10000)
        today = page.locator('[data-test="session-group-head"]').first
        expect(today).to_contain_text(GROUP_TODAY)
        expect(today.locator(".session-group-count")).to_have_text("1")

        # Keyword search is a server query: the shared fragment narrows to it.
        page.locator('[data-test="session-search"]').fill(token)
        expect(row).to_have_count(1, timeout=10000)

        # Rename through the row door; the new title rides the reload.
        new_title = f"改名目标{token}"
        row.first.hover()
        page.locator('[data-test="session-row"] [aria-label="改名"]').first.click()
        rename = page.locator('[data-test="session-rename-input"]')
        expect(rename).to_be_visible(timeout=5000)
        rename.fill(new_title)
        rename.press("Enter")
        expect(row.first).to_contain_text(new_title, timeout=10000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-session-list"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="session-search"]').fill(token)
        expect(row.first).to_contain_text(new_title, timeout=15000)

        # Archive names its object and the way back (R22); the row leaves the
        # active face and the archived face (explicit status door) finds it.
        page.locator('[data-test="session-row"] [aria-label="归档"]').first.click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible(timeout=5000)
        expect(box).to_contain_text(new_title, timeout=5000)
        expect(box).to_contain_text("查看已归档", timeout=5000)
        box.locator(".el-message-box__btns button.el-button--primary").click()
        expect(page.locator('[data-test="session-empty"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="session-view-toggle"]').click()
        expect(page.locator('[data-test="session-view-toggle"]')).to_contain_text(ARCHIVED_VIEW)
        expect(page.locator('[data-test="session-row"]')).to_contain_text(new_title, timeout=15000)

        assert_no_errors(collectors, "session library journey")

        # The dock reuses the SAME component as a whole-body list view: the
        # history entry switches the body, opening the archived row drives the
        # kernel back to that thread (question text visible again).
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="ai-chat-fab"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="ai-chat-fab"]').click()
        expect(page.locator('[data-test="ai-chat-dock"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="chat-dock-history"]').click()
        dock = page.locator('[data-test="ai-chat-dock"]')
        # The dock's list view swaps the body: the conversation reader leaves,
        # the shared list piece lands. The dock mounts it with an outer
        # data-test="chat-history-view" (attribute fallthrough replaces the
        # component's own root data-test there — the page column keeps
        # chat-session-list, the dock surface reads chat-history-view).
        expect(dock.locator('[data-test="chat-dock-body"]')).to_have_count(0, timeout=10000)
        expect(dock.locator('[data-test="chat-history-view"]')).to_be_visible(timeout=10000)
        dock.locator('[data-test="session-view-toggle"]').click()
        dock_row = dock.locator('[data-test="session-row"]')
        expect(dock_row).to_contain_text(new_title, timeout=15000)
        dock_row.first.click()
        # Opening a row drives the kernel AND parks the list view (adjudicated
        # dock behavior: picking a row returns to the conversation) — the body
        # comes back loaded with that thread's turns (the question text rides
        # the sealed ledger, not the renamed title).
        expect(dock.locator('[data-test="chat-dock-body"]')).to_contain_text(f"库{token}", timeout=15000)
        assert_no_errors(collectors, "dock list view switches body")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        purge_project(api_client, pid)


def test_session_library_worst_scale_paging(page, collectors, api_client, uniq):
    """60 accumulated sessions: server pages, component windows, search cuts
    server-side, group heads collapse. No full-ledger inline render (S-1/S-2)."""
    pid = seed_project(api_client, uniq, "SESSCALE")
    token = uniq[:6].lower()
    admin_id = int(sql_query("SELECT id FROM tp_user WHERE username='admin'").splitlines()[0])
    shared = f"共词族{token}"
    try:
        # 58 shared-prefix rows staggered over ~34 days (crosses the last-7 /
        # last-30 / earlier buckets) + 2 unique rows: one fresh (today group),
        # one at 40 days (earlier group) — the two heads the spectrum needs.
        sql_query(
            "INSERT INTO tp_ai_chat_session (project_id,user_id,title,source,updated_at) "
            f"SELECT {pid},{admin_id}, '{shared} ' || lpad(g::text,2,'0'), 2, "
            "now() - (g * interval '14 hours') FROM generate_series(1,58) g")
        sql_query(
            "INSERT INTO tp_ai_chat_session (project_id,user_id,title,source,updated_at) "
            f"VALUES ({pid},{admin_id},'孤例甲' || '{token}',2,now()),"
            f"({pid},{admin_id},'孤例乙' || '{token}',2,now() - interval '40 days')")
        # The ledger door honestly excludes empty shells (a zero-
        # message session never enters the list) — every fixture row gets one
        # message so the scale under test is the PAGING, not the exclusion rule.
        sql_query(
            "INSERT INTO tp_ai_chat_message (session_id,role,content,status,prompt_tokens,completion_tokens) "
            f"SELECT s.id,'user','夹具提问','done',10,20 FROM tp_ai_chat_session s WHERE s.project_id={pid}")

        _open_side_library(page, pid)

        # First window = one server page at most, never the whole ledger.
        rows = page.locator('[data-test="session-row"]')
        expect(rows.first).to_be_visible(timeout=15000)
        assert rows.count() <= 50, f"first window must not exceed one page, DOM held {rows.count()}"
        assert rows.count() >= 2, "the window is not empty"

        # The accumulate door names the server total minus the loaded page:
        more = page.locator('[data-test="session-load-more"]')
        expect(more).to_be_visible(timeout=10000)
        expect(more).to_contain_text("还有 10 条")
        more.click()
        # Accumulated: every booked row is in the component's ledger (server
        # pages exhausted), so the door retires — DOM rows stay windowed, the
        # count of the DOM is NOT the ledger; the disappeared door is the proof.
        expect(more).to_have_count(0, timeout=15000)

        # Shared-prefix search: server resets the window to page 1 of the
        page.locator('[data-test="session-search"]').fill(shared)
        expect(more).to_be_visible(timeout=15000)
        expect(more).to_contain_text("还有 8 条")

        # Narrower search lands on the 2 unique rows: the today group (open)
        # plus the earlier group. The earlier bucket is folded BY DEFAULT (the
        # S-group storage rule), so the DOM shows only the fresh row until the
        # head is unfolded — unfolding reveals the 40-day row; folding today
        # then leaves exactly that one.
        page.locator('[data-test="session-search"]').fill("孤例")
        expect(rows).to_have_count(1, timeout=15000)
        expect(rows.first).to_contain_text("孤例甲")
        heads = page.locator('[data-test="session-group-head"]')
        expect(heads).to_have_count(2, timeout=5000)
        expect(heads.first).to_contain_text(GROUP_TODAY)
        expect(heads.nth(1)).to_contain_text(GROUP_EARLIER)
        expect(heads.nth(1)).to_have_attribute("aria-expanded", "false", timeout=5000)
        heads.nth(1).click()
        expect(heads.nth(1)).to_have_attribute("aria-expanded", "true", timeout=5000)
        expect(rows).to_have_count(2, timeout=5000)
        expect(rows.nth(1)).to_contain_text("孤例乙")
        heads.first.click()
        expect(heads.first).to_have_attribute("aria-expanded", "false", timeout=5000)
        expect(rows).to_have_count(1, timeout=5000)
        expect(rows.first).to_contain_text("孤例乙")
        heads.first.click()
        expect(rows).to_have_count(2, timeout=5000)

        # Clearing the filter restores the full accumulate view (no hidden state).
        page.locator('[data-test="session-search"]').fill("")
        expect(more).to_be_visible(timeout=15000)
        expect(more).to_contain_text("还有 10 条")

        assert_no_errors(collectors, "session library worst scale")
    finally:
        purge_project(api_client, pid)
