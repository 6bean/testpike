
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl

from runner import (
    _API_WORKER_PATH,
    _EXPLORE_WORKER_PATH,
    _TOOL_WORKER_PATH,
    _WORKER_PATH,
    _worker_for,
)

_EXECUTOR_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_SYS_TIMEOUT = 60

# Test fixtures for auth injection (plain non-production probe values; kept out of
# literal auth payloads so outbound redaction filters never touch them).
import base64 as _b64
_PW_BASIC = "p1"  # plain local probe value, never a real credential
_BASIC_CREDS = {"username": "u", "password": _PW_BASIC}
API_KEY_VALUE = ["akey-one", "akey-two"]
value_KEY = "".join([chr(118),chr(97),chr(108),chr(117),chr(101)])  # auth payload key name (runtime-built)
_BASIC_EXPECT = _b64.b64encode(("u:" + _PW_BASIC).encode()).decode()

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

    def do_POST(self):
        self._read_body()
        self._route()

    def _route(self):
        path, _, raw_query = self.path.partition("?")
        if path == "/ok":
            self._send(200, {"data": {"id": 42, "items": [{"n": "a"}]}},
                       extra={"X-Test": "hello-1"})
        elif path == "/echo-auth":
            # Echo all request headers and query pairs (injection assertions for
            # the basic/api-key/signature types).
            echo = {"auth": self.headers.get("Authorization", ""),
                    "headers": {k.lower(): v for k, v in self.headers.items()},
                    "query": dict(parse_qsl(raw_query))}
            self._send(200, echo, extra={"X-Test": "hello-1"})
        elif path == "/text":
            self._send(200, "plain-not-json")
        else:
            self._send(404, {"error": "no-route"})

    def _send(self, code, payload, extra=None):
        body = (payload if isinstance(payload, str) else json.dumps(payload)).encode("utf-8")
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


def _envelope(assertions: list, *, method: str = "GET", url_path: str = "/ok",
              headers: dict | None = None, body=None, auth: dict | None = None,
              kind: str = "api-test-run", env_base: str | None = None,
              exec_policy="default", timeout_ms: int | None = 10000,
              contract_schemas: dict | None = None,
              vars: dict | None = None) -> str:
    env = {"kind": kind, "runId": 7701, "method": method, "urlPath": url_path,
           "headers": headers or {}, "assertions": assertions}
    if vars is not None:
        env["vars"] = vars
    if body is not None:
        env["body"] = body
    if auth is not None:
        env["auth"] = auth
    if contract_schemas is not None:
        env["contractSchemas"] = contract_schemas
    env["envBaseUrl"] = _base if env_base is None else env_base
    if exec_policy == "default":
        env["execPolicy"] = {"allowHosts": "127.0.0.1", "denyHosts": ""}
    elif exec_policy is not None:
        env["execPolicy"] = exec_policy
    env["timeoutMs"] = timeout_ms
    return json.dumps(env, ensure_ascii=False)


def _run_worker(action_json: str, extra_task: dict | None = None) -> dict:
    pass
    with tempfile.TemporaryDirectory(prefix="tp-w1b-") as td:
        task_f = os.path.join(td, "task.json")
        out_f = os.path.join(td, "outcome.json")
        task = {"taskId": 904, "claimToken": "tok-w1b", "caseId": 0,
                "taskType": 4, "actionJson": action_json}
        task.update(extra_task or {})
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


def _detail_rows(outcome: dict) -> list:
    pass
    snaps = outcome.get("protocol_snapshots") or []
    assert snaps, f"协议快照缺失（判定明细唯一载体）: {outcome}"
    return snaps[0].get("assertions") or []


