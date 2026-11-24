"""Playwright route consumer for the claim-side mock binding (U5, .

Why this file exists: a browser case whose declaration names a PUBLISHED mock
space receives task.mock={spaceKey,baseUrl,snapshotVersion} on the claim
(03 protocol 2.3 trailing key). The web batch face must route its API-like
traffic (fetch/XHR) through the mock-server traffic plane instead of the real
dependency — while the authoritative environment profile stays untouched.

Design rulings honoured here:
- Single rule library (tp_mock_stub): the executor implements NO matcher.
 Every request is re-addressed to the traffic-plane prefix and the real
 kernel adjudicates. The three U5 modes fall out of the kernel's answer:
 stub 2xx/4xx answer from a matched rule -> fulfilled verbatim
 fault injected failure rides the same answer (5xx verbatim;
 a wire "disconnect" surfaces as a fetch exception here and
 is reproduced as an aborted request in the browser)
 passthrough honest miss (404 + {"mocked": false}) -> route.fallback,
 the browser speaks to the real dependency itself
- Fail-closed dirty shape: a binding without spaceKey/baseUrl raises in
 plain words at mount time. Silently continuing against the real
 environment would misreport the tested scope (fake green, red line 1).
- No binding on the claim = no route mounted = old executor behaviour with
 zero drift .
"""
from __future__ import annotations

import json
from typing import Callable, Optional
from urllib.parse import urlsplit

# Resource classes that carry API traffic; documents/stylesheet/images keep
# flowing to the authoritative environment (the stand-in targets service
# dependencies of the page, not the page itself).
_API_RESOURCE_TYPES = ("fetch", "xhr")

_MISS_HEADER_PROBE = "X-TP-Mock-Rule"


class MockBindingError(ValueError):
    """Dirty mock binding on the claim envelope — plain-word refusal, raised
    before any browser traffic so the run never half-starts."""


def parse_binding(mock: object) -> dict:
    """Validate the claim-side mock segment; returns {spaceKey, baseUrl}.

    A dirty shape must never degrade into 'run against the real environment'
    — that would silently misreport the tested scope.
    """
    if mock is None:
        return {}
    if not isinstance(mock, dict):
        raise MockBindingError(
            "挂载仿真空间的 mock 信封段形态非法（应为对象）：" + repr(mock)[:120])
    space_key = str(mock.get("spaceKey") or "").strip()
    base_url = str(mock.get("baseUrl") or "").strip()
    if not space_key or not base_url:
        raise MockBindingError(
            "挂载仿真空间的 mock 信封段缺少 spaceKey/baseUrl——"
            "禁静默回退真实环境（会谎报测试范围），请在「仿真空间」核对 mock 版本配置后重试")
    return {"spaceKey": space_key, "baseUrl": base_url.rstrip("/") + "/"}


def mock_url_for(env_base: str, request_url: str, mock_base: str) -> Optional[str]:
    """Re-address one same-origin API URL onto the traffic-plane prefix.

    Returns None when the URL is outside the authoritative environment base
    (third-party calls never get hijacked — only the bound system-of-record
    is stood in for).
    """
    env = env_base.rstrip("/")
    if not request_url.startswith(env + "/"):
        return None
    relative = request_url[len(env):]
    return mock_base + relative.lstrip("/")


def is_honest_miss(status: int, body_text: str) -> bool:
    """The traffic plane's miss contract: 404 whose JSON body says
    {"mocked": false}. Anything else (stub answers, fault 5xx, refusals) is
    served verbatim — the executor never rewrites kernel verdicts."""
    if status != 404:
        return False
    try:
        parsed = json.loads(body_text)
    except (ValueError, TypeError):
        return False
    return isinstance(parsed, dict) and parsed.get("mocked") is False


def exempt_honest_miss_failures(failures: list, notes: list) -> list:
    """Drop >=400 rows whose path the mock kernel answered honest-miss.

    A ``passthrough`` audit note means the mock space does not cover this
    endpoint and the request fell back to the real environment: a failure
    seen there is a coverage gap of the mock space, not a product defect,
    and surfacing it on the interface-health card would be a false alarm.
    Matching is by URL path (evidence urls and mock target urls keep the
    same request path). This is the sole consumer of the audit notes as an
    exemption ledger — no second book.
    """
    passthrough = {urlsplit(str(n.get("path") or "")).path
                   for n in notes or [] if n.get("mode") == "passthrough"}
    passthrough.discard("")
    if not passthrough:
        return list(failures)
    return [row for row in failures
            if urlsplit(str(row.get("url") or "")).path not in passthrough]


def build_route_handler(env_base_of: Callable[[], Optional[str]], mock_base: str,
                        notes: list) -> Callable:
    """Sync-API route handler implementing the three-mode contract.

 `env_base_of` is resolved per request (the authoritative base is a
 per-run override in the shared-executor flow — the route mounts on the
 context at start, traffic only happens after run supplies the base).

 `notes` collects lightweight audit entries ({path, mode}) so the run can
 later report 'this case passed under N mock answers' (report badge face
 consumes it in a later segment; mount-level truth must exist first).
 """

    def _handler(route, request) -> None:
        if request.resource_type not in _API_RESOURCE_TYPES:
            # Documents/styles/images keep flowing to the authoritative
            # environment: the stand-in covers the page's service
            # dependencies, not the page itself.
            route.continue_()
            return
        env_base = env_base_of()
        if not env_base:
            route.continue_()
            return
        target = mock_url_for(env_base, request.url, mock_base)
        if target is None:
            route.continue_()
            return
        try:
            response = route.fetch(url=target)
        except Exception:  # noqa: BLE001 — wire disconnect fault mode
            notes.append({"path": target, "mode": "fault-abort"})
            route.abort()
            return
        try:
            body_text = response.text()
        except Exception:  # noqa: BLE001 — unreadable body is not a miss
            body_text = ""
        if is_honest_miss(response.status, body_text):
            notes.append({"path": target, "mode": "passthrough"})
            route.fallback()
            return
        mode = "stub" if response.status < 400 else "fault"
        note = {"path": target, "mode": mode, "status": response.status}
        # forward the traffic plane's provenance label verbatim
        # (Playwright lower-cases response header names). An AI fallback answer
        # must be tallied as such on the report face — never as a deterministic
        # stub. No header = no key = the pre-V241 row shape stays untouched.
        provenance = None
        try:
            headers = getattr(response, "headers", None) or {}
            provenance = headers.get("x-tp-mock-source")
        except Exception:  # noqa: BLE001 — header introspection never breaks a run
            provenance = None
        if provenance:
            note["source"] = provenance
        notes.append(note)
        route.fulfill(response=response)

    return _handler


def mount_mock_routes(context, env_base_of: Callable[[], Optional[str]],
                      mock: object) -> list:
    """Attach the mock route interceptor to a Playwright browser context.

    No binding (old core / unbound case) = zero mount, zero drift. Dirty
    binding = MockBindingError raised here, before any traffic. Returns the
    audit notes list (empty when nothing is mounted).
    """
    binding = parse_binding(mock)
    if not binding:
        return []
    notes: list = []
    handler = build_route_handler(env_base_of, binding["baseUrl"], notes)
    context.route("**/*", handler)
    return notes
