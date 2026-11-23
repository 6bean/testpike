
from __future__ import annotations

import json
import pathlib
import stat
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from driver.mobile.android_event_stream import (  # noqa: E402
    parse_getevent_lines,
    start_input_stream,
)

_TAP_STREAM = [
    "dev input1: EV_ABS       ABS_MT_TRACKING_ID   00000009",
    "dev input1: EV_KEY       BTN_TOUCH            DOWN",
    "dev input1: EV_ABS       ABS_MT_POSITION_X    00000064",
    "dev input1: EV_ABS       ABS_MT_POSITION_Y    000000c8",
    "dev input1: EV_SYN       SYN_REPORT           00000000",
    "dev input1: EV_ABS       ABS_MT_TRACKING_ID   ffffffff",
    "dev input1: EV_KEY       BTN_TOUCH            UP",
    "dev input1: EV_SYN       SYN_REPORT           00000000",
]

_SWIPE_STREAM = [
    "dev input1: EV_KEY       BTN_TOUCH            DOWN",
    "dev input1: EV_ABS       ABS_MT_POSITION_X    00000064",
    "dev input1: EV_ABS       ABS_MT_POSITION_Y    00000320",
    "dev input1: EV_SYN       SYN_REPORT           00000000",
    "dev input1: EV_ABS       ABS_MT_POSITION_X    00000064",
    "dev input1: EV_ABS       ABS_MT_POSITION_Y    00000190",
    "dev input1: EV_SYN       SYN_REPORT           00000000",
    "dev input1: EV_ABS       ABS_MT_POSITION_X    00000064",
    "dev input1: EV_ABS       ABS_MT_POSITION_Y    00000064",
    "dev input1: EV_SYN       SYN_REPORT           00000000",
    "dev input1: EV_KEY       BTN_TOUCH            UP",
    "dev input1: EV_SYN       SYN_REPORT           00000000",
]


def test_parse_tap_produces_coord_event_row():
    events = parse_getevent_lines(_TAP_STREAM)
    assert [e["kind"] for e in events] == ["tap"]
    assert "100" in events[0]["detail"] and "200" in events[0]["detail"]


def test_parse_swipe_direction_is_finger_motion_semantics():
    pass
    events = parse_getevent_lines(_SWIPE_STREAM)
    assert [e["kind"] for e in events] == ["swipe"]
    assert events[0]["target"] == "up"


def test_parse_hw_keys_map_to_semantic_kinds():
    events = parse_getevent_lines([
        "dev input0: EV_KEY       KEY_BACK             DOWN",
        "dev input0: EV_KEY       KEY_BACK             UP",
        "dev input2: EV_KEY       KEY_HOME             DOWN",
        "dev input2: EV_KEY       KEY_HOME             UP",
        "dev input0: EV_KEY       KEY_VOLUMEUP         DOWN",
        "dev input0: EV_KEY       KEY_VOLUMEUP         UP",
    ])
    assert [e["kind"] for e in events] == ["back", "home", "key"]
    assert events[2]["detail"] == "KEY_VOLUMEUP"


def test_parse_malformed_lines_isolated_not_crash():
    pass
    events = parse_getevent_lines([
        "garbage not a dev line",
        "dev input1: EV_KEY",
        "dev input1: EV_KEY       BTN_TOUCH            DOWN",
        "dev input1: EV_ABS       ABS_MT_POSITION_X    not-hex",
        "dev input1: EV_ABS       ABS_MT_POSITION_Y    00000064",
        "dev input1: EV_SYN       SYN_REPORT           00000000",
        "dev input1: EV_KEY       BTN_TOUCH            UP",
    ])
    assert [e["kind"] for e in events] == ["tap"]


def test_parse_autorepeat_not_double_counted():
    events = parse_getevent_lines([
        "dev input0: EV_KEY       KEY_BACK             DOWN",
        "dev input0: EV_KEY       KEY_BACK             AUTOREPEAT",
        "dev input0: EV_KEY       KEY_BACK             UP",
    ])
    assert [e["kind"] for e in events] == ["back"]



def _fake_adb_on_path(tmp_path, script_body):
    tool = tmp_path / "adb"
    tool.write_text(script_body, encoding="utf-8")
    tool.chmod(tool.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP)


