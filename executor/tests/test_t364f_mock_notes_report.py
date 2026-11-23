"""Executor-side trailing-key chain for mock audit notes (U5 report face).

Contract (03 protocol 2.4 result trailing key mockNotes):
- Every intercepted request appends a lightweight note {path,mode[,status]}
 on the executor side (driver/mock_route.py audit bucket). The run outcome
 must carry that bucket so the report can state "this case passed under N
 mock answers" (the badge face consumes it core-side in a later segment; the
 channel truth must exist on the executor first).
- Empty bucket = the key never appears in to_dict nor in the result payload
 .
- Per-run truth: a shared executor instance reused across runs must not leak
 the previous run's notes into the next outcome (same isolation contract as
 var_store / protocol snapshot buckets).

Real browser + real sockets on both planes — the execution plane is never
mocked (strict-testing-standards).
"""
from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from driver.webdriver import RunOutcome, WebDriverExecutor
from protocol import ExecutorProtocolClient

# Reuse the two-plane fixture handlers from the route-consumer test module
# (pytest rootdir inserts this directory into sys.path for collection).
from test_t364e_web_route import (  # noqa: E402
    _MockPlaneHandler,
    _RealPlaneHandler,
    _DOC,
    _state,
)


def _binding():
    # Built against THIS module's mock plane (the sibling module's fixture
    # servers are not started here).
    return {"spaceKey": "sp1", "baseUrl": _mock_base, "snapshotVersion": "7"}


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


def _doc():
    return _DOC.replace("@REAL@", _real_base + "/")


class TestOutcomeCarriesNotes:
    """A bound run reports its mock audit bucket on the outcome."""

    def test_bound_run_outcome_has_stub_note(self):
        _state["mock_hits"] = []
        _state["real_hits"] = []
        ex = WebDriverExecutor(headless=True, env_base_url=_real_base,
                               mock_binding=_binding())
        ex.start()
        try:
            outcome = ex.run(_doc(), env_base_url=_real_base)
        finally:
            ex.stop()
        assert outcome.result == "pass", outcome.error_detail
        notes = outcome.mock_notes
        assert notes, "bound run must carry the mock audit notes on outcome"
        stub_rows = [n for n in notes if n.get("mode") == "stub"]
        assert stub_rows, f"expected a stub-mode note, got: {notes}"
        assert "path" in stub_rows[0] and "status" in stub_rows[0]
        # to_dict must trail the bucket (subprocess -> outcome.json channel)
        assert outcome.to_dict().get("mock_notes") == notes


class TestPerRunIsolation:
    """Shared executor instance: run 2 must not inherit run 1's notes."""

    def test_second_run_carries_only_its_own_note(self):
        ex = WebDriverExecutor(headless=True, env_base_url=_real_base,
                               mock_binding=_binding())
        ex.start()
        try:
            first = ex.run(_doc(), env_base_url=_real_base)
            second = ex.run(_doc(), env_base_url=_real_base)
        finally:
            ex.stop()
        assert first.result == "pass", first.error_detail
        assert second.result == "pass", second.error_detail
        assert len(first.mock_notes) == len(second.mock_notes), \
            ("per-run notes leaked across runs: "
             f"first={first.mock_notes} second={second.mock_notes}")
        assert first.mock_notes, "bound runs must actually produce notes"


class TestNoBindingZeroDrift:
    """Unbound task: empty bucket = no key anywhere ."""

    def test_unbound_notes_empty_and_to_dict_omits_key(self):
        _state["mock_hits"] = []
        _state["real_hits"] = []
        ex = WebDriverExecutor(headless=True, env_base_url=_real_base,
                               mock_binding=None)
        ex.start()
        try:
            outcome = ex.run(_doc(), env_base_url=_real_base)
        finally:
            ex.stop()
        assert outcome.result == "pass", outcome.error_detail
        assert outcome.mock_notes == []
        assert "mock_notes" not in outcome.to_dict(), \
            "empty bucket must not trail a key into the result file"


class TestRoundTrip:
    """Outcome crosses the process boundary with the bucket intact."""

    def test_from_dict_keeps_notes(self):
        src = RunOutcome(result="pass",
                         mock_notes=[{"path": "/mocks/sp1/a", "mode": "stub",
                                      "status": 200}])
        back = RunOutcome.from_dict(src.to_dict())
        assert back.mock_notes == src.mock_notes
        assert "mock_notes" not in RunOutcome(result="pass").to_dict()


class _EchoHandler(BaseHTTPRequestHandler):
    """Records the result payload posted by ExecutorProtocolClient."""

    captured: list = []
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b"{}"
        _EchoHandler.captured.append(json.loads(raw.decode("utf-8")))
        body = json.dumps({"code": 0, "data": {}}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class TestResultChannel:
    """2.4 result: mockNotes trails only when non-empty (zero new channel)."""

    def _post(self, mock_notes):
        _EchoHandler.captured.clear()
        server = ThreadingHTTPServer(("127.0.0.1", 0), _EchoHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            client = ExecutorProtocolClient(
                f"http://127.0.0.1:{server.server_address[1]}")
            kwargs = {}
            if mock_notes is not None:
                kwargs["mock_notes"] = mock_notes
            client.submit_result(1, "tok", "pass", duration_ms=5, **kwargs)
        finally:
            server.shutdown()
            server.server_close()
        return _EchoHandler.captured[0]

    def test_empty_notes_no_payload_key(self):
        payload = self._post([])
        assert "mockNotes" not in payload, \
            "empty bucket must not touch the payload (old-core zero drift)"

    def test_non_empty_notes_trails_key(self):
        rows = [{"path": "/mocks/sp1/a", "mode": "stub", "status": 200}]
        payload = self._post(rows)
        assert payload.get("mockNotes") == rows


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
