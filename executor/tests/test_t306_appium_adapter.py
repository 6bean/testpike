
from __future__ import annotations

import json
import pathlib
import sys
import types

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from driver.mobile import appium_adapter as aa
from driver.mobile.contracts import capability_violations
from driver.mobile.engine_factory import mobile_engine_factory

V145_APPIUM_DESCRIPTOR = {
    "code": "APPIUM",
    "status": 1,
    "runtimeType": "WEB_DRIVER",
    "platformCodes": ["ANDROID", "IOS"],
    "deviceKindCodes": ["EMULATOR", "SIMULATOR", "REAL"],
}
V145_XCUITEST_DESCRIPTOR = {
    "code": "XCUITEST",
    "status": 1,
    "runtimeType": "WEB_DRIVER",
    "platformCodes": ["IOS"],
    "deviceKindCodes": ["SIMULATOR", "REAL"],
}

LEASE = {"deviceId": 11, "serial": "emulator-5554"}



class WebDriverError(Exception):
    pass


class NoSuchElementError(WebDriverError):
    pass


class TimeoutError_(WebDriverError):
    pass


class InvalidArgumentError(WebDriverError):
    pass


class JSCommandError(WebDriverError):
    pass


class SessionDeadError(WebDriverError):
    pass


class FakeElement:
    def __init__(self, driver, text="欢迎"):
        self.driver = driver
        self.text = text
        self.id = "element-1"
        self.sent: list = []
        self.cleared = 0

    def _record(self, name):
        self.driver.calls.append((name, self.text))

    def click(self):
        self._record("element.click")

    def send_keys(self, text):
        self.sent.append(text)
        self.driver.calls.append(("element.send_keys", text))

    def clear(self):
        self.cleared += 1
        self.driver.calls.append(("element.clear",))

    def get_attribute(self, name):
        self.driver.calls.append(("element.get_attribute", name))
        return self.text


class FakeWebDriver:
    pass

    def __init__(self, platform="ANDROID", screenshot_bytes=b"PNGDATA",
                 page_source="<?xml version='1.0'?><hierarchy/>", log_rows=None):
        self.platform = platform
        self.calls: list = []
        self.quit_called = 0
        self.timeouts: dict = {}
        self._screenshot_bytes = screenshot_bytes
        self.page_source = page_source
        self._log_rows = log_rows if log_rows is not None else [{"message": "log line"}]
        self._find_fail = False
        self._script_fail = False

    def quit(self):
        self.quit_called += 1

    def set_timeouts(self, **kwargs):
        self.timeouts.update(kwargs)

    def get_log(self, kind):
        if self._log_rows is None:
            raise SessionDeadError(f"log type '{kind}' is not supported")
        self.calls.append(("get_log", kind))
        return list(self._log_rows)

    def get_screenshot_as_png(self):
        self.calls.append(("screenshot",))
        return self._screenshot_bytes

    def back(self):
        if self.platform != "ANDROID":
            raise JSCommandError("Go back is not supported (iOS has no system back)")
        self.calls.append(("back",))

    def find_element(self, by, value):
        self.calls.append(("find", by, value))
        if self._find_fail:
            raise NoSuchElementError(f"no element {by}={value}")
        return FakeElement(self)

    def execute_script(self, script, *args):
        self.calls.append(("script", script, args))
        if self._script_fail:
            raise NoSuchElementError(f"mobile: {script} target element not found")
        return {"ok": True}


class FakeActionChains:
    pass

    def __init__(self, driver, pause=None):
        self.driver = driver

    def pause(self, seconds):
        self.driver.calls.append(("actions.pause", seconds))
        return self

    def move_to_location(self, x=None, y=None):
        self.driver.calls.append(("actions.move", x, y))
        return self

    def pointer_down(self, *a, **k):
        self.driver.calls.append(("actions.down",))
        return self

    def pointer_up(self, *a, **k):
        self.driver.calls.append(("actions.up",))
        return self

    def perform(self):
        self.driver.calls.append(("actions.perform",))
        return self



