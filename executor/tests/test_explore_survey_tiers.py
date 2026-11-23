"""WE-S4 P2c: survey viewport tiers ride the envelope across to the step.

The core write side (SurveyEnvelopeTiers) attaches the declared tier list
onto element-candidates envelopes of survey sessions; these pins prove the
executor transfer point (explore_worker.build_script_doc) maps the envelope
field onto the step dict that webdriver's collection branch reads.
Additive contract: an envelope WITHOUT the field (or with an empty list)
keeps the legacy minimal step — webdriver then collects the default tier
only, byte-for-byte the pre-survey behaviour.
"""
from __future__ import annotations

from explore_worker import build_script_doc


def test_survey_tiers_forwarded_into_candidates_step() -> None:
    doc = build_script_doc({
        "kind": "explore-act",
        "action": "element-candidates",
        "url": "http://localhost:5173/demo",
        "viewportClasses": ["mobile", "tablet"],
    })
    step = doc["steps"][0]
    assert step["action"] == "element-candidates"
    assert step.get("viewportClasses") == ["mobile", "tablet"]


def test_absent_tiers_keeps_step_minimal() -> None:
    doc = build_script_doc({
        "kind": "explore-act",
        "action": "element-candidates",
        "url": "http://localhost:5173/demo",
    })
    assert "viewportClasses" not in doc["steps"][0]


def test_empty_tiers_list_keeps_step_minimal() -> None:
    doc = build_script_doc({
        "kind": "explore-act",
        "action": "element-candidates",
        "url": "http://localhost:5173/demo",
        "viewportClasses": [],
    })
    assert "viewportClasses" not in doc["steps"][0]


def test_other_actions_never_gain_tiers() -> None:
    doc = build_script_doc({
        "kind": "explore-act",
        "action": "page-snapshot",
        "url": "http://localhost:5173/demo",
        "viewportClasses": ["mobile"],
    })
    assert doc["steps"][0] == {"action": "snapshot"}
