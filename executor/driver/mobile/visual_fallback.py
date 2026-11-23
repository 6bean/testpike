
from __future__ import annotations

import hashlib
import json
import os

DEFAULT_CONFIDENCE_HIGH = 0.85
DEFAULT_CONFIDENCE_LOW = 0.60

LOCATE_CONFIDENCE_ENV_KEY = "APPAUTO_LOCATE_CONFIDENCE"

VISION_WEIGHT = 0.80
A11Y_AGREEMENT_BONUS = 0.12
CACHE_AGREEMENT_BONUS = 0.10
VISION_SINGLE_SOURCE_CAP = 0.84

_MATCH_TOLERANCE_PX = 40.0

_MASTER_GATE_KEY = "ai.vision.enabled"
_SUB_GATE_KEY = "ai.vision.appauto-locate"


def fallback_switch_on(config: dict | None) -> bool:
    pass
    if not isinstance(config, dict):
        return False
    return (str(config.get(_MASTER_GATE_KEY) or "0").strip() == "1"
            and str(config.get(_SUB_GATE_KEY) or "0").strip() == "1")


def parse_confidence_thresholds(raw: str | None) -> tuple:
    pass
    text = str(raw or "").strip()
    if not text:
        text = os.environ.get(LOCATE_CONFIDENCE_ENV_KEY, "").strip()
    parts = text.split(",")
    if len(parts) != 2:
        return (DEFAULT_CONFIDENCE_HIGH, DEFAULT_CONFIDENCE_LOW)
    try:
        hi = float(parts[0])
        lo = float(parts[1])
    except ValueError:
        return (DEFAULT_CONFIDENCE_HIGH, DEFAULT_CONFIDENCE_LOW)
    if not (0.0 < lo < hi <= 1.0):
        return (DEFAULT_CONFIDENCE_HIGH, DEFAULT_CONFIDENCE_LOW)
    return (hi, lo)


def _vision_point(vision):
    pass
    if not isinstance(vision, dict):
        return None
    x, y = vision.get("x"), vision.get("y")
    if isinstance(x, bool) or isinstance(y, bool) \
            or not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
        return None
    return (float(x), float(y))


def _a11y_agrees(vision_point, label, candidate) -> bool:
    pass
    if not isinstance(candidate, dict):
        return False
    has_box = all(isinstance(candidate.get(k), (int, float))
                  and not isinstance(candidate.get(k), bool)
                  for k in ("x", "y", "w", "h"))
    if has_box:
        cx, cy = vision_point
        pad = _MATCH_TOLERANCE_PX
        return (candidate["x"] - pad <= cx <= candidate["x"] + candidate["w"] + pad
                and candidate["y"] - pad <= cy <= candidate["y"] + candidate["h"] + pad)
    cand_label = str(candidate.get("label") or "").strip()
    return bool(cand_label) and cand_label == str(label or "").strip()


def build_locate_anchor(step_no, step) -> str:
    pass
    selector = {}
    if isinstance(step, dict):
        cand = step.get("selector")
        if isinstance(cand, dict):
            selector = cand
    digest = json.dumps(selector, sort_keys=True, ensure_ascii=False,
                        default=str)
    fp = hashlib.sha1(digest.encode("utf-8")).hexdigest()[:16]
    action = str((step or {}).get("action") or "") if isinstance(step, dict) else ""
    return f"step{step_no}|{action}|{fp}"


def _cache_agrees(vision_point, cache_hit) -> bool:
    pass
    if not isinstance(cache_hit, dict):
        return False
    cx, cy = cache_hit.get("x"), cache_hit.get("y")
    if isinstance(cx, bool) or isinstance(cy, bool) \
            or not isinstance(cx, (int, float)) or not isinstance(cy, (int, float)):
        return False
    return abs(cx - vision_point[0]) <= _MATCH_TOLERANCE_PX \
        and abs(cy - vision_point[1]) <= _MATCH_TOLERANCE_PX


def consensus_score(vision, a11y_candidates, cache_hit) -> float:
    pass
    point = _vision_point(vision)
    if point is None:
        return 0.0
    try:
        base = float(vision.get("confidence") or 0.0)
    except (TypeError, ValueError):
        base = 0.0
    base = min(max(base, 0.0), 1.0)
    score = base * VISION_WEIGHT
    cands = a11y_candidates if isinstance(a11y_candidates, list) else []
    corroborated = False
    if any(_a11y_agrees(point, vision.get("label"), c) for c in cands):
        score += A11Y_AGREEMENT_BONUS
        corroborated = True
    if _cache_agrees(point, cache_hit):
        score += CACHE_AGREEMENT_BONUS
        corroborated = True
    if not corroborated:
        score = min(score, VISION_SINGLE_SOURCE_CAP)
    return min(score, 1.0)


def decide_visual_locate(vision, a11y_candidates, cache_hit,
                         thresholds: tuple | None = None) -> dict:
    pass
    hi, lo = thresholds if thresholds else parse_confidence_thresholds("")
    score = consensus_score(vision, a11y_candidates, cache_hit)
    if score >= hi:
        return {"tier": "direct", "action": "click", "score": score,
                "badge": "ai-located", "point": _vision_point(vision)}
    if score >= lo:
        return {"tier": "selfcheck", "action": "click_with_selfcheck",
                "score": score, "point": _vision_point(vision)}
    cands = [c for c in (a11y_candidates or []) if isinstance(c, dict)]
    label = str((vision or {}).get("label") or "").strip()
    hint = (f"视觉模型候选置信不足（{round(score, 2)}<{lo}），已放弃 AI 定位点击；"
            f"建议为该步改用确定性定位（testTag/resource-id/可见文本），"
            f"候选元素清单见证据（{len(cands)} 枚）")
    if label:
        hint = hint + f"。模型识别目标「{label}」未能与无障碍树吻合"
    return {"tier": "reject", "action": "reject", "score": score,
            "candidates": cands, "rewrite_hint": hint}


def post_selfcheck_decision(selfcheck_ok: bool, step_no, action: str,
                           score: float, vision=None,
                           a11y_candidates=None, cache_hit=None) -> dict:
    pass
    if selfcheck_ok:
        return {"action": "continue", "score": score}
    return {"action": "stop_fail_closed", "score": score,
            "detail": (f"第 {step_no} 步 {action} AI 视觉定位步内自检不一致"
                       f"（置信 {round(score, 2)} 中档区），已停步并落定位证据包供复核"),
            "evidence": build_locate_evidence_packet(
                step_no=step_no, action=action, score=score,
                vision=vision, a11y_candidates=a11y_candidates,
                cache_hit=cache_hit, screenshot_key=None)}


def build_locate_evidence_packet(step_no, action: str, score: float,
                                 vision, a11y_candidates, cache_hit,
                                 screenshot_key: str | None) -> dict:
    pass
    candidates = []
    if isinstance(vision, dict):
        candidates.append({"source": "vision", **vision})
    for c in (a11y_candidates or []):
        if isinstance(c, dict):
            candidates.append({"source": "a11y", **c})
    if isinstance(cache_hit, dict):
        candidates.append({"source": "cache", **cache_hit})
    return {"stepNo": step_no, "action": action,
            "score": round(float(score), 4),
            "screenshotKey": screenshot_key,
            "annotated": screenshot_key is not None,
            "candidates": candidates}
