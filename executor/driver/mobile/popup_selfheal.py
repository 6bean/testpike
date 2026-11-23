"""Popup-rule self-heal decision core (runtime consumer of approved rules).

The popup rule table reserves this runtime slot: rules only exist after a
human approves a learned draft (status=2), so consuming the confirmed set at
execution time adds no new write path. When a step fails on a locator error,
the caller (app_worker wiring) feeds each confirmed rule's detect clauses to
a screen-presence probe; this module decides which rule wins and turns its
dismiss record into a replayable step.

Kept deliberately pure (same posture as visual_fallback): no device calls, no
HTTP, no imports of execution machinery beyond the IR verb whitelist, which
is reused from actions_common — this file must never re-enumerate verbs.

Fail-closed rules:
- a dismiss action outside the ``app.*`` IR baseline vocabulary is refused;
- a rule without a usable detect/dismiss shape is skipped, not guessed at;
- a raising probe is treated as "no plan" (screen signal unreliable means the
  original step error stays the authoritative account).
"""
from __future__ import annotations

from typing import Callable, Optional

from actions_common import is_app_action, is_baseline_verb

# detect clause keys accepted from the stored rule JSONB shape
# ({"detect": {"text": ..., "selector": {...}, "selectors": [...]}}).
_DETECT_TEXT_KEYS = ("text",)
_DETECT_SELECTOR_KEYS = ("selector", "selectors")


def _clean_text(raw) -> str:
    return str(raw or "").strip()


def iter_detect_clauses(detect) -> list[dict]:
    """Normalise a detect block into ordered presence clauses.

    Each clause is either ``{"text": ...}`` or ``{"selector": {...}}``. The
    order is stable (text first, then the selector family in list order) so
    the first-hit semantics of the plan stay deterministic across runs.
    """
    if not isinstance(detect, dict):
        return []
    clauses: list[dict] = []
    for key in _DETECT_TEXT_KEYS:
        text = _clean_text(detect.get(key))
        if text:
            clauses.append({"text": text})
    for key in _DETECT_SELECTOR_KEYS:
        raw = detect.get(key)
        candidates = raw if isinstance(raw, list) else [raw]
        for cand in candidates:
            if isinstance(cand, dict) and cand:
                clauses.append({"selector": cand})
    return clauses


def dismiss_step_of(rule) -> Optional[dict]:
    """Build the replayable dismiss step for one rule row, or None.

    A row is honoured only when its dismiss action is a baseline ``app.*``
    IR verb and it carries a non-empty target locatable payload; the target
    merges into the step verbatim (this core does not translate locators —
    the adapter face owns that single source).
    """
    if not isinstance(rule, dict):
        return None
    body = rule.get("rule")
    if not isinstance(body, dict):
        return None
    dismiss = body.get("dismiss")
    if not isinstance(dismiss, dict):
        return None
    action = _clean_text(dismiss.get("action"))
    if not action or not is_app_action(action) or not is_baseline_verb(action):
        return None
    target = dismiss.get("target")
    if not isinstance(target, dict) or not target:
        return None
    return {"action": action, **target}


def find_selfheal_plan(rules, probe: Callable[[dict], bool]) -> Optional[dict]:
    """Return the first self-heal plan whose detect clause is present.

    ``probe`` answers "is this clause on screen right now" (the wiring binds
    it to the adapter's visibility check). Probing stops at the first hit.
    Rules whose dismiss record cannot become a legal step are skipped, and a
    failing probe means "no plan" so the caller keeps the original error.
    """
    if not rules:
        return None
    try:
        for rule in rules:
            step = dismiss_step_of(rule)
            if step is None:
                continue
            for clause in iter_detect_clauses(
                    (rule.get("rule") or {}).get("detect")):
                if probe(clause):
                    return {"ruleId": rule.get("id"),
                            "title": rule.get("title"),
                            "matchedClause": clause,
                            "dismissStep": step}
    except Exception:  # noqa: BLE001 — unreliable screen signal fails closed
        return None
    return None
