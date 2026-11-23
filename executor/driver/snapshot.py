
from __future__ import annotations

import re
from typing import Any, Optional

_SENSITIVE_TOKENS = ("authorization", "authenticate", "cookie", "password",
                     "token", "secret", "apikey")

MASKED = "***MASKED***"

_PHONE_RE = re.compile(r"\d{11}")
_EMAIL_RE = re.compile(r"[^@\s]+@[^@\s]+\.[^@\s]+")


def _mask_phone(match: re.Match) -> str:
    v = match.group()
    return v[:3] + "****" + v[7:]


def _mask_email(match: re.Match) -> str:
    v = match.group()
    at = v.find("@")
    if at <= 1:
        return "*" * len(v)
    return v[0] + "***" + v[at:]


def mask_text(value: str) -> str:
    pass
    return _EMAIL_RE.sub(_mask_email, _PHONE_RE.sub(_mask_phone, value))


def _is_sensitive_key(key: str) -> bool:
    normalized = re.sub(r"[_\-\s]", "", str(key).lower())
    return any(token in normalized for token in _SENSITIVE_TOKENS)


def mask_sensitive(value: Any) -> Any:
    pass
    if isinstance(value, dict):
        return {k: (MASKED if _is_sensitive_key(k) and v is not None else mask_sensitive(v))
                for k, v in value.items()}
    if isinstance(value, list):
        return [mask_sensitive(item) for item in value]
    if isinstance(value, str):
        return mask_text(value)
    return value


def build_step_snapshot(step_no: int, kind: str,
                        request: Optional[dict], response: Optional[dict],
                        assertions: Optional[list],
                        tls_mode: Optional[str] = None) -> dict:
    pass
    snap: dict = {
        "stepNo": step_no,
        "kind": kind,
        "request": mask_sensitive(request or {}),
        "assertions": mask_sensitive(assertions or []),
    }
    if response is not None:
        snap["response"] = mask_sensitive(response)
    if tls_mode:
        snap["tlsMode"] = tls_mode
    return snap
