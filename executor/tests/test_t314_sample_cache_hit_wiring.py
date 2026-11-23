
from __future__ import annotations

import json
import os

from app_worker import run_app_task
from driver.mobile.visual_fallback import build_locate_anchor


class _ShotCollectAdapter:
    pass

    def __init__(self):
        self._clicked = False

    def prepare(self, ctx):
        pass

    def execute(self, step):
        if step.get("x") is not None and step.get("y") is not None:
            # coordinate retry = the adapters' shared top-level x/y face
            self._clicked = True
            return {"result": "pass"}
        return {"result": "fail", "errorType": 2,
                "detail": "确定性定位全链失败（selector 未命中）"}

    def collect(self, artifact):
        d = artifact["artifactDir"]
        os.makedirs(d, exist_ok=True)
        shot = os.path.join(d, f"shot-{artifact['stepNo']}.png")
        tree = os.path.join(d, f"tree-{artifact['stepNo']}.json")
        with open(shot, "wb") as f:
            f.write(b"FRAME-POST" if self._clicked else b"FRAME-PRE")
        with open(tree, "w", encoding="utf-8") as f:
            json.dump([{"x": 90, "y": 190, "w": 60, "h": 40, "label": "提交"}], f)
        return [{"kind": "screenshot", "localPath": shot},
                {"kind": "dom_snapshot", "localPath": tree}]

    def teardown(self):
        pass


_ON = {"ai.vision.enabled": "1", "ai.vision.appauto-locate": "1",
       "ai.appauto.locate.confidence": "0.85,0.6"}

_STEP = {"action": "app.tap", "selector": {"testTag": "submit"},
         "arguments": {"text": "abc"}}


def _vision(confidence=0.8, x=110.0, y=205.0):
    return {"x": x, "y": y, "label": "提交按钮", "confidence": confidence}


def _task(adapter, channel, reader=None):
    envelope = {"kind": "app-auto-run", "runId": 1,
                "leaseRef": {"deviceId": 77, "serial": "emulator-x"},
                "engine": {"code": "STUB"}, "steps": [dict(_STEP)]}
    envelope["visualFallback"] = _ON
    task = {"taskId": 901, "actionJson": json.dumps(envelope),
            "_adapterOverride": adapter, "_visualLocateChannel": channel}
    if reader is not None:
        task["_aiSampleReader"] = reader
    return task


def _row(outcome, step_no=1):
    return next(r for r in outcome.steps_timeline if r.get("stepNo") == step_no)


def test_锚签名读写同源单点():
    pass
    a1 = build_locate_anchor(3, _STEP)
    a2 = build_locate_anchor(3, {"selector": {"testTag": "submit"}, "action": "app.tap"})
    assert a1 == a2
    assert a1 != build_locate_anchor(4, _STEP)
    assert a1 != build_locate_anchor(
        3, {"action": "app.tap", "selector": {"testTag": "other"}})
    assert len(a1) <= 64
    assert build_locate_anchor(1, None)


def test_同屏票在位第三源加分翻档():
    pass
    seen = []

    def reader(req):
        seen.append(req)
        return {"sampleId": 5, "source": "vision", "x": 110, "y": 205}

    outcome = run_app_task(_task(_ShotCollectAdapter(),
                                 lambda r: _vision(), reader))
    row = _row(outcome)
    assert outcome.result == "pass"
    assert row["aiLocate"]["tier"] == "direct"
    assert row["aiLocate"]["score"] >= 0.85
    assert seen, "读票通道未触达"
    payload = seen[0]
    assert payload["taskId"] == 901 and payload["stepNo"] == 1
    assert payload["deviceId"] == 77
    assert payload["anchor"] == build_locate_anchor(1, _STEP)


def test_无读票通道保持两源零涟漪():
    pass
    outcome = run_app_task(_task(_ShotCollectAdapter(), lambda r: _vision()))
    row = _row(outcome)
    assert outcome.result == "pass"
    assert row["aiLocate"]["tier"] == "selfcheck"
    assert "样本库" not in (row.get("detail") or "")


def test_读票异常降级缺票不炸主链():
    pass
    def boom(req):
        raise RuntimeError("读票链路超时")

    outcome = run_app_task(_task(_ShotCollectAdapter(), lambda r: _vision(), boom))
    row = _row(outcome)
    assert outcome.result == "pass"
    assert row["aiLocate"]["tier"] == "selfcheck"
    assert "样本库同屏票读取异常" in (row.get("detail") or "")


def test_回包缺坐标视同缺票禁假票():
    pass
    outcome = run_app_task(_task(_ShotCollectAdapter(), lambda r: _vision(),
                                 lambda req: {}))
    row = _row(outcome)
    assert outcome.result == "pass"
    assert row["aiLocate"]["tier"] == "selfcheck"


def test_弃点证据包含缓存候选():
    pass
    outcome = run_app_task(_task(_ShotCollectAdapter(),
                                 lambda r: _vision(confidence=0.3),
                                 lambda req: {"x": 110, "y": 205, "sampleId": 9}))
    ev = _row(outcome)["locateEvidence"]
    srcs = [c.get("source") for c in ev["candidates"]]
    assert "cache" in srcs
