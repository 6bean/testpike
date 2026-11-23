"""Mobilewright native record-event self-report channel (L2).

Covers the first level of the three-level recording source chain in
driver.mobile.recording: when the engine descriptor flips the
``record-native`` capability bit, the adapter must self-report every executed
action frame so that a recorded session yields events even though framework
injections (adb getevent kernel stream) are physically invisible to the scrcpy
fallback level.

Contract asserted here:
- prepare resets the ledger (one recorded session == one task run);
- only steps that actually executed (result == "pass") are reported;
- both execution surfaces are accounted: bridge primitives AND the adb verb
  family (half-accounting would silently drop verbs from drafts);
- the reported row shape matches the normalized whitelist keys
  (kind/ts/target/text/detail) consumed by collect_record_events;
- capability bit + hook together resolve the native level via the existing
  assembly single source (no second chain, R40).
"""
from __future__ import annotations

import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from driver.mobile.adb_verb_runner import ADB_FAMILY_VERBS  # noqa: E402
from driver.mobile.mobilewright_adapter import MobilewrightAdapter  # noqa: E402
from driver.mobile.recording import (  # noqa: E402
    LEVEL_NATIVE,
    collect_record_events,
    resolve_recording_source,
)


class _ResultBridge:
    """Frame-recording bridge stand-in (same surface as NodeBridge)."""

    def __init__(self, result="pass"):
        self.result = result
        self.started = False
        self.extra_env = {}

    def start(self):
        self.started = True

    def alive(self):
        return self.started

    def call(self, method, params=None):
        if method == "execute":
            return {"result": self.result}
        return {"ok": True}

    def stop(self):
        self.started = False


class _RecordingAdb:
    """adb-family stand-in that reports pass for every verb."""

    def __init__(self):
        self.runs: list[tuple[str, dict]] = []

    def run(self, verb, step, serial=None):
        self.runs.append((verb, step))
        return {"result": "pass"}

    def clear_network_profile(self, serial=None):
        return None


def _prepared_bridge_adapter(result="pass"):
    adapter = MobilewrightAdapter(bridge=_ResultBridge(result))
    adapter.prepare({"leaseRef": {"serial": "fake-serial"}, "runId": "r1"})
    return adapter


def test_adapter_exposes_record_events_hook():
    adapter = MobilewrightAdapter(bridge=_ResultBridge())
    assert callable(getattr(adapter, "record_events", None)), \
        "native level requires the record_events hook on the adapter"


def test_executed_bridge_verb_is_self_reported():
    adapter = _prepared_bridge_adapter()
    res = adapter.execute({"verb": "tap", "testId": "com.demo/login"})
    assert res.get("result") == "pass"
    events = adapter.record_events()
    assert len(events) == 1
    row = events[0]
    assert row["kind"] == "tap"
    assert row["target"] == "com.demo/login"
    assert isinstance(row["ts"], (int, float))
    assert set(row) <= {"kind", "ts", "target", "text", "detail"}


def test_executed_adb_family_verb_is_self_reported():
    adapter = _prepared_bridge_adapter()
    fake_adb = _RecordingAdb()
    adapter._adb = fake_adb
    verb = ADB_FAMILY_VERBS[0]
    res = adapter.execute({"verb": verb, "params": {}})
    assert res.get("result") == "pass"
    events = adapter.record_events()
    assert [e["kind"] for e in events] == [verb], \
        "the adb execution surface must be accounted as well as the bridge one"


def test_coordinate_step_reports_position_as_target():
    adapter = _prepared_bridge_adapter()
    adapter.execute({"verb": "tap", "x": 120, "y": 640})
    events = adapter.record_events()
    assert events and events[0]["target"] == "120,640"


def test_input_step_carries_text_field():
    adapter = _prepared_bridge_adapter()
    adapter.execute({"verb": "input", "testId": "kw", "text": "hello"})
    events = adapter.record_events()
    assert events and events[0].get("text") == "hello"


def test_failed_step_is_not_recorded():
    adapter = _prepared_bridge_adapter(result="fail")
    adapter.execute({"verb": "tap", "testId": "x"})
    assert adapter.record_events() == []


def test_rejected_verb_is_not_recorded():
    adapter = _prepared_bridge_adapter()
    res = adapter.execute({"verb": "notAVerbAtAll"})
    assert res.get("result") == "fail"
    assert adapter.record_events() == []


def test_prepare_clears_the_ledger():
    adapter = _prepared_bridge_adapter()
    adapter.execute({"verb": "tap", "testId": "x"})
    assert len(adapter.record_events()) == 1
    adapter.prepare({"leaseRef": {"serial": "fake-serial"}, "runId": "r2"})
    assert adapter.record_events() == []


def test_descriptor_bit_plus_hook_resolve_native_and_collect_events():
    descriptor = {
        "code": "MOBILEWRIGHT",
        "platformCodes": ["ANDROID"],
        "capabilities": {"record-native": True},
    }
    adapter = _prepared_bridge_adapter()
    adapter.execute({"verb": "tap", "testId": "com.demo/login"})
    source = resolve_recording_source(descriptor, adapter)
    assert source["level"] == LEVEL_NATIVE
    collected = collect_record_events(adapter, source)
    assert collected["level"] == LEVEL_NATIVE
    assert collected["dropped"] == 0
    assert len(collected["events"]) == 1
