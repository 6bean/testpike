
from __future__ import annotations

import pytest
from tests.test_protocol import _FakeProtocolServer

from protocol import ExecutorProtocolClient, ProtocolError


@pytest.fixture
def fake_server():
    srv = _FakeProtocolServer()
    yield srv
    srv.stop()


class TestCommandQueueClient:
    def test_poll_commands_executorId直领_返回命令列表(self, fake_server):
        fake_server.responses["/internal/cluster/commands/poll"] = {
            "code": "0", "data": [
                {"id": 12, "commandType": "engine-install", "targetType": "executor",
                 "payload": {"engines": ["mobilewright"]}, "status": 1, "claimedBy": 7}]}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")
        client.executor_id = 7

        cmds = client.poll_commands()

        sent = fake_server.requests[0]
        assert sent["path"] == "/internal/cluster/commands/poll"
        assert sent["body"] == {"executorId": 7, "limit": 5}
        assert cmds[0]["commandType"] == "engine-install"
        assert cmds[0]["payload"]["engines"] == ["mobilewright"]

    def test_poll_commands_limit可调(self, fake_server):
        fake_server.responses["/internal/cluster/commands/poll"] = {"code": "0", "data": []}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")
        client.executor_id = 7

        assert client.poll_commands(limit=1) == []
        assert fake_server.requests[0]["body"]["limit"] == 1

    def test_poll_commands_空data返回空列表非炸(self, fake_server):
        fake_server.responses["/internal/cluster/commands/poll"] = {"code": "0", "data": None}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")
        client.executor_id = 7

        assert client.poll_commands() == []

    def test_progress_序列化commandId_claimerId_step_message(self, fake_server):
        fake_server.responses["/internal/cluster/commands/progress"] = {"code": "0", "data": None}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")

        client.report_command_progress(12, 7, "install-recipe", "配方安装中")

        sent = fake_server.requests[0]
        assert sent["path"] == "/internal/cluster/commands/progress"
        assert sent["body"] == {"commandId": 12, "claimerId": 7,
                                "step": "install-recipe", "message": "配方安装中"}

    def test_progress_message空不出键(self, fake_server):
        fake_server.responses["/internal/cluster/commands/progress"] = {"code": "0", "data": None}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")

        client.report_command_progress(12, 7, "install-recipe")

        assert "message" not in fake_server.requests[0]["body"]

    def test_result_成败布尔与详情尾追(self, fake_server):
        fake_server.responses["/internal/cluster/commands/result"] = {"code": "0", "data": None}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")

        client.complete_command(12, 7, True, "引擎装毕")

        sent = fake_server.requests[0]
        assert sent["path"] == "/internal/cluster/commands/result"
        assert sent["body"] == {"commandId": 12, "claimerId": 7,
                                "success": True, "resultDetail": "引擎装毕"}

    def test_result_失败回执_详情空不出键(self, fake_server):
        fake_server.responses["/internal/cluster/commands/result"] = {"code": "0", "data": None}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")

        client.complete_command(12, 7, False)

        body = fake_server.requests[0]["body"]
        assert body["success"] is False
        assert "resultDetail" not in body

    def test_poll_业务错误码上抛(self, fake_server):
        fake_server.responses["/internal/cluster/commands/poll"] = {
            "code": "40401", "msg": "执行器未注册", "data": None}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")
        client.executor_id = 7

        with pytest.raises(ProtocolError) as exc:
            client.poll_commands()
        assert exc.value.code == "40401"
