
from __future__ import annotations

import fnmatch
import re
from typing import Any, Dict, Optional

from driver.expect_match import ExpectMatchError

HTTP_METHODS = frozenset(
    {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"})
ASSERT_WINDOWS = ("sinceLast", "full")
_GLOB_REJECTED = re.compile(r"[\[({\\]")


class NetAssertError(ValueError):
    pass


def validate_net_assert_step(step: Dict[str, Any]) -> Dict[str, Any]:
    pass
    match = step.get("match")
    if not isinstance(match, dict) or not str(match.get("urlPattern") or "").strip():
        raise NetAssertError(
            "net.assert 必须给定 match（含 urlPattern，例 "
            '{"urlPattern":"*/api/v1/orders*"}），当前缺失或为空')
    pattern = str(match["urlPattern"])
    bad = _GLOB_REJECTED.search(pattern)
    if bad:
        raise NetAssertError(
            f"match.urlPattern 仅支持 glob 通配 * 与 ?（不允许 {bad.group(0)} "
            f"等正则/字符类元字符），当前值「{pattern}」")
    method = match.get("method")
    method_norm: Optional[str] = None
    if method is not None and not isinstance(method, bool):
        method_norm = str(method).upper()
        if method_norm not in HTTP_METHODS:
            raise NetAssertError(
                f"net.assert 的 match.method 不是合法 HTTP 动词，当前值「{method}」")
    window = step.get("window")
    window_norm = "sinceLast"
    if window is not None and not isinstance(window, bool):
        window_norm = str(window)
        if window_norm not in ASSERT_WINDOWS:
            raise NetAssertError(
                "net.assert 的 window 仅支持 "
                + " / ".join(ASSERT_WINDOWS)
                + f"，当前值「{window}」")
    expect = step.get("expect")
    if expect is not None and not isinstance(expect, dict):
        raise NetAssertError("net.assert 的 expect 必须是对象（status / body）")
    return {"pattern": pattern,
            "method": method_norm,
            "window": window_norm,
            "absent": bool(step.get("absent")),
            "expect": expect or {}}


class NetObserver:
    pass

    def __init__(self, page) -> None:
        self.entries: list = []
        self.hits: list = []
        self._cursor = 0
        page.on("response", self._on_response)

    def _on_response(self, response) -> None:
        try:
            self.entries.append({
                "url": response.url,
                "method": str(response.request.method).upper(),
                "status": response.status,
                "durationMs": self._timing_ms(response),
                # Lowercased response-header snapshot for expect.headers judgement
                # (HTTP header names are case-insensitive); captured immediately —
                # reading later would race with the observation entry recycling.
                "responseHeaders": {k.lower(): v
                                    for k, v in (response.headers or {}).items()},
                "response": response,
            })
        except Exception:
            return

    @staticmethod
    def _timing_ms(response) -> Optional[int]:
        try:
            timing = response.request.timing
            end, start = timing.get("responseEnd"), timing.get("start")
            if end is None or start is None or end < 0 or start < 0:
                return None
            return int(end - start)
        except Exception:  # noqa: BLE001
            return None

    def matches_in_window(self, spec: Dict[str, Any]) -> list:
        pass
        src = (self.entries if spec["window"] == "full"
               else self.entries[self._cursor:])
        return [e for e in src
                if fnmatch.fnmatchcase(e["url"], spec["pattern"])
                and (spec["method"] is None or e["method"] == spec["method"])]

    def consume(self, spec: Dict[str, Any], step_no: int) -> list:
        pass
        found = self.matches_in_window(spec)
        for e in found:
            self.hits.append({"url": e["url"], "method": e["method"],
                              "status": e["status"], "stepNo": step_no,
                              "durationMs": e.get("durationMs")})
        self._cursor = len(self.entries)
        return found

    @staticmethod
    def describe(entry: Dict[str, Any]) -> str:
        return f"{entry['method']} {entry['url']} → {entry['status']}"


def assert_net(spec: Dict[str, Any], found: list, judge) -> str:
    pass
    pattern, method = spec["pattern"], spec["method"]
    mdesc = f"（method={method}）" if method else ""
    if spec["absent"]:
        if found:
            raise ExpectMatchError(
                f"断言不满足：不该发的请求被发出——urlPattern「{pattern}」{mdesc}"
                f" 窗口内命中 {len(found)} 次（首个：{NetObserver.describe(found[0])}）")
        return f"net.assert absent 通过：未观测到不该发的请求（urlPattern={pattern}）"
    if not found:
        raise ExpectMatchError(
            f"断言不满足：窗口内未观测到匹配请求（urlPattern「{pattern}」{mdesc}）"
            "——异步请求需显式 wait/http.poll 步前置（13 §10 net=即时一次判定）")
    first = found[0]
    expect = spec["expect"]
    if expect:
        text = ""
        if expect.get("body"):
            try:
                text = first["response"].text()
            except Exception as e:
                raise NetAssertError(
                    f"net.assert 响应体读取失败（{first['url']}）：{e}"
                    "——判定过晚，页面响应已被回收") from None
        # durationMs/headers observations ride the same judge call (single
        # implementation shared with http.request — no second spec)
        judge(expect, first["status"], first["url"], text, first["method"],
              duration_ms=first.get("durationMs"),
              response_headers=first.get("responseHeaders") or {})
    return (f"net.assert 命中 {len(found)} 次：{NetObserver.describe(first)}"
            + ("；expect 判定通过" if expect else ""))
