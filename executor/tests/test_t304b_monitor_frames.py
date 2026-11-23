
from __future__ import annotations

import base64
import io
import json
import pathlib
import sys
import tempfile

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from PIL import Image

from driver.mobile.contracts import (
    downsample_to_frame,
    monitor_frame_from_artifacts,
    step_references_credential,
)
from driver.mobile.mobilewright_adapter import MobilewrightAdapter
from driver.mobile.stub_adapter import StubEngineAdapter


def _png_file(tmp_path: pathlib.Path, size=(800, 600)) -> str:
    img = Image.new("RGB", size, (10, 120, 200))
    f = tmp_path / "shot.png"
    img.save(f)
    return str(f)



def test_downsample_frame_returns_jpeg_base64_within_width(tmp_path):
    pass
    b64 = downsample_to_frame(_png_file(tmp_path), max_width=480)
    assert b64
    raw = base64.b64decode(b64)
    img = Image.open(io.BytesIO(raw))
    assert img.format == "JPEG"
    assert img.width <= 480


def test_downsample_frame_honest_none_on_bad_input(tmp_path):
    pass
    assert downsample_to_frame(str(tmp_path / "nope.png"), max_width=480) is None
    junk = tmp_path / "junk.png"
    junk.write_bytes(b"not-an-image")
    assert downsample_to_frame(str(junk), max_width=480) is None


def test_monitor_frame_from_artifacts_picks_screenshot_only():
    pass
    with tempfile.TemporaryDirectory() as td:
        shot = _png_file(pathlib.Path(td))
        items = [{"kind": "dom_snapshot", "localPath": shot},
                 {"kind": "screenshot", "localPath": shot}]
        frame = monitor_frame_from_artifacts(items, max_width=480)
        assert frame and frame["image"]
        assert monitor_frame_from_artifacts(
            [{"kind": "dom_snapshot", "localPath": shot}], max_width=480) is None
        assert monitor_frame_from_artifacts(None, max_width=480) is None
        assert monitor_frame_from_artifacts(
            {"kind": "screenshot", "localPath": shot}, max_width=480) is not None


def test_step_references_credential_marks_cred_steps():
    pass
    assert step_references_credential(
        {"action": "app.input", "text": "密码=${CRED:pwd}"}) is True
    assert step_references_credential({"action": "app.tap", "target": "登录"}) is False
    assert step_references_credential(None) is False



class _FrameBridge:
    pass

    def __init__(self, png_path: str):
        self.png = png_path
        self.extra_env = {}

    def start(self): ...
    def alive(self): return True
    def stop(self): ...

    def call(self, method, params=None):
        if method == "collect":
            return {"artifacts": [{"kind": "screenshot", "localPath": self.png}]}
        return {"result": "pass"}


def test_mobilewright_watch_frame_downsamples_collect_screenshot(tmp_path):
    adapter = MobilewrightAdapter(bridge=_FrameBridge(_png_file(tmp_path)))
    adapter._prepared = True
    frame = adapter.watch_frame(str(tmp_path), max_width=480)
    assert frame and frame["image"]
    raw = base64.b64decode(frame["image"])
    assert Image.open(io.BytesIO(raw)).width <= 480


def test_watch_frame_honest_absent_when_not_prepared(tmp_path):
    pass
    adapter = MobilewrightAdapter(bridge=_FrameBridge(_png_file(tmp_path)))
    assert adapter.watch_frame(str(tmp_path)) is None


def test_stub_adapter_has_no_watch_frame_hook():
    pass
    assert not hasattr(StubEngineAdapter(), "watch_frame")



class _WatchAdapter:
    def __init__(self):
        self.frames_emitted = 0

    def prepare(self, ctx): ...
    def execute(self, step): return {"result": "pass"}
    def collect(self, artifact): return None
    def teardown(self): ...

    def watch_frame(self, artifact_dir, max_width=None):
        self.frames_emitted += 1
        return {"image": "aGk="}


def _watch_only_adapter():
    return _WatchAdapter()


def test_app_worker_writes_frames_to_sink_with_sensitive_flag():
    pass
    import app_worker

    with tempfile.TemporaryDirectory() as td:
        sink = pathlib.Path(td) / "monitor-frames.jsonl"
        adapter = _watch_only_adapter()
        task = {
            "taskId": 777,
            "_adapterOverride": adapter,
            "_monitorFrameSink": str(sink),
            "actionJson": json.dumps({
                "kind": "app-auto-run", "runId": 1,
                "steps": [
                    {"action": "app.tap", "target": "登录"},
                    {"action": "app.input", "text": "${CRED:pwd}"},
                ]}),
        }
        outcome = app_worker.run_app_task(task)
        assert outcome.result == "pass"
        lines = [json.loads(x) for x in sink.read_text(encoding="utf-8").splitlines() if x.strip()]
        assert len(lines) == 2
        assert {k: lines[0][k] for k in ("taskId", "stepNo", "image", "sensitive")} == \
            {"taskId": 777, "stepNo": 1, "image": "aGk=", "sensitive": False}
        assert isinstance(lines[0]["ts"], float) and lines[0]["ts"] > 0
        assert lines[1]["stepNo"] == 2 and lines[1]["sensitive"] is True
        assert adapter.frames_emitted == 2


