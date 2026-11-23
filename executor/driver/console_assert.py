
from __future__ import annotations

import fnmatch
from typing import Any, Dict, List, Optional

from driver.expect_match import ExpectMatchError
from driver.net_observer import _GLOB_REJECTED

CONSOLE_MODES = frozenset({"fail", "warn", "collect"})
ASSERT_WINDOWS = ("sinceLast", "full")
CONSOLE_LEVELS = frozenset({"error", "warning", "log", "info", "debug"})

DETAIL_MAX_HITS = 3
DETAIL_MAX_BYTES = 512


class ConsoleAssertError(ValueError):
    pass


def validate_console_assert_step(step: Dict[str, Any]) -> Dict[str, Any]:
    pass
    mode = step.get("mode")
    if mode is not None and not isinstance(mode, bool):
        if str(mode) not in CONSOLE_MODES:
            raise ConsoleAssertError(
                f"console.assert 的 mode 仅支持 fail / warn / collect，当前值「{mode}」")
    scope = step.get("scope")
    scope_norm = "sinceLast"
    if scope is not None and not isinstance(scope, bool):
        if str(scope) not in ASSERT_WINDOWS:
            raise ConsoleAssertError(
                "console.assert 的 scope 仅支持 sinceLast / full，当前值「"
                + str(scope) + "」")
        scope_norm = str(scope)
    level = step.get("level")
    level_norm: List[str] = ["error"]
    if level is not None:
        if not isinstance(level, list) or not level:
            raise ConsoleAssertError(
                'console.assert 的 level 必须是非空数组（例 ["error"]）')
        for item in level:
            if str(item) not in CONSOLE_LEVELS:
                raise ConsoleAssertError(
                    "console.assert 的 level 含非法值「" + str(item)
                    + "」（仅 error / warning / log / info / debug）")
        level_norm = [str(x) for x in level]
    ignore = step.get("ignorePatterns")
    ignore_norm: List[str] = []
    if ignore is not None:
        if not isinstance(ignore, list):
            raise ConsoleAssertError("console.assert 的 ignorePatterns 必须是字符串数组")
        for item in ignore:
            pattern = str(item)
            bad = _GLOB_REJECTED.search(pattern)
            if bad:
                raise ConsoleAssertError(
                    f"ignorePatterns 仅支持 glob 通配 * 与 ?（不允许 {bad.group(0)} "
                    f"等正则/字符类元字符），当前值「{pattern}」")
            ignore_norm.append(pattern)
    return {"mode": (None if mode is None or isinstance(mode, bool) else str(mode)),
            "scope": scope_norm,
            "level": level_norm,
            "ignore": ignore_norm}


def _truncate(text: str, limit: int = DETAIL_MAX_BYTES) -> str:
    pass
    raw = text.encode("utf-8")
    if len(raw) <= limit:
        return text
    return raw[:limit].decode("utf-8", errors="ignore") + "…（已截断）"


class ConsoleObserver:
    pass

    def __init__(self) -> None:
        self.lines: list = []
        self.entries: list = []
        self.violations: list = []
        self._cursor = 0

    def record(self, msg) -> None:
        try:
            level, text = str(msg.type), str(msg.text)
        except Exception:
            return
        self.lines.append(f"[{level}] {text}")
        self.entries.append({"level": level, "text": text})


def _hit(entry: Dict[str, Any], spec: Dict[str, Any]) -> bool:
    if entry["level"] not in spec["level"]:
        return False
    return not any(fnmatch.fnmatchcase(entry["text"], p) for p in spec["ignore"])


def assert_console(observer: ConsoleObserver, spec: Dict[str, Any],
                   step_no: int, policy_mode: Optional[str]) -> str:
    pass
    effective = spec["mode"] or policy_mode or "fail"
    src = (observer.entries if spec["scope"] == "full"
           else observer.entries[observer._cursor:])
    hits = [e for e in src if _hit(e, spec)]
    observer._cursor = len(observer.entries)
    for h in hits[:max(20 - len(observer.violations), 0)]:
        observer.violations.append({"stepNo": step_no, "level": h["level"],
                                    "text": _truncate(h["text"])})
    if effective != "fail":
        label = {"off": "已停用（exec.console-assert.mode=off）",
                 "warn": "告警档（仅记录不判败）",
                 "collect": "收集档（仅记录不判败）"}.get(effective, "仅记录不判败")
        return (f"console.assert {label}：窗口命中 {len(hits)} 条"
                f"（level={'+'.join(spec['level'])}）")
    if hits:
        preview = "；".join(f"[{h['level']}] {h['text']}" for h in hits[:DETAIL_MAX_HITS])
        raise ExpectMatchError(
            f"断言不满足：console 窗口内命中 {len(hits)} 条"
            f"「{'+'.join(spec['level'])}」（ignorePatterns 豁免后，前 "
            f"{min(len(hits), DETAIL_MAX_HITS)} 条：{_truncate(preview)}）")
    return (f"console.assert 通过：窗口内无未豁免的"
            f"「{'+'.join(spec['level'])}」消息")
