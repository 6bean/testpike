""" stage B: executor WebSocket consumption branch (api-test-run protocol=ws).

The core assembler stamps envelope key protocol:"ws" for ws-transport requests
(03 protocol trailing-key contract, commit 37593bbc); this package is the
consumer face: branch on protocol -> websockets sync client connect/send/recv
-> judge the three ws assertion types (ws.receive / ws.order / ws.frequency,
vocabulary mirrored from common ApiWsContract, no second verdict copy).

Honesty red line (W1c fix-3 ruling): a ws request WITHOUT ws assertions is
attributed by connection reachability only (reachable=pass / handshake
refused=class 3 naming the status / unreachable=class 3) — never a fake
"assertions passed". Unknown protocol values and non-ws assertion types on the
ws face fail closed with class 3 in plain words.

No mocks on the execution path: executor/fixtures/ws_station.py is a real ws
server (module fixture, auto-selected free port, five faces echo/order/freq/
closed/reject) driven through the real api_runner.py subprocess (test_w1b
_run_worker posture). Zero-drift guard: absent protocol key or explicit
"http" keeps the legacy HTTP branch byte-identical.
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import tempfile
import time

import pytest

_EXECUTOR_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_WS_STATION = os.path.join(_EXECUTOR_DIR, "fixtures", "ws_station.py")
_SYS_TIMEOUT = 90

sys.path.insert(0, _EXECUTOR_DIR)
from runner import _API_WORKER_PATH  # noqa: E402


def _free_port() -> int:
    """Bind-then-release a port so parallel xdist workers never collide."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def ws_station():
    """Real ws fixture station (one subprocess per module, readiness-probed)."""
    port = _free_port()
    proc = subprocess.Popen([sys.executable, _WS_STATION, str(port)],
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                            cwd=_EXECUTOR_DIR)
    try:
        _wait_ready(port)
    except Exception:
        proc.kill()
        raise
    yield f"http://127.0.0.1:{port}", port
    proc.kill()


def _wait_ready(port: int) -> None:
    """Environment precondition: the station answers the echo greeting."""
    from websockets.sync.client import connect
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        try:
            with connect(f"ws://127.0.0.1:{port}/echo", open_timeout=1) as ws:
                ws.recv(timeout=1)
            return
        except OSError:
            time.sleep(0.2)
    raise RuntimeError(f"ws fixture station not ready on port {port}")


def _envelope(base: str, rows: list, *, protocol: str | None = "ws",
              url_path: str = "/echo", body=None, timeout_ms: int = 8000) -> str:
    """Minimal api-test-run envelope for the ws branch (mirrors core shape)."""
    env = {
        "kind": "api-test-run", "runId": 905,
        "method": "GET", "urlPath": url_path,
        "headers": {}, "auth": {}, "assertions": rows,
        "envBaseUrl": base,
        "execPolicy": {"allowHosts": "127.0.0.1", "denyHosts": "",
                       "timeoutMs": timeout_ms},
        "tlsPolicy": {"mode": "VERIFY"},
        "timeoutMs": timeout_ms,
    }
    if protocol is not None:
        env["protocol"] = protocol
    if body is not None:
        env["body"] = body
    return json.dumps(env, ensure_ascii=False)


def _run_worker(action_json: str) -> dict:
    """Real subprocess run of api_runner.py (task.json -> outcome.json)."""
    with tempfile.TemporaryDirectory(prefix="tp-ws-") as td:
        task_f = os.path.join(td, "task.json")
        out_f = os.path.join(td, "outcome.json")
        task = {"taskId": 906, "claimToken": "tok-ws", "caseId": 0,
                "taskType": 4, "actionJson": action_json}
        with open(task_f, "w", encoding="utf-8") as f:
            json.dump(task, f, ensure_ascii=False)
        proc = subprocess.run([sys.executable, _API_WORKER_PATH, task_f, out_f],
                              capture_output=True, text=True, timeout=_SYS_TIMEOUT,
                              check=False, cwd=_EXECUTOR_DIR)
        assert proc.returncode == 0, \
            f"worker nonzero exit: rc={proc.returncode} stderr={proc.stderr[:500]}"
        with open(out_f, "r", encoding="utf-8") as f:
            return json.load(f)


def _detail_rows(outcome: dict) -> list:
    """Per-row verdicts ride the protocol-snapshot channel (single carrier)."""
    snaps = outcome.get("protocol_snapshots") or []
    assert snaps, f"protocol snapshot missing (only verdict carrier): {outcome}"
    return snaps[0].get("assertions") or []


class TestWsReceive:
    """ws.receive: at least one message inside the window satisfies the op."""

    def test_send_then_echo_hit_pass(self, ws_station) -> None:
        base, _ = ws_station
        env = _envelope(base,
                        [{"type": "ws.receive", "op": "contains",
                          "expect": "echo:hi", "withinMs": 3000}],
                        body="hi")
        outcome = _run_worker(env)
        assert outcome["result"] == "pass", f"expected pass: {outcome}"
        rows = _detail_rows(outcome)
        assert rows[0]["type"] == "ws.receive" and rows[0]["passed"] is True

    def test_content_mismatch_fail_class1(self, ws_station) -> None:
        base, _ = ws_station
        env = _envelope(base,
                        [{"type": "ws.receive", "op": "exact",
                          "expect": "never-sent", "withinMs": 2000}],
                        body="hi")
        outcome = _run_worker(env)
        assert outcome["result"] == "fail" and outcome["error_type"] == 1, \
            f"expectation miss must be class 1: {outcome}"

    def test_illegal_op_expectation_family_class1(self, ws_station) -> None:
        base, _ = ws_station
        env = _envelope(base,
                        [{"type": "ws.receive", "op": "wildcard",
                          "expect": "x", "withinMs": 1000}],
                        body="hi")
        outcome = _run_worker(env)
        assert outcome["result"] == "fail" and outcome["error_type"] == 1, \
            f"illegal op belongs to the ExpectMatchError class-1 family: {outcome}"


