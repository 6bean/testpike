
from __future__ import annotations

import json

from driver.mobile.perf_assert import (
    HOST_SIDE_VERBS,
    SUPPORTED_METRICS,
    evaluate_perf_step,
)


class _FakeSession:
    pass

    def __init__(self, samples: list[dict], dir_path: str) -> None:
        import os

        self.active = True
        self.samples_path = os.path.join(dir_path, "tp-perf-samples.jsonl")
        with open(self.samples_path, "w", encoding="utf-8") as f:
            for row in samples:
                f.write(json.dumps(row) + "\n")


def _samples() -> list[dict]:
    return [
        {"ts": 1.0, "jankPct": 3.0, "pssKb": 100_000, "cpuPct": 20.0, "tempC": 30.0},
        {"ts": 2.0, "jankPct": 6.5, "pssKb": 524_288, "cpuPct": 45.0, "tempC": 38.5},
    ]


def test_perf_动词登记宿主侧单源集合():
    assert "perf" in HOST_SIDE_VERBS
    assert HOST_SIDE_VERBS == frozenset({"perf", "launchPerf"})


def test_阈值行通过态取最新样本读数(tmp_path):
    session = _FakeSession(_samples(), str(tmp_path))
    res = evaluate_perf_step({"action": "app.perf", "expr": "jankPct<=10"}, session)
    assert res["result"] == "pass"
    assert "6.5" in res["detail"], "通过态必须报实际读数（禁裸 pass）"


def test_阈值行失败态点名读数与阈值(tmp_path):
    session = _FakeSession(_samples(), str(tmp_path))
    res = evaluate_perf_step({"action": "app.perf", "expr": "cpuPct<30"}, session)
    assert res["result"] == "fail"
    assert "45" in res["detail"] and "30" in res["detail"]


def test_pss_指标换算mb口径(tmp_path):
    session = _FakeSession(_samples(), str(tmp_path))
    assert evaluate_perf_step({"expr": "pssMb<=512"}, session)["result"] == "pass"
    assert evaluate_perf_step({"expr": "pssMb<=511.9"}, session)["result"] == "fail"


def test_表达式非法人话点名支持面(tmp_path):
    session = _FakeSession(_samples(), str(tmp_path))
    res = evaluate_perf_step({"expr": "jankPct ~ 5"}, session)
    assert res["result"] == "fail"
    for metric in SUPPORTED_METRICS:
        assert metric in res["detail"], "违例文案必须列出支持指标全集（改错免翻文档）"


def test_白名单外指标诚实点名未支持(tmp_path):
    session = _FakeSession(_samples(), str(tmp_path))
    res = evaluate_perf_step({"expr": "coldStartMs<2500"}, session)
    assert res["result"] == "fail"
    assert "coldStartMs" in res["detail"]
    assert "未" in res["detail"] or "not" in res["detail"].lower()


def test_指标最新样本缺值诚实缺位不猜(tmp_path):
    samples = [{"ts": 1.0, "jankPct": 1.0}, {"ts": 2.0, "pssKb": 10}]
    session = _FakeSession(samples, str(tmp_path))
    res = evaluate_perf_step({"expr": "tempC<=45"}, session)
    assert res["result"] == "fail"
    assert "tempC" in res["detail"]


def test_采样会话缺位诚实fail禁假过(tmp_path):
    res = evaluate_perf_step({"expr": "jankPct<=10"}, None)
    assert res["result"] == "fail"
    assert "采样会话" in res["detail"]
    empty = _FakeSession([], str(tmp_path))
    res2 = evaluate_perf_step({"expr": "jankPct<=10"}, empty)
    assert res2["result"] == "fail"


def test_expr_键缺失人话点名步参数():
    res = evaluate_perf_step({}, None)
    assert res["result"] == "fail"
    assert "expr" in res["detail"]
