
from __future__ import annotations

import json
import os
import pathlib
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import ClassVar

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from driver.mobile.perf_session import (
    PERF_CRASHES_FILENAME,
    PERF_SAMPLES_FILENAME,
    PerfSession,
    resolve_target_pkg,
    summarize_rows,
)

SHIM_SRC = '''#!/bin/sh
case "$*" in
  *"dumpsys gfxinfo"*) printf '%b\\n' "Total frames rendered: 100\\nJanky frames: 10 (10.00%)"\\n;;
  *"dumpsys meminfo"*) printf '%b\\n' "TOTAL PSS: 123456\\n";;
  *"dumpsys cpuinfo"*) printf '%b\\n' "12.3%TOTAL: 100 user";;
  *"dumpsys battery"*) printf '%b\\n' "temperature: 435";;
  *"pidof"*) exit 1;;
esac
exit 0
'''


def _install_shim(tmp_path, monkeypatch):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "adb"
    shim.write_text(SHIM_SRC, encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))



def test_包名解析_档案优先步面兜底全缺返回空():
    assert resolve_target_pkg({"envProfile": {"appId": "com.a"}}) == "com.a"
    assert resolve_target_pkg({"envProfile": {"pkg": "com.b"},
                               "steps": [{"pkg": "com.c"}]}) == "com.b"
    assert resolve_target_pkg({"steps": [{"action": "app.tap"},
                                         {"action": "app.clearData",
                                          "pkg": "com.c"}]}) == "com.c"
    assert resolve_target_pkg({"steps": []}) is None
    assert resolve_target_pkg({}) is None



def test_wall_clock_换算双钟锚现算():
    session = PerfSession(serial="em-1", pkg="com.demo", interval=0.05,
                          dir_path="/tmp/unused")
    mono = session._mono_base + 3.0
    assert abs(session.wall_clock(mono) - (session._ts_base + 3.0)) < 0.01



def test_摘要_均值峰值末帧现算():
    samples = [
        {"serial": "em-1", "pkg": "com.demo", "ts": 1.0, "tempC": 40.0,
         "cpuPct": 10.0, "jankPct": 5.0, "pssKb": 100},
        {"serial": "em-1", "pkg": "com.demo", "ts": 2.0, "tempC": 43.5,
         "cpuPct": 88.0, "jankPct": 15.0, "pssKb": 200},
    ]
    row = summarize_rows(samples, [])
    assert row["sampleCount"] == 2
    assert row["lastTempC"] == 43.5
    assert row["maxTempC"] == 43.5
    assert row["maxCpuPct"] == 88.0
    assert row["avgJankPct"] == 10.0
    assert row["serial"] == "em-1" and row["pkg"] == "com.demo"
    assert row["lastTs"] == 2.0


def test_摘要_全空诚实不出行_仅崩溃也出行():
    assert summarize_rows([], []) is None
    crashes = [{"kind": "crash-java", "detail": "崩溃线程[main]", "ts": 9.0,
                "serial": "em-1", "pkg": "com.demo"}]
    row = summarize_rows([], crashes)
    assert row["sampleCount"] == 0
    assert row["crashes"] == crashes



class _FakeAdapter:
    SUPPORTED_PLATFORMS = ("ANDROID",)
    SUPPORTED_DEVICE_KINDS = ("EMULATOR",)
    CAPABILITIES = ()

    def __init__(self):
        self.prepared = False
        self.teared = False

    def prepare(self, ctx):
        self.prepared = True

    def execute(self, step):
        return {"result": "pass"}

    def collect(self, step):
        return []

    def teardown(self):
        self.teared = True


def test_drive_会话接线_样例行文件在位且步行带双钟锚(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("EXECUTOR_PERF_SAMPLE_INTERVAL", "0.05")
    import app_worker

    art_dir = tmp_path / "sess"
    from driver.mobile import perf_session
    monkeypatch.setattr(perf_session, "ART_DIR_BASE", str(art_dir))
    adapter = _FakeAdapter()
    envelope = {"leaseRef": {"deviceId": 1, "serial": "em-1"},
                "envProfile": {"appId": "com.demo"}, "steps": []}
    outcome = app_worker._drive(adapter, [
        {"action": "app.launch", "pkg": "com.demo"},
        {"action": "app.tap"},
    ], {"taskId": 77, "leaseRef": envelope["leaseRef"]}, envelope)
    assert outcome.result == "pass"
    samples = art_dir / "77" / PERF_SAMPLES_FILENAME
    crashes = art_dir / "77" / PERF_CRASHES_FILENAME
    assert samples.exists(), "prepare 后采样线程必落样例行文件"
    assert crashes.exists()
    rows = [json.loads(l) for l in
            samples.read_text(encoding="utf-8").splitlines() if l.strip()]
    assert rows and rows[0]["tempC"] == 43.5 and rows[0]["jankPct"] == 10.0
    for row in outcome.steps_timeline:
        assert "tsStart" in row and "tsEnd" in row, \
            f"步行必带双钟换算锚（包②切片对齐）: {row}"
        assert row["tsEnd"] >= row["tsStart"]


def test_drive_adb缺位_诚实零会话_执行判定零扰动(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path / "empty-bin"))
    import app_worker

    adapter = _FakeAdapter()
    envelope = {"leaseRef": {"serial": "em-1"},
                "envProfile": {"appId": "com.demo"}}
    outcome = app_worker._drive(adapter, [{"action": "app.tap"}],
                                {"taskId": 78}, envelope)
    assert outcome.result == "pass"
    assert not (tmp_path / "empty-bin" / PERF_SAMPLES_FILENAME).exists()
    assert adapter.teared, "会话收必触达 teardown"


