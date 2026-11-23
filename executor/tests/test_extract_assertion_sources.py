
from __future__ import annotations

import httpx
import pytest
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from api_runner import _judge_rows
from driver.env_resolver import VarStore
from driver.http_gate import HttpExecError


def _rows_with(resp: httpx.Response, rows: list, session_capture: dict | None = None):
    store = VarStore()
    detail = _judge_rows(rows, resp.status_code, 12, resp, resp.text, store,
                         str(resp.request.url), session_capture=session_capture)
    return detail, store


def test_body_source_default_keeps_legacy_capture():
    pass
    resp = httpx.Response(200, json={"data": {"token": "T-1"}},
                          request=httpx.Request("POST", "http://tp.test/login"))
    detail, store = _rows_with(resp, [
        {"type": "extract", "path": "$.data.token", "saveAs": "TOKEN"}])
    assert detail[0]["passed"] is True
    assert store.get("TOKEN") == "T-1"


def test_header_source_captures_when_present():
    resp = httpx.Response(200, headers={"X-Auth-Token": "H-9"},
                          request=httpx.Request("GET", "http://tp.test/api"))
    detail, store = _rows_with(resp, [
        {"type": "extract", "source": "header", "name": "X-Auth-Token",
         "saveAs": "HTOKEN"}])
    assert detail[0]["passed"] is True
    assert detail[0]["actual"] == "H-9"
    assert store.get("HTOKEN") == "H-9"


def test_header_source_missing_fails_honestly_without_storing_none():
    resp = httpx.Response(200, headers={"Content-Type": "application/json"},
                          request=httpx.Request("GET", "http://tp.test/api"))
    detail, store = _rows_with(resp, [
        {"type": "extract", "source": "header", "name": "X-Absent",
         "saveAs": "GHOST"}])
    assert detail[0]["passed"] is False
    assert "X-Absent" in str(detail[0]["actual"])
    assert "GHOST" not in store.as_dict()


def test_cookie_source_captures_from_set_cookie():
    resp = httpx.Response(200, headers=[("set-cookie", "tp_sid=C-77; Path=/")],
                          request=httpx.Request("GET", "http://tp.test/api"))
    detail, store = _rows_with(resp, [
        {"type": "extract", "source": "cookie", "name": "tp_sid",
         "saveAs": "SID"}])
    assert detail[0]["passed"] is True
    assert store.get("SID") == "C-77"


def test_cookie_source_missing_fails_honestly():
    resp = httpx.Response(200,
                          request=httpx.Request("GET", "http://tp.test/api"))
    detail, store = _rows_with(resp, [
        {"type": "extract", "source": "cookie", "name": "nope",
         "saveAs": "GONE"}])
    assert detail[0]["passed"] is False
    assert "nope" in str(detail[0]["actual"])
    assert "GONE" not in store.as_dict()


def test_unknown_source_fails_closed_naming_the_three_values():
    resp = httpx.Response(200, json={},
                          request=httpx.Request("GET", "http://tp.test/api"))
    with pytest.raises(HttpExecError) as boom:
        _rows_with(resp, [
            {"type": "extract", "source": "xml", "name": "a", "saveAs": "V"}])
    msg = str(boom.value)
    assert "body" in msg and "header" in msg and "cookie" in msg


def test_source_without_name_or_path_is_environment_error():
    resp = httpx.Response(200, json={"data": {}},
                          request=httpx.Request("GET", "http://tp.test/api"))
    with pytest.raises(HttpExecError):
        _rows_with(resp, [
            {"type": "extract", "source": "header", "saveAs": "V"}])


def test_as_session_unified_across_three_sources():
    resp = httpx.Response(200, json={"data": {"token": "T-1"}},
                          headers=[("X-Auth-Token", "H-9"),
                                   ("set-cookie", "tp_sid=C-77; Path=/")],
                          request=httpx.Request("GET", "http://tp.test/login"))
    capture: dict = {}
    _rows_with(resp, [
        {"type": "extract", "path": "$.data.token", "saveAs": "B",
         "asSession": "sbody"},
        {"type": "extract", "source": "header", "name": "X-Auth-Token",
         "saveAs": "H", "asSession": "sheader"},
        {"type": "extract", "source": "cookie", "name": "tp_sid",
         "saveAs": "C", "asSession": "scookie"}],
        session_capture=capture)
    assert capture == {"sbody": "T-1", "sheader": "H-9", "scookie": "C-77"}


class _CaptureHandler(BaseHTTPRequestHandler):
    pass

    protocol_version = "HTTP/1.1"

    def do_GET(self):
        body = b'{"ok":true}'
        self.send_response(200)
        self.send_header("X-Capture-Token", "WIRE-42")
        self.send_header("Content-Type", "application/json")
        self.send_header("Set-Cookie", "wire_sid=W-88; Path=/")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def test_header_capture_over_real_socket_roundtrip():
    server = ThreadingHTTPServer(("127.0.0.1", 0), _CaptureHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        url = f"http://127.0.0.1:{server.server_address[1]}/capture"
        with httpx.Client(timeout=10) as client:
            resp = client.get(url)
        capture: dict = {}
        detail, store = _rows_with(resp, [
            {"type": "extract", "source": "header", "name": "X-Capture-Token",
             "saveAs": "WTOKEN", "asSession": "wire-token"},
            {"type": "extract", "source": "cookie", "name": "wire_sid",
             "saveAs": "WSID"}],
            session_capture=capture)
        assert all(row["passed"] for row in detail)
        assert store.get("WTOKEN") == "WIRE-42"
        assert store.get("WSID") == "W-88"
        assert capture == {"wire-token": "WIRE-42"}
    finally:
        server.shutdown()
        server.server_close()
