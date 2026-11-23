
from __future__ import annotations

import json
import os
import tempfile

from driver.script_limits import ScriptLimits, count_script_tree
from driver.webdriver import run_action_json

DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))


def _steps_doc(steps: list) -> str:
    return json.dumps({"version": "v2", "steps": steps}, ensure_ascii=False)


def _login_steps() -> list:
    pass
    return [
        {"action": "input", "target": {"css": "#username"}, "value": "tester01"},
        {"action": "input", "target": {"css": "#password"}, "value": "Test@123"},
        {"action": "click", "target": {"css": "#login-btn"}},
        {"action": "expect", "target": {"text": "欢迎，tester01！登录成功"}},
    ]


class TestCountScriptTree纯函数:
    pass

    def _count(self, doc: dict):
        return count_script_tree(doc)

    def test_线性步骤计数与深度1(self):
        nodes, loops, depth = self._count(
            {"steps": [{"action": "click"}, {"action": "input"}, {"action": "expect"}]})
        assert (nodes, loops, depth) == (3, 0, 1)

    def test_setup不计入步骤数(self):
        nodes, _, _ = self._count(
            {"setup": [{"action": "goto"}], "steps": [{"action": "click"}]})
        assert nodes == 1, "04 契约 setup 前置不计入 max-steps（ScriptLimitGuard 同口径）"

    def test_loop容器计数嵌套深度递归(self):
        doc = {"steps": [
            {"action": "click"},
            {"action": "loop", "count": 2, "steps": [
                {"action": "input"},
                {"action": "if", "condition": "x", "steps": [{"action": "expect"}],
                 "elseSteps": [{"action": "wait", "ms": 1}]}]}]}
        nodes, loops, depth = self._count(doc)
        assert nodes == 6, "click+loop+input+if+expect+wait 全节点计数（core Guard 同口径）"
        assert loops == 1
        assert depth == 3, "顶层1 → loop内2 → if内3"


class TestScriptLimits解析:
    pass

    def test_缺省回退现基线(self):
        lim = ScriptLimits.from_payload(None)
        assert lim.assert_timeout_ms == 15_000, "旧行为 STEP_TIMEOUT_MS=15s"
        assert lim.screenshot_mode == 1, "旧行为=每步一张"
        assert lim.fail_policy == 2, "旧行为=失败立即中止"
        assert lim.check_violation({"steps": [{"action": "click"}] * 999}) is None, \
            "缺省=无上限（守卫不拦，与旧 core 下发零漂移）"

    def test_部分键缺失逐键回退(self):
        lim = ScriptLimits.from_payload({"screenshot-mode": 3})
        assert lim.screenshot_mode == 3
        assert lim.fail_policy == 2 and lim.assert_timeout_ms == 15_000

    def test_非数字值容错回退(self):
        lim = ScriptLimits.from_payload({"assert-timeout-sec": "abc", "max-steps": None})
        assert lim.assert_timeout_ms == 15_000
        assert lim.check_violation({"steps": [{"action": "click"}] * 999}) is None


class Test超限守卫:
    pass

    def test_步骤超限_人话含实际数与上限_零执行(self):
        action = _steps_doc(_login_steps())
        with tempfile.TemporaryDirectory() as shots:
            outcome = run_action_json(action, headless=True, screenshots_dir=shots,
                                      script_limits={"max-steps": 3})
        assert outcome.result == "fail"
        assert "脚本步骤超限：4 步，上限 3" in (outcome.error_detail or ""), outcome.error_detail
        assert "ai.script.max-steps" in outcome.error_detail
        assert outcome.steps_timeline == [], "守卫在执行前拦截，一步都不跑"

    def test_loop超限与嵌套超限(self):
        doc = _steps_doc([{"action": "loop", "count": 1, "steps": [{"action": "wait", "ms": 1}]}])
        outcome = run_action_json(doc, headless=True, script_limits={"max-loop": 0})
        assert "脚本循环超限：1 个 loop，上限 0" in (outcome.error_detail or "")
        deep = {"steps": [{"action": "loop", "steps": [
            {"action": "if", "condition": "a", "steps": [{"action": "wait", "ms": 1}]}]}]}
        outcome2 = run_action_json(json.dumps(deep), headless=True,
                                   script_limits={"max-nest-depth": 2})
        assert "脚本嵌套超限：深度 3，上限 2" in (outcome2.error_detail or "")

    def test_合法脚本守卫零拦_线性真执行通过(self):
        pass
        steps = [{"action": "goto", "target": {"url": DEMO_URL}}] + _login_steps()
        outcome = run_action_json(_steps_doc(steps), headless=True,
                                  script_limits={"max-steps": 50, "max-loop": 20,
                                                 "max-nest-depth": 5})
        assert outcome.result == "pass", f"合法脚本应跑通: {outcome.error_detail}"
        assert len(outcome.steps_timeline) == len(steps)

    def test_loop脚本守卫放行_不因限额拦截(self):
        pass
        doc = _steps_doc([{"action": "loop", "count": 2,
                           "steps": [{"action": "wait", "ms": 1}]}])
        outcome = run_action_json(doc, headless=True,
                                  script_limits={"max-steps": 50, "max-loop": 20,
                                                 "max-nest-depth": 5})
        detail = outcome.error_detail or ""
        assert "超限" not in detail, f"守卫不应拦截限内脚本: {detail}"


