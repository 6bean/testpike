"""Keep-alive journey for the login-state ledger: enroll, expiry stop, replay.

Real chain, zero stubs (R3): enabling a row runs the synchronous double
GET pre-check against the slot fixture station door /api/protected -
the material round answers 200 with a fresh HMAC token, the anonymous
round answers 401, so the row enrolls. A login-exempt address (the
frontend origin, public to both rounds) is refused with nothing half-
written. Later the scheduler round replays the row material against the
same door; once the short-lived token has expired the round lands the
EXPIRED double stop: the verified light flips through the shared door
and the per-row switch parks itself with a stop reason naming the
remedy entry points. Per the ruling a plain round writes no event
frames - only transitions do - and the stop frame stays replayable
through the shared process-history base after the user leaves and
re-enters the page (R67 revisit).

Steps touching the row outside the API are limited to the keep-alive
pacing anchor (last_keepalive_at) - a seed-data time column, never a
service mock. Three zero-tolerance checks (4xx/5xx + console error/
warning + browser Issues) close the journey; --headless=new posture
comes from conftest.
"""
import base64
import hashlib
import hmac
import json
import os
import re
import time

import httpx
import psycopg2
import pytest
from run_replay_tail_support import (
    open_replay_tail_window,
    wait_for_rendered_rows,
)
from conftest import (
    API_BASE,
    BASE_URL,
    assert_no_errors,
    seed_project,
    slot_port_offset,
)
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

KEEPALIVE_JOB_KIND = "login-state-keepalive"
TOKEN_TTL_S = 40
STOP_WAIT_S = 220


def _fixture_origin() -> str:
    """Slot-aware origin of executor/serve_fixtures.py (8899 + slot offset)."""
    return f"http://127.0.0.1:{8899 + slot_port_offset()}"


def _sign_jwt(exp_epoch: int) -> str:
    """HS256 token the fixture station accepts: same secret derivation as
    executor/serve_fixtures.py so the door answers 200 while unexpired and
    401 'token expired' once the exp claim passes."""
    secret = os.environ.get("FIXTURE_LOGIN_JWT_SECRET", "fixture-insecure-secret")

    def seg(obj):
        raw = json.dumps(obj, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")

    header = seg({"alg": "HS256", "typ": "JWT"})
    payload = seg({"sub": "ka-journey", "iat": int(time.time()), "exp": exp_epoch})
    signing = f"{header}.{payload}".encode()
    sig = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), signing, hashlib.sha256).digest()
    ).decode().rstrip("=")
    return f"{header}.{payload}.{sig}"


def _dsn():
    from web_element_ledger_kit import dsn
    return dsn()


def _row(id_value: int):
    with psycopg2.connect(_dsn()) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT keepalive_enabled, keepalive_url, last_keepalive_verdict,"
            " keepalive_stop_reason, last_keepalive_at, status"
            " FROM tp_login_state WHERE id = %s",
            (id_value,),
        )
        cols = [d.name for d in cur.description]
        one = cur.fetchone()
        return dict(zip(cols, one)) if one else None


def _enable(headers: dict, id_value: int, url: str):
    return httpx.patch(
        f"{API_BASE}/session-states/{id_value}/keepalive",
        headers=headers, json={"enabled": True, "url": url, "intervalMinutes": None},
        timeout=60,
    )


