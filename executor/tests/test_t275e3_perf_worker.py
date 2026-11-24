
from __future__ import annotations

import json
import os
import stat
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from driver.webdriver import RunOutcome
from protocol import ExecutorProtocolClient
from runner import _TASK_TYPE_PERF_RUN, _worker_for



class TestRunnerRouting:
    pass

    def test_task_type_5_分流到perf_worker(self):
        assert _TASK_TYPE_PERF_RUN == 5
        path = _worker_for(5)
        assert path.endswith("perf_worker.py")
        assert os.path.exists(path), f"perf_worker 入口文件不存在: {path}"

    def test_存量分流零漂移(self):
        assert _worker_for(4) != _worker_for(5)
        assert _worker_for(4).endswith("api_runner.py")
        assert _worker_for(1).endswith("task_worker.py")


class TestOutcomePerfKeys:
    pass

    def test_空键不出现在序列化(self):
        out = RunOutcome(result="pass").to_dict()
        assert "perf_frames" not in out
        assert "perf_events" not in out
        assert "perf_status" not in out

    def test_非空三键往返(self):
        frames = [{"secondOffset": 0, "rps": 2.0, "p95Ms": 200.0, "errorRate": 0.0}]
        events = [{"secondOffset": 1, "eventKind": "engine-exit", "detail": "exit 1"}]
        o = RunOutcome(result="pass", perf_frames=frames, perf_events=events,
                       perf_status=2)
        data = o.to_dict()
        assert data["perf_frames"] == frames
        assert data["perf_events"] == events
        assert data["perf_status"] == 2
        back = RunOutcome.from_dict(data)
        assert back.perf_frames == frames
        assert back.perf_events == events
        assert back.perf_status == 2




class _FakeProtocolServer:
    pass

    def __init__(self) -> None:
        self.requests: list[dict] = []
        self.server = HTTPServer(("127.0.0.1", 0), self._handler_factory())
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def _handler_factory(self):
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(length) if length else b"{}"
                outer.requests.append({"path": self.path, "body": json.loads(raw)})
                data = json.dumps({"code": "0", "data": {}}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, format, *args):  # noqa: A002, ARG002
                pass

        return Handler

    def stop(self) -> None:
        self.server.shutdown()


@pytest.fixture
def fake_server():
    srv = _FakeProtocolServer()
    yield srv
    srv.stop()


def _client(srv: _FakeProtocolServer) -> ExecutorProtocolClient:
    client = ExecutorProtocolClient(f"http://127.0.0.1:{srv.port}")
    client.executor_id = 1
    return client


class TestSubmitResultPerfKeys:
    pass

    def test_空perf键不出现在payload(self, fake_server):
        client = _client(fake_server)
        client.submit_result(1001, "tk-1", "pass")
        body = fake_server.requests[-1]["body"]
        assert "perfFrames" not in body
        assert "perfEvents" not in body
        assert "perfStatus" not in body

    def test_非空perf键尾追进payload(self, fake_server):
        client = _client(fake_server)
        frames = [{"secondOffset": 0, "rps": 1.5, "p95Ms": 100.0, "errorRate": 0.0}]
        events = [{"secondOffset": 3, "eventKind": "engine-exit", "detail": "非零退出"}]
        client.submit_result(1002, "tk-2", "pass",
                             perf_frames=frames, perf_events=events, perf_status=2)
        body = fake_server.requests[-1]["body"]
        assert body["perfFrames"] == frames
        assert body["perfEvents"] == events
        assert body["perfStatus"] == 2

    def test_runner_execute_task透传三键(self, fake_server, monkeypatch, tmp_path):
        import runner

        frames = [{"secondOffset": 0, "rps": 2.0, "p95Ms": 200.0, "errorRate": 0.0}]
        outcome = RunOutcome(result="pass", perf_frames=frames, perf_status=2)
        with open(tmp_path / "outcome.json", "w", encoding="utf-8") as f:
            json.dump(outcome.to_dict(), f, ensure_ascii=False)

        fake = _FakeProc(exit_file=None)

        def _fake_popen(argv, **kwargs):
            out_f = argv[-1]
            with open(tmp_path / "outcome.json", encoding="utf-8") as src:
                with open(out_f, "w", encoding="utf-8") as dst:
                    dst.write(src.read())
            return fake

        from config import ExecutorConfig
        cfg = ExecutorConfig()
        monkeypatch.setattr(runner.subprocess, "Popen", _fake_popen)
        monkeypatch.setattr(runner.subprocess, "run", lambda *a, **k: None)
        task = {"taskId": 5001, "claimToken": "tk-5", "taskType": 5,
                "actionJson": "{\"kind\":\"perf-run\",\"runId\":9}",
                "perf": {"runId": 9, "engine": "K6", "artifact": "/x",
                         "pressureSnapshot": "{}"}}
        runner.execute_task(_client(fake_server), task, cfg)
        body = fake_server.requests[-1]["body"]
        assert body["perfFrames"] == frames
        assert body["perfStatus"] == 2
        assert "perfEvents" not in body


