
from __future__ import annotations

import logging
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field

from capture.capture_core import CaptureError, capture_candidates
from capture.scope_filter import ScopeError

# Commit-round entry projection whitelist: values are admitted here on purpose —
# they transit this process to the one commit-values request and nowhere else
# (no store, no report row, no log line).
_COMMIT_FIELDS = ("kind", "name", "origin", "value")

log = logging.getLogger("testpike.executor")

# Metadata projection: anything not listed here is dropped before it can leave
# the process. ``value`` is deliberately absent (red line 3).
_METADATA_FIELDS = ("kind", "name", "origin")
_METADATA_RENAMES = {"expires_at": "expiresAtEpochSecond"}


@dataclass
class CaptureRoundResult:
    """Outcome of one round (value object; red line 6 — no inner classes)."""

    session_id: str
    accepted: int = 0
    failure_reason: str | None = None
    rows: list[dict] = field(default_factory=list)

    @property
    def succeeded(self) -> bool:
        return self.failure_reason is None


def to_metadata_rows(entries: list[dict]) -> list[dict]:
    """Project raw CDP entries onto the whitelisted metadata shape."""
    rows: list[dict] = []
    for entry in entries or []:
        if not isinstance(entry, Mapping):
            continue
        row: dict = {}
        for key in _METADATA_FIELDS:
            value = entry.get(key)
            if value is not None:
                row[key] = str(value)
        for source, target in _METADATA_RENAMES.items():
            if entry.get(source) is not None:
                row[target] = entry[source]
        if row.get("name"):
            rows.append(row)
    return rows


def run_capture_round(session: Mapping, *,
                      capture: Callable[..., list[dict]] = capture_candidates,
                      spawn_probe: bool = True) -> CaptureRoundResult:
    """Read one session's scoped candidates and return the metadata to report.

    ``capture`` is injectable so the honest-refusal and value-stripping faces are
    testable without a browser; production rides the real CDP read path.
    ``spawn_probe`` is kept for symmetry with future rounds and ignored today.
    """
    session_id = str(session.get("sessionId") or "")
    endpoint = str(session.get("debugEndpoint") or "")
    origins = list(session.get("scopeOrigins") or [])
    timeout_ms = session.get("validateTimeoutMs") or 8000
    result = CaptureRoundResult(session_id=session_id)
    if not session_id or not endpoint or not origins:
        result.failure_reason = "采集会话参数不完整，请重新发起采集"
        return result
    try:
        entries = capture(endpoint, origins, timeout_s=float(timeout_ms) / 1000.0)
    except ScopeError as exc:
        # An illegal whitelist is a caller error, not a missing login: say so.
        result.failure_reason = f"来源域名白名单不合法：{exc}"
        return result
    except CaptureError as exc:
        result.failure_reason = (
            "无法连接本机浏览器调试端点，请确认浏览器已按指引开启远程调试后重新发起采集")
        log.warning("采集回合连接调试端点失败（会话 %s）: %s", session_id, exc)
        return result
    rows = to_metadata_rows(entries)
    result.rows = rows
    # Endpoint and values are never logged; only the count is observable.
    log.info("采集回合完成（会话 %s）候选条目=%d", session_id, len(rows))
    return result


def poll_and_run_capture_round(client) -> CaptureRoundResult | None:
    """Idle-round entry point: claim one session, read it, report the answer.

    Claim → run → report is a single round; the executor never holds a session
    across rounds. Reporting carries either candidate metadata or an honest
    failure reason, so the wizard always learns what happened.
    """
    session = client.claim_capture_session()
    if not session or not session.get("sessionId"):
        return None
    if session.get("commitSelection"):
        # Reverse commit signal (U3): re-read the checked keys and answer with
        # values on the commit-values door instead of the metadata report.
        result = run_commit_round(session)
        client.commit_capture_values(
            result.session_id,
            entries=result.rows,
            failure_reason=result.failure_reason)
        return result
    result = run_capture_round(session)
    client.report_capture_candidates(
        result.session_id,
        candidates=result.rows,
        failure_reason=result.failure_reason)
    return result


def to_committed_entries(entries: list[dict], selected_names: list[str]) -> list[dict]:
    """Project freshly read entries onto the commit shape, checked names only.

    Anything whose name is not ticked in the wizard is dropped whole — the
    unchecked key name must not even ride the request body as text (red line 2
    double gate, executor side of the pair; core re-filters independently).
    """
    wanted = {str(name) for name in selected_names or []}
    rows: list[dict] = []
    for entry in entries or []:
        if not isinstance(entry, Mapping) or str(entry.get("name")) not in wanted:
            continue
        row: dict = {}
        for key in _COMMIT_FIELDS:
            if entry.get(key) is not None:
                row[key] = str(entry[key])
        if entry.get("expires_at") is not None:
            row["expiresAtEpochSecond"] = entry["expires_at"]
        if row.get("name"):
            rows.append(row)
    return rows


def run_commit_round(session: Mapping, *,
                     capture: Callable[..., list[dict]] = capture_candidates
                     ) -> CaptureRoundResult:
    """Commit round: re-read ONLY the checked keys and answer with the values.

    The checked names arrive inside the claim (``commitSelection`` — the commit
    confirmation re-queues on the same hand-off queue, no second channel). A
    browser session lost by commit time is answered as an honest invalidation
    reason pointing the wizard back to re-opening; the core side deletes the
    session terminally on that answer, so a retry loop can never pin it open.
    """
    session_id = str(session.get("sessionId") or "")
    endpoint = str(session.get("debugEndpoint") or "")
    origins = list(session.get("scopeOrigins") or [])
    selected = [str(name) for name in (session.get("commitSelection") or []) if name]
    timeout_ms = session.get("validateTimeoutMs") or 8000
    result = CaptureRoundResult(session_id=session_id)
    if not session_id or not endpoint or not origins or not selected:
        result.failure_reason = "采集会话参数不完整，请重新发起采集"
        return result
    try:
        entries = capture(endpoint, origins, timeout_s=float(timeout_ms) / 1000.0)
    except ScopeError as exc:
        result.failure_reason = f"来源域名白名单不合法：{exc}"
        return result
    except CaptureError as exc:
        result.failure_reason = ("无法连接本机浏览器调试端点，登录态可能已失效，"
                                 "请在打开调试端点后重新发起采集")
        log.warning("提交回合无法连接调试端点（会话 %s）: %s", session_id, exc)
        return result
    result.rows = to_committed_entries(entries, selected)
    # Values never reach the log: only the committed count is observable here.
    log.info("提交回合完成（会话 %s）入册条目=%d", session_id, len(result.rows))
    return result
