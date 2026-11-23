
from __future__ import annotations

import logging
import subprocess
from collections.abc import Callable
from dataclasses import dataclass

from driver.mobile.takeover_input_contract import (
    TakeoverInputError,
    plan_input_argv,
    validate_takeover_command,
)

log = logging.getLogger("testpike.executor")

_INJECT_TIMEOUT_SEC = 10


@dataclass
class TakeoverInputOutcome:
    pass

    injected: int = 0
    dropped: int = 0
    failed: int = 0


def _default_spawn(argv: list[str]):
    return subprocess.run(argv, capture_output=True, text=True,
                          timeout=_INJECT_TIMEOUT_SEC, check=False)


def _returncode(proc) -> int:
    pass
    if isinstance(proc, dict):
        return int(proc.get("returncode", 0))
    return int(getattr(proc, "returncode", 0))


def poll_and_inject_takeover_input(client, spawn: Callable | None = None,
                                   limit: int = 10) -> TakeoverInputOutcome:
    pass
    run = spawn or _default_spawn
    outcome = TakeoverInputOutcome()
    for cmd in client.poll_takeover_input(limit=limit):
        why = validate_takeover_command(cmd)
        if why is not None:
            outcome.dropped += 1
            log.info("接管输入指令丢弃（词表闸）: %s", why)
            continue
        try:
            argv = plan_input_argv(cmd)
        except TakeoverInputError as e:
            outcome.dropped += 1
            log.info("接管输入指令丢弃（装配闸）: %s", e)
            continue
        try:
            proc = run(argv)
        except (OSError, subprocess.SubprocessError) as e:
            outcome.failed += 1
            log.warning("接管输入注入失败（载体触达异常，%s）: %s", argv[0], e)
            continue
        if _returncode(proc) != 0:
            outcome.failed += 1
            tail = str(getattr(proc, "stderr", "") or "")[-200:]
            log.warning("接管输入注入非零退出（%s）: %s", " ".join(argv), tail)
            continue
        outcome.injected += 1
    return outcome
