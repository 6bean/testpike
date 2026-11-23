
from __future__ import annotations

import base64
import json
import logging
import os
import shutil
import subprocess
import time

log = logging.getLogger("testpike-executor")

DEFAULT_REC_WINDOW_SECONDS = 30.0
_FRAME_MIN_DWELL_SECONDS = 0.4


def rec_window_seconds() -> float:
    pass
    raw = os.environ.get("EXECUTOR_REC_WINDOW_SECONDS", "")
    try:
        val = float(raw)
    except ValueError:
        return DEFAULT_REC_WINDOW_SECONDS
    return val if val > 0 else DEFAULT_REC_WINDOW_SECONDS


def probe_ffmpeg() -> str | None:
    pass
    return shutil.which("ffmpeg")


def load_frames(sink_path: str) -> list[dict]:
    pass
    frames: list[dict] = []
    try:
        with open(sink_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    frame = json.loads(line)
                except ValueError:
                    continue
                if isinstance(frame, dict) and frame.get("image"):
                    frames.append(frame)
    except OSError:
        return []
    return frames


def slice_recent_frames(frames: list[dict], fail_ts: float,
                        window: float) -> list[dict]:
    pass
    kept = []
    for frame in frames:
        ts = frame.get("ts")
        if not isinstance(ts, (int, float)):
            continue
        if fail_ts - float(ts) <= window:
            kept.append(frame)
    return kept


def _decode_frame_image(image_b64: str, out_dir: str, seq: int) -> str | None:
    pass
    try:
        raw = base64.b64decode(image_b64)
    except Exception:
        return None
    path = os.path.join(out_dir, f"frame-{seq:03d}.jpg")
    try:
        with open(path, "wb") as f:
            f.write(raw)
    except OSError:
        return None
    return path


def encode_frames_to_mp4(frame_paths: list[str], out_path: str,
                         dwell_seconds: float) -> bool:
    pass
    ffmpeg = probe_ffmpeg()
    if ffmpeg is None or not frame_paths:
        return False
    cmd = [ffmpeg, "-y", "-v", "error",
           "-framerate", f"{max(1.0 / dwell_seconds, 0.1):.4f}",
           "-i", frame_paths[0].rsplit("frame-", 1)[0] + "frame-%03d.jpg",
           "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
           "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
           out_path]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True,
                             timeout=120, check=False)
    except Exception as e:
        log.warning("录屏切片编码异常（诚实无第四联件）: %s", e)
        return False
    if res.returncode != 0 or not os.path.exists(out_path):
        log.warning("录屏切片编码失败 rc=%s: %s",
                    res.returncode, (res.stderr or "")[:200])
        return False
    return True


def build_failure_recording(sink_path: str | None, fail_step_no: int,
                            art_dir: str,
                            fail_ts: float | None = None,
                            window: float | None = None) -> dict | None:
    pass
    if not sink_path or probe_ffmpeg() is None:
        return None
    frames = load_frames(sink_path)
    if not frames:
        return None
    kept = slice_recent_frames(frames, fail_ts=fail_ts or time.time(),
                               window=window if window is not None
                               else rec_window_seconds())
    if len(kept) < 2:
        return None
    moment = fail_ts if fail_ts is not None else time.time()
    seq_dir = os.path.join(art_dir, "rec-frames")
    mp4 = os.path.join(art_dir, f"step-{fail_step_no}-recording.mp4")
    try:
        os.makedirs(seq_dir, exist_ok=True)
        paths: list[str] = []
        for frame in kept:
            path = _decode_frame_image(str(frame["image"]), seq_dir,
                                       len(paths) + 1)
            if path:
                paths.append(path)
        if len(paths) < 2:
            return None
        first_ts = float(kept[0].get("ts") or moment)
        span = max(1e-9, moment - first_ts)
        dwell = max(_FRAME_MIN_DWELL_SECONDS, min(span, 60.0) / len(paths))
        if not encode_frames_to_mp4(paths, mp4, dwell):
            return None
    finally:
        shutil.rmtree(seq_dir, ignore_errors=True)
    return {"kind": "recording", "localPath": mp4,
            "fileName": os.path.basename(mp4), "contentType": "video/mp4"}
