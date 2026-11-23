
from __future__ import annotations

import pathlib
import sys

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))
sys.path.insert(0, str(HERE))

from test_t306_appium_adapter import (
    FakeWebDriver,
    _install_fake_selenium,
)

from driver.mobile import appium_adapter as aa
from driver.mobile import recording as rec
from driver.mobile.contracts import (
    WEBVIEW_HYBRID_CAP,
    capabilities_of,
    is_hybrid_carrier,
    step_requests_webview,
    webview_step_violation,
)

LEASE = {"deviceId": 11, "serial": "emulator-5554"}

CAPS_ON = {"capabilities": {WEBVIEW_HYBRID_CAP: True}}
CAPS_OFF = {"capabilities": {WEBVIEW_HYBRID_CAP: False}}

WEB_STEP = {"action": "app.tap", "verb": "tap", "webview": True, "testId": "login"}
NATIVE_STEP = {"action": "app.tap", "verb": "tap", "testId": "login"}



def test_gate_rejects_flagless_engine_with_frozen_phrasing():
    pass
    res = webview_step_violation({"code": "MAESTRO", **CAPS_OFF}, WEB_STEP)
    assert res is not None and res["result"] == "fail"
    assert res["errorType"] == 1, "WebView 落区不匹配=脚本/能力面归 1（03 语义）"
    detail = res["detail"]
    assert "不支持混合 WebView 定位" in detail and "MAESTRO" in detail
    assert "建议换" in detail and "Appium" in detail and "automator" in detail
    assert "{" not in detail and "}" not in detail


def test_gate_passes_flagged_engine_dict_and_json_string_forms():
    pass
    assert webview_step_violation({"code": "APPIUM", **CAPS_ON}, WEB_STEP) is None
    assert webview_step_violation(
        {"code": "APPIUM", "capabilities": '{"webview-hybrid": true}'}, WEB_STEP) is None


def test_gate_missing_capabilities_face_fail_closed():
    pass
    res = webview_step_violation({"code": "HYPIUM"}, WEB_STEP)
    assert res is not None and res["result"] == "fail"


def test_gate_native_step_untouched():
    pass
    assert webview_step_violation({"code": "MAESTRO", **CAPS_OFF}, NATIVE_STEP) is None
    assert webview_step_violation(None, None) is None
    assert not step_requests_webview(NATIVE_STEP)
    assert step_requests_webview(WEB_STEP)


def test_capabilities_of_single_source_shared_with_recording():
    pass
    assert rec._capabilities_of is capabilities_of
    assert capabilities_of({"capabilities": {"a": 1}}) == {"a": 1}
    assert capabilities_of({"capabilities": "坏JSON"}) == {}
    assert capabilities_of(None) == {}


def test_hybrid_carrier_flag_from_env_profile():
    pass
    assert is_hybrid_carrier({"hybrid": True})
    assert not is_hybrid_carrier({"hybrid": False})
    assert not is_hybrid_carrier(None)
    assert not is_hybrid_carrier({})



def test_maestro_webview_step_fail_closed_before_session():
    from driver.mobile.maestro_adapter import MaestroAdapter

    a = MaestroAdapter(descriptor={"code": "MAESTRO", "status": 1, **CAPS_OFF})
    res = a.execute(dict(WEB_STEP))
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "不支持混合 WebView 定位" in res["detail"]


def test_harmony_webview_step_fail_closed_before_session():
    from driver.mobile.harmony_adapter import HarmonyAdapter

    a = HarmonyAdapter(descriptor={"code": "HMNEXTAUTO", "status": 1, **CAPS_OFF})
    res = a.execute(dict(WEB_STEP))
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "不支持混合 WebView 定位" in res["detail"]



class _SwitchTo:
    pass

    def __init__(self, driver):
        self._driver = driver

    @property
    def context(self):
        return self._driver._context_now

    @context.setter
    def context(self, name):
        self._driver._context_now = name
        self._driver.calls.append(("context", name))


class _WebViewDriver(FakeWebDriver):
    pass

    def __init__(self, contexts=None, **kw):
        super().__init__(**kw)
        self.contexts = list(contexts) if contexts is not None \
            else ["NATIVE_APP", "WEBVIEW_com.demo.app"]
        self._context_now = "NATIVE_APP"
        self.switch_to = _SwitchTo(self)


