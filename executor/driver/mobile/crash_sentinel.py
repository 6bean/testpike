
from __future__ import annotations

import json
import logging
import shutil
import subprocess
import threading
import time

log = logging.getLogger("testpike.executor.crash")

_PIDOF_POLL_INTERVAL = 1.0

_CMD_TIMEOUT = 10.0

_JAVA_FATAL_MARK = "FATAL EXCEPTION"
_NATIVE_FATAL_MARK = "Fatal signal"
_PROCESS_MARK = "Process:"


def parse_crash_lines(lines) -> list[dict]:
    pass
    events: list[dict] = []
    pending_java: dict | None = None
    for line in lines or []:
        if _JAVA_FATAL_MARK in line:
            if pending_java is not None:
                events.append(pending_java)
            head = line.split(_JAVA_FATAL_MARK, 1)[-1].strip(": ").strip()
            pending_java = {"kind": "crash-java",
                            "detail": f"崩溃线程[{head}]" if head else "崩溃线程[未知]"}
            continue
        if pending_java is not None and _PROCESS_MARK in line:
            proc = line.split(_PROCESS_MARK, 1)[-1].strip()
            pending_java["detail"] = f"{pending_java['detail']} 进程 {proc}"
            events.append(pending_java)
            pending_java = None
            continue
        if _NATIVE_FATAL_MARK in line:
            if pending_java is not None:
                events.append(pending_java)
                pending_java = None
            signal = line.split(_NATIVE_FATAL_MARK, 1)[-1].strip().strip(":")
            events.append({"kind": "crash-native",
                           "detail": f"native 崩溃 {signal}" if signal
                           else "native 崩溃"})
    if pending_java is not None:
        events.append(pending_java)
    return events


class CrashSentinel:
    pass

    def __init__(self, serial: str | None, pkg: str, sink_path: str,
                 poll_interval: float = _PIDOF_POLL_INTERVAL):
        self._serial = serial
        self._pkg = pkg
        self._sink_path = sink_path
        self._poll_interval = max(float(poll_interval), 0.05)
        self._sink_lock = threading.Lock()
        self._stop = threading.Event()
        self._logcat_proc: subprocess.Popen | None = None
        self._reader: threading.Thread | None = None
        self._poller: threading.Thread | None = None
        self._was_alive: bool | None = None
        self._ts_base = time.time()
        self._mono_base = time.monotonic()


    def logcat_argv(self) -> list[str]:
        """Stream command for the device logcat.

        /system/bin/logcat has no regex option: a device-side prefilter makes
        the child die before a single line is read, which silently kills the
        whole Java/native crash face. The whitelist stays in-process
        (parse_crash_lines) and the default buffer set carries the crash
        buffer (proved on the live AVD: FATAL EXCEPTION frames appear there).
        """
        prefix = ["-s", self._serial] if self._serial else []
        return prefix + ["shell", "logcat", "-v", "brief"]

    def pidof_argv(self) -> list[str]:
        prefix = ["-s", self._serial] if self._serial else []
        return prefix + ["shell", "pidof", self._pkg]


    def evaluate_pid(self, alive_now: bool) -> dict | None:
        pass
        event = None
        if self._was_alive and not alive_now:
            event = {"kind": "process-lost",
                     "detail": f"被测进程消失（pkg={self._pkg}，"
                               "上一轮存活本轮 pidof 读空）"}
        self._was_alive = alive_now
        return event


    def _record(self, event: dict) -> None:
        row = {**event, "serial": self._serial, "pkg": self._pkg,
               "ts": time.time(), "mono": time.monotonic(),
               "tsBase": self._ts_base, "monoBase": self._mono_base}
        try:
            line = json.dumps(row, ensure_ascii=False)
            with self._sink_lock, open(self._sink_path, "a",
                                       encoding="utf-8") as f:
                f.write(line + "\n")
        except OSError:
            log.warning("崩溃事件落盘失败（旁路吞掉，不影响哨兵继续）：%s",
                        self._sink_path, exc_info=True)


    def start(self) -> bool:
        pass
        if self._reader is not None or self._poller is not None:
            return True
        if shutil.which("adb") is None:
            return False
        self._stop.clear()
        self._spawn_logcat()
        self._poller = threading.Thread(target=self._poll_loop, daemon=True)
        self._poller.start()
        return True

    def _spawn_logcat(self):
        try:
            self._logcat_proc = subprocess.Popen(
                ["adb", *self.logcat_argv()], stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, text=True)
        except OSError:
            self._logcat_proc = None
            return
        self._reader = threading.Thread(target=self._pump_logcat, daemon=True)
        self._reader.start()

    def _pump_logcat(self):
        try:
            assert self._logcat_proc is not None
            for line in self._logcat_proc.stdout:
                if self._stop.is_set():
                    break
                for event in parse_crash_lines([line.rstrip("\n")]):
                    self._record(event)
        except (OSError, ValueError):
            pass

    def _poll_loop(self):
        while not self._stop.is_set():
            alive = self._pidof_alive()
            if alive is not None:
                event = self.evaluate_pid(alive)
                if event is not None:
                    self._record(event)
            self._stop.wait(self._poll_interval)

    def _pidof_alive(self) -> bool | None:
        pass
        adb = shutil.which("adb")
        if adb is None:
            return None
        try:
            proc = subprocess.run([adb, *self.pidof_argv()], capture_output=True,
                                  text=True, timeout=_CMD_TIMEOUT, check=False)
        except (OSError, subprocess.TimeoutExpired):
            return None
        return bool(proc.stdout.strip())

    def stop(self):
        pass
        self._stop.set()
        proc, self._logcat_proc = self._logcat_proc, None
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=2.0)
            except subprocess.TimeoutExpired:
                proc.kill()
        reader, self._reader = self._reader, None
        if reader is not None:
            reader.join(timeout=1.0)
        poller, self._poller = self._poller, None
        if poller is not None:
            poller.join(timeout=_CMD_TIMEOUT + 1.0)
