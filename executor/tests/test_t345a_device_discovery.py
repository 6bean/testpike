
from __future__ import annotations

import os
import pathlib
import sys
from contextlib import contextmanager
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

import device_discovery
from device_discovery import classify_serial, collect_devices, parse_adb_devices

_HEADER = "List of devices attached"
_EMU = "emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64a transport_id:1"
_USB = "0B123456789ABCDEF      device product:panther model:Pixel_7 device:panther transport_id:2"
_NET = "192.168.1.5:5555       device product:heroltexx model:SM_G9810 device:herolte transport_id:3"
_UNAUTH = "R58A123456           unauthorized product:foo model:Galaxy_S8 device:foo transport_id:4"
_OFF = "10.0.0.9:6666          offline transport_id:5"

_SAMPLE = "\n".join(["* daemon started successfully *", _HEADER,
                     _EMU, _USB, _NET, _UNAUTH, _OFF, ""])


def _make_shim(tmp_path, sample_text: str, rc: int = 0, name: str = "adb"):
    pass
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    data = bin_dir / "adb-sample.txt"
    data.write_text(sample_text, encoding="utf-8")
    shim = bin_dir / name
    shim.write_text("#!/bin/sh\ncat %s\nexit %d\n" % (data, rc), encoding="utf-8")
    os.chmod(shim, 0o755)
    return bin_dir


@contextmanager
def shim_env(bin_dir, clock):
    pass
    device_discovery._reset_cache_for_tests()
    with mock.patch.dict(os.environ,
                         {"PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}"},
                         ), \
            mock.patch.object(device_discovery.time, "monotonic", clock):
        yield



def test_parse_三形态_表头信息行跳过_model与状态如实():
    rows = parse_adb_devices(_SAMPLE)
    by_serial = {r["serial"]: r for r in rows}
    assert set(by_serial) == {"emulator-5554", "0B123456789ABCDEF",
                              "192.168.1.5:5555", "R58A123456", "10.0.0.9:6666"}
    assert by_serial["emulator-5554"]["state"] == "device"
    assert by_serial["emulator-5554"]["model"] == "sdk_gphone64_x86_64"
    assert by_serial["0B123456789ABCDEF"]["model"] == "Pixel_7"
    assert by_serial["R58A123456"]["state"] == "unauthorized"
    assert by_serial["10.0.0.9:6666"]["state"] == "offline"


def test_parse_仅表头_真实无机返回空():
    assert parse_adb_devices(_HEADER + "\n") == []


def test_判定规则_emulator网络usb三态与adbAddress():
    assert classify_serial("emulator-5554") == ("EMULATOR", None)
    assert classify_serial("192.168.1.5:5555") == ("ADB", "192.168.1.5:5555")
    assert classify_serial("0B123456789ABCDEF") == ("USB", None)
    assert classify_serial("hub:port:weird") == ("USB", None)
    assert classify_serial("1.2.3.4:999999") == ("USB", None)



def test_collect_adb缺位_返回None():
    clock = lambda: 1000.0
    device_discovery._reset_cache_for_tests()
    with mock.patch.dict(os.environ, {"PATH": "/nonexistent-dir"}), \
            mock.patch.object(device_discovery.time, "monotonic", clock):
        assert collect_devices(interval_sec=30.0) is None


def test_collect_命令非零退出_返回None(tmp_path):
    bin_dir = _make_shim(tmp_path, "boom\n", rc=1)
    with shim_env(bin_dir, lambda: 2000.0):
        assert collect_devices(interval_sec=30.0) is None


