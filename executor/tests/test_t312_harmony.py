
from __future__ import annotations

import json
import os
import pathlib
import sys
from typing import ClassVar

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from actions_common import IR_VERBS
from capabilities import probe_capabilities
from driver.mobile.contracts import capability_violations
from driver.mobile.engine_factory import mobile_engine_factory

V145_HMNEXTAUTO_DESCRIPTOR = {
    "code": "HMNEXTAUTO",
    "status": 1,
    "runtimeType": "PY_INPROC",
    "platformCodes": ["HARMONY_NEXT"],
    "deviceKindCodes": ["EMULATOR", "REAL"],
}

ENV_PROFILE = {"hdcPath": "/opt/hdc/bin/hdc", "deviceSerial": "harmony-1",
               "appId": "com.demo.hap"}



class FakeUiObject:
    def __init__(self, driver, kwargs):
        self.driver = driver
        self.kwargs = kwargs
        self.clicks = 0
        self.double_clicks = 0
        self.long_clicks = 0
        self.inputs: list = []
        self.clears = 0
        self._text = "欢迎"
        if kwargs.get("text") == "迟到文案":
            self._text = "迟到文案"

    def exists(self, retries=2, wait_time=1):
        self.driver.calls.append(("ui.exists", dict(self.kwargs)))
        return self.kwargs not in self.driver.missing

    def click(self):
        self.clicks += 1
        self.driver.calls.append(("ui.click", dict(self.kwargs)))

    def double_click(self):
        self.double_clicks += 1
        self.driver.calls.append(("ui.double_click", dict(self.kwargs)))

    def long_click(self):
        self.long_clicks += 1
        self.driver.calls.append(("ui.long_click", dict(self.kwargs)))

    def input_text(self, text):
        self.inputs.append(text)
        self.driver.calls.append(("ui.input_text", text))

    def clear_text(self):
        self.clears += 1
        self.driver.calls.append(("ui.clear_text", None))

    @property
    def text(self):
        self.driver.calls.append(("ui.text", dict(self.kwargs)))
        return self._text


class FakeDriver:
    pass

    _instances: ClassVar[dict] = {}

    def __init__(self, serial=None):
        self.serial = serial
        self.calls: list[tuple] = []
        self.missing: list[dict] = []
        self._elements: dict = {}
        self.screenshot_paths: list = []

    def __call__(self, **kwargs):
        key = tuple(sorted(kwargs.items()))
        el = self._elements.get(key) or FakeUiObject(self, kwargs)
        self._elements[key] = el
        return el

    def start_app(self, package_name, page_name=None):
        self.calls.append(("start_app", (package_name, page_name)))

    def stop_app(self, package_name):
        self.calls.append(("stop_app", package_name))

    def go_back(self):
        self.calls.append(("go_back", None))

    def click(self, x, y):
        self.calls.append(("click", (x, y)))

    def double_click(self, x, y):
        self.calls.append(("double_click", (x, y)))

    def long_click(self, x, y):
        self.calls.append(("long_click", (x, y)))

    def swipe(self, x1, y1, x2, y2, speed=2000):
        self.calls.append(("swipe", (x1, y1, x2, y2)))

    def swipe_ext(self, direction, box=None):
        self.calls.append(("swipe_ext", direction))

    def screenshot(self, path, method="snapshot_display"):
        self.calls.append(("screenshot", path))
        self.screenshot_paths.append(path)
        pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
        pathlib.Path(path).write_bytes(b"\x89PNG-fake")
        return path

    def dump_hierarchy(self):
        self.calls.append(("dump_hierarchy", None))
        return {"attributes": {}, "children": [
            {"attributes": {"text": "欢迎", "type": "Text"}}]}


@pytest.fixture()
def fake_hmdriver(monkeypatch):
    pass
    from driver.mobile import harmony_adapter

    created: list = []

    def fake_load():
        def factory(serial=None):
            driver = FakeDriver(serial=serial)
            created.append(driver)
            return driver
        return factory

    monkeypatch.setattr(harmony_adapter, "_load_driver_class", fake_load)
    return created


