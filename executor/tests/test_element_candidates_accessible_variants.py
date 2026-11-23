"""Survey collection: accessible-name anchors and cross-viewport variants.

Pure-function / fake-page L2 for the element-ledger survey collection
extension (WE batch S4 piece P1): candidates carry the role/accessible-name
signal, emitted role targets use the exact "role:accessible-name" dialect
that WebDriverExecutor._locator already parses (routing proven on a stub
page below), and a declared viewport-class list replays the collection once
per class into a variants array. No viewport classes declared = default
tier only, variants empty — honest absence, never a guess. The real-browser
survey spec rides the acceptance package.
"""
from __future__ import annotations

import json

from driver.element_candidates import (
    SURVEY_MAX_DETAIL_CHARS,
    VIEWPORT_CLASS_SIZES,
    collect_element_candidates,
    serialize_candidates,
)
from driver.webdriver import WebDriverExecutor


def _base(**over):
    item = {"tag": "button", "id": None, "testAttr": None, "testId": None,
            "name": None, "placeholder": None, "text": "登录", "visible": True,
            "roleAttr": None, "ariaLabel": None, "labelledbyText": None,
            "labelText": None, "altAttr": None, "inputType": None,
            "hasHref": False}
    item.update(over)
    return item


class TestAccessibleNameTargets:
    """role:NAME target form ⇄ driver dialect single source."""

    def test_aria_label_wins_into_role_dialect_target(self) -> None:
        parsed = json.loads(serialize_candidates(
            [_base(roleAttr="button", ariaLabel="提交登录", text="登录")]))
        row = parsed["candidates"][0]
        assert row["target"] == {"role": "button:提交登录"}
        assert row["role"] == "button" and row["accName"] == "提交登录"

    def test_implicit_role_from_tag_with_content_name(self) -> None:
        # No role attribute: <button> implies role=button, own text is the
        # content name (ARIA name-from-content roles only).
        parsed = json.loads(serialize_candidates([_base(text="登录")]))
        assert parsed["candidates"][0]["target"] == {"role": "button:登录"}

    def test_link_role_requires_href_bare_anchor_falls_back(self) -> None:
        parsed = json.loads(serialize_candidates(
            [_base(tag="a", roleAttr=None, text="详情", hasHref=False)]))
        # <a> without href has no implicit role → old text target, zero drift
        assert parsed["candidates"][0]["target"] == {"text": "详情"}

    def test_labelledby_label_alt_signal_priority(self) -> None:
        parsed = json.loads(serialize_candidates([_base(
            tag="input", roleAttr=None, text="", inputType="text",
            labelledbyText="用户名", labelText="账号", altAttr=None)]))
        row = parsed["candidates"][0]
        assert row["target"] == {"role": "textbox:用户名"}
        parsed2 = json.loads(serialize_candidates([_base(
            tag="input", text="", inputType="text",
            labelText="账号", placeholder="请输入")]))
        assert parsed2["candidates"][0]["target"] == {"role": "textbox:账号"}

    def test_id_and_testid_priority_stays_ahead_of_role(self) -> None:
        parsed = json.loads(serialize_candidates([_base(
            id="login-btn", roleAttr="button", ariaLabel="提交登录")]))
        assert parsed["candidates"][0]["target"] == {"css": "#login-btn"}

    def test_emitted_role_target_resolves_through_locator(self) -> None:
        # Bilateral pin: every role target this collector emits must parse in
        # WebDriverExecutor._locator without a second dialect (honesty nail).
        parsed = json.loads(serialize_candidates(
            [_base(roleAttr="button", ariaLabel="提交登录")]))
        target = parsed["candidates"][0]["target"]
        calls = []

        class _StubPage:
            def get_by_role(self, role, name=None):
                calls.append((role, name))
                return "role-locator"

        ex = WebDriverExecutor.__new__(WebDriverExecutor)
        loc = ex._locator(_StubPage(), {"target": target})
        assert loc == "role-locator"
        assert calls == [("button", "提交登录")]


class _FakePage:
    """Deterministic evaluate queue + viewport mutation log (L2 fake page;
    the real-browser survey proof rides the acceptance spec)."""

    def __init__(self, batches):
        self._batches = list(batches)
        self.sizes = []
        self.viewport_size = {"width": 1440, "height": 900}

    def evaluate(self, _js):
        return self._batches.pop(0)

    def set_viewport_size(self, size):
        self.sizes.append((size["width"], size["height"]))


class TestViewportVariants:
    """Declared classes replay collection; undeclared = empty variants."""

    def test_no_declared_classes_keeps_single_tier_and_empty_variants(self) -> None:
        page = _FakePage([[_base()]])
        parsed = json.loads(collect_element_candidates(page, ()))
        assert parsed["variants"] == []
        assert parsed["candidates"][0]["target"] == {"role": "button:登录"}
        assert page.sizes == []  # no viewport mutation when nothing declared

    def test_per_class_replay_assembles_variants_and_restores_viewport(self) -> None:
        mobile = _base(ariaLabel="移动端登录")
        desktop = _base(ariaLabel="桌面端登录")
        page = _FakePage([[_base()], [mobile], [desktop]])
        parsed = json.loads(
            collect_element_candidates(page, ("mobile", "desktop")))
        assert [v["viewport"] for v in parsed["variants"]] == ["mobile",
                                                               "desktop"]
        assert parsed["variants"][0]["candidates"][0]["target"] == \
            {"role": "button:移动端登录"}
        assert parsed["variants"][1]["candidates"][0]["target"] == \
            {"role": "button:桌面端登录"}
        assert page.sizes == [VIEWPORT_CLASS_SIZES["mobile"],
                              VIEWPORT_CLASS_SIZES["desktop"],
                              (1440, 900)]  # restored after the sweep

    def test_unknown_class_skipped_honestly_never_silent(self) -> None:
        page = _FakePage([[_base()]])
        parsed = json.loads(collect_element_candidates(page, ("flip-phone",)))
        assert parsed["variants"] == []
        assert parsed["skippedViewportClasses"] == ["flip-phone"]

    def test_survey_budget_trims_tail_variants_with_flag(self) -> None:
        big = [_base(tag="button", ariaLabel=f"按钮{i}" + "字" * 40)
               for i in range(300)]
        page = _FakePage([big, big, big, big])  # default tier + 3 classes
        text = collect_element_candidates(page, ("mobile", "tablet", "desktop"))
        parsed = json.loads(text)
        assert len(text) <= SURVEY_MAX_DETAIL_CHARS
        assert parsed["variantsTruncated"] is True
        assert len(parsed["variants"]) < 3

    def test_collection_failure_stays_fail_soft(self) -> None:
        class _Boom:
            viewport_size = None

            def evaluate(self, _js):
                raise RuntimeError("page closed")
        out = collect_element_candidates(_Boom(), ("mobile",))
        assert out.startswith("元素候选采集失败")