class _FakeProc:
    pass

    def __init__(self, exit_file):
        self.pid = os.getpid()
        self.returncode = 0
        self.stdout = None
        self.stderr = None

    def communicate(self, input=None, timeout=None):  # noqa: A002
        return ("", "")

    def terminate(self):
        pass

    def wait(self, timeout=None):
        return 0

    def kill(self):
        pass




class TestPerfWorkerEnvelope:
    pass

    def _run(self, task: dict) -> RunOutcome:
        from perf_worker import run_perf_task
        return run_perf_task(task)

    def test_缺perf上下文_人话终止归3(self):
        o = self._run({"taskId": 1, "actionJson": "{\"kind\":\"perf-run\"}"})
        assert o.result == "fail"
        assert o.error_type == 3
        assert "压测运行上下文" in (o.error_detail or "")

    def test_gatling引擎_执行支缺产物人话点名禁静默降级(self):
        # subprocess form: GATLING now dispatches to _run_gatling (the old
        # "integration not built" refusal is retired). This envelope points at a
        # nonexistent artifact = dispatch-chain break, named before the binary
        # check (ordering mirrors _run_k6) — and never a silent k6 substitution.
        o = self._run({"taskId": 2, "perf": {
            "runId": 9, "engine": "GATLING", "artifact": "/tmp/x.scala",
            "pressureSnapshot": "{}"}})
        assert o.result == "fail"
        assert o.error_type == 3
        assert "编译产物" in (o.error_detail or "")

    def test_k6缺二进制_人话点名(self, tmp_path):
        art = tmp_path / "PerfGenK6_1.js"
        art.write_text("// compiled", encoding="utf-8")
        old = os.environ.get("PATH", "")
        os.environ["PATH"] = str(tmp_path)
        try:
            o = self._run({"taskId": 3, "perf": {
                "runId": 9, "engine": "K6", "artifact": str(art),
                "pressureSnapshot": "{}"}})
        finally:
            os.environ["PATH"] = old
        assert o.result == "fail"
        assert o.error_type == 3
        assert "k6" in (o.error_detail or "") and "缺" in (o.error_detail or "")

    def test_产物不可读_人话点名(self, tmp_path):
        art = tmp_path / "gone.js"
        o = self._run({"taskId": 4, "perf": {
            "runId": 9, "engine": "K6", "artifact": str(art),
            "pressureSnapshot": "{}"}})
        assert o.result == "fail"
        assert o.error_type == 3
        assert "产物" in (o.error_detail or "")


