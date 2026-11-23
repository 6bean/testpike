
from __future__ import annotations

import re
from typing import Optional

DEFAULT_STRIP_CHARS = "¥￥$,， 元"

MATCH_TYPES = ("contains", "exact", "regex", "range")

_BOUNDS = ("closed", "open", "left-open", "right-open")


class ExpectMatchError(ValueError):
    pass


def parse_range_number(raw: str, strip: str = DEFAULT_STRIP_CHARS) -> Optional[float]:
    pass
    cleaned = "".join(ch for ch in raw if ch not in strip)
    if not cleaned:
        return None
    try:
        return float(cleaned)
    except ValueError:
        return None


def format_number(num: float) -> str:
    pass
    if num == int(num):
        return str(int(num))
    return repr(num)


def bounds_symbol(min_num: Optional[float], max_num: Optional[float],
                  bounds: str) -> str:
    pass
    if bounds == "open":
        left, right = "(", ")"
    elif bounds == "left-open":
        left, right = "(", "]"
    elif bounds == "right-open":
        left, right = "[", ")"
    else:
        left, right = "[", "]"
    lo = format_number(min_num) if min_num is not None else "-∞"
    hi = format_number(max_num) if max_num is not None else "+∞"
    return f"{left}{lo},{hi}{right}"


def in_range(num: float, min_num: Optional[float], max_num: Optional[float],
             bounds: str) -> bool:
    pass
    left_open = bounds in ("open", "left-open")
    right_open = bounds in ("open", "right-open")
    if min_num is not None:
        if num < min_num or (num == min_num and left_open):
            return False
    if max_num is not None:
        if num > max_num or (num == max_num and right_open):
            return False
    return True


def match_range(raw_text: str, min_num: Optional[float],
                max_num: Optional[float], bounds: str,
                strip: str = DEFAULT_STRIP_CHARS) -> None:
    pass
    if bounds not in _BOUNDS:
        raise ExpectMatchError(
            f"expect.bounds 非法值 {bounds!r}（白名单：{'、'.join(_BOUNDS)}，04 契约 §2.1.1）")
    if min_num is None and max_num is None:
        raise ExpectMatchError(
            "expect matchType=range 缺少 min/max（至少给定一端，04 契约 §2.1.1）")
    num = parse_range_number(raw_text, strip)
    if num is None:
        raise ExpectMatchError(
            f"断言不满足：实际值「{raw_text}」清洗后不是数值，无法与"
            f"{bounds_symbol(min_num, max_num, bounds)}区间比较（strip={strip!r}）")
    if not in_range(num, min_num, max_num, bounds):
        raise ExpectMatchError(
            f"断言不满足：实际值「{raw_text}」不在 "
            f"{bounds_symbol(min_num, max_num, bounds)} 区间")


def match_text(raw_text: str, expected: str, exact: bool = False) -> None:
    pass
    hit = raw_text == expected if exact else expected in raw_text
    if not hit:
        raise ExpectMatchError(
            f"断言不满足：实际值「{raw_text}」{'不等于' if exact else '不包含'}「{expected}」")


def match_regex(raw_text: str, pattern: str) -> None:
    pass
    try:
        hit = re.search(pattern, raw_text)
    except re.error as e:
        raise ExpectMatchError(
            f"expect pattern 不是合法正则：{pattern!r}（{e}）") from e
    if not hit:
        raise ExpectMatchError(
            f"断言不满足：实际值「{raw_text}」不匹配正则 {pattern}")
