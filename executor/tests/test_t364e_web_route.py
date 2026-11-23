"""Executor web-batch consumer segment for the claim-side trailing key `mock`.

Contract (03 protocol 2.3 mock trailing key, U5 ruling — playwright route,
three modes passthrough/stub/fault, rule library tp_mock_stub single source):
- core appends task.mock={spaceKey,baseUrl,snapshotVersion} to the claim of a
 task_type=1 browser task whose case names a PUBLISHED mock space. No
 binding = no key = old shape untouched .
- Consumption = declarative stand-in at the browser level: same-origin
 fetch/XHR traffic is re-addressed to the traffic-plane prefix; every mock
 answer is served by the REAL mock kernel on the other side, so the
 executor implements no second matcher (red line: single rule library).
- Three modes fall out of the traffic-plane answer:
 stub matched rule answers -> fulfilled verbatim
 fault fault injection rides the stub answer (5xx as-is; a wire
 disconnect makes the fetch raise -> aborted verbatim)
 passthrough miss (404 + {"mocked": false}) -> route.fallback, the browser
 speaks to the real dependency itself
- Fail-closed on dirty shape: a mock segment missing spaceKey/baseUrl raises
 in plain words and never silently proceeds against the real environment
 (that would misreport the tested scope — fake green, red line 1).

Real Playwright browser + real sockets on both planes (the execution plane is
never mocked — strict-testing-standards).
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_EXECUTOR_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _EXECUTOR_DIR not in sys.path:
    sys.path.insert(0, _EXECUTOR_DIR)

from driver.webdriver import WebDriverExecutor  # noqa: E402

_TIMEOUT = 20

# ------------------------------------------------------------------ servers

_state = {"mock_hits": [], "real_hits": []}


class _MockPlaneHandler(BaseHTTPRequestHandler):
    """Stands in for the mock-server traffic plane (shape per its contract):
    /mocks/sp1/api/greeting -> stub answer; any other path -> honest miss."""

    protocol_version = "HTTP/1.1"

    def do_GET(self):
        _state["mock_hits"].append(self.path)
        if self.path.startswith("/mocks/sp1/api/greeting"):
            payload = b'{"hello": "from-stub"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("X-TP-Mock-Rule", "greeting-stub")
            self.send_header("X-TP-Mock-Rule-Version", "7")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        elif self.path.startswith("/mocks/sp1/api/fivehundred"):
            payload = b'{"error": "injected"}'
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("X-TP-Mock-Rule", "faulty-stub")
            self.send_header("X-TP-Mock-Rule-Version", "7")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        else:
            payload = b'{"mocked": false, "reason": "no rule matched"}'
            self.send_response(404)
            self.send_header("Content-Type", "application/json")
            self.send_header("X-TP-Mock-Rule-Version", "7")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    def log_message(self, *args):
        pass


_PAGE = b"""<!doctype html><html><head><meta charset="utf-8"></head><body>
<div id="result">idle</div>
<script>
fetch('/api/greeting').then(function (r) {
  return r.text().then(function (t) {
    document.getElementById('result').textContent = r.status + '|' + t;
  });
});
</script></body></html>"""


class _RealPlaneHandler(BaseHTTPRequestHandler):
    """The authoritative environment: serves the page AND the real API."""

    protocol_version = "HTTP/1.1"

    def do_GET(self):
        _state["real_hits"].append(self.path)
        if self.path == "/":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(_PAGE)))
            self.end_headers()
            self.wfile.write(_PAGE)
        elif self.path.startswith("/api/greeting"):
            payload = b'{"hello": "from-real"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        elif self.path.startswith("/api/fivehundred"):
            payload = b'{"error": "real says five hundred"}'
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def log_message(self, *args):
        pass


_mock_server: ThreadingHTTPServer | None = None
_real_server: ThreadingHTTPServer | None = None
_mock_base = ""
_real_base = ""


def setup_module(_=None):
    global _mock_server, _real_server, _mock_base, _real_base
    _mock_server = ThreadingHTTPServer(("127.0.0.1", 0), _MockPlaneHandler)
    _real_server = ThreadingHTTPServer(("127.0.0.1", 0), _RealPlaneHandler)
    threading.Thread(target=_mock_server.serve_forever, daemon=True).start()
    threading.Thread(target=_real_server.serve_forever, daemon=True).start()
    mp = _mock_server.server_address
    rp = _real_server.server_address
    _mock_base = f"http://127.0.0.1:{mp[1]}/mocks/sp1/"
    _real_base = f"http://127.0.0.1:{rp[1]}"


def teardown_module(_=None):
    if _mock_server:
        _mock_server.shutdown()
    if _real_server:
        _real_server.shutdown()


def _reset():
    _state["mock_hits"] = []
    _state["real_hits"] = []


def _binding():
    return {"spaceKey": "sp1", "baseUrl": _mock_base, "snapshotVersion": "7"}


_DOC = json.dumps({"version": "v2", "steps": [
    {"action": "goto", "target": {"url": "@REAL@"}},
    {"action": "expect", "target": {"css": "#result"},
     "expect": {"state": "attached"}},
]})


def _run(mock_binding, probe_path="/api/greeting"):
    """One real browser run against the authoritative environment."""
    _reset()
    doc = _DOC.replace("@REAL@", _real_base + "/")
    ex = WebDriverExecutor(headless=True, env_base_url=_real_base,
                           mock_binding=mock_binding)
    try:
        ex.start()
        outcome = ex.run(doc, env_base_url=_real_base)
    finally:
        ex.stop()
    return outcome


class TestStubMode:
    """A matched stub answer rides back into the page verbatim."""

    def test_bound_task_page_sees_stub_answer_not_real(self):
        _reset()
        ex = WebDriverExecutor(headless=True, env_base_url=_real_base,
                               mock_binding=_binding())
        try:
            ex.start()
            page = ex._context.new_page()
            page.goto(_real_base + "/")
            text = page.evaluate("""() => new Promise(res => {
                const t = setInterval(() => {
                  const v = document.getElementById('result').textContent;
                  if (v !== 'idle') { clearInterval(t); res(v); }
                }, 50);
                setTimeout(() => { clearInterval(t);
                  res(document.getElementById('result').textContent); }, 8000);
            })""")
        finally:
            ex.stop()
        assert text.startswith("200") and "from-stub" in text, \
            f"page must show the stub answer verbatim, got: {text}"
        assert any(p.startswith("/mocks/sp1/api/greeting")
                   for p in _state["mock_hits"]), \
            "the bound traffic plane must have been asked"


class TestNoKeyZeroDrift:
    """No mock key on the claim = old shape untouched: page speaks to the real
    environment, and the mock plane is never contacted."""

    def test_unbound_task_hits_real_environment(self):
        outcome = _run(None)
        assert outcome.result == "pass", outcome.error_detail
        assert any(p.startswith("/api/greeting") for p in _state["real_hits"]), \
            "real environment must serve the API when no binding exists"
        assert not _state["mock_hits"], "no binding = zero route mounted"


class TestPassthroughMode:
    """A traffic-plane miss (404 + mocked:false) falls back: the browser
    re-asks the real dependency itself."""

    def test_missed_fall_through_path_served_by_real(self):
        _reset()
        doc = json.dumps({"version": "v2", "steps": [
            {"action": "goto", "target": {"url": _real_base + "/"}},
        ]})
        # /api/untouched is stubbed by nothing: the mock plane misses and the
        # browser must still get the real answer (or a real 404 — either way
        # the page load itself never errors and the request left the router).
        ex = WebDriverExecutor(headless=True, env_base_url=_real_base,
                               mock_binding=_binding())
        try:
            ex.start()
            page = ex._context.new_page()
            resp = page.goto(_real_base + "/")
            assert resp.status == 200
            api = page.evaluate("""() => fetch('/api/untouched')
                .then(r => r.status)""")
            outcome_ok = True
        finally:
            ex.stop()
        assert outcome_ok
        assert api in (404, 200), "fallback request must reach the real plane"
        assert any(p.startswith("/api/untouched") for p in _state["real_hits"]), \
            "missed mock path must fall through to the real environment"


class TestFaultMode:
    """A fault answer (injected 5xx) is served verbatim — the executor never
    rewrites or hides the failure."""

    def test_injected_five_hundred_reaches_the_page(self):
        _reset()
        ex = WebDriverExecutor(headless=True, env_base_url=_real_base,
                               mock_binding=_binding())
        try:
            ex.start()
            page = ex._context.new_page()
            page.goto(_real_base + "/")
            code = page.evaluate("""() => fetch('/api/fivehundred')
                .then(r => r.status)""")
        finally:
            ex.stop()
        assert code == 500, "fault-injected answer must ride through as-is"
        assert not any(p.startswith("/api/fivehundred")
                       for p in _state["real_hits"]), \
            "a stubbed (faulted) path must not also hit the real plane"


class TestFailClosedDirtyShape:
    """Dirty mock segment (missing spaceKey/baseUrl) = plain-word refusal at
    mount time; the run never silently proceeds against the real environment."""

    def test_missing_baseurl_refuses_and_never_goes_real(self):
        _reset()
        import pytest
        with pytest.raises(ValueError, match="baseUrl"):
            WebDriverExecutor(headless=True, env_base_url=_real_base,
                              mock_binding={"spaceKey": "sp1",
                                            "snapshotVersion": "7"})
        assert not _state["real_hits"], "refusal must happen before any traffic"

    def test_non_dict_refuses(self):
        import pytest
        with pytest.raises(ValueError):
            WebDriverExecutor(headless=True, env_base_url=_real_base,
                              mock_binding="sp1")

    def test_subprocess_worker_fail_closed_outcome(self):
        """task.json with a dirty mock key -> outcome fail / error_type 3 with
        plain words (no silent pass on the real environment)."""
        _reset()
        import tempfile
        task = {"taskId": 1, "claimToken": "t", "caseId": 1,
                "taskType": 1, "envBaseUrl": _real_base,
                "actionJson": _DOC.replace("@REAL@", _real_base + "/"),
                "mock": {"spaceKey": "sp1"}}
        with tempfile.TemporaryDirectory() as td:
            tf = os.path.join(td, "task.json")
            of = os.path.join(td, "outcome.json")
            with open(tf, "w", encoding="utf-8") as f:
                json.dump(task, f, ensure_ascii=False)
            proc = subprocess.run(
                [sys.executable, os.path.join(_EXECUTOR_DIR, "task_worker.py"),
                 tf, of], capture_output=True, text=True, timeout=_TIMEOUT * 3,
                cwd=_EXECUTOR_DIR)
            assert proc.returncode == 0, proc.stderr
            with open(of, encoding="utf-8") as f:
                outcome = json.load(f)
        assert outcome["result"] == "fail"
        assert outcome["error_type"] == 3
        assert "baseUrl" in outcome["error_detail"] or "仿真" in outcome["error_detail"], \
            outcome["error_detail"]
        assert not _state["real_hits"], "dirty shape must not run the real env"
