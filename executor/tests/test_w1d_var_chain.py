
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from runner import _API_WORKER_PATH

_EXECUTOR_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_SYS_TIMEOUT = 60

_server: ThreadingHTTPServer | None = None
_base: str = ""


class _Handler(BaseHTTPRequestHandler):
    pass

    protocol_version = "HTTP/1.1"

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", 0) or 0)
        return self.rfile.read(length) if length else b"{}"

    def do_GET(self):
        self._route()

    def do_POST(self):
        raw = self._read_body()
        self._route(raw)

    def _route(self, raw: bytes = b"{}"):
        try:
            parsed = json.loads(raw.decode("utf-8") or "{}")
        except ValueError:
            parsed = {}
        if self.path == "/ok":
            body = {"data": {"id": 42}}
        else:
            body = {"path": self.path,
                    "tok": self.headers.get("X-Tok", ""),
                    "oid": parsed.get("orderId")}
        data = json.dumps(body).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

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


def _envelope(assertions: list, *, method: str = "GET", url_path: str = "/echo",
              headers: dict | None = None, body=None,
              vars_: dict | None = None) -> str:
    env = {"kind": "api-test-run", "runId": 7801, "method": method,
           "urlPath": url_path, "headers": headers or {},
           "assertions": assertions, "envBaseUrl": _base,
           "execPolicy": {"allowHosts": "127.0.0.1", "denyHosts": ""},
           "timeoutMs": 10000}
    if body is not None:
        env["body"] = body
    if vars_ is not None:
        env["vars"] = vars_
    return json.dumps(env, ensure_ascii=False)


def _run_worker(action_json: str) -> dict:
    pass
    with tempfile.TemporaryDirectory(prefix="tp-w1d-") as td:
        task_f = os.path.join(td, "task.json")
        out_f = os.path.join(td, "outcome.json")
        task = {"taskId": 906, "claimToken": "tok-w1d", "caseId": 0,
                "taskType": 4, "actionJson": action_json}
        with open(task_f, "w", encoding="utf-8") as f:
            json.dump(task, f, ensure_ascii=False)
        proc = subprocess.run([sys.executable, _API_WORKER_PATH, task_f, out_f],
                              capture_output=True, text=True, timeout=_SYS_TIMEOUT,
                              check=False, cwd=_EXECUTOR_DIR)
        assert proc.returncode == 0, \
            f"worker 非零退出: rc={proc.returncode} stderr={proc.stderr[:500]}"
        assert os.path.exists(out_f), f"结果文件未写出: stderr={proc.stderr[:500]}"
        with open(out_f, "r", encoding="utf-8") as f:
            return json.load(f)


class TestApiRunnerVarChain:
    pass

    def test_种子变量替换路径头与body_全链真发送(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "body", "path": "$.tok", "op": "exact", "expect": "Bearer secret-1"},
             {"type": "body", "path": "$.path", "op": "exact", "expect": "/echo/88"},
             {"type": "body", "path": "$.oid", "op": "exact", "expect": "secret-1"}],
            method="POST", url_path="/echo/{{VAR:oid}}",
            headers={"X-Tok": "Bearer {{VAR:token}}"},
            body={"orderId": "{{VAR:token}}"},
            vars_={"token": "secret-1", "oid": "88"}))
        assert outcome["result"] == "pass", f"种子替换后三行应全过: {outcome}"

    def test_种子值原样注入单段(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "body", "path": "$.tok", "op": "exact", "expect": "secret-1"}],
            headers={"X-Tok": "{{VAR:token}}"},
            vars_={"token": "secret-1"}))
        assert outcome["result"] == "pass", f"单段占位应整串替换: {outcome}"

    def test_未定义变量_fail_closed_归3点名(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "status", "expect": 200}],
            headers={"X-Tok": "{{VAR:nope}}"}))
        assert outcome["result"] == "fail", f"未定义引用必须拒发: {outcome}"
        assert outcome["error_type"] == 3
        detail = outcome.get("error_detail") or ""
        assert "未定义" in detail and "nope" in detail, f"点名缺失键: {detail}"

    def test_无vars键旧信封零回归(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "status", "expect": 200}], headers={"X-Tok": "plain"}))
        assert outcome["result"] == "pass", f"旧信封形状不动: {outcome}"

    def test_extract_值随结果vars键回传(self) -> None:
        pass
        outcome = _run_worker(_envelope(
            [{"type": "extract", "path": "$.data.id", "saveAs": "TOKEN"}],
            url_path="/ok"))
        assert outcome["result"] == "pass", f"{outcome}"
        assert (outcome.get("extracted_vars") or {}).get("TOKEN") == 42, \
            f"extract 值应回传供后续运行装配: {outcome.get('extracted_vars')}"

    def test_种子与提取同池_重名后存覆盖前(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "extract", "path": "$.data.id", "saveAs": "K"}],
            url_path="/ok", vars_={"K": 1}))
        assert outcome["result"] == "pass", f"{outcome}"
        assert (outcome.get("extracted_vars") or {}).get("K") == 42, \
            f"同池后存覆盖前: {outcome.get('extracted_vars')}"


class _CaptureHandler(BaseHTTPRequestHandler):
    pass

    protocol_version = "HTTP/1.1"
    captured: dict = {}

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        _CaptureHandler.captured.update(json.loads(self.rfile.read(length) or b"{}"))
        data = json.dumps({"code": 0, "data": {"accepted": True}}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


class Test协议result回传vars键:
    pass

    def _capture(self, **kwargs) -> dict:
        import protocol
        _CaptureHandler.captured = {}
        srv = ThreadingHTTPServer(("127.0.0.1", 0), _CaptureHandler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            client = protocol.ExecutorProtocolClient(
                f"http://127.0.0.1:{srv.server_address[1]}")
            client.executor_id = 1
            client.submit_result(906, "tok-w1d", "pass", **kwargs)
        finally:
            srv.shutdown()
            srv.server_close()
        return _CaptureHandler.captured

    def test_extracted_vars_出vars键(self) -> None:
        sent = self._capture(extracted_vars={"TOKEN": "abc"})
        assert sent.get("vars") == {"TOKEN": "abc"}, \
            f"03 §2.3.2 result 尾追 vars 键缺失: {sent}"

    def test_空池不出vars键_旧core零影响(self) -> None:
        sent = self._capture()
        assert "vars" not in sent, f"空/None 不出键（尾追兼容姿势）: {sent}"
