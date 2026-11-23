
from __future__ import annotations

import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from capabilities import collect_components


def _fake_env(tools=None, version_texts=None, pkgs=None):
    pass
    tools = set(tools or [])
    version_texts = version_texts or {}
    pkgs = pkgs or {}
    calls = []

    def which(name):
        return f"/usr/bin/{name}" if name in tools else None

    def run_version(exe, flag="--version"):
        calls.append((exe, flag))
        return version_texts.get((exe, flag))

    def pkg_version(name):
        if name in pkgs:
            return pkgs[name]
        raise Exception(f"No package {name!r}")

    return which, run_version, pkg_version, calls


def _families(comps):
    return {c["family"] for c in comps}



def test_node在位_上报engine_runtime_node族与版本():
    which, run_version, pkg_version, _ = _fake_env(
        tools=["node"],
        version_texts={("node", "--version"): "v22.23.1\n"})
    comps = collect_components(which, run_version, pkg_version)
    row = next(c for c in comps if c["family"] == "engine-runtime.node")
    assert row["currentVersion"] == "22.23.1"


def test_工具缺位_该族诚实缺席():
    pass
    which, run_version, pkg_version, _ = _fake_env(
        tools=["node", "adb"],
        version_texts={("node", "--version"): "v22.23.1"})
    comps = collect_components(which, run_version, pkg_version)
    assert "engine-runtime.mobilecli" not in _families(comps)
    assert "engine-runtime.node" in _families(comps)


def test_版本探测失败_该族诚实缺席不报占位():
    pass
    which, run_version, pkg_version, _ = _fake_env(
        tools=["node", "maestro"],
        version_texts={("node", "--version"): ""})
    comps = collect_components(which, run_version, pkg_version)
    assert all(c["currentVersion"] for c in comps)
    assert "engine-runtime.node" not in _families(comps)
    assert "engine-runtime.maestro" not in _families(comps)


def test_hdc走v旗标探测():
    pass
    which, run_version, pkg_version, calls = _fake_env(
        tools=["hdc"],
        version_texts={("hdc", "-v"): "Ver: 1.2.3\n"})
    comps = collect_components(which, run_version, pkg_version)
    assert ("hdc", "-v") in calls
    assert any(c["family"] == "engine-runtime.hdc" and
               c["currentVersion"] == "1.2.3" for c in comps)


def test_minium按python包在位上报导入元数据版本():
    pass
    which, run_version, pkg_version, _ = _fake_env(
        pkgs={"minium": "1.6.1"})
    real_import_ok = None
    import capabilities as cap
    real = cap._import_ok
    cap._import_ok = lambda name: name == "minium"
    try:
        comps = collect_components(which, run_version, pkg_version)
    finally:
        cap._import_ok = real
    assert any(c["family"] == "engine-runtime.minium" and
               c["currentVersion"] == "1.6.1" for c in comps)


def test_executor_agent版本取出参入参():
    pass
    which, run_version, pkg_version, _ = _fake_env()
    comps = collect_components(which, run_version, pkg_version,
                               executor_version="1.4.2")
    assert {"family": "executor-agent", "currentVersion": "1.4.2"} in comps
    bare = collect_components(which, run_version, pkg_version)
    assert "executor-agent" not in _families(bare)


def test_输出带杂质_抠版本复用单源解析件():
    pass
    which, run_version, pkg_version, _ = _fake_env(
        tools=["mobilecli"],
        version_texts={("mobilecli", "--version"):
                       "mobilecli 0.9.4 (build-7)\n"})
    comps = collect_components(which, run_version, pkg_version)
    assert any(c["family"] == "engine-runtime.mobilecli" and
               c["currentVersion"] == "0.9.4" for c in comps)



def test_本机活体_node在位则族清单真实非空():
    import shutil
    comps = collect_components(executor_version="9.9.9-live")
    assert {"family": "executor-agent", "currentVersion": "9.9.9"} in comps
    if shutil.which("node"):
        assert "engine-runtime.node" in _families(comps)
    for c in comps:
        assert c["family"] and c["currentVersion"]



class _Recorder:
    def __init__(self):
        self.bodies = []
        self.server = HTTPServer(("127.0.0.1", 0), self._handler())
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def _handler(self):
        outer = self

        class H(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                n = int(self.headers.get("Content-Length", 0))
                outer.bodies.append(json.loads(self.rfile.read(n) or b"{}"))
                data = json.dumps({"code": "0", "data": {"ok": True}}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *a):  # noqa: N802, ARG002
                pass

        return H

    def stop(self):
        self.server.shutdown()


def _client_at(port):
    from protocol import ExecutorProtocolClient
    c = ExecutorProtocolClient(f"http://127.0.0.1:{port}")
    c.executor_id = 7
    return c


def test_heartbeat_components空或缺省不出键():
    pass
    rec = _Recorder()
    try:
        client = _client_at(rec.port)
        client.heartbeat(1)
        assert "components" not in rec.bodies[0]
        client.heartbeat(1, components=[])
        assert "components" not in rec.bodies[1]
    finally:
        rec.stop()


def test_heartbeat_components有值原样尾追():
    rec = _Recorder()
    try:
        client = _client_at(rec.port)
        comps = [{"family": "engine-runtime.node", "currentVersion": "22.23.1"}]
        client.heartbeat(1, components=comps)
        assert rec.bodies[0]["components"] == comps
        assert rec.bodies[0]["runningTasks"] == 1
    finally:
        rec.stop()



def test_send_heartbeat_透传部件清单进心跳():
    pass
    import runner

    sent = []

    class Stub:
        def heartbeat(self, running_tasks, monitor_frames=None, components=None,
                      net_proxy_audits=None, perf_reports=None, devices=None,
                      device_project_scope=None, perf_partial=None):
            sent.append({"runningTasks": running_tasks,
                         "monitorFrames": monitor_frames,
                         "components": components})
            return {"ok": True}

    stub = Stub()
    runner.send_heartbeat(stub, 2, components=[{"family": "executor-agent",
                                                "currentVersion": "1.0.0"}])
    assert sent[0]["components"] == [{"family": "executor-agent",
                                      "currentVersion": "1.0.0"}]
    runner.send_heartbeat(stub, 0)
    assert sent[1]["components"] is None
