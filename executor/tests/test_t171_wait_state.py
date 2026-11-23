
from __future__ import annotations

import json
import os

from driver.webdriver import run_action_json

DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))

_FAST = {"assert-timeout-sec": 1}


def _doc(steps: list) -> str:
    return json.dumps({"version": "v2", "steps": steps}, ensure_ascii=False)


def _wait_step(**extra) -> list:
    pass
    step = {"action": "wait", "target": {"css": "#welcome"}}
    step.update(extra)
    return [{"action": "goto", "target": {"url": DEMO_URL}}, step]


class TestWaitState:
    pass

    def test_attached_隐藏但存在元素_应通过(self):
        pass
        outcome = run_action_json(_doc(_wait_step(state="attached")), headless=True)
        assert outcome.result == "pass", (
            f"attached 应等元素挂 DOM（隐藏元素合法），现被硬等 visible: {outcome.error_detail}")

    def test_hidden_隐藏元素_应通过(self):
        outcome = run_action_json(_doc(_wait_step(state="hidden")), headless=True)
        assert outcome.result == "pass", f"hidden 应过: {outcome.error_detail}"

    def test_detached_不存在元素_应通过(self):
        step = {"action": "wait", "target": {"css": "#not-exist-elem"},
                "state": "detached"}
        steps = [{"action": "goto", "target": {"url": DEMO_URL}}, step]
        outcome = run_action_json(_doc(steps), headless=True)
        assert outcome.result == "pass", f"detached 不存在元素应立即过: {outcome.error_detail}"

    def test_visible_隐藏元素_仍超时失败(self):
        pass
        outcome = run_action_json(_doc(_wait_step()), headless=True,
                                  script_limits=_FAST)
        assert outcome.result == "fail", "缺省 visible 对 display:none 元素必须超时判负"

    def test_visible_显式_登录按钮_应通过(self):
        outcome = run_action_json(_doc(_wait_step(state="visible",
                                                  target={"css": "#login-btn"})),
                                  headless=True)
        assert outcome.result == "pass", f"显式 visible 可见元素应过: {outcome.error_detail}"

    def test_非法state_fail_fast人话点名(self):
        pass
        outcome = run_action_json(_doc(_wait_step(state="appears")), headless=True)
        assert outcome.result == "fail", "非法 state 必须 fail-fast，不得回退 visible 静默执行"
        detail = outcome.error_detail or ""
        assert "attached" in detail and "appears" in detail, (
            f"报错须人话含非法值与白名单: {detail}")

    def test_ms_定时等待分支零漂移(self):
        pass
        steps = [{"action": "goto", "target": {"url": DEMO_URL}},
                 {"action": "wait", "ms": 50, "state": "appears-invalid-ignored"}]
        outcome = run_action_json(_doc(steps), headless=True)
        assert outcome.result == "pass", f"ms 分支应忽略 state 直接定时: {outcome.error_detail}"
