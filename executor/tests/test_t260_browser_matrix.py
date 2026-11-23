
from __future__ import annotations

import json
import tempfile
from unittest import mock

import pytest

from driver.webdriver import BrowserLaunchError, WebDriverExecutor

_DEMO_ACTION = json.dumps({
    "version": "v2",
    "steps": [{"action": "goto", "target": {"url": "about:blank"}}],
}, ensure_ascii=False)


def _launch_failure_side_effect(exc_cls, msg: str):
    pass
    def _fail(**kwargs):
        raise exc_cls(msg)
    return _fail


class TestBrowserDispatch:
    pass

    def test_字段缺失_回落chromium_行为零破坏(self):
        pass
        ex = WebDriverExecutor(headless=True)
        assert ex.browser == "chromium", "缺省必须=chromium（尾追兼容钉）"

    def test_空串与None_回落chromium(self):
        assert WebDriverExecutor(headless=True, browser="").browser == "chromium"
        assert WebDriverExecutor(headless=True, browser=None).browser == "chromium"

    def test_白名单外内核名_人话点名合法集_禁静默降级(self):
        ex = WebDriverExecutor(headless=True, browser="edge")
        with pytest.raises(BrowserLaunchError) as ei:
            ex.start()
        detail = str(ei.value)
        assert "edge" in detail, "必须点名非法内核名"
        for k in ("chromium", "firefox", "webkit"):
            assert k in detail, "人话必须列明合法内核集（信息完整不截断）"
        ex.stop()

    def test_firefox_真起_关闭无残留(self):
        pass
        ex = WebDriverExecutor(headless=True, browser="firefox")
        try:
            ex.start()
            probe = ex._context.new_page()
            try:
                probe.goto("about:blank")
                ua = probe.evaluate("navigator.userAgent")
            finally:
                probe.close()
            assert "firefox" in ua.lower(), f"真实内核应为 firefox，UA={ua}"
            outcome = ex.run(_DEMO_ACTION)
            assert outcome.result == "pass", outcome.error_detail
        finally:
            ex.stop()

    def test_内核缺失_人话点名内核名_禁静默换内核(self):
        pass
        ex = WebDriverExecutor(headless=True, browser="webkit")
        fake_pw = mock.Mock()
        fake_pw.webkit.launch.side_effect = _launch_failure_side_effect(
            Exception, "error while loading shared libraries: libsecret-1.so.0")
        fake_pw.chromium = None
        with mock.patch("playwright.sync_api.sync_playwright") as sp:
            sp.return_value.start.return_value = fake_pw
            with pytest.raises(BrowserLaunchError) as ei:
                ex.start()
        detail = str(ei.value)
        assert "webkit" in detail and "执行机缺少浏览器内核" in detail, detail
        assert "libsecret" not in detail or "webkit" in detail

    def test_launch异常_错误细节附带_不落凭据(self):
        pass
        ex = WebDriverExecutor(headless=True, browser="firefox")
        fake_pw = mock.Mock()
        fake_pw.firefox.launch.side_effect = _launch_failure_side_effect(
            Exception, "FF crashed code -11")
        with mock.patch("playwright.sync_api.sync_playwright") as sp:
            sp.return_value.start.return_value = fake_pw
            with pytest.raises(BrowserLaunchError) as ei:
                ex.start()
        assert "FF crashed code -11" in str(ei.value)


class TestTaskWorkerBrowserChannel:
    pass

    def _run_worker(self, task: dict) -> dict:
        with tempfile.TemporaryDirectory() as d:
            tf, of = f"{d}/task.json", f"{d}/outcome.json"
            with open(tf, "w", encoding="utf-8") as f:
                json.dump(task, f, ensure_ascii=False)
            import task_worker
            with mock.patch.object(task_worker, "WebDriverExecutor") as kls:
                inst = kls.return_value
                inst.run.return_value = __import__("driver.webdriver",
                                                   fromlist=["RunOutcome"]
                                                   ).RunOutcome(result="pass")
                rc = task_worker.main(["task_worker.py", tf, of])
            assert rc == 0
            ctor_kwargs = kls.call_args.kwargs
            with open(of, encoding="utf-8") as f:
                outcome = json.load(f)
            return {"ctor": ctor_kwargs, "outcome": outcome}

    def test_claim带browser_透传构造(self):
        r = self._run_worker({"actionJson": "{}", "caseId": 1, "browser": "firefox"})
        assert r["ctor"].get("browser") == "firefox"
        assert r["outcome"]["result"] == "pass"

    def test_claim缺browser键_不透传保持现行为(self):
        r = self._run_worker({"actionJson": "{}", "caseId": 1})
        assert r["ctor"].get("browser") in (None, "chromium")

    def test_内核缺失_回传fail且error_type环境类_人话不降级(self):
        pass
        from driver.webdriver import BrowserLaunchError
        with tempfile.TemporaryDirectory() as d:
            tf, of = f"{d}/task.json", f"{d}/outcome.json"
            with open(tf, "w", encoding="utf-8") as f:
                json.dump({"actionJson": "{}", "caseId": 1, "browser": "webkit"}, f)
            import task_worker
            with mock.patch.object(task_worker, "WebDriverExecutor") as kls:
                kls.return_value.start.side_effect = BrowserLaunchError(
                    "执行机缺少浏览器内核 webkit（launch 失败：libsecret 缺）")
                rc = task_worker.main(["task_worker.py", tf, of])
            assert rc == 0, "任何失败必回传落终态，不向 stderr 抛栈"
            with open(of, encoding="utf-8") as f:
                outcome = json.load(f)
        assert outcome["result"] == "fail"
        assert outcome["error_type"] == 3, "内核缺失=环境问题类（03 错误分型）"
        assert "webkit" in outcome["error_detail"]
