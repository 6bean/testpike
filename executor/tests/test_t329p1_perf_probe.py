
from __future__ import annotations

import json
import os
import pathlib
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from driver.mobile.perf_probe import (
    PerfProbe,
    parse_battery_temperature,
    parse_cpu_total,
    parse_gfxinfo,
    parse_meminfo_pss,
)

GFX_SHIM_SRC = '''#!/bin/sh
echo "$@" >> "$ADB_SHIM_LOG"
case "$*" in
  *"dumpsys gfxinfo"*) printf '%b\n' "${SHIM_GFX:-}";;
  *"dumpsys meminfo"*) printf '%b\n' "${SHIM_MEMINFO:-}"; exit ${SHIM_MEM_RC:-0};;
  *"dumpsys cpuinfo"*) printf '%b\n' "${SHIM_CPUINFO:-}";;
  *"dumpsys battery"*) printf '%b\n' "${SHIM_BATTERY:-}";;
esac
exit ${SHIM_RC:-0}
'''


def _install_shim(tmp_path, monkeypatch, *, gfx=None, meminfo=None,
                  cpuinfo=None, battery=None, mem_rc=0, rc=0):
    pass
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "adb"
    shim.write_text(GFX_SHIM_SRC, encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))
    log = tmp_path / "adb-argv.log"
    monkeypatch.setenv("ADB_SHIM_LOG", str(log))
    for key, val in (("SHIM_GFX", gfx), ("SHIM_MEMINFO", meminfo),
                     ("SHIM_CPUINFO", cpuinfo), ("SHIM_BATTERY", battery)):
        if val is not None:
            monkeypatch.setenv(key, val)
        else:
            monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("SHIM_MEM_RC", str(mem_rc))
    monkeypatch.setenv("SHIM_RC", str(rc))
    return log


def _argv_rows(log_path):
    if not log_path.exists():
        return []
    return [line.strip() for line in log_path.read_text(encoding="utf-8").splitlines()]



def test_dumpsys_argv_plans_carry_serial_and_pkg():
    probe = PerfProbe(serial="em-5554", pkg="com.demo", interval=0.05,
                      sink_path="/tmp/unused.jsonl")
    assert probe.gfxinfo_argv() == ["-s", "em-5554", "shell", "dumpsys",
                                    "gfxinfo", "com.demo"]
    assert probe.meminfo_argv() == ["-s", "em-5554", "shell", "dumpsys",
                                    "meminfo", "com.demo"]
    assert probe.cpu_argv() == ["-s", "em-5554", "shell", "dumpsys", "cpuinfo"]
    assert probe.battery_argv() == ["-s", "em-5554", "shell", "dumpsys", "battery"]
    solo = PerfProbe(serial=None, pkg="com.demo", interval=0.05,
                     sink_path="/tmp/unused.jsonl")
    assert solo.gfxinfo_argv()[:1] != ["-s"]



def test_parse_gfxinfo_reads_frames_and_computes_jank_rate():
    text = "\n".join([
        "Applications Graphics Acceleration Info does not support:",
        "  Graphics info for pid 1234 [com.demo]:",
        "Total frames rendered: 500",
        "Janky frames: 25 (5.00%)",
        "  90th percentile: 30ms",
        "HISTOGRAM: ...",
    ])
    stats = parse_gfxinfo(text)
    assert stats["totalFrames"] == 500
    assert stats["jankyFrames"] == 25
    assert stats["jankPct"] == 5.0


def test_parse_gfxinfo_zero_frames_and_garbage_honest_none():
    assert parse_gfxinfo("Total frames rendered: 0") is None
    assert parse_gfxinfo("") is None
    assert parse_gfxinfo("Janky frames: not-a-number (x%)") is None
    assert parse_gfxinfo("Total frames rendered: 0\nJanky frames: 3 (0.00%)") is None


def test_parse_meminfo_pss_whitelist_line():
    text = "\n".join([  # noqa: FLY002
        "** MEMINFO in pid 1234 [com.demo] **",
        "                   Pss  Private  Private",
        "  TOTAL:        123456     80000     20000",
        " TOTAL PSS:   123456   TOTAL RSS:   200000",
    ])
    assert parse_meminfo_pss(text) == 123456
    assert parse_meminfo_pss("no pss here") is None


def test_parse_cpu_total_percent():
    text = "\n".join([  # noqa: FLY002
        "Load: 6.0 / 6.0 / 5.5",
        "CPU usage from 100ms to 5000ms later:",
        "  37% 8%: 10% user + 27% kernel",
        "  15% 4%: 5% user + 10% kernel",
        "  100% TOTAL: 40% user + 50% kernel + 10% iowait",
    ])
    assert parse_cpu_total(text) == 100.0


