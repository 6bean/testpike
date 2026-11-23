"""Executor consumer segment for the api-test-run envelope 'mock' trailing key.

Contract (03 protocol trailing-key style, zero-drift discipline):
- core appends mock={spaceKey, baseUrl, snapshotVersion} to the envelope of a
 run whose case declares a bound mock space; baseUrl is the traffic-plane
 prefix /mocks/{key}/. No binding = no key = old shape untouched.
- Consumption = declarative stand-in: when the key exists, the run target base
 is rewritten to the mock baseUrl (an env-URL substitute). The rewrite touches
 only the envelope target address; the authoritative tp_project_env profile is
 never modified (no implicit environment change).
- Fail-closed on dirty shape: a mock segment missing spaceKey/baseUrl is an
 illegal envelope (error_type 3, plain words). Silently falling back to the
 real environment would misreport the tested scope -- forbidden.
- Gate semantics preserved: the rewritten address still passes the execPolicy
 double gate (denyHosts still blocks a mock host; the allow fallback set is
 derived from the rewritten base, same rights as a real environment host).

Real socket stub + real subprocess only (the execution plane is never mocked).
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

_server: ThreadingHTTPServer | None = None
_base: str = ""


class _Handler(BaseHTTPRequestHandler):
    """Stub serving /ok and /mocks/sp1/ok so the snapshot URL proves the rewrite."""

    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path in ("/ok", "/mocks/sp1/ok"):
            payload = b'{"ok": true}'
            self.send_response(200)
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


def setup_module(_mod=None):
    global _server, _base
    _server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=_server.serve_forever, daemon=True).start()
    port = _server.server_address[1]
    _base = f"http://127.0.0.1:{port}"


def teardown_module(_mod=None):
    if _server is not None:
        _server.shutdown()
        _server.server_close()


def _envelope(*, url_path: str = "/ok", mock=None,
              env_base: str | None = None,
              exec_policy: dict | str = "default") -> str:
    env = {"kind": "api-test-run", "runId": 7801, "method": "GET",
           "urlPath": url_path, "headers": {},
           "assertions": [{"type": "status", "expect": 200}]}
    env["envBaseUrl"] = _base if env_base is None else env_base
    if exec_policy == "default":
        env["execPolicy"] = {"allowHosts": "127.0.0.1", "denyHosts": ""}
    elif exec_policy is not None:
        env["execPolicy"] = exec_policy
    if mock is not None:
        env["mock"] = mock
    env["timeoutMs"] = 10000
    return json.dumps(env, ensure_ascii=False)


def _run_worker(action_json: str) -> dict:
    """Run api_runner.py as a real subprocess (task.json -> outcome.json)."""
    with tempfile.TemporaryDirectory(prefix="tp-t364d-") as td:
        task_f = os.path.join(td, "task.json")
        out_f = os.path.join(td, "outcome.json")
        task = {"taskId": 905, "claimToken": "tok-t364d", "caseId": 0,
                "taskType": 4, "actionJson": action_json}
        with open(task_f, "w", encoding="utf-8") as f:
            json.dump(task, f, ensure_ascii=False)
        proc = subprocess.run([sys.executable, _API_WORKER_PATH, task_f, out_f],
                              capture_output=True, text=True, timeout=_SYS_TIMEOUT,
                              check=False, cwd=_EXECUTOR_DIR)
        assert proc.returncode == 0, \
            f"worker nonzero exit: rc={proc.returncode} stderr={proc.stderr[:500]}"
        assert os.path.exists(out_f), f"outcome file missing: stderr={proc.stderr[:500]}"
        with open(out_f, "r", encoding="utf-8") as f:
            return json.load(f)


def _snap_url(outcome: dict) -> str:
    """Protocol snapshot is the only carrier of the actually requested URL."""
    snaps = outcome.get("protocol_snapshots") or []
    assert snaps, f"protocol snapshot missing: {outcome}"
    return str((snaps[0].get("request") or {}).get("url") or "")


class TestMockEnvelopeRewrite:
    def test_mock_key_present_rewrites_target_base_to_traffic_prefix(self):
        outcome = _run_worker(_envelope(mock={
            "spaceKey": "sp1", "baseUrl": _base + "/mocks/sp1",
            "snapshotVersion": "pin-abc"}))
        assert outcome["result"] == "pass", f"expected pass: {outcome}"
        url = _snap_url(outcome)
        assert url == _base + "/mocks/sp1/ok", \
            f"mock-bound target must equal traffic prefix + relative path, got {url}"

    def test_no_mock_key_zero_drift(self):
        outcome = _run_worker(_envelope())
        assert outcome["result"] == "pass", f"expected pass: {outcome}"
        assert _snap_url(outcome) == _base + "/ok"

    def test_mock_key_missing_baseurl_fail_closed(self):
        outcome = _run_worker(_envelope(mock={"spaceKey": "sp1"}))
        assert outcome["result"] == "fail", f"dirty shape must fail: {outcome}"
        assert outcome.get("error_type") == 3, f"must map to error_type 3: {outcome}"
        assert "mock" in str(outcome.get("error_detail", "")).lower(), \
            f"plain-word refusal missing: {outcome}"

    def test_mock_host_hitting_prod_blacklist_still_denied(self):
        outcome = _run_worker(_envelope(
            mock={"spaceKey": "sp1", "baseUrl": "http://prod.internal/mocks/sp1"},
            exec_policy={"allowHosts": "", "denyHosts": "prod.internal"}))
        assert outcome["result"] == "fail", f"denyHosts hit must refuse: {outcome}"
        assert "prod.internal" in str(outcome.get("error_detail", "")), \
            f"gate refusal must name the host: {outcome}"
