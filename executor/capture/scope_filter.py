"""Origin whitelist narrowing for captured browser state (fail-closed).

The capture contract never exports a full cookie/storage dump: every entry
is kept only when its origin is covered by the declared scope list. An
empty or malformed list rejects the read before any browser connection is
made, so a misconfigured wizard cannot leak state from unrelated sites.
"""
from __future__ import annotations

from urllib.parse import urlsplit


class ScopeError(ValueError):
    """Raised when the declared origin scope list is empty or malformed."""


def normalize_origin(value: str) -> tuple[str, str, int]:
    """Split one declared scope entry into (scheme, host, port).

    Only absolute http/https origins with a host are accepted; paths,
    wildcards and non-web schemes are rejected — narrowing must be exact.
    """
    parts = urlsplit((value or "").strip())
    scheme = parts.scheme.lower()
    host = (parts.hostname or "").lower()
    if scheme not in ("http", "https") or not host or parts.path not in ("", "/"):
        raise ScopeError(f"scope entry is not an exact http(s) origin: {value!r}")
    try:
        port = parts.port if parts.port is not None else (443 if scheme == "https" else 80)
    except ValueError as exc:  # malformed port in the URL
        raise ScopeError(f"scope entry has an invalid port: {value!r}") from exc
    return scheme, host, port


def parse_scope(origins: list[str]) -> list[tuple[str, str, int]]:
    """Validate the whole list up front; an empty list is rejected."""
    if not origins:
        raise ScopeError("origin scope list is empty; capture requires at least one origin")
    return [normalize_origin(origin) for origin in origins]


def storage_origin_matches(origin_url: str, scope: list[tuple[str, str, int]]) -> bool:
    """True when a page/storage origin equals one declared scope entry."""
    try:
        normalized = normalize_origin(origin_url)
    except ScopeError:
        return False
    return normalized in scope


def cookie_matches_scope(cookie: dict, scope: list[tuple[str, str, int]]) -> bool:
    """True when a CDP cookie's host-only domain equals one scope host.

    Cookies carry no port; the host string must match exactly (a leading
    dot from a Domain= attribute is stripped). Secure cookies are only kept
    for https scope entries.
    """
    domain = (cookie.get("domain") or "").lower().lstrip(".")
    if not domain:
        return False
    secure = bool(cookie.get("secure"))
    return any(host == domain and (not secure or scheme == "https") for scheme, host, _port in scope)
