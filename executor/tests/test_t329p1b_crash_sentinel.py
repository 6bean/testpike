
from __future__ import annotations

import json
import os
import pathlib
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from driver.mobile.crash_sentinel import CrashSentinel, parse_crash_lines

LOGCAT_CRASH_TEXT = "\n".join([  # noqa: FLY002
    "--------- beginning of crash",
    "E AndroidRuntime: FATAL EXCEPTION: main",
    "E AndroidRuntime: Process: com.demo, PID: 1234",
    "E AndroidRuntime: java.lang.RuntimeException: boom",
    "E AndroidRuntime: \tat com.demo.Main.run(Main.java:1)",
    "F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 2222",
])



def test_parse_crash_lines_reports_fatal_exception_once_per_block():
    events = parse_crash_lines(LOGCAT_CRASH_TEXT.splitlines())
    kinds = [e["kind"] for e in events]
    assert kinds == ["crash-java", "crash-native"]
    assert "com.demo" in events[0]["detail"] and "PID: 1234" in events[0]["detail"]


def test_parse_crash_lines_ignores_noise_and_empty():
    noise = ["I ActivityTaskManager: START u0", "D Test: hello", ""]
    assert parse_crash_lines(noise) == []
    assert parse_crash_lines([]) == []


def test_parse_crash_lines_repeated_fatal_each_reported():
    twice = ["FATAL EXCEPTION: main", "blah", "FATAL EXCEPTION: main"]
    assert len(parse_crash_lines(twice)) == 2



def test_pidof_lost_only_on_live_to_dead_edge():
    sentinel = CrashSentinel(serial="em-1", pkg="com.demo", sink_path="/tmp/x")
    assert sentinel.evaluate_pid(False) is None
    assert sentinel.evaluate_pid(True) is None
    lost = sentinel.evaluate_pid(False)
    assert lost is not None and lost["kind"] == "process-lost"
    assert "com.demo" in lost["detail"]
    assert sentinel.evaluate_pid(False) is None
    assert sentinel.evaluate_pid(True) is None



SHIM_SRC = '''#!/bin/sh
echo "$@" >> "$ADB_SHIM_LOG"
case "$*" in
  *"logcat"*) printf '%b\n' "${SHIM_LOGCAT:-}" & sleep ${SHIM_LOGCAT_HOLD:-2};;
  *"pidof"*) echo "${SHIM_PIDOF:-}"; exit 0;;
esac
exit 0
'''


def _install_shim(tmp_path, monkeypatch, logcat=None, pidof=None, hold=None):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "adb"
    shim.write_text(SHIM_SRC, encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))
    log = tmp_path / "adb-argv.log"
    monkeypatch.setenv("ADB_SHIM_LOG", str(log))
    monkeypatch.setenv("SHIM_LOGCAT", logcat if logcat is not None else "")
    monkeypatch.setenv("SHIM_PIDOF", pidof if pidof is not None else "")
    monkeypatch.setenv("SHIM_LOGCAT_HOLD", str(hold) if hold else "2")
    return log


def _event_rows(path):
    if not path.exists():
        return []
    return [json.loads(line) for line in
            path.read_text(encoding="utf-8").splitlines() if line.strip()]


def test_start_without_adb_is_honest_false(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path / "empty-bin"))
    sentinel = CrashSentinel(serial=None, pkg="com.demo",
                             sink_path=str(tmp_path / "crash.jsonl"))
    assert sentinel.start() is False
    sentinel.stop()


def test_logcat_stream_events_recorded_with_dual_clock(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch, logcat=LOGCAT_CRASH_TEXT, pidof="1234")
    sink = tmp_path / "crash.jsonl"
    sentinel = CrashSentinel(serial="em-7", pkg="com.demo",
                             sink_path=str(sink), poll_interval=0.05)
    assert sentinel.start() is True
    deadline = time.monotonic() + 6.0
    while time.monotonic() < deadline and len(_event_rows(sink)) < 2:
        time.sleep(0.05)
    rows = _event_rows(sink)
    kinds = {r["kind"] for r in rows}
    assert {"crash-java", "crash-native"} <= kinds
    assert all(r["serial"] == "em-7" and r["pkg"] == "com.demo" for r in rows)
    assert all(isinstance(r["ts"], float) and isinstance(r["mono"], float)
               for r in rows)
    sentinel.stop()


