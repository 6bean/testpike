
from __future__ import annotations

import pytest

from command_consumer import (
    CommandContext,
    consume_command,
    detect_host,
    fetch_engine_commands,
    poll_and_consume,
)
from protocol import ExecutorProtocolClient, ProtocolError
from tests.test_protocol import _FakeProtocolServer

RECIPE_OK = {"version_pin": "0.0.63",
             "hosts": {"linux": {"install": [{"cmd": "npm install -g mobilewright"}],
                                 "uninstall": [{"cmd": "npm uninstall -g mobilewright"}]}}}


class _FakeProc:
    def __init__(self, rc=0, stdout="", stderr=""):
        self.returncode = rc
        self.stdout = stdout
        self.stderr = stderr


def _spawn_factory(recipe_proc=None, exec_procs=None):
    pass
    calls = []
    recipe_proc = recipe_proc or _FakeProc(0, "npm install -g mobilewright\n")

    def spawn(args):
        calls.append(args)
        if isinstance(args, list):
            return recipe_proc
        return (exec_procs or {}).get(args, _FakeProc(0))
    return spawn, calls


def _client(fake_server, recipe_data=None, recipe_code="mobilewright"):
    fake_server.responses["/internal/executors/register"] = {
        "code": "0", "data": {"executorId": 7}}
    fake_server.responses["/internal/cluster/commands/poll"] = {"code": "0", "data": []}
    fake_server.responses["/internal/cluster/commands/progress"] = {"code": "0", "data": None}
    fake_server.responses["/internal/cluster/commands/result"] = {"code": "0", "data": None}
    info = {"code": recipe_code,
            "installRecipe": recipe_data if recipe_data is not None else RECIPE_OK}
    fake_server.responses[f"/internal/cluster/engine-recipe?code={recipe_code}"] = {
        "code": "0", "data": info}
    client = ExecutorProtocolClient(f"http://127.0.0.1:{fake_server.port}")
    client.executor_id = 7
    return client


def _cmd(command_type="engine-install", cmd_id=12, payload=None):
    return {"id": cmd_id, "commandType": command_type, "targetType": "executor",
            "targetId": 7, "payload": payload if payload is not None
            else {"engines": ["mobilewright"]}, "status": 1, "claimedBy": 7}


@pytest.fixture
def fake_server():
    srv = _FakeProtocolServer()
    yield srv
    srv.stop()


class TestHostAndRecipe:
    def test_detect_host_三平台映射(self):
        assert detect_host("linux") == "linux"
        assert detect_host("darwin") == "macos"
        assert detect_host("win32") == "windows"
        assert detect_host("linux2") == "linux"

    def test_engine_recipe_GET带code参数(self, fake_server):
        client = _client(fake_server)
        info = client.engine_recipe("mobilewright")
        got = [r for r in fake_server.requests
               if r["path"].startswith("/internal/cluster/engine-recipe")]
        assert got[0]["path"] == "/internal/cluster/engine-recipe?code=mobilewright"
        assert info["installRecipe"]["version_pin"] == "0.0.63"

    def test_engine_recipe_未知code业务错上抛(self, fake_server):
        fake_server.responses["/internal/cluster/engine-recipe?code=nope"] = {
            "code": "40401", "msg": "引擎未收录", "data": None}
        client = _client(fake_server)
        with pytest.raises(ProtocolError) as exc:
            client.engine_recipe("nope")
        assert exc.value.code == "40401"

    def test_fetch_engine_commands_子跑recipe并镜像透传(self, fake_server):
        spawn, calls = _spawn_factory(
            _FakeProc(0, "python3 -m pip install minium==1.6.1 -i http://m/simple\n"))
        client = _client(fake_server, recipe_data={
            "version_pin": "1.6.1", "hosts": {"linux": {}}}, recipe_code="minium")
        ctx = CommandContext(host="linux", mirror_base_url="http://m/simple",
                             spawn=spawn)
        cmds = fetch_engine_commands(client, "minium", "install", ctx)
        assert cmds == ["python3 -m pip install minium==1.6.1 -i http://m/simple"]
        argv = calls[0]
        assert argv[1].endswith("recipe.py")
        assert "--host" in argv and "linux" in argv
        assert "--mirror-base-url" in argv and "http://m/simple" in argv
        assert "--recipe-file" in argv

    def test_fetch_engine_commands_配方未实装人话拒(self, fake_server):
        spawn, _calls = _spawn_factory(
            _FakeProc(2, "", "该引擎安装配方未实装（空配方）——拒绝凭空猜安装命令"))
        client = _client(fake_server, recipe_data={})
        ctx = CommandContext(host="linux", spawn=spawn)
        with pytest.raises(ValueError) as exc:
            fetch_engine_commands(client, "mobilewright", "install", ctx)
        assert "未实装" in str(exc.value)