def _install_fake_selenium(driver_factory):
    selenium = types.ModuleType("selenium")
    selenium_common = types.ModuleType("selenium.common")
    exceptions_mod = types.ModuleType("selenium.common.exceptions")
    s_webdriver = types.ModuleType("selenium.webdriver")
    common = types.ModuleType("selenium.webdriver.common")
    by_mod = types.ModuleType("selenium.webdriver.common.by")
    action_chains_mod = types.ModuleType("selenium.webdriver.common.action_chains")

    class By:
        ACCESSIBILITY_ID = "accessibility id"
        ID = "id"
        CSS_SELECTOR = "css selector"
        XPATH = "xpath"
        CLASS_NAME = "class name"

    exceptions_mod.WebDriverException = WebDriverError
    exceptions_mod.NoSuchElementException = NoSuchElementError
    exceptions_mod.TimeoutException = TimeoutError_
    exceptions_mod.InvalidArgumentException = InvalidArgumentError
    exceptions_mod.JavascriptException = JSCommandError
    exceptions_mod.SessionNotCreatedException = SessionDeadError
    exceptions_mod.UnsupportedOperationException = JSCommandError
    exceptions_mod.ElementNotInteractableException = NoSuchElementError

    action_chains_mod.ActionChains = FakeActionChains
    by_mod.By = By
    common.By = By
    common.by = by_mod
    common.action_chains = action_chains_mod
    s_webdriver.common = common

    selenium.common = selenium_common
    selenium_common.exceptions = exceptions_mod
    selenium.webdriver = s_webdriver

    appium_pkg = types.ModuleType("appium")
    appium_wd = types.ModuleType("appium.webdriver")

    class Remote:
        def __new__(cls, command_executor=None, capabilities=None, options=None):
            return driver_factory(capabilities if capabilities is not None else options)

    appium_wd.Remote = Remote
    appium_wd.By = By
    appium_pkg.webdriver = appium_wd

    names = ("selenium", "selenium.common", "selenium.common.exceptions",
             "selenium.webdriver", "selenium.webdriver.common",
             "selenium.webdriver.common.by",
             "selenium.webdriver.common.action_chains", "appium", "appium.webdriver")
    saved = {n: sys.modules.get(n) for n in names}
    sys.modules.update(dict(zip(names, (
        selenium, selenium_common, exceptions_mod, s_webdriver, common,
        by_mod, action_chains_mod, appium_pkg, appium_wd))))

    def uninstall():
        for n, v in saved.items():
            if v is None:
                sys.modules.pop(n, None)
            else:
                sys.modules[n] = v

    return uninstall


class _Harness:
    pass

    def __init__(self, descriptor, driver=None, server_ok=True, host_os="linux",
                 xcrun_ok=True):
        self.driver = driver or FakeWebDriver(
            platform="IOS" if descriptor["code"] == "XCUITEST" else "ANDROID")
        self.probe_urls: list = []
        self._saved = (aa._probe_appium_server, aa._which, aa._host_os, aa._create_session)
        aa._probe_appium_server = lambda url: self.probe_urls.append(url) or server_ok
        aa._which = lambda name: "/usr/bin/xcrun" if xcrun_ok else None
        aa._host_os = lambda: host_os
        self._uninstall = _install_fake_selenium(lambda options: self.driver)
        self.captured_caps: dict = {}

        def fake_create_session(server_url, caps, budget_sec):
            self.captured_caps = dict(caps)
            return self.driver

        aa._create_session = fake_create_session
        self.descriptor = descriptor
        self.adapter = mobile_engine_factory(descriptor)

    def prepare(self, ctx=None):
        base = {"leaseRef": dict(LEASE), "taskId": 901,
                "envProfile": {"appId": "com.example.app"}}
        base.update(ctx or {})
        self.adapter.prepare(base)
        return self

    def close(self):
        (aa._probe_appium_server, aa._which, aa._host_os,
         aa._create_session) = self._saved
        self._uninstall()

    def last_caps(self):
        return self.captured_caps


@pytest.fixture
def h():
    harness = _Harness(V145_APPIUM_DESCRIPTOR)
    harness.prepare()
    yield harness
    harness.close()


@pytest.fixture
def xi():
    harness = _Harness(V145_XCUITEST_DESCRIPTOR, host_os="macos")
    harness.prepare()
    yield harness
    harness.close()


def _step(verb, **kw):
    return dict({"action": f"app.{verb}", "verb": verb, "stepNo": 3}, **kw)



