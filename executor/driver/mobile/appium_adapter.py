
from __future__ import annotations

import os
import pathlib
import tempfile
import time

from actions_common import APP_NAMESPACE, IR_VERBS, app_action_violation, app_verb
from driver.mobile.contracts import (
    is_hybrid_carrier,
    monitor_frame_from_artifacts,
    require_step_param,
    step_requests_webview,
    verb_capability_violation,
    webview_step_violation,
)

_ERROR_TYPE_SCRIPT = 1
_ERROR_TYPE_ENV = 2

_SCRIPT_EXC_KEYWORDS: tuple = ("NoSuchElement", "Timeout", "InvalidArgument", "Value",
                               "ElementNotInteractable", "ElementClickIntercepted",
                               "StaleElementReference")


def _env_seconds(key: str, default: float) -> float:
    pass
    raw = os.environ.get(key, "").strip()
    try:
        return max(float(raw) if raw else default, 1.0)
    except ValueError:
        return default


def _step_budget_sec() -> float:
    pass
    return _env_seconds("APPAUTO_APPIUM_STEP_TIMEOUT_SEC", 120.0)


def _wait_timeout_sec() -> float:
    pass
    return _env_seconds("APPAUTO_APPIUM_WAIT_TIMEOUT_SEC", 10.0)


def _server_url() -> str:
    pass
    return os.environ.get("APPAUTO_APPIUM_SERVER_URL", "").strip() or "http://127.0.0.1:4723"


def _which(name: str):
    pass
    from shutil import which
    return which(name)


def _host_os() -> str:
    pass
    return os.uname().sysname.lower()


def _probe_appium_server(url: str) -> bool:
    pass
    try:
        import requests

        resp = requests.get(url.rstrip("/") + "/status", timeout=5)
        return resp.status_code == 200
    except Exception:
        return False


def resolve_platform(descriptor: dict, env_profile: dict) -> str:
    pass
    for src in (descriptor or {}, env_profile or {}):
        raw = str(src.get("platform") or "").strip().upper()
        if raw in ("ANDROID", "IOS"):
            return raw
    codes = [str(c).strip().upper() for c in (descriptor or {}).get("platformCodes") or []]
    if len(set(codes)) == 1 and codes[0] in ("ANDROID", "IOS"):
        return codes[0]
    return "ANDROID"


def build_appium_capabilities(platform: str, device_kind: str,
                              serial: str | None, app_id: str | None) -> dict:
    pass
    ios = str(platform).upper() == "IOS"
    caps: dict = {
        "platformName": "iOS" if ios else "Android",
        "appium:automationName": "XCUITest" if ios else "UiAutomator2",
    }
    if serial:
        caps["appium:udid"] = str(serial)
    if app_id:
        caps["appium:bundleId" if ios else "appium:appPackage"] = str(app_id)
    if device_kind:
        caps["appium:deviceKind"] = str(device_kind)
    caps["appium:newCommandTimeout"] = int(_step_budget_sec() * 2)
    caps["appium:noReset"] = True
    return caps


def _load_selenium():
    pass
    try:
        from appium.webdriver import Remote
        from selenium.common import exceptions as selenium_exceptions
        from selenium.webdriver.common.action_chains import ActionChains
        from selenium.webdriver.common.by import By
    except ImportError as e:
        raise RuntimeError(
            "执行器缺 selenium WebDriver client / Appium-Python-Client（Apache-2.0，"
            "pip 依赖已在 executor/requirements.txt 钉版）：当前 venv 未装载——生产执行机"
            "经 installkit 独立 venv 安装（缺件归因禁静默降级换引擎）") from e
    return {"Remote": Remote, "By": By, "ActionChains": ActionChains,
            "exceptions": selenium_exceptions}


def _create_session(server_url: str, caps: dict, budget_sec: float):
    pass
    selenium = _load_selenium()
    return selenium["Remote"](command_executor=server_url, capabilities=caps)


