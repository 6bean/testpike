
from __future__ import annotations

import pathlib
import sys
from typing import ClassVar

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from actions_common import IR_VERBS
from driver.mobile.contracts import capability_violations
from driver.mobile.engine_factory import mobile_engine_factory

V145_MINIUM_DESCRIPTOR = {
    "code": "MINIUM",
    "status": 1,
    "runtimeType": "PY_INPROC",
    "platformCodes": ["MP"],
    "deviceKindCodes": ["DEVTOOLS_IDE", "EMULATOR", "SIMULATOR", "REAL"],
}

ENV_PROFILE = {"appid": "wxdemo0001", "projectPath": "/tmp/wx-demo",
               "cliPath": "/opt/wxcli", "autoPort": 9420}



class FakeElement:
    def __init__(self, session, selector, text="欢迎"):
        self.session = session
        self.selector = selector
        self._text = text
        self.taps = 0
        self.long_presses = 0
        self.inputs: list = []
        self.data_sets: list = []
        self.scrolled = 0

    def tap(self):
        self.taps += 1
        self.session.calls.append(("element.tap", self.selector))

    def long_press(self, duration=None):
        self.long_presses += 1
        self.session.calls.append(("element.long_press", duration))

    def input(self, text):
        self.inputs.append(text)
        self.session.calls.append(("element.input", text))

    def data(self, value):
        self.data_sets.append(value)
        self.session.calls.append(("element.data", value))

    def inner_text(self):
        self.session.calls.append(("element.inner_text", self.selector))
        return self._text

    def scroll_to(self):
        self.scrolled += 1
        self.session.calls.append(("element.scroll_to", self.selector))


class FakeApp:
    def __init__(self, session):
        self.session = session

    def release(self):
        self.session.calls.append(("app.release", None))

    def shutdown(self):
        self.session.calls.append(("app.shutdown", None))


class FakePage:
    pass

    wxml = "<view id='root'><text>欢迎</text></view>"
    data: ClassVar[dict] = {"greeting": "hello"}


class FakeSession:
    pass

    def __init__(self, conf: dict):
        self.conf = conf
        self.calls: list[tuple] = []
        self.app = FakeApp(self)
        self.missing_selectors: set = set()
        self._elements: dict = {}
        self.screenshot_paths: list = []
        self.closed = False
        self.page = FakePage()

    def _element(self, selector):
        if selector in self.missing_selectors:
            raise RuntimeError(f"找不到元素 {selector}")
        el = self._elements.get(selector) or FakeElement(self, selector)
        self._elements[selector] = el
        return el

    def get_element(self, candidates, raise_exception=True, **kwargs):
        self.calls.append(("get_element", candidates))
        sel = str(candidates)
        if sel in self.missing_selectors:
            if raise_exception:
                raise RuntimeError(f"找不到元素 {sel}")
            return None
        return self._element(sel)

    def reLaunch(self, url):
        self.calls.append(("reLaunch", url))

    def navigateBack(self, delta=1):
        self.calls.append(("navigateBack", delta))

    def slide(self, x1, y1, x2, y2, duration=None):
        self.calls.append(("slide", (x1, y1, x2, y2)))

    def wait_for(self, selector, timeout=10000):
        self.calls.append(("wait_for", selector))
        if selector in self.missing_selectors:
            return None
        return self._element(selector)

    def screenshot(self, path=""):
        self.calls.append(("screenshot", path))
        self.screenshot_paths.append(path)
        pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
        pathlib.Path(path).write_bytes(b"\x89PNG-fake")
        return path


@pytest.fixture()
def fake_minium(monkeypatch):
    pass
    from driver.mobile import minium_adapter

    created: list = []

    def fake_load():
        def factory(conf):
            session = FakeSession(conf)
            created.append(session)
            return session
        return type("minium_fake", (), {"Minium": staticmethod(factory)})()

    monkeypatch.setattr(minium_adapter, "_load_minium", fake_load)
    return created


def _prepared_adapter(fake_minium, ctx=None):
    adapter = mobile_engine_factory(dict(V145_MINIUM_DESCRIPTOR))
    adapter.prepare(dict({"envProfile": dict(ENV_PROFILE),
                          "leaseRef": {"deviceId": 7, "serial": "devtools-1"},
                          "runId": "r1"}, **(ctx or {})))
    return adapter



def test_factory_loads_real_minium_adapter():
    adapter = mobile_engine_factory(dict(V145_MINIUM_DESCRIPTOR))
    from driver.mobile.minium_adapter import MiniumAdapter
    assert isinstance(adapter, MiniumAdapter)
    assert type(adapter).__name__ != "StubEngineAdapter"


