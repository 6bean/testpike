
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

from driver.mobile.adb_verb_runner import AdbVerbRunner

SHIM_SRC = '''#!/bin/sh
echo "$@" >> "$ADB_SHIM_LOG"
case "$*" in
  *"settings get"*) echo "${SHIM_PROXY_VALUE-null}"; exit ${SHIM_GET_RC:-0};;
  *"settings put"*) exit ${SHIM_PUT_RC:-0};;
esac
exit 0
'''


def _install_shim(tmp_path, monkeypatch, *, value=None, get_rc=0, put_rc=0,
                  empty_bin=False):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    if not empty_bin:
        shim = bin_dir / "adb"
        shim.write_text(SHIM_SRC, encoding="utf-8")
        os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))
    log = tmp_path / "adb-argv.log"
    monkeypatch.setenv("ADB_SHIM_LOG", str(log))
    monkeypatch.setenv("SHIM_PROXY_VALUE", value) if value is not None \
        else monkeypatch.delenv("SHIM_PROXY_VALUE", raising=False)
    monkeypatch.setenv("SHIM_GET_RC", str(get_rc))
    monkeypatch.setenv("SHIM_PUT_RC", str(put_rc))
    return log


def _argv_rows(log_path):
    if not log_path.exists():
        return []
    return [line.strip() for line in
            log_path.read_text(encoding="utf-8").splitlines()]



def test_残留代理_探测行后单源清行并上报对账行(tmp_path, monkeypatch):
    pass
    log = _install_shim(tmp_path, monkeypatch, value="10.0.2.2:8888")
    row = AdbVerbRunner().audit_net_proxy("emulator-5554")
    assert row is not None
    assert row["serial"] == "emulator-5554"
    assert row["proxy"] == "10.0.2.2:8888"
    assert row["action"] == "cleared"
    rows = _argv_rows(log)
    assert any("shell settings get global http_proxy" in r for r in rows)
    assert any("shell settings put global http_proxy :0" in r for r in rows)
    assert rows.index(next(r for r in rows if "get global" in r)) < \
        rows.index(next(r for r in rows if "put global" in r))
    assert all(r.startswith("-s emulator-5554") for r in rows)


def test_清位值_诚实静默零清除零上报(tmp_path, monkeypatch):
    pass
    for clean in (":0", "null", ""):
        log = _install_shim(tmp_path, monkeypatch, value=clean)
        if log.exists():
            log.unlink()
        assert AdbVerbRunner().audit_net_proxy("emulator-5556") is None, clean
        assert not any("put global" in r for r in _argv_rows(log)), clean


def test_清除行失败_仍上报clear_failed禁假装已清(tmp_path, monkeypatch):
    pass
    _install_shim(tmp_path, monkeypatch, value="192.168.1.7:9000", put_rc=1)
    row = AdbVerbRunner().audit_net_proxy("192.168.1.50:5555")
    assert row is not None and row["action"] == "clear-failed"
    assert row["proxy"] == "192.168.1.7:9000"
    assert "非零退出" in str(row["detail"])


def test_adb缺件_诚实跳过零上报行(tmp_path, monkeypatch):
    pass
    _install_shim(tmp_path, monkeypatch, empty_bin=True)
    assert AdbVerbRunner().audit_net_proxy("emulator-5557") is None


def test_探测行失败_诚实跳过(tmp_path, monkeypatch):
    pass
    log = _install_shim(tmp_path, monkeypatch, get_rc=1)
    assert AdbVerbRunner().audit_net_proxy("emulator-5558") is None
    assert not any("put global" in r for r in _argv_rows(log))



class _RecorderHandler(BaseHTTPRequestHandler):
    bodies: ClassVar[list] = []

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        _RecorderHandler.bodies.append(json.loads(self.rfile.read(n) or b"{}"))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        body = b'{"code":0,"data":{"ok":true}}'
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def _stub_server():
    _RecorderHandler.bodies = []
    server = ThreadingHTTPServer(("127.0.0.1", 0), _RecorderHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def test_心跳尾追键_空或缺省不出键_有值原样带出():
    from protocol import ExecutorProtocolClient
    server = _stub_server()
    try:
        client = ExecutorProtocolClient(f"http://127.0.0.1:{server.server_address[1]}")
        client.executor_id = 9
        client.heartbeat(0)
        assert "netProxyAudits" not in _RecorderHandler.bodies[0]
        client.heartbeat(0, net_proxy_audits=[])
        assert "netProxyAudits" not in _RecorderHandler.bodies[1]
        audits = [{"serial": "emulator-5554", "proxy": "10.0.2.2:8080",
                   "action": "cleared", "detail": "已清"}]
        client.heartbeat(0, net_proxy_audits=audits)
        assert _RecorderHandler.bodies[2]["netProxyAudits"] == audits
    finally:
        server.shutdown()



def test_collect_proxy_audits_排除活跃会话串行():
    pass
    import runner

    calls = []

    class FakeAuditor:
        def audit_net_proxy(self, serial):
            calls.append(serial)
            return {"serial": serial, "proxy": "1.2.3.4:8", "action": "cleared",
                    "detail": "已清"}

    real = runner.AdbVerbRunner
    try:
        runner.AdbVerbRunner = FakeAuditor
        runner._NET_SERIAL_SEEN.update({"serial-active": 0.0, "serial-idle": 0.0})
        runner._ACTIVE_LEASES[9101] = {"deviceId": 1, "serial": "serial-active"}
        runner._PROXY_AUDIT_LAST[0] = 0.0
        rows = runner.collect_proxy_audits(interval_sec=0, now=10_000.0)
        assert calls == ["serial-idle"]
        assert rows and rows[0]["serial"] == "serial-idle"
    finally:
        runner.AdbVerbRunner = real
        runner._NET_SERIAL_SEEN.pop("serial-active", None)
        runner._NET_SERIAL_SEEN.pop("serial-idle", None)
        runner._ACTIVE_LEASES.pop(9101, None)


def test_collect_proxy_audits_低频闸未到_零触达():
    pass
    import runner

    calls = []

    class FakeAuditor:
        def audit_net_proxy(self, serial):
            calls.append(serial)

    real = runner.AdbVerbRunner
    try:
        runner.AdbVerbRunner = FakeAuditor
        runner._NET_SERIAL_SEEN.update({"serial-gated": 0.0})
        runner._PROXY_AUDIT_LAST[0] = 100.0
        assert runner.collect_proxy_audits(interval_sec=60, now=130.0) == []
        assert calls == []
        assert runner.collect_proxy_audits(interval_sec=60, now=161.0) == []
        assert calls == ["serial-gated"]
        assert runner._PROXY_AUDIT_LAST[0] == 161.0
    finally:
        runner.AdbVerbRunner = real
        runner._NET_SERIAL_SEEN.pop("serial-gated", None)


def test_send_heartbeat_透传巡检行进尾追键():
    import runner

    sent = []

    class Stub:
        def heartbeat(self, running_tasks, monitor_frames=None, components=None,
                      net_proxy_audits=None, perf_reports=None, devices=None,
                      device_project_scope=None, perf_partial=None):
            sent.append(net_proxy_audits)
            return {"ok": True}

    runner.send_heartbeat(Stub(), 1, net_proxy_audits=[{"serial": "s1"}])
    assert sent[0] == [{"serial": "s1"}]
    runner.send_heartbeat(Stub(), 1, net_proxy_audits=[])
    assert sent[1] is None
