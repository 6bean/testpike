

import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from driver.console_assert import ConsoleAssertError, validate_console_assert_step
from driver.webdriver import RunOutcome, run_action_json
from fake_response_t192 import _FakeResp

FIXTURES = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures"))
PAGE_URL = "http://127.0.0.1:{port}/console_page.html"


class _Handler(BaseHTTPRequestHandler):
    pass

    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/console_page.html":
            with open(os.path.join(FIXTURES, "console_page.html"), "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            body = b"no-route"
            self.send_response(404)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    def log_message(self, *args):
        pass


_server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
_port = _server.server_address[1]
_thread = threading.Thread(target=_server.serve_forever, daemon=True)
_thread.start()
PAGE = PAGE_URL.format(port=_port)


def _run(steps, exec_policy=None):
    doc = {"action_version": "2.0", "steps": steps}
    return run_action_json(json.dumps(doc), env_base_url=PAGE,
                           exec_policy=exec_policy)


def _goto():
    return {"action": "goto", "target": {"url": PAGE}}


def _click(css):
    return {"action": "click", "target": {"css": css}}


def _expect_result(text):
    pass
    return {"action": "expect", "target": {"css": "#result"}, "text": text}


def _cassert(**kw):
    return {"action": "console.assert", **kw}


class ConsoleAssert判定(unittest.TestCase):
    pass

    def test_无error命中_通过(self):
        outcome = _run([_goto(), _cassert()])
        self.assertEqual("pass", outcome.result,
                         f"干净页面必须 pass：{outcome.error_detail}")

    def test_真error命中_归1断言不满足(self):
        outcome = _run([_goto(), _click("#btn-error"), _expect_result("已触发错误"),
                        _cassert()])
        self.assertEqual("fail", outcome.result)
        self.assertEqual(1, outcome.error_type,
                         "断言不满足=产品缺陷归 1（评估稿 §4 冻结）")
        self.assertIn("断言不满足", outcome.error_detail)
        self.assertIn("orderTotal", outcome.error_detail, "detail 带命中文本")

    def test_白名单豁免第三方噪音(self):
        outcome = _run([_goto(), _click("#btn-noise"), _expect_result("已触发噪音"),
                        _cassert(ignorePatterns=["*analytics*"])])
        self.assertEqual("pass", outcome.result,
                         f"ignorePatterns 豁免后必须 pass：{outcome.error_detail}")

    def test_sinceLast分段归因_消费后不误伤(self):
        outcome = _run([_goto(), _click("#btn-error"), _expect_result("已触发错误"),
                        _cassert(ignorePatterns=["*orderTotal*"]),
                        _cassert()])
        self.assertEqual("pass", outcome.result,
                         f"游标推进后后续窗口必须干净：{outcome.error_detail}")

    def test_full窗口回看历史噪音(self):
        outcome = _run([_goto(), _click("#btn-error"), _expect_result("已触发错误"),
                        _cassert(ignorePatterns=["*orderTotal*"]),
                        _cassert(scope="full")])
        self.assertEqual("fail", outcome.result,
                         "full=回看全窗，已消费的 error 仍计入")
        self.assertEqual(1, outcome.error_type)

    def test_warning级默认不判_显式level判定(self):
        ok = _run([_goto(), _click("#btn-warn"), _expect_result("已触发告警"),
                   _cassert()])
        self.assertEqual("pass", ok.result, "默认 level=[error]，warning 不判")
        bad = _run([_goto(), _click("#btn-warn"), _expect_result("已触发告警"),
                    _cassert(level=["warning"])])
        self.assertEqual("fail", bad.result)
        self.assertEqual(1, bad.error_type)


class ConsoleAssert档位(unittest.TestCase):
    pass

    def _error_steps(self, **kw):
        return [_goto(), _click("#btn-error"), _expect_result("已触发错误"), _cassert(**kw)]

    def test_策略off_直通仅记录(self):
        outcome = _run(self._error_steps(), exec_policy={"consoleAssertMode": "off"})
        self.assertEqual("pass", outcome.result, "off=直通不判（netAssertEnabled=0 镜像）")
        self.assertTrue(outcome.console_violations, "off 档仍记证据（仅记录不判败）")

    def test_策略warn_记录告警不判(self):
        outcome = _run(self._error_steps(), exec_policy={"consoleAssertMode": "warn"})
        self.assertEqual("pass", outcome.result)
        self.assertEqual(1, len(outcome.console_violations))
        ev = outcome.console_violations[0]
        self.assertIn("orderTotal", json.dumps(ev, ensure_ascii=False))

    def test_步mode_collect_覆盖策略fail(self):
        outcome = _run(self._error_steps(mode="collect"),
                       exec_policy={"consoleAssertMode": "fail"})
        self.assertEqual("pass", outcome.result, "step.mode=collect 覆盖策略判定档")
        self.assertTrue(outcome.console_violations)

    def test_缺省策略_按fail判定(self):
        outcome = _run(self._error_steps())
        self.assertEqual("fail", outcome.result,
                         "缺 execPolicy=按 V71 种子缺省 fail（区别于 http 链路 fail-closed）")


class ConsoleAssert校验镜像(unittest.TestCase):
    pass

    def test_合法步归一化(self):
        spec = validate_console_assert_step(
            {"action": "console.assert", "level": ["error"], "scope": "full",
             "mode": "warn", "ignorePatterns": ["*/analytics/*"]})
        self.assertEqual("warn", spec["mode"])
        self.assertEqual("full", spec["scope"])
        self.assertEqual(["error"], spec["level"])
        self.assertEqual(["*/analytics/*"], spec["ignore"])

    def test_缺省归一(self):
        spec = validate_console_assert_step({"action": "console.assert"})
        self.assertIsNone(spec["mode"])
        self.assertEqual("sinceLast", spec["scope"])
        self.assertEqual(["error"], spec["level"])

    def test_mode枚举外_产物问题点名(self):
        with self.assertRaises(ConsoleAssertError) as cm:
            validate_console_assert_step({"action": "console.assert", "mode": "loud"})
        self.assertIn("mode", str(cm.exception))

    def test_scope枚举外点名(self):
        with self.assertRaises(ConsoleAssertError):
            validate_console_assert_step({"action": "console.assert", "scope": "prev"})

    def test_level空数组与非枚举点名(self):
        with self.assertRaises(ConsoleAssertError):
            validate_console_assert_step({"action": "console.assert", "level": []})
        with self.assertRaises(ConsoleAssertError):
            validate_console_assert_step({"action": "console.assert",
                                          "level": ["critical"]})

    def test_ignore正则元字符拒(self):
        with self.assertRaises(ConsoleAssertError):
            validate_console_assert_step({"action": "console.assert",
                                          "ignorePatterns": ["^foo\\d+"]})

    def test_校验违例执行归3(self):
        outcome = _run([_goto(), _cassert(mode="loud")])
        self.assertEqual("fail", outcome.result)
        self.assertEqual(3, outcome.error_type)


class 证据回传链(unittest.TestCase):
    pass

    def test_三键序列化往返(self):
        o = RunOutcome(result="pass",
                       console_violations=[{"stepNo": 2, "count": 1}],
                       net_hits=[{"url": "u", "status": 200, "durationMs": 12}],
                       db_digest=["step4: scalar=42"])
        d = o.to_dict()
        self.assertEqual(1, d["console_violations"][0]["count"])
        back = RunOutcome.from_dict(d)
        self.assertEqual(o.console_violations, back.console_violations)
        self.assertEqual(o.net_hits, back.net_hits)
        self.assertEqual(o.db_digest, back.db_digest)

    def test_旧payload缺键回退空(self):
        back = RunOutcome.from_dict({"result": "pass"})
        self.assertEqual([], back.console_violations)
        self.assertEqual([], back.net_hits)
        self.assertEqual([], back.db_digest)

    def test_nethits带durationMs(self):
        hits = [{"url": "u", "method": "GET", "status": 200, "stepNo": 1,
                 "durationMs": 7}]
        self.assertEqual([{"url": "u", "status": 200, "durationMs": 7}],
                         [{k: v for k, v in h.items()
                           if k in ("url", "status", "durationMs")} for h in hits])
        from driver.net_observer import NetObserver
        self.assertIsNone(NetObserver._timing_ms(_FakeResp({})))
        self.assertEqual(5, NetObserver._timing_ms(
            _FakeResp({"responseEnd": 12.5, "start": 7.5})))


class K2契约零漂移(unittest.TestCase):
    def test_console_logs出参字符串形态不变(self):
        outcome = _run([_goto(), _click("#btn-error"), _expect_result("已触发错误")])
        joined = "\n".join(outcome.console_logs)
        self.assertIn("[error] Uncaught TypeError: orderTotal", joined,
                      "K2 出参保持 `[type] text` 字符串（结构化仅内部升级）")


if __name__ == "__main__":
    unittest.main()
