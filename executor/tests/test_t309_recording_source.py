
from __future__ import annotations

import json
import os
import pathlib
import stat
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from driver.webdriver import RunOutcome  # noqa: E402

from driver.mobile.recording import (  # noqa: E402
    LEVEL_NATIVE,
    LEVEL_SCRCPY,
    LEVEL_UNAVAILABLE,
    normalize_events,
    resolve_recording_source,
)


def _descriptor(code="FAKE-L2", record_native=False, platforms=("ANDROID",)):
    return {
        "code": code,
        "platformCodes": list(platforms),
        "capabilities": {
            "record-native": record_native,
            "tap": True,
        },
    }


class _NativeHookAdapter:
    pass

    def record_events(self):
        return [{"kind": "tap", "ts": 1}]


class _NoHookAdapter:
    pass


def _fake_tool_on_path(tmp_path, monkeypatch, name="scrcpy"):
    tool = tmp_path / name
    tool.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    tool.chmod(tool.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP)
    monkeypatch.setenv("PATH", str(tmp_path))



def test_native_level_when_capability_on_and_hook_present():
    res = resolve_recording_source(_descriptor(record_native=True), _NativeHookAdapter())
    assert res["level"] == LEVEL_NATIVE
    assert res["reason"] is None


def test_capability_on_but_hook_missing_is_drift_not_fake_native(tmp_path, monkeypatch):
    pass
    monkeypatch.setenv("PATH", "")
    res = resolve_recording_source(_descriptor(record_native=True), _NoHookAdapter())
    assert res["level"] == LEVEL_UNAVAILABLE
    assert "record-native" in res["reason"] and "缺失" in res["reason"]


def test_capabilities_json_string_form_is_parsed():
    pass
    import json
    d = _descriptor(record_native=True)
    d["capabilities"] = json.dumps(d["capabilities"])
    res = resolve_recording_source(d, _NativeHookAdapter())
    assert res["level"] == LEVEL_NATIVE



def test_android_fallback_scrcpy_when_capability_off_and_tool_in_path(tmp_path, monkeypatch):
    _fake_tool_on_path(tmp_path, monkeypatch, "scrcpy")
    res = resolve_recording_source(_descriptor(record_native=False), _NoHookAdapter())
    assert res["level"] == LEVEL_SCRCPY


def test_android_fallback_also_satisfied_by_adb(tmp_path, monkeypatch):
    pass
    _fake_tool_on_path(tmp_path, monkeypatch, "adb")
    res = resolve_recording_source(_descriptor(record_native=False), _NoHookAdapter())
    assert res["level"] == LEVEL_SCRCPY


def test_non_android_no_fallback_stream(tmp_path, monkeypatch):
    pass
    monkeypatch.setenv("PATH", str(tmp_path))
    res = resolve_recording_source(
        _descriptor(record_native=False, platforms=("HARMONY",)), _NoHookAdapter())
    assert res["level"] == LEVEL_UNAVAILABLE