class Test运行时三键消费:
    pass

    def _failing_steps(self) -> list:
        pass
        return [
            {"action": "goto", "target": {"url": DEMO_URL}},
            {"action": "expect", "target": {"text": "不存在的文本XYZ"}},
            {"action": "expect", "target": {"text": "测试门户登录"}},
            {"action": "expect", "target": {"text": "登 录"}},
        ]

    def test_fail_policy1_继续后续步骤全记录(self):
        pass
        with tempfile.TemporaryDirectory():
            outcome = run_action_json(_steps_doc(self._failing_steps()), headless=True,
                                      script_limits={"fail-policy": 1})
        tl = outcome.steps_timeline
        assert len(tl) == 4, "policy=1 失败步后续步骤继续执行并记录"
        assert [s["status"] for s in tl] == ["ok", "fail", "ok", "ok"]
        assert outcome.result == "fail"
        assert "失败 1 步" in (outcome.error_detail or "")
        assert "步骤 2" in outcome.error_detail, "首个失败步人话点名"

    def test_fail_policy2_立即中止(self):
        outcome = run_action_json(_steps_doc(self._failing_steps()), headless=True,
                                  script_limits={"fail-policy": 2})
        assert len(outcome.steps_timeline) == 2, "policy=2 失败即停（旧行为）"
        assert outcome.steps_timeline[-1]["status"] == "fail"
        assert outcome.error_type == 2, "定位/断言失败归 2（TC-EXEC-05 口径）"

    def test_缺省_失败即停零漂移(self):
        pass
        outcome = run_action_json(_steps_doc(self._failing_steps()), headless=True)
        assert len(outcome.steps_timeline) == 2

    def test_screenshot_mode3_仅失败截(self):
        steps = [{"action": "goto", "target": {"url": DEMO_URL}}] + _login_steps()
        with tempfile.TemporaryDirectory() as shots:
            run_action_json(_steps_doc(steps), headless=True, screenshots_dir=shots,
                            script_limits={"screenshot-mode": 3})
            assert os.listdir(shots) == [], "全通过任务在 mode=3 下零截图"
        with tempfile.TemporaryDirectory() as shots:
            run_action_json(_steps_doc(self._failing_steps()), headless=True,
                            screenshots_dir=shots,
                            script_limits={"screenshot-mode": 3, "fail-policy": 1})
            assert len(os.listdir(shots)) == 1, "mode=3 仅失败步一张"

    def test_screenshot_mode2_关键步(self):
        steps = [{"action": "goto", "target": {"url": DEMO_URL}}] + _login_steps()
        with tempfile.TemporaryDirectory() as shots:
            run_action_json(_steps_doc(steps), headless=True, screenshots_dir=shots,
                            script_limits={"screenshot-mode": 2})
            files = os.listdir(shots)
        assert len(files) == 1 and "expect" in files[0], \
            f"mode=2 关键步=expect 断言步截图: {files}"

    def test_screenshot_mode1_每步现行为(self):
        steps = [{"action": "goto", "target": {"url": DEMO_URL}}] + _login_steps()
        with tempfile.TemporaryDirectory() as shots:
            run_action_json(_steps_doc(steps), headless=True, screenshots_dir=shots,
                            script_limits={"screenshot-mode": 1})
            assert len(os.listdir(shots)) == 5, "每步一张（存量基线）"

    def test_assert_timeout_sec_1s_快速失败(self):
        pass
        steps = [{"action": "goto", "target": {"url": DEMO_URL}},
                 {"action": "expect", "target": {"text": "不存在ABC"}}]
        outcome = run_action_json(_steps_doc(steps), headless=True,
                                  script_limits={"assert-timeout-sec": 1})
        assert outcome.result == "fail"
        assert outcome.duration_ms < 8_000, \
            f"1s 断言超时应快速失败，实际 {outcome.duration_ms}ms（未生效则 ~15s）"
