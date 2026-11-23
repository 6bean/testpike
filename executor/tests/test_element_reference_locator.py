"""Element-reference locator resolution in the executor (WE batch S1, A2).

The dispatch envelope (03 protocol 2.3 trailing key ``elementAnchors``) carries
ledger anchor snapshots already ordered server-side (confirmed rows first,
strongest anchor first). The executor must try anchors strictly in that order —
re-scoring in Python would create a second ordering source (red line 6).

These unit tests drive ``WebDriverExecutor._locator`` with a fake page: no
browser is needed for the resolution logic itself; the live three-spectrum run
(hit / degrade / all-miss on a local target page) belongs to the verification
package.
"""
from __future__ import annotations

import pytest

from driver.webdriver import WebDriverExecutor


class FakeLocator:
    """Records probes; resolves when ``count`` is positive."""

    def __init__(self, page: "FakePage", key: str, count: int):
        self.page = page
        self.key = key
        self._count = count
        self.first = self

    def count(self) -> int:
        self.page.probes.append(self.key)
        return self._count


class FakePage:
    def __init__(self, counts: dict[str, int]):
        self.counts = counts
        self.probes: list[str] = []

    def _make(self, key: str) -> FakeLocator:
        return FakeLocator(self, key, self.counts.get(key, 0))

    def locator(self, selector: str) -> FakeLocator:
        return self._make(selector)

    def get_by_text(self, text, exact=False) -> FakeLocator:
        return self._make(f"text={text}")

    def get_by_role(self, role, name=None) -> FakeLocator:
        return self._make(f"role={role}:{name}")

    def get_by_placeholder(self, text) -> FakeLocator:
        return self._make(f"placeholder={text}")

    def get_by_alt_text(self, text) -> FakeLocator:
        return self._make(f"alt={text}")

    def get_by_test_id(self, id_) -> FakeLocator:
        return self._make(f"testid={id_}")


def _executor(element_anchors: dict | None) -> WebDriverExecutor:
    ex = WebDriverExecutor(headless=True, screenshots_dir=None)
    ex._element_anchors = element_anchors
    return ex


def _snapshot(name: str, anchors: list[dict], element_id: int = 7) -> dict:
    """One ledger row frozen for dispatch, keys mirroring ElementSnapshot."""
    return {"elements": {name: [{"elementId": element_id, "pageKey": "login",
                                 "state": 1, "confidence": 0.8,
                                 "anchors": anchors}]}}


def test_reference_target_tries_anchors_in_envelope_order():
    page = FakePage({"#gone": 0, "testid=login-btn": 1})
    ex = _executor(_snapshot("登录按钮", [
        {"type": "css", "value": "#gone", "strength": 0.6, "note": None},
        {"type": "testid", "value": "login-btn", "strength": 0.95, "note": None},
    ]))
    step = {"action": "click", "target": {"by": "element", "name": "登录按钮"}}
    locator = ex._locator(page, step)
    assert page.probes == ["#gone", "testid=login-btn"]
    assert locator.key == "testid=login-btn"


def test_all_anchor_miss_raises_with_names_and_maps_to_locator_error():
    page = FakePage({})
    ex = _executor(_snapshot("登录按钮", [
        {"type": "css", "value": "#a", "strength": 0.6, "note": None},
        {"type": "xpath", "value": "//b", "strength": 0.5, "note": None},
    ]))
    step = {"action": "click", "target": {"by": "element", "name": "登录按钮"}}
    with pytest.raises(ValueError) as err:
        ex._locator(page, step)
    detail = str(err.value)
    assert "登录按钮" in detail and "#a" in detail and "//b" in detail
    # plain-word locator failure wording maps to error_type=2 via _map_failure
    assert ex._map_failure(detail) == 2