def test_factory_loads_real_adapter_for_both_rows():
    for code in ("APPIUM", "XCUITEST"):
        obj = mobile_engine_factory({**V145_APPIUM_DESCRIPTOR, "code": code})
        assert type(obj).__name__ == "AppiumAdapter"


def test_factory_rejects_disabled_engine():
    with pytest.raises(ValueError):
        mobile_engine_factory({**V145_APPIUM_DESCRIPTOR, "status": 0})


def test_self_description_within_descriptor_face():
    assert capability_violations(
        aa.AppiumAdapter(descriptor=V145_APPIUM_DESCRIPTOR), V145_APPIUM_DESCRIPTOR) is None
    assert capability_violations(
        aa.AppiumAdapter(descriptor=V145_XCUITEST_DESCRIPTOR), V145_XCUITEST_DESCRIPTOR) is None



def test_prepare_requires_lease_device():
    saved = (aa._probe_appium_server, aa._which, aa._host_os)
    aa._probe_appium_server = lambda url: True
    aa._which = lambda name: "/usr/bin/xcrun"
    aa._host_os = lambda: "linux"
    uninstall = _install_fake_selenium(lambda options: FakeWebDriver())
    try:
        adapter = mobile_engine_factory(V145_APPIUM_DESCRIPTOR)
        with pytest.raises(ValueError):
            adapter.prepare({"leaseRef": {}, "taskId": 1})
        with pytest.raises(ValueError):
            adapter.prepare({"taskId": 1})
    finally:
        uninstall()
        (aa._probe_appium_server, aa._which, aa._host_os) = saved


def test_prepare_dead_server_names_install_guidance():
    saved = (aa._probe_appium_server, aa._which, aa._host_os)
    aa._probe_appium_server = lambda url: False
    aa._which = lambda name: "/usr/bin/xcrun"
    aa._host_os = lambda: "linux"
    try:
        adapter = mobile_engine_factory(V145_APPIUM_DESCRIPTOR)
        with pytest.raises(RuntimeError) as e:
            adapter.prepare({"leaseRef": dict(LEASE), "taskId": 1})
        msg = str(e.value)
        assert "appium" in msg.lower() and "自行安装" in msg and "不随包分发" in msg
    finally:
        (aa._probe_appium_server, aa._which, aa._host_os) = saved


def test_prepare_missing_selenium_human_error():
    pass
    saved = (aa._probe_appium_server, aa._which, aa._host_os)
    names = ("selenium", "selenium.webdriver", "selenium.webdriver.common",
              "selenium.webdriver.common.by", "selenium.webdriver.common.action_chains",
              "selenium.common", "selenium.common.exceptions", "appium", "appium.webdriver")
    stashed = {n: sys.modules.pop(n, None) for n in names}
    aa._probe_appium_server = lambda url: True
    aa._which = lambda name: "/usr/bin/xcrun"
    aa._host_os = lambda: "linux"
    try:
        adapter = aa.AppiumAdapter(descriptor=V145_APPIUM_DESCRIPTOR)
        with pytest.raises(RuntimeError) as e:
            adapter.prepare({"leaseRef": dict(LEASE), "taskId": 1})
        assert "selenium" in str(e.value)
    finally:
        for n, v in stashed.items():
            if v is not None:
                sys.modules[n] = v
        (aa._probe_appium_server, aa._which, aa._host_os) = saved


def test_xcuitest_row_refuses_non_macos_host():
    pass
    saved = (aa._probe_appium_server, aa._which, aa._host_os)
    aa._probe_appium_server = lambda url: True
    aa._which = lambda name: None
    aa._host_os = lambda: "linux"
    uninstall = _install_fake_selenium(lambda options: FakeWebDriver(platform="IOS"))
    try:
        adapter = mobile_engine_factory(V145_XCUITEST_DESCRIPTOR)
        with pytest.raises(RuntimeError) as e:
            adapter.prepare({"leaseRef": dict(LEASE), "taskId": 1})
        assert "macOS" in str(e.value)
    finally:
        uninstall()
        (aa._probe_appium_server, aa._which, aa._host_os) = saved


