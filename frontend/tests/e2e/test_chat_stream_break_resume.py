"""SSE stream-break + cursor resume E2E .

Proves the IT-AP-04 reconnect contract in a REAL browser on the REAL chain
(browser -> admin 8081 -> RabbitMQ -> ai-service -> LLM provider), the
full-chain shape the L3 door script (l3-aichat-stream-dialog.py, 21/21)
cannot show: what the dock DOES when the transport dies under a live answer.

1) a stream torn by a REAL server restart leaves the UI honest: the admin
 JVM is SIGTERMed while its SSE is live and re-exec'd from the same
 /proc-captured command line + environment (offline emulation was ruled
 ineffective for an established localhost SSE in — the stream kept
 flowing; and an ai-service tear cannot reach the browser either: admin's
 pump polls the ledger, so ai-service silence becomes a self-injected
 error frame = designed terminal, no door). The run itself keeps
 journalling through core/ai-service — admin only views it — so the dock
 offers exactly one resume door (chat-dock-resume) instead of silently
 re-streaming, and the break never opens a second fresh /stream (counted
 on the network, not read from the UI);
2) the resume re-POSTs /stream carrying resumeCursor >= 1 (Last-Event-ID
 semantics in the body — the api/aiChat.ts contract) and the turn then
 settles normally;
3) no loss / no duplication: the pre-break prefix stays a PREFIX of the
 final answer (cursor replay continues the turn, never re-streams it from
 zero), and after a reload the ledger-folded text equals the live text —
 frames journaled server-side during the break were replayed in, the
 journal (not the DOM) stays the single source;
4) zero-tolerance is kept for PRODUCT defects throughout: entries raised
 inside the deliberate server-tear window are asserted to be ONLY
 connection-loss artefacts (enumerated below — a scenario caused by the
 test itself, then removed from the collectors so later phases keep
 auditing against a clean baseline); any other console/network/Issues
 entry in that window fails the run.

Iron rules: exec gate hot-enabled and restored; worst-window first-frame
budget pinned then restored; the shared dev admin service is re-started on
mid-test failure by the finally guard (never left down); scratch project
purged by the shared sweep; hard assertions only (rule 5d, no conditional
guards). Service credentials stay inside the /proc-captured env dict —
never printed, never inlined.
Pinned to the shared exec-switch worker group like the rest of the AI-chat
family.
"""
import httpx
import json
import os
import re
import shlex
import signal
import subprocess
import time
from pathlib import Path

import pytest

import conftest as e2e_conftest
from conftest import (
    _set_sys_config_value,
    assert_no_errors,
    inject_token,
    purge_project,
    seed_project,
    sql_query,
)
from playwright.sync_api import expect
from tp_origin_forwarder import OriginForwarder

pytestmark = [pytest.mark.timeout(900), pytest.mark.xdist_group("ai-chat-exec-switch")]

# Console artefacts Chromium legitimately prints when the server transport
# dies under a live stream. Anything else in the tear window = product bug.
_BREAK_ARTIFACTS = re.compile(
    r"ERR_INTERNET_DISCONNECTED|ERR_NAME_NOT_RESOLVED|ERR_NETWORK_CHANGED|"
    r"ERR_PROXY_CONNECTION_FAILED|ERR_CONNECTION_RESET|ERR_EMPTY_RESPONSE|"
    r"ERR_HTTP2_PROTOCOL_ERROR|Failed to fetch|net::",
    re.I,
)

_ADMIN_PORT = 8081
_LIVE_LOG = "/tmp/testpike-tick-live.log"
# (cmdline, env, cwd) of the admin JVM captured at tear time: the finally
# guard can bring the shared service back even when the test failed midway.
_ADMIN_SPEC: list = []


def _admin_pid():
    """PID of the JVM listening on the admin port, None when the port is free."""
    out = subprocess.run(["ss", "-ltnp"], capture_output=True, text=True,
                         check=False).stdout
    for line in out.splitlines():
        if f":{_ADMIN_PORT} " in line:
            m = re.search(r"pid=(\d+)", line)
            if m:
                return int(m.group(1))
    return None