def test_start_returns_none_when_adb_absent(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path))
    assert start_input_stream(serial="emulator-5554") is None


def test_capture_reads_fake_adb_stream_and_stops(tmp_path, monkeypatch):
    lines = "\\n".join(_TAP_STREAM)
    _fake_adb_on_path(tmp_path, f"#!/bin/sh\nprintf '{lines}\\n'\n")
    monkeypatch.setenv("PATH", str(tmp_path))
    capture = start_input_stream(serial="emulator-5554")
    assert capture is not None
    events = capture.events()
    assert [e["kind"] for e in events] == ["tap"]
    capture.stop()


def test_capture_dies_midstream_is_honest_partial(tmp_path, monkeypatch):
    pass
    _fake_adb_on_path(tmp_path, "#!/bin/sh\nexit 1\n")
    monkeypatch.setenv("PATH", str(tmp_path))
    capture = start_input_stream(serial=None)
    assert capture is not None
    assert capture.events() == []


def test_capture_events_have_ts_from_wall_clock(tmp_path, monkeypatch):
    _fake_adb_on_path(tmp_path,
                      "#!/bin/sh\n"
                      "printf 'dev input0: EV_KEY       KEY_BACK             DOWN\\n'\n"
                      "printf 'dev input0: EV_KEY       KEY_BACK             UP\\n'\n")
    monkeypatch.setenv("PATH", str(tmp_path))
    capture = start_input_stream()
    events = capture.events()
    assert events and isinstance(events[0]["ts"], (int, float))



from driver.mobile.recording import collect_record_events  # noqa: E402


class _NoHookAdapter:
    pass


def test_scrcpy_level_consumes_capture_handle():
    class _StubCapture:
        def events(self):
            return [{"kind": "tap", "ts": 1, "detail": "坐标(1,2)"}]

    out = collect_record_events(_NoHookAdapter(),
                                {"level": "scrcpy", "capture": _StubCapture()})
    assert out["level"] == "scrcpy"
    assert out["events"][0]["kind"] == "tap"
    assert out["dropped"] == 0


def test_scrcpy_level_without_capture_is_honest_empty():
    pass
    out = collect_record_events(_NoHookAdapter(), {"level": "scrcpy"})
    assert out == {"level": "scrcpy", "events": [], "dropped": 0}


def test_capture_hook_crash_does_not_break_result():
    class _BoomCapture:
        def events(self):
            raise RuntimeError("子进程炸了")

    out = collect_record_events(_NoHookAdapter(),
                                {"level": "scrcpy", "capture": _BoomCapture()})
    assert out["events"] == []



from app_worker import run_app_task  # noqa: E402
from driver.webdriver import RunOutcome  # noqa: E402


class _RecordReadyAdapter:
    def __init__(self):
        self.prepared = False

    def prepare(self, ctx):
        self.prepared = True

    def execute(self, step):
        return {"result": "pass"}

    def collect(self, artifact):
        return None

    def teardown(self):
        pass


def test_app_worker_scrcpy_record_wires_stream_events(tmp_path, monkeypatch):
    lines = "\\n".join(_SWIPE_STREAM)
    _fake_adb_on_path(tmp_path, f"#!/bin/sh\nprintf '{lines}\\n'\n")
    monkeypatch.setenv("PATH", str(tmp_path))
    envelope = {
        "kind": "app-auto-run",
        "runId": "rec-2b-1",
        "leaseRef": {"deviceId": 1, "serial": "emulator-5554"},
        "steps": [],
        "record": True,
        "engine": {
            "code": "FAKE-2B",
            "platformCodes": ["ANDROID"],
            "capabilities": {"record-native": False, "tap": True},
        },
    }
    task = {"taskId": 900002, "_adapterOverride": _RecordReadyAdapter(),
            "actionJson": json.dumps(envelope, ensure_ascii=False)}
    outcome = run_app_task(task)
    assert outcome.result == "pass"
    rec = outcome.record_events
    assert rec["level"] == "scrcpy"
    assert [e["kind"] for e in rec["events"]] == ["swipe"]
    revived = RunOutcome.from_dict(json.loads(json.dumps(outcome.to_dict())))
    assert revived.record_events["events"][0]["target"] == "up"