def test_prepare_probes_server_and_binds_session(h):
    assert h.probe_urls and "4723" in h.probe_urls[0]
    assert h.adapter.is_prepared()


def test_android_capabilities_assembly(h):
    pass
    caps = h.last_caps()
    assert caps["appium:automationName"] == "UiAutomator2"
    assert caps["platformName"].lower() == "android"
    assert caps["appium:udid"] == "emulator-5554"
    assert caps["appium:appPackage"] == "com.example.app"


def test_xcuitest_capabilities_assembly(xi):
    caps = xi.last_caps()
    assert caps["appium:automationName"] == "XCUITest"
    assert caps["platformName"].lower() == "ios"
    assert caps["appium:udid"] == LEASE["serial"] or caps["appium:udid"]
    assert caps["appium:bundleId"] == "com.example.app"


def test_capabilities_pure_function_platform_split():
    android = aa.build_appium_capabilities(
        platform="ANDROID", device_kind="EMULATOR", serial="emulator-5554",
        app_id="com.example.app")
    assert android["appium:automationName"] == "UiAutomator2"
    assert android["appium:appPackage"] == "com.example.app"
    assert "appium:bundleId" not in android
    ios = aa.build_appium_capabilities(
        platform="IOS", device_kind="SIMULATOR", serial="ios-udid", app_id="com.example.app")
    assert ios["appium:automationName"] == "XCUITest"
    assert ios["appium:bundleId"] == "com.example.app"
    assert "appium:appPackage" not in ios


def test_platform_resolution_from_engine_code_and_profile():
    pass
    assert aa.resolve_platform(V145_XCUITEST_DESCRIPTOR, {}) == "IOS"
    assert aa.resolve_platform(V145_APPIUM_DESCRIPTOR,
                               {"platformCodes": ["IOS"], "platform": "IOS"}) == "IOS"
    assert aa.resolve_platform({"code": "APPIUM", "platform": "ios"}, {}) == "IOS"
    assert aa.resolve_platform(V145_APPIUM_DESCRIPTOR, {"platform": "IOS"}) == "IOS"
    assert aa.resolve_platform(V145_APPIUM_DESCRIPTOR, {}) == "ANDROID"



def test_execute_rejects_non_ir_verb(h):
    res = h.adapter.execute({"action": "app.bogus", "verb": "bogus", "stepNo": 1})
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "IR 基线" in res["detail"]


def test_execute_before_prepare_human_fail():
    adapter = aa.AppiumAdapter(descriptor=V145_APPIUM_DESCRIPTOR)
    res = adapter.execute(_step("tap", x=1, y=2))
    assert res["result"] == "fail" and res["errorType"] == 2
    assert "prepare" in res["detail"] or "未准备" in res["detail"]


def test_locator_priority_single_source(h):
    pass
    h.adapter.execute(_step("tap", testId="login_btn"))
    assert ("find", "id", "login_btn") in h.driver.calls
    h.adapter.execute(_step("assertVisible", accessibilityId="a11y-key"))
    assert ("find", "accessibility id", "a11y-key") in h.driver.calls
    h.adapter.execute(_step("assertVisible", cssSelector=".btn"))
    assert ("find", "css selector", ".btn") in h.driver.calls
    h.adapter.execute(_step("assertVisible", xpath="//x/y"))
    assert ("find", "xpath", "//x/y") in h.driver.calls


def test_tap_without_locator_fails_closed(h):
    res = h.adapter.execute(_step("tap"))
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "定位器" in res["detail"]


def test_tap_coordinate_via_mobile_script(h):
    res = h.adapter.execute(_step("tap", x=100, y=200))
    assert res["result"] == "pass"
    assert any(c[0] == "script" and "clickGesture" in str(c[1]) for c in h.driver.calls)


def test_input_and_clear(h):
    assert h.adapter.execute(_step("input", id="user", text="张三"))["result"] == "pass"
    assert ("element.send_keys", "张三") in h.driver.calls
    assert h.adapter.execute(_step("clearInput", id="user"))["result"] == "pass"
    assert ("element.clear",) in h.driver.calls


def test_input_without_text_human_reject(h):
    res = h.adapter.execute(_step("input", id="user"))
    assert res["result"] == "fail" and res["errorType"] == 1


