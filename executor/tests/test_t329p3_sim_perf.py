
from __future__ import annotations

import json
import os
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from driver.mobile.perf_session import PERF_SAMPLES_FILENAME, PerfSession

SHIM_XCRUN = '''#!/bin/sh
case "$*" in
  *"top -l 1"*) printf '%b\\n' "CPU usage: 10.5% user, 22.0% sys, 67.5% idle\\nPhysMem: 15G used (3G wired), 1G unused."\\n;;
esac
exit 0
'''


def _install_xcrun_shim(tmp_path, monkeypatch):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "xcrun"
    shim.write_text(SHIM_XCRUN, encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))



def test_top_解析_cpu用户系统现算_坏行诚实缺键():
    from driver.mobile.sim_perf import parse_top_cpu

    row = parse_top_cpu("CPU usage: 10.5% user, 22.0% sys, 67.5% idle")
    assert abs(row - 32.5) < 1e-6
    assert parse_top_cpu("完全不像 top 的输出") is None
    assert parse_top_cpu("") is None
    assert parse_top_cpu(None) is None


def test_top_解析_内存占用率单位归一现算():
    from driver.mobile.sim_perf import parse_top_mem

    val = parse_top_mem("PhysMem: 15G used (3G wired), 1G unused.")
    assert val is not None and abs(val - 93.75) < 0.01
    val_m = parse_top_mem("PhysMem: 512M used, 512M unused.")
    assert val_m is not None and abs(val_m - 50.0) < 0.01
    assert parse_top_mem("PhysMem: 读不到") is None


def test_帧差估fps_帧间隔现算_样本不足诚实空():
    from driver.mobile.sim_perf import estimate_fps

    frames = [{"ts": 100.0}, {"ts": 101.0}, {"ts": 103.0}]
    assert abs(estimate_fps(frames) - 0.67) < 0.01  # (n-1)/span=2/3
    assert estimate_fps([]) is None
    assert estimate_fps([{"ts": 1.0}]) is None
    assert estimate_fps([{"ts": 5.0}, {"ts": 5.0}]) is None
    assert estimate_fps([{"ts": "坏"}, {}]) is None



def test_探针_缺xcrun诚实不可用_shim在位可用():
    from driver.mobile.sim_perf import probe_sim_prefix

    assert probe_sim_prefix(which=lambda name: None) is None
    assert probe_sim_prefix(which=lambda name: (
        "/usr/bin/simctl" if name == "simctl" else None)) == ["simctl"]
    assert probe_sim_prefix(which=lambda name: (
        "/usr/bin/xcrun" if name == "xcrun" else None)) == ["xcrun", "simctl"]


def test_会话_xcrun缺位_诚实不start_零行文件(tmp_path):
    import shutil as _sh

    from driver.mobile.sim_perf import SimPerfSession

    real = _sh.which
    _sh.which = lambda name: None if name in ("xcrun", "simctl") else real(name)
    try:
        session = SimPerfSession(udid="UDID-1", interval=0.05,
                                 dir_path=str(tmp_path / "s1"))
        assert session.start() is False
        assert not (tmp_path / "s1" / PERF_SAMPLES_FILENAME).exists()
        session.stop()
    finally:
        _sh.which = real


def test_会话_shim采样_样例行带cpu内存键与双钟锚(tmp_path, monkeypatch):
    _install_xcrun_shim(tmp_path, monkeypatch)
    from driver.mobile.sim_perf import SimPerfSession

    session = SimPerfSession(udid="UDID-1", interval=0.05,
                             dir_path=str(tmp_path / "s2"))
    assert session.start() is True
    row = session.sample_once()
    session.stop()
    assert row is not None
    assert abs(row["cpuPct"] - 32.5) < 1e-6
    assert row["memUsedPct"] is not None
    assert row["serial"] == "UDID-1"
    assert row["source"] == "sim-host"
    assert isinstance(row["ts"], float) and isinstance(row["mono"], float)


def test_会话_帧行管道在位_fps估帧入样(tmp_path, monkeypatch):
    _install_xcrun_shim(tmp_path, monkeypatch)
    from driver.mobile.sim_perf import SimPerfSession

    sink = tmp_path / "frames.jsonl"
    lines = [json.dumps({"ts": 100.0, "image": "aGk="}),
             json.dumps({"ts": 101.0, "image": "aGk="}),
             json.dumps({"ts": 102.0, "image": "aGk="}),
             json.dumps({"ts": 103.0, "image": "aGk="})]
    sink.write_text("\n".join(lines) + "\n", encoding="utf-8")
    session = SimPerfSession(udid="U", interval=0.05,
                             dir_path=str(tmp_path / "s3"),
                             frame_sink=str(sink))
    row = session.sample_once()
    assert row["fpsEst"] is not None and abs(row["fpsEst"] - 1.0) < 0.01



