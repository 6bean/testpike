"""Equivalent re-anchor decision rules (WE S2 , design 8.1 / 8.4).

Pure helpers, zero I/O: the webdriver hook supplies page facts, this module
only decides. Two orthogonal scales gate a direct heal (8.1):
- A identity: same testid, or same visible text WITH the role recorded in the
 anchor note ("role=button" form). An unrecorded role can never be proven at
 runtime, so the text branch stays fail-closed instead of guessing.
- B visual similarity from the ledger baseline compare (>= envelope floor).
Missing either scale never passes by default — an A-hit without B files a
repair suggestion (WEB shape, contract) for the human/auto adoption
chain instead. Single sources respected: comparison math lives in the core
baseline service (B7), ordering/scoring stays core-side; this module only
matches recorded identity values.
"""
from __future__ import annotations

import json
import re
from typing import Any, Optional

# Anchor note records the ledger element's accessible role this dialect;
# anything else leaves the role evidence missing (text branch cannot prove A).
_ROLE_NOTE_RE = re.compile(r"role=([A-Za-z][A-Za-z0-9_-]*)")


def identity_clues(anchors: list[dict] | None) -> dict[str, Any]:
    """Extract the strong-anchor identity values a candidate must reproduce."""
    testids: list[str] = []
    texts: list[tuple[str, Optional[str]]] = []
    for anchor in anchors or []:
        a_type = str(anchor.get("type") or "")
        value = str(anchor.get("value") or "")
        if not value:
            continue
        if a_type == "testid":
            testids.append(value)
        elif a_type == "text":
            match = _ROLE_NOTE_RE.search(str(anchor.get("note") or ""))
            texts.append((value, match.group(1) if match else None))
    return {"testids": testids, "texts": texts}


def match_candidate(clues: dict[str, Any],
                    candidates: list[dict] | None) -> Optional[dict]:
    """First candidate proving scale A (testid, or text AND recorded role)."""
    for cand in candidates or []:
        if not isinstance(cand, dict):
            continue
        if cand.get("testid") and cand["testid"] in clues.get("testids", []):
            return cand
        for value, recorded_role in clues.get("texts", []):
            if (cand.get("text") == value and recorded_role is not None
                    and cand.get("role") == recorded_role):
                return cand
    return None


def candidate_anchor(candidate: dict) -> Optional[dict]:
    """Ledger-anchor form for a matched candidate (testid beats text)."""
    if candidate.get("testid"):
        return {"type": "testid", "value": str(candidate["testid"])}
    if candidate.get("text"):
        return {"type": "text", "value": str(candidate["text"])}
    return None


def decide(candidate: Optional[dict], similarity: Optional[float],
           floor: Optional[float]) -> Optional[str]:
    """"direct" only with both scales; "propose" when A hits but B is unproven."""
    if candidate is None:
        return None
    if (similarity is not None and floor is not None
            and similarity >= floor):
        return "direct"
    return "propose"


# 8.4 evidence row key set — single source for the executor envelope and the
# core stepHealEvidence writeback record (bilateral gate reads this tuple).
HEAL_EVIDENCE_ROW_KEYS = ("healed", "firstAnchorFailed", "elementId",
                         "stepNo", "fromAnchor", "usedAnchor", "viewport",
                         "ledgerRevision", "similarityScore")


def build_evidence(step_no: int, element_id: Optional[int], from_anchor: dict,
                   used_anchor: dict, viewport: str,
                   ledger_revision: Optional[int],
                   similarity: Optional[float]) -> dict:
    """8.4 trailing-key row: what failed, what healed, at what evidence."""
    return {
        "healed": True,
        "firstAnchorFailed": True,
        "elementId": element_id,
        "stepNo": step_no,
        "fromAnchor": {"type": str(from_anchor.get("type") or ""),
                       "value": str(from_anchor.get("value") or "")},
        "usedAnchor": {"type": str(used_anchor.get("type") or ""),
                       "value": str(used_anchor.get("value") or "")},
        "viewport": viewport,
        "ledgerRevision": ledger_revision,
        "similarityScore": similarity,
    }


def build_web_suggestion(task_id: Optional[int], step_no: int,
                         element_id: Optional[int], from_anchor: dict,
                         used_anchor: dict, similarity: Optional[float],
                         viewport: str) -> dict:
    """WEB-shape payload for /internal/appauto/step-suggestions/record.

 contract: no carried action (locator re-point only), ledger entry
 id required, failureClass fixed at 2 (UI change suspect — the only class
 the suggestion board accepts). taskId rides at flush time from the runner
 when the driver instance does not know it.
 """
    if similarity is None:
        reason = ("全部锚失手后扫到等价强锚候选，但无视觉基线可比（8.1 禁默认通过）"
                  f"，视口 {viewport}；待采纳升锚")
    else:
        reason = (f"全部锚失手后扫到等价强锚候选，视觉相似度 {similarity} 未达直更闸"
                  f"，视口 {viewport}；待采纳升锚")
    return {
        "taskId": task_id,
        "stepNo": step_no,
        "failureClass": 2,
        "anchor": f"{from_anchor.get('type')}={from_anchor.get('value')}",
        "source": "a11y",
        "proposedAction": None,
        "proposedTarget": json.dumps(used_anchor, ensure_ascii=False),
        "reason": reason,
        "x": None,
        "y": None,
        "objectType": "WEB",
        "elementId": element_id,
    }
