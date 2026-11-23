
from __future__ import annotations

import shutil
from typing import Optional

from driver.mobile.contracts import capabilities_of

LEVEL_NATIVE = "native"
LEVEL_SCRCPY = "scrcpy"
LEVEL_UNAVAILABLE = "unavailable"

_RECORD_NATIVE_CAP = "record-native"

_ANDROID_FALLBACK_TOOLS = ("scrcpy", "adb")

_EVENT_KEYS = ("kind", "ts", "target", "text", "detail")


_capabilities_of = capabilities_of


def resolve_recording_source(descriptor: dict | None,
                             adapter) -> dict:
    pass
    descriptor = descriptor or {}
    caps = _capabilities_of(descriptor)

    if caps.get(_RECORD_NATIVE_CAP) is True:
        hook = getattr(adapter, "record_events", None)
        if callable(hook):
            return {"level": LEVEL_NATIVE, "reason": None}
        native_gap = (f"描述符已声明 {_RECORD_NATIVE_CAP} 能力位，但引擎适配器"
                      "record_events 实现缺失（两侧漂移——请核对引擎版本与适配器装载面）")
    else:
        native_gap = f"引擎未声明原生录制能力（{_RECORD_NATIVE_CAP} 位未开）"

    platforms = set(descriptor.get("platformCodes") or [])
    if "ANDROID" in platforms:
        found = [t for t in _ANDROID_FALLBACK_TOOLS if shutil.which(t)]
        if found:
            return {"level": LEVEL_SCRCPY, "reason": None}
        return {
            "level": LEVEL_UNAVAILABLE,
            "reason": (native_gap + "；安卓回落流需宿主安装 scrcpy 或 adb"
                       "（环境件需自行安装，testPike 不随包分发），两者均未在 PATH 检出"),
        }

    return {
        "level": LEVEL_UNAVAILABLE,
        "reason": (native_gap + "；该引擎平台声明非安卓（ANDROID），无输入注入回落流"
                   "——录制入口将置灰显示本原因"),
    }


def normalize_events(raw_events) -> dict:
    pass
    events: list[dict] = []
    dropped = 0
    for item in (raw_events or []):
        if not isinstance(item, dict):
            dropped += 1
            continue
        kind = item.get("kind")
        if not isinstance(kind, str) or not kind.strip():
            dropped += 1
            continue
        row: dict = {"kind": kind.strip()}
        for key in _EVENT_KEYS[1:]:
            val: Optional[str] = item.get(key)
            if val is not None:
                row[key] = val
        ts = item.get("ts")
        if ts is None or not isinstance(ts, (int, float)):
            row.pop("ts", None)
        events.append(row)
    return {"events": events, "dropped": dropped}


def collect_record_events(adapter, source: dict) -> dict:
    pass
    raw = None
    level = (source or {}).get("level")
    if level == LEVEL_NATIVE:
        hook = getattr(adapter, "record_events", None)
        if callable(hook):
            try:
                raw = hook()
            except Exception:
                raw = None
    elif level == LEVEL_SCRCPY:
        capture = (source or {}).get("capture")
        reader = getattr(capture, "events", None)
        if callable(reader):
            try:
                raw = reader()
            except Exception:
                raw = None
    normalized = normalize_events(raw)
    return {"level": level, **normalized}
