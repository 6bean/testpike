
from __future__ import annotations

import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from driver.mobile.takeover_input_contract import (
    TakeoverInputError,
    plan_input_argv,
    validate_takeover_command,
)


def _tap(x=100, y=200, platform="ANDROID", serial=None):
    cmd = {"platform": platform, "verb": "tap", "x": x, "y": y}
    if serial is not None:
        cmd["serial"] = serial
    return cmd



def test_validate_accepts_tap():
    assert validate_takeover_command(_tap()) is None


def test_validate_rejects_unknown_verb():
    err = validate_takeover_command({"platform": "ANDROID", "verb": "shake", "x": 1, "y": 2})
    assert err is not None and "shake" in err


def test_validate_rejects_missing_coords():
    assert validate_takeover_command({"platform": "ANDROID", "verb": "tap"}) is not None


def test_validate_rejects_coord_out_of_bounds():
    assert validate_takeover_command(_tap(x=99999)) is not None
    assert validate_takeover_command(_tap(y=-5)) is not None


def test_validate_rejects_non_int_coords():
    assert validate_takeover_command(_tap(x="100")) is not None


def test_validate_swipe_needs_four_coords():
    assert validate_takeover_command({"platform": "ANDROID", "verb": "swipe",
                                      "x1": 10, "y1": 20, "x2": 30}) is not None
    assert validate_takeover_command({"platform": "ANDROID", "verb": "swipe",
                                      "x1": 10, "y1": 20, "x2": 30, "y2": 40}) is None


def test_validate_key_code_domain():
    assert validate_takeover_command({"platform": "ANDROID", "verb": "key",
                                      "keycode": 66}) is None
    assert validate_takeover_command({"platform": "ANDROID", "verb": "key",
                                      "keycode": -1}) is not None
    assert validate_takeover_command({"platform": "ANDROID", "verb": "key",
                                      "keycode": 256}) is not None


def test_validate_text_length_domain():
    assert validate_takeover_command({"platform": "ANDROID", "verb": "text",
                                      "text": "hello 世界"}) is None
    assert validate_takeover_command({"platform": "ANDROID", "verb": "text",
                                      "text": ""}) is not None
    assert validate_takeover_command({"platform": "ANDROID", "verb": "text",
                                      "text": "x" * 129}) is not None


def test_validate_rejects_missing_platform():
    assert validate_takeover_command({"verb": "tap", "x": 1, "y": 2}) is not None



def test_adb_tap_argv():
    assert plan_input_argv(_tap(serial="emulator-5554")) == [
        "adb", "-s", "emulator-5554", "shell", "input", "tap", "100", "200"]


def test_adb_tap_argv_without_serial():
    assert plan_input_argv(_tap()) == [
        "adb", "shell", "input", "tap", "100", "200"]


def test_adb_swipe_argv():
    cmd = {"platform": "ANDROID", "verb": "swipe",
           "x1": 10, "y1": 20, "x2": 30, "y2": 40}
    assert plan_input_argv(cmd)[-4:] == ["10", "20", "30", "40"]


def test_adb_key_argv():
    cmd = {"platform": "ANDROID", "verb": "key", "keycode": 4}
    argv = plan_input_argv(cmd)
    assert argv[-3:] == ["input", "keyevent", "4"]


def test_adb_text_spaces_normalized_to_percent_s():
    cmd = {"platform": "ANDROID", "verb": "text", "text": "hi there"}
    argv = plan_input_argv(cmd)
    assert argv[-1] == "hi%sthere"


def test_hdc_tap_uses_uitest_uiinput():
    cmd = _tap(platform="HARMONY_NEXT", serial="dev-1")
    argv = plan_input_argv(cmd)
    assert argv[:4] == ["hdc", "-t", "dev-1", "shell"]
    assert argv[4:] == ["uitest", "uiInput", "click", "100", "200"]


def test_hdc_swipe_uses_uitest_uiinput():
    cmd = {"platform": "HARMONY_NEXT", "verb": "swipe",
           "x1": 10, "y1": 20, "x2": 30, "y2": 40}
    assert plan_input_argv(cmd)[-5:] == ["swipe", "10", "20", "30", "40"]


def test_hdc_key_and_text_honestly_unsupported():
    for cmd in ({"platform": "HARMONY_NEXT", "verb": "key", "keycode": 4},
                {"platform": "HARMONY_NEXT", "verb": "text", "text": "a"}):
        try:
            plan_input_argv(cmd)
            raise AssertionError("鸿蒙 key/text 无稳定映射，必须诚实点名不支持")
        except TakeoverInputError as e:
            assert "不支持" in str(e)


def test_no_injection_platforms_rejected():
    for platform in ("MP", "IOS", "QUICKAPP"):
        try:
            plan_input_argv(_tap(platform=platform))
            raise AssertionError(f"{platform} 无注入面必须人话拒")
        except TakeoverInputError as e:
            assert platform in str(e)


def test_plan_rejects_invalid_before_argv():
    import pytest
    with pytest.raises(TakeoverInputError):
        plan_input_argv(_tap(x=99999))
