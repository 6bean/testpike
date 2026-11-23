

import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from driver.webdriver import WebDriverExecutor, run_action_json

FIXTURES = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures"))

DEMO_POLICY = {"allowHosts": "127.0.0.1", "denyHosts": "",
               "timeoutSeconds": 10, "netAssertEnabled": 1}


class _Handler(BaseHTTPRequestHandler):
    pass

    protocol_version = "HTTP/1.1"

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
        return self.rfile.read(length) if length else b""

    def do_POST(self):
        self._read_body()
        self._send(200, {"ok": True})

    def do_GET(self):
        if self.path == "/api/order":
            self._send(200, {"data": {"id": 42, "status": "PAID"}})
        elif self.path == "/api/forbidden":
            self._send(403, {"error": "not-logged-in"})
        elif self.path == "/net_page.html":
            with open(os.path.join(FIXTURES, "net_page.html"), "rb") as f:
                self._send_bytes(200, f.read(), "text/html; charset=utf-8")
        else:
            self._send(404, {"error": "no-route"})

    def _send(self, code, payload):
        self._send_bytes(code, json.dumps(payload).encode("utf-8"),
                         "application/json")

    def _send_bytes(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


_server: ThreadingHTTPServer | None = None
_base = ""
PAGE_URL = ""


def setUpModule():
    global _server, _base, PAGE_URL
    _server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=_server.serve_forever, daemon=True).start()
    _base = f"http://127.0.0.1:{_server.server_address[1]}"
    PAGE_URL = f"{_base}/net_page.html"


def tearDownModule():
    if _server:
        _server.shutdown()
        _server.server_close()


def _net(pattern, method=None, absent=False, window=None, expect=None):
    match = {"urlPattern": pattern}
    if method:
        match["method"] = method
    step = {"action": "net.assert", "match": match, "absent": absent}
    if window:
        step["window"] = window
    if expect:
        step["expect"] = expect
    return step


def _click(selector="#btn-export"):
    return [{"action": "goto", "target": {"url": PAGE_URL}},
            {"action": "click", "target": {"css": selector}},
            {"action": "wait", "ms": 800}]


def _run(steps, policy=DEMO_POLICY):
    doc = {"version": "v2", "steps": steps}
    return run_action_json(json.dumps(doc, ensure_ascii=False),
                           exec_policy=dict(policy), headless=True)


