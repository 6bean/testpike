
from __future__ import annotations

import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

import pytest

from protocol import ExecutorProtocolClient
from takeover_input_consumer import poll_and_inject_takeover_input
from tests.test_protocol import _FakeProtocolServer


@pytest.fixture
def fake_server():
    srv = _FakeProtocolServer()
    yield srv
    srv.stop()


class TestTakeoverInputPollClient:
    def test_poll_扇出形态_executorId加limit直传(self, fake_server):
        fake_server.responses["/internal/appauto/takeover/input/poll"] = {
            "code": "0", "data": [{"platform": "ANDROID", "verb": "tap",
                                   "x": 1, "y": 2, "serial": "s1"}]}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")
        client.executor_id = 7

        cmds = client.poll_takeover_input(limit=10)

        sent = fake_server.requests[0]
        assert sent["path"] == "/internal/appauto/takeover/input/poll"
        assert sent["body"] == {"executorId": 7, "limit": 10}
        assert cmds == [{"platform": "ANDROID", "verb": "tap",
                         "x": 1, "y": 2, "serial": "s1"}]

    def test_poll_空data返回空列表非炸(self, fake_server):
        pass
        fake_server.responses["/internal/appauto/takeover/input/poll"] = {
            "code": "0", "data": None}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")
        client.executor_id = 7
        assert client.poll_takeover_input() == []

    def test_poll_data为列表原样透传(self, fake_server):
        fake_server.responses["/internal/appauto/takeover/input/poll"] = {
            "code": "0", "data": []}
        client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")
        client.executor_id = 7
        assert client.poll_takeover_input(limit=3) == []


class _FakeClient:
    pass

    def __init__(self, commands):
        self._commands = commands
        self.calls = 0

    def poll_takeover_input(self, limit: int = 10) -> list[dict]:
        self.calls += 1
        return list(self._commands)


class _RecordingSpawn:
    def __init__(self, returncode=0, exc=None):
        self.argvs: list[list[str]] = []
        self.returncode = returncode
        self.exc = exc

    def __call__(self, argv, **kwargs):
        self.argvs.append(list(argv))
        if self.exc is not None:
            raise self.exc
        return {"returncode": self.returncode}


_TAP = {"platform": "ANDROID", "verb": "tap", "x": 100, "y": 200, "serial": "s1"}


def test_好指令_装配并真执行注入():
    spawn = _RecordingSpawn()
    res = poll_and_inject_takeover_input(_FakeClient([dict(_TAP)]), spawn=spawn)
    assert res.injected == 1
    assert res.dropped == 0
    assert spawn.argvs == [["adb", "-s", "s1", "shell", "input", "tap",
                            "100", "200"]]


def test_坏指令诚实丢弃不触子进程():
    spawn = _RecordingSpawn()
    bad = {"platform": "ANDROID", "verb": "shake", "x": 1, "y": 2}
    res = poll_and_inject_takeover_input(_FakeClient([bad, dict(_TAP)]), spawn=spawn)
    assert res.dropped == 1
    assert res.injected == 1
    assert len(spawn.argvs) == 1


def test_无注入面平台丢弃计数():
    spawn = _RecordingSpawn()
    cmd = {"platform": "MP", "verb": "tap", "x": 1, "y": 2}
    res = poll_and_inject_takeover_input(_FakeClient([cmd]), spawn=spawn)
    assert res.dropped == 1
    assert spawn.argvs == []


def test_子进程非零退出计失败不炸一轮():
    spawn = _RecordingSpawn(returncode=1)
    res = poll_and_inject_takeover_input(_FakeClient([dict(_TAP)]), spawn=spawn)
    assert res.injected == 0
    assert res.failed == 1


def test_adb缺件oserror计失败():
    spawn = _RecordingSpawn(exc=OSError("adb not found"))
    res = poll_and_inject_takeover_input(_FakeClient([dict(_TAP)]), spawn=spawn)
    assert res.failed == 1
    assert res.injected == 0


def test_空领取零触达():
    client = _FakeClient([])
    spawn = _RecordingSpawn()
    res = poll_and_inject_takeover_input(client, spawn=spawn)
    assert (res.injected, res.dropped, res.failed) == (0, 0, 0)
    assert client.calls == 1