def test_registered_but_empty_rows_fail_closed_in_plain_words():
    page = FakePage({})
    ex = _executor({"allowCandidate": False, "elements": {"提交按钮": []}})
    step = {"action": "click", "target": {"by": "element", "name": "提交按钮"}}
    with pytest.raises(ValueError) as err:
        ex._locator(page, step)
    assert "提交按钮" in str(err.value) and "未登记" in str(err.value)
    assert page.probes == []


def test_reference_without_envelope_fails_closed():
    page = FakePage({})
    ex = _executor(None)
    step = {"action": "click", "target": {"by": "element", "name": "登录按钮"}}
    with pytest.raises(ValueError) as err:
        ex._locator(page, step)
    assert "elementAnchors" in str(err.value)


def test_inline_targets_keep_working_unchanged():
    page = FakePage({"css=#ok": 1, "xpath=//ok": 1, "text=你好": 1,
                     "role=button:保存": 1})
    ex = _executor(None)
    assert ex._locator(page, {"action": "click",
                              "target": {"css": "#ok"}}).key == "#ok"
    assert ex._locator(page, {"action": "click",
                              "target": {"xpath": "//ok"}}).key == "xpath=//ok"
    assert ex._locator(page, {"action": "expect",
                              "target": {"text": "你好"}}).key == "text=你好"
    assert ex._locator(page, {"action": "click",
                              "target": {"role": "button:保存"}}).key == \
        "role=button:保存"
    assert page.probes == []  # inline path never probes the ledger


def test_rel_anchor_is_an_honest_miss_not_a_silent_pass():
    page = FakePage({})
    ex = _executor(_snapshot("验证码框", [
        {"type": "rel", "value": "right-of:登录按钮", "strength": 0.3,
         "note": None},
    ]))
    step = {"action": "click", "target": {"by": "element", "name": "验证码框"}}
    with pytest.raises(ValueError) as err:
        ex._locator(page, step)
    detail = str(err.value)
    assert "rel" in detail and "验证码框" in detail
    assert page.probes == []  # not resolvable standalone, nothing was tried


def test_rows_are_tried_in_envelope_order_before_giving_up():
    page = FakePage({"testid=second": 1})
    ex = _executor({"allowCandidate": False, "elements": {"登录按钮": [
        {"elementId": 1, "pageKey": "login", "state": 1, "confidence": 0.9,
         "anchors": [{"type": "css", "value": "#stale", "strength": 0.6,
                      "note": None}]},
        {"elementId": 2, "pageKey": "login", "state": 1, "confidence": 0.8,
         "anchors": [{"type": "testid", "value": "second", "strength": 0.95,
                      "note": None}]},
    ]}})
    step = {"action": "click", "target": {"by": "element", "name": "登录按钮"}}
    locator = ex._locator(page, step)
    assert page.probes == ["#stale", "testid=second"]
    assert locator.key == "testid=second"


# ---- per-attempt hit/miss report rows ----
# Every anchor attempt (hit or miss) produces one ledger row so the core-side
# stat table can recompute confidence from real outcomes. Row shape is the
# bilateral contract consumed by ExecResultInput.elementAnchorHits in 3b.


def _hits(ex) -> list[dict]:
    return ex._element_anchor_hits


def test_every_failed_attempt_records_one_row():
    page = FakePage({"#gone": 0, "testid=login-btn": 1})
    ex = _executor(_snapshot("登录按钮", [
        {"type": "css", "value": "#gone", "strength": 0.6, "note": None},
        {"type": "testid", "value": "login-btn", "strength": 0.95, "note": None},
    ], element_id=42))
    ex._locator(page, {"action": "click",
                       "target": {"by": "element", "name": "登录按钮"}})
    rows = _hits(ex)
    assert [(r["anchorType"], r["hit"], r["tried"]) for r in rows] == [
        ("css", False, True), ("testid", True, True)]
    assert all(r["elementId"] == 42 and r["name"] == "登录按钮"
               and r["viewport"] == "1440x900" for r in rows)


