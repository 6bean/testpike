
from __future__ import annotations

import pathlib
import sys
from types import SimpleNamespace

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from actions_common import IR_VERBS
from driver.mobile.engine_factory import mobile_engine_factory

HM_DESCRIPTOR = {
    "code": "HMNEXTAUTO", "status": 1, "runtimeType": "PY_INPROC",
    "platformCodes": ["HARMONY_NEXT"], "deviceKindCodes": ["EMULATOR", "REAL"],
}
MINIUM_DESCRIPTOR = {
    "code": "MINIUM", "status": 1, "runtimeType": "PY_INPROC",
    "platformCodes": ["MP"], "deviceKindCodes": ["DEVTOOLS_IDE"],
}
AUTOMATOR_DESCRIPTOR = {
    "code": "MINIPROGRAM_AUTOMATOR", "status": 1, "runtimeType": "NODE_BRIDGE",
    "platformCodes": ["MP"], "deviceKindCodes": ["DEVTOOLS_IDE"],
}
APPIUM_DESCRIPTOR = {
    "code": "APPIUM", "status": 1, "runtimeType": "SELENIUM",
    "platformCodes": ["ANDROID", "IOS"], "deviceKindCodes": ["EMULATOR", "REAL"],
}

FAKE_ROTATION_ENUM = SimpleNamespace(ROTATION_0=0, ROTATION_90=1,
                                     ROTATION_180=2, ROTATION_270=3)



class FakeHmDriver:
    pass

    def __init__(self, serial=None):
        self.serial = serial
        self.calls: list[tuple] = []
        self.shell_exit_code = 0

    def go_home(self):
        self.calls.append(("go_home", None))

    def unlock(self):
        self.calls.append(("unlock", None))

    def open_url(self, url, system_browser=True):
        self.calls.append(("open_url", (url, system_browser)))

    def clear_app(self, package_name):
        self.calls.append(("clear_app", package_name))

    def set_display_rotation(self, rotation):
        self.calls.append(("set_display_rotation", int(rotation)))

    def shell(self, cmd):
        self.calls.append(("shell", cmd))
        return SimpleNamespace(output="", error="boom", exit_code=self.shell_exit_code)


@pytest.fixture()
def fake_hm(monkeypatch):
    from driver.mobile import harmony_adapter

    created: list = []

    def fake_load():
        def factory(serial=None):
            driver = FakeHmDriver(serial=serial)
            created.append(driver)
            return driver
        return factory

    monkeypatch.setattr(harmony_adapter, "_load_driver_class", fake_load)
    monkeypatch.setattr(harmony_adapter, "_load_rotation_enum",
                        lambda: FAKE_ROTATION_ENUM)
    return created


def _hm_adapter(fake_hm):
    adapter = mobile_engine_factory(dict(HM_DESCRIPTOR))
    adapter.prepare({"envProfile": {"appId": "com.demo.hap"},
                     "leaseRef": {"deviceId": 9, "serial": "h1"}, "runId": "r1"})
    return adapter



def test_harmony_home_and_wakeunlock_map_driver_methods(fake_hm):
    adapter = _hm_adapter(fake_hm)
    assert adapter.execute({"action": "app.home", "verb": "home"})["result"] == "pass"
    assert adapter.execute({"action": "app.wakeUnlock",
                            "verb": "wakeUnlock"})["result"] == "pass"
    verbs = [c[0] for c in fake_hm[0].calls]
    assert "go_home" in verbs, "home=hmdriver2 go_home（send_key HOME L256）"
    assert "unlock" in verbs, "wakeUnlock=hmdriver2 unlock（亮屏+上滑 L271 整面）"


def test_harmony_deeplink_and_openurl_split_system_browser_flag(fake_hm):
    adapter = _hm_adapter(fake_hm)
    deep = adapter.execute({"action": "app.deepLink", "verb": "deepLink",
                            "url": "com.demo.hap://page/detail"})
    openr = adapter.execute({"action": "app.openUrl", "verb": "openUrl",
                             "url": "https://example.com"})
    assert deep["result"] == "pass" and openr["result"] == "pass"
    calls = [c for c in fake_hm[0].calls if c[0] == "open_url"]
    assert ("com.demo.hap://page/detail", False) in [c[1] for c in calls], \
        "deepLink=scheme 唤起应用内页（system_browser=False）"
    assert ("https://example.com", True) in [c[1] for c in calls], \
        "openUrl=系统浏览器打开外链（system_browser=True）"


def test_harmony_deeplink_missing_url_rejected_in_plain_words(fake_hm):
    adapter = _hm_adapter(fake_hm)
    res = adapter.execute({"action": "app.deepLink", "verb": "deepLink"})
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "url" in res["detail"] and "deepLink" in res["detail"]
    assert [c for c in fake_hm[0].calls if c[0] == "open_url"] == [], \
        "缺参先闸，不得把空 url 递给引擎"


