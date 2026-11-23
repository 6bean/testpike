
from __future__ import annotations

import logging
import re
import shutil
import subprocess
import threading
import time
from typing import Callable, Optional

from capabilities import host_os_name

log = logging.getLogger("executor")

_HEADER_MARKER = "List of devices attached"
_EMULATOR_RE = re.compile(r"^emulator-\d+$")
_HOST_PORT_RE = re.compile(r"^[^:\s]+:(\d{1,5})$")
_SCAN_TIMEOUT_SEC = 10.0

_cache_lock = threading.Lock()
_last_scan_mono: Optional[float] = None
_last_rows: Optional[list] = None


def classify_serial(serial: str) -> tuple[str, Optional[str]]:
    pass
    if _EMULATOR_RE.match(serial):
        return "EMULATOR", None
    match = _HOST_PORT_RE.match(serial)
    if match and 0 < int(match.group(1)) <= 65535:
        return "ADB", serial
    return "USB", None


def parse_adb_devices(text: str) -> list[dict]:
    pass
    rows: list[dict] = []
    for line in (text or "").splitlines():
        line = line.strip()
        if not line or line == _HEADER_MARKER or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        props: dict[str, str] = {}
        for token in parts[2:]:
            key, sep, value = token.partition(":")
            if sep and key:
                props[key] = value
        rows.append({"serial": parts[0], "state": parts[1],
                     "model": props.get("model")})
    return rows


def _to_facts(rows: list[dict]) -> list[dict]:
    pass
    facts: list[dict] = []
    for row in rows:
        device_type, adb_address = classify_serial(row["serial"])
        fact = {
            "serial": row["serial"],
            "state": row["state"],
            "deviceType": device_type,
            "platform": "ANDROID",
            "hostOs": host_os_name(),
            "creatable": row["state"] == "device",
        }
        if row.get("model"):
            fact["model"] = row["model"]
        if adb_address:
            fact["adbAddress"] = adb_address
        facts.append(fact)
    return facts


def collect_devices(interval_sec: float = 60.0,
                    which: Callable = shutil.which) -> Optional[list[dict]]:
    pass
    global _last_scan_mono, _last_rows
    now = time.monotonic()
    with _cache_lock:
        if _last_scan_mono is not None and now - _last_scan_mono < interval_sec:
            return _last_rows
    exe = which("adb")
    if exe is None:
        log.info("adb 不在 PATH（设备枚举诚实缺席，心跳不出 devices 键）")
        return None
    try:
        proc = subprocess.run([exe, "devices", "-l"], capture_output=True,
                              text=True, timeout=_SCAN_TIMEOUT_SEC)
    except (OSError, subprocess.SubprocessError) as e:
        log.warning("设备枚举命令异常（本轮缺席下轮再试）: %s", e)
        return None
    if proc.returncode != 0:
        log.warning("设备枚举非零退出 rc=%s（本轮缺席）: %s",
                    proc.returncode, (proc.stderr or "")[:200])
        return None
    rows = _to_facts(parse_adb_devices(proc.stdout))
    with _cache_lock:
        _last_scan_mono, _last_rows = time.monotonic(), rows
    return rows


def _reset_cache_for_tests() -> None:
    pass
    global _last_scan_mono, _last_rows
    with _cache_lock:
        _last_scan_mono, _last_rows = None, None