def _start_admin():
    """Re-exec the captured command line in a TRANSIENT systemd unit, not as
    a child of this process, sourcing the project .env inside the unit.

    E2E3 hard lesson: a Popen child stays in the cgroup of the systemd-run
    unit that hosts this pytest — when the test process exits, systemd
    (KillMode=control-group) reaps the re-exec'd admin JVM too, silently
    leaving the SHARED dev service down (observed 18:11, admin health 000).
    Handing the JVM to the user manager puts it in its own cgroup where the
    test unit's death cannot reach it.

    E2E6 hard lesson: a transient unit does NOT inherit the caller's env —
    the env= dict only reaches the systemd-run client, so the re-exec'd JVM
    booted without SSO_JWT_SECRET and died on its fail-fast config check
    (observed 18:29, admin health never returned). Credentials must therefore
    come from the project .env sourced INSIDE the unit command: never in
    argv, never printed, never inlined (AGENTS red line 4).
    """
    if not _ADMIN_SPEC:
        pytest.fail("no captured admin service spec to restart from")
    cmdline, env, cwd = _ADMIN_SPEC[0]
    env = dict(env)
    env.setdefault("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")
    env.setdefault("DBUS_SESSION_BUS_ADDRESS",
                   f"unix:path=/run/user/{os.getuid()}/bus")
    env_file = Path(__file__).resolve().parents[3] / ".env"
    shell = ("cd " + shlex.quote(cwd) + " && set -a && . "
             + shlex.quote(str(env_file)) + " && set +a && exec "
             + " ".join(shlex.quote(c) for c in cmdline))
    unit = f"tp-e2e-admin-reexec-{int(time.time() * 1000)}"
    log = open(_LIVE_LOG, "a")
    subprocess.run(["systemd-run", "--user", "--collect", f"--unit={unit}",
                    "bash", "-c", shell],
                   env=env, check=True, stdout=log, stderr=log)


def _restart_admin_jvm():
    """KILL the admin JVM serving the live SSE and re-exec the exact command
    line + environment captured from /proc beforehand.

    E2E3 evidence: SIGTERM let Spring GRACEFUL-SHUTDOWN complete the in-flight
    pump through the controller's pumpDied catch, which self-injects an error
    frame before closing (AiChatStreamController:96-100) — a DESIGNED terminal
    the dock honestly does not open a door for (same lesson as tear6). The
    contract under test is an abrupt transport death (crash/kill -9), which
    tears the socket without any frame and must land on the interrupted path.
    """
    pid = _admin_pid()
    assert pid, "admin port 8081 has no listener before the tear"
    raw_cmd = open(f"/proc/{pid}/cmdline", "rb").read().split(b"\0")
    cmdline = [c.decode("utf-8", "surrogateescape") for c in raw_cmd if c]
    env = {}
    for entry in open(f"/proc/{pid}/environ", "rb").read().split(b"\0"):
        if entry and b"=" in entry:
            k, _, v = entry.partition(b"=")
            env[k.decode()] = v.decode("utf-8", "surrogateescape")
    cwd = os.readlink(f"/proc/{pid}/cwd")
    _ADMIN_SPEC.clear()
    _ADMIN_SPEC.append((cmdline, env, cwd))
    os.kill(pid, signal.SIGKILL)
    deadline = time.time() + 60
    while time.time() < deadline and _admin_pid():
        time.sleep(0.5)
    assert _admin_pid() is None, "admin JVM did not release port 8081 within 60s"
    _start_admin()


def _wait_admin_up(timeout_s: int = 180):
    """Poll the admin health door directly (out of band of the browser) until
    the re-exec'd JVM serves 200 again — the resume POST must hit a live
    server, not a cold start."""
    deadline = time.time() + timeout_s
    last = None
    while time.time() < deadline:
        try:
            r = httpx.get(f"http://localhost:{_ADMIN_PORT}/actuator/health",
                          timeout=3)
            if r.status_code == 200:
                return
            last = r.status_code
        except Exception as exc:  # connection refused while booting
            last = type(exc).__name__
        time.sleep(2)
    pytest.fail(f"admin did not come back within {timeout_s}s (last={last})")


def _normalize(text: str) -> str:
    # Comparison shape = plain answer text. Inline markdown emphasis markers
    # are stripped because an answer torn MID-EMPHASIS shows the literal `**`
    # on screen while the completed render swallows those characters into
    # bold spans — the continuation contract is about the text the reader
    # keeps, never about formatting punctuation (evidence: a torn
    text = re.sub(r"[*_`]+", "", text or "")
    return re.sub(r"\s+", " ", text).strip()


def _md_body(page, nth_last: int = 1):
    """The markdown body of the Nth-last message row — the answer text
    WITHOUT row chrome (avatar label, the collapsed-process toggle row and
    the usage/citation footer all live outside chat-msg-md; comparing whole
    rows against a body snapshot could never match since the process area
    got its default-collapsed summary line)."""
    return page.locator('[data-test="chat-msg"]').nth(-nth_last).locator(
        '[data-test="chat-msg-md"]')