def test_harmony_rotation_uses_display_rotation_enum(fake_hm):
    adapter = _hm_adapter(fake_hm)
    assert adapter.execute({"action": "app.rotation", "verb": "rotation",
                            "orientation": "portrait"})["result"] == "pass"
    assert adapter.execute({"action": "app.rotation", "verb": "rotation",
                            "orientation": "landscape"})["result"] == "pass"
    logged = [c[1] for c in fake_hm[0].calls if c[0] == "set_display_rotation"]
    assert logged == [0, 1], "portrait→ROTATION_0 / landscape→ROTATION_90（proto 枚举实证）"


def test_harmony_rotation_bad_value_rejected_without_touching_engine(fake_hm):
    adapter = _hm_adapter(fake_hm)
    res = adapter.execute({"action": "app.rotation", "verb": "rotation",
                            "orientation": "sideways"})
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "sideways" in res["detail"] and "portrait" in res["detail"]
    assert [c for c in fake_hm[0].calls if c[0] == "set_display_rotation"] == []


def test_harmony_cleardata_requires_pkg(fake_hm):
    adapter = _hm_adapter(fake_hm)
    assert adapter.execute({"action": "app.clearData", "verb": "clearData",
                            "pkg": "com.demo.hap"})["result"] == "pass"
    assert ("clear_app", "com.demo.hap") in fake_hm[0].calls
    res = adapter.execute({"action": "app.clearData", "verb": "clearData"})
    assert res["result"] == "fail" and res["errorType"] == 1 and "pkg" in res["detail"]


def test_harmony_grantpermission_shells_bm_grant_per_permission(fake_hm):
    adapter = _hm_adapter(fake_hm)
    res = adapter.execute({"action": "app.grantPermission", "verb": "grantPermission",
                            "pkg": "com.demo.hap",
                            "permissions": ["ohos.permission.CAMERA",
                                            "ohos.permission.LOCATION"]})
    assert res["result"] == "pass"
    shells = [c[1] for c in fake_hm[0].calls if c[0] == "shell"]
    assert shells == ["bm grant -n com.demo.hap -p ohos.permission.CAMERA",
                      "bm grant -n com.demo.hap -p ohos.permission.LOCATION"]


def test_harmony_grantpermission_nonzero_exit_honest_fail(fake_hm):
    pass
    adapter = _hm_adapter(fake_hm)
    fake_hm[0].shell_exit_code = 1
    res = adapter.execute({"action": "app.grantPermission", "verb": "grantPermission",
                           "pkg": "com.demo.hap",
                           "permissions": ["ohos.permission.CAMERA"]})
    assert res["result"] == "fail"
    assert "bm grant" in res["detail"] or "exit" in res["detail"]


def test_harmony_grantpermission_missing_permissions_rejected(fake_hm):
    adapter = _hm_adapter(fake_hm)
    res = adapter.execute({"action": "app.grantPermission", "verb": "grantPermission",
                           "pkg": "com.demo.hap"})
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "permissions" in res["detail"]


def test_harmony_capability_false_verb_honest_reject(fake_hm):
    pass
    descriptor = {**HM_DESCRIPTOR,
                  "capabilities": {**{v: True for v in IR_VERBS},
                                   "background": False}}
    adapter = mobile_engine_factory(dict(descriptor))
    adapter.prepare({"envProfile": {"appId": "com.demo.hap"},
                     "leaseRef": {"deviceId": 9, "serial": "h1"}, "runId": "r1"})
    res = adapter.execute({"action": "app.background", "verb": "background",
                           "durationSec": 3})
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "background" in res["detail"] and "HMNEXTAUTO" in res["detail"]
    if fake_hm:
        assert fake_hm[0].calls == [], "能力位拒必须先于引擎触达"



@pytest.mark.parametrize("descriptor,session_attr", [
    (HM_DESCRIPTOR, "_driver"),
    (MINIUM_DESCRIPTOR, "_session"),
    (AUTOMATOR_DESCRIPTOR, "_prepared"),
    (APPIUM_DESCRIPTOR, "_prepared"),
])
def test_four_adapters_share_capability_gate_single_source(descriptor, session_attr):
    pass
    adapter = mobile_engine_factory(dict(descriptor))
    setattr(adapter, session_attr, object())
    marked = {**descriptor, "capabilities": {"home": False}}
    adapter.descriptor = marked
    res = adapter.execute({"action": "app.home", "verb": "home"})
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "home" in res["detail"] and descriptor["code"] in res["detail"]



class FakeMiniSession:
    def __init__(self, conf=None):
        self.calls: list[tuple] = []

    def navigate_to(self, url, params=None):
        self.calls.append(("navigate_to", url))

    def reLaunch(self, url):
        self.calls.append(("reLaunch", url))


@pytest.fixture()
def fake_mini(monkeypatch):
    from driver.mobile import minium_adapter

    created: list = []

    class FakeMinium:
        def __new__(cls, conf):
            session = FakeMiniSession(conf)
            created.append(session)
            return session

    module = SimpleNamespace(Minium=FakeMinium)
    monkeypatch.setattr(minium_adapter, "_load_minium", lambda: module)
    return created


