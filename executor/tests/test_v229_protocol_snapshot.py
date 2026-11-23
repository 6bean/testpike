
from __future__ import annotations

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from driver.snapshot import build_step_snapshot, mask_sensitive
from driver.webdriver import run_action_json


class _Handler(BaseHTTPRequestHandler):
    pass

    protocol_version = "HTTP/1.1"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b"{}"
        echo = json.loads(raw or b"{}")
        self._send(200, {"ok": True, "echo": echo, "contact": "ops@demo.com"})

    def do_GET(self):
        self._send(200, {"data": {"phone": "13812345678"}})

    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


_server = None
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


def _policy(allow="127.0.0.1"):
    return {"allowHosts": allow, "denyHosts": "", "timeoutSeconds": 10,
            "netAssertEnabled": 1}


class Test脱敏件(unittest.TestCase):
    pass

    def test_敏感键变体全掩码(self):
        src = {"headers": {"Authorization": "Bearer abc123", "cookie": "s=1",
                           "X-Normal": "keep"},
               "body": {"Password": "p@1", "TOKEN": "t-1", "api_key": "k-1",
                        "client-secret": "cs-1", "user": "zhangsan"}}
        out = json.loads(json.dumps(mask_sensitive(src)))
        self.assertEqual(out["headers"]["Authorization"], "***MASKED***")
        self.assertEqual(out["headers"]["cookie"], "***MASKED***")
        self.assertEqual(out["headers"]["X-Normal"], "keep")
        self.assertEqual(out["body"]["Password"], "***MASKED***")
        self.assertEqual(out["body"]["TOKEN"], "***MASKED***")
        self.assertEqual(out["body"]["api_key"], "***MASKED***")
        self.assertEqual(out["body"]["client-secret"], "***MASKED***")
        self.assertEqual(out["body"]["user"], "zhangsan", "非敏感值原样")

    def test_嵌套体内敏感键与E5文本打码(self):
        src = {"a": {"b": {"token": "deep-secret-value"}},
               "note": "联系 13812345678 或 zhangsan@example.com"}
        masked = mask_sensitive(src)
        text = json.dumps(masked)
        self.assertNotIn("deep-secret-value", text)
        self.assertNotIn("13812345678", text, "E5 手机号打码")
        self.assertNotIn("zhangsan@example.com", text, "E5 邮箱打码")
        self.assertIn("138****5678", text, "打码形态与 core DesensitizeUtils 同口径")
        json.loads(text)

    def test_标量与列表混合结构可解析(self):
        src = [{"token": "x"}, ["13812345678", "keep"]]
        out = mask_sensitive(src)
        self.assertEqual(out[1][1], "keep")
        json.loads(json.dumps(out))


class Test快照采集件(unittest.TestCase):
    pass

    def test_http步快照三件构造(self):
        snap = build_step_snapshot(
            step_no=3, kind="http.request",
            request={"method": "POST", "url": _base + "/api",
                     "headers": {"Authorization": "Bearer leak"},
                     "body": {"q": 1}},
            response={"status": 200, "headers": {"Set-Cookie": "sid=leak"},
                      "body": '{"ok":true}'},
            assertions=[{"kind": "expect.status", "expected": 200, "actual": 200}])
        self.assertEqual(snap["stepNo"], 3)
        self.assertEqual(snap["kind"], "http.request")
        text = json.dumps(snap)
        self.assertNotIn("Bearer leak", text, "采集即脱敏（凭据不出进程）")
        self.assertNotIn("sid=leak", text)
        self.assertIn("expect.status", text, "断言操作数留存")
        json.loads(text)


class TestHttp步骤执行采集链路(unittest.TestCase):
    pass

    def test_http步执行产出快照(self):
        step = {"action": "http.request", "method": "GET", "url": _base + "/q",
                "expect": {"status": 200}}
        outcome = run_action_json(json.dumps({"version": "v2", "steps": [step]}),
                                  exec_policy=_policy(), headless=True)
        self.assertEqual(outcome.result, "pass", outcome.error_detail)
        snaps = outcome.protocol_snapshots
        self.assertEqual(len(snaps), 1)
        self.assertEqual(snaps[0]["stepNo"], 1)
        self.assertIn("data", json.dumps(snaps[0]["response"]))
        self.assertNotIn("13812345678", json.dumps(snaps[0]))

    def test_旧任务无http步零快照键(self):
        outcome = run_action_json(json.dumps({"version": "v2", "steps": []}),
                                  exec_policy=_policy(), headless=True)
        self.assertFalse(getattr(outcome, "protocol_snapshots", None))


class Test协议客户端尾追键(unittest.TestCase):
    pass

    def test_载荷键在位与空不出键(self):
        import protocol
        import inspect
        sig = inspect.signature(protocol.ExecutorProtocolClient.submit_result)
        self.assertIn("protocol_snapshots", sig.parameters,
                      "03 v2.40 result 尾追键（三方契约一致，坑③）")
