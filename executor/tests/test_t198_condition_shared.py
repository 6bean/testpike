
from __future__ import annotations

import json
from pathlib import Path

import pytest

from driver.condition_expr import ConditionError, evaluate_condition

CASES = json.loads(
    (Path(__file__).resolve().parents[2] / "shared" / "condition_expr_cases.json")
    .read_text(encoding="utf-8"))


def test_夹具非空且含说明字段():
    assert len(CASES["negatives"]) >= 10, "负向族不得缩水（R3 同源比对基准）"
    assert len(CASES["positives"]) >= 5
    for c in CASES["negatives"]:
        assert c.get("why"), f"负向样本缺拒绝理由：{c['expr']!r}"


@pytest.mark.parametrize("case", CASES["negatives"],
                         ids=lambda c: c["expr"][:24] or "empty")
def test_负向样本_Python权威侧一律拒绝(case):
    with pytest.raises(ConditionError):
        evaluate_condition(case["expr"], {"a": 1, "b": True, "x": 1,
                                          "item": {"price": 120},
                                          "flag": True, "count": 2})


@pytest.mark.parametrize("case", CASES["positives"],
                         ids=lambda c: c["expr"][:24])
def test_正向样本_Python权威侧求值为真(case):
    assert evaluate_condition(case["expr"], dict(case["scope"])) is True