def _prepared_appium(driver, caps_extra=None, env_profile=None):
    uninstall = _install_fake_selenium(lambda _caps: driver)
    saved_probe = aa._probe_appium_server
    aa._probe_appium_server = lambda url: True
    desc = {"code": "APPIUM", "status": 1, "runtimeType": "WEB_DRIVER",
            "platformCodes": ["ANDROID"], "deviceKindCodes": ["EMULATOR", "REAL"],
            "capabilities": {WEBVIEW_HYBRID_CAP: True}}
    desc.update(caps_extra or {})
    adapter = aa.AppiumAdapter(descriptor=desc)
    adapter._saved_probe = saved_probe
    profile = {"appId": "com.demo.app"}
    profile.update(env_profile or {})
    adapter.prepare({"leaseRef": dict(LEASE), "envProfile": profile, "taskId": "t313"})
    return adapter, uninstall


def _teardown_appium(adapter, uninstall):
    aa._probe_appium_server = adapter._saved_probe
    uninstall()


def test_appium_webview_step_switches_context_by_package_then_restores():
    pass
    driver = _WebViewDriver()
    adapter, uninstall = _prepared_appium(driver)
    try:
        res = adapter.execute({"action": "app.assertVisible", "verb": "assertVisible",
                               "webview": True, "testId": "login"})
        assert res["result"] == "pass", res
        ctx_calls = [c for c in driver.calls if c[0] == "context"]
        assert ctx_calls == [("context", "WEBVIEW_com.demo.app"),
                             ("context", "NATIVE_APP")], driver.calls
        find_at = next(i for i, c in enumerate(driver.calls) if c[0] == "find")
        switch_at = driver.calls.index(("context", "WEBVIEW_com.demo.app"))
        assert switch_at < find_at, "定位必须发生在 context 切换之后"
    finally:
        _teardown_appium(adapter, uninstall)


def test_appium_step_package_overrides_session_app_id():
    pass
    driver = _WebViewDriver(contexts=["NATIVE_APP", "WEBVIEW_com.other.app"])
    adapter, uninstall = _prepared_appium(driver)
    try:
        res = adapter.execute({"action": "app.assertVisible", "verb": "assertVisible",
                               "webview": True, "pkg": "com.other.app",
                               "testId": "x"})
        assert res["result"] == "pass", res
        assert ("context", "WEBVIEW_com.other.app") in driver.calls
    finally:
        _teardown_appium(adapter, uninstall)


def test_appium_no_webview_context_fail_closed_names_carrier():
    pass
    driver = _WebViewDriver(contexts=["NATIVE_APP"])
    adapter, uninstall = _prepared_appium(driver, env_profile={"hybrid": True})
    try:
        res = adapter.execute({"action": "app.assertVisible", "verb": "assertVisible",
                               "webview": True, "testId": "login"})
        assert res["result"] == "fail" and res["errorType"] == 1
        assert "WebView" in res["detail"] and "混合" in res["detail"]
        assert res["detail"].count("{") == 0, "人话禁 JSON dump"
    finally:
        _teardown_appium(adapter, uninstall)


def test_appium_native_step_never_touches_context():
    pass
    driver = _WebViewDriver()
    adapter, uninstall = _prepared_appium(driver)
    try:
        res = adapter.execute({"action": "app.assertVisible", "verb": "assertVisible",
                               "testId": "login"})
        assert res["result"] == "pass", res
        assert not [c for c in driver.calls if c[0] == "context"]
    finally:
        _teardown_appium(adapter, uninstall)



def _node_available() -> bool:
    import shutil

    return shutil.which("node") is not None


def _mw_bridge_adapter(descriptor_caps):
    from driver.mobile.bridge_process import NodeBridge
    from driver.mobile.mobilewright_adapter import MobilewrightAdapter

    bridge = NodeBridge(
        script_path=str(EXECUTOR_ROOT / "driver" / "mobile" / "mobilewright_bridge.mjs"),
        timeout_sec=15.0,
        extra_env={"MOBILEWRIGHT_DISABLE_TELEMETRY": "1",
                   "MW_TEST_ENGINE_MODULE": str(HERE / "fixtures" / "mw_fake_engine.mjs")})
    bridge.start()
    adapter = MobilewrightAdapter(
        descriptor={"code": "MOBILEWRIGHT", "status": 1, "platformCodes": ["ANDROID"],
                    "deviceKindCodes": ["EMULATOR"], "capabilities": descriptor_caps})
    adapter._bridge = bridge
    adapter.prepare({"leaseRef": dict(LEASE), "runId": "t313-mw"})
    return adapter


