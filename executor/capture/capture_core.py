"""One CDP capture pass: connect, read scoped cookies and localStorage.

This is the read side of the login-state capture main path. It returns
in-memory candidate entries only — nothing here writes to the ledger or to
storage; registration and replay validation arrive with the LOGIN_CAPTURE
envelope work. The whitelist gate is applied before any entry leaves this
module, so out-of-scope state is never handed onward.
"""
from __future__ import annotations

import asyncio
from urllib.parse import urlsplit

from capture.cdp_client import CaptureError, CdpCaptureClient  # noqa: F401  (re-export: one import surface for callers)
from capture.scope_filter import cookie_matches_scope, parse_scope, storage_origin_matches

_LOCAL_STORAGE_SNAPSHOT = (
    "(() => { const out = {}; for (let i = 0; i < localStorage.length; i++) {"
    " const k = localStorage.key(i); out[k] = localStorage.getItem(k); } return out; })()"
)


def _origin_of_url(url: str) -> str:
    parts = urlsplit(url)
    if not parts.scheme or not parts.hostname:
        return ""
    host = parts.hostname.lower()
    scheme = parts.scheme.lower()
    default_port = 443 if scheme == "https" else 80
    port = parts.port if parts.port is not None else default_port
    if port == default_port:
        return f"{scheme}://{host}"
    return f"{scheme}://{host}:{port}"


def _cookie_entry(cookie: dict) -> dict:
    expires = cookie.get("expires", -1)
    # CDP reports -1 for session cookies; those must not claim an expiry.
    expires_at = float(expires) if isinstance(expires, (int, float)) and expires and expires > 0 else None
    scheme = "https" if cookie.get("secure") else "http"
    return {
        "kind": "cookie",
        "name": cookie.get("name", ""),
        "value": cookie.get("value", ""),
        "origin": f"{scheme}://{(cookie.get('domain') or '').lstrip('.')}",
        "expires_at": expires_at,
        "http_only": bool(cookie.get("httpOnly")),
    }


async def _capture_async(endpoint: str, scope: list[tuple[str, str, int]], timeout_s: float) -> list[dict]:
    async with CdpCaptureClient(endpoint, timeout_s) as client:
        cookies = [
            _cookie_entry(c) for c in await client.read_all_cookies() if cookie_matches_scope(c, scope)
        ]
        entries = list(cookies)
        for target in client.page_targets():
            origin = _origin_of_url(target.get("url", ""))
            if not origin or not storage_origin_matches(origin, scope):
                continue
            session_id = await client.attach_page(target["id"])
            snapshot = await client.evaluate_json(_LOCAL_STORAGE_SNAPSHOT, session_id) or {}
            for name, value in snapshot.items():
                entries.append(
                    {
                        "kind": "local_storage",
                        "name": name,
                        "value": value,
                        "origin": origin,
                        "expires_at": None,
                        "http_only": False,
                    }
                )
        return entries


def capture_candidates(endpoint: str, scope_origins: list[str], *, timeout_s: float = 8.0) -> list[dict]:
    """Read login-state candidates for the declared origins, fail-closed.

    The scope list is validated before any socket opens (an empty or
    malformed list raises ScopeError and the browser is never connected).
    The debug endpoint string stays in memory for the duration of the pass
    only and the session is always closed afterwards.
    """
    scope = parse_scope(scope_origins)
    return asyncio.run(_capture_async(endpoint, scope, timeout_s))