def test_unavailable_reason_is_plain_words(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", "")
    res = resolve_recording_source(_descriptor(record_native=False), _NoHookAdapter())
    assert res["level"] == LEVEL_UNAVAILABLE
    assert "录制" in res["reason"] or "置灰" in res["reason"]
    assert "scrcpy" in res["reason"] and "adb" in res["reason"]


def test_no_scattered_engine_branch_same_data_same_verdict():
    pass
    a = resolve_recording_source(_descriptor(code="MOBILEWRIGHT", record_native=False),
                                 _NoHookAdapter())
    b = resolve_recording_source(_descriptor(code="HMNEXTAUTO", record_native=False),
                                 _NoHookAdapter())
    assert a == b


def test_missing_adapter_is_honest_not_crash():
    res = resolve_recording_source(_descriptor(record_native=True), None)
    assert res["level"] != LEVEL_NATIVE



def test_normalize_keys_wellformed_events():
    out = normalize_events([
        {"kind": "tap", "ts": 1727000000.5, "target": "com.demo:id/login"},
        {"kind": "input", "ts": 1727000001, "detail": "文本已注入"},
    ])
    assert out["dropped"] == 0
    assert [e["kind"] for e in out["events"]] == ["tap", "input"]
    assert out["events"][0]["target"] == "com.demo:id/login"


def test_normalize_drops_bad_rows_with_count_not_whole_sequence():
    pass
    out = normalize_events([
        {"kind": "tap", "ts": 1},
        "不是对象",
        {"ts": 2},
        {"kind": ""},
        None,
        {"kind": "swipe", "ts": 3, "extra": "键外料不收"},
    ])
    assert [e["kind"] for e in out["events"]] == ["tap", "swipe"]
    assert out["dropped"] == 4
    assert "extra" not in out["events"][1]


def test_normalize_empty_input_is_clean_shape():
    out = normalize_events(None)
    assert out == {"events": [], "dropped": 0}



from app_worker import run_app_task  # noqa: E402


class _RecordReadyAdapter:
    pass

    def __init__(self):
        self.prepared = False
        self.torndown = False

    def prepare(self, ctx):
        self.prepared = True

    def execute(self, step):
        return {"result": "pass"}

    def collect(self, artifact):
        return None

    def teardown(self):
        self.torndown = True

    def record_events(self):
        return [{"kind": "tap", "ts": 1}]


def _record_envelope(record=True, native=False):
    return {
        "kind": "app-auto-run",
        "runId": "rec-l2-1",
        "steps": [],
        "engine": _descriptor(record_native=native),
        "record": record,
    }


def _task(adapter, envelope: dict | None = None):
    task = {"taskId": 900001, "_adapterOverride": adapter}
    if envelope is not None:
        import json
        task["actionJson"] = json.dumps(envelope, ensure_ascii=False)
    return task


def test_record_mode_unavailable_fails_with_plain_reason_and_zero_execution(tmp_path, monkeypatch):
    pass
    monkeypatch.setenv("PATH", "")
    adapter = _RecordReadyAdapter()
    task = _task(adapter, _record_envelope(record=True, native=False))
    outcome = run_app_task(task)
    assert outcome.result == "fail"
    assert "录制" in outcome.error_detail
    assert not adapter.prepared


def test_record_mode_native_passes_source_conclusion_to_session(tmp_path, monkeypatch):
    pass
    adapter = _RecordReadyAdapter()
    task = _task(adapter, _record_envelope(record=True, native=True))
    outcome = run_app_task(task)
    assert outcome.result == "pass"
    assert adapter.torndown


def test_non_record_envelope_behavior_unchanged():
    pass
    adapter = _RecordReadyAdapter()
    env = _record_envelope(record=True, native=False)
    env.pop("record")
    task = _task(adapter, env)
    outcome = run_app_task(task)
    assert outcome.result == "pass"


def test_record_events_collected_into_outcome_channel(tmp_path, monkeypatch):
    pass
    adapter = _RecordReadyAdapter()
    task = _task(adapter, _record_envelope(record=True, native=True))
    outcome = run_app_task(task)
    rec = getattr(outcome, "record_events", None)
    assert rec is not None and rec["level"] == "native"
    assert [e["kind"] for e in rec["events"]] == ["tap"]


def test_record_outcome_survives_process_boundary_roundtrip():
    pass
    adapter = _RecordReadyAdapter()
    task = _task(adapter, _record_envelope(record=True, native=True))
    outcome = run_app_task(task)
    revived = RunOutcome.from_dict(json.loads(json.dumps(outcome.to_dict())))
    rec = revived.record_events or {}
    assert rec["level"] == "native"
    assert rec["events"][0]["kind"] == "tap"


def test_plain_run_emits_no_record_key():
    pass
    adapter = _RecordReadyAdapter()
    task = _task(adapter, _record_envelope(record=True, native=True))
    task["actionJson"] = json.dumps({**_record_envelope(), "record": False})
    outcome = run_app_task(task)
    assert "record_events" not in outcome.to_dict()



from protocol import ExecutorProtocolClient  # noqa: E402


class _FakeResultServer:
    pass

    def __init__(self):
        import http.server
        import threading
        self.received: list[dict] = []

        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length)
                outer.received.append(json.loads(body or b"{}"))
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"code":"0"}')

            def log_message(self, format, *args):
                pass

        self.server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        self.server.shutdown()


def _protocol_client(srv):
    client = ExecutorProtocolClient(f"http://127.0.0.1:{srv.server.server_address[1]}")
    client.executor_id = 1
    return client


def test_record_events_trailing_key_in_result_payload():
    srv = _FakeResultServer()
    try:
        client = _protocol_client(srv)
        rec = {"level": "native", "events": [{"kind": "tap", "ts": 1}], "dropped": 0}
        client.submit_result(7001, "tk", "pass", record_events=rec)
        body = srv.received[-1]
        assert body["recordEvents"] == rec
        client.submit_result(7002, "tk", "pass")
        assert "recordEvents" not in srv.received[-1]
    finally:
        srv.stop()
