

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from driver.webdriver import WebDriverExecutor, run_action_json

CASES = json.loads(
    (Path(__file__).resolve().parents[2] / "shared" / "http_expect_cases.json")
    .read_text(encoding="utf-8"))


class _Handler(BaseHTTPRequestHandler):
    """Minimal stub serving a JSON order with controllable headers (real socket)."""

    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/order":
            self._send(200, {"data": {"id": 42}})
        else:
            self._send(404, {"error": "no-route"})

    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Trace-Id", "deadbeef")
        self.send_header("X-Retry", "2")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):  # silence stub stderr noise
        pass


_server = None
_base = ""


def setUpModule():
    global _server, _base
    _server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=_server.serve_forever, daemon=True).start()
    _base = f"http://127.0.0.1:{_server.server_address[1]}"


def tearDownModule():
    if _server:
        _server.shutdown()
        _server.server_close()


def _policy():
    return {"allowHosts": "127.0.0.1", "denyHosts": "",
            "timeoutSeconds": 10, "netAssertEnabled": 1}


def _executor():
    # Judge unit tests instantiate the executor without a browser (method under
    # test is pure over its arguments — same posture as net_observer judge reuse).
    return WebDriverExecutor(headless=True)


class TestExpectJudgeParity(unittest.TestCase):
    """Fixture judge rows against _check_http_expect (single kernel, no 2nd set)."""

    def test_judge_positives_pass(self):
        ex = _executor()
        for case in CASES["judge"]["positives"]:
            ctx = case["runCtx"]
            try:
                ex._check_http_expect(case["expect"], ctx["status"], "http://x/y",
                                      ctx.get("bodyText", ""), "GET",
                                      duration_ms=ctx.get("durationMs"),
                                      response_headers=ctx.get("headers") or {})
            except Exception as e:  # noqa: BLE001 — fail with message for triage
                self.fail(f"declared expect must pass: {case['expect']} ({e})")

    def test_judge_negatives_fail_with_assertion_words(self):
        ex = _executor()
        for case in CASES["judge"]["negatives"]:
            ctx = case["runCtx"]
            with self.subTest(expect=case["expect"]):
                try:
                    ex._check_http_expect(case["expect"], ctx["status"], "http://x/y",
                                          ctx.get("bodyText", ""), "GET",
                                          duration_ms=ctx.get("durationMs"),
                                          response_headers=ctx.get("headers") or {})
                except Exception as e:  # noqa: BLE001 — message assertions below
                    message = str(e)
                    for anchor in case["messageIncludes"]:
                        self.assertIn(anchor, message,
                                      f"message must name {anchor!r}: {message}")
                    return
                self.fail(f"expect must not pass: {case['expect']}")

    def test_duration_failure_is_type1_assertion_not_env(self):
        ex = _executor()
        with self.assertRaises(Exception) as ctx:
            ex._check_http_expect(
                {"durationMs": {"op": "range", "expect": [0, 100]}},
                200, "http://x/y", "{}", "GET", duration_ms=120, response_headers={})
        self.assertIn("断言不满足", str(ctx.exception))


class TestHttpRequestRealChain(unittest.TestCase):
    """http.request steps with new expect keys run over a real socket (zero mock)."""

    def _run(self, expect):
        doc = {"version": "v2",
               "steps": [{"action": "http.request", "method": "GET",
                          "url": _base + "/order", "expect": expect}]}
        return run_action_json(json.dumps(doc, ensure_ascii=False),
                               exec_policy=_policy(), headless=True)

    def test_duration_and_headers_pass_together(self):
        outcome = self._run({"status": 200,
                             "durationMs": {"op": "range", "expect": [0, 60000]},
                             "headers": [{"name": "X-Trace-Id", "op": "regex",
                                          "expect": "^[0-9a-f]{8}$"},
                                         {"name": "X-Retry", "op": "range",
                                          "expect": [1, 3]}]})
        self.assertEqual(outcome.result, "pass", outcome.error_detail)

    def test_header_mismatch_is_assertion_type1(self):
        outcome = self._run({"headers": [{"name": "X-Trace-Id", "op": "exact",
                                          "expect": "00000000"}]})
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(outcome.error_type, 1, outcome.error_detail)
        self.assertIn("断言不满足", outcome.error_detail or "")

    def test_unknown_expect_key_fails_closed(self):
        # fail-closed: a mistyped key must refuse, never silently skip judgement.
        outcome = self._run({"durationms": {"op": "range", "expect": [0, 1]}})
        self.assertEqual(outcome.result, "fail")
        self.assertIn("未声明键", outcome.error_detail or "")


if __name__ == "__main__":
    unittest.main()