def test_mid_answer_reload_restores_resume_door_and_finishes_without_duplicates(
    page, collectors, api_client, uniq
):
    """Live answer -> admin JVM torn mid-stream -> the READER reloads the page
    -> the active-turn door re-raises the resume entry -> one click finishes
    the turn -> nothing lost, nothing duplicated.

    This is the reload-resume spectrum (unsealed-turn door): before this seam
    existed, a reload in the middle of an answer showed neither the running
    row nor a door — the sealed-history door by design leaves the streaming
    turn out (the ledger fold defect behind production complaint ⑥/⑩). The
    probe now proves the full user loop: GET active-message after reload
    raises chat-dock-resume with an empty live row; clicking re-POSTs
    /stream with cursor 0 = replay-from-ledger (the fresh page acknowledged
    nothing, so the server re-streams the whole journaled turn into the row
    the door booked); the torn prefix must re-appear as a PREFIX of the
    replayed text (continuation semantics survive the reload), the thread
    must settle, and a second reload must fold the SAME text from the ledger
    with exactly one done assistant row — proving no loss and no duplication
    across the whole tear-reload-resume cycle.

    Deliberate-tear-window audit identical to the first scenario (only
    connection-loss artefacts tolerated, everything else = product defect).
    """
    pid = seed_project(api_client, uniq, "RLOAD")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _set_sys_config_value("ai.chat.first-frame-budget-seconds", "30")
    forwarder = OriginForwarder()
    base = forwarder.start()
    original_base = e2e_conftest.BASE_URL
    e2e_conftest.BASE_URL = base
    try:
        inject_token(page)
        page.goto(f"{base}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        events = {"stream": 0}
        stream_bodies: list[str] = []

        def on_request(req):
            # Survives page.reload(): request listeners are bound to the page,
            # not to a document — a second POST counted here can ONLY come
            # from the click on the door.
            if req.method == "POST" and req.url.endswith("/stream") \
                    and "/ai/chat/sessions/" in req.url:
                events["stream"] += 1
                try:
                    stream_bodies.append(req.post_data or "")
                except Exception:
                    stream_bodies.append("")

        page.on("request", on_request)

        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill("请用编号逐条列举至少二十五条接口回归测试要点，每条一到两句话，全部列完再收尾")
        inp.press("Enter")

        answer = page.locator('[data-test="chat-msg"][data-state="running"]').locator(
            '[data-test="chat-msg-md"]')
        expect(answer).to_have_count(1, timeout=30000)
        expect(answer.locator(".md-empty")).to_have_count(0, timeout=120000)
        expect(answer).to_contain_text(re.compile(r"\S"), timeout=5000)
        assert events["stream"] == 1, f"one question = one stream, got {events['stream']}"
        assert_no_errors(collectors, "stream is live before the tear (reload spectrum)")

        torn_prefix = _normalize(answer.inner_text())
        assert len(torn_prefix) > 0, "pre-tear prefix must be non-empty"

        n4, n5 = len(collectors["network_4xx"]), len(collectors["network_errors"])
        nc = len(collectors["console_errors"])
        _restart_admin_jvm()
        _wait_admin_up()

        # THE scenario under test: the reader reloads mid-answer. Hard reload
        # assertion (rule 5d) — before the unsealed-turn door this sequence
        # showed neither the running row nor the entry, and the spectrum
        # silently passed. No stream may auto-open from the reload itself.
        # page.reload() is mandatory here: a same-URL page.goto() on a hash
        # route is a SAME-DOCUMENT navigation that never reloads the JS state
        # (probe: a window marker survived the goto, the app never
        # re-read history and the tear-time failure card masqueraded as the
        # reload view — the reload seam was silently untested).
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        resume = page.locator('[data-test="chat-dock-resume"]')
        expect(resume).to_have_count(1, timeout=30000)
        expect(resume).to_be_visible(timeout=10000)
        assert events["stream"] == 1, \
            (f"the reload may only RAISE the door, never auto-open a stream "
             f"(running must stay false after reload), got {events['stream']}")
        # The door books the live row the stream path writes into — exactly
        # one running-state message, no fake body before replay lands.
        expect(page.locator('[data-test="chat-msg"][data-state="running"]')
               ).to_have_count(1, timeout=10000)

        # Audit the deliberate-tear window narrowly, then clean the baseline.
        for msg in collectors["console_errors"][nc:]:
            assert _BREAK_ARTIFACTS.search(msg), \
                f"non-artifact console error inside the tear window: {msg}"
        assert not collectors["network_4xx"][n4:], \
            f"real 4xx during the tear window: {collectors['network_4xx'][n4:]}"
        assert not collectors["network_errors"][n5:], \
            f"real 5xx during the tear window: {collectors['network_errors'][n5:]}"
        del collectors["console_errors"][nc:]

        # One click on the door resumes the turn.
        resume.click()
        assert events["stream"] == 2, \
            f"the door click must re-open the stream exactly once, got {events['stream']}"
        try:
            cursor = json.loads(stream_bodies[1]).get("resumeCursor")
        except (ValueError, AttributeError):
            cursor = None
        # Fresh page after reload acknowledged nothing -> cursor 0 = replay
        # the WHOLE journaled turn from the ledger (int-typed and >= 0 keeps
        # the numeric contract; the ledger replay is what restores content).
        assert isinstance(cursor, int) and cursor >= 0, \
            f"resume carries a numeric resumeCursor, got {cursor!r}"

        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=300000)
        final_text = _normalize(_md_body(page).inner_text())
        assert final_text.startswith(torn_prefix), \
            ("the ledger replay after reload must CONTINUE the torn answer: "
             f"pre-tear prefix diverges from the replayed text\n"
             f"prefix[:80]={torn_prefix[:80]!r}\nfinal[:80]={final_text[:80]!r}")
        assert len(final_text) >= len(torn_prefix), \
            (f"replay lost content: live final {len(final_text)} "
             f"< pre-tear {len(torn_prefix)}")
        assert_no_errors(collectors, "reload-resume replay settled")

        # Second reload: the ledger is the single source — same text, one row.
        # True reload (same lesson as above: goto would be a same-document
        # no-op and the ledger fold would never actually be re-read).
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        reloaded_text = _normalize(_md_body(page).inner_text())
        assert reloaded_text == final_text, \
            (f"reload from the ledger must fold the SAME answer "
             f"(len live={len(final_text)} len reload={len(reloaded_text)})")
        done = int(sql_query(
            "SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
            "(SELECT id FROM tp_ai_chat_session WHERE project_id="
            f"{pid} ORDER BY id DESC LIMIT 1) AND role='assistant' AND status='done'"))
        assert done == 1, \
            f"the torn-and-reloaded turn must journal exactly one done answer, got {done}"
        assert_no_errors(collectors, "ledger reload after reload-resume")
    finally:
        e2e_conftest.BASE_URL = original_base
        forwarder.stop()
        purge_project(api_client, pid)
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _set_sys_config_value("ai.chat.first-frame-budget-seconds", "5")
        if _admin_pid() is None and _ADMIN_SPEC:
            _start_admin()


