"""Real-CDP capture read path over a live Chromium debugging endpoint.

Coverage (login-state capture, main path):
- scoped reads only: cookies + localStorage are narrowed per declared origin
  list; a second open page outside the list yields zero entries (fail-closed,
  no bulk dump);
- expiry semantics: a cookie set with max-age carries an absolute expiry,
  a session cookie carries none;
- governance guards: an empty origin list rejects before any socket opens;
  a dead endpoint fails honestly instead of pretending an empty success.

The browser under test is a real Chromium launched with
--remote-debugging-port (the same protocol the operator's own browser will
expose), and the two fixture sites are local HTTP servers inside this test.
Nothing here mocks the CDP transport.
"""
from __future__ import annotations

import contextlib
import http.server
import json
import os
import socket
import subprocess
import tempfile
import threading
import time
import urllib.parse
import urllib.request

import pytest

from capture.capture_core import CaptureError, capture_candidates
from capture.scope_filter import ScopeError

_TIMEOUT_S = 10.0


def _chromium_executable() -> str:
    override = os.environ.get("TESTPIKE_CHROMIUM")
    if override and os.path.exists(override):
        return override
    cache = os.path.expanduser("~/.cache/ms-playwright")
    for name in sorted(os.listdir(cache), reverse=True) if os.path.isdir(cache) else []:
        if not name.startswith("chromium-"):
            continue
        for layout in ("chrome-linux64", "chrome-linux"):
            candidate = os.path.join(cache, name, layout, "chrome")
            if os.path.exists(candidate):
                return candidate
    raise RuntimeError("no local Chromium binary found; install a chromium build first")


class _FixtureHandler(http.server.BaseHTTPRequestHandler):
    """One login page that plants a scoped cookie and a localStorage key."""

    cookie_name = "sid"
    storage_name = "access_token"

    def do_GET(self):  # noqa: N802 - http.server naming
        body = (
            "<html><head><script>"
            f"document.cookie='{self.cookie_name}=value-{self.cookie_name};"
            "path=/;max-age=3600';"
            "document.cookie='plain=session-only;path=/';"
            f"localStorage.setItem('{self.storage_name}','stored-value');"
            "</script></head><body>fixture</body></html>"
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):  # silence per-request logging
        pass


class _Server:
    def __init__(self, host: str):
        slug = host.replace(".", "_")
        handler = type("BoundHandler", (_FixtureHandler,), {"cookie_name": "sid_" + slug})
        self._httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.port = self._httpd.server_address[1]
        self.origin = f"http://{host}:{self.port}"
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()

    def close(self):
        self._httpd.shutdown()
        self._httpd.server_close()


def _free_port() -> int:
    with contextlib.closing(socket.socket()) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def _wait_endpoint(base_url: str, deadline: float):
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(base_url + "/json/version", timeout=1.0) as resp:
                if resp.status == 200:
                    return
        except Exception as exc:  # keep probing until the deadline
            last_error = exc
        time.sleep(0.2)
    raise RuntimeError(f"debug endpoint never became ready: {last_error}")


