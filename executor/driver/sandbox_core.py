
from __future__ import annotations

import os
import signal
import subprocess
import sys
import tempfile
from dataclasses import dataclass

STDERR_MAX_CHARS = 4096
STDOUT_MAX_CHARS = 64 * 1024


@dataclass
class SandboxResult:
    pass
    returncode: int
    stdout: str
    stderr: str
    timed_out: bool


def clip_stderr(text: str) -> str:
    pass
    if len(text) <= STDERR_MAX_CHARS:
        return text
    return "…[前段省略]" + text[-STDERR_MAX_CHARS:]


def _clip_stdout(text: str) -> str:
    if len(text) <= STDOUT_MAX_CHARS:
        return text
    return "…[前段省略]" + text[-STDOUT_MAX_CHARS:]


def run_sandboxed_script(script: str, entry: str, input_text: str = "",
                         budget_ms: float = 30000) -> SandboxResult:
    pass
    budget_sec = max(float(budget_ms or 30000), 100.0) / 1000.0
    with tempfile.TemporaryDirectory(prefix="tp-sandbox-") as td:
        script_path = os.path.join(td, entry)
        with open(script_path, "w", encoding="utf-8") as f:
            f.write(script)
        proc = subprocess.Popen([sys.executable, script_path],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, cwd=td,
                                start_new_session=True)
        timed_out = False
        try:
            out, err = proc.communicate(input=input_text, timeout=budget_sec)
        except subprocess.TimeoutExpired:
            timed_out = True
            _kill_process_group(proc.pid)
            try:
                out, err = proc.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                out, err = "", ""
        return SandboxResult(returncode=proc.returncode,
                             stdout=_clip_stdout(out or ""),
                             stderr=clip_stderr(err or ""),
                             timed_out=timed_out)


def _kill_process_group(pid: int) -> None:
    pass
    if not pid:
        return
    try:
        os.killpg(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    except OSError as e:
        import logging
        logging.getLogger("testpike.executor").warning(
            "沙箱进程组 %s 定向清理失败（降级等待自然退出）: %s", pid, e)