class TestConsumeCommand:
    def test_engine_install_全链成功_打点回执与重报(self, fake_server):
        client = _client(fake_server)
        spawn, calls = _spawn_factory()
        refreshed = []
        ctx = CommandContext(host="linux", spawn=spawn)
        consume_command(client, _cmd(), ctx, on_components=lambda: refreshed.append(1))

        progress = [r["body"] for r in fake_server.requests
                    if r["path"] == "/internal/cluster/commands/progress"]
        assert progress and progress[0]["commandId"] == 12
        assert progress[0]["claimerId"] == 7
        assert progress[0]["step"] == "install"
        assert "mobilewright" in progress[0]["message"]
        result = [r["body"] for r in fake_server.requests
                  if r["path"] == "/internal/cluster/commands/result"]
        assert result[0] == {"commandId": 12, "claimerId": 7, "success": True,
                             "resultDetail": result[0]["resultDetail"]}
        assert "成功" in result[0]["resultDetail"]
        assert calls[-1] == "npm install -g mobilewright"
        assert refreshed == [1], "装毕 capabilities 局部重报必须恰好一次"

    def test_engine_install_命令失败诚实回执False(self, fake_server):
        client = _client(fake_server)
        spawn, _ = _spawn_factory(
            _FakeProc(0, "npm install -g mobilewright\n"),
            {"npm install -g mobilewright": _FakeProc(127, "", "npm: command not found")})
        refreshed = []
        ctx = CommandContext(host="linux", spawn=spawn)
        consume_command(client, _cmd(), ctx, on_components=lambda: refreshed.append(1))
        result = [r["body"] for r in fake_server.requests
                  if r["path"] == "/internal/cluster/commands/result"]
        assert result[0]["success"] is False
        assert "exit 127" in result[0]["resultDetail"]
        assert "command not found" in result[0]["resultDetail"]
        assert refreshed == [], "安装失败不得重报（无件可报）"

    def test_engine_install_空配方人话拒不执行命令(self, fake_server):
        client = _client(fake_server, recipe_data={})
        spawn, _calls = _spawn_factory(
            _FakeProc(2, "", "该引擎安装配方未实装（空配方）——拒绝凭空猜安装命令"))
        ctx = CommandContext(host="linux", spawn=spawn)
        consume_command(client, _cmd(), ctx)
        result = [r["body"] for r in fake_server.requests
                  if r["path"] == "/internal/cluster/commands/result"]
        assert result[0]["success"] is False
        assert "未实装" in result[0]["resultDetail"]

    def test_engine_install_缺engines键拒领白烧(self, fake_server):
        client = _client(fake_server)
        ctx = CommandContext(host="linux", spawn=lambda a: _FakeProc(0))
        consume_command(client, _cmd(payload={}), ctx)
        result = [r["body"] for r in fake_server.requests
                  if r["path"] == "/internal/cluster/commands/result"]
        assert result[0]["success"] is False
        assert "engines" in result[0]["resultDetail"]

    def test_engine_uninstall_走uninstall动作且回执(self, fake_server):
        client = _client(fake_server)
        spawn, calls = _spawn_factory(_FakeProc(0, "npm uninstall -g mobilewright\n"))
        refreshed = []
        ctx = CommandContext(host="linux", spawn=spawn)
        consume_command(client, _cmd("engine-uninstall"), ctx,
                        on_components=lambda: refreshed.append(1))
        argv = next(c for c in calls if isinstance(c, list))
        assert argv[2] == "uninstall"
        result = [r["body"] for r in fake_server.requests
                  if r["path"] == "/internal/cluster/commands/result"]
        assert result[0]["success"] is True
        assert refreshed == [1], "卸毕同样要重报（capabilities 缺件诚实缺席）"

    def test_scale_in_纯回执成功_零配方外呼(self, fake_server):
        client = _client(fake_server)
        calls = []
        ctx = CommandContext(host="linux", spawn=lambda a: calls.append(a) or _FakeProc(0))
        consume_command(client, _cmd("scale-in", payload={}), ctx)
        progress = [r["body"] for r in fake_server.requests
                    if r["path"] == "/internal/cluster/commands/progress"]
        assert progress[0]["step"] == "drain"
        result = [r["body"] for r in fake_server.requests
                  if r["path"] == "/internal/cluster/commands/result"]
        assert result[0]["success"] is True
        assert "排空" in result[0]["resultDetail"]
        assert calls == []

    def test_node_remove_回执成功(self, fake_server):
        client = _client(fake_server)
        ctx = CommandContext(host="linux", spawn=lambda a: _FakeProc(0))
        consume_command(client, _cmd("node-remove", payload={}), ctx)
        result = [r["body"] for r in fake_server.requests
                  if r["path"] == "/internal/cluster/commands/result"]
        assert result[0]["success"] is True

    def test_未知命令型_fail_closed拒执行(self, fake_server):
        client = _client(fake_server)
        ctx = CommandContext(host="linux", spawn=lambda a: _FakeProc(0))
        consume_command(client, _cmd("rm-rf-everything", payload={}), ctx)
        result = [r["body"] for r in fake_server.requests
                  if r["path"] == "/internal/cluster/commands/result"]
        assert result[0]["success"] is False
        assert "rm-rf-everything" in result[0]["resultDetail"]