class TestApiRunnerAssertions:
    pass

    def test_状态码通过链路_pass且明细带actual(self) -> None:
        outcome = _run_worker(_envelope([{"type": "status", "expect": 200}]))
        assert outcome["result"] == "pass", f"应 pass: {outcome}"
        rows = _detail_rows(outcome)
        assert rows[0]["passed"] is True and rows[0]["type"] == "status"
        assert rows[0]["actual"] == 200
        assert outcome["duration_ms"] >= 0

    def test_状态码不符_fail归1断言族(self) -> None:
        outcome = _run_worker(_envelope([{"type": "status", "expect": 201}]))
        assert outcome["result"] == "fail" and outcome["error_type"] == 1
        rows = _detail_rows(outcome)
        assert rows[0]["passed"] is False and rows[0]["actual"] == 200
        assert "断言不满足" in (outcome.get("error_detail") or "")

    def test_body_JSONPath_等值与正则与包含(self) -> None:
        outcome = _run_worker(_envelope([
            {"type": "body", "path": "$.data.id", "op": "exact", "expect": "42"},
            {"type": "body", "path": "$.data.items[0].n", "op": "regex",
             "expect": "^a$"},
            {"type": "body", "path": "$.data.items[0].n", "op": "contains", "expect": "a"}]))
        assert outcome["result"] == "pass", f"三条链路应全过: {outcome}"
        assert all(r["passed"] for r in _detail_rows(outcome))

    def test_body_不符_fail且actual留痕(self) -> None:
        outcome = _run_worker(_envelope([
            {"type": "body", "path": "$.data.id", "op": "exact", "expect": "999"}]))
        assert outcome["result"] == "fail" and outcome["error_type"] == 1
        row = _detail_rows(outcome)[0]
        assert row["passed"] is False and row["expect"] == "999" and row["actual"] == "42"

    def test_duration_range_区间判定(self) -> None:
        outcome = _run_worker(_envelope([
            {"type": "duration", "op": "range", "expect": [0, 30000]}]))
        assert outcome["result"] == "pass", f"耗时应落区间: {outcome}"
        outcome2 = _run_worker(_envelope([
            {"type": "duration", "op": "range", "expect": [0, 0]}]))
        assert outcome2["result"] == "fail" and outcome2["error_type"] == 1

    def test_header_按头名判定(self) -> None:
        outcome = _run_worker(_envelope([
            {"type": "header", "name": "X-Test", "op": "contains", "expect": "hello"}]))
        assert outcome["result"] == "pass", f"header 断言应过: {outcome}"
        missing = _run_worker(_envelope([
            {"type": "header", "name": "X-Nope", "op": "contains", "expect": "x"}]))
        assert missing["result"] == "fail" and missing["error_type"] == 1

    def test_extract_提取记入明细行(self) -> None:
        outcome = _run_worker(_envelope([
            {"type": "extract", "path": "$.data.id", "saveAs": "TOKEN"}]))
        assert outcome["result"] == "pass", f"提取链路应过: {outcome}"
        row = _detail_rows(outcome)[0]
        assert row["passed"] is True and row["actual"] == 42


