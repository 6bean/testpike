
from __future__ import annotations

import json
import os
import stat

from driver.mobile.launch_perf import (
    LAUNCH_TOTAL_MS_RE,
    LaunchPerfRecorder,
    evaluate_launch_step,
    parse_launch_wait,
    parse_resolve_activity,
)
from driver.mobile.perf_assert import (
    HOST_SIDE_VERBS,
    SUPPORTED_METRICS,
    evaluate_perf_step,
)
from driver.mobile.perf_session import PerfSession

_COLD_OUTPUT = """Starting: Intent { cmp=com.example.app/.MainActivity }
Status: ok
LaunchState: COLD
Activity: com.example.app/.MainActivity
TotalTime: 1234
WaitTime: 1240
Complete
"""

_HOT_OUTPUT = """Starting: Intent { cmp=com.example.app/.MainActivity }
Status: ok
LaunchState: HOT
Activity: com.example.app/.MainActivity
TotalTime: 210
WaitTime: 215
Complete
"""

_TIMEOUT_OUTPUT = """Starting: Intent { cmp=com.example.app/.MainActivity }
Status: unknown code
WaitTime: 30000
"""


def test_白名单键面解析_冷热两态读数实算():
    row = parse_launch_wait(_COLD_OUTPUT)
    assert row is not None
    assert row["launchTotalMs"] == 1234
    assert row["launchWaitMs"] == 1240
    assert row["launchState"] == "cold"
    hot = parse_launch_wait(_HOT_OUTPUT)
    assert hot is not None
    assert hot["launchState"] == "hot" and hot["launchTotalMs"] == 210


def test_状态非ok或无总耗时_诚实None():
    assert parse_launch_wait(_TIMEOUT_OUTPUT) is None
    assert parse_launch_wait("") is None
    assert parse_launch_wait(None) is None


def test_launchstate行漂移_诚实缺键不猜():
    text = _COLD_OUTPUT.replace("LaunchState: COLD\n", "")
    row = parse_launch_wait(text)
    assert row is not None and "launchState" not in row
    assert row["launchTotalMs"] == 1234


def test_组件解析取斜杠形态():
    comp = parse_resolve_activity(
        "activityResolver:\n  name=com.example.app/.MainActivity\n")
    assert comp == "com.example.app/.MainActivity"
    assert parse_resolve_activity("com.example.app/.Launcher\n") == (
        "com.example.app/.Launcher")
    assert parse_resolve_activity("") is None
    assert parse_resolve_activity("No activity found") is None


def _make_shim_adb(bin_dir: str, log_path: str) -> None:
    """Canned adb: records argv, answers subcommands from fixtures."""
    os.makedirs(bin_dir, exist_ok=True)
    script = (
        "#!/bin/sh\n"
        f'echo "$@" >> "{log_path}"\n'
        "case \"$*\" in\n"
        "  *resolve-activity*) echo 'activityResolver:';"
        " echo '  name=com.example.app/.MainActivity' ;;\n"
        f'  *"am start"*) cat "{log_path}.start" ;;\n'
        "  *) echo '' ;;\n"
        "esac\n"
        "exit 0\n"
    )
    path = os.path.join(bin_dir, "adb")
    with open(path, "w", encoding="utf-8") as f:
        f.write(script)
    os.chmod(path, stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP
             | stat.S_IROTH | stat.S_IXOTH)
    with open(f"{log_path}.start", "w", encoding="utf-8") as f:
        f.write(_COLD_OUTPUT)


def test_argv计划_冷态先清进程再W等待启动():
    recorder = LaunchPerfRecorder("emulator-5554", "com.example.app",
                                  "/tmp/x.jsonl", ts_base=100.0, mono_base=50.0)
    assert recorder.force_stop_argv() == [
        "-s", "emulator-5554", "shell", "am", "force-stop", "com.example.app"]
    assert recorder.resolve_component_argv() == [
        "-s", "emulator-5554", "shell", "cmd", "package", "resolve-activity",
        "--brief", "com.example.app"]
    assert recorder.start_wait_argv("com.example.app/.MainActivity") == [
        "-s", "emulator-5554", "shell", "am", "start", "-W", "-n",
        "com.example.app/.MainActivity"]
    assert recorder.start_wait_argv(None) == [
        "-s", "emulator-5554", "shell", "am", "start", "-W",
        "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER",
        "-p", "com.example.app"]
    # No serial = plain single-device argv (same posture as perf_probe)
    solo = LaunchPerfRecorder(None, "com.example.app", "/tmp/x.jsonl",
                              ts_base=1.0, mono_base=1.0)
    assert solo.force_stop_argv()[0] == "shell"


