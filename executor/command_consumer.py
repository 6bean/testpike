
from __future__ import annotations

import json
import logging
import os
import subprocess
import sys
import tempfile
from collections.abc import Callable
from dataclasses import dataclass

from protocol import ExecutorProtocolClient

log = logging.getLogger("testpike.executor")

_RECIPE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            os.pardir, "installkit", "recipe.py")
_CMD_TIMEOUT_SEC = 1800


def detect_host(platform: str | None = None) -> str:
    pass
    name = (platform or sys.platform).lower()
    if name.startswith("darwin"):
        return "macos"
    if name.startswith("win"):
        return "windows"
    return "linux"


@dataclass
class CommandContext:
    pass
    host: str
    mirror_base_url: str | None = None
    spawn: Callable | None = None

    def run(self, args):
        if self.spawn is not None:
            return self.spawn(args)
        return subprocess.run(args, capture_output=True, text=True,
                              timeout=_CMD_TIMEOUT_SEC, check=False)

    def run_shell(self, cmd: str):
        if self.spawn is not None:
            return self.spawn(cmd)
        return subprocess.run(cmd, shell=True, capture_output=True, text=True,
                              timeout=_CMD_TIMEOUT_SEC, check=False)


def fetch_engine_commands(client: ExecutorProtocolClient, engine_code: str,
                          action: str, ctx: CommandContext,
                          target_pin: str | None = None) -> list[str]:
    pass
    info = client.engine_recipe(engine_code)
    recipe = info.get("installRecipe") or {}
    argv = [sys.executable, os.path.abspath(_RECIPE_PATH), action,
            "--host", ctx.host]
    if ctx.mirror_base_url:
        argv += ["--mirror-base-url", ctx.mirror_base_url]
    if target_pin:
        argv += ["--target-pin", target_pin]
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False,
                                     encoding="utf-8") as f:
        json.dump(recipe, f, ensure_ascii=False)
        recipe_file = f.name
    try:
        argv += ["--recipe-file", recipe_file]
        proc = ctx.run(argv)
        if getattr(proc, "returncode", 1) != 0:
            raise ValueError((getattr(proc, "stderr", "") or "").strip()
                         or "配方解析失败（recipe.py 非零退出）")
        return [line.strip() for line in
                str(getattr(proc, "stdout", "") or "").splitlines() if line.strip()]
    finally:
        try:
            os.remove(recipe_file)
        except OSError:
            pass


def _engine_codes(cmd: dict) -> list[str]:
    payload = cmd.get("payload") or {}
    engines = payload.get("engines") if isinstance(payload, dict) else None
    return [str(e) for e in (engines or []) if str(e).strip()]


def consume_command(client: ExecutorProtocolClient, cmd: dict,
                    ctx: CommandContext,
                    on_components: Callable | None = None) -> None:
    pass
    command_id = int(cmd["id"])
    claimer_id = int(cmd.get("claimedBy") or client.executor_id or 0)
    ctype = str(cmd.get("commandType") or "")
    try:
        if ctype in ("engine-install", "engine-uninstall",
                     "engine-upgrade", "engine-rollback"):
            _consume_engine(client, command_id, claimer_id, ctype, cmd, ctx,
                            on_components)
        elif ctype == "scale-in":
            client.report_command_progress(command_id, claimer_id, "drain",
                                           "排空确认：已领取退池指令（停派发由平台侧联动）")
            client.complete_command(command_id, claimer_id, True,
                                    "排空回执完成（在跑归零后机器可安全退池）")
        elif ctype == "node-remove":
            client.report_command_progress(command_id, claimer_id, "remove",
                                           "机器移除确认")
            client.complete_command(command_id, claimer_id, True,
                                    "机器移除指令已确认回执")
        else:
            client.complete_command(command_id, claimer_id, False,
                                    f"未知命令型「{ctype}」——执行侧 fail-closed 拒执行"
                                    "（新命令型先扩 03 §2.9 契约再扩消费面）")
    except Exception as e:
        log.error("命令 %s 消费异常: %s", command_id, e)
        try:
            client.complete_command(command_id, claimer_id, False, f"消费异常: {e}")
        except Exception as e2:
            log.error("命令 %s 失败回执同样失败（core 侧兜底）: %s", command_id, e2)


def _consume_engine(client: ExecutorProtocolClient, command_id: int, claimer_id: int,
                    ctype: str, cmd: dict, ctx: CommandContext,
                    on_components: Callable | None) -> None:
    codes = _engine_codes(cmd)
    if not codes:
        client.complete_command(command_id, claimer_id, False,
                                "命令载荷缺 engines 清单——拒执行（防白烧会话）")
        return
    action = {"engine-install": "install", "engine-uninstall": "uninstall",
              "engine-upgrade": "upgrade", "engine-rollback": "upgrade"}[ctype]
    verb = {"install": "安装", "uninstall": "卸载",
            "upgrade": "升级" if ctype == "engine-upgrade" else "回滚"}[action]
    target_pin = None
    if ctype == "engine-rollback":
        payload = cmd.get("payload") or {}
        target_pin = str(payload.get("target_version") or "").strip() if isinstance(
            payload, dict) else ""
        if not target_pin:
            client.complete_command(command_id, claimer_id, False,
                                    "回滚命令缺 target_version（反向钉目标版本）——"
                                    "拒绝执行（版本权威=装机台账，禁凭空猜版本回滚）")
            return
    done: list[str] = []
    for code in codes:
        client.report_command_progress(command_id, claimer_id, action,
                                       f"引擎{verb}中：{code}")
        commands = fetch_engine_commands(client, code, action, ctx,
                                         target_pin=target_pin)
        for shell_cmd in commands:
            proc = ctx.run_shell(shell_cmd)
            rc = getattr(proc, "returncode", 1)
            if rc != 0:
                tail = str(getattr(proc, "stderr", "") or "")[-300:]
                client.complete_command(command_id, claimer_id, False,
                                        f"引擎{verb}失败：{code} 命令「{shell_cmd}」"
                                        f"退出码 exit {127 if rc < 0 else rc}——{tail}")
                return
        done.append(code)
    client.complete_command(command_id, claimer_id, True,
                            f"引擎{verb}成功：{'、'.join(done)}（配方命令全量执行完毕）")
    if on_components is not None:
        try:
            on_components()
        except Exception as e:
            log.warning("装后 capabilities 重报失败（心跳兜底旧清单）: %s", e)


def poll_and_consume(client: ExecutorProtocolClient, ctx: CommandContext,
                     on_components: Callable | None = None) -> int:
    pass
    commands = client.poll_commands()
    for cmd in commands:
        consume_command(client, cmd, ctx, on_components=on_components)
    return len(commands)