def test_parse_cpu_tolerates_decimal_and_missing():
    assert parse_cpu_total("  37.5% TOTAL: 20% user") == 37.5
    assert parse_cpu_total("nothing") is None


def test_parse_battery_temperature_is_tenth_of_degree():
    assert parse_battery_temperature("  temperature: 315") == 31.5
    assert parse_battery_temperature("  temperature: 420") == 42.0
    assert parse_battery_temperature("  tech: Li-ion") is None
    assert parse_battery_temperature("  temperature: abc") is None



def test_sample_once_builds_row_with_dual_clock_anchor(tmp_path, monkeypatch):
    log = _install_shim(tmp_path, monkeypatch,
                        gfx="Total frames rendered: 200\\nJanky frames: 10 (5.00%)",
                        meminfo=" TOTAL PSS:   98765",
                        cpuinfo="  44% TOTAL: 30% user",
                        battery="  temperature: 398")
    sink = tmp_path / "perf.jsonl"
    probe = PerfProbe(serial="em-1", pkg="com.demo", interval=1.0,
                      sink_path=str(sink))
    row = probe.sample_once()
    assert row is not None
    assert row["totalFrames"] == 200 and row["jankyFrames"] == 10
    assert row["jankPct"] == 5.0
    assert row["pssKb"] == 98765
    assert row["cpuPct"] == 44.0
    assert row["tempC"] == 39.8
    assert row["serial"] == "em-1" and row["pkg"] == "com.demo"
    assert isinstance(row["ts"], float) and isinstance(row["mono"], float)
    assert row["monoBase"] <= row["mono"] < row["monoBase"] + 3600
    assert isinstance(row["tsBase"], float) and row["tsBase"] <= row["ts"]
    rows = _argv_rows(log)
    assert any("gfxinfo com.demo" in r for r in rows)
    assert any("dumpsys battery" in r for r in rows)


def test_sample_row_appended_as_jsonl(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch,
                  gfx="Total frames rendered: 100\\nJanky frames: 1 (1.00%)",
                  meminfo=" TOTAL PSS:   1000", cpuinfo="  1% TOTAL:",
                  battery="  temperature: 300")
    sink = tmp_path / "perf.jsonl"
    probe = PerfProbe(serial=None, pkg="com.demo", interval=1.0,
                      sink_path=str(sink))
    probe.sample_and_record()
    probe.sample_and_record()
    lines = sink.read_text(encoding="utf-8").splitlines()
    assert len(lines) == 2
    assert json.loads(lines[0])["totalFrames"] == 100


def test_all_metrics_absent_emits_no_row(tmp_path, monkeypatch):
    pass
    _install_shim(tmp_path, monkeypatch)
    sink = tmp_path / "perf.jsonl"
    probe = PerfProbe(serial=None, pkg="com.demo", interval=1.0,
                      sink_path=str(sink))
    assert probe.sample_and_record() is None
    assert not sink.exists() or sink.read_text(encoding="utf-8") == ""


def test_partial_metrics_row_keeps_present_fields(tmp_path, monkeypatch):
    pass
    _install_shim(tmp_path, monkeypatch, gfx="Total frames rendered: 50",
                  mem_rc=1)
    sink = tmp_path / "perf.jsonl"
    probe = PerfProbe(serial=None, pkg="com.demo", interval=1.0,
                      sink_path=str(sink))
    row = probe.sample_and_record()
    assert row is not None
    assert row["totalFrames"] == 50
    assert "pssKb" not in row


def test_start_without_adb_binary_is_honest_false(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path / "empty-bin"))
    probe = PerfProbe(serial=None, pkg="com.demo", interval=0.05,
                      sink_path=str(tmp_path / "perf.jsonl"))
    assert probe.start() is False
    probe.stop()


def test_loop_samples_periodically_and_stops(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch,
                  gfx="Total frames rendered: 100\\nJanky frames: 1 (1.00%)",
                  meminfo=" TOTAL PSS:   1000", cpuinfo="  1% TOTAL:",
                  battery="  temperature: 300")
    sink = tmp_path / "perf.jsonl"
    probe = PerfProbe(serial=None, pkg="com.demo", interval=0.05,
                      sink_path=str(sink))
    assert probe.start() is True
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        if sink.exists() and len(sink.read_text(encoding="utf-8").splitlines()) >= 2:
            break
        time.sleep(0.05)
    probe.stop()
    probe.stop()
    lines = sink.read_text(encoding="utf-8").splitlines()
    assert len(lines) >= 2
    time.sleep(0.3)
    assert len(sink.read_text(encoding="utf-8").splitlines()) == len(lines)