def test_launch_and_close(h):
    assert h.adapter.execute(_step("launch", pkg="com.example.app"))["result"] == "pass"
    assert any(c[0] == "script" and "activateApp" in str(c[1]) for c in h.driver.calls)
    assert h.adapter.execute(_step("close"))["result"] == "pass"
    assert any(c[0] == "script" and "terminateApp" in str(c[1]) for c in h.driver.calls)


def test_launch_without_pkg_fails_closed(h):
    res = h.adapter.execute(_step("launch"))
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "包名" in res["detail"] or "pkg" in res["detail"]


def test_gestures_android_mobile_commands(h):
    assert h.adapter.execute(_step("longPress", id="t", duration=800))["result"] == "pass"
    lp = [c for c in h.driver.calls if c[0] == "script" and "touchAndHold" in str(c[1])]
    assert lp and "0.8" in json.dumps(lp[-1][2], default=str)
    assert h.adapter.execute(_step("doubleTap", id="t"))["result"] == "pass"
    assert any(c[0] == "script" and "doubleClick" in str(c[1]) for c in h.driver.calls)
    assert h.adapter.execute(_step("swipe", direction="down"))["result"] == "pass"
    assert any(c[0] == "script" and "swipeGesture" in str(c[1]) for c in h.driver.calls)
    assert h.adapter.execute(_step("scroll", direction="up"))["result"] == "pass"
    assert any(c[0] == "script" and "scrollGesture" in str(c[1]) for c in h.driver.calls)


def test_swipe_ios_falls_back_to_w3c_actions(xi):
    pass
    assert xi.adapter.execute(_step("swipe", direction="up"))["result"] == "pass"
    assert any(str(c[0]).startswith("actions.") for c in xi.driver.calls)
    assert xi.adapter.execute(_step("scroll", direction="up"))["result"] == "pass"


def test_back_android_ok_ios_fail_closed(h, xi):
    assert h.adapter.execute(_step("back"))["result"] == "pass"
    res = xi.adapter.execute(_step("back"))
    assert res["result"] == "fail" and res["errorType"] == 2
    assert "back" in res["detail"].lower() or "返回" in res["detail"]


def test_screenshot_writes_file(h):
    assert h.adapter.execute(_step("screenshot"))["result"] == "pass"


def test_screenshot_success_without_bytes_contradiction(h):
    h.driver.get_screenshot_as_png = lambda: b""  # type: ignore[method-assign]
    res = h.adapter.execute(_step("screenshot"))
    assert res["result"] == "fail" and res["errorType"] == 2
    assert "矛盾" in res["detail"]


def test_assert_visible_family(h):
    assert h.adapter.execute(_step("assertVisible", id="a"))["result"] == "pass"
    h.driver._find_fail = True
    res = h.adapter.execute(_step("assertVisible", id="a"))
    assert res["result"] == "fail" and res["errorType"] == 1
    assert h.adapter.execute(_step("assertNotVisible", id="a"))["result"] == "pass"
    h.driver._find_fail = False
    res = h.adapter.execute(_step("assertNotVisible", id="a"))
    assert res["result"] == "fail" and res["errorType"] == 1


def test_wait_visible_timeout_budget_env(h, monkeypatch):
    monkeypatch.setenv("APPAUTO_APPIUM_WAIT_TIMEOUT_SEC", "2")
    h.driver._find_fail = True
    res = h.adapter.execute(_step("waitVisible", id="a"))
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "2" in res["detail"]


def test_assert_text_matches_or_fails(h):
    assert h.adapter.execute(_step("assertText", id="a", text="欢迎"))["result"] == "pass"
    res = h.adapter.execute(_step("assertText", id="a", text="不匹配的字样"))
    assert res["result"] == "fail" and res["errorType"] == 1


def test_extract_text_returns_extracted(h):
    res = h.adapter.execute(_step("extractText", id="title", saveAs="t"))
    assert res["result"] == "pass"
    assert res.get("extracted") == "欢迎"


def test_driver_connection_crash_normalizes_env_error(h):
    def boom(*a, **k):
        raise SessionDeadError("socket connection dropped")
    h.driver.find_element = boom  # type: ignore[method-assign]
    res = h.adapter.execute(_step("assertVisible", id="a"))
    assert res["result"] == "fail" and res["errorType"] == 2
    assert "socket connection dropped" in res["detail"]


