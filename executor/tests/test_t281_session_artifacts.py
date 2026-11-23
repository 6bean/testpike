
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from protocol import ExecutorProtocolClient
from runner import _report_session_artifacts

_EXECUTOR_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_SYS_TIMEOUT = 60

_server: ThreadingHTTPServer | None = None
_base: str = ""


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
        return self.rfile.read(length) if length else b"{}"

    def do_GET(self):
        self._read_body()
        self._route()

    def _route(self):
        if self.path == "/echo-session":
            self._send(200, {"session": self.headers.get("X-Session", "")},
                       extra={"X-Session": self.headers.get("X-Session", "")})
        elif self.path == "/whoami":
            if self.headers.get("Cookie") == "sid=good":
                self._send(200, {"who": "tester"})
            self._send(401, {"error": "session expired"})
        elif self.path == "/login":
            self._send(200, {"data": {"token": "tk-new-9"}})
        else:
            self._send(404, {"error": "no-route"})

    def _send(self, code, payload, extra=None):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def setup_module() -> None:
    global _server, _base
    _server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=_server.serve_forever, daemon=True).start()
    _base = f"http://127.0.0.1:{_server.server_address[1]}"


def teardown_module() -> None:
    if _server:
        _server.shutdown()
        _server.server_close()


def _envelope(assertions: list, *, url_path: str = "/echo-session",
              headers: dict | None = None, session: dict | None = None) -> str:
    env = {"kind": "api-test-run", "runId": 7801, "method": "GET",
           "urlPath": url_path, "headers": headers or {}, "assertions": assertions,
           "envBaseUrl": _base,
           "execPolicy": {"allowHosts": "127.0.0.1", "denyHosts": ""},
           "timeoutMs": 10000}
    if session is not None:
        env["session"] = session
    return json.dumps(env, ensure_ascii=False)


def _run_worker(action_json: str, extra_task: dict | None = None) -> dict:
    pass
    with tempfile.TemporaryDirectory(prefix="tp-t281-") as td:
        task_f = os.path.join(td, "task.json")
        out_f = os.path.join(td, "outcome.json")
        task = {"taskId": 905, "claimToken": "tok-t281", "caseId": 0,
                "taskType": 4, "actionJson": action_json}
        task.update(extra_task or {})
        with open(task_f, "w", encoding="utf-8") as f:
            json.dump(task, f, ensure_ascii=False)
        proc = subprocess.run([sys.executable, os.path.join(_EXECUTOR_DIR, "api_runner.py"),
                               task_f, out_f],
                              capture_output=True, text=True, timeout=_SYS_TIMEOUT,
                              check=False, cwd=_EXECUTOR_DIR)
        assert proc.returncode == 0, \
            f"worker 非零退出: rc={proc.returncode} stderr={proc.stderr[:500]}"
        assert os.path.exists(out_f), f"结果文件未写出: stderr={proc.stderr[:500]}"
        with open(out_f, "r", encoding="utf-8") as f:
            return json.load(f)