def test_adapter_self_description_narrowed_to_devtools_emulator():
    from driver.mobile.minium_adapter import MiniumAdapter
    assert MiniumAdapter.SUPPORTED_PLATFORMS == ("MP",)
    assert set(MiniumAdapter.SUPPORTED_DEVICE_KINDS) == {"DEVTOOLS_IDE", "EMULATOR"}
    assert tuple(MiniumAdapter.CAPABILITIES) == tuple(IR_VERBS)


def test_descriptor_capability_reconciliation_clean():
    from driver.mobile.minium_adapter import MiniumAdapter
    adapter = MiniumAdapter(descriptor=dict(V145_MINIUM_DESCRIPTOR))
    assert capability_violations(adapter, V145_MINIUM_DESCRIPTOR) is None



def test_prepare_rejects_missing_env_profile_in_plain_words(fake_minium, monkeypatch):
    from driver.mobile.minium_adapter import MiniumAdapter
    adapter = MiniumAdapter(descriptor=dict(V145_MINIUM_DESCRIPTOR))
    with pytest.raises(ValueError) as exc:
        adapter.prepare({"leaseRef": {"deviceId": 7, "serial": "d1"}})
    assert "环境档案" in str(exc.value)
    assert fake_minium == [], "档案先闸=引擎 import 前拒绝，不得拉起会话"


def test_prepare_rejects_incomplete_profile_keys(fake_minium):
    from driver.mobile.minium_adapter import MiniumAdapter
    adapter = MiniumAdapter(descriptor=dict(V145_MINIUM_DESCRIPTOR))
    with pytest.raises(ValueError) as exc:
        adapter.prepare({"envProfile": {"appid": "wx1"},
                         "leaseRef": {"deviceId": 7, "serial": "d1"}})
    assert "projectPath" in str(exc.value)


def test_prepare_rejects_missing_lease(fake_minium):
    from driver.mobile.minium_adapter import MiniumAdapter
    adapter = MiniumAdapter(descriptor=dict(V145_MINIUM_DESCRIPTOR))
    with pytest.raises(ValueError) as exc:
        adapter.prepare({"envProfile": dict(ENV_PROFILE), "leaseRef": {}})
    assert "租约" in str(exc.value)


def test_prepare_builds_conf_from_profile(fake_minium):
    adapter = _prepared_adapter(fake_minium)
    session = fake_minium[0]
    assert session.conf["project_path"] == ENV_PROFILE["projectPath"]
    assert session.conf["dev_port"] == 9420
    assert session.conf["platform"] == "wx"
    assert adapter.is_prepared() is True


def test_minium_absent_gives_plain_words_env_attribution(monkeypatch):
    pass
    from driver.mobile import minium_adapter

    def boom():
        raise ImportError("No module named 'minium'")

    monkeypatch.setattr(minium_adapter, "_load_minium", boom)
    adapter = minium_adapter.MiniumAdapter(descriptor=dict(V145_MINIUM_DESCRIPTOR))
    with pytest.raises(RuntimeError) as exc:
        adapter.prepare({"envProfile": dict(ENV_PROFILE),
                         "leaseRef": {"deviceId": 7, "serial": "d1"}})
    msg = str(exc.value)
    assert "minium" in msg and "pip" in msg



def test_all_sixteen_verbs_dispatch(fake_minium):
    adapter = _prepared_adapter(fake_minium)
    session = fake_minium[0]
    session.missing_selectors.add(".gone")
    steps = [
        {"action": "app.launch", "verb": "launch", "page": "/pages/home"},
        {"action": "app.tap", "verb": "tap", "selector": ".login-btn"},
        {"action": "app.longPress", "verb": "longPress", "selector": ".file"},
        {"action": "app.doubleTap", "verb": "doubleTap", "selector": ".zoom"},
        {"action": "app.input", "verb": "input", "selector": ".name", "text": "abc"},
        {"action": "app.clearInput", "verb": "clearInput", "selector": ".name"},
        {"action": "app.swipe", "verb": "swipe",
         "x1": 100, "y1": 500, "x2": 100, "y2": 100},
        {"action": "app.scroll", "verb": "scroll", "selector": ".list"},
        {"action": "app.back", "verb": "back"},
        {"action": "app.screenshot", "verb": "screenshot"},
        {"action": "app.assertVisible", "verb": "assertVisible", "selector": ".home"},
        {"action": "app.assertNotVisible", "verb": "assertNotVisible",
         "selector": ".gone"},
        {"action": "app.assertText", "verb": "assertText",
         "selector": ".title", "text": "欢迎"},
        {"action": "app.waitVisible", "verb": "waitVisible", "selector": ".late"},
        {"action": "app.extractText", "verb": "extractText", "selector": ".title"},
        {"action": "app.close", "verb": "close"},
    ]
    for step in steps:
        res = adapter.execute(step)
        assert res.get("result") == "pass", f"{step['verb']} 分发失败: {res}"
    verbs = [c[0] for c in session.calls]
    assert "reLaunch" in verbs and "navigateBack" in verbs
    assert "app.release" in verbs, "close 动词=会话收（release）语义"
    extracted = adapter.execute({"action": "app.extractText", "verb": "extractText",
                                 "selector": ".title"})
    assert extracted.get("extracted") == "欢迎"


