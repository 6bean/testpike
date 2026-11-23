
from __future__ import annotations

import logging
import os
import shutil
import statistics
import tempfile
import time

from driver.mobile.crash_sentinel import CrashSentinel
from driver.mobile.launch_perf import LaunchPerfRecorder
from driver.mobile.perf_assert import SUPPORTED_METRICS, metric_value
from driver.mobile.perf_probe import PerfProbe

log = logging.getLogger("testpike.executor")

ART_DIR_BASE = os.path.join(tempfile.gettempdir(), "testpike-perf")

PERF_SAMPLES_FILENAME = "tp-perf-samples.jsonl"
PERF_CRASHES_FILENAME = "tp-perf-crashes.jsonl"
PERF_LAUNCHES_FILENAME = "tp-perf-launches.jsonl"

_DEFAULT_INTERVAL_ENV = "EXECUTOR_PERF_SAMPLE_INTERVAL"


def perf_dir(task_id) -> str:
    pass
    return os.path.join(ART_DIR_BASE, str(task_id))


def default_perf_interval() -> float:
    try:
        val = float(os.environ.get(_DEFAULT_INTERVAL_ENV, "") or 1.0)
    except ValueError:
        return 1.0
    return val if val > 0 else 1.0


PERF_INTERVAL_KEY = "appauto.perf-sample-interval"


def resolve_perf_interval(envelope: dict | None) -> float:
    pass
    if isinstance(envelope, dict):
        keys = envelope.get("perfKeys")
        if isinstance(keys, dict):
            try:
                val = float(str(keys.get(PERF_INTERVAL_KEY, "") or ""))
                if val > 0:
                    return val
            except (TypeError, ValueError):
                pass
    return default_perf_interval()


def resolve_target_pkg(envelope: dict | None) -> str | None:
    pass
    if not isinstance(envelope, dict):
        return None
    profile = envelope.get("envProfile")
    if isinstance(profile, dict):
        for key in ("appId", "pkg"):
            val = str(profile.get(key) or "").strip()
            if val:
                return val
    for step in envelope.get("steps") or []:
        if isinstance(step, dict):
            val = str(step.get("pkg") or "").strip()
            if val:
                return val
    return None


def summarize_rows(samples: list[dict], crashes: list[dict]) -> dict | None:
    pass
    if not samples and not crashes:
        return None
    row: dict = {"sampleCount": len(samples), "crashes": list(crashes)}
    ref = (samples[-1] if samples else crashes[-1])
    row["serial"] = ref.get("serial")
    row["pkg"] = ref.get("pkg")
    if samples:
        temps = [s["tempC"] for s in samples if isinstance(s.get("tempC"), (int, float))]
        cpus = [s["cpuPct"] for s in samples if isinstance(s.get("cpuPct"), (int, float))]
        janks = [s["jankPct"] for s in samples if isinstance(s.get("jankPct"), (int, float))]
        if temps:
            row["lastTempC"] = temps[-1]
            row["maxTempC"] = max(temps)
        if cpus:
            row["maxCpuPct"] = max(cpus)
        if janks:
            row["avgJankPct"] = round(sum(janks) / len(janks), 2)
        row["lastTs"] = samples[-1].get("ts")
    return row


def summarize_metrics(samples: list[dict], launches: list[dict]) -> dict | None:
    """Sampling + launch rows -> batch-window aggregate (median per metric).

    W5 phase-two B6 P4c: the batch report surface consumes these numbers via
    the result trailing key mobilePerfSummary; the core side never re-parses
    raw rows, so this is the only aggregation point on the executor side.
    Readings go through perf_assert.metric_value (single conversion surface,
    pssKb -> pssMb included). A metric with no readable row is honestly
    absent from the output — never 0-filled; no readings at all -> None
    (empty = no key, trailing-key zero-drift).
    """
    rows = list(samples or []) + list(launches or [])
    metrics: dict[str, dict] = {}
    for metric in sorted(SUPPORTED_METRICS):
        values = [v for v in (metric_value(row, metric) for row in rows)
                  if v is not None]
        if values:
            metrics[metric] = {"median": round(float(statistics.median(values)), 2),
                               "count": len(values)}
    if not metrics:
        return None
    return {"metrics": metrics, "sampleCount": len(samples or [])}


class PerfSession:
    pass

    def __init__(self, serial: str | None, pkg: str, interval: float,
                 dir_path: str):
        self.serial = serial
        self.pkg = pkg
        self.interval = max(float(interval), 0.05)
        self.dir_path = dir_path
        self.samples_path = os.path.join(dir_path, PERF_SAMPLES_FILENAME)
        self.crashes_path = os.path.join(dir_path, PERF_CRASHES_FILENAME)
        self.launches_path = os.path.join(dir_path, PERF_LAUNCHES_FILENAME)
        self._ts_base = time.time()
        self._mono_base = time.monotonic()
        self._probe: PerfProbe | None = None
        self._sentinel: CrashSentinel | None = None
        self.active = False

    def wall_clock(self, mono: float) -> float:
        pass
        if self._ts_base is None or self._mono_base is None:
            return mono
        return self._ts_base + (mono - self._mono_base)

    def start(self) -> bool:
        pass
        if shutil.which("adb") is None:
            return False
        os.makedirs(self.dir_path, exist_ok=True)
        for path in (self.samples_path, self.crashes_path, self.launches_path):
            open(path, "a", encoding="utf-8").close()
        self._probe = PerfProbe(self.serial, self.pkg, self.interval,
                                self.samples_path)
        self._sentinel = CrashSentinel(self.serial, self.pkg, self.crashes_path)
        self._ts_base = self._probe._ts_base
        self._mono_base = self._probe._mono_base
        started = self._probe.start()
        if not started:
            self._probe = None
            return False
        self.active = True
        if not self._sentinel.start():
            self._sentinel = None
        return True

    def launch_recorder(self) -> LaunchPerfRecorder:
        """Cold/hot launch-timing recorder assembly (W5 phase-two B6 P4b).

        Single build point binding session serial/pkg, session dual-clock
        anchors and the launch sink — app_worker must not reconstruct a
        second recorder (R40 single source).
        """
        return LaunchPerfRecorder(self.serial, self.pkg, self.launches_path,
                                  ts_base=self._ts_base,
                                  mono_base=self._mono_base)

    def stop(self) -> None:
        pass
        for part in (self._probe, self._sentinel):
            if part is not None:
                try:
                    part.stop()
                except Exception:
                    log.warning("性能采样旁路件停收异常（吞掉继续）", exc_info=True)
        self._probe = None
        self._sentinel = None