def _prepared_adapter(fake_hmdriver, ctx=None):
    adapter = mobile_engine_factory(dict(V145_HMNEXTAUTO_DESCRIPTOR))
    adapter.prepare(dict({"envProfile": dict(ENV_PROFILE),
                          "leaseRef": {"deviceId": 9, "serial": "harmony-1"},
                          "runId": "r1"}, **(ctx or {})))
    return adapter



def test_factory_loads_real_harmony_adapter():
    adapter = mobile_engine_factory(dict(V145_HMNEXTAUTO_DESCRIPTOR))
    from driver.mobile.harmony_adapter import HarmonyAdapter
    assert isinstance(adapter, HarmonyAdapter)
    assert type(adapter).__name__ != "StubEngineAdapter"


def test_adapter_self_description_matches_descriptor_face():
    from driver.mobile.harmony_adapter import HarmonyAdapter
    assert HarmonyAdapter.SUPPORTED_PLATFORMS == ("HARMONY_NEXT",)
    assert set(HarmonyAdapter.SUPPORTED_DEVICE_KINDS) == {"EMULATOR", "REAL"}
    assert tuple(HarmonyAdapter.CAPABILITIES) == tuple(IR_VERBS)


def test_descriptor_capability_reconciliation_clean():
    from driver.mobile.harmony_adapter import HarmonyAdapter
    adapter = HarmonyAdapter(descriptor=dict(V145_HMNEXTAUTO_DESCRIPTOR))
    assert capability_violations(adapter, V145_HMNEXTAUTO_DESCRIPTOR) is None


def test_hypium_still_refused():
    pass
    desc = dict(V145_HMNEXTAUTO_DESCRIPTOR, code="HYPIUM", status=0)
    with pytest.raises(ValueError) as exc:
        mobile_engine_factory(desc)
    assert "HYPIUM" in str(exc.value) and "停用" in str(exc.value)



def test_prepare_rejects_missing_env_profile_in_plain_words(fake_hmdriver):
    from driver.mobile.harmony_adapter import HarmonyAdapter
    adapter = HarmonyAdapter(descriptor=dict(V145_HMNEXTAUTO_DESCRIPTOR))
    with pytest.raises(ValueError) as exc:
        adapter.prepare({"leaseRef": {"deviceId": 9, "serial": "h1"}})
    assert "环境档案" in str(exc.value)
    assert fake_hmdriver == [], "档案先闸=引擎 import 前拒绝，不得拉起会话"


def test_prepare_rejects_missing_lease(fake_hmdriver):
    from driver.mobile.harmony_adapter import HarmonyAdapter
    adapter = HarmonyAdapter(descriptor=dict(V145_HMNEXTAUTO_DESCRIPTOR))
    with pytest.raises(ValueError) as exc:
        adapter.prepare({"envProfile": dict(ENV_PROFILE), "leaseRef": {}})
    assert "租约" in str(exc.value)


def test_prepare_opens_driver_with_lease_serial(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)
    assert fake_hmdriver[0].serial == "harmony-1"
    assert adapter.is_prepared() is True


def test_hmdriver2_absent_gives_plain_words_env_attribution(monkeypatch):
    pass
    from driver.mobile import harmony_adapter

    def boom():
        raise ImportError("No module named 'hmdriver2'")

    monkeypatch.setattr(harmony_adapter, "_load_driver_class", boom)
    adapter = harmony_adapter.HarmonyAdapter(descriptor=dict(V145_HMNEXTAUTO_DESCRIPTOR))
    with pytest.raises(RuntimeError) as exc:
        adapter.prepare({"envProfile": dict(ENV_PROFILE),
                         "leaseRef": {"deviceId": 9, "serial": "h1"}})
    msg = str(exc.value)
    assert "hmdriver2" in msg and "pip" in msg



