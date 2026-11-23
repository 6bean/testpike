
from __future__ import annotations

import json
from typing import Any, Optional
from urllib.parse import urlsplit


def storage_state_kwargs(envelope: Optional[dict]) -> dict[str, Any]:
    """Translate the claim's sessionState envelope into ``new_context`` kwargs.

    Returns ``{}`` when no login state is declared and ``{"storage_state": ...}``
    when a usable document is present. Raises ``ValueError`` in plain words when
    the envelope is half-built, so the task fails with a readable reason instead
    of running without the login state.
    """
    if not envelope:
        return {}
    if not isinstance(envelope, dict):
        raise ValueError(
            "登录态通道形态不正确（应为包含 stateName/storageState 的对象），本次执行已停止，请在登录态页重新采集后再试。")
    state_name = str(envelope.get("stateName") or "").strip() or "未命名"
    raw = envelope.get("storageState")
    if raw is None or (isinstance(raw, str) and not raw.strip()):
        # Core is the policy source: a refusal envelope carries the precise
        # remedy (row absent / ambiguous role listing candidates / token kind).
        # Relay it verbatim instead of degrading it to the generic wording.
        refusal = str(envelope.get("refusal") or "").strip()
        if refusal:
            raise ValueError(refusal)
        raise ValueError(
            f"登录态「{state_name}」没有可用的浏览器快照内容，本次执行已停止，请在登录态页重新采集。")
    if isinstance(raw, dict):
        return {"storage_state": raw}
    if not isinstance(raw, str):
        raise ValueError(
            f"登录态「{state_name}」的快照内容形态不正确，本次执行已停止，请在登录态页重新采集。")
    try:
        document = json.loads(raw)
    except ValueError as malformed:
        raise ValueError(
            f"登录态「{state_name}」的快照内容无法解析（{malformed}），本次执行已停止，"
            "请在登录态页重新采集。") from malformed
    if not isinstance(document, dict):
        raise ValueError(
            f"登录态「{state_name}」的快照内容不是浏览器可识别的存储状态对象，本次执行已停止，"
            "请在登录态页重新采集。")
    return {"storage_state": document}


REJECTION_STATUSES = (401, 403)
MAX_REJECTION_ROWS = 5


def _origin(url: str) -> str:
    """Lowercased scheme://host[:port] of an absolute URL; '' when unusable."""
    try:
        parts = urlsplit(str(url).strip())
    except ValueError:
        return ""
    if not parts.scheme or not parts.hostname:
        return ""
    origin = f"{parts.scheme.lower()}://{parts.hostname.lower()}"
    return f"{origin}:{parts.port}" if parts.port else origin


def collect_session_auth_rejections(entries, base_url) -> list[dict]:
    """Narrow the response observations to the run's own origin (03 result
 trailing key sessionAuthRejections, AV face 3 .

 Only called when a ledger login state was actually injected. A 401/403
 answer from the environment origin is the rejection signal for that state;
 rejections from other origins (third parties, unrelated proxies) say
 nothing about it and are dropped, so core SessionAuthRejectionWriteback
 can weigh every row here against the ledger's own scope whitelist and the
 expected-status exemption. Deduplicated, capped at 5 rows: evidence
 ceiling discipline — this helper holds zero policy judgements (red line 6).
 """
    base_origin = _origin(base_url or "")
    if not base_origin:
        return []
    rows: list[dict] = []
    seen: set = set()
    for entry in entries or []:
        status = entry.get("status")
        url = str(entry.get("url") or "")
        if status not in REJECTION_STATUSES or _origin(url) != base_origin:
            continue
        key = (url, status)
        if key in seen:
            continue
        seen.add(key)
        rows.append({"url": url, "status": int(status)})
        if len(rows) >= MAX_REJECTION_ROWS:
            break
    return rows
