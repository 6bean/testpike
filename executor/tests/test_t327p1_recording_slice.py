
from __future__ import annotations

import json
import os
import pathlib
import shutil
import subprocess
import sys
import time

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from PIL import Image

import app_worker
from driver.mobile import monitor_slice

FFMPEG = shutil.which("ffmpeg")


def _jpeg_b64(color=(20, 120, 200), size=(640, 480)) -> str:
    pass
    import base64
    import io
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, format="JPEG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def _write_sink(path: pathlib.Path, frames: list[dict]) -> None:
    with open(path, "w", encoding="utf-8") as f:
        f.writelines(json.dumps(fr, ensure_ascii=False) + "\n" for fr in frames)


def _frame(step_no: int, ts: float, sensitive: bool = False) -> dict:
    return {"taskId": 900, "stepNo": step_no, "image": _jpeg_b64(),
            "sensitive": sensitive, "ts": ts}



def test_monitor_frame_row_carries_ts_for_window_slicing(tmp_path):
    pass
    sink = tmp_path / "frames.jsonl"

    class Watch:
        def prepare(self, ctx):
            pass

        def execute(self, step):
            return {"result": "pass"}

        def watch_frame(self, artifact_dir, max_width=None):
            return {"image": "aGk="}

        def teardown(self):
            pass

    task = {"taskId": 901, "_adapterOverride": Watch(),
            "_monitorFrameSink": str(sink),
            "actionJson": json.dumps({"kind": "app-auto-run", "runId": 1,
                                      "steps": [{"action": "app.tap"}]})}
    outcome = app_worker.run_app_task(task)
    assert outcome.result == "pass"
    lines = [json.loads(x) for x in
             sink.read_text(encoding="utf-8").splitlines() if x.strip()]
    assert len(lines) == 1
    row = lines[0]
    assert set(row) == {"taskId", "stepNo", "image", "sensitive", "ts"}
    assert isinstance(row["ts"], float) and row["ts"] > 0



def test_probe_missing_ffmpeg_returns_none(tmp_path, monkeypatch):
    monkeypatch.setattr(shutil, "which", lambda name: None)
    assert monitor_slice.probe_ffmpeg() is None


def test_build_recording_honest_skip_without_ffmpeg(tmp_path, monkeypatch):
    pass
    monkeypatch.setattr(monitor_slice, "probe_ffmpeg", lambda: None)
    sink = tmp_path / "frames.jsonl"
    now = time.time()
    _write_sink(sink, [_frame(i, now - i) for i in range(1, 5)])
    assert monitor_slice.build_failure_recording(
        str(sink), 4, str(tmp_path)) is None



@pytest.mark.skipif(FFMPEG is None, reason="环境件 ffmpeg 未装机（宿主缺位诚实标注）")
def test_slice_window_excludes_frames_older_than_window(tmp_path):
    pass
    now = time.time()
    offsets = [45.0, 40.0, 20.0, 10.0, 5.0]
    frames = [_frame(i + 1, now - off) for i, off in enumerate(offsets)]
    kept = monitor_slice.slice_recent_frames(frames, fail_ts=now, window=30.0)
    assert [f["stepNo"] for f in kept] == [3, 4, 5]


@pytest.mark.skipif(FFMPEG is None, reason="环境件 ffmpeg 未装机（宿主缺位诚实标注）")
def test_fewer_than_two_frames_honest_no_recording(tmp_path):
    pass
    sink = tmp_path / "frames.jsonl"
    now = time.time()
    _write_sink(sink, [_frame(1, now)])
    assert monitor_slice.build_failure_recording(
        str(sink), 1, str(tmp_path)) is None


