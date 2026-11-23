""" the mock audit bucket must carry the honest answer provenance.

Why this file exists: the report face has to say "this case passed under N AI
fallback answers" separately from deterministic stubs (honesty red line — an
llm guess never wears a stub's clothes). The traffic plane already stamps
X-TP-Mock-Source: llm on its answers ; the executor consumers must
forward that provenance into the mockNotes audit rows so core can count the
llm dimension consumes it).

Contract:
- api-test-run (task_type=4): a mock-bound run appends one note per served
 response {path,mode,status[,source]} — source is taken verbatim from the
 X-TP-Mock-Source response header when present, and the key is absent when
 the plane answered as a plain stub (zero drift for pre-V241 answers).
- web route (task_type=1): the intercepted note rows gain the same optional
 source key from the traffic-plane header; stub/fault/passthrough rows keep
 their existing shape untouched.
- Unbound runs never grow a mockNotes key on the outcome or the payload
 .

Real socket stubs + real Playwright browser — the execution plane is never
mocked (strict-testing-standards).
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from runner import _API_WORKER_PATH

_EXECUTOR_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_SYS_TIMEOUT = 60

_state = {"api_hits": [], "mock_hits": [], "real_hits": []}


class _ApiPlaneHandler(BaseHTTPRequestHandler):
    """Stands in for the mock-server traffic plane on the API leg:
    /mocks/sp1/ok      plain stub answer (no provenance header)
    /mocks/sp1/guess   AI fallback answer (X-TP-Mock-Source: llm)
    """

    protocol_version = "HTTP/1.1"

    def do_GET(self):
        _state["api_hits"].append(self.path)
        if self.path == "/mocks/sp1/ok" or self.path == "/ok":
            # /ok serves the unbound leg (authoritative environment); the
            # mock prefix serves the bound leg. Same answer shape, so the
            # zero-drift test below isolates the audit bucket, not the route.
            self._send(200, b'{"ok": true}', extra=[])
        elif self.path == "/mocks/sp1/guess":
            self._send(200, b'{"guessed": true}',
                       extra=[("X-TP-Mock-Source", "llm")])
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def _send(self, status, payload, extra):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        for k, v in extra:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


_api_server: ThreadingHTTPServer | None = None
_api_base = ""


def setup_module(_mod=None):
    global _api_server, _api_base
    _api_server = ThreadingHTTPServer(("127.0.0.1", 0), _ApiPlaneHandler)
    threading.Thread(target=_api_server.serve_forever, daemon=True).start()
    _api_base = f"http://127.0.0.1:{_api_server.server_address[1]}"


def teardown_module(_mod=None):
    if _api_server is not None:
        _api_server.shutdown()
        _api_server.server_close()


def _envelope(*, url_path: str, mock=None) -> str:
    env = {"kind": "api-test-run", "runId": 7901, "method": "GET",
           "urlPath": url_path, "headers": {},
           "assertions": [{"type": "status", "expect": 200}],
           "envBaseUrl": _api_base,
           "execPolicy": {"allowHosts": "127.0.0.1", "denyHosts": ""},
           "timeoutMs": 10000}
    if mock is not None:
        env["mock"] = mock
    return json.dumps(env, ensure_ascii=False)


def _run_worker(action_json: str) -> dict:
    """Run api_runner.py as a real subprocess (task.json -> outcome.json)."""
    with tempfile.TemporaryDirectory(prefix="tp-t366b3-") as td:
        task_f = os.path.join(td, "task.json")
        out_f = os.path.join(td, "outcome.json")
        task = {"taskId": 909, "claimToken": "tok-t366b3", "caseId": 0,
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


def _binding():
    # Built at call time — the server port only exists after setup_module.
    return {"spaceKey": "sp1", "baseUrl": _api_base + "/mocks/sp1",
            "snapshotVersion": "pin-b3"}


class TestApiLegProvenance:
    def test_llm_answer_note_carries_source(self):
        outcome = _run_worker(_envelope(url_path="/guess", mock=_binding()))
        assert outcome["result"] == "pass", f"expected pass: {outcome}"
        notes = outcome.get("mock_notes")
        assert notes, f"mock-bound run must carry the audit bucket: {outcome}"
        assert notes[0].get("source") == "llm", \
            f"llm-provenance header must ride the note verbatim: {notes}"
        assert notes[0].get("mode") == "stub", \
            f"a 200 answer stays mode=stub with the source label beside it: {notes}"

    def test_plain_stub_note_has_no_source_key(self):
        outcome = _run_worker(_envelope(url_path="/ok", mock=_binding()))
        assert outcome["result"] == "pass", f"expected pass: {outcome}"
        notes = outcome.get("mock_notes")
        assert notes, f"mock-bound run must carry the audit bucket: {outcome}"
        assert "source" not in notes[0], \
            f"a plain stub answer must not fake a provenance: {notes}"


class TestWebLegProvenance:
    """Playwright route consumer: the llm header must tag the intercepted note."""

    def test_web_route_note_carries_llm_source(self):
        # Reuse the two-plane fixture family from the route-consumer tests,
        # extended with the provenance header on the stub answer.
        from driver.mock_route import build_route_handler

        captured: list = []
        notes: list = []

        class _Resp:
            status = 200

            def __init__(self, headers):
                self._headers = headers

            def text(self):
                return '{"hello": "from-llm"}'

            @property
            def headers(self):
                return self._headers

        class _Route:
            def __init__(self, resp):
                self._resp = resp
                self.fulfilled = None

            def fetch(self, url):
                return self._resp

            def fulfill(self, response):
                self.fulfilled = response

            def fallback(self):
                captured.append("fallback")

            def abort(self):
                captured.append("abort")

            def continue_(self):
                captured.append("continue")

        class _Req:
            resource_type = "fetch"
            url = "http://env.test/api/greet"

        handler = build_route_handler(lambda: "http://env.test",
                                      "http://mock.test/mocks/sp1/", notes)
        handler(_Route(_Resp({"x-tp-mock-source": "llm"})), _Req())
        assert notes and notes[0].get("source") == "llm", \
            f"the provenance header must ride the web-leg note: {notes}"

    def test_web_route_plain_stub_note_unchanged(self):
        from driver.mock_route import build_route_handler

        notes: list = []

        class _Resp:
            status = 200

            def text(self):
                return '{"hello": "from-stub"}'

            @property
            def headers(self):
                return {}

        class _Route:
            def fulfill(self, response):
                pass

            def fetch(self, url):
                return _Resp()

        class _Req:
            resource_type = "fetch"
            url = "http://env.test/api/greet"

        handler = build_route_handler(lambda: "http://env.test",
                                      "http://mock.test/mocks/sp1/", notes)
        handler(_Route(), _Req())
        assert notes == [{"path": "http://mock.test/mocks/sp1/api/greet",
                          "mode": "stub", "status": 200}], \
            f"plain stub rows keep their exact pre-V241 shape: {notes}"


class TestUnboundZeroDrift:
    def test_unbound_run_never_grows_notes(self):
        outcome = _run_worker(_envelope(url_path="/ok"))
        assert outcome["result"] == "pass", f"expected pass: {outcome}"
        assert "mock_notes" not in outcome, \
            f"an unbound run must serialize no audit bucket: {outcome}"