class AppiumAdapter:
    pass

    def __init__(self, descriptor: dict | None = None):
        self.descriptor = descriptor or {}
        declared = [str(c).strip().upper()
                    for c in self.descriptor.get("platformCodes") or []]
        self.SUPPORTED_PLATFORMS: tuple = tuple(
            c for c in declared if c in ("ANDROID", "IOS")) or ("ANDROID", "IOS")
        declared_kinds = [str(c).strip().upper()
                          for c in self.descriptor.get("deviceKindCodes") or []]
        self.SUPPORTED_DEVICE_KINDS: tuple = tuple(
            c for c in declared_kinds
            if c in ("EMULATOR", "SIMULATOR", "REAL")) or ("EMULATOR", "SIMULATOR", "REAL")
        self.CAPABILITIES: tuple = IR_VERBS
        self._driver = None
        self._prepared = False
        self._platform = "ANDROID"
        self._app_id: str | None = None
        self._env_profile: dict = {}
        self._run_seq = 0
        self._artifact_dir = pathlib.Path(tempfile.gettempdir(), "testpike-appauto")

    def is_prepared(self) -> bool:
        return self._prepared

    # ── prepare ────────────────────────────────────────────────────────────

    def prepare(self, ctx: dict) -> None:
        lease = (ctx or {}).get("leaseRef") or {}
        if not lease.get("deviceId"):
            raise ValueError("Appium 会话准备失败：设备租约未确认（信封 leaseRef "
                             "缺设备标识），禁止裸跑引擎")
        env_profile = ctx.get("envProfile") or {}
        self._platform = resolve_platform(self.descriptor, env_profile)
        self._assert_host_supports_platform()
        server = _server_url()
        if not _probe_appium_server(server):
            raise RuntimeError(
                f"Appium server 不在位（探活失败：{server}）：appium 为 Node 服务进程，"
                "需在执行机自行安装并常驻（默认端口 4723，testPike 不随包分发），"
                "Android 另需 UiAutomator2 driver、iOS 需 macOS 宿主装 XCUITest driver"
                "（缺件归因禁静默降级换引擎，装机指引=appium.io 官方文档）")
        _load_selenium()
        self._app_id = (str(env_profile.get("appId") or env_profile.get("bundleId")
                          or env_profile.get("pkg") or "").strip() or None)
        self._env_profile = env_profile
        caps = build_appium_capabilities(
            platform=self._platform,
            device_kind=str(ctx.get("deviceKind") or lease.get("deviceKind") or "").strip(),
            serial=lease.get("serial"), app_id=self._app_id)
        self._driver = _create_session(server, caps, _step_budget_sec())
        root = self._artifact_dir / f"appium-{ctx.get('taskId') or ctx.get('runId') or 'run'}"
        root.mkdir(parents=True, exist_ok=True)
        self._artifact_dir = root
        self._prepared = True

    def _assert_host_supports_platform(self) -> None:
        pass
        if self._platform != "IOS":
            return
        if _host_os() not in ("darwin", "macos") or _which("xcrun") is None:
            raise RuntimeError(
                "iOS 会话要求 macOS + Xcode 宿主（xcrun 在位）：当前执行机不满足"
                "——请把该 iOS 用例派发到 macOS 执行器池（V145 描述符 host_os 锁），"
                "或改用 Android 支持面")

    # ── execute ────────────────────────────────────────────────────────────

    def execute(self, step: dict) -> dict:
        action = step.get("action")
        verb = step.get("verb") or app_verb(action) or str(action or "")
        if verb not in IR_VERBS:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": app_action_violation(APP_NAMESPACE + verb)
                              + "——请修正脚本或走能力位"}
        violation = webview_step_violation(self.descriptor, step)
        if violation is not None:
            return violation
        if not self._prepared:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": "引擎会话未准备（prepare 未执行或半途失败），禁止裸步下发"}
        caps_violation = verb_capability_violation(self.descriptor, verb)
        if caps_violation is not None:
            return caps_violation
        self._run_seq += 1
        handler = getattr(self, f"_do_{verb}", None)
        if handler is None:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"动词 {verb} 未在 Appium 适配器映射表落位（引擎侧缺口，"
                              "禁静默成功）——请换引擎执行该步或走能力位"}
        if step_requests_webview(step):
            return self._in_webview_context(step, verb, handler)
        try:
            return handler(step)
        except Exception as e:
            return self._normalize_exc(e, verb)


    def _webview_target_context(self, step: dict) -> str | None:
        pass
        try:
            contexts = [str(c) for c in (self._driver.contexts or [])]
        except Exception:
            return None
        if self._platform != "IOS":
            pkg = str(step.get("pkg") or step.get("appId") or "").strip() or self._app_id
            if pkg:
                exact = "WEBVIEW_" + pkg
                if exact in contexts:
                    return exact
        return next((c for c in contexts if c.upper().startswith("WEBVIEW")), None)

    def _in_webview_context(self, step: dict, verb: str, handler) -> dict:
        pass
        target = self._webview_target_context(step)
        if target is None:
            carrier = ("环境档案已声明混合载体（hybrid）但会话上下文表未见 WEBVIEW 落区"
                       if is_hybrid_carrier(self._env_profile) else
                       "该步标记落 WebView 区域（webview 旗标）而会话上下文表未见 WEBVIEW 落区"
                       "——混合载体应用请在环境档案声明 hybrid，并确认 WebView 已渲染可调试")
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"Appium 无法切入 WebView 上下文（{carrier}）——请确认目标页面"
                              "已打开 WebView 内容（Android 需 WebView 调试使能/Chromedriver"
                              "可附着；iOS 为 WKWebView 形态），或换用支持该落区的执行形态"}
        try:
            self._driver.switch_to.context = target
            try:
                return handler(step)
            except Exception as e:
                return self._normalize_exc(e, verb)
        finally:
            try:
                self._driver.switch_to.context = "NATIVE_APP"
            except Exception:
                pass

    def _normalize_exc(self, exc: Exception, verb: str) -> dict:
        pass
        name = type(exc).__name__
        script_side = any(k in name for k in _SCRIPT_EXC_KEYWORDS)
        return {"result": "fail",
                "errorType": _ERROR_TYPE_SCRIPT if script_side else _ERROR_TYPE_ENV,
                "detail": f"Appium {verb} 失败（{name}）: {exc}"}


    def _locator(self, step: dict, verb: str) -> tuple | None:
        pass
        by = _load_selenium()["By"]
        if step.get("testId"):
            return (by.ACCESSIBILITY_ID if self._platform == "IOS" else by.ID,
                    str(step["testId"]))
        if step.get("accessibilityId"):
            return (by.ACCESSIBILITY_ID, str(step["accessibilityId"]))
        if step.get("text") and verb not in ("assertText", "input"):
            value = str(step["text"]).replace('"', "")
            return (by.XPATH, f'//*[@text="{value}" or @content-desc="{value}"]')
        if step.get("placeholder"):
            value = str(step["placeholder"]).replace('"', "")
            return (by.XPATH, f'//*[@content-desc="{value}"]')
        if step.get("id"):
            return (by.ID, str(step["id"]))
        if step.get("className"):
            return (by.CLASS_NAME, str(step["className"]))
        if step.get("cssSelector"):
            return (by.CSS_SELECTOR, str(step["cssSelector"]))
        if step.get("xpath"):
            return (by.XPATH, str(step["xpath"]))
        return None

    def _require_locator(self, verb: str, step: dict) -> tuple:
        loc = self._locator(step, verb)
        if loc is None:
            raise ValueError(f"动词 {verb} 需要定位器（testId/accessibilityId/text/"
                             "placeholder/id/className/cssSelector/xpath）或坐标（x/y），"
                             "信封两者皆缺——请修正脚本步参数")
        return loc

    def _element(self, verb: str, step: dict):
        return self._driver.find_element(*self._require_locator(verb, step))

    def _script(self, name: str, args: dict):
        return self._driver.execute_script(f"mobile: {name}", args)

    def _coordinate(self, step: dict) -> tuple | None:
        if step.get("x") is None or step.get("y") is None:
            return None
        return (float(step["x"]), float(step["y"]))


    def _do_launch(self, step: dict) -> dict:
        pass
        app_id = str(step.get("pkg") or step.get("appId") or "").strip()
        if not app_id:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": "launch 缺应用包名（步骤 pkg 参数为空）"
                              "——Appium 无包名无从拉起，请补参数"}
        self._script("activateApp", {"appId": app_id})
        return {"result": "pass"}

    def _do_close(self, step: dict) -> dict:
        app_id = (str(step.get("pkg") or step.get("appId") or "").strip()
                  or self._app_id or None)
        if not app_id:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": "close 缺应用包名（launch 未带 pkg 且环境档案无 appId 位）"
                              "——引擎无从终止目标，禁静默成功"}
        self._script("terminateApp", {"appId": app_id})
        return {"result": "pass"}

    def _do_tap(self, step: dict) -> dict:
        point = self._coordinate(step)
        if point:
            self._script("clickGesture", {"x": point[0], "y": point[1]})
            return {"result": "pass"}
        self._element("tap", step).click()
        return {"result": "pass"}

    def _do_longPress(self, step: dict) -> dict:
        seconds = (float(step["duration"]) / 1000.0
                   if step.get("duration") else 1.0)
        if self._platform == "IOS":
            chains = self._w3c().pause(seconds)
            self._chains_to_target(chains, step) \
                .pointer_down().pause(seconds).pointer_up().perform()
            return {"result": "pass"}
        args: dict = {"duration": seconds}
        point = self._coordinate(step)
        if point:
            args.update({"x": point[0], "y": point[1]})
        else:
            args["elementId"] = self._element("longPress", step).id
        self._script("touchAndHold", args)
        return {"result": "pass"}

    def _do_doubleTap(self, step: dict) -> dict:
        pass
        element = self._element("doubleTap", step)
        if self._platform == "IOS":
            element.click()
            element.click()
        else:
            self._script("doubleClick", {"elementId": element.id})
        return {"result": "pass"}

    def _do_input(self, step: dict) -> dict:
        text = step.get("text") if step.get("text") is not None else step.get("value")
        if text is None or str(text) == "":
            raise ValueError("input 缺文本参数（text/value 皆空）——请修正脚本步参数")
        self._element("input", step).send_keys(str(text))
        return {"result": "pass"}

    def _do_clearInput(self, step: dict) -> dict:
        self._element("clearInput", step).clear()
        return {"result": "pass"}

    def _do_swipe(self, step: dict) -> dict:
        direction = str(step.get("direction") or "up")
        if self._platform == "IOS":
            self._w3c_swipe(direction)
            return {"result": "pass"}
        self._script("swipeGesture", {"direction": direction})
        return {"result": "pass"}

    def _do_scroll(self, step: dict) -> dict:
        direction = str(step.get("direction") or "down")
        if self._platform == "IOS":
            self._script("swipe", {"direction": direction})
            return {"result": "pass"}
        self._script("scrollGesture", {"direction": direction})
        return {"result": "pass"}

    def _do_back(self, step: dict) -> dict:
        self._driver.back()
        return {"result": "pass"}

    def _do_screenshot(self, step: dict) -> dict:
        data = self._driver.get_screenshot_as_png() or b""
        if not data:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": "Appium 截图返回空字节（产物与结果矛盾）"
                              "——引擎会话或设备面异常，禁假绿"}
        tag = f"{self._run_seq}-{step.get('stepNo') or 0}"
        path = self._artifact_dir / f"step-{tag}-appium-shot.png"
        path.write_bytes(data)
        return {"result": "pass"}

    def _do_assertVisible(self, step: dict) -> dict:
        self._element("assertVisible", step)
        return {"result": "pass"}

    def _do_assertNotVisible(self, step: dict) -> dict:
        try:
            self._element("assertNotVisible", step)
        except Exception as e:
            if any(k in type(e).__name__ for k in ("NoSuchElement", "Timeout",
                                                   "StaleElement")):
                return {"result": "pass"}
            raise
        return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                "detail": "assertNotVisible 违例：目标元素当前可见——请核对脚本预期"}

    def _do_waitVisible(self, step: dict) -> dict:
        loc = self._require_locator("waitVisible", step)
        budget = _wait_timeout_sec()
        deadline = time.monotonic() + budget
        last_exc: Exception | None = None
        while time.monotonic() < deadline:
            try:
                self._driver.find_element(*loc)
                return {"result": "pass"}
            except Exception as e:
                if not any(k in type(e).__name__ for k in ("NoSuchElement",
                                                           "StaleElement")):
                    raise
                last_exc = e
                time.sleep(min(0.25, max(deadline - time.monotonic(), 0.01)))
        return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                "detail": f"waitVisible 等待超时（预算 {budget:g}s 内元素未出现）"
                          f"——{last_exc or '定位器无匹配'}"}

    def _do_assertText(self, step: dict) -> dict:
        expected = str(step.get("text") or "")
        if not expected:
            raise ValueError("assertText 缺 text 参数（期望文本）——请修正脚本步参数")
        actual = self._element_text(self._element("assertText", step))
        if actual == expected:
            return {"result": "pass"}
        return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                "detail": f"断言文本不符：期望「{expected}」，实际「{actual}」"}

    def _do_extractText(self, step: dict) -> dict:
        text = self._element_text(self._element("extractText", step))
        return {"result": "pass", "extracted": text}


    def _do_deepLink(self, step: dict) -> dict:
        url = require_step_param(step, "url", "deepLink")
        args = {"url": url}
        pkg = str(step.get("pkg") or step.get("appId") or "").strip()
        if pkg:
            args["pkg"] = pkg
        self._script("deepLink", args)
        return {"result": "pass"}

    @staticmethod
    def _element_text(element) -> str:
        try:
            text = str(getattr(element, "text", "") or "")
        except Exception:
            text = ""
        if text:
            return text
        try:
            return str(element.get_attribute("text") or "")
        except Exception:  # noqa: BLE001
            return ""


    def _w3c(self):
        return _load_selenium()["ActionChains"](self._driver)

    def _w3c_swipe(self, direction: str) -> None:
        dx, dy = {"up": (0, -300), "down": (0, 300), "left": (-300, 0),
                  "right": (300, 0)}.get(direction, (0, -300))
        self._w3c().move_to_location(540, 1200).pointer_down() \
            .pause(0.1).move_to_location(540 + dx, 1200 + dy).pause(0.1) \
            .pointer_up().perform()

    def _chains_to_target(self, chains, step: dict):
        point = self._coordinate(step)
        if point:
            return chains.move_to_location(point[0], point[1])
        return chains

    # ── collect / watch / teardown ─────────────────────────────────────────

    def collect(self, artifact: dict):
        pass
        if not self._prepared:
            return None
        step_no = artifact.get("stepNo") or 0
        root = pathlib.Path(str(artifact.get("artifactDir") or self._artifact_dir))
        try:
            root.mkdir(parents=True, exist_ok=True)
        except OSError:
            return None
        items: list[dict] = []
        try:
            data = self._driver.get_screenshot_as_png()
            if data:
                path = root / f"step-{step_no}-appium-screenshot.png"
                path.write_bytes(data)
                items.append({"kind": "screenshot", "localPath": str(path),
                              "contentType": "image/png"})
        except Exception:
            pass
        try:
            source = str(getattr(self._driver, "page_source", "") or "")
            if source.strip():
                path = root / f"step-{step_no}-appium-view-tree.json"
                path.write_text(source, encoding="utf-8")
                items.append({"kind": "dom_snapshot", "localPath": str(path),
                              "contentType": "application/json"})
        except Exception:  # noqa: BLE001, S110
            pass
        try:
            rows = self._driver.get_log(
                "device" if self._platform == "IOS" else "logcat") or []
            if rows:
                path = root / f"step-{step_no}-appium-engine.log"
                path.write_text("\n".join(str(r.get("message", r)) for r in rows),
                                encoding="utf-8")
                items.append({"kind": "log_tail", "localPath": str(path),
                              "contentType": "text/plain"})
        except Exception:
            pass
        return items or None

    def watch_frame(self, artifact_dir: str, max_width=None):
        pass
        if not self._prepared:
            return None
        candidates = sorted(self._artifact_dir.glob("step-*-appium-shot.png"))
        latest = [c for c in candidates if c.exists() and c.stat().st_size > 0]
        if not latest:
            return None
        return monitor_frame_from_artifacts(
            [{"kind": "screenshot", "localPath": str(latest[-1])}], max_width=max_width)

    def teardown(self) -> None:
        pass
        self._prepared = False
        driver = self._driver
        self._driver = None
        if driver is None:
            return
        try:
            driver.quit()
        except Exception:
            pass