def test_hit_row_truncates_further_anchor_records():
    page = FakePage({"testid=first": 1, "testid=second": 0})
    ex = _executor(_snapshot("提交按钮", [
        {"type": "testid", "value": "first", "strength": 0.9, "note": None},
        {"type": "testid", "value": "second", "strength": 0.5, "note": None},
    ]))
    ex._locator(page, {"action": "click",
                       "target": {"by": "element", "name": "提交按钮"}})
    rows = _hits(ex)
    assert len(rows) == 1 and rows[0]["hit"] is True
    assert page.probes == ["testid=first"]  # later anchors never probed


def test_rel_anchor_row_is_recorded_as_not_tried():
    page = FakePage({"testid=captcha": 1})
    ex = _executor(_snapshot("验证码框", [
        {"type": "rel", "value": "right-of:登录按钮", "strength": 0.3,
         "note": None},
        {"type": "testid", "value": "captcha", "strength": 0.9, "note": None},
    ], element_id=9))
    ex._locator(page, {"action": "click",
                       "target": {"by": "element", "name": "验证码框"}})
    rows = _hits(ex)
    assert [(r["anchorType"], r["hit"], r["tried"]) for r in rows] == [
        ("rel", False, False), ("testid", True, True)]
    assert page.probes == ["testid=captcha"]  # rel stays unprobed


def test_all_miss_records_every_attempt_across_rows():
    page = FakePage({})
    ex = _executor({"allowCandidate": False, "elements": {"登录按钮": [
        {"elementId": 1, "pageKey": "login", "state": 1, "confidence": 0.9,
         "anchors": [{"type": "css", "value": "#a", "strength": 0.6,
                      "note": None},
                     {"type": "xpath", "value": "//b", "strength": 0.5,
                      "note": None}]},
        {"elementId": 2, "pageKey": "login", "state": 1, "confidence": 0.8,
         "anchors": [{"type": "testid", "value": "c", "strength": 0.7,
                      "note": None}]},
    ]}})
    with pytest.raises(ValueError):
        ex._locator(page, {"action": "click",
                           "target": {"by": "element", "name": "登录按钮"}})
    rows = _hits(ex)
    assert [(r["elementId"], r["anchorType"], r["hit"], r["tried"])
            for r in rows] == [
        (1, "css", False, True), (1, "xpath", False, True),
        (2, "testid", False, True)]


def test_run_outcome_roundtrip_omits_empty_key():
    from driver.webdriver import RunOutcome
    empty = RunOutcome(result="pass")
    assert "element_anchor_hits" not in empty.to_dict()
    row = {"elementId": 7, "name": "登录按钮", "viewport": "1440x900",
           "anchorType": "css", "hit": True, "tried": True}
    full = RunOutcome(result="fail", error_type=2, error_detail="x",
                      element_anchor_hits=[row])
    data = full.to_dict()
    assert data["element_anchor_hits"] == [row]
    back = RunOutcome.from_dict(data)
    assert back.element_anchor_hits == [row]


def test_overflow_above_two_hundred_lines_appends_sentinel_once():
    page = FakePage({})
    anchors = [{"type": "css", "value": f"#c{i}", "strength": 0.5, "note": None}
               for i in range(WebDriverExecutor._ANCHOR_HIT_REPORT_MAX + 5)]
    ex = _executor(_snapshot("登录按钮", anchors, element_id=3))
    step = {"action": "click", "target": {"by": "element", "name": "登录按钮"}}
    for _ in range(2):  # second resolution round must not duplicate the sentinel
        with pytest.raises(ValueError):
            ex._locator(page, step)
    rows = _hits(ex)
    sentinels = [r for r in rows if r["anchorType"] == "__truncated__"]
    assert len(rows) == WebDriverExecutor._ANCHOR_HIT_REPORT_MAX + 1
    assert len(sentinels) == 1
    assert sentinels[0]["hit"] is False and sentinels[0]["tried"] is False