@pytest.mark.timeout(420)
def test_login_state_keepalive_journey(logged_in, collectors, api_client, uniq):
    """Refusal -> enable pre-check -> scheduled round -> EXPIRED double
    stop -> per-row replay with a continuous cursor."""
    page = logged_in
    headers = api_client.headers
    origin = _fixture_origin()
    protected = f"{origin}/api/protected"

    # The door must protect before anything rides on it: anonymous 401.
    try:
        anon = httpx.get(protected, timeout=10)
    except httpx.ConnectError as exc:
        raise AssertionError(
            "fixture station unreachable - start it with the window recipe "
            "(tools/ops/run_login_state_keepalive_e2e_journey_window.sh owns "
            "tp-ka-fixture-slot*) before running this journey"
        ) from exc
    assert anon.status_code == 401, f"door must answer 401 anonymously, got {anon.status_code}"

    pid = seed_project(api_client, uniq, "KAJ", display_name=f"登录态保活{uniq}")
    # Clear stale fixture rows left by earlier aborted window runs so the
    # UI leg reads only this run's ledger rows (owned name prefix only).
    with psycopg2.connect(_dsn()) as conn, conn.cursor() as cur:
        cur.execute("DELETE FROM tp_login_state WHERE state_name LIKE 'kaj-%'")
    main_state = None
    try:
        # --- refusal spectrum: a login-exempt address never enrolls, nothing half-writes.
        body = {
            "projectId": pid,
            "stateName": f"kaj-public-{uniq}",
            "kind": "token",
            "credKey": f"kaj_pub_{uniq[:6]}",
            "secret": _sign_jwt(int(time.time()) + TOKEN_TTL_S),
            "accountLabel": f"kaj-pub-{uniq}",
        }
        r = api_client.post("/session-states", json=body)
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        public_state = r.json()["data"]
        # The enable pre-check is issued by the backend, not the browser: the
        # preview server binds the IPv4 loopback, so hand the probe an
        # explicit 127.0.0.1 origin instead of a hostname that may resolve v6.
        r = _enable(headers, public_state, BASE_URL.replace("localhost", "127.0.0.1"))
        # Business refusals ride the framework envelope: HTTP 400 with a
        # non-zero code and the remedy text in `msg` (GlobalExceptionHandler).
        assert r.status_code == 400 and r.json().get("code") != "0", (
            f"login-exempt address must be refused: {r.text[:300]}")
        assert "不校验登录态" in r.json().get("msg", ""), r.text[:300]
        row = _row(public_state)
        assert row["keepalive_enabled"] in (0, None), (
            f"refusal must not half-write the switch: {row}")
        api_client.delete(f"/session-states/{public_state}")

        # --- enable spectrum: the protected door passes the double pre-check.
        exp_epoch = int(time.time()) + TOKEN_TTL_S
        body["stateName"] = f"kaj-main-{uniq}"
        body["credKey"] = f"kaj_main_{uniq[:6]}"
        body["secret"] = _sign_jwt(exp_epoch)
        body["accountLabel"] = f"kaj-{uniq}"
        r = api_client.post("/session-states", json=body)
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        main_state = r.json()["data"]

        r = _enable(headers, main_state, protected)
        assert r.status_code == 200 and r.json().get("code") == "0", (
            f"protected door must pass the double pre-check: {r.text[:300]}")
        row = _row(main_state)
        assert row["keepalive_enabled"] == 1 and row["last_keepalive_verdict"] == "VALID", row
        assert row["keepalive_stop_reason"] is None, row
        status_after_enable = row["status"]

        # Transition-only ledger (ruling D8): the enable frame exists and no
        # plain round has been fired yet.
        runs = api_client.get(f"/session-states/{main_state}/keepalive-runs",
                              params={"limit": 20}).json().get("data") or []
        assert len(runs) == 1, f"enable must land exactly one transition run: {runs}"

        # --- wait for the material to expire, then make the row due by
        # moving ONLY the pacing anchor (seed column, service untouched).
        # The ledger's own expires_at is pushed far into the future at the
        # same moment: the stored material is dead (idle timeout - exactly
        # the scenario keep-alive exists for) while the row metadata still
        # claims validity, so the absolute-expiry zero-outbound skip (the
        # ruling's section 2.2) cannot silently mask this double-stop leg.
        deadline_expire = exp_epoch + 3
        while time.time() < deadline_expire:
            time.sleep(2)
        with psycopg2.connect(_dsn()) as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE tp_login_state SET last_keepalive_at = now() - interval '15 min', "
                "expires_at = now() + interval '1 day' WHERE id = %s", (main_state,))

        parked = None
        deadline = time.time() + STOP_WAIT_S
        while time.time() < deadline:
            parked = _row(main_state)
            if parked and parked["keepalive_enabled"] == 0:
                break
            time.sleep(5)
        assert parked and parked["keepalive_enabled"] == 0, (
            f"scheduled round never parked the row within {STOP_WAIT_S}s: {parked}")
        assert parked["last_keepalive_verdict"] == "EXPIRED", parked
        reason = parked["keepalive_stop_reason"] or ""
        assert "重新采集" in reason and "编辑" in reason and "保活" in reason, (
            f"stop reason must name the remedy entry points (R63): {reason}")
        assert parked["status"] != status_after_enable, (
            f"EXPIRED must flip the verified light through the shared door: {parked}")

        # --- absolute-expiry negative spectrum (ruling section 2.2): once
        # expires_at has passed, the selection predicate skips the row with
        # zero outbound and zero frames even when the pacing anchor is stale.
        body["stateName"] = f"kaj-abs-{uniq}"
        body["credKey"] = f"kaj_abs_{uniq[:6]}"
        body["secret"] = _sign_jwt(int(time.time()) + TOKEN_TTL_S)
        body["accountLabel"] = f"kaj-abs-{uniq}"
        r = api_client.post("/session-states", json=body)
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        abs_state = r.json()["data"]
        try:
            r = _enable(headers, abs_state, protected)
            assert r.status_code == 200 and r.json().get("code") == "0", (
                f"fresh material must pass the pre-check before the skip leg: {r.text[:300]}")
            with psycopg2.connect(_dsn()) as conn, conn.cursor() as cur:
                cur.execute(
                    "UPDATE tp_login_state SET expires_at = now() - interval '1 min', "
                    "last_keepalive_at = now() - interval '15 min' WHERE id = %s",
                    (abs_state,))
            before = _row(abs_state)
            time.sleep(95)  # spans at least one full scheduler tick window
            after = _row(abs_state)
            assert before and after, "skip-leg row vanished before the pacing check"
            assert after["keepalive_enabled"] == 1, (
                f"absolute expiry skips rounds, it never parks the switch: {after}")
            assert after["last_keepalive_at"] == before["last_keepalive_at"], (
                f"round stamped an absolutely-expired row (zero outbound broken): "
                f"{before} -> {after}")
            runs_abs = api_client.get(f"/session-states/{abs_state}/keepalive-runs",
                                      params={"limit": 20}).json().get("data") or []
            assert len(runs_abs) == 1, (
                f"skipped rounds must write no frames: {runs_abs}")
        finally:
            api_client.delete(f"/session-states/{abs_state}")

        # --- frames: enable + stop transitions, nothing from plain rounds.
        runs = api_client.get(f"/session-states/{main_state}/keepalive-runs",
                              params={"limit": 20}).json().get("data") or []
        assert len(runs) == 2, f"expected exactly two transition runs: {runs}"
        stop_run = runs[0]
        stop_token = stop_run.get("runToken")
        assert stop_token and stop_run.get("eventCount", 0) >= 1, stop_run
        frames = api_client.get(
            f"/session-states/{main_state}/keepalive-events",
            params={"runToken": stop_token, "cursor": 0}).json().get("data") or []
        assert frames and "熔断" in json.dumps(frames, ensure_ascii=False), frames

        # --- UI: stopped cell, reopen affordance, EXPIRED verdict tag.
        ledger_url = f"{BASE_URL}/#/login-states?projectId={pid}"
        page.goto(ledger_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="login-state-page"]')).to_be_visible(timeout=15000)
        rows_sel = '[data-test="login-state-table"] .el-table__row'
        expect(page.locator(rows_sel).first).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="login-state-keepalive-stop"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="login-state-keepalive-reopen"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="login-state-keepalive-verdict"]')).to_contain_text(
            "失效", timeout=15000)

        # --- R67 revisit: leave the page entirely, re-enter, replay the stop
        # run through the shared process-history base with a monotonic cursor.
        page.goto(f"{BASE_URL}/#/projects", wait_until="domcontentloaded")
        expect(page.locator(rows_sel)).to_have_count(0, timeout=10000)
        page.goto(ledger_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="login-state-page"]')).to_be_visible(timeout=15000)

        cursor_reads = []

        def on_request(request):
            m = re.search(r"keepalive-events\?.*cursor=([^&\s]+)", request.url)
            if m:
                cursor_reads.append(m.group(1))

        page.on("request", on_request)
        page.locator('[data-test="login-state-keepalive-btn"]').first.click()
        dialog = page.locator('[data-test="login-state-keepalive-dialog"]')
        expect(dialog).to_be_visible(timeout=15000)
        # A stopped row keeps its read door and loses only the write door: the
        # notice names the remedy path while Save stays honestly disabled.
        expect(page.locator('[data-test="login-state-keepalive-locked"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="login-state-keepalive-save"]')).to_be_disabled()
        page.locator('[data-test="login-state-keepalive-history"]').click()
        history = page.locator('[data-test="run-history-table"]')
        expect(history).to_be_visible(timeout=15000)
        window = open_replay_tail_window(page, stop_token)
        dom_rows = wait_for_rendered_rows(page, window, len(frames))
        assert dom_rows == len(frames), (
            f"replay shows {dom_rows} DOM rows for {len(frames)} stored frames")
        assert cursor_reads, "replay never touched the lazy cursor door"
        numeric = [int(x) for x in cursor_reads if re.fullmatch(r"\d+", x)]
        assert len(numeric) == len(cursor_reads), f"non-numeric cursor leaked: {cursor_reads}"
        assert numeric == sorted(numeric), f"cursor went backwards: {cursor_reads}"

        assert_no_errors(collectors, "login-state keep-alive journey")
        assert_no_browser_issues(
            collectors.get("browser_issues", []), "login-state keep-alive journey")
    finally:
        if main_state is not None:
            try:
                httpx.patch(
                    f"{API_BASE}/session-states/{main_state}/keepalive",
                    headers=api_client.headers,
                    json={"enabled": False, "url": None, "intervalMinutes": None},
                    timeout=30,
                )
            finally:
                api_client.delete(f"/session-states/{main_state}")