class TestPollAndConsume:
    def test_poll领取后逐条消费返回处理数(self, fake_server):
        client = _client(fake_server)
        fake_server.responses["/internal/cluster/commands/poll"] = {
            "code": "0", "data": [_cmd("scale-in", 101, payload={}),
                                  _cmd("scale-in", 102, payload={})]}
        ctx = CommandContext(host="linux", spawn=lambda a: _FakeProc(0))
        processed = poll_and_consume(client, ctx)
        assert processed == 2
        results = [r["body"] for r in fake_server.requests
                   if r["path"] == "/internal/cluster/commands/result"]
        assert [r["commandId"] for r in results] == [101, 102]

    def test_单条消费炸不拖垮整轮(self, fake_server):
        client = _client(fake_server)
        fake_server.responses["/internal/cluster/commands/poll"] = {
            "code": "0", "data": [_cmd("engine-install", 101),
                                  _cmd("engine-install", 102)]}

        def spawn(args):
            if isinstance(args, list):
                raise RuntimeError("子跑炸了")
            return _FakeProc(0)
        ctx = CommandContext(host="linux", spawn=spawn)
        processed = poll_and_consume(client, ctx)
        assert processed == 2, "异常命令也要有回执终态（禁白占已领取状态）"
        results = [r["body"] for r in fake_server.requests
                   if r["path"] == "/internal/cluster/commands/result"]
        assert all(r["success"] is False for r in results)

    def test_无命令返回零非炸(self, fake_server):
        client = _client(fake_server)
        ctx = CommandContext(host="linux", spawn=lambda a: _FakeProc(0))
        assert poll_and_consume(client, ctx) == 0
