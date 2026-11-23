"""Minimal Chrome DevTools Protocol reader for one capture pass.

The debug endpoint string is ephemeral and sensitive: it is used in memory
only, never logged and never persisted. The whole pass runs inside a single
asyncio loop so the browser WebSocket stays on its owning loop, and the
socket is always closed in the finally branch (R57: resource paired at its
creation point — success is not the only exit).
"""
from __future__ import annotations

import asyncio
import json
import urllib.error
import urllib.request
from typing import Any

import websockets


class CaptureError(RuntimeError):
    """Honest failure of one capture pass (endpoint dead or protocol error)."""


def _http_json(endpoint: str, timeout_s: float) -> Any:
    try:
        with urllib.request.urlopen(endpoint, timeout=timeout_s) as response:
            return json.loads(response.read())
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as exc:
        raise CaptureError("debug endpoint unreachable or returned a non-JSON answer") from exc


class CdpCaptureClient:
    """Browser-level CDP session used by exactly one async capture pass."""

    def __init__(self, endpoint: str, timeout_s: float):
        self._endpoint = endpoint.rstrip("/")
        self._timeout_s = timeout_s
        self._ws: Any = None
        self._next_id = 0

    async def __aenter__(self) -> "CdpCaptureClient":
        version = _http_json(self._endpoint + "/json/version", self._timeout_s)
        ws_url = version.get("webSocketDebuggerUrl")
        if not ws_url:
            raise CaptureError("debug endpoint answered without a browser websocket URL")
        try:
            self._ws = await asyncio.wait_for(
                websockets.connect(ws_url, max_size=32 * 1024 * 1024), self._timeout_s
            )
        except CaptureError:
            raise
        except Exception as exc:
            raise CaptureError("could not attach to the browser debug session") from exc
        return self

    async def __aexit__(self, *exc_info) -> None:
        if self._ws is not None:
            try:
                await asyncio.wait_for(self._ws.close(), self._timeout_s)
            except Exception:
                pass  # teardown path: nothing downstream may depend on the socket
            self._ws = None

    async def send(self, method: str, params: dict | None = None, session_id: str | None = None) -> dict:
        if self._ws is None:
            raise CaptureError("capture session is not open")
        self._next_id += 1
        request_id = self._next_id
        message: dict[str, Any] = {"id": request_id, "method": method, "params": params or {}}
        if session_id:
            message["sessionId"] = session_id
        await asyncio.wait_for(self._ws.send(json.dumps(message)), self._timeout_s)
        while True:
            raw = await asyncio.wait_for(self._ws.recv(), self._timeout_s)
            reply = json.loads(raw)
            if reply.get("id") == request_id:
                if reply.get("error"):
                    raise CaptureError(f"CDP call {method} rejected: {reply['error'].get('message')}")
                return reply.get("result", {})

    def page_targets(self) -> list[dict]:
        targets = _http_json(self._endpoint + "/json/list", self._timeout_s)
        return [t for t in targets if t.get("type") == "page" and t.get("url", "").startswith("http")]

    async def read_all_cookies(self) -> list[dict]:
        # Storage.getCookies is the browser-level cookie read channel kept
        # current by Chromium (the legacy Network.getAllCookies was removed
        # from the browser context); both return {cookies: [...]}.
        return (await self.send("Storage.getCookies")).get("cookies", [])

    async def attach_page(self, target_id: str) -> str:
        result = await self.send("Target.attachToTarget", {"targetId": target_id, "flatten": True})
        session_id = result.get("sessionId")
        if not session_id:
            raise CaptureError("page target attach returned no session")
        return session_id

    async def evaluate_json(self, expression: str, session_id: str) -> Any:
        """Runtime.evaluate an expression that yields a JSON-safe value."""
        result = await self.send(
            "Runtime.evaluate",
            {"expression": expression, "returnByValue": True, "awaitPromise": False},
            session_id=session_id,
        )
        remote = result.get("result", {})
        if result.get("exceptionDetails") or remote.get("subtype") == "error":
            raise CaptureError("page-side read expression threw; the session may be stale")
        return remote.get("value")
