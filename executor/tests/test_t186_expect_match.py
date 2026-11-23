
from __future__ import annotations

import json
import os

from driver.webdriver import run_action_json

B10_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "b10_page.html"))

_FAST = {"assert-timeout-sec": 1}
_SLOW = {"assert-timeout-sec": 3}


def _doc(steps: list) -> str:
    return json.dumps({"version": "v2", "steps": steps}, ensure_ascii=False)


def _steps(expect_step: dict) -> list:
    return [{"action": "goto", "target": {"url": B10_URL}}, expect_step]


def _range(css: str, **kw) -> dict:
    step = {"action": "expect", "target": {"css": css}, "matchType": "range"}
    step.update(kw)
    return step


def _regex(css: str, pattern: str) -> dict:
    return {"action": "expect", "target": {"css": css},
            "matchType": "regex", "pattern": pattern}


def _run(expect_step: dict, limits: dict = _FAST):
    return run_action_json(_doc(_steps(expect_step)), headless=True,
                           script_limits=limits)


class TestRangeFourStates:
    pass

    def test_区间内_通过(self):
        outcome = _run(_range("#amt-pass", min=100, max=200, bounds="open"))
        assert outcome.result == "pass", outcome.error_detail

    def test_低于下界_失败带实际值与区间(self):
        outcome = _run(_range("td#amt-low", min=100, max=200, bounds="open"))
        assert outcome.result == "fail"
        detail = outcome.error_detail or ""
        assert "¥96.00" in detail, f"须带实际值原文: {detail}"
        assert "(100,200)" in detail, f"须人话含区间: {detail}"
        assert outcome.error_type == 1, f"断言不满足须归产品缺陷: {outcome.error_type}"

    def test_开边界上界_失败(self):
        outcome = _run(_range("td#amt-bound", min=100, max=200, bounds="open"))
        assert outcome.result == "fail", "200 不在开区间 (100,200)"

    def test_闭边界上界_通过(self):
        outcome = _run(_range("td#amt-bound", min=100, max=200,
                              bounds="closed"))
        assert outcome.result == "pass", outcome.error_detail

    def test_左开右闭_下界失败上界通过(self):
        lo = _run(_range("td#amt-low", min=100, max=200, bounds="left-open"))
        hi = _run(_range("td#amt-bound", min=100, max=200, bounds="left-open"))
        assert lo.result == "fail"
        assert hi.result == "pass", hi.error_detail

    def test_右开_内值通过双界失败(self):
        pass
        bad_lo = _run(_range("td#amt-low", min=100, max=200, bounds="right-open"))
        bad_hi = _run(_range("td#amt-bound", min=100, max=200,
                             bounds="right-open"))
        ok = _run(_range("#amt-pass", min=100, max=200, bounds="right-open"))
        assert bad_lo.result == "fail"
        assert bad_hi.result == "fail", "200 在右开 [100,200) 必须失败"
        assert ok.result == "pass", ok.error_detail

    def test_仅min_单端判定(self):
        hi = _run(_range("#amt-pass", min=100))
        lo = _run(_range("td#amt-low", min=100))
        assert hi.result == "pass", hi.error_detail
        assert lo.result == "fail"
        assert "[100" in (lo.error_detail or "") or "100" in (lo.error_detail or "")

    def test_仅max_单端判定(self):
        hi = _run(_range("#amt-pass", max=200))
        lo = _run(_range("td#amt-bound", max=200, bounds="open"))
        assert hi.result == "pass", hi.error_detail
        assert lo.result == "fail"

    def test_非数值文本_fail带原文_禁捞数字(self):
        pass
        outcome = _run(_range("td#amt-nonnum", min=100, max=200))
        assert outcome.result == "fail"
        detail = outcome.error_detail or ""
        assert "￥1,2O元" in detail, f"失败须带清洗前原文: {detail}"


