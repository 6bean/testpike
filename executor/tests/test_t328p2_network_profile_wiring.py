
from __future__ import annotations

import os
import pathlib
import re
import socket
import sys
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from driver.mobile.adb_verb_runner import ADB_FAMILY_VERBS, AdbVerbRunner

ADSHIM_SRC = '''#!/bin/sh
echo "$@" >> "$ADB_SHIM_LOG"
exit 0
'''


class _OriginHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length", "4")
        self.end_headers()
        self.wfile.write(b"pong")


def _start_origin():
    server = ThreadingHTTPServer(("127.0.0.1", 0), _OriginHandler)
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return server, server.server_address[1]


def _install_adb_shim(tmp_path, monkeypatch):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "adb"
    shim.write_text(ADSHIM_SRC, encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))
    log = tmp_path / "adb-argv.log"
    monkeypatch.setenv("ADB_SHIM_LOG", str(log))
    monkeypatch.delenv("TESTPIKE_PROXY_ADVERTISE_HOST", raising=False)
    return log


def _argv_rows(log_path):
    if not log_path.exists():
        return []
    return [line.strip() for line in log_path.read_text(encoding="utf-8").splitlines()]


_SET_ROW_RE = re.compile(r"settings put global http_proxy (\S+):(\d+)")
_CLEAR_RE = re.compile(r"settings put global http_proxy :0")


def _port_alive(host, port):
    try:
        with socket.create_connection((host, int(port)), timeout=0.5):
            return True
    except OSError:
        return False



def test_network_profile_now_in_adb_family():
    pass
    assert "networkProfile" in ADB_FAMILY_VERBS


def test_planner_carries_proxy_address_argv():
    runner = AdbVerbRunner()
    rows = runner.planner("networkProfile", {"proxyAddress": "10.0.2.2:18899"})
    assert rows == [["shell", "settings", "put", "global", "http_proxy",
                    "10.0.2.2:18899"]]



def test_run_profile_sets_device_proxy_with_real_port(tmp_path, monkeypatch):
    log = _install_adb_shim(tmp_path, monkeypatch)
    runner = AdbVerbRunner()
    res = runner.run("networkProfile",
                     {"profile": "3g", "artifactDir": str(tmp_path)},
                     serial="emulator-5554")
    assert res["result"] == "pass", res
    rows = _argv_rows(log)
    set_rows = [r for r in rows if _SET_ROW_RE.search(r)]
    assert set_rows, f"未见 settings put http_proxy 设定行：{rows}"
    host, port = _SET_ROW_RE.search(set_rows[-1]).groups()
    assert host == "10.0.2.2", "模拟器位须广告 10.0.2.2（宿主环回 NAT 实证姿势）"
    assert _port_alive("127.0.0.1", port), "广告端口=真起代理（禁假地址）"
    origin = _start_origin()
    try:
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": f"127.0.0.1:{port}"}))
        opener.open(f"http://127.0.0.1:{origin[1]}/probe", timeout=10).read()
    finally:
        origin[0].shutdown()
        origin[0].server_close()
    timeline = (tmp_path / "tp-net-timeline.jsonl").read_text(encoding="utf-8")
    assert "durMs" in timeline and "127.0.0.1" in timeline
    runner.clear_network_profile(serial="emulator-5554")



def test_run_bad_profile_param_fails_closed_without_adb(tmp_path, monkeypatch):
    log = _install_adb_shim(tmp_path, monkeypatch)
    runner = AdbVerbRunner()
    res = runner.run("networkProfile", {}, serial="emulator-5554")
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "profile" in res["detail"] and "custom" in res["detail"]
    assert _argv_rows(log) == [], "参数违例不得触 adb/起代理"



def test_switch_profile_retracts_before_setting(tmp_path, monkeypatch):
    log = _install_adb_shim(tmp_path, monkeypatch)
    runner = AdbVerbRunner()
    r1 = runner.run("networkProfile", {"profile": "2g"}, serial="emulator-5554")
    assert r1["result"] == "pass", r1
    port1 = int(_SET_ROW_RE.search(
        [r for r in _argv_rows(log) if _SET_ROW_RE.search(r)][-1]).group(2))
    r2 = runner.run("networkProfile", {"profile": "3g"}, serial="emulator-5554")
    assert r2["result"] == "pass", r2
    rows = _argv_rows(log)
    idx_clear = max(i for i, r in enumerate(rows) if _CLEAR_RE.search(r))
    idx_set = max(i for i, r in enumerate(rows) if _SET_ROW_RE.search(r))
    assert idx_clear < idx_set, "换档必须先撤后立（:0 清行先于新设定行，禁叠档）"
    assert not _port_alive("127.0.0.1", port1), "旧档代理必须真停"
    runner.clear_network_profile(serial="emulator-5554")



def test_clear_removes_proxy_and_is_idempotent(tmp_path, monkeypatch):
    log = _install_adb_shim(tmp_path, monkeypatch)
    runner = AdbVerbRunner()
    assert runner.clear_network_profile(serial="emulator-5554")["result"] == "pass"
    assert _argv_rows(log) == [], "无活跃档位时清档零 adb 触达（幂等）"
    res = runner.run("networkProfile", {"profile": "high-latency"},
                     serial="emulator-5554")
    assert res["result"] == "pass", res
    port = int(_SET_ROW_RE.search(
        [r for r in _argv_rows(log) if _SET_ROW_RE.search(r)][-1]).group(2))
    cleared = runner.clear_network_profile(serial="emulator-5554")
    assert cleared["result"] == "pass", cleared
    assert _CLEAR_RE.search(_argv_rows(log)[-1]), "清档须下发 http_proxy :0"
    assert not _port_alive("127.0.0.1", port), "会话收代理必须真停"



def test_real_device_advertises_env_override(tmp_path, monkeypatch):
    log = _install_adb_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("TESTPIKE_PROXY_ADVERTISE_HOST", "192.168.50.7")
    runner = AdbVerbRunner()
    res = runner.run("networkProfile", {"profile": "3g"}, serial="R3CB1234")
    assert res["result"] == "pass", res
    hosts = [_SET_ROW_RE.search(r).group(1)
             for r in _argv_rows(log) if _SET_ROW_RE.search(r)]
    assert hosts[-1] == "192.168.50.7", "真机位须广告执行机可达地址（覆盖键优先）"
    runner.clear_network_profile(serial="R3CB1234")



def test_run_without_adb_binary_fails_env_honest(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path / "empty-bin"))
    monkeypatch.delenv("TESTPIKE_PROXY_ADVERTISE_HOST", raising=False)
    runner = AdbVerbRunner()
    res = runner.run("networkProfile", {"profile": "3g"}, serial="emulator-5554")
    assert res["result"] == "fail" and res["errorType"] == 2
    assert "adb" in res["detail"] and "自行安装" in res["detail"]
