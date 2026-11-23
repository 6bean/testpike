
from __future__ import annotations

import json
import os

import pytest

from driver.condition_expr import ConditionError, evaluate_condition
from driver.webdriver import RunOutcome, run_action_json

DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))

_WELCOME = "欢迎，{}！登录成功"


def _doc(steps: list) -> str:
    return json.dumps({"version": "v2", "steps": steps}, ensure_ascii=False)


def _scope(**kw):
    return kw


class TestConditionExpr:
    pass

    def test_布尔字面量(self):
        assert evaluate_condition("true", {}) is True
        assert evaluate_condition("false", {}) is False

    def test_数值比较(self):
        assert evaluate_condition("3 > 2", {}) is True
        assert evaluate_condition("2 >= 3", {}) is False
        assert evaluate_condition("2 == 2", {}) is True
        assert evaluate_condition("2 != 2", {}) is False

    def test_字符串等值(self):
        assert evaluate_condition("'a' == 'a'", {}) is True
        assert evaluate_condition('"x" == "y"', {}) is False

    def test_点路径变量(self):
        scope = _scope(promo={"stackable": False})
        assert evaluate_condition("promo.stackable == false", scope) is True
        assert evaluate_condition("promo.stackable", scope) is False

    def test_逻辑组合与短路(self):
        scope = _scope(a=1, b="ok")
        assert evaluate_condition("a == 1 and b == 'ok'", scope) is True
        assert evaluate_condition("a == 2 or b == 'ok'", scope) is True
        assert evaluate_condition("not (a == 2)", scope) is True

    def test_括号与逻辑符号等价(self):
        scope = _scope(x=True)
        assert evaluate_condition("(x == true && 1 < 2) || false", scope) is True

    def test_未定义变量点名(self):
        with pytest.raises(ConditionError) as ei:
            evaluate_condition("foo == 1", {})
        assert "未定义变量" in str(ei.value) and "foo" in str(ei.value)

    def test_拒绝任意代码注入_禁eval(self):
        for evil in ("__import__('os').system('id')", "eval('1+1')",
                     "open('/etc/passwd').read()"):
            with pytest.raises(ConditionError):
                evaluate_condition(evil, {})

    def test_语法残缺拒绝(self):
        for bad in ("", "a ==", "== 1", "1 < < 2", "(1 == 1"):
            with pytest.raises(ConditionError):
                evaluate_condition(bad, {"a": 1, "1": 1})