class TestRegex:
    pass

    def test_锚定正则_命中通过(self):
        outcome = _run(_regex("#code-ok", r"^ORD-\d{6}$"))
        assert outcome.result == "pass", outcome.error_detail

    def test_锚定正则_不命中失败带实际值(self):
        outcome = _run(_regex("#code-bad", r"^ORD-\d{6}$"))
        assert outcome.result == "fail"
        detail = outcome.error_detail or ""
        assert "ORD-12a456" in detail, f"须带实际值: {detail}"
        assert r"^ORD-\d{6}$" in detail, f"须含 pattern: {detail}"

    def test_非锚定子串_命中通过(self):
        outcome = _run(_regex("#code-ok", r"\d{3}"))
        assert outcome.result == "pass", outcome.error_detail


class TestNoPolling:
    pass

    def test_range_异步刷新值_不轮询判首读(self):
        outcome = _run(_range("#late-amt", min=100, max=200,
                              bounds="open"), limits=_SLOW)
        assert outcome.result == "fail", (
            "range 必须元素出现即判一次（读 96），轮询到 150=违契约")
        assert "96" in (outcome.error_detail or "")

    def test_regex_异步刷新值_不轮询判首读(self):
        outcome = _run(_regex("#late-amt", r"^ORD-\d{6}$"), limits=_SLOW)
        assert outcome.result == "fail", "regex 同样不轮询取值"


class TestMatchTypeFailFast:
    def test_非法matchType_点名白名单(self):
        step = {"action": "expect", "target": {"css": "#amt-pass"},
                "matchType": "fuzzy"}
        outcome = _run(step)
        assert outcome.result == "fail", "非法 matchType 必须 fail-fast 禁静默回退"
        detail = outcome.error_detail or ""
        assert "fuzzy" in detail and "range" in detail, (
            f"报错须含非法值与白名单: {detail}")

    def test_range_缺双端_fail_fast(self):
        pass
        outcome = _run({"action": "expect", "target": {"css": "#amt-pass"},
                        "matchType": "range"})
        assert outcome.result == "fail"
        assert "min" in (outcome.error_detail or "")

    def test_regex_缺pattern_fail_fast(self):
        outcome = _run({"action": "expect", "target": {"css": "#code-ok"},
                        "matchType": "regex"})
        assert outcome.result == "fail"
        assert "pattern" in (outcome.error_detail or "")

    def test_regex_非法正则本体_转fail不抛栈(self):
        pass
        outcome = _run(_regex("#code-ok", r"(a+)+$["))
        assert outcome.result == "fail"
        assert "Traceback" not in (outcome.error_detail or "")


class TestLegacyZeroDrift:
    pass

    def test_无matchType_text_contains_仍子串匹配(self):
        step = {"action": "expect", "text": "150.0"}
        outcome = _run(step)
        assert outcome.result == "pass", outcome.error_detail

    def test_matchType_contains_显式_同默认(self):
        step = {"action": "expect", "text": "ORD-123456",
                "matchType": "contains"}
        outcome = _run(step)
        assert outcome.result == "pass", outcome.error_detail

    def test_matchType_exact_true_精确匹配(self):
        step = {"action": "expect", "text": "¥150.00", "matchType": "exact"}
        outcome = _run(step)
        assert outcome.result == "pass", outcome.error_detail

    def test_matchType_exact_子串不相等_失败(self):
        step = {"action": "expect", "text": "150", "matchType": "exact"}
        outcome = _run(step, limits=_FAST)
        assert outcome.result == "fail"


class TestStripMatrix:
    pass

    @staticmethod
    def _parse(raw: str, strip=None):
        from driver.expect_match import parse_range_number
        return (parse_range_number(raw) if strip is None
                else parse_range_number(raw, strip))

    def test_默认集清洗(self):
        assert self._parse("¥1,234.50 元") == 1234.50
        assert self._parse("￥200") == 200.0
        assert self._parse("-50") == -50.0
        assert self._parse("1 234") == 1234.0
        assert self._parse("$1,000") == 1000.0

    def test_非数值返回None禁抛栈(self):
        assert self._parse("￥1,2O元") is None
        assert self._parse("1.2.3") is None
        assert self._parse("") is None
        assert self._parse("abc") is None

    def test_科学计数法判定确定(self):
        assert self._parse("1e3") == 1000.0

    def test_自定义strip集(self):
        assert self._parse("12px") is None
        assert self._parse("12px", strip="px") == 12.0
