# -*- coding: utf-8 -*-
"""S1 : installkit doctor carries a live MCP transport handshake probe.

Locked semantics (shape-gate ruling, in-session confirmation batch):
- deny shape: an unauthenticated POST /mcp must be honestly refused with 401/403
 before any JSON-RPC is touched — a door that answers unauthenticated is a
 shape-gap (auth gate absent), not a success;
- authed shape: with a bearer token present the transport must answer a JSON-RPC
 envelope; a rejected token after a correct deny shape is an auth-gap;
- with no token available the authed phase is honestly "skipped" (never faked);
- a refused TCP connect is "unreachable" (fall to fact, never to guess);
- the probe is read-only: it only ever sends initialize + tools/list shaped
 requests and never a write call;
- the report gains an additive "mcp_handshake" key (existing consumers of
 engines/missing/missing_runtime stay byte-compatible);
- base url resolution: explicit flag > TP_MCP_BASE > API_PORT > shipped 8082.

fetch is the stdlib client; the fake doors are local http servers standing in
for the api service at L2 (the real live chain is the dual-client spectrum).
"""
import json
import socket
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import doctor


def _free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class _Door(BaseHTTPRequestHandler):
    """Configurable MCP door; class attrs injected per test via partial subclass."""
    accept_token = None      # None = no auth gate at all (answers everything)
    log_message = lambda self, *a: None

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length)
        if self.path != "/mcp":
            self.send_response(404)
            self.end_headers()
            return
        if self.accept_token is not None:
            auth = self.headers.get("Authorization") or ""
            if auth != "Bearer " + self.accept_token:
                payload = json.dumps({"message": "missing or invalid token"}).encode()
                self.send_response(401)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
                return
        try:
            body = json.loads(raw or b"{}")
        except ValueError:
            body = {}
        reply = json.dumps({"jsonrpc": "2.0", "id": body.get("id", 1),
                            "result": {"protocolVersion": "2025-03-26",
                                       "serverInfo": {"name": "fake-door"}}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)


def _serve(accept_token):
    handler = type("Door", (_Door,), {"accept_token": accept_token})
    srv = HTTPServer(("127.0.0.1", _free_port()), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d" % srv.server_address[1]


def test_deny_shape_ok_and_authed_handshake_ok():
    srv, base = _serve("good-token")
    try:
        out = doctor.mcp_handshake(base, token="good-token")
        assert out["deny"] == "ok", out
        assert out["authed"] == "ok", out
        assert out["status"] == "ok", out
    finally:
        srv.shutdown()


def test_open_door_without_auth_gate_is_shape_gap():
    srv, base = _serve(None)  # answers unauthenticated = no honest deny
    try:
        out = doctor.mcp_handshake(base, token=None)
        assert out["deny"] == "missing", out
        assert out["status"] == "shape-gap", out
    finally:
        srv.shutdown()


def test_refused_connection_is_unreachable():
    out = doctor.mcp_handshake("http://127.0.0.1:%d" % _free_port(), token=None)
    assert out["deny"] == "unreachable", out
    assert out["status"] == "unreachable", out


def test_rejected_token_after_good_deny_is_auth_gap():
    srv, base = _serve("good-token")
    try:
        out = doctor.mcp_handshake(base, token="wrong-token")
        assert out["deny"] == "ok", out
        assert out["authed"] == "fail", out
        assert out["status"] == "auth-gap", out
    finally:
        srv.shutdown()


def test_authed_phase_skipped_honestly_without_token():
    srv, base = _serve("good-token")
    try:
        out = doctor.mcp_handshake(base, token=None)
        assert out["deny"] == "ok", out
        assert out["authed"] == "skipped", out
        assert out["status"] == "ok", out
    finally:
        srv.shutdown()


def test_report_carries_additive_handshake_key(tmp_path):
    state = tmp_path / "state"
    state.mkdir()
    report = doctor.build_report(state, "linux", mcp_probe={"deny": "ok", "authed": "skipped",
                                                             "status": "ok", "base": "x", "detail": ""})
    assert report["mcp_handshake"]["status"] == "ok"
    # additive only: the frozen consumer keys stay in place
    assert {"host", "engines", "missing", "missing_runtime"} <= set(report)
    legacy = doctor.build_report(state, "linux")
    assert "mcp_handshake" not in legacy  # zero-break for two-arg callers


def test_base_resolution_prefers_flag_then_env(tmp_path, monkeypatch):
    monkeypatch.setenv("TP_MCP_BASE", "http://env-host:9/")
    assert doctor.mcp_base_url(None) == "http://env-host:9/mcp"
    monkeypatch.delenv("TP_MCP_BASE")
    monkeypatch.setenv("API_PORT", "8282")
    assert doctor.mcp_base_url(None) == "http://localhost:8282/mcp"
    assert doctor.mcp_base_url("http://flag-host:1") == "http://flag-host:1/mcp"
