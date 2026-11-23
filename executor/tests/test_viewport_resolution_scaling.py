"""Viewport resolution scaling contract for mobile coordinate steps .

Why this module exists: recorded scripts carry baseline-pixel coordinates produced
against one device resolution; replaying them on a device with a different resolution
must map each coordinate through a single shared transform (rule R40: one source for
the scaling decision, every engine adapter consumes it, none may re-implement it).

Contract pinned here (per adjudicated segment plan):
- baseline x device -> proportional point mapping with rounded integer pixels;
- unreadable/zero-width device -> identity transform plus an honest note (fallback is
 visible in the record, never silent);
- illegal baseline -> rejected by name (script/profile defect must not be masked);
- wm size parsing prefers the override line (that is what the screen actually uses);
- device read failures degrade to None, they never raise into the step pipeline.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest

from driver.mobile.viewport_scaling import (
    make_transform,
    parse_resolution,
    parse_wm_size,
    read_device_resolution,
    scale_delta,
    scale_point,
)

# ── parse_resolution: accepted carrier shapes ───────────────────────────────


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("1080x2400", (1080, 2400)),
        ("1080X2400", (1080, 2400)),
        (" 1080 x 2400 ", (1080, 2400)),
        ((1080, 2400), (1080, 2400)),
        ({"width": 1080, "height": 2400}, (1080, 2400)),
        ({"width": "1080", "height": "2400"}, (1080, 2400)),
    ],
)
def test_parse_resolution_accepts_documented_shapes(raw, expected):
    assert parse_resolution(raw) == expected


@pytest.mark.parametrize(
    "raw",
    [None, "", "abc", "0x0", "-5x10", "1080", {"width": 0, "height": 100}, (720, 0)],
)
def test_parse_resolution_rejects_garbage_as_none(raw):
    # Parser layer stays lenient (returns None); the rejection decision belongs to
    # make_transform so callers can tell "no baseline configured" from "illegal".
    assert parse_resolution(raw) is None


# ── make_transform: mapping, identity, honest fallback, rejection ───────────


def test_transform_maps_point_proportionally_with_rounded_pixels():
    t = make_transform((1080, 2400), (720, 1280))
    assert scale_point(t, 540, 1200) == (360, 640)
    assert not t["identity"]


def test_transform_is_identity_when_sizes_equal():
    t = make_transform((1080, 2400), (1080, 2400))
    assert t["identity"] is True
    assert scale_point(t, 123, 456) == (123, 456)


@pytest.mark.parametrize("device", [None, (0, 0), (0, 1920)])
def test_unreadable_or_zero_width_device_falls_back_to_identity_with_note(device):
    t = make_transform((1080, 2400), device)
    assert t["identity"] is True
    assert scale_point(t, 540, 1200) == (540, 1200)
    # The honest-record requirement: a note naming why scaling was skipped.
    assert t["note"] and "device" in t["note"]


@pytest.mark.parametrize("baseline", [None, (0, 2400), (1080, -1), "abc"])
def test_illegal_baseline_is_rejected_by_name(baseline):
    with pytest.raises(ValueError) as exc:
        make_transform(baseline, (720, 1280))
    message = str(exc.value)
    # Rejection must point at the carrier (env profile payload key) and the value.
    assert "resolutionBaseline" in message


def test_legal_baseline_string_carrier_is_accepted_through_payload_shape():
    # envProfile keys arrive as the projected payload values, commonly strings.
    t = make_transform("1080x2400", (720, 1280))
    assert scale_point(t, 1080, 2400) == (720, 1280)


# ── scale_delta: swipe/scroller offsets are vectors, not absolute points ─────


def test_scale_delta_scales_both_axes_independently():
    t = make_transform((1080, 2400), (540, 1200))
    assert scale_delta(t, 300, 600) == (150, 300)


# ── wm size text parsing (adb shell wm size live shape) ─────────────────────


def test_parse_wm_size_prefers_override_line():
    text = "Physical size: 1080x2400\nOverride size: 720x1280\n"
    assert parse_wm_size(text) == (720, 1280)


def test_parse_wm_size_uses_physical_when_no_override():
    assert parse_wm_size("Physical size: 1080x2400\n") == (1080, 2400)


@pytest.mark.parametrize("text", ["", "error: no devices", "size: ??"])
def test_parse_wm_size_returns_none_for_unparsable_output(text):
    assert parse_wm_size(text) is None


# ── device read channel: argv-strict executor injection (pit: never shell-string) ─


def test_read_device_resolution_uses_injected_runner_argv_form():
    calls = []

    def runner(argv):
        calls.append(argv)
        return "Physical size: 1080x2400\n"

    assert read_device_resolution("emulator-5554", runner=runner) == (1080, 2400)
    # Exact argv shape: adb -s <serial> shell wm size, list form, no shell string.
    assert calls[0] == ["adb", "-s", "emulator-5554", "shell", "wm", "size"]


def test_read_device_resolution_degrades_to_none_on_runner_failure():
    def runner(argv):
        raise OSError("adb not found")

    assert read_device_resolution("emulator-5554", runner=runner) is None


def test_transform_from_profile_and_device_reads_compose_end_to_end():
    profile = {"resolutionBaseline": "1080x2400"}
    device = read_device_resolution(
        "x", runner=lambda argv: "Physical size: 720x1280\n"
    )
    t = make_transform(profile.get("resolutionBaseline"), device)
    assert scale_point(t, 540, 1200) == (360, 640)