def test_录制器冷态_先行清理且样例行对齐perf_probe形态(tmp_path):
    log = str(tmp_path / "adb-calls.log")
    _make_shim_adb(str(tmp_path / "bin"), log)
    sink = str(tmp_path / "tp-perf-launches.jsonl")
    old_path = os.environ["PATH"]
    os.environ["PATH"] = f"{tmp_path / 'bin'}:{old_path}"
    try:
        recorder = LaunchPerfRecorder("emulator-5554", "com.example.app", sink,
                                      ts_base=1000.5, mono_base=7.25)
        res = recorder.record("cold")
    finally:
        os.environ["PATH"] = old_path
    assert res["result"] == "pass", res
    calls = open(log, encoding="utf-8").read()
    assert calls.index("force-stop") < calls.index("start -W")
    rows = [json.loads(line) for line in open(sink, encoding="utf-8")]
    assert len(rows) == 1
    row = rows[0]
    assert row["kind"] == "launch" and row["launchMode"] == "cold"
    assert row["launchState"] == "cold" and row["launchTotalMs"] == 1234
    assert row["launchWaitMs"] == 1240
    assert row["serial"] == "emulator-5554" and row["pkg"] == "com.example.app"
    # Dual-clock anchors ride the session values so rows stay sliceable
    assert row["tsBase"] == 1000.5 and row["monoBase"] == 7.25
    assert row["ts"] >= row["tsBase"] - 1 and row["mono"] >= row["monoBase"] - 1


def test_热态不清进程_报文超时诚实失败不落行(tmp_path):
    log = str(tmp_path / "adb-calls.log")
    _make_shim_adb(str(tmp_path / "bin"), log)
    with open(f"{log}.start", "w", encoding="utf-8") as f:
        f.write(_HOT_OUTPUT)
    sink = str(tmp_path / "tp-perf-launches.jsonl")
    old_path = os.environ["PATH"]
    os.environ["PATH"] = f"{tmp_path / 'bin'}:{old_path}"
    try:
        recorder = LaunchPerfRecorder("emulator-5554", "com.example.app", sink,
                                      ts_base=1.0, mono_base=1.0)
        hot = recorder.record("hot")
        with open(f"{log}.start", "w", encoding="utf-8") as f2:
            f2.write(_TIMEOUT_OUTPUT)
        bad = recorder.record("cold")
    finally:
        os.environ["PATH"] = old_path
    assert hot["result"] == "pass" and "210" in hot["detail"]
    calls = open(log, encoding="utf-8").read()
    assert calls[:calls.index("start -W")].count("force-stop") == 0
    assert bad["result"] == "fail"
    assert len([line for line in open(sink, encoding="utf-8")]) == 1


def test_模式非法_adb缺位_诚实失败点名支持面(tmp_path):
    recorder = LaunchPerfRecorder("emulator-5554", "com.example.app",
                                  str(tmp_path / "x.jsonl"), ts_base=1.0,
                                  mono_base=1.0)
    assert recorder.record("warm")["result"] == "fail"
    old_path = os.environ["PATH"]
    os.environ["PATH"] = str(tmp_path / "empty-bin")
    try:
        res = recorder.record("cold")
    finally:
        os.environ["PATH"] = old_path
    assert res["result"] == "fail"
    assert "adb" in res["detail"]


def test_会话闸_无会话诚实拒采(tmp_path):
    res = evaluate_launch_step({"verb": "launchPerf"}, None)
    assert res["result"] == "fail"
    assert "会话" in res["detail"]

    class _Sim:
        active = True

    res = evaluate_launch_step({"verb": "launchPerf"}, _Sim())
    assert res["result"] == "fail"
    assert "launchPerf" in res["detail"]


def test_会话装配_启动耗时文件与录制器同源锚(tmp_path):
    session = PerfSession("emulator-5554", "com.example.app", 1.0, str(tmp_path))
    assert session.launches_path == str(tmp_path / "tp-perf-launches.jsonl")
    session._ts_base = 1000.5
    session._mono_base = 7.25
    recorder = session.launch_recorder()
    assert recorder.sink_path == session.launches_path
    assert recorder.ts_base == 1000.5 and recorder.mono_base == 7.25
    assert recorder.pkg == "com.example.app"


def test_perf消费面_启动耗时指标白名单与读数源分族(tmp_path):
    assert "launchPerf" in HOST_SIDE_VERBS
    assert {"launchTotalMs", "launchWaitMs"} <= SUPPORTED_METRICS
    assert LAUNCH_TOTAL_MS_RE.pattern

    class _Stub:
        active = True

    stub = _Stub()
    stub.samples_path = str(tmp_path / "tp-perf-samples.jsonl")
    stub.launches_path = str(tmp_path / "tp-perf-launches.jsonl")
    # Periodic rows carry no launch keys: launch metrics must read the
    # launch sink (metric-family source routing), never race the probe loop.
    with open(stub.samples_path, "w", encoding="utf-8") as f:
        f.write(json.dumps({"ts": 2.0, "cpuPct": 1.0}) + "\n")
    with open(stub.launches_path, "w", encoding="utf-8") as f:
        f.write(json.dumps({"ts": 1.0, "kind": "launch",
                            "launchTotalMs": 900}) + "\n")
        f.write(json.dumps({"ts": 3.0, "kind": "launch",
                            "launchTotalMs": 3000}) + "\n")
    ok = evaluate_perf_step({"expr": "launchTotalMs<=3000"}, stub)
    assert ok["result"] == "pass", ok
    fail = evaluate_perf_step({"expr": "launchTotalMs<=1000"}, stub)
    assert fail["result"] == "fail" and "3000" in fail["detail"]
    # Periodic metrics keep reading the sample sink (newest-row semantics)
    cpu = evaluate_perf_step({"expr": "cpuPct<=50"}, stub)
    assert cpu["result"] == "pass"
