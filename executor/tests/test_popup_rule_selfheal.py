"""Popup-rule self-heal decision core (runtime consumption of approved rules).

Covers the runtime slot reserved by the popup rule table: a step that fails
with a locator error consults confirmed rules (status=2, human-approved only)
and, when one matches the current screen, replays the recorded dismiss verb.
This module is the single deterministic decision point: zero IO, zero device
calls. The screen-presence probe and the adapter execution stay on the
caller side (wiring), so the whole judgement can be proven at L2.

Contract highlights:
- a rule is only honoured when its dismiss action is an ``app.*`` IR baseline
  verb (whitelist imported from actions_common, never re-enumerated here);
- malformed rules are skipped without raising (fail-closed: a broken rule may
  not execute arbitrary steps, and it must not break the honest original error);
- the first rule whose detect clause is probed present wins (server supplies
  the deterministic ordering; this core keeps the list order as given).
"""
from __future__ import annotations

import pytest

from driver.mobile.popup_selfheal import (
    dismiss_step_of,
    find_selfheal_plan,
    iter_detect_clauses,
)


def _rule(rule_id=7, title="permission dialog", text="ALLOW",
          action="app.tap", target=None):
    return {
        "id": rule_id,
        "title": title,
        "rule": {
            "detect": {"text": text},
            "dismiss": {"action": action,
                        "target": target if target is not None
                        else {"text": text}},
        },
    }


# ---------- detect clause normalisation ----------

def test_detect_text_single_clause():
    assert iter_detect_clauses({"text": "ALLOW"}) == [{"text": "ALLOW"}]


def test_detect_selector_family_expands_in_order():
    clauses = iter_detect_clauses(
        {"text": "ALLOW", "selectors": [{"id": "ok"}, {"contentDesc": "agree"}]})
    assert clauses == [{"text": "ALLOW"},
                       {"selector": {"id": "ok"}},
                       {"selector": {"contentDesc": "agree"}}]


def test_detect_missing_or_empty_yields_nothing():
    assert iter_detect_clauses(None) == []
    assert iter_detect_clauses({}) == []
    assert iter_detect_clauses({"text": "  "}) == []


# ---------- dismiss step validation (IR verb whitelist, single source) ----------

def test_dismiss_step_accepts_baseline_verb_and_merges_target():
    step = dismiss_step_of(_rule())
    assert step is not None
    assert step["action"] == "app.tap"
    assert step["text"] == "ALLOW"


def test_dismiss_step_rejects_non_baseline_verb():
    # The verb list is owned by actions_common; a rule naming a verb outside
    # the IR baseline must never turn into an executed step.
    assert dismiss_step_of(_rule(action="app.evilVerb")) is None


def test_dismiss_step_rejects_missing_prefix_or_target():
    assert dismiss_step_of(_rule(action="tap")) is None
    broken = _rule()
    del broken["rule"]["dismiss"]["target"]
    assert dismiss_step_of(broken) is None
    assert dismiss_step_of({"id": 1, "rule": {"detect": {"text": "x"}}}) is None


# ---------- plan selection (probe-driven, first hit wins) ----------

def test_plan_none_when_no_rules():
    assert find_selfheal_plan([], probe=lambda c: True) is None
    assert find_selfheal_plan(None, probe=lambda c: True) is None


def test_plan_picks_first_rule_with_present_clause():
    probed: list[dict] = []

    def probe(clause: dict) -> bool:
        probed.append(clause)
        return clause == {"text": "ALLOW"}

    plan = find_selfheal_plan([_rule(rule_id=7), _rule(rule_id=8, text="DENY")],
                              probe=probe)
    assert plan is not None
    assert plan["ruleId"] == 7
    assert plan["matchedClause"] == {"text": "ALLOW"}
    assert plan["dismissStep"] == dismiss_step_of(_rule(rule_id=7))
    # The second rule must not be probed after the first one already hit.
    assert probed == [{"text": "ALLOW"}]


def test_plan_skips_malformed_rules_and_keeps_searching():
    rules = [_rule(rule_id=9, action="app.notAVerb"), _rule(rule_id=10)]
    plan = find_selfheal_plan(rules, probe=lambda c: True)
    assert plan is not None and plan["ruleId"] == 10


def test_plan_none_when_no_probe_hits():
    plan = find_selfheal_plan([_rule(), _rule(rule_id=11, text="OTHER")],
                              probe=lambda c: False)
    assert plan is None


def test_probe_exception_fails_closed_to_no_plan():
    # A raising probe means the screen signal is unreliable: the caller must
    # keep the original error untouched, so the core reports "no plan".
    def probe(clause: dict) -> bool:
        raise RuntimeError("screen dump unavailable")

    assert find_selfheal_plan([_rule()], probe=probe) is None