class _FakeAdapter:
    SUPPORTED_PLATFORMS = ("IOS",)
    SUPPORTED_DEVICE_KINDS = ("SIMULATOR",)
    CAPABILITIES = ()

    def __init__(self):
        self.teared = False

    def prepare(self, ctx):
        pass

    def execute(self, step):
        return {"result": "pass"}

    def collect(self, step):
        return []

    def teardown(self):
        self.teared = True


def test_drive_IOS描述符_走宿主采样_样例行在位(tmp_path, monkeypatch):
    _install_xcrun_shim(tmp_path, monkeypatch)
    import app_worker
    from driver.mobile import perf_session
    monkeypatch.setattr(perf_session, "ART_DIR_BASE", str(tmp_path / "perf"))

    envelope = {"engine": {"code": "XCUITEST", "platformCodes": ["IOS"]},
                "leaseRef": {"deviceId": 9, "serial": "UDID-9"},
                "envProfile": {"appId": "com.demo"}, "steps": []}
    outcome = app_worker._drive(
        _FakeAdapter(), [{"action": "app.tap"}],
        {"taskId": 81, "leaseRef": envelope["leaseRef"]}, envelope)
    assert outcome.result == "pass"
    samples = tmp_path / "perf" / "81" / PERF_SAMPLES_FILENAME
    assert samples.exists(), "IOS 会话必走宿主采样件落样行文件"
    rows = [json.loads(l) for l in
            samples.read_text(encoding="utf-8").splitlines() if l.strip()]
    assert rows and rows[0]["source"] == "sim-host"
    for row in outcome.steps_timeline:
        assert "tsStart" in row and "tsEnd" in row


def test_drive_IOS宿主件缺位_诚实零会话_执行判定零扰动(tmp_path, monkeypatch):
    import app_worker
    from driver.mobile import perf_session
    monkeypatch.setattr(perf_session, "ART_DIR_BASE", str(tmp_path / "perf"))

    envelope = {"engine": {"code": "XCUITEST", "platformCodes": ["IOS"]},
                "leaseRef": {"serial": "UDID-9"}}
    outcome = app_worker._drive(
        _FakeAdapter(), [{"action": "app.tap"}],
        {"taskId": 82, "leaseRef": envelope["leaseRef"]}, envelope)
    assert outcome.result == "pass"
    d = tmp_path / "perf" / "82"
    assert not d.exists() or not (d / PERF_SAMPLES_FILENAME).exists()
    assert "tsStart" not in outcome.steps_timeline[0]


def test_drive_安卓描述符仍走adb会话_分型不回退(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path / "no-bin"))
    import app_worker
    from driver.mobile import perf_session
    monkeypatch.setattr(perf_session, "ART_DIR_BASE", str(tmp_path / "perf"))

    envelope = {"engine": {"code": "MOBILEWRIGHT", "platformCodes": ["ANDROID"]},
                "leaseRef": {"serial": "em-1"}, "envProfile": {"appId": "com.demo"}}
    outcome = app_worker._drive(
        _FakeAdapter(), [{"action": "app.tap"}],
        {"taskId": 83, "leaseRef": envelope["leaseRef"]}, envelope)
    assert outcome.result == "pass"
    session = PerfSession(serial="em-1", pkg="com.demo", interval=1.0,
                          dir_path=str(tmp_path / "x"))
    assert session.start() is False


def test_drive_鸿蒙描述符_执行侧零会话_引擎原生件复用位(tmp_path, monkeypatch):
    pass
    monkeypatch.setenv("PATH", str(tmp_path / "bin"))
    import app_worker
    from driver.mobile import perf_session
    monkeypatch.setattr(perf_session, "ART_DIR_BASE", str(tmp_path / "perf"))

    envelope = {"engine": {"code": "HMNEXTAUTO",
                           "platformCodes": ["HARMONY_NEXT"]},
                "leaseRef": {"serial": "hm-1"},
                "envProfile": {"appId": "com.demo"}}
    outcome = app_worker._drive(
        _FakeAdapter(), [{"action": "app.tap"}],
        {"taskId": 84, "leaseRef": envelope["leaseRef"]}, envelope)
    assert outcome.result == "pass"
    d = tmp_path / "perf" / "84"
    assert not d.exists() or not (d / PERF_SAMPLES_FILENAME).exists()
    assert "tsStart" not in outcome.steps_timeline[0]
