
from __future__ import annotations

import pytest
from command_consumer import CommandContext, consume_command
from tests.test_t317c_command_consumer import (
    _client,
    _cmd,
    _FakeProc,
    _spawn_factory,
)

RECIPE_PIN = {"version_pin": "2.0.0",
              "hosts": {"linux": {
                  "install": [{"cmd": "npm install -g demo@2.0.0"}],
                  "upgrade": [{"cmd": "npm install -g demo@2.0.0"}]}}}


@pytest.fixture
def fake_server():
    from tests.test_protocol import _FakeProtocolServer
    srv = _FakeProtocolServer()
    yield srv
    srv.stop()


def _ctx(fake_server, recipe_data=None, exec_procs=None):
    client = _client(fake_server, recipe_data=recipe_data or RECIPE_PIN,
                     recipe_code="demoengine")
    spawn, calls = _spawn_factory(
        recipe_proc=_FakeProc(0, "npm install -g demo@2.0.0\n"),
        exec_procs=exec_procs)
    return client, spawn, calls, CommandContext(host="linux", spawn=spawn)


def _receipts(fake_server):
    return [r for r in fake_server.requests
            if r["path"] == "/internal/cluster/commands/result"]


def test_engine_upgrade_走upgrade动作并成功回执(fake_server):
    client, _spawn, calls, ctx = _ctx(fake_server)
    consume_command(client, _cmd("engine-upgrade", payload={"engines": ["demoengine"]}),
                    ctx, on_components=lambda: None)
    argv = next(c for c in calls if isinstance(c, list))
    assert "upgrade" in argv, f"recipe.py 子跑必须收 upgrade 动作：{argv}"
    r = _receipts(fake_server)[-1]["body"]
    assert r["success"] is True and "升级" in r["resultDetail"]


def test_engine_upgrade_重报能力面一次(fake_server):
    client, _spawn, _calls, ctx = _ctx(fake_server)
    hits = []
    consume_command(client, _cmd("engine-upgrade", payload={"engines": ["demoengine"]}), ctx,
                    on_components=lambda: hits.append(1))
    assert hits == [1], "升级成功=capabilities 局部重探恰一次（S4 尾追先例复用）"


def test_engine_rollback_缺目标版本人话拒(fake_server):
    client, _spawn, calls, ctx = _ctx(fake_server)
    consume_command(client, _cmd("engine-rollback", payload={"engines": ["demoengine"]}),
                    ctx, on_components=lambda: None)
    r = _receipts(fake_server)[-1]["body"]
    assert r["success"] is False
    assert "版本" in r["resultDetail"], "回滚缺 target_version 必须人话点名（禁猜版本假回滚）"
    assert not any(isinstance(c, str) for c in calls), "拒执行=零命令外呼"


def test_engine_rollback_带目标版本_反向钉透传子跑(fake_server):
    client, _spawn, calls, ctx = _ctx(fake_server)
    consume_command(client, _cmd("engine-rollback",
                                 payload={"engines": ["demoengine"],
                                          "target_version": "1.9.0"}),
                    ctx, on_components=lambda: None)
    argv = next(c for c in calls if isinstance(c, list))
    assert "--target-pin" in argv and "1.9.0" in argv, f"反向钉必须透传 recipe 消费面：{argv}"
    r = _receipts(fake_server)[-1]["body"]
    assert r["success"] is True and "回滚" in r["resultDetail"]


def test_engine_rollback_命令失败诚实回执(fake_server):
    client = _client(fake_server, recipe_data=RECIPE_PIN, recipe_code="demoengine")
    spawn, _calls = _spawn_factory(recipe_proc=_FakeProc(0, "npm install -g demo@1.9.0\n"),
                                  exec_procs={"npm install -g demo@1.9.0":
                                              _FakeProc(1, "", "boom")})
    consume_command(client, _cmd("engine-rollback",
                                 payload={"engines": ["demoengine"],
                                          "target_version": "1.9.0"}),
                    CommandContext(host="linux", spawn=spawn),
                    on_components=lambda: None)
    r = _receipts(fake_server)[-1]["body"]
    assert r["success"] is False and "exit 1" in r["resultDetail"]