def _mini_adapter(fake_mini):
    adapter = mobile_engine_factory(dict(MINIUM_DESCRIPTOR))
    adapter.prepare({"envProfile": {"appid": "wx1", "projectPath": "/tmp/p"},
                     "leaseRef": {"deviceId": 7, "serial": "d1"}, "runId": "r1"})
    return adapter


def test_minium_deeplink_navigates_page_path(fake_mini):
    adapter = _mini_adapter(fake_mini)
    res = adapter.execute({"action": "app.deepLink", "verb": "deepLink",
                           "url": "/pages/detail?id=1"})
    assert res["result"] == "pass"
    assert ("navigate_to", "/pages/detail?id=1") in fake_mini[0].calls, \
        "deepLink=minium navigate_to（L875 实证；页面栈跳转语义=url 直传页面路径）"


def test_minium_deeplink_missing_url_rejected(fake_mini):
    adapter = _mini_adapter(fake_mini)
    res = adapter.execute({"action": "app.deepLink", "verb": "deepLink"})
    assert res["result"] == "fail" and res["errorType"] == 1 and "url" in res["detail"]


def test_minium_openurl_honest_gap_reject(fake_mini):
    pass
    adapter = _mini_adapter(fake_mini)
    res = adapter.execute({"action": "app.openUrl", "verb": "openUrl",
                           "url": "https://example.com"})
    assert res["result"] == "fail"
    assert "openUrl" in res["detail"] and "未在 Minium 映射表落位" in res["detail"]



BRIDGE_SCRIPT = EXECUTOR_ROOT / "driver" / "mobile" / "automator_bridge.mjs"
FAKE_ENGINE = HERE / "fixtures" / "automator_fake_engine.mjs"


def _node_available() -> bool:
    import subprocess
    try:
        subprocess.run(["node", "--version"], capture_output=True, check=True)
        return True
    except Exception:  # noqa: BLE001
        return False


def _automator_on_real_bridge():
    from driver.mobile.bridge_process import NodeBridge
    bridge = NodeBridge(script_path=str(BRIDGE_SCRIPT), timeout_sec=15.0,
                        extra_env={"AUTOMATOR_TEST_ENGINE_MODULE": str(FAKE_ENGINE)})
    bridge.start()
    adapter = mobile_engine_factory(dict(AUTOMATOR_DESCRIPTOR))
    adapter._bridge = bridge
    adapter.prepare({"envProfile": {"appid": "wx1", "projectPath": "/tmp/p",
                                    "cliPath": "/opt/wxcli", "autoPort": 9420},
                     "leaseRef": {"deviceId": 7, "serial": "devtools-1"},
                     "runId": "r1"})
    return adapter


@pytest.mark.skipif(not _node_available(), reason="node 环境件不可达（如实标注，L4 另刀）")
def test_automator_bridge_deeplink_openurl_pass_through_callwxmethod():
    adapter = _automator_on_real_bridge()
    try:
        deep = adapter.execute({"action": "app.deepLink", "verb": "deepLink",
                                "url": "/pages/detail?id=1"})
        assert deep["result"] == "pass", f"automator deepLink 红: {deep}"
        openr = adapter.execute({"action": "app.openUrl", "verb": "openUrl",
                                 "url": "/pages/web?url=https://example.com"})
        assert openr["result"] == "pass", f"automator openUrl 红: {openr}"
        bad = adapter.execute({"action": "app.deepLink", "verb": "deepLink"})
        assert bad["result"] == "fail" and "url" in bad["detail"], "缺参=脚本面人话拒"
    finally:
        adapter.teardown()



class FakeAppiumDriver:
    def __init__(self):
        self.calls: list = []

    def execute_script(self, script, *args):
        self.calls.append((script, args))
        return {"ok": True}


def test_appium_deeplink_uses_mobile_script_best_effort():
    adapter = mobile_engine_factory(dict(APPIUM_DESCRIPTOR))
    adapter._prepared = True
    driver = FakeAppiumDriver()
    adapter._driver = driver
    res = adapter.execute({"action": "app.deepLink", "verb": "deepLink",
                           "url": "myapp://open?foo=1", "pkg": "com.demo"})
    assert res["result"] == "pass", f"appium deepLink 红: {res}"
    assert ("mobile: deepLink", ({"url": "myapp://open?foo=1", "pkg": "com.demo"},)) \
        in driver.calls


def test_appium_deeplink_missing_url_script_reject():
    adapter = mobile_engine_factory(dict(APPIUM_DESCRIPTOR))
    adapter._prepared = True
    driver = FakeAppiumDriver()
    adapter._driver = driver
    res = adapter.execute({"action": "app.deepLink", "verb": "deepLink"})
    assert res["result"] == "fail" and res["errorType"] == 1 and "url" in res["detail"]
    assert driver.calls == [], "缺参先闸不得触达引擎脚本面"