def _automator_bridge_adapter(descriptor_caps):
    from driver.mobile.bridge_process import NodeBridge
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter

    bridge = NodeBridge(
        script_path=str(EXECUTOR_ROOT / "driver" / "mobile" / "automator_bridge.mjs"),
        timeout_sec=15.0,
        extra_env={"AUTOMATOR_TEST_ENGINE_MODULE":
                   str(HERE / "fixtures" / "automator_fake_engine.mjs")})
    bridge.start()
    adapter = AutomatorAdapter(
        descriptor={"code": "MINIPROGRAM_AUTOMATOR", "status": 1, "platformCodes": ["MP"],
                    "deviceKindCodes": ["DEVTOOLS_IDE"], "capabilities": descriptor_caps})
    adapter._bridge = bridge
    adapter.prepare({"leaseRef": dict(LEASE), "runId": "t313-mpa",
                     "envProfile": {"projectPath": "/tmp/fake-mp"}})
    return adapter


@pytest.mark.skipif(not _node_available(), reason="node 环境件不可达（如实标注，随包③补账）")
def test_mobilewright_webview_step_penetrates_accessibility_tree():
    pass
    adapter = _mw_bridge_adapter({WEBVIEW_HYBRID_CAP: True})
    try:
        res = adapter.execute({"action": "app.tap", "verb": "tap",
                               "webview": True, "text": "登录"})
        assert res["result"] == "pass", res
        res = adapter.execute({"action": "app.tap", "verb": "tap",
                               "webview": True, "testId": "web:login"})
        assert res["result"] == "pass", res
    finally:
        adapter.teardown()


@pytest.mark.skipif(not _node_available(), reason="node 环境件不可达（如实标注，随包③补账）")
def test_mobilewright_webview_step_tree_miss_fails_in_plain_words():
    pass
    adapter = _mw_bridge_adapter({WEBVIEW_HYBRID_CAP: True})
    try:
        res = adapter.execute({"action": "app.tap", "verb": "tap",
                               "webview": True, "text": "不存在的按钮"})
        assert res["result"] == "fail", res
        assert "无障碍树" in res["detail"] and "web" in res["detail"].lower()
    finally:
        adapter.teardown()


@pytest.mark.skipif(not _node_available(), reason="node 环境件不可达（如实标注，随包③补账）")
def test_mobilewright_flagless_descriptor_refuses_webview_step_python_side():
    pass
    from driver.mobile.mobilewright_adapter import MobilewrightAdapter

    adapter = MobilewrightAdapter(descriptor={"code": "MOBILEWRIGHT", "status": 1})
    res = adapter.execute({"action": "app.tap", "verb": "tap", "webview": True,
                           "text": "登录"})
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "不支持混合 WebView 定位" in res["detail"]


@pytest.mark.skipif(not _node_available(), reason="node 环境件不可达（如实标注，随包③补账）")
def test_automator_webview_step_locates_through_web_view_node():
    pass
    adapter = _automator_bridge_adapter({WEBVIEW_HYBRID_CAP: True})
    try:
        res = adapter.execute({"action": "app.tap", "verb": "tap",
                               "webview": True, "selector": "#buy"})
        assert res["result"] == "pass", res
        res = adapter.execute({"action": "app.assertText", "verb": "assertText",
                               "webview": True, "selector": "#title", "text": "欢迎"})
        assert res["result"] == "pass", res
        res = adapter.execute({"action": "app.tap", "verb": "tap",
                               "webview": True, "selector": "#ghost"})
        assert res["result"] == "fail" and "web-view" in res["detail"], res
    finally:
        adapter.teardown()


@pytest.mark.skipif(not _node_available(), reason="node 环境件不可达（如实标注，随包③补账）")
def test_automator_native_step_not_routed_through_web_domain():
    pass
    adapter = _automator_bridge_adapter({WEBVIEW_HYBRID_CAP: True})
    try:
        res = adapter.execute({"action": "app.tap", "verb": "tap", "selector": "#login"})
        assert res["result"] == "pass", res
    finally:
        adapter.teardown()


def test_minium_declared_flag_without_adapter_impl_refuses():
    pass
    from driver.mobile.minium_adapter import MiniumAdapter

    a = MiniumAdapter(descriptor={"code": "MINIUM", "status": 1, **CAPS_ON})
    res = a.execute({"action": "app.tap", "verb": "tap", "webview": True,
                     "selector": "#buy"})
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "两侧漂移" in res["detail"]
