
from __future__ import annotations

import json
import os

from app_worker import run_app_task


class _ShotCollectAdapter:
    pass

    def __init__(self, fail_error_type=2, tap_changes_frame=True):
        self.calls: list[str] = []
        self.fail_error_type = fail_error_type
        self.tap_changes_frame = tap_changes_frame
        self._prepared = False
        self._clicked = False

    def prepare(self, ctx):
        self._prepared = True
        self.calls.append("prepare")

    def execute(self, step):
        verb = step.get("verb")
        self.calls.append(f"execute:{verb}")
        if step.get("x") is not None and step.get("y") is not None:
            # Coordinate retry rides the top-level x/y face that every real
            # adapter consumes (mobilewright bridge params.x, appium
            # _coordinate, maestro step['x']). The old nested "point" face
            # was a fixture invention the live channel never honoured.
            self._clicked = True
            return {"result": "pass"}
        return {"result": "fail", "errorType": self.fail_error_type,
                "detail": "确定性定位全链失败（selector 未命中）"}

    def collect(self, artifact):
        d = artifact["artifactDir"]
        os.makedirs(d, exist_ok=True)
        shot = os.path.join(d, f"shot-{artifact['stepNo']}.png")
        tree = os.path.join(d, f"tree-{artifact['stepNo']}.json")
        frame = (b"FRAME-POST" if (self._clicked and self.tap_changes_frame)
                 else b"FRAME-PRE")
        with open(shot, "wb") as f:
            f.write(frame)
        with open(tree, "w", encoding="utf-8") as f:
            json.dump([{"x": 90, "y": 190, "w": 60, "h": 40, "label": "提交"}], f)
        return [{"kind": "screenshot", "localPath": shot},
                {"kind": "dom_snapshot", "localPath": tree}]

    def teardown(self):
        self.calls.append("teardown")


def _task(adapter, fallback_cfg=None, channel=None, action="app.tap"):
    envelope = {"kind": "app-auto-run", "runId": 1,
                "engine": {"code": "STUB"},
                "steps": [{"action": action,
                           "selector": {"testTag": "submit"},
                           "arguments": {"text": "abc"}}]}
    if fallback_cfg is not None:
        envelope["visualFallback"] = fallback_cfg
    task = {"taskId": 901, "actionJson": json.dumps(envelope),
            "_adapterOverride": adapter}
    if channel is not None:
        task["_visualLocateChannel"] = channel
    return task


_ON = {"ai.vision.enabled": "1", "ai.vision.appauto-locate": "1",
       "ai.appauto.locate.confidence": "0.85,0.6"}


def _vision(confidence=1.0, x=110.0, y=205.0):
    return {"x": x, "y": y, "label": "提交按钮", "confidence": confidence}


def _row_of(outcome, step_no=1):
    return next(r for r in outcome.steps_timeline if r.get("stepNo") == step_no)


def test_switch_off_zero_residue_channel_never_touched():
    pass
    touched = []

    def channel(req):
        touched.append(req)
        return _vision()

    outcome = run_app_task(_task(_ShotCollectAdapter(), None, channel))
    assert outcome.result == "fail"
    assert not touched
    assert "aiLocate" not in _row_of(outcome)


def test_non_locator_error_never_falls_back():
    pass
    touched = []
    outcome = run_app_task(_task(_ShotCollectAdapter(fail_error_type=3),
                                 _ON, lambda r: touched.append(r) or _vision()))
    assert outcome.result == "fail"
    assert not touched


def test_direct_tier_flips_step_green_with_badge(tmp_path):
    pass
    adapter = _ShotCollectAdapter()
    seen = []

    def channel(req):
        seen.append(req)
        assert req["screenshotBytes"]
        assert any(c.get("label") == "提交" for c in req["a11yCandidates"])
        return _vision(confidence=1.0)

    outcome = run_app_task(_task(adapter, _ON, channel))
    assert outcome.result == "pass"
    row = _row_of(outcome)
    assert row["aiLocate"]["tier"] == "direct"
    assert row["aiLocate"]["badge"] == "ai-located"
    assert row["aiLocate"]["score"] >= 0.85
    assert seen


def test_selfcheck_tier_frame_changed_passes():
    pass
    outcome = run_app_task(_task(_ShotCollectAdapter(tap_changes_frame=True),
                                 _ON, lambda r: _vision(confidence=0.8)))
    row = _row_of(outcome)
    assert outcome.result == "pass"
    assert row["aiLocate"]["tier"] == "selfcheck"


def test_selfcheck_tier_frame_unchanged_fails_closed():
    pass
    adapter = _ShotCollectAdapter(tap_changes_frame=False)

    def channel(req):
        return _vision(confidence=0.8)

    outcome = run_app_task(_task(adapter, _ON, channel))
    row = _row_of(outcome)
    assert outcome.result == "fail"
    assert "自检不一致" in (row.get("detail") or "")
    ev = row["locateEvidence"]
    assert ev["stepNo"] == 1 and ev["candidates"]


def test_reject_tier_keeps_failure_with_rewrite_hint():
    pass
    outcome = run_app_task(_task(_ShotCollectAdapter(), _ON,
                                 lambda r: _vision(confidence=0.3)))
    row = _row_of(outcome)
    assert outcome.result == "fail"
    assert "建议" in (row.get("detail") or "")
    assert row["locateEvidence"]["candidates"]


def test_switch_on_channel_missing_honest_annotation():
    pass
    outcome = run_app_task(_task(_ShotCollectAdapter(), _ON, None))
    row = _row_of(outcome)
    assert outcome.result == "fail"
    assert "通道未接线" in (row.get("detail") or "")


def test_channel_explosion_degrades_not_naked_throw():
    pass
    def boom(req):
        raise RuntimeError("模型网关超时")

    outcome = run_app_task(_task(_ShotCollectAdapter(), _ON, boom))
    assert outcome.result == "fail"
    assert "视觉兜底通道异常" in (_row_of(outcome).get("detail") or "")


def test_non_tap_verb_not_swallowed_by_point_retry():
    pass
    outcome = run_app_task(_task(_ShotCollectAdapter(), _ON,
                                 lambda r: _vision(), action="app.input"))
    row = _row_of(outcome)
    assert outcome.result == "fail"
    assert "仅可兜底点击类" in (row.get("detail") or "")
