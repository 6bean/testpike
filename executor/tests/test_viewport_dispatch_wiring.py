"""Viewport scaling wiring contract at the single step-dispatch point .

Why this module exists: recon pinned that baseline-pixel coordinates and
visual-fallback hit coordinates are indistinguishable at the engine adapter face
(the fallback re-dispatch writes dict(step, x=.., y=..) exactly like a recorded
step). Therefore the ONLY sanctioned scaling site is the worker dispatch point:
recorded steps scale through driver.mobile.viewport_scaling (single source,
rule R40 — this face binds the transform, it never re-implements the decision),
while live-device-space steps (visual fallback hit points) bypass the scaler
verbatim.

Pinned here:
- declared resolutionBaseline + readable device -> recorded x/y mapped at dispatch;
- no declared baseline -> zero-touch: device plane never consulted, coordinates
 dispatched unchanged;
- declared but illegal baseline -> run fails before prepare (engine session
 untouched), naming the payload key in the message;
- unreadable device -> identity dispatch + honest note visible in the step ledger;
- popup self-heal probe/dismiss/retry ride the same scaled channels (recorded
 space, the dismiss step was learned against the baseline resolution);
- visual-fallback live point is dispatched verbatim (already device space).
"""
from __future__ import annotations

import copy
import json
import os
import tempfile

from app_worker import run_app_task
from driver.mobile import viewport_scaling

_BASELINE = "720x1600"
_DEVICE = (1440, 3200)  # exactly 2x the baseline on both channels
_NOTE_FRAGMENT = "跨分辨率变体账未生效"


class ScriptedAdapter:
    """L2 engine face: records every dispatched step, pops one canned result.

    Stub plane only (never the real dispatch chain): collect yields no files so
    the failure-evidence trio degrades honestly and bypass paths keep running.
    """

    def __init__(self, script, a11y_frame=None):
        self.script = list(script)
        self.a11y_frame = a11y_frame
        self.dispatched: list[dict] = []
        self.calls: list[str] = []

    def prepare(self, ctx):
        self.calls.append("prepare")

    def execute(self, step):
        self.dispatched.append(copy.deepcopy(step))
        return self.script.pop(0) if self.script else {"result": "pass"}

    def collect(self, artifact):
        if not self.a11y_frame:
            return []
        root = tempfile.mkdtemp(prefix="vp-wiring-")
        shot = os.path.join(root, "shot.png")
        tree = os.path.join(root, "tree.json")
        with open(shot, "wb") as fh:
            fh.write(b"\x89PNG fake-frame")
        with open(tree, "w", encoding="utf-8") as fh:
            json.dump(self.a11y_frame, fh)
        return [{"kind": "screenshot", "localPath": shot},
                {"kind": "dom_snapshot", "localPath": tree}]

    def teardown(self):
        self.calls.append("teardown")


def _envelope(baseline: str | None = _BASELINE, steps=None, **extra):
    profile = {"profileKind": "AVD"}
    if baseline is not None:
        profile[viewport_scaling.BASELINE_PAYLOAD_KEY] = baseline
    envelope = {"kind": "app-auto-run", "runId": 7,
                "engine": {"code": "STUB"},
                "leaseRef": {"deviceId": 11, "serial": "emulator-5554"},
                "envProfile": profile,
                "steps": steps if steps is not None
                else [{"action": "app.tap", "x": 100, "y": 200}]}
    envelope.update(extra)
    return envelope


def _task(adapter, envelope, reader=None, **extra):
    task = {"taskId": 907, "actionJson": json.dumps(envelope),
            "_adapterOverride": adapter}
    if reader is not None:
        task["_viewportResolutionReader"] = reader
    task.update(extra)
    return task


def _reader(sizes, seen):
    """Injected device plane: records the serial it was asked about."""

    def read(serial):
        seen.append(serial)
        return sizes.pop(0) if sizes else None
    return read


# ── dispatch-point scaling ─────────────────────────────────────────────────


def test_no_declared_baseline_is_zero_touch():
    adapter = ScriptedAdapter([])
    seen: list[str] = []
    outcome = run_app_task(_task(adapter, _envelope(baseline=None),
                                 reader=_reader([_DEVICE], seen)))
    assert outcome.result == "pass"
    assert seen == []
    assert adapter.dispatched[0]["x"] == 100
    assert adapter.dispatched[0]["y"] == 200


def test_declared_baseline_scales_recorded_tap_at_dispatch():
    adapter = ScriptedAdapter([{"result": "pass"}])
    seen: list[str] = []
    outcome = run_app_task(_task(adapter, _envelope(),
                                 reader=_reader([_DEVICE], seen)))
    assert outcome.result == "pass"
    assert seen == ["emulator-5554"]
    assert adapter.dispatched[0]["x"] == 200
    assert adapter.dispatched[0]["y"] == 400