@pytest.mark.skipif(FFMPEG is None, reason="环境件 ffmpeg 未装机（宿主缺位诚实标注）")
def test_build_recording_produces_real_mp4(tmp_path):
    pass
    sink = tmp_path / "frames.jsonl"
    now = time.time()
    _write_sink(sink, [_frame(i, now - 3 + i, sensitive=(i == 2))
                       for i in range(1, 5)])
    art = monitor_slice.build_failure_recording(str(sink), 4, str(tmp_path))
    assert art is not None
    assert art["kind"] == "recording"
    assert art["localPath"].endswith(".mp4")
    assert os.path.getsize(art["localPath"]) > 0
    assert art["contentType"] == "video/mp4"
    assert not os.path.exists(os.path.join(str(tmp_path), "rec-frames"))


@pytest.mark.skipif(FFMPEG is None, reason="环境件 ffmpeg 未装机（宿主缺位诚实标注）")
def test_mp4_probe_has_video_stream(tmp_path):
    pass
    sink = tmp_path / "frames.jsonl"
    now = time.time()
    _write_sink(sink, [_frame(i, now - 2 + i * 0.5) for i in range(1, 5)])
    art = monitor_slice.build_failure_recording(str(sink), 4, str(tmp_path))
    out = subprocess.run([FFMPEG.replace("ffmpeg", "ffprobe"), "-v", "error",
                          "-select_streams", "v:0", "-show_entries",
                          "stream=codec_type", "-of", "json", art["localPath"]],
                         capture_output=True, text=True, timeout=30, check=False)
    assert out.returncode == 0
    assert json.loads(out.stdout)["streams"][0]["codec_type"] == "video"



class _DriveWatch:
    def __init__(self, fail_at):
        self.fail_at = fail_at
        self.n = 0

    def prepare(self, ctx):
        pass

    def execute(self, step):
        self.n += 1
        if self.n == self.fail_at:
            return {"result": "fail", "errorType": 1, "detail": "控件没找到"}
        return {"result": "pass"}

    def watch_frame(self, artifact_dir, max_width=None):
        return {"image": _jpeg_b64()}

    def collect(self, artifact):
        return None

    def teardown(self):
        pass


@pytest.mark.skipif(FFMPEG is None, reason="环境件 ffmpeg 未装机（宿主缺位诚实标注）")
def test_failed_step_registers_recording_slot_in_artifacts(tmp_path, monkeypatch):
    pass
    monkeypatch.setattr(app_worker, "_return_lease", lambda *a: None)
    sink = tmp_path / "frames.jsonl"
    task = {"taskId": 902, "_adapterOverride": _DriveWatch(3),
            "_monitorFrameSink": str(sink),
            "actionJson": json.dumps({"kind": "app-auto-run", "runId": 2,
                                      "steps": [{"action": "app.tap"}] * 5})}
    outcome = app_worker.run_app_task(task)
    assert outcome.result == "fail"
    rec = [x for x in outcome.pending_artifacts if x.get("kind") == "recording"]
    assert len(rec) == 1
    item = rec[0]
    assert item["slot"] == "record" and item["stepNo"] == 3
    assert item["contentType"] == "video/mp4"
    assert os.path.exists(item["localPath"])
    assert "localPath" not in json.dumps(outcome.steps_timeline,
                                         ensure_ascii=False)


def test_pass_task_registers_no_recording(tmp_path, monkeypatch):
    pass
    monkeypatch.setattr(app_worker, "_return_lease", lambda *a: None)
    sink = tmp_path / "frames.jsonl"
    task = {"taskId": 903, "_adapterOverride": _DriveWatch(99),
            "_monitorFrameSink": str(sink),
            "actionJson": json.dumps({"kind": "app-auto-run", "runId": 3,
                                      "steps": [{"action": "app.tap"}] * 2})}
    outcome = app_worker.run_app_task(task)
    assert outcome.result == "pass"
    assert not [x for x in outcome.pending_artifacts
                if x.get("kind") == "recording"]


def test_evidence_slots_and_content_types_extended():
    pass
    assert app_worker._EVIDENCE_SLOTS["recording"] == "record"
    assert app_worker._ARTIFACT_DEFAULT_CONTENT_TYPES["recording"] == "video/mp4"
