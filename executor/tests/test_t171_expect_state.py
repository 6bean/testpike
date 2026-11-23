
from __future__ import annotations

import json
import os

from driver.webdriver import run_action_json

DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))

_FAST = {"assert-timeout-sec": 1}


def _doc(steps: list) -> str:
    return json.dumps({"version": "v2", "steps": steps}, ensure_ascii=False)


def _steps(expect_step: dict) -> list:
    return [{"action": "goto", "target": {"url": DEMO_URL}}, expect_step]


class TestExpectTopLevelState:
    pass

    def test_hidden_隐藏但存在元素_应通过(self):
        step = {"action": "expect", "target": {"css": "#welcome"}, "state": "hidden"}
        outcome = run_action_json(_doc(_steps(step)), headless=True)
        assert outcome.result == "pass", (
            f"hidden 应等元素不可见（display:none 合法），现被硬等 visible: "
            f"{outcome.error_detail}")

    def test_hidden_不存在元素_应通过(self):
        pass
        step = {"action": "expect",
                "target": {"css": ".error, #error, .error-message"},
                "state": "hidden"}
        outcome = run_action_json(_doc(_steps(step)), headless=True)
        assert outcome.result == "pass", (
            f"hidden 对不存在元素应立即过: {outcome.error_detail}")

    def test_hidden_可见元素_应失败(self):
        step = {"action": "expect", "target": {"css": "#login-btn"},
                "state": "hidden"}
        outcome = run_action_json(_doc(_steps(step)), headless=True,
                                  script_limits=_FAST)
        assert outcome.result == "fail", "可见元素断 hidden 必须超时判负"

    def test_attached_隐藏元素_应通过(self):
        step = {"action": "expect", "target": {"css": "#welcome"},
                "state": "attached"}
        outcome = run_action_json(_doc(_steps(step)), headless=True)
        assert outcome.result == "pass", f"attached 应过: {outcome.error_detail}"

    def test_缺省_隐藏元素_仍超时失败(self):
        pass
        step = {"action": "expect", "target": {"css": "#welcome"}}
        outcome = run_action_json(_doc(_steps(step)), headless=True,
                                  script_limits=_FAST)
        assert outcome.result == "fail", "缺省 visible 对 display:none 元素必须超时判负"

    def test_非法state_fail_fast人话点名(self):
        step = {"action": "expect", "target": {"css": "#welcome"},
                "state": "appears"}
        outcome = run_action_json(_doc(_steps(step)), headless=True)
        assert outcome.result == "fail", "非法 state 必须 fail-fast，不得回退 visible 静默执行"
        detail = outcome.error_detail or ""
        assert "hidden" in detail and "appears" in detail, (
            f"报错须人话含非法值与白名单: {detail}")


class TestExpectNestedState:
    pass

    def test_嵌套形_hidden_应通过(self):
        step = {"action": "expect", "expect": {"state": "hidden"},
                "target": {"css": "#welcome"}}
        outcome = run_action_json(_doc(_steps(step)), headless=True)
        assert outcome.result == "pass", (
            f"嵌套 expect.state=hidden 应过（f9 现场复刻）: {outcome.error_detail}")

    def test_嵌套形_非法值_应fail_fast(self):
        step = {"action": "expect", "expect": {"state": "gone"},
                "target": {"css": "#welcome"}}
        outcome = run_action_json(_doc(_steps(step)), headless=True)
        assert outcome.result == "fail", f"嵌套非法值必须 fail-fast: {outcome.error_detail}"
        assert "gone" in (outcome.error_detail or "")

    def test_嵌套形_expect对象非dict_不炸(self):
        pass
        step = {"action": "expect", "expect": "welcome",
                "target": {"css": "#login-btn"}}
        outcome = run_action_json(_doc(_steps(step)), headless=True)
        assert outcome.result == "pass", (
            f"非 dict 的 expect 键应被忽略走缺省 visible: {outcome.error_detail}")
