

import json
import os
import unittest

from driver.env_resolver import (
    EnvPlaceholderError,
    resolve_placeholders,
    resolve_url_template,
)
from driver.webdriver import run_action_json

DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))


class TestResolvePlaceholders纯函数(unittest.TestCase):
    pass

    def test_递归替换_input值与嵌套字段(self):
        doc = {
            "setup": [{"action": "goto", "target": {"url": "{{ENV:BASE}}/login"}}],
            "steps": [
                {"action": "input", "target": {"css": "#u"}, "value": "{{ENV:USER}}"},
                {"action": "loop", "count": 3, "steps": [
                    {"action": "expect", "text": "欢迎 {{ENV:USER}}"}]},
            ],
        }
        resolved = resolve_placeholders(
            doc, {"BASE": "http://e.local", "USER": "envuser42"})
        self.assertEqual(resolved["setup"][0]["target"]["url"], "http://e.local/login")
        self.assertEqual(resolved["steps"][0]["value"], "envuser42")
        self.assertEqual(resolved["steps"][1]["steps"][0]["text"], "欢迎 envuser42")
        self.assertEqual(resolved["steps"][1]["count"], 3)

    def test_同串多占位符全部替换(self):
        out = resolve_placeholders(
            {"v": "{{ENV:A}}-{{ENV:B}}-{{ENV:A}}"}, {"A": "1", "B": "2"})
        self.assertEqual(out["v"], "1-2-1")

    def test_无占位符字符串原样_文档结构不破坏(self):
        doc = {"steps": [{"action": "click", "target": {"css": "#a"}}]}
        out = resolve_placeholders(doc, {})
        self.assertEqual(out, doc)
        self.assertIsNot(out["steps"], doc["steps"], "必须返回新结构不改原对象")

    def test_缺键_fail_closed_一次点名全部缺失键(self):
        with self.assertRaises(EnvPlaceholderError) as ctx:
            resolve_placeholders({"a": "{{ENV:X}}", "b": "{{ENV:Y}}"}, {})
        self.assertEqual(ctx.exception.missing_keys, ["X", "Y"])
        self.assertIn("环境缺少参数", str(ctx.exception))
        self.assertIn("X、Y", str(ctx.exception))

    def test_vars优先于保留字URL(self):
        out = resolve_placeholders({"u": "{{ENV:URL}}"},
                                   {"URL": "http://from-vars"},
                                   env_base_url="http://from-base")
        self.assertEqual(out["u"], "http://from-vars")

    def test_保留字URL回退env_base_url(self):
        out = resolve_placeholders({"u": "{{ENV:URL}}/login"}, {},
                                   env_base_url="http://base.local")
        self.assertEqual(out["u"], "http://base.local/login")


class TestResolveUrlTemplate(unittest.TestCase):
    pass

    def test_保留字两键映射baseUrl(self):
        self.assertEqual(
            resolve_url_template("{{ENV:BASE_URL}}/x", {}, "http://b.local"),
            "http://b.local/x")

    def test_泛化键进URL(self):
        self.assertEqual(
            resolve_url_template("{{ENV:HOST}}/login?u={{ENV:USER}}",
                                 {"HOST": "http://h", "USER": "u1"}, None),
            "http://h/login?u=u1")

    def test_缺键仍fail_closed(self):
        with self.assertRaises(EnvPlaceholderError):
            resolve_url_template("{{ENV:MISS}}/x", {}, None)


class TestWebdriver集成解析(unittest.TestCase):
    pass

    def _login_action(self) -> str:
        return json.dumps({
            "version": "v2",
            "setup": [{"action": "goto", "target": {"url": DEMO_URL}}],
            "steps": [
                {"action": "input", "target": {"css": "#username"},
                 "value": "{{ENV:USER}}"},
                {"action": "click", "target": {"css": "#login-btn"}},
                {"action": "expect", "target": {"text": "欢迎，{{ENV:USER}}！登录成功"}},
            ],
        }, ensure_ascii=False)

    def test_env_vars全字段解析_登录成功(self):
        outcome = run_action_json(self._login_action(), env_vars={"USER": "envuser42"},
                                  headless=True)
        self.assertEqual(outcome.result, "pass",
                         f"占位符应解析实值执行: {outcome.error_detail}")

    def test_缺键_执行失败人话_环境问题(self):
        outcome = run_action_json(self._login_action(), env_vars={}, headless=True)
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(outcome.error_type, 3, "缺参数归环境问题（非定位错误）")
        self.assertIn("环境缺少参数 USER", outcome.error_detail or "")

    def test_既有ENV_URL两键语义零回归(self):
        pass
        action = json.dumps({
            "version": "v2",
            "steps": [
                {"action": "goto", "target": {"url": "{{ENV:URL}}"}},
                {"action": "expect", "target": {"text": "登录"}},
            ],
        }, ensure_ascii=False)
        outcome = run_action_json(action, env_base_url=DEMO_URL, headless=True)
        self.assertEqual(outcome.result, "pass",
                         f"既有两键语义回归: {outcome.error_detail}")

    def test_共享实例per_run_vars不泄漏(self):
        pass
        from driver.webdriver import WebDriverExecutor
        ex = WebDriverExecutor(headless=True)
        ex.start()
        try:
            ok = ex.run(self._login_action(), env_vars={"USER": "shared-user"})
            self.assertEqual(ok.result, "pass", ok.error_detail)
            leak = ex.run(self._login_action(), env_vars=None)
            self.assertEqual(leak.result, "fail")
            self.assertIn("环境缺少参数 USER", leak.error_detail or "")
        finally:
            ex.stop()


if __name__ == "__main__":
    unittest.main()