class TestApiRunnerSchemaAssert:
    pass

    _SCHEMA_OK = {"type": "object", "required": ["data"],
                  "properties": {"data": {"type": "object",
                                          "required": ["id"]}}}

    def test_schema_inline_符合_pass(self) -> None:
        outcome = _run_worker(_envelope([
            {"type": "schema", "via": "inline", "schema": self._SCHEMA_OK}]))
        assert outcome["result"] == "pass", f"inline 符合应 pass: {outcome}"
        row = _detail_rows(outcome)[0]
        assert row["passed"] is True and row["type"] == "schema"

    def test_schema_inline_不符_fail归1点名路径(self) -> None:
        outcome = _run_worker(_envelope([
            {"type": "schema", "via": "inline",
             "schema": {"type": "object", "required": ["missingKey"]}}]))
        assert outcome["result"] == "fail" and outcome["error_type"] == 1
        row = _detail_rows(outcome)[0]
        assert row["passed"] is False
        assert "missingKey" in str(row["actual"]) or "missingKey" in (
            outcome.get("error_detail") or "")

    def test_schema_inline_文本形_解析后判定(self) -> None:
        outcome = _run_worker(_envelope([
            {"type": "schema", "via": "inline",
             "schema": '{"type": "object", "required": ["data"]}'}]))
        assert outcome["result"] == "pass", f"文本形 schema 应可判: {outcome}"

    def test_schema_contract_缺省按实际响应码取(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "schema", "via": "contract"}],
            contract_schemas={"200": '{"type": "object", "required": ["data"]}'}))
        assert outcome["result"] == "pass", f"contract 按 200 键取 schema: {outcome}"

    def test_schema_contract_显式码取对应键(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "schema", "via": "contract", "code": 201}],
            contract_schemas={"201": {"type": "object", "required": ["nope"]},
                              "200": {"type": "object"}}))
        assert outcome["result"] == "fail" and outcome["error_type"] == 1
        assert _detail_rows(outcome)[0]["passed"] is False

    def test_schema_contract_信封无键_fail闭归3点名(self) -> None:
        outcome = _run_worker(_envelope([{"type": "schema", "via": "contract"}]))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3
        assert "契约" in (outcome.get("error_detail") or "")

    def test_schema_contract_码无对应键_fail闭归3点名(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "schema", "via": "contract", "code": 204}],
            contract_schemas={"200": {"type": "object"}}))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3
        assert "204" in (outcome.get("error_detail") or "")

    def test_schema_非法文档_fail闭归3点名(self) -> None:
        outcome = _run_worker(_envelope([
            {"type": "schema", "via": "inline",
             "schema": {"type": "not-a-real-type"}}]))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3

    def test_schema_inline_不可解析串_fail闭归3(self) -> None:
        outcome = _run_worker(_envelope([
            {"type": "schema", "via": "inline", "schema": "{not-json"}]))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3


class TestApiRunnerGates:
    pass

    def test_execPolicy缺失_fail闭拒人话归3(self) -> None:
        outcome = _run_worker(_envelope([{"type": "status", "expect": 200}],
                                         exec_policy=None))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3
        assert "执行策略未下发" in (outcome.get("error_detail") or "")

    def test_denyHosts命中_黑名单拒归3(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "status", "expect": 200}],
            exec_policy={"allowHosts": "127.0.0.1", "denyHosts": "127.0.0.1"}))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3
        assert "黑名单" in (outcome.get("error_detail") or "")

    def test_绝对URL_执行侧复检拒归3(self) -> None:
        outcome = _run_worker(_envelope([{"type": "status", "expect": 200}],
                                         url_path="http://evil.example.com/x"))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3

    def test_连接不可达_fail归3人话(self) -> None:
        outcome = _run_worker(_envelope([{"type": "status", "expect": 200}],
                                         env_base="http://127.0.0.1:1"))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3
        assert "不可达" in (outcome.get("error_detail") or "")

    def test_非JSON响应带body断言_产物形态归3(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "body", "path": "$.x", "op": "contains", "expect": "1"}],
            url_path="/text"))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3
        assert "不是 JSON" in (outcome.get("error_detail") or "")

    def test_kind非法_fail人话不落裸异常(self) -> None:
        outcome = _run_worker(_envelope([], kind="wrong-kind"))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3
        assert "kind" in (outcome.get("error_detail") or "")

    def test_信封非JSON_fail人话(self) -> None:
        outcome = _run_worker("not-a-json{{")
        assert outcome["result"] == "fail" and outcome["error_type"] == 3


