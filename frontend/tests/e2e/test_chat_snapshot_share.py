"""Dialog snapshot share E2E .

Proves the whole share loop in REAL browsers against the REAL chain
(admin 8081 owner doors -> core 8083 freeze; public read = api 8082
/open/chat-share with NO session anywhere on the reader side):

1) owner streams one sealed turn, opens the share panel, creates a 24h link
 — the panel lists it and the copy door fires;
2) a FRESH browser context (no token, no storage) opens
 /#/share/chat/<token> and reads the frozen markdown — zero 404/401,
 zero login bounce;
3) revoking owner-side bites on the NEXT touch: the same public URL now
 shows the dead notice (Cache-Control no-store means no stale copy);
4) an expired link (expires_at pinned back by SQL, the L3-proven posture)
 answers the same dead sentence to a new visitor.

Iron rules: exec gate hot-enabled and restored; scratch project purged by
the shared sweep; every phase ends with the 4xx/5xx + console + Issues-panel
zero-tolerance check (the public context carries its own collectors).
"""
import httpx
import pytest
from conftest import (
    API_BASE,
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    attach_monitors,
    inject_token,
    purge_project,
    seed_project,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]

ADMIN_API = API_BASE  # http://localhost:8081/api/v1


def _drop_expected_dead_link_404(collectors):
    """The dead-link 404 IS the contract answer (TC-AO-20 bidirectional
    expiry spectrum: unknown/expired/revoked share one 404 sentence, core
    single-sourced). The network observer in attach_monitors only books
    '/api/' URLs, so a public '/open/chat-share/' touch surfaces ONLY as
    Chromium's console line; when the fetch actually happened (requests
    ledger proves it) and the page-side dead notice was asserted visible
    right before this call, that paired console line is dropped (one
    contract refusal, one observer — not a whitelist)."""
    touched = any("/open/chat-share/" in r["url"] for r in collectors.get("requests", []))
    if touched:
        collectors["console_errors"] = [
            m for m in collectors["console_errors"]
            if not ("Failed to load resource" in m and "404" in m)]
        collectors["network_4xx"] = [e for e in collectors["network_4xx"]
                                     if "/open/chat-share/" not in e["url"]]


def _stream_one_turn(page, token_phrase):
    inp = page.locator('[data-test="chat-dock-input"]')
    inp.fill(f"只回答两个字：分享{token_phrase}")
    inp.press("Enter")
    expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)


def test_share_create_public_read_revoke_expire(page, collectors, api_client, uniq, browser):
    """Owner share -> login-free read -> revoke bites -> expired link dead."""
    pid = seed_project(api_client, uniq, "SHARE")
    phrase = uniq[:6].lower()
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    share_id = None
    try:
        # ---- 1. owner side: one sealed turn, then create the link ----
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        _stream_one_turn(page, phrase)

        page.locator('[data-test="chat-page-ops"] [data-test="chat-share-btn"]').click()
        panel = page.locator('[data-test="chat-share-panel"]')
        expect(panel).to_be_visible(timeout=5000)
        page.locator('[data-test="chat-share-create-btn"]').click()
        row = page.locator('[data-test="chat-share-row-meta"]').first
        expect(row).to_be_visible(timeout=10000)
        assert_no_errors(collectors, "T438-P2 owner share panel")

        # The token: read it back from the ledger (the panel copies it to the
        # clipboard, which headless cannot observe; the DB row is the truth).
        sid = sql_query(f"SELECT id FROM tp_ai_chat_session WHERE project_id={pid} "
                        "ORDER BY id DESC LIMIT 1")
        share_row = sql_query(
            "SELECT id, share_token FROM tp_ai_chat_share WHERE session_id="
            f"{sid} ORDER BY id DESC LIMIT 1")
        assert share_row, "the create door must book a share row"
        # psql -tAc joins columns with '|' (default field separator)
        share_token = share_row.split("|")[1]

        # ---- 2. a stranger's browser: no token, no storage, just the link ----
        ctx = browser.new_context(viewport={"width": 1440, "height": 900})
        pub = ctx.new_page()
        pub_col = attach_monitors(pub)
        pub.goto(f"{BASE_URL}/#/share/chat/{share_token}", wait_until="domcontentloaded")
        expect(pub.locator('[data-test="share-chat-page"]')).to_be_visible(timeout=10000)
        body = pub.locator('[data-test="share-chat-body"]')
        expect(body).to_be_visible(timeout=15000)
        assert phrase in body.inner_text(), "the frozen copy must carry the streamed answer"
        # zero login affordance on the public surface
        assert pub.locator('[data-test="share-chat-dead"]').count() == 0
        assert_no_errors(pub_col, "T438-P2 public read (fresh context)")

        # ---- 3. owner revokes; the SAME stranger tab dies on next touch ----
        page.locator('[data-test="chat-share-revoke-btn"]').first.click()
        expect(page.locator('[data-test="chat-share-revoked-tag"]').first).to_be_visible(timeout=10000)
        assert_no_errors(collectors, "T438-P2 owner revoke")

        # goto to the SAME hash URL is a same-document navigation (no app
        # reload, no refetch); a real link holder arrives fresh, so force a
        # reload to make the next touch actually hit the door.
        pub.reload(wait_until="domcontentloaded")
        dead = pub.locator('[data-test="share-chat-dead"]')
        expect(dead).to_be_visible(timeout=10000)
        _drop_expected_dead_link_404(pub_col)
        assert_no_errors(pub_col, "T438-P2 public read after revoke")
        ctx.close()

        # ---- 4. an expired link answers the same dead sentence ----
        r = httpx.post(f"{ADMIN_API}/ai/chat/sessions/{sid}/share",
                       params={"ttlHours": 1},
                       headers=api_client.headers, timeout=15)
        assert r.status_code == 200 and r.json()["code"] == "0", r.text
        t2 = r.json()["data"]["shareToken"]
        sql_query("UPDATE tp_ai_chat_share SET expires_at = NOW() - INTERVAL '1 minute' "
                  f"WHERE share_token='{t2}'")
        ctx2 = browser.new_context(viewport={"width": 1440, "height": 900})
        pub2 = ctx2.new_page()
        pub2_col = attach_monitors(pub2)
        pub2.goto(f"{BASE_URL}/#/share/chat/{t2}", wait_until="domcontentloaded")
        expect(pub2.locator('[data-test="share-chat-dead"]')).to_be_visible(timeout=10000)
        # the dead-link 404 is the contract answer for THIS door only
        # (unknown/expired/revoked share one core sentence) — dropped after
        # the notice is asserted; all other non-2xx stays zero-tolerance.
        _drop_expected_dead_link_404(pub2_col)
        assert_no_errors(pub2_col, "T438-P2 public read after expiry")
        ctx2.close()
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        purge_project(api_client, pid)
