

import json
import logging
import os
import unittest

from driver.env_resolver import (
    EnvPlaceholderError,
    VarStore,
    VarUndefinedError,
    resolve_placeholders,
    resolve_vars,
)
from driver.webdriver import WebDriverExecutor, run_action_json

DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))


class TestVarStore域(unittest.TestCase):
    pass

    def test_set后可读取(self):
        store = VarStore()
        store.set("order.id", "A42")
        self.assertEqual(store.get("order.id"), "A42")

    def test_重名后覆盖前且WARN留痕不含实值(self):
        store = VarStore()
        store.set("order.id", "SECRET-1")
        with self.assertLogs("testpike.executor", level="WARN") as captured:
            store.set("order.id", "SECRET-2")
        self.assertEqual(store.get("order.id"), "SECRET-2")
        joined = "\n".join(captured.output)
        self.assertIn("order.id", joined, "WARN 必须点名重名键")
        self.assertNotIn("SECRET", joined, "WARN 零实值泄漏（H3 §5 镜像）")

    def test_初始预置构造(self):
        store = VarStore({"a": 1, "b": "x"})
        self.assertEqual(store.get("a"), 1)
        self.assertEqual(store.get("b"), "x")


class TestResolveVars纯函数(unittest.TestCase):
    pass

    def test_全字段递归替换(self):
        doc = {
            "setup": [{"action": "goto", "target": {"url": DEMO_URL}}],
            "steps": [
                {"action": "input", "target": {"css": "#username"},
                 "value": "{{VAR:order.no}}"},
                {"action": "http.request", "method": "GET",
                 "url": "http://api/x?id={{VAR:step3.data.id}}",
                 "headers": {"X-Tenant": "{{VAR:tenant}}"},
                 "body": {"items": ["{{VAR:order.no}}"]}},
            ],
        }
        store = VarStore({"order.no": "A42", "step3.data.id": "7",
                          "tenant": "t1"})
        out = resolve_vars(doc, store)
        self.assertEqual(out["steps"][0]["value"], "A42")
        self.assertEqual(out["steps"][1]["url"], "http://api/x?id=7")
        self.assertEqual(out["steps"][1]["headers"]["X-Tenant"], "t1")
        self.assertEqual(out["steps"][1]["body"]["items"], ["A42"])

    def test_同串多占位符全部替换(self):
        out = resolve_vars({"v": "{{VAR:A}}-{{VAR:B}}-{{VAR:A}}"},
                           VarStore({"A": "1", "B": "2"}))
        self.assertEqual(out["v"], "1-2-1")

    def test_无VAR占位符原样且返回新结构(self):
        doc = {"steps": [{"action": "click", "target": {"css": "#a"},
                          "text": "{{ENV:KEEP}}"}]}
        out = resolve_vars(doc, VarStore())
        self.assertEqual(out, doc)
        self.assertIsNot(out["steps"], doc["steps"])
        self.assertEqual(out["steps"][0]["text"], "{{ENV:KEEP}}")

    def test_未定义fail_fast_一次点名全部缺失键(self):
        with self.assertRaises(VarUndefinedError) as ctx:
            resolve_vars({"a": "{{VAR:step9.data.id}}",
                          "b": "{{VAR:order.no}}"}, VarStore())
        self.assertEqual(ctx.exception.missing_vars,
                         ["step9.data.id", "order.no"])
        msg = str(ctx.exception)
        self.assertIn("变量", msg)
        self.assertIn("未定义（检查前序 saveAs）", msg)
        self.assertIn("step9.data.id、order.no", msg)

    def test_点名信息零已解析实值(self):
        store = VarStore({"known": "TOP-SECRET-VALUE"})
        with self.assertRaises(VarUndefinedError) as ctx:
            resolve_vars({"a": "{{VAR:known}}-{{VAR:missing}}"}, store)
        self.assertNotIn("TOP-SECRET-VALUE", str(ctx.exception))

    def test_VAR与ENV互不干扰(self):
        pass
        env_doc = {"v": "{{VAR:x}}"}
        self.assertEqual(resolve_placeholders(env_doc, {}), {"v": "{{VAR:x}}"})
        var_doc = {"v": "{{ENV:X}}"}
        self.assertEqual(resolve_vars(var_doc, VarStore({"X": "no"})),
                         {"v": "{{ENV:X}}"})