def test_out_of_baseline_verb_rejected_in_plain_words(fake_minium):
    adapter = _prepared_adapter(fake_minium)
    res = adapter.execute({"action": "app.selfDestruct", "verb": "selfDestruct"})
    assert res.get("result") == "fail"
    assert "selfDestruct" in (res.get("detail") or "") and "不在" in res["detail"]


def test_execute_before_prepare_is_env_fail(fake_minium):
    from driver.mobile.minium_adapter import MiniumAdapter
    adapter = MiniumAdapter(descriptor=dict(V145_MINIUM_DESCRIPTOR))
    res = adapter.execute({"action": "app.tap", "verb": "tap", "selector": ".a"})
    assert res.get("result") == "fail" and res.get("errorType") == 2
    assert "prepare" in (res.get("detail") or "")


def test_missing_selector_becomes_structured_locator_fail(fake_minium):
    adapter = _prepared_adapter(fake_minium)
    session = fake_minium[0]
    session.missing_selectors.add(".ghost")
    res = adapter.execute({"action": "app.tap", "verb": "tap", "selector": ".ghost"})
    assert res.get("result") == "fail" and res.get("errorType") == 1
    assert ".ghost" in (res.get("detail") or "")


def test_locator_absent_step_rejected_in_plain_words(fake_minium):
    adapter = _prepared_adapter(fake_minium)
    res = adapter.execute({"action": "app.tap", "verb": "tap"})
    assert res.get("result") == "fail" and res.get("errorType") == 1
    assert "定位器" in (res.get("detail") or "")


def test_assert_visible_fails_when_element_missing(fake_minium):
    adapter = _prepared_adapter(fake_minium)
    session = fake_minium[0]
    session.missing_selectors.add(".gone")
    res = adapter.execute({"action": "app.assertVisible", "verb": "assertVisible",
                           "selector": ".gone"})
    assert res.get("result") == "fail"


def test_assert_not_visible_fails_when_element_found(fake_minium):
    adapter = _prepared_adapter(fake_minium)
    res = adapter.execute({"action": "app.assertNotVisible", "verb": "assertNotVisible",
                           "selector": ".real"})
    assert res.get("result") == "fail"
    assert ".real" in (res.get("detail") or "")


def test_assert_text_mismatch_reports_both_sides(fake_minium):
    adapter = _prepared_adapter(fake_minium)
    res = adapter.execute({"action": "app.assertText", "verb": "assertText",
                           "selector": ".title", "text": "预期文案"})
    assert res.get("result") == "fail"
    detail = res.get("detail") or ""
    assert "预期文案" in detail and "欢迎" in detail, "断言失败须两侧文案齐（信息不截断）"


def test_swipe_without_coordinates_rejected(fake_minium):
    adapter = _prepared_adapter(fake_minium)
    res = adapter.execute({"action": "app.swipe", "verb": "swipe"})
    assert res.get("result") == "fail" and "坐标" in (res.get("detail") or "")



def test_double_tap_is_two_taps_with_configurable_interval(fake_minium, monkeypatch):
    from driver.mobile import minium_adapter
    sleeps: list = []
    monkeypatch.setattr(minium_adapter.time, "sleep", lambda s: sleeps.append(s))
    monkeypatch.delenv("APPAUTO_MP_DOUBLE_TAP_INTERVAL_MS", raising=False)
    adapter = _prepared_adapter(fake_minium)
    res = adapter.execute({"action": "app.doubleTap", "verb": "doubleTap",
                           "selector": ".zoom"})
    assert res.get("result") == "pass"
    assert len(sleeps) == 1, "两次 tap 之间恰一次间隔等待"
    session = fake_minium[0]
    el = session._elements.get(".zoom")
    assert el is not None and el.taps == 2, "doubleTap 尽力位=原生两次 tap"


