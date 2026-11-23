

import json
import os
import unittest

from driver.env_resolver import (
    DataKeyMissingError,
    VarStore,
    resolve_data,
)

DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))


class TestResolveData纯函数(unittest.TestCase):
    pass

    def test_递归替换全字段_嵌套结构(self):
        doc = {
            "steps": [
                {"action": "input", "target": {"css": "#u"},
                 "value": "{{DATA:ACCOUNT}}"},
                {"action": "loop", "count": 2, "steps": [
                    {"action": "expect", "text": "订单 {{DATA:ORDER_NO}}"}]},
                {"action": "http.request", "params": {"token": "{{DATA:TOKEN}}"}},
            ],
        }
        resolved = resolve_data(doc, {"ACCOUNT": "qa_01", "ORDER_NO": "SO-9",
                                      "TOKEN": "t-abc"})
        self.assertEqual(resolved["steps"][0]["value"], "qa_01")
        self.assertEqual(resolved["steps"][1]["steps"][0]["text"], "订单 SO-9")
        self.assertEqual(resolved["steps"][2]["params"]["token"], "t-abc")
        self.assertEqual(resolved["steps"][1]["count"], 2)

    def test_同串多DATA占位符全部替换(self):
        out = resolve_data({"v": "{{DATA:A}}-{{DATA:B}}-{{DATA:A}}"},
                           {"A": "1", "B": "2"})
        self.assertEqual(out["v"], "1-2-1")

    def test_返回新结构不改原对象(self):
        doc = {"steps": [{"action": "click", "target": {"css": "#a"}}]}
        out = resolve_data(doc, {})
        self.assertEqual(out, doc)
        self.assertIsNot(out["steps"], doc["steps"])

    def test_缺键_fail_closed_一次点名全部缺失键(self):
        with self.assertRaises(DataKeyMissingError) as ctx:
            resolve_data({"a": "{{DATA:X}}", "b": "{{DATA:Y}}"}, {})
        self.assertEqual(ctx.exception.missing_keys, ["X", "Y"])
        msg = str(ctx.exception)
        self.assertIn("数据池中不存在键", msg, "人话句案（03 §2.3.1b 字面）")
        self.assertIn("X、Y", msg, "一次点名信息完整不截断")

    def test_点名信息零实值_已解析值不回放(self):
        with self.assertRaises(DataKeyMissingError) as ctx:
            resolve_data({"a": "{{DATA:SECRET_HIT}}", "b": "{{DATA:NO_HIT}}"},
                         {"SECRET_HIT": "plain-should-not-leak"})
        self.assertNotIn("plain-should-not-leak", str(ctx.exception),
                         "红线4：点名禁泄漏池值明文")


class Test三命名空间正交(unittest.TestCase):
    pass

    def test_DATA链路不吞ENV与VAR占位符(self):
        doc = {"url": "{{ENV:BASE}}/x", "v": "{{VAR:s1.id}}", "d": "{{DATA:K}}"}
        out = resolve_data(doc, {"K": "kv"})
        self.assertEqual(out["url"], "{{ENV:BASE}}/x")
        self.assertEqual(out["v"], "{{VAR:s1.id}}")
        self.assertEqual(out["d"], "kv")


class TestWebdriver数据域集成(unittest.TestCase):
    pass

    def _data_action(self):
        return json.dumps({
            "version": "v2",
            "setup": [{"action": "goto", "target": {"url": DEMO_URL}}],
            "steps": [
                {"action": "input", "target": {"css": "#username"},
                 "value": "{{DATA:ACCOUNT}}"},
                {"action": "click", "target": {"css": "#login-btn"}},
                {"action": "expect",
                 "target": {"text": "欢迎，qa_pool_42！登录成功"}},
            ],
        }, ensure_ascii=False)

    def test_data_vars注入_input替换为真值(self):
        from driver.webdriver import run_action_json
        outcome = run_action_json(self._data_action(),
                                  data_vars={"ACCOUNT": "qa_pool_42"},
                                  headless=True)
        self.assertEqual(outcome.result, "pass",
                         f"DATA 注入应通过: {outcome.error_detail}")

    def test_缺DATA键_执行失败人话_不静默透传(self):
        from driver.webdriver import run_action_json
        outcome = run_action_json(self._data_action(), data_vars={},
                                  headless=True)
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(outcome.error_type, 3, "数据配置问题归环境类（ENV 缺键先例同归因）")
        self.assertIn("数据池中不存在键 ACCOUNT", outcome.error_detail)

    def test_DATA键不被ENV原样透传吞_混用互不干扰(self):
        from driver.webdriver import run_action_json
        action = json.dumps({
            "version": "v2",
            "setup": [{"action": "goto", "target": {"url": DEMO_URL}}],
            "steps": [
                {"action": "input", "target": {"css": "#username"},
                 "value": "{{ENV:U}}-{{DATA:D}}"},
                {"action": "click", "target": {"css": "#login-btn"}},
                {"action": "expect",
                 "target": {"text": "欢迎，eu-ed！登录成功"}},
            ],
        }, ensure_ascii=False)
        outcome = run_action_json(action, env_vars={"U": "eu"},
                                  data_vars={"D": "ed"}, headless=True)
        self.assertEqual(outcome.result, "pass",
                         f"ENV/DATA 混用互不吞: {outcome.error_detail}")

    def test_无data_vars既有用例零回归(self):
        from driver.webdriver import run_action_json
        action = json.dumps({
            "version": "v2",
            "setup": [{"action": "goto", "target": {"url": DEMO_URL}}],
            "steps": [{"action": "expect", "target": {"text": "测试门户登录"}}],
        })
        outcome = run_action_json(action, headless=True)
        self.assertEqual(outcome.result, "pass",
                         f"缺省 dataVars=空表零破坏: {outcome.error_detail}")


if __name__ == "__main__":
    unittest.main()
