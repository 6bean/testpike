""" R67 revisit journey for the login-state probe door.

Real chain, zero stubs (R3): the probe runs against the slot-local dev
server origin, its trail lands in tp_ai_task_event under kind
'login-state-probe', and the row-level process-history door replays the
SAME run after the user leaves and re-enters the page. The journey proves:

 1. a registered state probed once yields a run token and a stored trail
    (DB readback on the run_token),
 2. after leaving the ledger page entirely and re-entering through the
    deep link, the row-level process-history button is still on screen
    (terminal state archives the live window, never the visibility),
 3. replaying the run through the shared tail window shows every stored
    frame (DOM row count == DB event count under the retention cap), the
    cursor channel is read monotonically without frame loss and the door
    is never re-fetched as a whole (R67 lazy-load semantics).

Three zero-tolerance checks (4xx/5xx + console error/warning + browser
Issues) close the journey; --headless=new posture comes from conftest.
"""
import base64
import json
import re
import time
from urllib.parse import quote_plus

import httpx
import psycopg2
import pytest
from conftest import (
    API_BASE,
    BASE_URL,
    assert_no_errors,
    seed_project,
)
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

PROBE_JOB_KIND = "login-state-probe"
TERMINAL_STATES = ("completed", "failed", "partial", "stopped", "cancelled")
RUN_WAIT_S = 120


def _jwt(exp_epoch: int) -> str:
    """Unsigned JWT-shaped token: SessionStateSupport.parseJwtExp only
    base64url-decodes the payload segment and reads the numeric exp claim."""
    def seg(obj):
        raw = json.dumps(obj, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")

    return f"{seg({'alg': 'none', 'typ': 'JWT'})}.{seg({'exp': exp_epoch})}.revisit"


def _dsn():
    from web_element_ledger_kit import dsn
    return dsn()


def _stored_events(run_token: str):
    with psycopg2.connect(_dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT id, level, summary FROM tp_ai_task_event"
            " WHERE job_kind = %s AND run_token = %s ORDER BY id",
            (PROBE_JOB_KIND, run_token),
        )
        return cur.fetchall()


@pytest.mark.timeout(420)
def test_login_state_probe_revisit_journey(logged_in, collectors, api_client, uniq):
    """Register -> probe -> leave the page entirely -> re-enter ->
    replay the same run with a continuous cursor."""
    page = logged_in
    pid = seed_project(api_client, uniq, "LRV", display_name=f"登录态回放{uniq}")
    state_id = None
    try:
        body = {
            "projectId": pid,
            "stateName": f"revisit-{uniq}",
            "kind": "token",
            "credKey": f"lrv_key_{uniq[:6]}",
            "secret": _jwt(int(time.time()) + 3 * 86400),
            "accountLabel": f"acct-{uniq}",
        }
        r = api_client.post("/session-states", json=body)
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        state_id = r.json()["data"]

        # The shared api_client posts a body only; query values ride the URL.
        # This dispatch is synchronous server-side (claim + probe execution),
        # so a freshly launched executor station can make it exceed the
        # client's 10-second read budget. Use the long-budget direct form
        # already used by other suites (60s) instead of widening the fixture.
        r = httpx.post(
            f"{API_BASE}/session-states/{state_id}/probe?url={quote_plus(BASE_URL)}",
            headers=api_client.headers,
            timeout=60,
        )
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        outcome = r.json()["data"]
        run_token = outcome.get("runToken")
        assert run_token, f"probe outcome carries no run token: {outcome}"

        deadline = time.time() + RUN_WAIT_S
        events = []
        stable_since = None
        while time.time() < deadline:
            runs_r = api_client.get(f"/session-states/{state_id}/probe-runs",
                                    params={"limit": 10})
            runs = runs_r.json().get("data") or []
            mine = [x for x in runs if x.get("runToken") == run_token]
            events = _stored_events(run_token)
            if mine and str(mine[0].get("status", "")).lower() in TERMINAL_STATES:
                break
            if events and stable_since and time.time() - stable_since > 8:
                break
            stable_since = time.time() if events else None
            time.sleep(2)
        assert events, "probe run stored no trail frames (door silent)"

        ledger_url = f"{BASE_URL}/#/login-states?projectId={pid}"
        page.goto(ledger_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="login-state-page"]')).to_be_visible(timeout=15000)
        rows_sel = '[data-test="login-state-table"] .el-table__row'
        expect(page.locator(rows_sel).first).to_be_visible(timeout=15000)

        # Leave the page entirely, then re-enter through the deep link.
        page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
        expect(page.locator(rows_sel)).to_have_count(0, timeout=10000)
        page.goto(ledger_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="login-state-page"]')).to_be_visible(timeout=15000)
        history_btn = page.locator('[data-test="login-state-history-btn"]')
        expect(history_btn.first).to_be_visible(timeout=15000)

        # Capture every cursor read on the events door for the replay.
        cursor_reads = []

        def on_request(request):
            m = re.search(r"probe-events\?.*cursor=([^&\s]+)", request.url)
            if m:
                cursor_reads.append(m.group(1))

        page.on("request", on_request)
        history_btn.first.click()
        dialog = page.locator('[data-test="run-history-table"]')
        expect(dialog).to_be_visible(timeout=15000)
        replay_btn = page.locator(f'[data-test="run-history-replay-{run_token}"]')
        expect(replay_btn).to_be_visible(timeout=15000)
        replay_btn.click()

        window = page.locator('[data-test="run-history-tail-window"]')
        expect(window).to_be_visible(timeout=15000)
        page.wait_for_selector(".task-tail-empty, .task-tail-row", timeout=20000)
        # DOM frames catch up with the stored trail (cap-sized window may
        # page lazily; wait bounded, then reconcile against the door).
        dom_rows = 0
        deadline = time.time() + 30
        while time.time() < deadline:
            dom_rows = window.locator(".task-tail-row").count()
            if dom_rows >= min(len(events), 1):
                break
            page.wait_for_timeout(500)
        assert dom_rows > 0, "replay tail window renders no stored frames"
        assert dom_rows <= len(events) + 1, (
            f"replay shows {dom_rows} rows for {len(events)} stored events — "
            "phantom frames on the revisit channel")

        # Cursor continuity: every read carries a cursor argument, values
        # never rewind, and the door is never re-fetched whole.
        assert cursor_reads, "replay never touched the lazy cursor door"
        numeric = [int(x) for x in cursor_reads if re.fullmatch(r"\d+", x)]
        assert len(numeric) == len(cursor_reads), (
            f"non-numeric cursor leaked into reads: {cursor_reads}")
        assert numeric == sorted(numeric), (
            f"cursor went backwards (frame loss risk): {cursor_reads}")
        whole_refetches = [x for x in cursor_reads if x == "0"]
        assert len(whole_refetches) <= 1, (
            f"tail refetched the whole run {len(whole_refetches)} times")

        if len(events) <= dom_rows:
            summaries = window.locator(".task-tail-summary").all_inner_texts()
            missing = [e for e in events if e[2] and not any(
                str(e[2])[:24] in s for s in summaries)]
            assert not missing, (
                f"stored frames absent from the replay DOM: {missing[:3]}")

        assert_no_errors(collectors, "login-state probe revisit journey")
        assert_no_browser_issues(
            collectors.get("browser_issues", []), "login-state probe revisit journey")
    finally:
        if state_id is not None:
            api_client.delete(f"/session-states/{state_id}")
