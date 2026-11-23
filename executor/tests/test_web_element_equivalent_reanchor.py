"""WE S2 equivalent re-anchor on total ledger-anchor miss (design 8.1/8.4).

Contract pinned here:
- 8.1 two-scale rule: A identity (same testid, or same visible text WITH the
 role recorded in the anchor note) AND B visual similarity >= the floor
 delivered on the dispatch envelope. Missing either scale must NEVER pass by
 default — A-hit-without-B files a repair suggestion instead.
- 8.4 evidence trailing keys: healed/firstAnchorFailed/fromAnchor/usedAnchor/
 viewport/ledgerRevision/similarityScore ride the existing result-envelope
 relay form (elementAnchorHits precedent, no second channel).
- Suggestion payload mirrors the WEB-shape record contract landed with
 (objectType=WEB, elementId, failureClass=2, no carried action).
- Unwired ports (no candidates / page without evaluate) keep the honest raise
 from unchanged — zero behaviour drift when the feature is off.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from driver.re_anchor import (HEAL_EVIDENCE_ROW_KEYS, build_evidence,
                              build_web_suggestion, candidate_anchor, decide,
                              identity_clues, match_candidate)
from driver.webdriver import WebDriverExecutor
from protocol import ExecutorProtocolClient

REPO_ROOT = Path(__file__).resolve().parents[2]


# ------------------------------------------------------------------ pure rules
def test_identity_clues_collect_testids_and_text_roles():
    clues = identity_clues([
        {"type": "css", "value": "#a", "strength": 0.6, "note": None},
        {"type": "testid", "value": "order-submit", "strength": 0.9, "note": None},
        {"type": "text", "value": "提交订单", "strength": 0.7, "note": "role=button"},
    ])
    assert clues["testids"] == ["order-submit"]
    assert clues["texts"] == [("提交订单", "button")]


def test_text_clue_role_stays_none_when_note_records_nothing():
    clues = identity_clues([{"type": "text", "value": "登录", "note": None}])
    assert clues["texts"] == [("登录", None)]


def test_match_candidate_via_testid_equivalence():
    clues = {"testids": ["order-submit"], "texts": []}
    cand = {"testid": "order-submit", "text": "提交", "role": "button"}
    assert match_candidate(clues, [cand]) is cand


def test_text_only_match_without_recorded_role_is_not_identity():
    # 8.1: same visible text AND same role — an unrecorded role can never be
    # proven, so the text branch stays fail-closed (no silent A-hit).
    clues = {"testids": [], "texts": [("登录", None)]}
    cand = {"testid": None, "text": "登录", "role": "button"}
    assert match_candidate(clues, [cand]) is None


def test_text_and_recorded_role_match_is_identity():
    clues = {"testids": [], "texts": [("登录", "button")]}
    cand = {"testid": None, "text": "登录", "role": "button"}
    assert match_candidate(clues, [cand]) is cand
    other = {"testid": None, "text": "登录", "role": "link"}
    assert match_candidate(clues, [other]) is None


def test_candidate_anchor_prefers_testid_over_text():
    assert candidate_anchor({"testid": "t1", "text": "登录", "role": "button"}) == \
        {"type": "testid", "value": "t1"}
    assert candidate_anchor({"testid": None, "text": "登录", "role": "button"}) == \
        {"type": "text", "value": "登录"}


def test_no_candidate_never_heals():
    assert decide(None, similarity=0.99, floor=0.85) is None


def test_missing_visual_baseline_proposes_never_passes():
    # 8.1 honest boundary: no comparable baseline = B missing = suggestion.
    assert decide({"testid": "t"}, similarity=None, floor=0.85) == "propose"


def test_missing_floor_proposes():
    # Floor rides the envelope; until core delivers it, direct heal is impossible.
    assert decide({"testid": "t"}, similarity=0.99, floor=None) == "propose"


def test_similarity_at_floor_directs_and_below_proposes():
    assert decide({"testid": "t"}, similarity=0.85, floor=0.85) == "direct"
    assert decide({"testid": "t"}, similarity=0.849, floor=0.85) == "propose"


def test_floor_one_empties_the_direct_set():
    # 8.5 judgement: dial the gate to 1.0 and no similarity below it heals.
    assert decide({"testid": "t"}, similarity=0.999, floor=1.0) == "propose"


def test_evidence_row_carries_84_trailing_keys():
    row = build_evidence(step_no=3, element_id=9,
                         from_anchor={"type": "css", "value": "#a"},
                         used_anchor={"type": "testid", "value": "t1"},
                         viewport="1440x900", ledger_revision=7, similarity=0.91)
    assert row["healed"] is True
    assert row["firstAnchorFailed"] is True
    assert row["elementId"] == 9
    assert row["stepNo"] == 3
    assert row["fromAnchor"] == {"type": "css", "value": "#a"}
    assert row["usedAnchor"] == {"type": "testid", "value": "t1"}
    assert row["viewport"] == "1440x900"
    assert row["ledgerRevision"] == 7
    assert row["similarityScore"] == 0.91
    # Row keys ride a single module constant — the Java writeback record and
    # the envelope contract gate read the same set (no second key list).
    assert set(row.keys()) == set(HEAL_EVIDENCE_ROW_KEYS)


def test_web_suggestion_payload_matches_record_contract_shape():
    payload = build_web_suggestion(task_id=41, step_no=2, element_id=7,
                                   from_anchor={"type": "css", "value": "#a"},
                                   used_anchor={"type": "testid", "value": "t1"},
                                   similarity=None, viewport="1440x900")
    assert payload["objectType"] == "WEB"
    assert payload["elementId"] == 7
    assert payload["taskId"] == 41
    assert payload["stepNo"] == 2
    assert payload["failureClass"] == 2
    # WEB rows re-point a locator only — a carried action is refused core-side.
    assert payload["proposedAction"] is None
    target = json.loads(payload["proposedTarget"])
    assert target == {"type": "testid", "value": "t1"}
    assert payload["anchor"] == "css=#a"
    assert payload["source"] == "a11y"


# ------------------------------------------------------------------ wiring
class HealLocator:
    def __init__(self, page, key, count):
        self.page = page
        self.key = key
        self.count_value = count
        self.first = self

    def count(self):
        self.page.probes.append(self.key)
        return self.count_value

    def screenshot(self):
        return b"PNG"


class HealPage:
    """Fake page exposing the candidate scan + screenshot the real driver gets."""

    def __init__(self, counts, candidates):
        self.counts = counts
        self.candidates = candidates
        self.probes: list[str] = []
        self.evaluated = 0

    def locator(self, selector):
        return HealLocator(self, selector, self.counts.get(selector, 0))

    def get_by_text(self, text, exact=False):
        return HealLocator(self, f"text={text}", self.counts.get(f"text={text}", 0))

    def get_by_role(self, role, name=None):
        return HealLocator(self, f"role={role}:{name}", 0)

    def get_by_placeholder(self, text):
        return HealLocator(self, f"placeholder={text}", 0)

    def get_by_alt_text(self, text):
        return HealLocator(self, f"alt={text}", 0)

    def get_by_test_id(self, id_):
        return HealLocator(self, f"testid={id_}", self.counts.get(f"testid={id_}", 0))

    def evaluate(self, script):
        self.evaluated += 1
        return self.candidates


def _binding(rows, floor=None):
    binding = {"elements": {"下单按钮": [rows]}}
    if floor is not None:
        binding["reAnchorSimFloor"] = floor
    return binding


def _row(anchors, element_id=7, revision=5):
    return {"elementId": element_id, "pageKey": "order", "state": 1,
            "confidence": 0.8, "revision": revision, "anchors": anchors}


def _executor(binding, compare=None):
    ex = WebDriverExecutor(headless=True, screenshots_dir=None)
    ex._element_anchors = binding
    ex._visual_compare = compare
    ex._suggestions = []
    return ex


FAILED_ANCHORS = [
    {"type": "css", "value": "#old-btn", "strength": 0.6, "note": None},
    {"type": "testid", "value": "order-submit", "strength": 0.9, "note": None},
]


def test_direct_heal_returns_candidate_locator_and_records_evidence():
    # Engine-direct dialect: the ledger probe rides get_by_test_id (0 hits,
    # e.g. attribute casing/selector-engine drift), the heal locator rides the
    # raw [data-testid=...] attribute query the DOM scan actually observed.
    heal_key = '[data-testid="order-submit"]'
    page = HealPage({"css=#old-btn": 0, "testid=order-submit": 0, heal_key: 1},
                    candidates=[{"testid": "order-submit",
                                 "text": "提交订单", "role": "button"}])
    ex = _executor(_binding(_row(FAILED_ANCHORS), floor=0.85),
                   compare=lambda eid, png: 0.93)
    ex._current_step_no = 4
    step = {"action": "click", "target": {"by": "element", "name": "下单按钮"}}
    loc = ex._locator(page, step)
    assert loc.key == heal_key
    assert len(ex._heal_evidence) == 1
    ev = ex._heal_evidence[0]
    assert ev["healed"] is True and ev["similarityScore"] == 0.93
    assert ev["elementId"] == 7
    assert ev["stepNo"] == 4 and ev["ledgerRevision"] == 5
    assert ev["fromAnchor"]["type"] == "css"
    assert ex._suggestions == []


def test_missing_baseline_files_suggestion_and_still_raises():
    # A hits and the heal locator is reachable, but B is unprovable (no
    # compare port) → suggestion, step fails.
    page = HealPage({'[data-testid="order-submit"]': 1},
                    candidates=[{"testid": "order-submit",
                                 "text": "提交订单", "role": "button"}])
    ex = _executor(_binding(_row(FAILED_ANCHORS), floor=0.85))
    step = {"action": "click", "target": {"by": "element", "name": "下单按钮"}}
    with pytest.raises(ValueError) as err:
        ex._locator(page, step)
    assert "下单按钮" in str(err.value)
    assert len(ex._suggestions) == 1
    assert ex._suggestions[0]["objectType"] == "WEB"


def test_no_equivalent_candidate_keeps_plain_honest_raise():
    page = HealPage({}, candidates=[])
    ex = _executor(_binding(_row(FAILED_ANCHORS), floor=0.85))
    step = {"action": "click", "target": {"by": "element", "name": "下单按钮"}}
    with pytest.raises(ValueError) as err:
        ex._locator(page, step)
    assert "等待修复建议单或人工重锚" in str(err.value)
    assert ex._suggestions == [] and ex._heal_evidence == []


class NoScanPage:
    """Page double without evaluate: the scan cannot run, nothing is invented."""

    def __init__(self):
        self.probes: list[str] = []

    def _miss(self, key):
        self.probes.append(key)
        return HealLocator(self, key, 0)

    def locator(self, selector):
        return self._miss(selector)

    def get_by_text(self, text, exact=False):
        return self._miss(f"text={text}")

    def get_by_role(self, role, name=None):
        return self._miss(f"role={role}:{name}")

    def get_by_placeholder(self, text):
        return self._miss(f"placeholder={text}")

    def get_by_alt_text(self, text):
        return self._miss(f"alt={text}")

    def get_by_test_id(self, id_):
        return self._miss(f"testid={id_}")


def test_page_without_scan_support_fails_closed():
    # Defensive: an environment that cannot answer the candidate scan must
    # behave exactly like (no crash, no invented candidate).
    ex = _executor(_binding(_row(FAILED_ANCHORS), floor=0.85))
    step = {"action": "click", "target": {"by": "element", "name": "下单按钮"}}
    with pytest.raises(ValueError):
        ex._locator(NoScanPage(), step)
    assert ex._suggestions == [] and ex._heal_evidence == []


# ------------------------------------------------------------------ relay
def _spy_client():
    client = ExecutorProtocolClient("http://127.0.0.1:1/internal")
    captured = {}

    def _fake_post(path, payload):
        captured["path"] = path
        captured["payload"] = payload
        return {}

    client._post = _fake_post
    return client, captured


def test_submit_step_suggestion_posts_record_endpoint():
    client, captured = _spy_client()
    client.submit_step_suggestion({"taskId": 1, "objectType": "WEB"})
    assert captured["path"] == "/internal/appauto/step-suggestions/record"
    assert captured["payload"]["objectType"] == "WEB"


def test_result_envelope_trails_step_heal_evidence_key():
    client, captured = _spy_client()
    rows = [{"healed": True, "stepNo": 1}]
    client.submit_result(11, "tok", "pass", step_heal_evidence=rows)
    assert captured["payload"]["stepHealEvidence"] == rows
    client.submit_result(11, "tok", "pass", step_heal_evidence=[])
    assert "stepHealEvidence" not in captured["payload"]


def test_run_outcome_roundtrip_keeps_heal_evidence_and_suggestions():
    from driver.webdriver import RunOutcome
    row = {"healed": True, "stepNo": 2}
    sug = {"objectType": "WEB", "elementId": 7}
    full = RunOutcome(result="fail", error_type=2, error_detail="x",
                      heal_evidence=[row], step_suggestions=[sug])
    data = full.to_dict()
    assert data["heal_evidence"] == [row] and data["step_suggestions"] == [sug]
    back = RunOutcome.from_dict(data)
    assert back.heal_evidence == [row] and back.step_suggestions == [sug]
    assert "heal_evidence" not in RunOutcome(result="pass").to_dict()
    assert "step_suggestions" not in RunOutcome(result="pass").to_dict()


def test_runner_relays_heal_evidence_and_flushes_suggestions():
    source = (REPO_ROOT / "executor" / "runner.py").read_text(encoding="utf-8")
    assert "step_heal_evidence=outcome.heal_evidence or None" in source
    assert "submit_step_suggestion(" in source
