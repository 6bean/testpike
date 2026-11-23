
from __future__ import annotations

import json
import logging
import re
import shutil
import subprocess
import threading
import time

log = logging.getLogger("testpike.executor.perf")

_CMD_TIMEOUT = 10.0

_TOTAL_FRAMES_RE = re.compile(r"Total frames rendered:\s*(\d+)")
_JANKY_FRAMES_RE = re.compile(r"Janky frames:\s*(\d+)")

_TOTAL_PSS_RE = re.compile(r"TOTAL PSS:\s*(\d+)")
_TOTAL_ROW_RE = re.compile(r"^\s*TOTAL:\s*(\d+)", re.MULTILINE)

_CPU_TOTAL_RE = re.compile(r"^\s*([\d.]+)%\s+TOTAL", re.MULTILINE)

_BATTERY_TEMP_RE = re.compile(r"temperature:\s*(-?\d+)")


def parse_gfxinfo(text: str) -> dict | None:
    pass
    total_m = _TOTAL_FRAMES_RE.search(text or "")
    if total_m is None:
        return None
    total = int(total_m.group(1))
    if total <= 0:
        return None
    out = {"totalFrames": total}
    janky_m = _JANKY_FRAMES_RE.search(text or "")
    if janky_m is not None:
        janky = int(janky_m.group(1))
        out["jankyFrames"] = janky
        out["jankPct"] = round(janky * 100.0 / total, 2)
    return out


def parse_meminfo_pss(text: str) -> int | None:
    pass
    m = _TOTAL_PSS_RE.search(text or "")
    if m is None:
        m = _TOTAL_ROW_RE.search(text or "")
    return int(m.group(1)) if m else None


def parse_cpu_total(text: str) -> float | None:
    pass
    m = _CPU_TOTAL_RE.search(text or "")
    if m is None:
        return None
    try:
        return float(m.group(1))
    except ValueError:
        return None


def parse_battery_temperature(text: str) -> float | None:
    pass
    m = _BATTERY_TEMP_RE.search(text or "")
    if m is None:
        return None
    try:
        return int(m.group(1)) / 10.0
    except ValueError:
        return None


class PerfProbe:
    pass

    def __init__(self, serial: str | None, pkg: str, interval: float,
                 sink_path: str):
        self._serial = serial
        self._pkg = pkg
        self._interval = max(float(interval), 0.05)
        self._sink_path = sink_path
        self._sink_lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._ts_base = time.time()
        self._mono_base = time.monotonic()


    def _device_prefix(self) -> list[str]:
        return ["-s", self._serial] if self._serial else []

    def gfxinfo_argv(self) -> list[str]:
        return self._device_prefix() + ["shell", "dumpsys", "gfxinfo", self._pkg]

    def meminfo_argv(self) -> list[str]:
        return self._device_prefix() + ["shell", "dumpsys", "meminfo", self._pkg]

    def cpu_argv(self) -> list[str]:
        return self._device_prefix() + ["shell", "dumpsys", "cpuinfo"]

    def battery_argv(self) -> list[str]:
        return self._device_prefix() + ["shell", "dumpsys", "battery"]


    def _read(self, argv: list[str]) -> str | None:
        pass
        adb = shutil.which("adb")
        if adb is None:
            return None
        try:
            proc = subprocess.run([adb, *argv], capture_output=True, text=True,
                                  timeout=_CMD_TIMEOUT, check=False)
        except (OSError, subprocess.TimeoutExpired):
            return None
        if proc.returncode != 0:
            return None
        return proc.stdout

    def sample_once(self) -> dict | None:
        pass
        ts = time.time()
        mono = time.monotonic()
        row: dict = {"ts": ts, "mono": mono,
                     "tsBase": self._ts_base, "monoBase": self._mono_base,
                     "serial": self._serial, "pkg": self._pkg}
        gfx = parse_gfxinfo(self._read(self.gfxinfo_argv()) or "")
        if gfx:
            row.update(gfx)
        pss = parse_meminfo_pss(self._read(self.meminfo_argv()) or "")
        if pss is not None:
            row["pssKb"] = pss
        cpu = parse_cpu_total(self._read(self.cpu_argv()) or "")
        if cpu is not None:
            row["cpuPct"] = cpu
        temp = parse_battery_temperature(self._read(self.battery_argv()) or "")
        if temp is not None:
            row["tempC"] = temp
        metric_keys = ("totalFrames", "pssKb", "cpuPct", "tempC")
        if not any(k in row for k in metric_keys):
            return None
        return row

    def sample_and_record(self) -> dict | None:
        pass
        row = self.sample_once()
        if row is None:
            return None
        try:
            line = json.dumps(row, ensure_ascii=False)
            with self._sink_lock, open(self._sink_path, "a",
                                       encoding="utf-8") as f:
                f.write(line + "\n")
        except OSError:
            log.warning("性能采样行落盘失败（旁路吞掉，不影响采样循环）：%s",
                        self._sink_path, exc_info=True)
        return row


    def start(self) -> bool:
        pass
        if self._thread is not None:
            return True
        if shutil.which("adb") is None:
            return False
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()
        return True

    def _loop(self):
        while not self._stop.is_set():
            try:
                self.sample_and_record()
            except Exception:
                log.warning("性能采样循环异常（旁路吞掉继续）", exc_info=True)
            self._stop.wait(self._interval)

    def stop(self):
        pass
        thread, self._thread = self._thread, None
        self._stop.set()
        if thread is not None:
            thread.join(timeout=_CMD_TIMEOUT + 2.0)