def test_identity_resolution_dispatches_unchanged():
    adapter = ScriptedAdapter([{"result": "pass"}])
    seen: list[str] = []
    outcome = run_app_task(_task(adapter, _envelope(),
                                 reader=_reader([(720, 1600)], seen)))
    assert outcome.result == "pass"
    assert adapter.dispatched[0]["x"] == 100
    assert adapter.dispatched[0]["y"] == 200
    row = outcome.steps_timeline[0]
    assert _NOTE_FRAGMENT not in (row.get("detail") or "")


def test_unreadable_device_notes_the_record_and_keeps_coordinates():
    adapter = ScriptedAdapter([{"result": "pass"}])
    seen: list[str] = []
    outcome = run_app_task(_task(adapter, _envelope(), reader=_reader([None], seen)))
    assert outcome.result == "pass"
    assert adapter.dispatched[0]["x"] == 100
    assert adapter.dispatched[0]["y"] == 200
    assert _NOTE_FRAGMENT in (outcome.steps_timeline[0].get("detail") or "")


def test_illegal_declared_baseline_fails_before_engine_prepare():
    adapter = ScriptedAdapter([])
    seen: list[str] = []
    outcome = run_app_task(_task(adapter, _envelope(baseline="wide-tall"),
                                 reader=_reader([_DEVICE], seen)))
    assert outcome.result == "fail"
    assert viewport_scaling.BASELINE_PAYLOAD_KEY in (outcome.error_detail or "")
    assert adapter.calls == []


def test_direction_only_swipe_channel_untouched():
    adapter = ScriptedAdapter([{"result": "pass"}])
    seen: list[str] = []
    envelope = _envelope(steps=[{"action": "app.swipe", "direction": "up"}])
    outcome = run_app_task(_task(adapter, envelope, reader=_reader([_DEVICE], seen)))
    assert outcome.result == "pass"
    step = adapter.dispatched[0]
    assert step["direction"] == "up"
    assert "x" not in step and "y" not in step


# ── bypass paths share the same single point ───────────────────────────────


def test_popup_selfheal_replays_scaled_channels():
    rules = [{"id": 41, "title": "force-stop confirm",
              "rule": {"detect": {"text": "Cancel"},
                       "dismiss": {"action": "app.tap",
                                   "target": {"text": "Cancel",
                                              "x": 30, "y": 40}}}}]
    adapter = ScriptedAdapter([
        {"result": "fail", "errorType": 2, "detail": "element not found"},
        {"result": "pass"},   # presence probe rides the engine's own verb
        {"result": "pass"},   # dismiss tap (recorded space -> scaled)
        {"result": "pass"},   # original step retried (recorded space -> scaled)
    ])
    seen: list[str] = []
    outcome = run_app_task(_task(adapter, _envelope(popupRules=rules),
                                 reader=_reader([_DEVICE], seen)))
    assert outcome.result == "pass"
    assert outcome.steps_timeline[0]["popupSelfheal"]["ruleId"] == 41
    assert adapter.dispatched[0]["x"] == 200
    assert adapter.dispatched[1]["action"] == "app.assertVisible"
    assert adapter.dispatched[2]["x"] == 60 and adapter.dispatched[2]["y"] == 80
    assert adapter.dispatched[3]["x"] == 200 and adapter.dispatched[3]["y"] == 400


def test_visual_fallback_live_point_is_never_rescaled():
    fallback_cfg = {"ai.vision.enabled": "1", "ai.vision.appauto-locate": "1",
                    "ai.appauto.locate.confidence": "0.85,0.6"}

    def channel(req):
        return {"x": 900.0, "y": 1500.0, "label": "submit button",
                "confidence": 1.0}

    adapter = ScriptedAdapter([
        {"result": "fail", "errorType": 2, "detail": "element not found"},
        {"result": "pass"},   # retry at the live hit point
    ], a11y_frame=[{"x": 860, "y": 1460, "w": 80, "h": 80,
                    "label": "submit button"}])
    seen: list[str] = []
    task = _task(adapter, _envelope(visualFallback=fallback_cfg),
                 reader=_reader([_DEVICE], seen))
    task["_visualLocateChannel"] = channel
    outcome = run_app_task(task)
    assert outcome.result == "pass"
    retry = adapter.dispatched[1]
    assert float(retry["x"]) == 900.0 and float(retry["y"]) == 1500.0


def test_unreadable_device_note_survives_prepare_failure():
    """A session that never comes up must still record why scaling was skipped.

    Bogus/unreadable device + declared baseline: prepare raises before any step
    row exists, so the identity-fallback note rides the failure detail instead
    of vanishing with the empty timeline (honest-record contract).
    """
    class _BoomAdapter(ScriptedAdapter):
        def prepare(self, ctx):
            raise RuntimeError("no online android device found")

    adapter = _BoomAdapter([])
    outcome = run_app_task(_task(adapter, _envelope(),
                                 reader=lambda serial: None))
    assert outcome.result == "fail"
    assert "未读到" in (outcome.error_detail or "")
