
from __future__ import annotations

import json
import logging
import os
import re
import shutil
import subprocess
import threading
import time

from driver.mobile.monitor_slice import load_frames
from driver.mobile.perf_session import PERF_SAMPLES_FILENAME

log = logging.getLogger("testpike.executor.simperf")

_CMD_TIMEOUT = 10.0

_CPU_USAGE_RE = re.compile(
    r"CPU usage:\s*([\d.]+)%\s*user.*?([\d.]+)%\s*sys")
_PHYS_MEM_RE = re.compile(
    r"PhysMem:\s*([\d.]+)\s*([KMGT]?B?)\s*used.*?([\d.]+)\s*([KMGT]?B?)\s*unused",
    re.IGNORECASE)

_UNIT_MULT = {"K": 1024, "M": 1024 ** 2, "G": 1024 ** 3, "T": 1024 ** 4,
              "KB": 1024, "MB": 1024 ** 2, "GB": 1024 ** 3, "TB": 1024 ** 4}


def _unit_value(num: str, suffix: str) -> float:
    mult = _UNIT_MULT.get((suffix or "").upper(), 1)
    return float(num) * mult


def parse_top_cpu(text: str) -> float | None:
    pass
    m = _CPU_USAGE_RE.search(text or "")
    if m is None:
        return None
    return round(float(m.group(1)) + float(m.group(2)), 2)


def parse_top_mem(text: str) -> float | None:
    pass
    m = _PHYS_MEM_RE.search(text or "")
    if m is None:
        return None
    try:
        used = _unit_value(m.group(1), m.group(2))
        unused = _unit_value(m.group(3), m.group(4))
    except ValueError:
        return None
    total = used + unused
    if total <= 0:
        return None
    return round(used * 100.0 / total, 2)


def estimate_fps(frames: list[dict]) -> float | None:
    pass
    stamps = sorted(float(f["ts"]) for f in frames or []
                    if isinstance(f, dict) and isinstance(f.get("ts"),
                                                          (int, float)))
    if len(stamps) < 2:
        return None
    span = stamps[-1] - stamps[0]
    if span <= 0:
        return None
    return round((len(stamps) - 1) / span, 2)


def probe_sim_prefix(which=shutil.which) -> list[str] | None:
    pass
    if which("xcrun"):
        return ["xcrun", "simctl"]
    if which("simctl"):
        return ["simctl"]
    return None


class SimPerfSession:
    pass

    def __init__(self, udid: str, interval: float, dir_path: str,
                 frame_sink: str | None = None):
        self.serial = udid
        self.pkg = ""
        self.udid = udid
        self.interval = max(float(interval), 0.05)
        self.dir_path = dir_path
        self.samples_path = os.path.join(dir_path, PERF_SAMPLES_FILENAME)
        self.frame_sink = frame_sink
        self._prefix: list[str] | None = None
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._sink_lock = threading.Lock()
        self._ts_base = time.time()
        self._mono_base = time.monotonic()
        self.active = False

    def wall_clock(self, mono: float) -> float:
        return self._ts_base + (mono - self._mono_base)

    def top_argv(self) -> list[str]:
        prefix = self._prefix if self._prefix is not None else probe_sim_prefix()
        if prefix is None:
            return []
        return prefix + ["spawn", self.udid, "top", "-l", "1"]

    def _read_top(self) -> str | None:
        argv = self.top_argv()
        exe = shutil.which(argv[0]) if argv else None
        if exe is None:
            return None
        try:
            proc = subprocess.run([exe, *argv[1:]],
                                  capture_output=True, text=True,
                                  timeout=_CMD_TIMEOUT, check=False)
        except (OSError, subprocess.TimeoutExpired):
            return None
        if proc.returncode != 0:
            return None
        return proc.stdout

    def sample_once(self) -> dict | None:
        pass
        top = self._read_top() or ""
        row: dict = {"ts": time.time(), "mono": time.monotonic(),
                     "tsBase": self._ts_base, "monoBase": self._mono_base,
                     "serial": self.udid, "source": "sim-host"}
        cpu = parse_top_cpu(top)
        if cpu is not None:
            row["cpuPct"] = cpu
        mem = parse_top_mem(top)
        if mem is not None:
            row["memUsedPct"] = mem
        if self.frame_sink:
            fps = estimate_fps(load_frames(self.frame_sink))
            if fps is not None:
                row["fpsEst"] = fps
        if not any(k in row for k in ("cpuPct", "memUsedPct", "fpsEst")):
            return None
        return row

    def sample_and_record(self) -> dict | None:
        row = self.sample_once()
        if row is None:
            return None
        try:
            line = json.dumps(row, ensure_ascii=False)
            with self._sink_lock, open(self.samples_path, "a",
                                       encoding="utf-8") as f:
                f.write(line + "\n")
        except OSError:
            log.warning("iOS 宿主采样行落盘失败（旁路吞掉继续）：%s",
                        self.samples_path, exc_info=True)
        return row

    def start(self) -> bool:
        pass
        if self._thread is not None:
            return True
        prefix = probe_sim_prefix()
        if prefix is None or not (self.udid or "").strip():
            return False
        self._prefix = prefix
        os.makedirs(self.dir_path, exist_ok=True)
        open(self.samples_path, "a", encoding="utf-8").close()
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()
        self.active = True
        return True

    def _loop(self):
        while not self._stop.is_set():
            try:
                self.sample_and_record()
            except Exception:
                log.warning("iOS 宿主采样循环异常（旁路吞掉继续）", exc_info=True)
            self._stop.wait(self.interval)

    def stop(self) -> None:
        pass
        thread, self._thread = self._thread, None
        self.active = False
        self._stop.set()
        if thread is not None:
            thread.join(timeout=_CMD_TIMEOUT + 2.0)