def test_collect_在线行含建档事实_离线行creatable假(tmp_path):
    bin_dir = _make_shim(tmp_path, _SAMPLE)
    with shim_env(bin_dir, lambda: 3000.0):
        rows = collect_devices(interval_sec=30.0)
    by_serial = {r["serial"]: r for r in rows}
    assert by_serial["emulator-5554"]["deviceType"] == "EMULATOR"
    assert by_serial["emulator-5554"]["platform"] == "ANDROID"
    assert by_serial["emulator-5554"]["hostOs"] == device_discovery.host_os_name()
    assert by_serial["emulator-5554"]["creatable"] is True
    assert "adbAddress" not in by_serial["emulator-5554"]
    assert by_serial["192.168.1.5:5555"]["deviceType"] == "ADB"
    assert by_serial["192.168.1.5:5555"]["adbAddress"] == "192.168.1.5:5555"
    assert by_serial["0B123456789ABCDEF"]["deviceType"] == "USB"
    assert by_serial["R58A123456"]["creatable"] is False
    assert by_serial["10.0.0.9:6666"]["creatable"] is False


def test_collect_仅表头_空数组非None(tmp_path):
    bin_dir = _make_shim(tmp_path, _HEADER + "\n")
    with shim_env(bin_dir, lambda: 4000.0):
        rows = collect_devices(interval_sec=30.0)
    assert rows == []



def test_collect_间隔内复采_零重复触达(tmp_path):
    bin_dir = _make_shim(tmp_path, _HEADER + "\n" + _EMU + "\n")
    clock = {"t": 5000.0}
    calls = {"n": 0}
    real_run = device_discovery.subprocess.run

    def counting_run(*a, **kw):
        calls["n"] += 1
        return real_run(*a, **kw)

    with shim_env(bin_dir, lambda: clock["t"]), \
            mock.patch.object(device_discovery.subprocess, "run", counting_run):
        first = collect_devices(interval_sec=30.0)
        clock["t"] += 5.0
        cached = collect_devices(interval_sec=30.0)
        clock["t"] += 35.0
        collect_devices(interval_sec=30.0)
    assert calls["n"] == 2
    assert cached == first



class _StubClient:
    def __init__(self):
        self.calls = []

    def heartbeat(self, running_tasks, monitor_frames=None, components=None,
                  net_proxy_audits=None, perf_reports=None, devices=None,
                  device_project_scope=None, perf_partial=None):
        self.calls.append({"runningTasks": running_tasks,
                           "devices": devices,
                           "deviceProjectScope": device_project_scope})
        return {"ok": True}


def test_send_heartbeat_devices尾追_None与空不出键():
    import runner
    stub = _StubClient()
    runner.send_heartbeat(stub, 0)
    assert stub.calls[-1]["devices"] is None
    runner.send_heartbeat(stub, 0, devices=[])
    assert stub.calls[-1]["devices"] is None
    runner.send_heartbeat(stub, 0, devices=[{"serial": "em-1"}],
                          project_scope="7")
    assert stub.calls[-1]["devices"] == [{"serial": "em-1"}]
    assert stub.calls[-1]["deviceProjectScope"] == "7"


def test_send_heartbeat_通道炸_devices不回投():
    import runner

    class _Boom(_StubClient):
        def heartbeat(self, *a, **kw):
            raise RuntimeError("channel down")

    try:
        runner.send_heartbeat(_Boom(), 0, devices=[{"serial": "em-1"}],
                              project_scope="")
    except RuntimeError:
        pass
    stub2 = _StubClient()
    runner.send_heartbeat(stub2, 0)
    assert stub2.calls[-1]["devices"] is None


def test_protocol_heartbeat_条件出键():
    from protocol import ExecutorProtocolClient

    captured = {}

    class _C(ExecutorProtocolClient):
        def __init__(self):
            self.executor_id = 1

        def _post(self, path, body):
            captured["body"] = body
            return {"ok": True}

    c = _C()
    c.heartbeat(0)
    assert "devices" not in captured["body"]
    c.heartbeat(0, devices=[{"serial": "s"}], device_project_scope="3")
    assert captured["body"]["devices"] == [{"serial": "s"}]
    assert captured["body"]["deviceProjectScope"] == "3"
    c.heartbeat(0, devices=[{"serial": "s"}])
    assert "deviceProjectScope" not in captured["body"]