class TestWsOrder:
    """ws.order: arrival sequence equals the expected prefix inside the window."""

    def test_sequence_match_pass(self, ws_station) -> None:
        base, _ = ws_station
        env = _envelope(base,
                        [{"type": "ws.order",
                          "expect": ["alpha", "beta", "gamma"],
                          "withinMs": 3000}],
                        url_path="/order")
        outcome = _run_worker(env)
        assert outcome["result"] == "pass", f"expected pass: {outcome}"

    def test_sequence_mismatch_fail_class1(self, ws_station) -> None:
        base, _ = ws_station
        env = _envelope(base,
                        [{"type": "ws.order",
                          "expect": ["beta", "alpha", "gamma"],
                          "withinMs": 3000}],
                        url_path="/order")
        outcome = _run_worker(env)
        assert outcome["result"] == "fail" and outcome["error_type"] == 1, \
            f"out-of-order arrival must be class 1: {outcome}"


class TestWsFrequency:
    """ws.frequency: message count inside the window >= expect (200ms ticker)."""

    def test_frequency_met_pass(self, ws_station) -> None:
        base, _ = ws_station
        env = _envelope(base,
                        [{"type": "ws.frequency", "expect": 5,
                          "withinMs": 1500}],
                        url_path="/freq")
        outcome = _run_worker(env)
        assert outcome["result"] == "pass", f"expected pass: {outcome}"

    def test_frequency_short_fail_class1(self, ws_station) -> None:
        base, _ = ws_station
        env = _envelope(base,
                        [{"type": "ws.frequency", "expect": 60,
                          "withinMs": 1200}],
                        url_path="/freq")
        outcome = _run_worker(env)
        assert outcome["result"] == "fail" and outcome["error_type"] == 1, \
            f"insufficient frequency must be class 1: {outcome}"


class TestWsReachabilityHonesty:
    """Three-state reachability attribution (no ws assertions = no fake verdict)."""

    def test_reachable_and_closed_with_no_rows_pass(self, ws_station) -> None:
        base, _ = ws_station
        env = _envelope(base, [], url_path="/closed")
        outcome = _run_worker(env)
        assert outcome["result"] == "pass", \
            f"reachable face must pass honestly (no asserted content): {outcome}"

    def test_handshake_refused_class3_names_status(self, ws_station) -> None:
        base, _ = ws_station
        env = _envelope(base, [], url_path="/reject")
        outcome = _run_worker(env)
        assert outcome["result"] == "fail" and outcome["error_type"] == 3, \
            f"refused handshake must be class 3: {outcome}"
        assert "404" in (outcome.get("error_detail") or "")

    def test_unreachable_service_class3(self, ws_station) -> None:
        dead = _free_port()  # bound-then-released port = refused connection
        env = _envelope(f"http://127.0.0.1:{dead}", [])
        outcome = _run_worker(env)
        assert outcome["result"] == "fail" and outcome["error_type"] == 3, \
            f"unreachable service must be class 3: {outcome}"


class TestWsFailClosedVocabulary:
    """Unknown protocol values and non-ws assertion rows fail closed class 3."""

    def test_unknown_protocol_value_class3_names_it(self, ws_station) -> None:
        base, _ = ws_station
        env = _envelope(base, [], protocol="grpc")
        outcome = _run_worker(env)
        assert outcome["result"] == "fail" and outcome["error_type"] == 3, \
            f"unknown protocol must fail closed: {outcome}"
        assert "grpc" in (outcome.get("error_detail") or "")

    def test_status_row_on_ws_face_class3(self, ws_station) -> None:
        base, _ = ws_station
        env = _envelope(base, [{"type": "status", "expect": 200}],
                        url_path="/closed")
        outcome = _run_worker(env)
        assert outcome["result"] == "fail" and outcome["error_type"] == 3, \
            f"HTTP-only assertion on the ws face must fail closed: {outcome}"

    def test_loop_on_ws_face_class3(self, ws_station) -> None:
        base, _ = ws_station
        env = json.loads(_envelope(base, [], url_path="/closed"))
        env["loop"] = {"mode": "count", "times": 3}
        outcome = _run_worker(json.dumps(env, ensure_ascii=False))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3, \
            f"loop is not supported on the ws face this slice: {outcome}"


class TestWsZeroDrift:
    """Absent protocol key / explicit http keeps the legacy HTTP branch intact."""

    def test_missing_protocol_key_stays_http_branch(self, ws_station) -> None:
        base, _ = ws_station
        # Same station port over plain HTTP: /reject answers 404; a status-404
        # assertion passing proves the envelope stayed on the HTTP path.
        env = _envelope(base, [{"type": "status", "expect": 404}],
                        protocol=None, url_path="/reject")
        outcome = _run_worker(env)
        assert outcome["result"] == "pass", f"zero drift must stay HTTP: {outcome}"