def test_double_tap_interval_env_override(fake_minium, monkeypatch):
    from driver.mobile import minium_adapter
    sleeps: list = []
    monkeypatch.setattr(minium_adapter.time, "sleep", lambda s: sleeps.append(s))
    monkeypatch.setenv("APPAUTO_MP_DOUBLE_TAP_INTERVAL_MS", "50")
    adapter = _prepared_adapter(fake_minium)
    adapter.execute({"action": "app.doubleTap", "verb": "doubleTap",
                     "selector": ".zoom2"})
    assert sleeps == [0.05], "间隔键 APPAUTO_MP_DOUBLE_TAP_INTERVAL_MS 生效（毫秒）"


# ── collect / teardown ──────────────────────────────────────────────────────

def test_collect_writes_screenshot_artifact(fake_minium, tmp_path):
    adapter = _prepared_adapter(fake_minium)
    art = adapter.collect({"stepNo": 3, "artifactDir": str(tmp_path)})
    items = art if isinstance(art, list) else [art]
    shot = next(a for a in items if a.get("kind") == "screenshot")
    p = pathlib.Path(shot["localPath"])
    assert p.exists() and p.stat().st_size > 0


def test_collect_returns_dom_snapshot_alongside_screenshot(fake_minium, tmp_path):
    pass
    import json
    adapter = _prepared_adapter(fake_minium)
    art = adapter.collect({"stepNo": 5, "artifactDir": str(tmp_path)})
    assert isinstance(art, list) and {a["kind"] for a in art} == {"screenshot",
                                                                  "dom_snapshot"}
    tree = next(a for a in art if a["kind"] == "dom_snapshot")
    body = json.loads(pathlib.Path(tree["localPath"]).read_text(encoding="utf-8"))
    assert body.get("wxml"), "视图树=wxml 真值序列化"
    assert (body.get("data") or {}).get("greeting") == "hello"


def test_collect_view_tree_unavailable_is_honest(fake_minium, tmp_path):
    pass
    adapter = _prepared_adapter(fake_minium)
    session = fake_minium[0]

    class DeadPage:
        @property
        def wxml(self):
            raise RuntimeError("page destroyed")
        data = None
    session.page = DeadPage()
    art = adapter.collect({"stepNo": 6, "artifactDir": str(tmp_path)})
    items = art if isinstance(art, list) else [art]
    kinds = {a.get("kind") for a in items}
    assert kinds == {"screenshot"}, f"视图树不可得=诚实缺席: {art!r}"


def test_collect_before_prepare_returns_none(fake_minium):
    from driver.mobile.minium_adapter import MiniumAdapter
    assert MiniumAdapter().collect({"stepNo": 1, "artifactDir": "/tmp"}) is None


def test_teardown_release_then_shutdown_even_if_release_blows(fake_minium):
    adapter = _prepared_adapter(fake_minium)
    session = fake_minium[0]

    def boom_release():
        raise RuntimeError("release 通道断")

    session.app.release = boom_release
    adapter.teardown()
    assert ("app.shutdown", None) in session.calls
    assert adapter.is_prepared() is False


def test_teardown_idempotent(fake_minium):
    adapter = _prepared_adapter(fake_minium)
    adapter.teardown()
    adapter.teardown()



def test_app_worker_passes_env_profile_into_ctx(fake_minium, monkeypatch):
    import json as _json

    import app_worker
    from driver.mobile import minium_adapter

    seen: dict = {}
    real_prepare = minium_adapter.MiniumAdapter.prepare

    def spy_prepare(self, ctx):
        seen.update(ctx)
        real_prepare(self, ctx)

    monkeypatch.setattr(minium_adapter.MiniumAdapter, "prepare", spy_prepare)

    envelope = {
        "kind": "app-auto-run", "runId": "r9",
        "leaseRef": {"deviceId": 7, "serial": "devtools-1"},
        "engine": dict(V145_MINIUM_DESCRIPTOR),
        "envProfile": dict(ENV_PROFILE),
        "steps": [{"action": "app.back"}],
    }
    task = {"taskId": 1, "actionJson": _json.dumps(envelope),
            "_adapterOverride": minium_adapter.MiniumAdapter(
                descriptor=dict(V145_MINIUM_DESCRIPTOR))}
    outcome = app_worker.run_app_task(task)
    assert outcome.result == "pass", outcome.error_detail
    assert seen.get("envProfile") == ENV_PROFILE
    assert seen.get("leaseRef") == envelope["leaseRef"]