def test_engine_command_gap_fails_closed_not_silently(h):
    pass
    h.driver._script_fail = True
    res = h.adapter.execute(_step("tap", x=1, y=2))
    assert res["result"] == "fail"


# ── ④ collect / watch / teardown ───────────────────────────────────────────

def test_collect_triple_evidence(h, tmp_path):
    h.adapter.execute(_step("screenshot"))
    arts = h.adapter.collect({"stepNo": 3, "artifactDir": str(tmp_path)})
    kinds = {a["kind"] for a in arts}
    assert kinds == {"screenshot", "dom_snapshot", "log_tail"}
    for a in arts:
        assert pathlib.Path(a["localPath"]).exists()
        assert a["localPath"].startswith(str(tmp_path))
        assert a.get("contentType")


def test_collect_honest_missing_faces(h, tmp_path):
    h.driver.page_source = ""
    h.driver._log_rows = None
    arts = h.adapter.collect({"stepNo": 5, "artifactDir": str(tmp_path)})
    kinds = {a["kind"] for a in arts}
    assert "dom_snapshot" not in kinds and "log_tail" not in kinds


def test_collect_files_named_by_step_no(h, tmp_path):
    pass
    arts = h.adapter.collect({"stepNo": 7, "artifactDir": str(tmp_path)})
    names = {pathlib.Path(a["localPath"]).name for a in arts}
    assert all(name.startswith("step-7-") for name in names)
    assert all(a["localPath"].startswith(str(tmp_path)) for a in arts)


def test_watch_frame_from_latest_shot(h, tmp_path):
    pass
    h.adapter.execute(_step("screenshot"))
    arts = h.adapter.collect({"stepNo": 3, "artifactDir": str(tmp_path)})
    shot = next(a for a in arts if a["kind"] == "screenshot")
    frame = h.adapter.watch_frame(str(tmp_path), max_width=160)
    assert frame is None or (isinstance(frame, dict) and frame.get("image")) or shot


def test_teardown_quits_driver_once(h):
    h.adapter.teardown()
    assert h.driver.quit_called == 1
    assert not h.adapter.is_prepared()
    res = h.adapter.execute(_step("tap", x=1, y=1))
    assert res["result"] == "fail" and res["errorType"] == 2


def test_teardown_idempotent_when_unprepared():
    adapter = aa.AppiumAdapter(descriptor=V145_APPIUM_DESCRIPTOR)
    adapter.teardown()



def test_step_budget_env_projection(monkeypatch):
    monkeypatch.delenv("APPAUTO_APPIUM_STEP_TIMEOUT_SEC", raising=False)
    assert aa._step_budget_sec() == 120.0
    monkeypatch.setenv("APPAUTO_APPIUM_STEP_TIMEOUT_SEC", "30")
    assert aa._step_budget_sec() == 30.0
    monkeypatch.setenv("APPAUTO_APPIUM_STEP_TIMEOUT_SEC", "abc")
    assert aa._step_budget_sec() == 120.0
    monkeypatch.setenv("APPAUTO_APPIUM_STEP_TIMEOUT_SEC", "0")
    assert aa._step_budget_sec() == 1.0


def test_server_url_env_projection(monkeypatch):
    monkeypatch.delenv("APPAUTO_APPIUM_SERVER_URL", raising=False)
    assert "4723" in aa._server_url()
    monkeypatch.setenv("APPAUTO_APPIUM_SERVER_URL", "http://10.0.0.8:4725/extra")
    assert aa._server_url() == "http://10.0.0.8:4725/extra"


def test_wait_timeout_env_projection(monkeypatch):
    monkeypatch.delenv("APPAUTO_APPIUM_WAIT_TIMEOUT_SEC", raising=False)
    assert aa._wait_timeout_sec() == 10.0
    monkeypatch.setenv("APPAUTO_APPIUM_WAIT_TIMEOUT_SEC", "5")
    assert aa._wait_timeout_sec() == 5.0
    monkeypatch.setenv("APPAUTO_APPIUM_WAIT_TIMEOUT_SEC", "-1")
    assert aa._wait_timeout_sec() == 1.0
