""" step 3: executor consumption of the orchestration envelope keys.

Contract anchors (docs/03, api-test-run envelope, trailing keys):
- an unsatisfied envelope precondition answers result=manual_skip (the honest
 third state — core records the run as status 4; the executor never sends the
 request and never fakes a judgement); a status precondition reads the pool
 key last_status, and a missing seed is undecidable = honest skip naming the
 absent previous-hop status;
- an envelope loop row drives the repeat policy: mode=count runs times hops,
 mode=until stops on its condition guarded by maxTimes, delayMs pauses
 between iterations; every answered hop writes the response status back into
 pool key last_status (returned via extracted_vars so the collection pool
 feeds the next hop's precondition);
- a legacy envelope (neither key present) stays a single-shot run with zero
 behavior drift (the whole w1b suite must stay green).

Real-run discipline: a real socket stub (hit-counter / flaky endpoints) plus
a real api_runner.py subprocess (w1b pattern) — the execution plane is never
mocked.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
import time as _time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from runner import _API_WORKER_PATH

_EXECUTOR_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_SYS_TIMEOUT = 60

_server: ThreadingHTTPServer | None = None
_base: str = ""
_lock = threading.Lock()
_hits = {"count": 0, "flaky": 0}


class _Handler(BaseHTTPRequestHandler):
    """Counter stub: /hits answers 200 with a running total; /flaky flips 500->200."""

    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self._route()

    def do_POST(self):
        self._route()

    def _route(self):
        path = self.path.partition("?")[0]
        if path == "/hits":
            with _lock:
                _hits["count"] += 1
                n = _hits["count"]
            self._send(200, {"hits": n})
        elif path == "/flaky":
            with _lock:
                _hits["flaky"] += 1
                n = _hits["flaky"]
            self._send(200 if n >= 2 else 500, {"attempt": n})
        elif path == "/ok":
            self._send(200, {"data": {"id": 42}})
        else:
            self._send(404, {"error": "no-route"})

    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def setup_module() -> None:
    global _server, _base
    _server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=_server.serve_forever, daemon=True).start()
    _base = f"http://127.0.0.1:{_server.server_address[1]}"


def teardown_module() -> None:
    if _server:
        _server.shutdown()
        _server.server_close()


def _reset_hits() -> None:
    with _lock:
        _hits["count"] = 0
        _hits["flaky"] = 0


def _envelope(*, url_path: str = "/ok", precondition: dict | None = None,
              loop: dict | None = None, vars_seed: dict | None = None,
              assertions: list | None = None) -> str:
    env = {"kind": "api-test-run", "runId": 7701, "method": "GET",
           "urlPath": url_path, "headers": {},
           "assertions": assertions if assertions is not None
           else [{"type": "status", "expect": 200}],
           "envBaseUrl": _base,
           "execPolicy": {"allowHosts": "127.0.0.1", "denyHosts": ""},
           "timeoutMs": 10000}
    if precondition is not None:
        env["precondition"] = precondition
    if loop is not None:
        env["loop"] = loop
    if vars_seed:
        env["vars"] = dict(vars_seed)
    return json.dumps(env, ensure_ascii=False)


def _run_worker(action_json: str) -> dict:
    with tempfile.TemporaryDirectory(prefix="tp-t414-") as td:
        task_f = os.path.join(td, "task.json")
        out_f = os.path.join(td, "outcome.json")
        task = {"taskId": 904, "claimToken": "tok-t414", "caseId": 0,
                "taskType": 4, "actionJson": action_json}
        with open(task_f, "w", encoding="utf-8") as f:
            json.dump(task, f, ensure_ascii=False)
        proc = subprocess.run([sys.executable, _API_WORKER_PATH, task_f, out_f],
                              capture_output=True, text=True, timeout=_SYS_TIMEOUT,
                              check=False, cwd=_EXECUTOR_DIR)
        assert proc.returncode == 0, \
            f"worker exited non-zero: rc={proc.returncode} stderr={proc.stderr[:500]}"
        with open(out_f, "r", encoding="utf-8") as f:
            return json.load(f)


class TestPreconditionSkip:
    """Precondition tri-state: met runs, unmet skips honestly, no seed skips."""

    def test_status_precondition_met_runs(self):
        outcome = _run_worker(_envelope(url_path="/ok", vars_seed={"last_status": "200"},
                                        precondition={"field": "status", "op": "eq",
                                                      "expect": 200}))
        assert outcome["result"] == "pass", outcome.get("error_detail")

    def test_status_precondition_unmet_skips(self):
        outcome = _run_worker(_envelope(url_path="/ok", vars_seed={"last_status": "500"},
                                        precondition={"field": "status", "op": "eq",
                                                      "expect": 200}))
        assert outcome["result"] == "manual_skip", outcome
        assert "前置条件" in (outcome.get("error_detail") or "")

    def test_status_precondition_without_seed_honest_skip(self):
        # first hop without a last_status seed: undecidable = honest skip
        outcome = _run_worker(_envelope(url_path="/ok",
                                        precondition={"field": "status", "op": "in",
                                                      "expect": [200, 201]}))
        assert outcome["result"] == "manual_skip", outcome
        assert "无上一跳状态" in (outcome.get("error_detail") or "")

    def test_var_exists_absent(self):
        hit = _run_worker(_envelope(url_path="/ok", vars_seed={"token": "abc"},
                                    precondition={"field": "var", "name": "token",
                                                  "op": "exists"}))
        assert hit["result"] == "pass", hit.get("error_detail")
        miss = _run_worker(_envelope(url_path="/ok",
                                     precondition={"field": "var", "name": "token",
                                                   "op": "exists"}))
        assert miss["result"] == "manual_skip"
        absent = _run_worker(_envelope(url_path="/ok",
                                       precondition={"field": "var", "name": "token",
                                                     "op": "absent"}))
        assert absent["result"] == "pass", absent.get("error_detail")

    def test_var_eq_and_matches(self):
        eq = _run_worker(_envelope(url_path="/ok", vars_seed={"state": "ready"},
                                   precondition={"field": "var", "name": "state",
                                                 "op": "eq", "expect": "ready"}))
        assert eq["result"] == "pass", eq.get("error_detail")
        ne = _run_worker(_envelope(url_path="/ok", vars_seed={"state": "ready"},
                                   precondition={"field": "var", "name": "state",
                                                 "op": "eq", "expect": "pending"}))
        assert ne["result"] == "manual_skip"
        rx = _run_worker(_envelope(url_path="/ok", vars_seed={"token": "tk-77"},
                                   precondition={"field": "var", "name": "token",
                                                 "op": "matches", "matchType": "regex",
                                                 "expect": r"^tk-\d+$"}))
        assert rx["result"] == "pass", rx.get("error_detail")

    def test_no_precondition_key_zero_drift(self):
        # legacy envelope shape (no orchestration keys) = single shot, zero drift
        outcome = _run_worker(_envelope(url_path="/ok"))
        assert outcome["result"] == "pass", outcome.get("error_detail")
        assert "protocol_snapshots" in outcome


class TestLastStatusPool:
    """Each answered hop publishes its status into pool key last_status."""

    def test_last_status_extracted(self):
        outcome = _run_worker(_envelope(url_path="/ok"))
        vars_back = outcome.get("extracted_vars") or {}
        assert str(vars_back.get("last_status")) == "200", vars_back


class TestLoopReplay:
    """Loop replay: count / until with maxTimes guard / delayMs pacing."""

    def test_count_replays_times(self):
        _reset_hits()
        outcome = _run_worker(_envelope(url_path="/hits",
                                        loop={"mode": "count", "times": 3},
                                        assertions=[{"type": "status", "expect": 200}]))
        assert outcome["result"] == "pass", outcome.get("error_detail")
        assert _hits["count"] == 3, _hits

    def test_until_stops_when_satisfied(self):
        _reset_hits()
        outcome = _run_worker(_envelope(url_path="/flaky",
                                        loop={"mode": "until", "maxTimes": 5,
                                              "until": {"field": "status", "op": "eq",
                                                        "expect": 200}},
                                        assertions=[{"type": "status", "expect": 200}]))
        assert _hits["flaky"] == 2, "replay must stop once the condition holds"
        assert outcome["result"] == "pass", outcome.get("error_detail")

    def test_until_guard_exhausts_max_times(self):
        _reset_hits()
        # /hits always 200 while until expects 418: the maxTimes guard bounds the loop
        outcome = _run_worker(_envelope(url_path="/hits",
                                        loop={"mode": "until", "maxTimes": 2,
                                              "until": {"field": "status", "op": "eq",
                                                        "expect": 418}},
                                        assertions=[{"type": "status",
                                                      "expect": 418}]))
        assert _hits["count"] == 2, _hits
        assert outcome["result"] == "fail"
        assert outcome.get("error_type") == 1

    def test_delay_ms_between_iterations(self):
        _reset_hits()
        started = _time.monotonic()
        outcome = _run_worker(_envelope(url_path="/hits",
                                        loop={"mode": "count", "times": 2,
                                              "delayMs": 300},
                                        assertions=[{"type": "status", "expect": 200}]))
        assert outcome["result"] == "pass", outcome.get("error_detail")
        assert _time.monotonic() - started >= 0.25, "delayMs pacing never applied"

    def test_precondition_gates_whole_request_with_loop(self):
        # the precondition gates the whole request: a skip sends zero hops
        _reset_hits()
        outcome = _run_worker(_envelope(url_path="/hits",
                                        vars_seed={"last_status": "500"},
                                        precondition={"field": "status", "op": "eq",
                                                      "expect": 200},
                                        loop={"mode": "count", "times": 3}))
        assert outcome["result"] == "manual_skip", outcome
        assert _hits["count"] == 0, "an honest skip must not send any request"

    def test_illegal_orchestration_rows_fail_closed(self):
        # an out-of-domain op fails closed on the executor too (second gate)
        outcome = _run_worker(_envelope(url_path="/ok", vars_seed={"token": "t"},
                                        precondition={"field": "var", "name": "token",
                                                      "op": "gt", "expect": 1}))
        assert outcome["result"] == "fail"
        assert outcome.get("error_type") == 3