class TestNet观测器(unittest.TestCase):
    pass

    def test_正向_点击导出_net观测200加body判定过(self):
        pass
        outcome = _run(_click() + [
            _net("*/api/order*", expect={"status": 200, "body": [
                {"path": "$.data.id", "matchType": "range",
                 "min": 40, "max": 45}]}),
        ])
        self.assertEqual(outcome.result, "pass", outcome.error_detail)
        self.assertIn("命中", outcome.steps_timeline[-1]["detail"])

    def test_负向_absent不该发的请求发出即fail归1(self):
        pass
        outcome = _run(_click("#btn-forbidden") + [
            _net("*/api/forbidden*", absent=True),
        ])
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(outcome.error_type, 1, "断言不满足=产品缺陷（分型冻结）")
        self.assertIn("断言不满足", outcome.error_detail or "")
        self.assertIn("不该发", outcome.error_detail or "")

    def test_absent未发出_合法通过(self):
        outcome = _run(_click() + [
            _net("*/api/forbidden*", absent=True),
        ])
        self.assertEqual(outcome.result, "pass", outcome.error_detail)

    def test_absent但无任何命中_通过且期望命中无请求判败归1(self):
        ok = _run(_click() + [_net("*/api/never-called*", absent=True)])
        self.assertEqual(ok.result, "pass", ok.error_detail)
        miss = _run([{"action": "goto", "target": {"url": PAGE_URL}},
                     _net("*/api/order*")])
        self.assertEqual(miss.result, "fail")
        self.assertEqual(miss.error_type, 1)
        self.assertIn("断言不满足", miss.error_detail or "")

    def test_expect_status不符_absent假_判败归1(self):
        outcome = _run(_click() + [_net("*/api/order*", expect={"status": 500})])
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(outcome.error_type, 1)
        self.assertIn("断言不满足", outcome.error_detail or "")

    def test_window_sinceLast不串窗_二次观测同请求判败(self):
        pass
        outcome = _run(_click() + [
            _net("*/api/order*"),
            _net("*/api/order*"),
        ])
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(outcome.steps_timeline[-1]["status"], "fail")
        self.assertEqual(outcome.steps_timeline[-2]["status"], "ok")

    def test_window_full全窗可见_二次仍可命中(self):
        outcome = _run(_click() + [
            _net("*/api/order*"),
            _net("*/api/order*", window="full"),
        ])
        self.assertEqual(outcome.result, "pass", outcome.error_detail)

    def test_method过滤与大小写归一(self):
        ok = _run(_click() + [_net("*/api/order*", method="get")])
        self.assertEqual(ok.result, "pass", ok.error_detail)
        miss = _run(_click() + [_net("*/api/order*", method="POST")])
        self.assertEqual(miss.result, "fail", "GET 请求不被 POST 模式匹配")
        absent_ok = _run(_click() + [_net("*/api/order*", method="POST",
                                          absent=True)])
        self.assertEqual(absent_ok.result, "pass", absent_ok.error_detail)
        bad = _run(_click() + [_net("*/api/order*", method="FOO")])
        self.assertEqual(bad.result, "fail")
        self.assertEqual(bad.error_type, 3)
        self.assertIn("HTTP 动词", bad.error_detail or "")

    def test_netAssertDisabled_直通仅记录不判败(self):
        pass
        policy = dict(DEMO_POLICY)
        policy["netAssertEnabled"] = 0
        outcome = _run(_click("#btn-forbidden") + [
            _net("*/api/forbidden*", absent=True),
        ], policy=policy)
        self.assertEqual(outcome.result, "pass", outcome.error_detail)
        detail = outcome.steps_timeline[-1]["detail"] or ""
        self.assertIn("已停用", detail)
        self.assertIn("记录", detail)

    def test_glob非法字符集python镜像点名(self):
        pass
        outcome = _run(_click() + [_net("[0-9]/api/order*")])
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(outcome.error_type, 3, "脚本违例=产物问题禁归产品缺陷")
        self.assertIn("仅支持 glob", outcome.error_detail or "")

    def test_缺match或urlPattern人话点名(self):
        for bad in ({"action": "net.assert"},
                    {"action": "net.assert", "match": {}},
                    {"action": "net.assert", "match": {"urlPattern": "  "}}):
            with self.subTest(bad=bad):
                outcome = _run([{"action": "goto", "target": {"url": PAGE_URL}},
                                bad])
                self.assertEqual(outcome.result, "fail")
                self.assertEqual(outcome.error_type, 3)
                self.assertIn("urlPattern", outcome.error_detail or "")

    def test_window非法值点名白名单(self):
        outcome = _run(_click() + [_net("*/api/order*", window="lastMinute")])
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(outcome.error_type, 3)
        self.assertIn("sinceLast", outcome.error_detail or "")

    def test_hits暂存供T192(self):
        pass
        ex = WebDriverExecutor(exec_policy=dict(DEMO_POLICY))
        ex.start()
        try:
            doc = {"version": "v2", "steps": _click() + [
                _net("*/api/order*", expect={"status": 200})]}
            outcome = ex.run(json.dumps(doc))
            self.assertEqual(outcome.result, "pass", outcome.error_detail)
            hits = ex.net_observer.hits
            self.assertGreaterEqual(len(hits), 1)
            hit = hits[-1]
            self.assertIn("/api/order", hit["url"])
            self.assertEqual(hit["method"], "GET")
            self.assertEqual(hit["status"], 200)
            self.assertEqual(hit["stepNo"], outcome.steps_timeline[-1]["stepNo"])
        finally:
            ex.stop()


if __name__ == "__main__":
    unittest.main()