def test_app_worker_watch_frame_failure_never_breaks_steps():
    pass
    import app_worker

    class Boom(_WatchAdapter):
        def watch_frame(self, artifact_dir, max_width=None):
            raise RuntimeError("引擎帧采集炸")

    with tempfile.TemporaryDirectory() as td:
        sink = pathlib.Path(td) / "monitor-frames.jsonl"
        task = {"taskId": 778, "_adapterOverride": Boom(), "_monitorFrameSink": str(sink),
                "actionJson": json.dumps({"kind": "app-auto-run", "runId": 1,
                                          "steps": [{"action": "app.tap"}]})}
        outcome = app_worker.run_app_task(task)
        assert outcome.result == "pass"
        assert not sink.exists() or not sink.read_text().strip()


def test_app_worker_no_sink_no_frames(tmp_path):
    pass
    import app_worker
    task = {"taskId": 779, "_adapterOverride": _watch_only_adapter(),
            "actionJson": json.dumps({"kind": "app-auto-run", "runId": 1,
                                      "steps": [{"action": "app.tap"}]})}
    assert app_worker.run_app_task(task).result == "pass"



def test_drain_monitor_frames_incremental_with_offset_and_cap():
    import runner

    with tempfile.TemporaryDirectory() as td:
        sink = pathlib.Path(td) / "frames.jsonl"
        task_id = 9001
        try:
            runner._MONITOR_SINKS[task_id] = (str(sink), 0)
            with open(sink, "w", encoding="utf-8") as f:
                f.write(json.dumps({"taskId": task_id, "stepNo": 1, "image": "aGk="}) + "\n")
            frames = runner.drain_monitor_frames()
            assert len(frames) == 1 and frames[0]["stepNo"] == 1
            assert runner.drain_monitor_frames() == []
            with open(sink, "a", encoding="utf-8") as f:
                f.write("坏行非JSON\n")
                f.write(json.dumps({"taskId": task_id, "stepNo": 2, "image": "aGk="}) + "\n")
            frames = runner.drain_monitor_frames()
            assert [x["stepNo"] for x in frames] == [2]
        finally:
            runner._MONITOR_SINKS.pop(task_id, None)


def test_drain_monitor_frames_caps_keeping_newest():
    pass
    import runner

    with tempfile.TemporaryDirectory() as td:
        sink = pathlib.Path(td) / "frames.jsonl"
        task_id = 9002
        try:
            runner._MONITOR_SINKS[task_id] = (str(sink), 0)
            with open(sink, "w", encoding="utf-8") as f:
                f.writelines(
                    json.dumps({"taskId": task_id, "stepNo": i, "image": "aGk="}) + "\n"
                    for i in range(25))
            frames = runner.drain_monitor_frames()
            assert len(frames) == runner.MONITOR_DRAIN_MAX_FRAMES
            assert frames[-1]["stepNo"] == 24
        finally:
            runner._MONITOR_SINKS.pop(task_id, None)


def test_requeue_monitor_frames_prepend_on_heartbeat_failure():
    import runner
    try:
        runner._MONITOR_REQUEUE.clear()
        runner.requeue_monitor_frames([{"stepNo": 2}])
        runner._MONITOR_SINKS.pop(9003, None)
        frames = runner.drain_monitor_frames()
        assert frames and frames[0]["stepNo"] == 2
        assert runner.drain_monitor_frames() == []
    finally:
        runner._MONITOR_REQUEUE.clear()


def test_monitor_session_active_and_heartbeat_interval_bounds():
    pass
    import runner
    from config import ExecutorConfig

    assert runner.monitor_session_active() is False
    try:
        runner._MONITOR_SINKS[9004] = ("/tmp/none.jsonl", 0)
        assert runner.monitor_session_active() is True
    finally:
        runner._MONITOR_SINKS.pop(9004, None)
    cfg = ExecutorConfig()
    assert 0 < cfg.monitor_heartbeat_interval < 30
    assert cfg.monitor_heartbeat_interval <= cfg.heartbeat_interval


def test_send_heartbeat_carries_drained_frames_and_requeues_on_failure():
    pass
    import runner

    class RecClient:
        def __init__(self):
            self.calls = []

        def heartbeat(self, running_tasks, monitor_frames=None, components=None,
                      net_proxy_audits=None, perf_reports=None, devices=None,
                      device_project_scope=None, perf_partial=None):
            self.calls.append((running_tasks, monitor_frames))
            if getattr(self, "boom", False):
                raise runner.ProtocolError("心跳炸")
            return {}

    try:
        runner._MONITOR_REQUEUE.clear()
        with tempfile.TemporaryDirectory() as td:
            sink = pathlib.Path(td) / "frames.jsonl"
            runner._MONITOR_SINKS[9005] = (str(sink), 0)
            with open(sink, "w", encoding="utf-8") as f:
                f.write(json.dumps({"taskId": 9005, "stepNo": 1, "image": "aGk="}) + "\n")
            client = RecClient()
            resp = runner.send_heartbeat(client, running_tasks=1)
            assert resp == {}
            assert client.calls[0] == (1, [{"taskId": 9005, "stepNo": 1, "image": "aGk="}])
            assert runner.drain_monitor_frames() == []
            client.boom = True
            with open(sink, "a", encoding="utf-8") as f:
                f.write(json.dumps({"taskId": 9005, "stepNo": 2, "image": "aGk="}) + "\n")
            with pytest.raises(runner.ProtocolError):
                runner.send_heartbeat(client, running_tasks=1)
            client.boom = False
            runner.send_heartbeat(client, running_tasks=0)
            carried = client.calls[-1][1]
            assert carried == [{"taskId": 9005, "stepNo": 2, "image": "aGk="}]
    finally:
        runner._MONITOR_SINKS.pop(9005, None)
        runner._MONITOR_REQUEUE.clear()