def test_process_loss_transition_reported_once(tmp_path, monkeypatch):
    pass
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "adb"
    shim.write_text('''#!/bin/sh
case "$*" in
  *"pidof"*) if [ "$SHIM_ALIVE" = 1 ]; then echo 1234; fi;;
  *"logcat"*) sleep 5;;
esac
exit 0
''', encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))
    monkeypatch.setenv("SHIM_ALIVE", "1")
    sink = tmp_path / "crash.jsonl"
    sentinel = CrashSentinel(serial=None, pkg="com.demo",
                             sink_path=str(sink), poll_interval=0.05)
    sentinel.start()
    time.sleep(0.3)
    monkeypatch.setenv("SHIM_ALIVE", "0")
    deadline = time.monotonic() + 6.0
    rows: list = []
    while time.monotonic() < deadline and not rows:
        time.sleep(0.05)
        rows = _event_rows(sink)
    assert rows and rows[0]["kind"] == "process-lost"
    time.sleep(0.3)
    assert len(_event_rows(sink)) == 1
    sentinel.stop()


# -- (4) device-side argv fidelity: real logcat rejects unknown options -------
# The loose shim above echoes canned text whatever flags it is given, which hid
# a dead stream on device (the crash face shipped a device-side regex prefilter
# that logcat does not support, so the child died before one line was read).
# This shim validates the argv like /system/bin/logcat does: an unknown option
# makes the child exit with no output, which must be visible, not silent.

SHIM_STRICT_SRC = '''#!/bin/sh
echo "$@" >> "$ADB_SHIM_LOG"
rest="${*#*logcat}"
case "$*" in
  *"logcat"*) ;;
  *) exit 0;;
esac
case "$*" in
  *"pidof"*) echo "${SHIM_PIDOF:-}"; exit 0;;
esac
set -- $rest
while [ $# -gt 0 ]; do
  case "$1" in
    -v|-b)
      case "$2" in ""|-*) echo "logcat: missing argument for $1" >&2; exit 1;; esac
      shift 2;;
    -c|-d|-g|-s|-t|-P|--pid|--brief) shift;;
    -*) echo "logcat: unknown option '$1'" >&2; exit 1;;
    *) shift;;
  esac
done
printf '%b\\n' "${SHIM_LOGCAT:-}"
sleep ${SHIM_LOGCAT_HOLD:-1}
exit 0
'''


def _install_strict_shim(tmp_path, monkeypatch, logcat=None, pidof=""):
    """Fake adb that mirrors device argument validation for logcat."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "adb"
    shim.write_text(SHIM_STRICT_SRC, encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))
    log = tmp_path / "adb-argv.log"
    monkeypatch.setenv("ADB_SHIM_LOG", str(log))
    monkeypatch.setenv("SHIM_LOGCAT", logcat if logcat is not None
                       else LOGCAT_CRASH_TEXT)
    monkeypatch.setenv("SHIM_PIDOF", pidof)
    monkeypatch.setenv("SHIM_LOGCAT_HOLD", "1")
    return log


def test_crash_stream_argv_is_accepted_by_device_logcat_validation(tmp_path,
                                                                   monkeypatch):
    """End-to-end through the argv the sentinel actually passes to adb.

    The stream must yield crash events when the fake adb rejects any option
    the device would reject (fail-closed against a silently dead crash face).
    """
    argv_log = _install_strict_shim(tmp_path, monkeypatch)
    sink = tmp_path / "crash.jsonl"
    sentinel = CrashSentinel(serial="em-9", pkg="com.demo",
                             sink_path=str(sink), poll_interval=0.05)
    assert sentinel.start() is True
    deadline = time.monotonic() + 6.0
    while time.monotonic() < deadline and len(_event_rows(sink)) < 2:
        time.sleep(0.05)
    rows = _event_rows(sink)
    sentinel.stop()
    assert {r["kind"] for r in rows} >= {"crash-java", "crash-native"}, (
        "crash stream produced no events; argv sent to adb="
        + argv_log.read_text(encoding="utf-8").strip())
