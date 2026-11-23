

import json
import logging
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from driver.env_resolver import VarStore
from driver.jsonpath_subset import JsonPathError, evaluate_json_path, scalar_leaves
from driver.webdriver import run_action_json

DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))


class _Handler(BaseHTTPRequestHandler):
    pass

    protocol_version = "HTTP/1.1"
    slow_hits = 0

    def _read_body(self) -> bytes:
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            data = b""
            while True:
                size = int(self.rfile.readline().strip().split(b";")[0] or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    break
                data += self.rfile.read(size)
                self.rfile.readline()
            return data
        length = int(self.headers.get("Content-Length", 0) or 0)
        return self.rfile.read(length) if length else b"{}"

    def do_POST(self):
        self._read_body()
        self._send(200, {"ok": True})

    def do_GET(self):
        if self.path == "/order":
            self._send(200, {"data": {"id": 42,
                                      "items": [{"n": "a"}, {"n": "b"}]}})
        elif self.path == "/slow":
            _Handler.slow_hits += 1
            if _Handler.slow_hits < 3:
                self._send(500, {"status": "RUNNING"})
            else:
                self._send(200, {"status": "SUCCESS"})
        elif self.path == "/running":
            self._send(500, {"status": "RUNNING"})
        elif self.path == "/text":
            self._send(200, "plain-not-json")
        else:
            self._send(404, {"error": "no-route"})

    def _send(self, code, payload):
        body = (payload if isinstance(payload, str)
                else json.dumps(payload)).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


_server: ThreadingHTTPServer | None = None
_base = ""


def setUpModule():
    global _server, _base
    _server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=_server.serve_forever, daemon=True).start()
    _base = f"http://127.0.0.1:{_server.server_address[1]}"


def tearDownModule():
    if _server:
        _server.shutdown()
        _server.server_close()


def _policy(allow="127.0.0.1", deny="", timeout=10, net=1):
    return {"allowHosts": allow, "denyHosts": deny,
            "timeoutSeconds": timeout, "netAssertEnabled": net}


def _run(step, policy=_policy(), var_store=None, env_base_url=None):
    doc = {"version": "v2", "steps": [step]}
    return run_action_json(json.dumps(doc, ensure_ascii=False),
                           exec_policy=policy, var_store=var_store,
                           env_base_url=env_base_url, headless=True)


class TestJsonPath子集求值件(unittest.TestCase):
    pass

    OBJ = {"data": {"id": 42, "items": [{"n": "a"}, {"n": "b"}],
                    "flag": True, "nil": None}}

    def test_合法路径逐段求值(self):
        self.assertEqual(evaluate_json_path(self.OBJ, "$.data.items[1].n"), "b")
        self.assertEqual(evaluate_json_path(self.OBJ, "$.data.id"), 42)
        self.assertEqual(evaluate_json_path(self.OBJ, "$.data.flag"), True)

    def test_非法路径人话点名禁抛栈(self):
        for bad in ("", "data.id", "$..items", "$.a[?(@.x==1)]", "$.a[1:2]",
                    "$.*", "$.a.b."):
            with self.subTest(bad=bad):
                with self.assertRaises(JsonPathError) as ctx:
                    evaluate_json_path(self.OBJ, bad)
                self.assertIn("受限子集", str(ctx.exception))

    def test_缺路径点名路径本身(self):
        with self.assertRaises(JsonPathError) as ctx:
            evaluate_json_path(self.OBJ, "$.data.nope.deep")
        self.assertIn("$.data.nope.deep", str(ctx.exception))

    def test_命中非叶拒标量语义(self):
        with self.assertRaises(JsonPathError) as ctx:
            evaluate_json_path(self.OBJ, "$.data")
        self.assertIn("标量", str(ctx.exception))

    def test_标量叶展开(self):
        leaves = scalar_leaves(self.OBJ["data"], "resp")
        self.assertEqual(leaves["resp.id"], 42)
        self.assertEqual(leaves["resp.items[0].n"], "a")
        self.assertEqual(leaves["resp.items[1].n"], "b")
        self.assertEqual(scalar_leaves(7, "x"), {"x": 7})


