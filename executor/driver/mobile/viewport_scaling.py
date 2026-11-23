"""Viewport resolution scaling for mobile coordinate steps (single source, rule R40).

Recorded app-auto scripts carry baseline-pixel coordinates (x/y step params and fixed
swipe offsets) produced against one device resolution. When the same script replays on
a device whose resolution differs, every coordinate channel must map through THIS one
transform: engine adapters (appium/maestro/minium/harmony...) consume it, none may
re-implement the decision.

Boundaries pinned by the segment contract (executor/tests/test_viewport_resolution_scaling.py):
- baseline comes from the env profile payload key ``resolutionBaseline`` (projected
 onto the envelope ``envProfile`` by core AppAutoDispatchSupport, free-form payload =
 zero migration needed);
- an illegal/missing-but-declared baseline is a script/profile defect: rejected by
 name, never silently treated as identity;
- an unreadable device resolution is an environment degradation: identity transform
 plus an honest note that belongs in the execution record (visible fallback);
- visual-fallback hit coordinates are NOT scaled here: they are produced from the live
 device frame and already match the device coordinate space.

``adb shell wm size`` live proof on the AVD: Physical size: 1080x2400, Override size
takes effect over Physical when present (the screen uses the override).
"""
from __future__ import annotations

import re
import subprocess

# Payload key name is part of the cross-language contract; keep in sync with the
# env profile seed/UI docs when the carrier changes.
BASELINE_PAYLOAD_KEY = "resolutionBaseline"

_RES_PATTERN = re.compile(r"^(\d+)\s*[xX]\s*(\d+)$")
_WM_SIZE_PATTERN = re.compile(r"(\d+)\s*[xX]\s*(\d+)")
_IDENTITY = {"scale_x": 1.0, "scale_y": 1.0, "identity": True, "note": None}


def parse_resolution(raw) -> tuple[int, int] | None:
    """Lenient parse of the accepted carrier shapes; None when unparsable.

    Accepted: "1080x2400" (case-insensitive, spaces tolerated), a (w, h) pair, or
    {"width": w, "height": h}. Zero/negative dims count as unparsable.
    """
    if raw is None:
        return None
    if isinstance(raw, (tuple, list)) and len(raw) == 2:
        width, height = raw
    elif isinstance(raw, dict):
        width, height = raw.get("width"), raw.get("height")
    else:
        match = _RES_PATTERN.match(str(raw).strip())
        if not match:
            return None
        width, height = match.group(1), match.group(2)
    if width is None or height is None:
        return None
    try:
        pair = (int(width), int(height))
    except (TypeError, ValueError):
        return None
    return pair if pair[0] > 0 and pair[1] > 0 else None


def parse_wm_size(text: str | None) -> tuple[int, int] | None:
    """Parse ``adb shell wm size`` output; the Override line wins over Physical.

    The override is what the display actually renders with, so it is the coordinate
    space live taps land in. Unparsable/garbage output returns None (degrade, no raise).
    """
    if not text:
        return None
    override = re.search(r"Override size:\s*" + _WM_SIZE_PATTERN.pattern, text)
    picked = override or re.search(r"Physical size:\s*" + _WM_SIZE_PATTERN.pattern, text) \
        or _WM_SIZE_PATTERN.search(text)
    if not picked:
        return None
    pair = (int(picked.group(1)), int(picked.group(2)))
    return pair if pair[0] > 0 and pair[1] > 0 else None


def _default_adb_runner(argv: list[str]) -> str:
    completed = subprocess.run(
        argv, capture_output=True, text=True, timeout=10, check=False
    )
    return completed.stdout or ""


def read_device_resolution(serial: str | None, *, runner=None) -> tuple[int, int] | None:
    """Read the live device resolution via ``adb -s <serial> shell wm size``.

    Strict argv form (pit: device-plane commands never take shell strings). Any failure
    (missing adb, offline device, timeout) degrades to None so the caller records an
    honest identity fallback instead of exploding the step pipeline.
    """
    if not serial:
        return None
    run = runner or _default_adb_runner
    try:
        return parse_wm_size(run(["adb", "-s", str(serial), "shell", "wm", "size"]))
    except Exception:  # noqa: BLE001 - degrade to None by contract, environment reds
        # surface through the transform note, not a crashed worker step.
        return None


def make_transform(baseline, device) -> dict:
    """Compose the scaling transform from baseline + live device resolutions.

    baseline: raw carrier value (string/pair/dict) from envProfile[resolutionBaseline].
    device: live resolution tuple or None when unreadable.
    Returns a transform dict consumed by scale_point/scale_delta.
    """
    base = parse_resolution(baseline)
    if base is None:
        raise ValueError(
            f"变体缩放基线不合法（环境档案键 {BASELINE_PAYLOAD_KEY}，"
            f"实际值={baseline!r}）：应为 “宽x高” 正整数，如 1080x2400——"
            "请修正应用自动化环境档案 payload 后重跑"
        )
    if device is None or device[0] <= 0 or device[1] <= 0:
        # Honest fallback: identity mapping with a note that must reach the record.
        return dict(_IDENTITY, note=(
            "device 分辨率未读到（adb shell wm size 无有效输出），"
            "坐标按基线原样执行——跨分辨率变体账未生效"
        ))
    return {
        "scale_x": device[0] / base[0],
        "scale_y": device[1] / base[1],
        "identity": device == base,
        "note": None,
    }


def scale_point(transform: dict, x, y) -> tuple[int, int]:
    """Map one absolute coordinate through the transform (rounded to whole pixels)."""
    return (_round_px(x * transform["scale_x"]), _round_px(y * transform["scale_y"]))


def scale_delta(transform: dict, dx, dy) -> tuple[int, int]:
    """Map a swipe/scroller offset (vector between two points, both axes scaled)."""
    return (_round_px(dx * transform["scale_x"]), _round_px(dy * transform["scale_y"]))


def _round_px(value: float) -> int:
    """Round half up: deterministic pixel mapping (banker's rounding flips .5 cases)."""
    return int(value + 0.5) if value >= 0 else -int(-value + 0.5)