def test_all_sixteen_verbs_dispatch(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)
    driver = fake_hmdriver[0]
    driver.missing.append({"text": "不存在的元素"})
    steps = [
        {"action": "app.launch", "verb": "launch", "pkg": "com.demo.hap"},
        {"action": "app.tap", "verb": "tap", "id": "login-btn"},
        {"action": "app.longPress", "verb": "longPress", "text": "文件"},
        {"action": "app.doubleTap", "verb": "doubleTap", "text": "图片"},
        {"action": "app.input", "verb": "input", "id": "name", "text": "abc"},
        {"action": "app.clearInput", "verb": "clearInput", "id": "name"},
        {"action": "app.swipe", "verb": "swipe",
         "x1": 100, "y1": 500, "x2": 100, "y2": 100},
        {"action": "app.scroll", "verb": "scroll", "direction": "up"},
        {"action": "app.back", "verb": "back"},
        {"action": "app.screenshot", "verb": "screenshot"},
        {"action": "app.assertVisible", "verb": "assertVisible", "text": "欢迎"},
        {"action": "app.assertNotVisible", "verb": "assertNotVisible",
         "text": "不存在的元素"},
        {"action": "app.assertText", "verb": "assertText",
         "id": "title", "text": "欢迎"},
        {"action": "app.waitVisible", "verb": "waitVisible", "text": "欢迎"},
        {"action": "app.extractText", "verb": "extractText", "id": "title"},
        {"action": "app.close", "verb": "close"},
    ]
    for step in steps:
        res = adapter.execute(step)
        assert res.get("result") == "pass", f"{step['verb']} 分发失败: {res}"
    verbs = [c[0] for c in driver.calls]
    assert "start_app" in verbs and "go_back" in verbs and "swipe" in verbs
    assert "swipe_ext" in verbs, "scroll=hmdriver2 swipe_ext 方向语义"
    assert "stop_app" in verbs, "close 动词=应用收（stop_app）语义"
    extracted = adapter.execute({"action": "app.extractText", "verb": "extractText",
                                 "id": "title"})
    assert extracted.get("extracted") == "欢迎"


def test_out_of_baseline_verb_rejected_in_plain_words(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)
    res = adapter.execute({"action": "app.selfDestruct", "verb": "selfDestruct"})
    assert res.get("result") == "fail"
    assert "selfDestruct" in (res.get("detail") or "") and "不在" in res["detail"]


def test_execute_before_prepare_is_env_fail(fake_hmdriver):
    from driver.mobile.harmony_adapter import HarmonyAdapter
    adapter = HarmonyAdapter(descriptor=dict(V145_HMNEXTAUTO_DESCRIPTOR))
    res = adapter.execute({"action": "app.tap", "verb": "tap", "id": "a"})
    assert res.get("result") == "fail" and res.get("errorType") == 2
    assert "prepare" in (res.get("detail") or "")


def test_missing_locator_rejected_in_plain_words(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)
    res = adapter.execute({"action": "app.tap", "verb": "tap"})
    assert res.get("result") == "fail" and res.get("errorType") == 1
    assert "定位器" in (res.get("detail") or "")


def test_tap_by_coordinates_without_locator(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)
    res = adapter.execute({"action": "app.tap", "verb": "tap", "x": 120, "y": 340})
    assert res.get("result") == "pass"
    assert ("click", (120, 340)) in fake_hmdriver[0].calls


def test_launch_without_package_rejected(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)
    res = adapter.execute({"action": "app.launch", "verb": "launch"})
    assert res.get("result") == "fail" and res.get("errorType") == 1
    assert "包名" in (res.get("detail") or "")


def test_assert_visible_fails_when_element_absent(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)
    driver = fake_hmdriver[0]
    driver.missing.append({"text": "不存在的元素"})
    res = adapter.execute({"action": "app.assertVisible", "verb": "assertVisible",
                           "text": "不存在的元素"})
    assert res.get("result") == "fail" and res.get("errorType") == 1
    assert "不存在的元素" in (res.get("detail") or "")


def test_assert_text_mismatch_reports_both_sides(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)
    res = adapter.execute({"action": "app.assertText", "verb": "assertText",
                           "id": "title", "text": "预期文案"})
    assert res.get("result") == "fail"
    detail = res.get("detail") or ""
    assert "预期文案" in detail and "欢迎" in detail, "断言失败须两侧文案齐（信息不截断）"


def test_swipe_without_coordinates_rejected(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)
    res = adapter.execute({"action": "app.swipe", "verb": "swipe"})
    assert res.get("result") == "fail" and "坐标" in (res.get("detail") or "")


def test_engine_exception_becomes_structured_fail(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)
    driver = fake_hmdriver[0]

    def boom():
        raise RuntimeError("hdc 通道断开")

    driver.go_back = boom
    res = adapter.execute({"action": "app.back", "verb": "back"})
    assert res.get("result") == "fail" and res.get("errorType") == 2
    assert "hdc 通道断开" in (res.get("detail") or "")