def test_stream_break_then_cursor_resume_replays_without_duplicates(
    page, collectors, api_client, uniq
):
    """Live answer -> admin JVM torn mid-stream -> resume door -> cursor
    replay -> prefix intact -> reload folds the same text from the ledger."""
    pid = seed_project(api_client, uniq, "RESUME")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    # Same scenario discipline as the steering spectrum: pin the pump's
    # first-frame budget to its in-range ceiling so the transport is provably
    # OPEN (a real stream exists to tear) instead of racing a cold provider.
    _set_sys_config_value("ai.chat.first-frame-budget-seconds", "30")
    # Transport shape (verdict): the browser faces the stack
    # through the same-origin forwarder, NOT the vite dev proxy — measured
    # that the dev proxy never propagates an upstream SIGKILL downstream
    # (direct origin died in 0.04s, through-proxy hung 300s), so through it
    # the resume door is physically unobservable. The forwarder tears the
    # browser socket the instant upstream EOF/reset lands (production
    # reverse-proxy semantics) — that is the condition under test.
    # inject_token follows automatically: it reads conftest.BASE_URL at call
    # time, so the token is injected on the SAME origin the pages use.
    forwarder = OriginForwarder()
    base = forwarder.start()
    original_base = e2e_conftest.BASE_URL
    e2e_conftest.BASE_URL = base
    try:
        inject_token(page)
        page.goto(f"{base}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        events = {"stream": 0}
        stream_bodies: list[str] = []

        def on_request(req):
            if req.method == "POST" and req.url.endswith("/stream") \
                    and "/ai/chat/sessions/" in req.url:
                events["stream"] += 1
                try:
                    stream_bodies.append(req.post_data or "")
                except Exception:  # body not retrievable for odd transports
                    stream_bodies.append("")

        page.on("request", on_request)

        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill("请用编号逐条列举至少二十五条接口回归测试要点，每条一到两句话，全部列完再收尾")
        inp.press("Enter")

        # Contract precondition: only tear a stream whose first real delta
        # already reached the browser (the .md-empty placeholder must be gone
        # — same lesson as the steering v5/v6 reds).
        answer = page.locator('[data-test="chat-msg"][data-state="running"]').locator(
            '[data-test="chat-msg-md"]')
        expect(answer).to_have_count(1, timeout=30000)
        expect(answer.locator(".md-empty")).to_have_count(0, timeout=120000)
        expect(answer).to_contain_text(re.compile(r"\S"), timeout=5000)
        assert events["stream"] == 1, f"one question = one stream, got {events['stream']}"
        assert_no_errors(collectors, "stream is live before the break")

        prefix = _normalize(answer.inner_text())
        assert len(prefix) > 0, "pre-break prefix must be non-empty"

        # Snapshot collector baselines so the tear window is audited narrowly,
        # then the transport is torn AT THE SERVER: the admin JVM serving this
        # SSE is SIGTERMed and re-exec'd; the in-flight run keeps journalling
        # through core/ai-service untouched.
        n4, n5 = len(collectors["network_4xx"]), len(collectors["network_errors"])
        nc = len(collectors["console_errors"])
        _restart_admin_jvm()
        _wait_admin_up()

        # The UI must fall back to the honest resume door — one, visible,
        # and NO silent second stream opened meanwhile.
        resume = page.locator('[data-test="chat-dock-resume"]')
        expect(resume).to_have_count(1, timeout=60000)
        expect(resume).to_be_visible(timeout=10000)
        assert events["stream"] == 1, \
            f"a torn stream must never auto-open a second stream, got {events['stream']}"

        # Audit the deliberate-tear window: only connection-loss artefacts are
        # tolerated; anything else is a product defect and fails hard.
        for msg in collectors["console_errors"][nc:]:
            assert _BREAK_ARTIFACTS.search(msg), \
                f"non-artifact console error inside the tear window: {msg}"
        assert not collectors["network_4xx"][n4:], \
            f"real 4xx during the tear window: {collectors['network_4xx'][n4:]}"
        assert not collectors["network_errors"][n5:], \
            f"real 5xx during the tear window: {collectors['network_errors'][n5:]}"
        # Drop the scenario-induced artefacts so every later assert_no_errors
        # re-audits against a clean baseline (product errors were just zeroed).
        del collectors["console_errors"][nc:]

        resume.click()
        # The counter is the authority for "resume re-opened the stream": the
        # stop button is NOT asserted here — a fast run can settle between the
        # click and the UI tick, and that race is not the contract (the
        # settle below is asserted by its disappearance with a hard floor).
        assert events["stream"] == 2, \
            f"resume must re-open the stream exactly once, got {events['stream']}"
        resumed_body = stream_bodies[1]
        try:
            cursor = json.loads(resumed_body).get("resumeCursor")
        except (ValueError, AttributeError):
            cursor = None
        assert isinstance(cursor, int) and cursor >= 1, \
            f"resume carries a cursor >= 1 in the body (Last-Event-ID semantics), got {cursor!r}"

        # Settle: the replayed turn closes normally.
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=300000)
        final_text = _normalize(_md_body(page).inner_text())
        assert final_text.startswith(prefix), \
            ("cursor replay must CONTINUE the answer, not re-stream it: "
             f"pre-break prefix diverges from the final text\nprefix[:80]={prefix[:80]!r}\n"
             f"final[:80]={final_text[:80]!r}")
        assert len(final_text) >= len(prefix), \
            f"replay lost content: live final {len(final_text)} < pre-break {len(prefix)}"
        assert_no_errors(collectors, "resume replay settled")

        # Reload: the ledger is the single source — the folded thread must
        # equal the live text (frames journaled during the break came back).
        # True reload: a same-URL goto stays inside the same document, which
        # would let this fold assertion pass against the untouched live DOM.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
        reloaded_text = _normalize(_md_body(page).inner_text())
        assert reloaded_text == final_text, \
            ("reload from the ledger must fold the SAME answer "
             f"(len live={len(final_text)} len reload={len(reloaded_text)}); "
             "lost/duplicated frames during the break would split here")
        done = int(sql_query(
            "SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
            "(SELECT id FROM tp_ai_chat_session WHERE project_id="
            f"{pid} ORDER BY id DESC LIMIT 1) AND role='assistant' AND status='done'"))
        assert done == 1, f"the torn-and-resumed turn must journal exactly one done answer, got {done}"
        assert_no_errors(collectors, "ledger reload after resume")
    finally:
        e2e_conftest.BASE_URL = original_base
        forwarder.stop()
        purge_project(api_client, pid)
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _set_sys_config_value("ai.chat.first-frame-budget-seconds", "5")
        # Never leave the shared dev admin down on a mid-test failure.
        if _admin_pid() is None and _ADMIN_SPEC:
            _start_admin()