def test_drive_缺包名_诚实不建会话(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch)
    import app_worker

    art_dir = tmp_path / "sess2"
    from driver.mobile import perf_session
    monkeypatch.setattr(perf_session, "ART_DIR_BASE", str(art_dir))
    outcome = app_worker._drive(
        _FakeAdapter(), [{"action": "app.tap"}], {"taskId": 79},
        {"leaseRef": {"serial": "em-1"}})
    assert outcome.result == "pass"
    assert not (art_dir / "79").exists() or not \
        (art_dir / "79" / PERF_SAMPLES_FILENAME).exists()
    assert "tsStart" not in outcome.steps_timeline[0]



class _RecorderHandler(BaseHTTPRequestHandler):
    bodies: ClassVar[list] = []

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        _RecorderHandler.bodies.append(json.loads(self.rfile.read(n) or b"{}"))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        body = json.dumps({"code": 0, "data": {"actions": [],
                                               "cancelTaskIds": []}}).encode()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def test_心跳尾追键_空或缺省不出键_有值原样带出():
    from protocol import ExecutorProtocolClient
    _RecorderHandler.bodies = []
    server = ThreadingHTTPServer(("127.0.0.1", 0), _RecorderHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        client = ExecutorProtocolClient(f"http://127.0.0.1:{server.server_address[1]}")
        client.executor_id = 9
        client.heartbeat(0)
        assert "perfReports" not in _RecorderHandler.bodies[0]
        client.heartbeat(0, perf_reports=[])
        assert "perfReports" not in _RecorderHandler.bodies[1]
        reports = [{"serial": "em-1", "sampleCount": 2, "maxTempC": 43.5}]
        client.heartbeat(0, perf_reports=reports)
        assert _RecorderHandler.bodies[2]["perfReports"] == reports
    finally:
        server.shutdown()



def test_runner_排空_摘要出行_崩溃二排不重报(tmp_path):
    import runner

    d = tmp_path / "t1"
    d.mkdir()
    (d / PERF_SAMPLES_FILENAME).write_text(
        json.dumps({"serial": "em-1", "pkg": "com.demo", "ts": 1.0,
                    "tempC": 41.0, "cpuPct": 50.0, "jankPct": 8.0}) + "\n" +
        json.dumps({"serial": "em-1", "pkg": "com.demo", "ts": 2.0,
                    "tempC": 44.0, "cpuPct": 90.0, "jankPct": 12.0}) + "\n",
        encoding="utf-8")
    (d / PERF_CRASHES_FILENAME).write_text(
        json.dumps({"kind": "crash-java", "detail": "崩溃线程[main]",
                    "ts": 1.5, "serial": "em-1", "pkg": "com.demo"}) + "\n",
        encoding="utf-8")
    runner._PERF_TASKS[88] = {"dir": str(d)}
    runner._PERF_CRASH_REPORTED.clear()
    try:
        rows = runner.collect_perf_reports()
        assert len(rows) == 1
        assert rows[0]["taskId"] == 88
        assert rows[0]["sampleCount"] == 2 and rows[0]["maxTempC"] == 44.0
        assert len(rows[0]["crashes"]) == 1
        rows2 = runner.collect_perf_reports()
        assert rows2[0]["crashes"] == []
        assert rows2[0]["sampleCount"] == 2
    finally:
        runner._PERF_TASKS.pop(88, None)
        runner._PERF_CRASH_REPORTED.clear()
    assert runner.collect_perf_reports() == []


def test_runner_排空_目录缺位诚实零行():
    import runner
    runner._PERF_TASKS[89] = {"dir": "/nonexistent-dir-t329"}
    try:
        assert runner.collect_perf_reports() == []
    finally:
        runner._PERF_TASKS.pop(89, None)


def test_send_heartbeat_透传perfReports进尾追键():
    import runner
    from protocol import ExecutorProtocolClient
    _RecorderHandler.bodies = []
    server = ThreadingHTTPServer(("127.0.0.1", 0), _RecorderHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        client = ExecutorProtocolClient(f"http://127.0.0.1:{server.server_address[1]}")
        client.executor_id = 9
        runner.send_heartbeat(client, 0, perf_reports=[{"serial": "s",
                                                        "sampleCount": 1}])
        assert _RecorderHandler.bodies[0]["perfReports"] == [{"serial": "s",
                                                              "sampleCount": 1}]
        runner.send_heartbeat(client, 0)
        assert "perfReports" not in _RecorderHandler.bodies[1]
    finally:
        server.shutdown()