# ── collect / teardown ──────────────────────────────────────────────────────

def test_collect_writes_screenshot_and_hierarchy(fake_hmdriver, tmp_path):
    pass
    adapter = _prepared_adapter(fake_hmdriver)
    art = adapter.collect({"stepNo": 3, "artifactDir": str(tmp_path)})
    assert isinstance(art, list) and {a["kind"] for a in art} == {"screenshot",
                                                                  "dom_snapshot"}
    shot = next(a for a in art if a["kind"] == "screenshot")
    assert pathlib.Path(shot["localPath"]).exists()
    tree = next(a for a in art if a["kind"] == "dom_snapshot")
    body = json.loads(pathlib.Path(tree["localPath"]).read_text(encoding="utf-8"))
    assert body.get("children"), "视图树=dump_hierarchy 真值序列化"


def test_collect_before_prepare_returns_none():
    from driver.mobile.harmony_adapter import HarmonyAdapter
    assert HarmonyAdapter().collect({"stepNo": 1, "artifactDir": "/tmp"}) is None


def test_teardown_clears_session_and_idempotent(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)
    adapter.teardown()
    assert adapter.is_prepared() is False
    adapter.teardown()


def test_teardown_never_raises_even_if_driver_blows(fake_hmdriver):
    adapter = _prepared_adapter(fake_hmdriver)

    def boom():
        raise RuntimeError("收会话炸")

    adapter._driver.stop_app = boom
    adapter.teardown()
    assert adapter.is_prepared() is False



def test_app_worker_passes_env_profile_into_ctx(fake_hmdriver, monkeypatch):
    import app_worker
    from driver.mobile import harmony_adapter

    seen: dict = {}
    real_prepare = harmony_adapter.HarmonyAdapter.prepare

    def spy_prepare(self, ctx):
        seen.update(ctx)
        real_prepare(self, ctx)

    monkeypatch.setattr(harmony_adapter.HarmonyAdapter, "prepare", spy_prepare)

    envelope = {
        "kind": "app-auto-run", "runId": "r9",
        "leaseRef": {"deviceId": 9, "serial": "harmony-1"},
        "engine": dict(V145_HMNEXTAUTO_DESCRIPTOR),
        "envProfile": dict(ENV_PROFILE),
        "steps": [{"action": "app.back"}],
    }
    task = {"taskId": 1, "actionJson": json.dumps(envelope),
            "_adapterOverride": harmony_adapter.HarmonyAdapter(
                descriptor=dict(V145_HMNEXTAUTO_DESCRIPTOR))}
    outcome = app_worker.run_app_task(task)
    assert outcome.result == "pass", outcome.error_detail
    assert seen.get("envProfile") == ENV_PROFILE
    assert seen.get("leaseRef") == envelope["leaseRef"]



def _fake_probe(tools=None, versions=None, host_os="linux", exists=()):
    tools = set(tools or [])
    versions = versions or {}
    present = set(exists)

    def which(name):
        return f"/usr/bin/{name}" if name in tools else None

    def run_version(exe):
        return versions.get(exe)

    import capabilities as cap
    real_exists = cap.os.path.exists
    cap.os.path.exists = lambda p: p in present or real_exists(p)
    try:
        return probe_capabilities(which=which, run_version=run_version,
                                  host_os=host_os)
    finally:
        cap.os.path.exists = real_exists


def test_adb_在位_上报快应用尽力位():
    pass
    caps = _fake_probe(tools=["adb", "node"], versions={"node": "v22.0.0"})
    assert "QUICKAPP" in caps["platforms"]


def test_adb_缺位_不上报快应用_宁缺毋滥():
    os.environ.pop("WX_DEVTOOLS_CLI_PATH", None)
    caps = _fake_probe(tools=["node"], versions={"node": "v22.0.0"})
    assert "QUICKAPP" not in caps["platforms"]
    assert "ANDROID" not in caps["platforms"]


def test_hdc_在位_上报鸿蒙平台():
    pass
    caps = _fake_probe(tools=["hdc"], versions={})
    assert "HARMONY_NEXT" in caps["platforms"]