class TestPerfWorkerK6Shim:
    pass

    def _make_shim(self, tmp_path, exit_code: int, stderr_text: str = "") -> str:
        shim = tmp_path / "k6"
        shim.write_text(
            f"#!{sys.executable}\n"
            "import sys\n"
            "args = sys.argv\n"
            "out = None\n"
            "for i, a in enumerate(args):\n"
            "    if a == '--out' and i + 1 < len(args): out = args[i + 1]\n"
            "if out and out.startswith('json='):\n"
            "    lines = [\n"
            "      '{\\\"type\\\":\\\"Point\\\",\\\"metric\\\":\\\"http_reqs\\\",\\\"data\\\":{\\\"time\\\":\\\"2026-09-30T00:00:01.000Z\\\",\\\"value\\\":2,\\\"tags\\\":{}}}',\n"
            "      '{\\\"type\\\":\\\"Point\\\",\\\"metric\\\":\\\"http_req_duration\\\",\\\"data\\\":{\\\"time\\\":\\\"2026-09-30T00:00:01.200Z\\\",\\\"value\\\":100,\\\"tags\\\":{}}}',\n"
            "      '{\\\"type\\\":\\\"Point\\\",\\\"metric\\\":\\\"http_req_duration\\\",\\\"data\\\":{\\\"time\\\":\\\"2026-09-30T00:00:01.800Z\\\",\\\"value\\\":200,\\\"tags\\\":{}}}',\n"
            "      '{\\\"type\\\":\\\"Point\\\",\\\"metric\\\":\\\"http_reqs\\\",\\\"data\\\":{\\\"time\\\":\\\"2026-09-30T00:00:02.000Z\\\",\\\"value\\\":1,\\\"tags\\\":{}}}',\n"
            "      '{\\\"type\\\":\\\"Point\\\",\\\"metric\\\":\\\"http_req_duration\\\",\\\"data\\\":{\\\"time\\\":\\\"2026-09-30T00:00:02.500Z\\\",\\\"value\\\":300,\\\"tags\\\":{}}}',\n"
            "      '{\\\"type\\\":\\\"Point\\\",\\\"metric\\\":\\\"http_req_failed\\\",\\\"data\\\":{\\\"time\\\":\\\"2026-09-30T00:00:02.600Z\\\",\\\"value\\\":1,\\\"tags\\\":{}}}',\n"
            "    ]\n"
            "    open(out[5:], 'w').write('\\n'.join(lines))\n"
            f"sys.stderr.write({stderr_text!r})\n"
            f"sys.exit({exit_code})\n",
            encoding="utf-8")
        shim.chmod(shim.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
        return str(tmp_path)

    def test_k6正常退出_秒级帧归一_perfstatus2(self, tmp_path, monkeypatch):
        import perf_worker
        monkeypatch.setenv("PATH", self._make_shim(tmp_path, 0))
        art = tmp_path / "PerfGenK6_1.js"
        art.write_text("// compiled", encoding="utf-8")
        o = perf_worker.run_perf_task({"taskId": 10, "perf": {
            "runId": 9, "engine": "K6", "artifact": str(art),
            "pressureSnapshot": "{}"}})
        assert o.result == "pass", o.error_detail
        assert o.perf_status == 2
        assert len(o.perf_frames) == 2
        f0, f1 = o.perf_frames
        assert f0["secondOffset"] == 0 and f0["rps"] == 2.0
        assert f0["p95Ms"] == 200.0 and f0["errorRate"] == 0.0
        assert f1["secondOffset"] == 1 and f1["rps"] == 1.0
        assert f1["p95Ms"] == 300.0 and f1["errorRate"] == 1.0
        assert "ttftMs" not in f0 and "itlMs" not in f0 and "tokensPerSec" not in f0
        assert o.perf_events == []

    def test_k6非零退出_fail事件摘要入流(self, tmp_path, monkeypatch):
        import perf_worker
        monkeypatch.setenv("PATH", self._make_shim(tmp_path, 99, "level=error msg=压测失败\n"))
        art = tmp_path / "PerfGenK6_2.js"
        art.write_text("// compiled", encoding="utf-8")
        o = perf_worker.run_perf_task({"taskId": 11, "perf": {
            "runId": 9, "engine": "K6", "artifact": str(art),
            "pressureSnapshot": "{}"}})
        assert o.result == "fail"
        assert o.error_type == 3
        assert o.perf_status is None
        assert o.perf_events, "非零退出必须带 engine-exit 事件摘要"
        kinds = {e["eventKind"] for e in o.perf_events}
        assert "engine-exit" in kinds
        for e in o.perf_events:
            assert len(e["detail"]) <= 4000

    def test_main落盘三键(self, tmp_path, monkeypatch):
        import perf_worker
        task_f = tmp_path / "task.json"
        out_f = tmp_path / "outcome.json"
        task_f.write_text(json.dumps({"taskId": 12}), encoding="utf-8")
        rc = perf_worker.main(["perf_worker.py", str(task_f), str(out_f)])
        assert rc == 0
        data = json.loads(out_f.read_text(encoding="utf-8"))
        assert data["result"] == "fail"