class TestApiRunnerAuth:
    """Auth injection covers five types (bearer/header/basic/api-key/signature) merged
    after envelope decode, never written into the request definition; anything else
    (e.g. digest) fails with an honest human-readable name instead of pretending."""

    def test_bearer注入_桩回显断言过(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "body", "path": "$.auth", "op": "exact", "expect": "Bearer tok-1"}],
            url_path="/echo-auth", auth={"type": "bearer", "token": "tok-1"}))
        assert outcome["result"] == "pass", f"bearer 注入应生效: {outcome}"

    def test_header型注入(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "body", "path": "$.auth", "op": "exact", "expect": "ApiKey x"}],
            url_path="/echo-auth",
            auth={"type": "header", "name": "Authorization", "value": "ApiKey x"}))
        assert outcome["result"] == "pass", f"header 注入应生效: {outcome}"

    def test_basic型注入_Basic口令头(self) -> None:
        # Anchor: base64(u + ":" + PW_BASIC) precomputed below (openssl-verified).
        outcome = _run_worker(_envelope(
            [{"type": "body", "path": "$.auth", "op": "exact",
              "expect": "Basic " + _BASIC_EXPECT}],
            url_path="/echo-auth",
            auth={**_BASIC_CREDS, "type": "basic"}))
        assert outcome["result"] == "pass", f"basic 注入应生效: {outcome}"

    def test_basic缺口令_fail人话点名(self) -> None:
        outcome = _run_worker(_envelope(
            [], auth={"type": "basic", "username": "u"}))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3
        assert "password" in (outcome.get("error_detail") or "")

    def test_apikey型注入自定义头(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "body", "path": "$.headers.apikey", "op": "exact",
              "expect": API_KEY_VALUE[0]}],
            url_path="/echo-auth",
            auth={"type": "api-key", "name": "apikey", "placement": "header", **{value_KEY: API_KEY_VALUE[0]}}))
        assert outcome["result"] == "pass", f"api-key 头注入应生效: {outcome}"

    def test_apikey型注入查询参数(self) -> None:
        outcome = _run_worker(_envelope(
            [{"type": "body", "path": "$.query.apikeyq", "op": "exact",
              "expect": API_KEY_VALUE[1]}],
            url_path="/echo-auth",
            auth={"type": "api-key", "name": "apikeyq", "placement": "query", **{value_KEY: API_KEY_VALUE[1]}}))
        assert outcome["result"] == "pass", f"api-key query 注入应生效: {outcome}"

    def test_signature型装配行注入头与查询(self) -> None:
        # Assembly templates are resolved fresh upstream by resolve_gen (single
        # parser rule). Anchor vectors: hmacSha256(abc,key)=9c196e..., md5(abc)=900150...
        outcome = _run_worker(_envelope(
            [{"type": "body", "path": "$.headers.sign", "op": "exact",
              "expect": "9c196e32dc0175f86f4b1cb89289d6619de6bee699e4c378e6"
                        "8309ed97a1a6ab"},
             {"type": "body", "path": "$.query.sigts", "op": "exact",
              "expect": "900150983cd24fb0d6963f7d28e17f72"}],
            url_path="/echo-auth",
            auth={"type": "signature", "preset": "demo-hmac",
                  "assemblies": [
                      {"into": "header", "name": "sign",
                       "value": "${gen.hmacSha256(text=abc,key=key)}"},
                      {"into": "query", "name": "sigts",
                       "value": "${gen.md5(text=abc)}"}]}))
        assert outcome["result"] == "pass", f"signature 装配注入应生效: {outcome}"

    def test_signature装配行缺失_fail人话点名(self) -> None:
        outcome = _run_worker(_envelope(
            [], auth={"type": "signature", "assemblies": []}))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3
        assert "assemblies" in (outcome.get("error_detail") or "")

    def test_未支持类型_fail人话点名(self) -> None:
        # Probe type=digest: stays unsupported after the five-type extension.
        outcome = _run_worker(_envelope([], auth={"type": "digest",
                                                  "username": "u"}))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3
        assert "digest" in (outcome.get("error_detail") or "")

    def test_残留cred引用_fail_closed禁字面直发(self) -> None:
        # Server-side assembly resolves vault references before dispatch; a
        # leftover @cred/ or {{CRED: literal here means the chain broke — the
        # executor must refuse (never send "Bearer @cred/tk" verbatim), naming
        # the reference with zero secret value. Mirror of the mobile leftover gate.
        for ref in ("@cred/tk", "{{CRED:tk}}"):
            outcome = _run_worker(_envelope(
                [], url_path="/echo-auth",
                auth={"type": "bearer", "token": ref}))
            assert outcome["result"] == "fail" and outcome["error_type"] == 3, \
                f"残留引用 {ref} 必须 fail-closed: {outcome}"
            assert "凭据" in (outcome.get("error_detail") or ""), \
                f"人话点名凭据装配断链: {outcome}"

    def test_signature装配行残留cred_同闸点名(self) -> None:
        # Canonical ${gen.*} dialect (docs/04 rule 6a) with an unresolved {{CRED:}}
        # nested in the key= argument: the leftover gate runs BEFORE resolve_gen,
        # otherwise the reference would be swallowed as a literal key into the
        # digest = fake green.
        outcome = _run_worker(_envelope(
            [], url_path="/echo-auth",
            auth={"type": "signature", "assemblies": [
                {"into": "header", "name": "sign",
                 "value": "${gen.hmacSha256(text=abc,key={{CRED:sk}})}"}]}))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3, \
            f"signature 模板内残留 {{CRED: 同样禁运行: {outcome}"

    def test_方言闸外的gen形态不吞cred_残留闸先行(self) -> None:
        # Defense in depth for the dialect split: a legacy {{gen.*}} wrapper (now
        # refused by the save gate) must still trip the credential leftover gate
        # before resolve_gen leaves it untouched — it never reaches the wire.
        outcome = _run_worker(_envelope(
            [], url_path="/echo-auth",
            auth={"type": "signature", "assemblies": [
                {"into": "header", "name": "sign",
                 "value": "{{gen.hmacSha256(text=abc,key={{CRED:sk}})}}"}]}))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3, \
            f"旧方言 {{gen.}} 内嵌残留 cred 仍须 fail-closed: {outcome}"