class TestLoopExecution:

    def test_count展开_子步时间线与全局步号(self):
        doc = _doc([{"action": "goto", "target": {"url": DEMO_URL}},
                    {"action": "loop", "count": 3, "steps": [
                        {"action": "expect", "target": {"text": "测试门户登录"}}]}])
        out = run_action_json(doc, headless=True)
        assert out.result == "pass", out.error_detail
        assert len(out.steps_timeline) == 2, "loop 容器=1 个顶层步"
        top = out.steps_timeline[1]
        assert top["action"] == "loop" and top["status"] == "ok"
        its = top["children"]
        assert [it["iteration"] for it in its] == [1, 2, 3]
        assert [it["steps"][0]["stepNo"] for it in its] == [3, 4, 5], \
            "子步全局连续编号：goto=1、loop=2，子步 3/4/5"

    def test_items逐项绑定item变量驱动登录(self):
        steps = [{"action": "goto", "target": {"url": DEMO_URL}},
                 {"action": "loop", "items": ["tester01", "tester02"], "steps": [
                     {"action": "input", "target": {"css": "#username"},
                      "value": "{{item}}"},
                     {"action": "click", "target": {"css": "#login-btn"}},
                     {"action": "expect",
                      "target": {"text": _WELCOME.format("{{item}}")}}]}]
        out = run_action_json(_doc(steps), headless=True)
        assert out.result == "pass", out.error_detail
        loop_tl = out.steps_timeline[1]
        assert [it["iteration"] for it in loop_tl["children"]] == [1, 2]
        assert all(s["status"] == "ok"
                   for it in loop_tl["children"] for s in it["steps"])

    def test_自定义var名与index保留变量(self):
        steps = [{"action": "goto", "target": {"url": DEMO_URL}},
                 {"action": "loop", "count": 2, "var": "round", "steps": [
                     {"action": "input", "target": {"css": "#username"},
                      "value": "user-{{round}}"},
                     {"action": "click", "target": {"css": "#login-btn"}},
                     {"action": "expect",
                      "target": {"text": _WELCOME.format("user-{{round}}")}}]}]
        out = run_action_json(_doc(steps), headless=True)
        assert out.result == "pass", out.error_detail
        assert [it["iteration"] for it in
                out.steps_timeline[1]["children"]] == [1, 2]

    def test_items空数组_零迭代合法通过(self):
        out = run_action_json(_doc([{"action": "loop", "items": [],
                                     "steps": [{"action": "wait", "ms": 1}]}]),
                              headless=True)
        assert out.result == "pass", out.error_detail
        assert out.steps_timeline[0]["children"] == []
        assert "0 次" in (out.steps_timeline[0]["detail"] or "")

    def test_loop套loop至深度上限合法(self):
        doc = _doc([{"action": "goto", "target": {"url": DEMO_URL}},
                    {"action": "loop", "count": 2, "steps": [
                        {"action": "loop", "count": 1, "steps": [
                            {"action": "expect", "target": {"text": "测试门户登录"}}]}]}])
        out = run_action_json(doc, headless=True,
                              script_limits={"max-nest-depth": 5})
        assert out.result == "pass", out.error_detail
        outer = out.steps_timeline[1]["children"]
        assert [it["iteration"] for it in outer] == [1, 2]
        assert outer[0]["steps"][0]["action"] == "loop", "内层 loop 作为子步留痕"
        inner = outer[0]["steps"][0]["children"]
        assert inner[0]["steps"][0]["status"] == "ok"

    def test_超限嵌套执行前守卫拦_零执行(self):
        doc = _doc([{"action": "loop", "count": 1, "steps": [
            {"action": "if", "condition": "true", "steps": [
                {"action": "loop", "count": 1, "steps": [
                    {"action": "wait", "ms": 1}]}]}]}])
        out = run_action_json(doc, headless=True,
                              script_limits={"max-nest-depth": 2})
        assert "脚本嵌套超限：深度 4，上限 2" in (out.error_detail or "")
        assert out.steps_timeline == [], "守卫前置：一步都不跑"

    def test_loop子步失败_policy2立即中止(self):
        steps = [{"action": "goto", "target": {"url": DEMO_URL}},
                 {"action": "loop", "count": 3, "steps": [
                     {"action": "expect", "target": {"text": "不存在文本XYZ"}}]},
                 {"action": "expect", "target": {"text": "测试门户登录"}}]
        out = run_action_json(_doc(steps), headless=True,
                              script_limits={"assert-timeout-sec": 1,
                                             "fail-policy": 2})
        assert out.result == "fail"
        assert len(out.steps_timeline) == 2, "policy=2 中止：loop 后顶层步不执行"
        loop_tl = out.steps_timeline[1]
        assert loop_tl["status"] == "fail"
        assert len(loop_tl["children"]) == 1, "首个失败迭代即停，剩余迭代不跑"
        assert "迭代 1" in (loop_tl["detail"] or "")

    def test_loop子步失败_policy1继续留痕(self):
        steps = [{"action": "goto", "target": {"url": DEMO_URL}},
                 {"action": "loop", "count": 2, "steps": [
                     {"action": "expect", "target": {"text": "不存在文本XYZ"}}]},
                 {"action": "expect", "target": {"text": "测试门户登录"}}]
        out = run_action_json(_doc(steps), headless=True,
                              script_limits={"assert-timeout-sec": 1,
                                             "fail-policy": 1})
        assert out.result == "fail"
        assert len(out.steps_timeline) == 3, "policy=1 loop 失败后顶层步继续"
        loop_tl = out.steps_timeline[1]
        assert loop_tl["status"] == "fail"
        assert len(loop_tl["children"]) == 2, "两次迭代全留痕"
        assert all(it["steps"][0]["status"] == "fail"
                   for it in loop_tl["children"])
        assert out.steps_timeline[2]["status"] == "ok"

    def test_未定义迭代变量_fail_fast点名(self):
        doc = _doc([{"action": "loop", "count": 1, "steps": [
            {"action": "input", "target": {"css": "#username"},
             "value": "{{nope}}"}]}])
        out = run_action_json(doc, headless=True)
        assert out.result == "fail"
        assert "未定义迭代变量" in (out.error_detail or "") and "nope" in out.error_detail