def _login_action_var() -> str:
    pass
    return json.dumps({
        "version": "v2",
        "setup": [{"action": "goto", "target": {"url": DEMO_URL}}],
        "steps": [
            {"action": "input", "target": {"css": "#username"},
             "value": "{{VAR:USER}}"},
            {"action": "click", "target": {"css": "#login-btn"}},
            {"action": "expect", "target": {"text": "欢迎，{{VAR:USER}}！登录成功"}},
        ],
    }, ensure_ascii=False)


class TestWebdriver变量域集成(unittest.TestCase):
    pass

    def test_预置VAR全链消费_登录成功(self):
        outcome = run_action_json(_login_action_var(),
                                  var_store=VarStore({"USER": "varuser42"}),
                                  headless=True)
        self.assertEqual(outcome.result, "pass",
                         f"VAR 应解析实值执行: {outcome.error_detail}")

    def test_未定义VAR_本步fail人话点名_归env(self):
        outcome = run_action_json(_login_action_var(), headless=True)
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(outcome.error_type, 3, "缺变量归 env（分型表冻结）")
        self.assertIn("变量 USER 未定义（检查前序 saveAs）",
                      outcome.error_detail or "")
        first = outcome.steps_timeline[0] if outcome.steps_timeline else None
        self.assertIsNotNone(first, "未定义引用必须留步骤时间线痕迹")

    def test_per_run变量域不泄漏下次run(self):
        pass
        ex = WebDriverExecutor(headless=True)
        ex.start()
        try:
            ok = ex.run(_login_action_var(),
                        var_store=VarStore({"USER": "shared-var"}))
            self.assertEqual(ok.result, "pass", ok.error_detail)
            leak = ex.run(_login_action_var())
            self.assertEqual(leak.result, "fail")
            self.assertIn("变量 USER 未定义", leak.error_detail or "")
        finally:
            ex.stop()

    def test_loop子步VAR消费(self):
        pass
        action = json.dumps({
            "version": "v2",
            "setup": [{"action": "goto", "target": {"url": DEMO_URL}}],
            "steps": [{
                "action": "loop", "count": 1, "steps": [
                    {"action": "input", "target": {"css": "#username"},
                     "value": "{{VAR:USER}}"},
                    {"action": "click", "target": {"css": "#login-btn"}},
                    {"action": "expect",
                     "target": {"text": "欢迎，{{VAR:USER}}！登录成功"}},
                ]}],
        }, ensure_ascii=False)
        outcome = run_action_json(action, var_store=VarStore({"USER": "loopvar"}),
                                  headless=True)
        self.assertEqual(outcome.result, "pass", outcome.error_detail)

    def test_ENV与VAR同文档双命名空间共存(self):
        action = json.dumps({
            "version": "v2",
            "setup": [{"action": "goto", "target": {"url": "{{ENV:BASE}}"}}],
            "steps": [
                {"action": "input", "target": {"css": "#username"},
                 "value": "{{VAR:USER}}"},
                {"action": "click", "target": {"css": "#login-btn"}},
                {"action": "expect", "target": {"text": "欢迎，{{VAR:USER}}！登录成功"}},
            ],
        }, ensure_ascii=False)
        outcome = run_action_json(action,
                                  env_vars={"BASE": DEMO_URL},
                                  var_store=VarStore({"USER": "dual"}),
                                  headless=True)
        self.assertEqual(outcome.result, "pass", outcome.error_detail)


class Test涟漪既有ENV面(unittest.TestCase):
    pass

    def test_ENV缺键仍点名_ENVPlaceholderError(self):
        with self.assertRaises(EnvPlaceholderError):
            resolve_placeholders({"a": "{{ENV:X}}"}, {})


if __name__ == "__main__":
    unittest.main()
