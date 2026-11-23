
from __future__ import annotations

import pytest

from driver.mobile.visual_fallback import (
    DEFAULT_CONFIDENCE_HIGH,
    DEFAULT_CONFIDENCE_LOW,
    build_locate_evidence_packet,
    consensus_score,
    decide_visual_locate,
    fallback_switch_on,
    parse_confidence_thresholds,
    post_selfcheck_decision,
)


def _vision(x=100.0, y=200.0, label="提交按钮", confidence=0.9):
    return {"x": x, "y": y, "label": label, "confidence": confidence}


def _a11y(x=90.0, y=190.0, w=60.0, h=40.0, label="提交"):
    return {"x": x, "y": y, "w": w, "h": h, "label": label}



def test_switch_off_master_gate_returns_false_zero_fallback():
    assert fallback_switch_on({"ai.vision.enabled": "0",
                               "ai.vision.appauto-locate": "1"}) is False


def test_switch_off_sub_gate_returns_false():
    assert fallback_switch_on({"ai.vision.enabled": "1",
                               "ai.vision.appauto-locate": "0"}) is False


def test_switch_missing_keys_treated_as_off():
    assert fallback_switch_on(None) is False
    assert fallback_switch_on({}) is False


def test_switch_both_on_returns_true():
    assert fallback_switch_on({"ai.vision.enabled": "1",
                               "ai.vision.appauto-locate": "1"}) is True



def test_thresholds_default_on_blank_raw():
    hi, lo = parse_confidence_thresholds("")
    assert (hi, lo) == (DEFAULT_CONFIDENCE_HIGH, DEFAULT_CONFIDENCE_LOW)


def test_thresholds_parse_valid_pair():
    hi, lo = parse_confidence_thresholds("0.9,0.7")
    assert (hi, lo) == pytest.approx((0.9, 0.7))


def test_thresholds_inverted_pair_rejected_falls_back_default():
    hi, lo = parse_confidence_thresholds("0.5,0.8")
    assert (hi, lo) == (DEFAULT_CONFIDENCE_HIGH, DEFAULT_CONFIDENCE_LOW)


def test_thresholds_garbage_falls_back_default():
    hi, lo = parse_confidence_thresholds("不是数字")
    assert (hi, lo) == (DEFAULT_CONFIDENCE_HIGH, DEFAULT_CONFIDENCE_LOW)



def test_three_source_agreement_boosts_to_direct_tier():
    score = consensus_score(
        vision=_vision(confidence=0.8),
        a11y_candidates=[_a11y()],
        cache_hit={"x": 105.0, "y": 198.0},
    )
    assert score >= DEFAULT_CONFIDENCE_HIGH


def test_vision_alone_without_corroboration_not_direct():
    score = consensus_score(vision=_vision(confidence=0.95),
                            a11y_candidates=[], cache_hit=None)
    assert score < DEFAULT_CONFIDENCE_HIGH


def test_a11y_label_semantic_match_counts_without_box():
    score_with = consensus_score(
        vision=_vision(label="提交按钮"),
        a11y_candidates=[{"label": "提交按钮"}], cache_hit=None)
    score_without = consensus_score(
        vision=_vision(label="提交按钮"),
        a11y_candidates=[{"label": "取消"}], cache_hit=None)
    assert score_with > score_without


def test_invalid_vision_input_scores_zero():
    assert consensus_score(vision=None, a11y_candidates=[_a11y()],
                           cache_hit=None) == 0.0
    assert consensus_score(vision={"label": "无坐标"}, a11y_candidates=[],
                           cache_hit=None) == 0.0



def test_decide_direct_tier_clicks_with_badge():
    d = decide_visual_locate(_vision(confidence=0.95),
                             [_a11y()], {"x": 100.0, "y": 200.0})
    assert d["tier"] == "direct"
    assert d["action"] == "click"
    assert d["badge"] == "ai-located"


def test_decide_mid_band_requires_selfcheck():
    d = decide_visual_locate(_vision(confidence=0.8), [], None)
    assert d["tier"] == "selfcheck"
    assert d["action"] == "click_with_selfcheck"


def test_decide_low_band_rejects_with_candidates_and_hint():
    d = decide_visual_locate(_vision(confidence=0.3, label="模糊目标"),
                             [_a11y(label="提交")], None)
    assert d["tier"] == "reject"
    assert d["action"] == "reject"
    assert isinstance(d["candidates"], list) and d["candidates"]
    assert d["rewrite_hint"]


def test_decide_threshold_override_hot_updates():
    d = decide_visual_locate(_vision(confidence=0.9), [_a11y()], None,
                             thresholds=(0.99, 0.6))
    assert d["tier"] != "direct"



def test_selfcheck_pass_continues():
    d = post_selfcheck_decision(selfcheck_ok=True, step_no=3,
                                action="app.tap", score=0.7)
    assert d["action"] == "continue"


def test_selfcheck_fail_stops_and_produces_evidence():
    d = post_selfcheck_decision(selfcheck_ok=False, step_no=3,
                                action="app.tap", score=0.7,
                                vision=_vision(), a11y_candidates=[_a11y()])
    assert d["action"] == "stop_fail_closed"
    assert "第 3 步" in d["detail"]
    assert d["evidence"]["candidates"]



def test_evidence_packet_has_three_layers():
    pkt = build_locate_evidence_packet(step_no=5, action="app.tap",
                                       score=0.72, vision=_vision(),
                                       a11y_candidates=[_a11y()],
                                       cache_hit=None,
                                       screenshot_key="task/5/locate.png")
    assert pkt["screenshotKey"] == "task/5/locate.png"
    assert pkt["annotated"] is True
    assert len(pkt["candidates"]) == 2
    assert pkt["score"] == pytest.approx(0.72)
    assert pkt["stepNo"] == 5 and pkt["action"] == "app.tap"


def test_evidence_packet_without_screenshot_is_honest():
    pkt = build_locate_evidence_packet(step_no=1, action="app.tap",
                                       score=0.5, vision=None,
                                       a11y_candidates=[], cache_hit=None,
                                       screenshot_key=None)
    assert pkt["screenshotKey"] is None
    assert pkt["annotated"] is False