class TestIfExecution:

    def _login_then(self, cond: str, then_steps: list, else_steps: list) -> list:
        return [{"action": "goto", "target": {"url": DEMO_URL}},
                {"action": "input", "target": {"css": "#username"},
                 "value": "tester01"},
                {"action": "click", "target": {"css": "#login-btn"}},
                {"action": "if", "condition": cond, "steps": then_steps,
                 "elseSteps": else_steps}]

    def test_真分支走steps_else合法跳过(self):
        out = run_action_json(_doc(self._login_then(
            "true", [{"action": "expect",
                      "target": {"text": _WELCOME.format("tester01")}}],
            [{"action": "expect", "target": {"text": "永不应命中AAA"}}])),
            headless=True, script_limits={"assert-timeout-sec": 1})
        assert out.result == "pass", out.error_detail
        if_tl = out.steps_timeline[3]
        assert if_tl["status"] == "ok"
        assert len(if_tl["children"]) == 1 and if_tl["children"][0]["branch"] == "then"
        assert if_tl["children"][0]["steps"][0]["status"] == "ok"

    def test_假分支走elseSteps(self):
        out = run_action_json(_doc(self._login_then(
            "false", [{"action": "expect", "target": {"text": "永不应命中AAA"}}],
            [{"action": "expect", "target": {"text": "测试门户登录"}}])),
            headless=True, script_limits={"assert-timeout-sec": 1})
        assert out.result == "pass", out.error_detail
        ch = out.steps_timeline[3]["children"]
        assert ch[0]["branch"] == "else"
        assert ch[0]["steps"][0]["action"] == "expect"

    def test_假分支无else_合法跳过非报错(self):
        steps = [{"action": "goto", "target": {"url": DEMO_URL}},
                 {"action": "if", "condition": "false",
                  "steps": [{"action": "expect",
                             "target": {"text": "永不应命中AAA"}}]}]
        out = run_action_json(_doc(steps), headless=True)
        assert out.result == "pass", "if 分支不存在=合法跳过（D-1.3），不判失败"
        if_tl = out.steps_timeline[1]
        assert if_tl["status"] == "skipped"
        assert "跳过" in (if_tl["detail"] or "")

    def test_条件未定义变量_步失败人话点名(self):
        steps = [{"action": "goto", "target": {"url": DEMO_URL}},
                 {"action": "if", "condition": "nope == 1",
                  "steps": [{"action": "wait", "ms": 1}]}]
        out = run_action_json(_doc(steps), headless=True)
        assert out.result == "fail"
        assert "未定义变量" in (out.error_detail or "") and "nope" in out.error_detail

    def test_loop内if_数据驱动双路(self):
        steps = [{"action": "goto", "target": {"url": DEMO_URL}},
                 {"action": "loop", "items": ["tester01", "badname"], "steps": [
                     {"action": "input", "target": {"css": "#username"},
                      "value": "{{item}}"},
                     {"action": "click", "target": {"css": "#login-btn"}},
                     {"action": "if", "condition": "item == 'tester01'",
                      "steps": [{"action": "expect",
                                 "target": {"text": _WELCOME.format("{{item}}")}}],
                      "elseSteps": [{"action": "expect",
                                     "target": {"text": _WELCOME.format("badname")}}]}]}]
        out = run_action_json(_doc(steps), headless=True,
                              script_limits={"assert-timeout-sec": 2})
        assert out.result == "pass", out.error_detail
        its = out.steps_timeline[1]["children"]
        br = [it["steps"][2]["children"][0]["branch"] for it in its]
        assert br == ["then", "else"], f"双路互嵌留痕: {br}"


class TestRunReport:

    def test_报告含loopCount与迭代展开数(self):
        doc = _doc([{"action": "loop", "count": 2, "steps": [
                    {"action": "loop", "count": 1, "steps": [
                        {"action": "wait", "ms": 1}]}]}])
        out = run_action_json(doc, headless=True)
        assert out.result == "pass", out.error_detail
        assert out.loop_count == 2, "静态 loop 节点数（script_limits 同口径）"
        assert out.loop_iterations == 4, "实际展开：外层 2 次 + 内层 2×1=2 次，共 4"

    def test_结果字典序列化往返保留children与计数(self):
        doc = _doc([{"action": "loop", "count": 1, "steps": [
            {"action": "wait", "ms": 1}]}])
        out = run_action_json(doc, headless=True)
        restored = RunOutcome.from_dict(json.loads(json.dumps(out.to_dict())))
        assert restored.loop_count == out.loop_count
        assert restored.loop_iterations == out.loop_iterations
        assert restored.steps_timeline[0]["children"][0]["iteration"] == 1