class TestHttp请求链路(unittest.TestCase):
    pass

    def test_缺执行策略_fail_closed人话拒归3(self):
        step = {"action": "http.request", "method": "GET", "url": _base + "/order"}
        outcome = run_action_json(json.dumps({"steps": [step]}),
                                  exec_policy=None, headless=True)
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(outcome.error_type, 3)
        self.assertIn("执行策略未下发", outcome.error_detail or "")

    def test_allow_hosts外拒不发请求(self):
        _Handler.slow_hits = 0
        step = {"action": "http.request", "method": "GET", "url": "http://169.254.169.254/lm"}
        outcome = _run(step, _policy(allow="example.com"))
        self.assertEqual(outcome.error_type, 3)
        self.assertIn("allow-hosts", outcome.error_detail or "")

    def test_生产黑名单拒优先于白名单(self):
        step = {"action": "http.request", "method": "GET",
                "url": "http://api.evil.com/x"}
        outcome = _run(step, _policy(allow="", deny="*.evil.com"))
        self.assertEqual(outcome.error_type, 3)
        self.assertIn("黑名单", outcome.error_detail or "")

    def test_连接失败归3禁Timeout字样误归2(self):
        step = {"action": "http.request", "method": "GET",
                "url": "http://127.0.0.1:1/nope"}
        outcome = _run(step)
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(outcome.error_type, 3,
                         f"连接失败必须归 env(3): {outcome.error_detail}")
        self.assertIn("连接", outcome.error_detail or "")
        self.assertNotIn("断言不满足", outcome.error_detail or "")

    def test_expect_status满足与不符(self):
        ok = _run({"action": "http.request", "url": _base + "/order",
                   "expect": {"status": 200}})
        self.assertEqual(ok.result, "pass", ok.error_detail)
        bad = _run({"action": "http.request", "url": _base + "/order",
                    "expect": {"status": 500}})
        self.assertEqual(bad.error_type, 1, "期望不符=产品缺陷（04 §2.1.2）")
        self.assertIn("断言不满足", bad.error_detail or "")

    def test_allow_hosts空回落环境baseUrl主机(self):
        step = {"action": "http.request", "url": _base + "/order"}
        ok = _run(step, _policy(allow=""), env_base_url=_base)
        self.assertEqual(ok.result, "pass", ok.error_detail)
        denied = _run({"action": "http.request",
                       "url": "http://other.example.com/x"},
                      _policy(allow=""), env_base_url=_base)
        self.assertIn("allow-hosts", denied.error_detail or "")

    def test_expect_body匹配器族复用禁第二套(self):
        base = {"action": "http.request", "url": _base + "/order"}
        cases = [
            ({"body": [{"path": "$.data.items[0].n", "matchType": "contains",
                        "text": "a"}]}, "pass"),
            ({"body": [{"path": "$.data.id", "matchType": "range",
                        "min": 40, "max": 45}]}, "pass"),
            ({"body": [{"path": "$.data.id", "matchType": "regex",
                        "pattern": r"^4[0-9]$"}]}, "pass"),
            ({"body": [{"path": "$.data.id", "matchType": "exact",
                        "text": "43"}]}, "fail"),
            ({"body": [{"path": "$.data.id", "matchType": "range",
                        "min": 100}]}, "fail"),
        ]
        for expect, want in cases:
            with self.subTest(expect=expect):
                outcome = _run({**base, "expect": expect})
                self.assertEqual(outcome.result, want, outcome.error_detail)
                if want == "fail":
                    self.assertEqual(outcome.error_type, 1)
                    self.assertIn("断言不满足", outcome.error_detail or "")

    def test_body_path非JSON响应人话拒(self):
        outcome = _run({"action": "http.request", "url": _base + "/text",
                        "expect": {"body": [{"path": "$.a", "matchType": "contains",
                                             "text": "x"}]}})
        self.assertEqual(outcome.error_type, 3, "产物形态问题禁归产品缺陷")
        self.assertIn("JSON", outcome.error_detail or "")

    def test_poll_interval钳1s加WARN_until_match转绿(self):
        _Handler.slow_hits = 0
        with self.assertLogs("testpike.executor", level="WARN") as captured:
            outcome = _run({"action": "http.request", "url": _base + "/slow",
                            "expect": {"status": 200},
                            "poll": {"intervalMs": 100, "timeoutMs": 15000,
                                     "until": "match"}})
        self.assertEqual(outcome.result, "pass", outcome.error_detail)
        self.assertTrue(any("intervalMs" in m for m in captured.output),
                        "intervalMs<1000 必须 WARN 留痕钳制定点")

    def test_poll超时归env带末次实际值(self):
        outcome = _run({"action": "http.request", "url": _base + "/running",
                        "expect": {"status": 200},
                        "poll": {"intervalMs": 1000, "timeoutMs": 1500}})
        self.assertEqual(outcome.error_type, 3, "轮询超时=环境问题（带末次实际值）")
        self.assertIn("末次", outcome.error_detail or "")
        self.assertIn("500", outcome.error_detail or "")

    def test_saveAs标量叶展开进变量链(self):
        pass
        store = VarStore()
        doc = {"version": "v2", "setup": [
            {"action": "goto", "target": {"url": DEMO_URL}}],
            "steps": [
                {"action": "http.request", "url": _base + "/order",
                 "saveAs": "resp"},
                {"action": "input", "target": {"css": "#username"},
                 "value": "{{VAR:resp.data.id}}"},
                {"action": "click", "target": {"css": "#login-btn"}},
                {"action": "expect",
                 "target": {"text": "欢迎，42！登录成功"}},
            ]}
        outcome = run_action_json(json.dumps(doc), exec_policy=_policy(),
                                  var_store=store, headless=True)
        self.assertEqual(outcome.result, "pass", outcome.error_detail)
        self.assertEqual(store.get("resp.data.id"), 42)
        self.assertEqual(store.get("resp.data.items[0].n"), "a")
        self.assertEqual(store.get("resp.data.items[1].n"), "b")

    def test_无expect纯调用成功即过_status不参与判定(self):
        outcome = _run({"action": "http.request", "method": "POST",
                        "url": _base + "/order", "body": {"k": "v"}})
        self.assertEqual(outcome.result, "pass", outcome.error_detail)


if __name__ == "__main__":
    unittest.main()
