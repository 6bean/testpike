
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app_worker import _try_popup_selfheal  # noqa: E402


class FakeAdapter:
    """Scripted engine face: pops one canned result per execute call."""

    def __init__(self, script):
        self.script = list(script)
        self.calls = []

    def execute(self, step):
        self.calls.append(dict(step))
        return self.script.pop(0)


def _rule(rule_id=41, title="force-stop confirm", detect="Cancel",
          action="app.tap", target=None):
    return {
        "id": rule_id,
        "title": title,
        "rule": {
            "detect": {"text": detect},
            "dismiss": {"action": action,
                        "target": target or {"text": detect}},
        },
    }


def _row(step_no=2, detail="element not found"):
    return {"stepNo": step_no, "action": "app.assertVisible",
            "result": "fail", "detail": detail}


def _envelope(rules):
    return {"kind": "app-auto-run", "popupRules": rules}


_STEP = {"action": "app.assertVisible", "text": "Storage & cache"}
_LOCATOR = 2  # errorType value for locator errors (app_worker single source)


def test_non_locator_error_never_consults_rules():
    adapter = FakeAdapter([])
    row = _row()
    assert _try_popup_selfheal(adapter, _STEP, row, _envelope([_rule()]),
                               1, "assertVisible", "/tmp") is False
    assert adapter.calls == []
    assert "popupSelfheal" not in row


def test_missing_or_invalid_rules_pass_through_silently():
    for envelope in ({"kind": "app-auto-run"}, _envelope("not-a-list"),
                     _envelope([])):
        adapter = FakeAdapter([])
        row = _row()
        assert _try_popup_selfheal(adapter, _STEP, row, envelope,
                                   _LOCATOR, "assertVisible", "/tmp") is False
        assert adapter.calls == []
        assert row["detail"] == "element not found"


def test_no_matching_rule_keeps_original_error_untouched():
    adapter = FakeAdapter([{"result": "fail"}])  # probe: clause absent
    row = _row()
    assert _try_popup_selfheal(adapter, _STEP, row, _envelope([_rule()]),
                               _LOCATOR, "assertVisible", "/tmp") is False
    assert row["result"] == "fail"
    assert row["detail"] == "element not found"
    # the probe must ride the adapter's own visibility verb, never a dump parser
    assert adapter.calls[0]["action"] == "app.assertVisible"
    assert adapter.calls[0]["text"] == "Cancel"


def test_healed_step_flips_to_pass_with_ledger_attribution():
    adapter = FakeAdapter([
        {"result": "pass"},   # probe sees the dialog
        {"result": "pass"},   # dismiss tap
        {"result": "pass"},   # original step retried
    ])
    row = _row()
    ok = _try_popup_selfheal(adapter, _STEP, row, _envelope([_rule()]),
                             _LOCATOR, "assertVisible", "/tmp")
    assert ok is True
    assert row["result"] == "pass"
    mark = row["popupSelfheal"]
    assert mark["ruleId"] == 41 and mark["title"] == "force-stop confirm"
    assert mark["matchedClause"] == {"text": "Cancel"}
    # dismiss replay carries the rule target verbatim (no locator translation)
    assert adapter.calls[1]["text"] == "Cancel"
    assert adapter.calls[2] == dict(_STEP, verb="assertVisible",
                                    artifactDir="/tmp")


def test_dismiss_failure_keeps_fail_and_notes_rule():
    adapter = FakeAdapter([{"result": "pass"}, {"result": "fail",
                                             "detail": "tap blocked"}])
    row = _row()
    assert _try_popup_selfheal(adapter, _STEP, row, _envelope([_rule()]),
                               _LOCATOR, "assertVisible", "/tmp") is False
    assert row["result"] == "fail"
    assert "自愈" in row["detail"] and "element not found" in row["detail"]


def test_retry_failure_after_dismiss_keeps_fail():
    adapter = FakeAdapter([{"result": "pass"}, {"result": "pass"},
                           {"result": "fail", "detail": "still missing"}])
    row = _row()
    assert _try_popup_selfheal(adapter, _STEP, row, _envelope([_rule()]),
                               _LOCATOR, "assertVisible", "/tmp") is False
    assert row["result"] == "fail"
    assert "popupSelfheal" not in row


def test_selector_clause_binds_selector_payload():
    rule = {"id": 42, "title": "overlay",
            "rule": {"detect": {"selectors": [{"resourceId": "ok"}]},
                     "dismiss": {"action": "app.back", "target": {}}}}
    # dismiss target {} -> dismiss_step_of rejects (needs non-empty payload),
    # so this rule must be skipped without probing (fail-closed core wins).
    adapter = FakeAdapter([])
    row = _row()
    assert _try_popup_selfheal(adapter, _STEP, row, _envelope([rule]),
                               _LOCATOR, "assertVisible", "/tmp") is False
    assert adapter.calls == []


def test_raising_probe_fails_closed_no_note():
    class Boom(FakeAdapter):
        def execute(self, step):
            raise RuntimeError("dump unavailable")

    row = _row()
    assert _try_popup_selfheal(Boom([]), _STEP, row, _envelope([_rule()]),
                               _LOCATOR, "assertVisible", "/tmp") is False
    assert row["result"] == "fail"
    assert row["detail"] == "element not found"
