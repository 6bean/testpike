
from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Optional

import pytest

from protocol import ExecutorProtocolClient, ProtocolError


class _FakeProtocolServer:
    pass

    def __init__(self) -> None:
        self.requests: list[dict] = []
        self.responses: dict[str, dict] = {}
        self.server = HTTPServer(("127.0.0.1", 0), self._handler_factory())
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def _handler_factory(self):
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(length) if length else b"{}"
                outer.requests.append({"path": self.path, "body": json.loads(raw)})
                self._reply(outer.responses.get(self.path, {"code": "0", "data": {}}))

            def do_GET(self):  # noqa: N802
                outer.requests.append({"path": self.path, "body": None})
                self._reply(outer.responses.get(self.path, {"code": "0", "data": {}}))

            def _reply(self, body: dict) -> None:
                data = json.dumps(body, ensure_ascii=False).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, format: str, *args) -> None:  # noqa: A002, ARG002
                pass

        return Handler

    def stop(self) -> None:
        self.server.shutdown()


@pytest.fixture
def fake_server():
    srv = _FakeProtocolServer()
    yield srv
    srv.stop()


class TestProtocolClient:
    def test_register_解析executorId(self, fake_server):
        fake_server.responses["/internal/executors/register"] = {
            "code": "0", "data": {"ok": True, "executorId": 7, "serverTime": "t"}}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")

        executor_id = client.register({"type": "register", "name": "ex-001", "capacity": 2})

        assert executor_id == 7
        assert client.executor_id == 7
        sent = fake_server.requests[0]
        assert sent["path"] == "/internal/executors/register"
        assert sent["body"]["name"] == "ex-001"

    def test_heartbeat_带executorId查询参数与在跑数(self, fake_server):
        fake_server.responses["/internal/executors/heartbeat?executorId=7"] = {
            "code": "0", "data": {"ok": True, "actions": [], "cancelTaskIds": []}}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")
        client.executor_id = 7

        resp = client.heartbeat(2)

        assert resp["ok"] is True
        assert fake_server.requests[0]["path"] == "/internal/executors/heartbeat?executorId=7"
        assert fake_server.requests[0]["body"]["runningTasks"] == 2

    def test_heartbeat_监看帧尾追键有帧才出键(self, fake_server):
        pass
        fake_server.responses["/internal/executors/heartbeat?executorId=7"] = {
            "code": "0", "data": {"ok": True, "actions": [], "cancelTaskIds": []}}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")
        client.executor_id = 7

        client.heartbeat(1)
        assert "monitorFrames" not in fake_server.requests[0]["body"]

        client.heartbeat(1, monitor_frames=[{"taskId": 9, "stepNo": 1, "image": "aGk="}])
        sent = fake_server.requests[1]["body"]
        assert sent["monitorFrames"] == [{"taskId": 9, "stepNo": 1, "image": "aGk="}]

    def test_claim_无任务返回None(self, fake_server):
        fake_server.responses["/internal/tasks/claim"] = {"code": "0", "data": {"ok": True, "task": None}}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")

        assert client.claim() is None

    def test_claim_有任务返回完整字段(self, fake_server):
        fake_server.responses["/internal/tasks/claim"] = {
            "code": "0", "data": {"ok": True, "task": {
                "taskId": 1001, "caseId": 123, "claimToken": "tk-1",
                "schemaVersion": "v2", "actionJson": "{}", "envBaseUrl": "http://demo"}}}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")

        task = client.claim()

        assert task is not None
        assert task["taskId"] == 1001
        assert task["claimToken"] == "tk-1"
        assert task["schemaVersion"] == "v2"
        assert fake_server.requests[0]["path"] == "/internal/tasks/claim"
        assert fake_server.requests[0]["body"]["executorId"] is None

    def test_submitResult_组装完整载荷(self, fake_server):
        fake_server.responses["/internal/tasks/result"] = {
            "code": "0", "data": {"accepted": True, "needRetry": False, "flaky": False}}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")

        client.submit_result(1001, "tk-1", "pass", steps_timeline=[{"stepNo": 1, "status": "ok"}],
                             screenshots=["exec/1001/s.png"], duration_ms=2500)

        sent = fake_server.requests[0]["body"]
        assert sent["taskId"] == 1001
        assert sent["claimToken"] == "tk-1"
        assert sent["result"] == "pass"
        assert sent["screenshots"] == ["exec/1001/s.png"]
        assert sent["durationMs"] == 2500

    def test_grantArtifact_返回上传地址(self, fake_server):
        fake_server.responses["/internal/tasks/artifact"] = {
            "code": "0", "data": {"taskId": 1001, "objectKey": "exec/1001/s.png",
                                  "uploadUrl": "http://s3obj/presigned"}}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")

        grant = client.grant_artifact(1001, "screenshot", "s.png")

        assert grant["objectKey"] == "exec/1001/s.png"
        assert fake_server.requests[0]["body"]["kind"] == "screenshot"

    def test_业务错误码_抛ProtocolError(self, fake_server):
        fake_server.responses["/internal/tasks/claim"] = {
            "code": "40902", "msg": "任务已被领取", "data": None}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")

        with pytest.raises(ProtocolError) as exc:
            client.claim()
        assert "40902" in str(exc.value)
        assert exc.value.code == "40902"

    def test_未注册错误码_携带code40401(self, fake_server):
        fake_server.responses["/internal/executors/heartbeat?executorId=7"] = {
            "code": "40401", "msg": "执行器未注册", "data": None}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")
        client.executor_id = 7

        with pytest.raises(ProtocolError) as exc:
            client.heartbeat(0)
        assert exc.value.code == "40401"

    def test_网络不可达_抛ProtocolError(self):
        client = ExecutorProtocolClient("http://127.0.0.1:1")

        with pytest.raises(ProtocolError):
            client.claim()