@pytest.fixture(scope="module")
def live_browser():
    """One real Chromium plus two open pages on different origins."""
    server_a = _Server("127.0.0.1")
    server_b = _Server("localhost")
    profile = tempfile.mkdtemp(prefix="testpike-cdp-capture-")
    port = _free_port()
    proc = subprocess.Popen(
        [
            _chromium_executable(),
            "--headless=new",
            "--no-sandbox",
            "--disable-gpu",
            f"--user-data-dir={profile}",
            f"--remote-debugging-port={port}",
            server_a.origin + "/login",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    base_url = f"http://127.0.0.1:{port}"
    try:
        _wait_endpoint(base_url, time.monotonic() + 15.0)
        # open the second page on the other origin through the CDP websocket
        # (the legacy /json/new HTTP verb was removed from current Chromium)
        import asyncio

        import websockets

        async def _open_second_page() -> None:
            version = json.loads(
                urllib.request.urlopen(base_url + "/json/version", timeout=3).read()
            )
            async with websockets.connect(version["webSocketDebuggerUrl"]) as ws:
                await ws.send(json.dumps({
                    "id": 1,
                    "method": "Target.createTarget",
                    "params": {"url": server_b.origin + "/login"},
                }))
                while True:
                    reply = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
                    if reply.get("id") == 1:
                        if reply.get("error"):
                            raise RuntimeError(reply["error"].get("message"))
                        return

        asyncio.run(_open_second_page())
        _wait_pages_ready(base_url, 2, time.monotonic() + 15.0)
        yield {"base_url": base_url, "origin_a": server_a.origin, "origin_b": server_b.origin}
    finally:
        proc.terminate()
        for _ in range(25):
            if proc.poll() is not None:
                break
            time.sleep(0.2)
        else:
            proc.kill()
            proc.wait(timeout=5)
        server_a.close()
        server_b.close()
        with contextlib.suppress(OSError):
            for root, _dirs, files in os.walk(profile):
                for fname in files:
                    with contextlib.suppress(OSError):
                        os.unlink(os.path.join(root, fname))
            os.removedirs(profile)


def _wait_pages_ready(base_url: str, expected: int, deadline: float):
    while time.monotonic() < deadline:
        with urllib.request.urlopen(base_url + "/json/list", timeout=2.0) as resp:
            targets = json.loads(resp.read())
        pages = [t for t in targets if t.get("type") == "page"]
        if len(pages) >= expected and all(
            t.get("url", "").startswith("http") for t in pages
        ):
            return
        time.sleep(0.2)
    raise RuntimeError("fixture pages never finished loading")


def test_scoped_cookie_and_storage_are_returned(live_browser):
    entries = capture_candidates(
        live_browser["base_url"],
        [live_browser["origin_a"]],
        timeout_s=_TIMEOUT_S,
    )
    by_name = {e["name"]: e for e in entries}
    assert "sid_127_0_0_1" in by_name, f"scoped cookie missing from {sorted(by_name)}"
    assert by_name["sid_127_0_0_1"]["kind"] == "cookie"
    assert by_name["sid_127_0_0_1"]["value"] == "value-sid_127_0_0_1"
    assert by_name["access_token"]["kind"] == "local_storage"
    assert by_name["access_token"]["origin"] == live_browser["origin_a"]
    assert by_name["plain"]["kind"] == "cookie"


def test_expiry_carries_absolute_time_for_persistent_cookie(live_browser):
    entries = capture_candidates(
        live_browser["base_url"],
        [live_browser["origin_a"]],
        timeout_s=_TIMEOUT_S,
    )
    by_name = {e["name"]: e for e in entries}
    persisted = by_name["sid_127_0_0_1"]["expires_at"]
    session_only = by_name["plain"]["expires_at"]
    assert persisted is not None
    assert abs(persisted - (time.time() + 3600)) < 180, "cookie expiry not parsed as max-age horizon"
    assert session_only is None, "session cookie must not claim an expiry"


def test_second_open_page_outside_scope_reads_zero_entries(live_browser):
    only_b = capture_candidates(
        live_browser["base_url"],
        [live_browser["origin_b"]],
        timeout_s=_TIMEOUT_S,
    )
    names_b = {e["name"] for e in only_b}
    assert "sid_localhost" in names_b
    assert "sid_127_0_0_1" not in names_b, "origin whitelist must exclude the other page"
    assert "access_token" in names_b

    only_a = capture_candidates(
        live_browser["base_url"],
        [live_browser["origin_a"]],
        timeout_s=_TIMEOUT_S,
    )
    assert "sid_localhost" not in {e["name"] for e in only_a}


def test_two_origin_scope_returns_both_domains(live_browser):
    both = capture_candidates(
        live_browser["base_url"],
        [live_browser["origin_a"], live_browser["origin_b"]],
        timeout_s=_TIMEOUT_S,
    )
    names = {e["name"] for e in both}
    assert {"sid_127_0_0_1", "sid_localhost"} <= names


def test_empty_scope_rejects_before_any_connection(live_browser):
    with pytest.raises(ScopeError):
        capture_candidates("http://127.0.0.1:1", [], timeout_s=_TIMEOUT_S)


def test_malformed_scope_entry_rejects(live_browser):
    with pytest.raises(ScopeError):
        capture_candidates("http://127.0.0.1:1", ["not-an-origin"], timeout_s=_TIMEOUT_S)


def test_dead_endpoint_fails_honestly():
    closed = _free_port()
    with pytest.raises(CaptureError):
        capture_candidates(f"http://127.0.0.1:{closed}", ["http://127.0.0.1:8899"], timeout_s=2.0)
