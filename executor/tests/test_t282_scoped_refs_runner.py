
from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from test_w1b_api_runner import _run_worker

_server: ThreadingHTTPServer | None = None
_base: str = ""


class _EchoHandler(BaseHTTPRequestHandler):
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
        self._respond()

    def do_POST(self):
        self._respond()

    def _respond(self) -> None:
        body = self._read_body()
        payload = {"echoHeader": self.headers.get("X-Scoped", ""),
                   "echoBody": body.decode("utf-8", "replace")}
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *args) -> None:
        pass


def setup_module() -> None:
    global _server, _base
    _server = ThreadingHTTPServer(("127.0.0.1", 0), _EchoHandler)
    threading.Thread(target=_server.serve_forever, daemon=True).start()
    _base = f"http://127.0.0.1:{_server.server_address[1]}"


def teardown_module() -> None:
    if _server:
        _server.shutdown()
        _server.server_close()


def _scoped_envelope(assertions: list, vars_seed: dict, *,
                     header_value: str, body_value=None) -> str:
    env = {"kind": "api-test-run", "runId": 7801, "method": "POST",
           "urlPath": "/echo", "headers": {"X-Scoped": header_value},
           "assertions": assertions, "envBaseUrl": _base,
           "execPolicy": {"allowHosts": "127.0.0.1", "denyHosts": ""},
           "timeoutMs": 10000, "vars": vars_seed}
    if body_value is not None:
        env["body"] = {"order": body_value}
    return json.dumps(env, ensure_ascii=False)


class TestApiRunnerScopedRefs:
    def test_global剥前缀种子与RUN同义形态打到线上(self):
        outcome = _run_worker(_scoped_envelope(
            [{"type": "status", "expect": 200},
             {"type": "body", "path": "$.echoHeader", "op": "exact",
              "expect": "g-99"},
             {"type": "body", "path": "$.echoBody", "op": "contains",
              "expect": "R-001"}],
            vars_seed={"库存": "g-99", "订单号": "R-001"},
            header_value="{{global.库存}}", body_value="{{RUN.订单号}}"))
        assert outcome["result"] == "pass", f"实值未上线: {outcome}"

    def test_global带前缀原键优先命中(self):
        outcome = _run_worker(_scoped_envelope(
            [{"type": "status", "expect": 200},
             {"type": "body", "path": "$.echoHeader", "op": "exact",
              "expect": "pref"}],
            vars_seed={"global.X": "pref", "X": "bare"},
            header_value="{{global.X}}"))
        assert outcome["result"] == "pass", f"带前缀原键未优先: {outcome}"

    def test_global缺键_fail_closed_归3人话点名零实值(self):
        outcome = _run_worker(_scoped_envelope(
            [{"type": "status", "expect": 200}],
            vars_seed={"有值": "secret-1"},
            header_value="{{global.MISSING_KEY}}"))
        assert outcome["result"] == "fail"
        assert outcome["error_type"] == 3
        detail = outcome["error_detail"]
        assert "跨集合变量池中不存在键 MISSING_KEY" in detail, detail
        assert "secret-1" not in detail, "点名信息禁回放池值明文（红线4）"

    def test_未标注VAR形态零回归_链上同store(self):
        outcome = _run_worker(_scoped_envelope(
            [{"type": "status", "expect": 200},
             {"type": "body", "path": "$.echoHeader", "op": "exact",
              "expect": "legacy"}],
            vars_seed={"n1": "legacy"},
            header_value="{{VAR:n1}}"))
        assert outcome["result"] == "pass", f"VAR 旧形态回归破坏: {outcome}"