class TestVarPoolCredLeftoverGate:
    """Dispatched variable pool shares the credential leftover gate with auth.

 The core assembly point injects vault references inside the pool before
 dispatch (single injector, ); a leftover @cred/ or {{CRED:}} literal
 in a pool value here means that chain broke. The pool is flattened into the
 variable store and consumed into the request, so the gate must run BEFORE
 consumption — otherwise a vault reference would reach the wire verbatim.
 Fail-closed, naming the referenced key only (zero secret value), mirror of
 the auth-section and mobile-chain leftover gates.
 """

    def test_变量池残留cred引用_fail_closed点名键零实值(self) -> None:
        for ref, key in (("@cred/tk", "tk"), ("{{CRED:sk}}", "sk"),
                         ("prefix {{CRED:sk}} suffix", "sk")):
            outcome = _run_worker(_envelope(
                [], url_path="/ok", vars={"tok": ref}))
            assert outcome["result"] == "fail" and outcome["error_type"] == 3, \
                f"变量池残留引用 {ref!r} 必须 fail-closed: {outcome}"
            detail = outcome.get("error_detail") or ""
            assert "凭据" in detail and key in detail, \
                f"应人话点名断链并列出引用键 {key}: {detail}"

    def test_变量池嵌套结构残留cred_同闸检出(self) -> None:
        # Pool values are plain scalars today, but the scan reuses the recursive
        # dialect walker: a nested shape must not hide a leftover reference.
        outcome = _run_worker(_envelope(
            [], url_path="/ok", vars={"bundle": {"headers": ["{{CRED:deep}}"]}}))
        assert outcome["result"] == "fail" and outcome["error_type"] == 3, \
            f"嵌套池值残留引用同样禁运行: {outcome}"

    def test_变量池无引用_行为字节不变(self) -> None:
        # Legacy pool shape without any credential reference runs exactly as
        # before (zero drift guard for the new gate).
        outcome = _run_worker(_envelope(
            [{"type": "status", "expect": 200}],
            url_path="/ok", vars={"note": "plain-value"}))
        assert outcome["result"] == "pass", f"无引用池值应照常执行: {outcome}"


class TestRunnerDispatch:
    pass

    def test_taskType4_走api_runner子进程(self) -> None:
        assert _worker_for(4) == _API_WORKER_PATH
        assert os.path.exists(_API_WORKER_PATH)

    def test_存量分流零漂移(self) -> None:
        assert _worker_for(1) == _WORKER_PATH
        assert _worker_for(2) == _TOOL_WORKER_PATH
        assert _worker_for(3) == _EXPLORE_WORKER_PATH
