"""Cold/hot app launch duration recorder (W5 phase-two B6 launch-timing chain).

Vocabulary: ``am start -W`` prints Status/LaunchState/TotalTime/WaitTime lines;
TotalTime is the app-side launch duration in ms and WaitTime includes the prior
process teardown. Cold mode force-stops the package first so the process starts
from zero (the industry cold-start KPI); hot mode re-enters the running task.

Sample rows ride the perf session directory as tp-perf-launches.jsonl with the
exact perf_probe line shape (ts/mono dual clocks + tsBase/monoBase anchors +
serial/pkg) plus ``kind=launch`` and ``launchMode``, so the app.perf assertion
can consume launchTotalMs/launchWaitMs without a second reader. The launch
sink is a separate file on purpose: the periodic probe loop keeps appending
rows that never carry launch keys, so reading "the newest sample row" for a
launch metric would race against the probe and fail honestly-but-flakily.

Honesty rules (strict-testing): no adb binary, non-zero exit, a launch report
whose Status is not ok or whose TotalTime is unreadable all fail with the
concrete reason named; rows are written only from a real reading (no 0-fill,
no fabricated observation). The launcher component is resolved via
``cmd package resolve-activity --brief`` and falls back to the explicit
MAIN/LAUNCHER intent argv when the resolver output is unparseable.
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import threading
import time

# am start -W blocks until the activity is drawn; slow emulators can take
# tens of seconds, so the launch route gets its own (larger) command budget.
_LAUNCH_CMD_TIMEOUT = 40.0
_LIGHT_CMD_TIMEOUT = 10.0

# Whitelist key-face of the `am start -W` report (output-format drift wall:
# only these lines are believed, anything else is skipped honestly).
LAUNCH_TOTAL_MS_RE = re.compile(r"TotalTime:\s*(\d+)")
_WAIT_TIME_RE = re.compile(r"WaitTime:\s*(\d+)")
_STATUS_RE = re.compile(r"Status:\s*(\S+)")
_STATE_RE = re.compile(r"LaunchState:\s*(\S+)")

# resolve-activity --brief answer face: a "package/class" component token
# anywhere in the output (the --brief form is the last line; resolver
# wrapper wording variants are tolerated because we search, not segment).
_COMPONENT_RE = re.compile(r"[\w.\-$]+/[\w.\-$]+")

# Step-facing modes (device-reported LaunchState WARM/HOT/UNKNOWN is a
# measurement, not an input; the input vocabulary stays cold|hot).
_MODES = ("cold", "hot")


def parse_launch_wait(text: str | None) -> dict | None:
    """Parse one `am start -W` report -> {launchTotalMs[,launchWaitMs,launchState]}.

    Status other than "ok" or a missing TotalTime means the reading does not
    exist: return None (never guess, never zero-fill). A drifted or absent
    LaunchState line only drops that key (the duration stays valid).
    """
    if not text:
        return None
    status_m = _STATUS_RE.search(text)
    if status_m is None or status_m.group(1).lower() != "ok":
        return None
    total_m = LAUNCH_TOTAL_MS_RE.search(text)
    if total_m is None:
        return None
    row: dict[str, object] = {"launchTotalMs": int(total_m.group(1))}
    wait_m = _WAIT_TIME_RE.search(text)
    if wait_m is not None:
        row["launchWaitMs"] = int(wait_m.group(1))
    state_m = _STATE_RE.search(text)
    if state_m is not None:
        row["launchState"] = state_m.group(1).lower()
    return row


def parse_resolve_activity(text: str | None) -> str | None:
    """Extract the "pkg/class" launcher component from resolver output; None=absent."""
    m = _COMPONENT_RE.search(text or "")
    return m.group(0) if m else None


class LaunchPerfRecorder:
    """One-step cold/hot launch timing recorder over the adb channel.

    argv planning is pure (L2 asserts it directly); the subprocess route is the
    honest fail path when adb is missing or the device reports garbage.
    """

    def __init__(self, serial: str | None, pkg: str, sink_path: str,
                 ts_base: float, mono_base: float):
        self.serial = serial
        self.pkg = pkg
        self.sink_path = sink_path
        self.ts_base = ts_base
        self.mono_base = mono_base
        self._sink_lock = threading.Lock()

    # ── argv plan face (pure functions; entries after the "adb" binary) ──

    def _device_prefix(self) -> list[str]:
        return ["-s", self.serial] if self.serial else []

    def force_stop_argv(self) -> list[str]:
        return self._device_prefix() + ["shell", "am", "force-stop", self.pkg]

    def resolve_component_argv(self) -> list[str]:
        return self._device_prefix() + [
            "shell", "cmd", "package", "resolve-activity", "--brief", self.pkg]

    def start_wait_argv(self, component: str | None) -> list[str]:
        base = self._device_prefix() + ["shell", "am", "start", "-W"]
        if component:
            return base + ["-n", component]
        return base + ["-a", "android.intent.action.MAIN",
                       "-c", "android.intent.category.LAUNCHER",
                       "-p", self.pkg]

    # ── subprocess route ──

    def _run(self, argv: list[str], timeout: float) -> tuple[int | None, str]:
        """Run one adb route; missing binary -> (None, "") (honest absence)."""
        adb = shutil.which("adb")
        if adb is None:
            return None, ""
        try:
            proc = subprocess.run([adb, *argv], capture_output=True,
                                  text=True, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired):
            return None, ""
        return proc.returncode, proc.stdout

    def record(self, mode: str) -> dict:
        """Collect one cold/hot launch sample and append the JSONL row."""
        if mode not in _MODES:
            return {"result": "fail",
                    "detail": f"app.launchPerf 模式「{mode}」不在支持面（cold / hot），"
                              "冷启动=先清进程再启动，热启动=直接重新进入前台"}
        if shutil.which("adb") is None:
            return {"result": "fail",
                    "detail": "app.launchPerf 采集失败：执行机 PATH 缺 adb（Android SDK "
                              "运行时需装机，本步读数不可得，禁假过）"}
        if mode == "cold":
            rc, _ = self._run(self.force_stop_argv(), _LIGHT_CMD_TIMEOUT)
            if rc != 0:
                return {"result": "fail",
                        "detail": "app.launchPerf 冷启动失败：am force-stop 未成功退出"
                                  f"（rc={rc}），进程未清零则读数不是冷态，禁续采"}
        rc, out = self._run(self.resolve_component_argv(), _LIGHT_CMD_TIMEOUT)
        component = parse_resolve_activity(out) if rc == 0 else None
        rc, out = self._run(self.start_wait_argv(component), _LAUNCH_CMD_TIMEOUT)
        if rc is None:
            return {"result": "fail",
                    "detail": "app.launchPerf 采集失败：adb 通道不可达或命令超时，"
                              "启动耗时读数不可得（禁假过）"}
        parsed = parse_launch_wait(out) if rc == 0 else None
        if parsed is None:
            status_m = _STATUS_RE.search(out or "")
            status = status_m.group(1) if status_m else "无报文"
            return {"result": "fail",
                    "detail": f"app.launchPerf 采集失败：am start -W 报文不可信"
                              f"（Status={status}，TotalTime 读数缺失），禁按 0 猜值"}
        row = {"ts": time.time(), "mono": time.monotonic(),
               "tsBase": self.ts_base, "monoBase": self.mono_base,
               "serial": self.serial, "pkg": self.pkg,
               "kind": "launch", "launchMode": mode}
        row.update(parsed)
        try:
            line = json.dumps(row, ensure_ascii=False)
            with self._sink_lock, open(self.sink_path, "a",
                                       encoding="utf-8") as f:
                f.write(line + "\n")
        except OSError:
            return {"result": "fail",
                    "detail": f"app.launchPerf 读数已得但样例行落盘失败：{self.sink_path}"}
        state = row.get("launchState", "unknown")
        return {"result": "pass",
                "detail": f"app.launchPerf {mode} 启动耗时已采集：TotalTime="
                          f"{row['launchTotalMs']}ms、WaitTime="
                          f"{row.get('launchWaitMs', '不可得')}ms、LaunchState={state}"
                          "（样例行已落启动耗时通道，app.perf 可按 launchTotalMs 断言）"}


def evaluate_launch_step(step: dict, session) -> dict:
    """Host-side dispatch entry for the app.launchPerf verb.

    Requires the live Android adb perf session (it owns the sink directory,
    the dual-clock anchors and the target package). No session / non-adb
    session (iOS simulator) = honest fail naming the supported surface.
    """
    if session is None or not getattr(session, "active", False):
        return {"result": "fail",
                "detail": "app.launchPerf 无法采集：性能采样会话缺位（会话未建立或未启动，"
                          "启动耗时读数依赖 adb 通道，禁假过）"}
    recorder = getattr(session, "launch_recorder", None)
    if recorder is None:
        return {"result": "fail",
                "detail": "app.launchPerf 无法采集：当前设备会话不是 Android adb 会话"
                          "（冷/热启动耗时采集支持面=adb 真机/模拟器）"}
    return recorder().record(str(step.get("mode") or "cold"))