class TestSessionArtifactConsume:
    pass

    def test_信封工件注入会话头_pass(self) -> None:
        env = _envelope(
            [{"type": "header", "name": "X-Session", "op": "exact", "expect": "abc"}],
            headers={"X-Session": "{{SESSION:tok}}"})
        outcome = _run_worker(env, {"sessionArtifacts": {"tok": "abc"}})
        assert outcome["result"] == "pass", f"工件注入后应直通: {outcome}"

    def test_缺键_fail闭人话点名键名(self) -> None:
        env = _envelope(
            [{"type": "status", "op": "exact", "expect": 200}],
            headers={"X-Session": "{{SESSION:ghost}}"})
        outcome = _run_worker(env, {"sessionArtifacts": {"tok": "abc"}})
        assert outcome["result"] == "fail", "缺工件键必须 fail-closed（禁原样透传静默跑）"
        assert "ghost" in (outcome["error_detail"] or ""), \
            f"缺键必须一次点名键名: {outcome.get('error_detail')}"

    def test_工件失效401_人话重登续跑归4(self) -> None:
        env = _envelope(
            [{"type": "status", "op": "exact", "expect": 200}],
            url_path="/whoami", headers={"Cookie": "{{SESSION:sid}}"})
        outcome = _run_worker(env, {"sessionArtifacts": {"sid": "stale"}})
        assert outcome["result"] == "fail"
        assert outcome.get("error_type") == 4, \
            f"工件失效=依赖失败归 4（非断言族 1/环境 3）: {outcome}"
        assert "重登续跑" in (outcome["error_detail"] or ""), \
            f"首错回退必须人话「工件失效，重登续跑」: {outcome.get('error_detail')}"

    def test_无声明存量信封零漂移(self) -> None:
        env = _envelope([{"type": "status", "op": "exact", "expect": 200}])
        outcome = _run_worker(env)
        assert outcome["result"] == "pass", f"零 SESSION 引用信封必须不受影响: {outcome}"
        assert not outcome.get("session_artifacts")


class TestSessionArtifactCapture:
    pass

    def test_提取行捕获工件进回注面(self) -> None:
        env = _envelope(
            [{"type": "extract", "path": "$.data.token", "saveAs": "tok",
              "asSession": "login-sess"}],
            url_path="/login")
        outcome = _run_worker(env)
        assert outcome["result"] == "pass", outcome
        assert (outcome.get("session_artifacts") or {}) == {"login-sess": "tk-new-9"}, \
            f"asSession 捕获值必须进回注面: {outcome}"

    def test_无asSession不产工件(self) -> None:
        env = _envelope(
            [{"type": "extract", "path": "$.data.token", "saveAs": "tok"}],
            url_path="/login")
        outcome = _run_worker(env)
        assert outcome["result"] == "pass", outcome
        assert not outcome.get("session_artifacts"), "无 asSession=零回注（防误传凭据）"


class TestProtocolIngest:
    pass

    def test_submit_session_artifacts_载荷含防重放令牌(self) -> None:
        recorded: dict = {}

        class _Api(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                recorded["path"] = self.path
                recorded["body"] = json.loads(self.rfile.read(length))
                body = json.dumps({"code": 0, "data": {"accepted": True}}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        srv = ThreadingHTTPServer(("127.0.0.1", 0), _Api)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            client = ExecutorProtocolClient(f"http://127.0.0.1:{srv.server_address[1]}")
            client.executor_id = 7
            resp = client.submit_session_artifacts(905, "tok-t281", {"k": "v"})
            assert resp.get("accepted") is True
            assert recorded["path"] == "/internal/tasks/session-artifacts"
            assert recorded["body"] == {"taskId": 905, "claimToken": "tok-t281",
                                        "artifacts": {"k": "v"}}
        finally:
            srv.shutdown()
            srv.server_close()


class TestRunnerReportingWiring:
    pass

    def test_有工件才上报且失败不阻断(self) -> None:
        calls: list = []

        class _Client:
            def submit_session_artifacts(self, task_id, claim_token, artifacts):
                if artifacts == {"boom": "x"}:
                    raise RuntimeError("通道不可达")
                calls.append((task_id, claim_token, dict(artifacts)))

        from driver.webdriver import RunOutcome
        _report_session_artifacts(_Client(), 905, "tk",
                                  RunOutcome(result="pass", session_artifacts={}))
        assert calls == [], "空工件不得触通道（防无谓请求）"
        _report_session_artifacts(_Client(), 905, "tk",
                                  RunOutcome(result="pass",
                                             session_artifacts={"k": "v"}))
        assert calls == [(905, "tk", {"k": "v"})]
        _report_session_artifacts(_Client(), 906, "tk",
                                  RunOutcome(result="pass",
                                             session_artifacts={"boom": "x"}))
