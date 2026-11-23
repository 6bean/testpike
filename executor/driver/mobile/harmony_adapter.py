
from __future__ import annotations

import json
import os
import pathlib
import tempfile

from actions_common import APP_NAMESPACE, IR_VERBS, app_action_violation, app_verb
from driver.mobile.contracts import (
    monitor_frame_from_artifacts,
    require_step_param,
    verb_capability_violation,
    webview_step_violation,
)

_ERROR_TYPE_SCRIPT = 1
_ERROR_TYPE_ENV = 2

_LOCATOR_KEY_MAP = {
    "testId": "id",
    "id": "id",
    "accessibilityId": "key",
    "placeholder": "description",
    "text": "text",
    "className": "type",
}


def _load_driver_class():
    pass
    from hmdriver2.driver import Driver
    return Driver


def _load_rotation_enum():
    pass
    from hmdriver2.proto import DisplayRotation
    return DisplayRotation


_ROTATION_ATTR_BY_ORIENTATION: dict = {
    "portrait": "ROTATION_0",
    "landscape": "ROTATION_90",
    "portraitUpsideDown": "ROTATION_180",
    "landscapeLeft": "ROTATION_270",
    "landscapeRight": "ROTATION_90",
    "reverse_portrait": "ROTATION_180",
    "reverse_landscape": "ROTATION_270",
}


class HarmonyAdapter:
    pass

    SUPPORTED_PLATFORMS: tuple = ("HARMONY_NEXT",)
    SUPPORTED_DEVICE_KINDS: tuple = ("EMULATOR", "REAL")
    CAPABILITIES: tuple = IR_VERBS

    def __init__(self, descriptor: dict | None = None):
        self.descriptor = descriptor or {}
        self._driver = None
        self._app_id = ""

    # ── prepare ────────────────────────────────────────────────────────────

    def prepare(self, ctx: dict) -> None:
        profile = self._gate_env_profile(ctx)
        lease = self._gate_lease(ctx)
        driver_cls = self._gate_import()
        self._app_id = str(profile.get("appId") or profile.get("bundleName") or "").strip()
        serial = str(lease.get("serial") or profile.get("deviceSerial") or "").strip() \
            or None
        self._driver = driver_cls(serial=serial) if serial else driver_cls()

    def is_prepared(self) -> bool:
        return self._driver is not None

    def _gate_env_profile(self, ctx: dict) -> dict:
        profile = ctx.get("envProfile")
        if not isinstance(profile, dict) or not profile:
            raise ValueError(
                "鸿蒙会话准备失败：任务信封缺环境档案（envProfile）——请在用例所属项目"
                "的「环境档案库」建一份鸿蒙 HDC 档案（HARMONY_HDC 型，hdcPath/设备序列号"
                "可选位）并在建单时选用（档案=机器环境项目级可复用，改一处全生效）")
        return profile

    def _gate_lease(self, ctx: dict) -> dict:
        lease = ctx.get("leaseRef")
        if not isinstance(lease, dict) or not (lease.get("deviceId") or lease.get("serial")):
            raise ValueError("鸿蒙会话准备失败：设备租约未确认（信封 leaseRef 缺设备标识）"
                             "——会话必须先由派发定版占机，请确认任务经 APP 派发链下发")
        return lease

    def _gate_import(self):
        try:
            return _load_driver_class()
        except ImportError as e:
            raise RuntimeError(
                "本执行器未安装 hmdriver2（pip install hmdriver2，HMNextAuto 运行时，"
                "HDC 工具链需在 PATH 或经环境档案 hdcPath 申报），无法拉起鸿蒙会话——"
                f"请经装机件补装后重试（缺件归因: {e}）") from e

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
        if self._driver is None:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": "鸿蒙会话未准备（prepare 未执行或半途失败），禁止裸步下发"}
        caps_violation = verb_capability_violation(self.descriptor, verb)
        if caps_violation is not None:
            return caps_violation
        handler = getattr(self, f"_do_{verb}", None)
        if handler is None:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": f"动词 {verb} 未在 HMNextAuto 映射表落位（引擎侧缺口，禁静默成功）"}
        try:
            return handler(step)
        except ValueError as e:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT, "detail": str(e)}
        except Exception as e:
            text = str(e)
            locatorish = any(k in text for k in ("找不到", "元素", "element",
                                                 "timeout", "超时", "not found",
                                                 "ElementNotFound"))
            return {"result": "fail",
                    "errorType": _ERROR_TYPE_SCRIPT if locatorish else _ERROR_TYPE_ENV,
                    "detail": f"引擎执行异常: {text}"}


    def _do_launch(self, step: dict) -> dict:
        pkg = str(step.get("pkg") or step.get("appId") or "").strip()
        if not pkg:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": "launch 缺应用包名（步骤 pkg 参数为空）"
                              "——鸿蒙无包名无从拉起，请补参数"}
        self._driver.start_app(pkg, str(step.get("page") or "") or None)
        return {"result": "pass"}

    def _do_close(self, step: dict) -> dict:
        pkg = str(step.get("pkg") or step.get("appId") or "").strip() or self._app_id
        if not pkg:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": "close 缺应用包名（步骤 pkg 参数为空且环境档案无 appId 位）"
                              "——请补参数或在鸿蒙 HDC 档案填 appId"}
        self._driver.stop_app(pkg)
        return {"result": "pass"}

    def _do_tap(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        if el is None:
            self._driver.click(step["x"], step["y"])
        else:
            el.click()
        return {"result": "pass"}

    def _do_longPress(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        if el is None:
            self._driver.long_click(step["x"], step["y"])
        else:
            el.long_click()
        return {"result": "pass"}

    def _do_doubleTap(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        if el is None:
            self._driver.double_click(step["x"], step["y"])
        else:
            el.double_click()
        return {"result": "pass"}

    def _do_input(self, step: dict) -> dict:
        el, err = self._locate(step, "input")
        if err:
            return err
        text = str(step.get("text") or "")
        if el is None:
            self._driver.input_text(text)
        else:
            el.input_text(text)
        return {"result": "pass"}

    def _do_clearInput(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        el.clear_text()
        return {"result": "pass"}

    def _do_swipe(self, step: dict) -> dict:
        coords = [step.get(k) for k in ("x1", "y1", "x2", "y2")]
        if any(v is None for v in coords):
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": "swipe 缺滑动起止坐标（x1/y1/x2/y2 四值必备）——请补脚本坐标或改用 scroll"}
        speed = step.get("speed")
        if speed is not None:
            self._driver.swipe(*[int(v) for v in coords], speed=int(speed))
        else:
            self._driver.swipe(*[int(v) for v in coords])
        return {"result": "pass"}

    def _do_scroll(self, step: dict) -> dict:
        direction = str(step.get("direction") or "down")
        if direction not in ("up", "down", "left", "right"):
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"scroll 方向 {direction!r} 不在 hmdriver2 swipe_ext 面"
                              "（up/down/left/right）——请修正脚本"}
        self._driver.swipe_ext(direction)
        return {"result": "pass"}

    def _do_back(self, step: dict) -> dict:
        self._driver.go_back()
        return {"result": "pass"}

    def _do_screenshot(self, step: dict) -> dict:
        self._last_shot = self._driver.screenshot(self._next_shot_path(step))
        return {"result": "pass"}

    def _do_assertVisible(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        if not el.exists():
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"断言失败：页面未出现元素 {self._locator_desc(step)!r}"}
        return {"result": "pass"}

    def _do_assertNotVisible(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        if el.exists():
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"断言失败：预期不出现的元素 {self._locator_desc(step)!r} 仍在页面"}
        return {"result": "pass"}

    def _do_assertText(self, step: dict) -> dict:
        el, err = self._locate(step, "assertText")
        if err:
            return err
        actual = str(el.text or "")
        expected = str(step.get("expectText") or step.get("expect")
                       or step.get("text") or "")
        if actual != expected:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"文案断言不一致：预期「{expected}」，实际「{actual}」"}
        return {"result": "pass"}

    def _do_waitVisible(self, step: dict) -> dict:
        timeout = step.get("timeout")
        retries = max(1, int((timeout or 2000) / 1000)) if timeout else 2
        el, err = self._locate(step)
        if err:
            return err
        if not el.exists(retries=retries, wait_time=1):
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"等待超时：元素 {self._locator_desc(step)!r} 未出现"}
        return {"result": "pass"}

    def _do_extractText(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        return {"result": "pass", "extracted": str(el.text or "")}


    def _do_home(self, step: dict) -> dict:
        self._driver.go_home()
        return {"result": "pass"}

    def _do_wakeUnlock(self, step: dict) -> dict:
        self._driver.unlock()
        return {"result": "pass"}

    def _do_deepLink(self, step: dict) -> dict:
        url = require_step_param(step, "url", "deepLink")
        self._driver.open_url(url, system_browser=False)
        return {"result": "pass"}

    def _do_openUrl(self, step: dict) -> dict:
        url = require_step_param(step, "url", "openUrl")
        self._driver.open_url(url, system_browser=True)
        return {"result": "pass"}

    def _do_clearData(self, step: dict) -> dict:
        pkg = require_step_param(step, "pkg", "clearData")
        self._driver.clear_app(pkg)
        return {"result": "pass"}

    def _do_grantPermission(self, step: dict) -> dict:
        pkg = require_step_param(step, "pkg", "grantPermission")
        perms = step.get("permissions")
        if not isinstance(perms, list) or not perms:
            raise ValueError("grantPermission 缺 permissions 清单（非空数组）"
                             "——请修正脚本步参数")
        for perm in perms:
            cmd = f"bm grant -n {pkg} -p {perm}"
            res = self._driver.shell(cmd)
            if getattr(res, "exit_code", 0) != 0:
                return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                        "detail": f"hdc shell {cmd} exit_code="
                                  f"{getattr(res, 'exit_code', '?')}（授权未生效）："
                                  + str(getattr(res, "error", "") or
                                        getattr(res, "output", "") or "")[-200:]}
        return {"result": "pass"}

    def _do_rotation(self, step: dict) -> dict:
        orientation = str(step.get("orientation") or "").strip()
        attr = _ROTATION_ATTR_BY_ORIENTATION.get(orientation)
        if attr is None:
            raise ValueError(
                f"rotation 方向值「{orientation or '（空）'}」不在支持面——"
                "请改用 portrait/landscape（或 portraitUpsideDown/landscapeLeft）")
        enum_cls = _load_rotation_enum()
        self._driver.set_display_rotation(getattr(enum_cls, attr))
        return {"result": "pass"}


    def _by_keys(self, step: dict, verb: str = "") -> dict:
        pass
        by: dict = {}
        for ir_key, hm_key in _LOCATOR_KEY_MAP.items():
            val = step.get(ir_key)
            if val is not None and str(val).strip():
                by.setdefault(hm_key, str(val).strip())
        if verb in ("assertText", "input") and "text" in by:
            others = [k for k in by if k != "text"]
            if others:
                by.pop("text")
        return by

    def _locator_desc(self, step: dict, verb: str = "") -> str:
        by = self._by_keys(step, verb)
        return ", ".join(f"{k}={v}" for k, v in by.items()) or str(step.get("selector") or "")

    def _locate(self, step: dict, verb: str = ""):
        pass
        by = self._by_keys(step, verb)
        if by:
            return self._driver(**by), None
        if step.get("x") is not None and step.get("y") is not None:
            return None, None
        return None, {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                      "detail": "缺少定位器（selector）——鸿蒙步请按 testId/id/"
                                "accessibilityId/placeholder/text/className 任一填写，"
                                "或 x/y 坐标尽力位（tap 系），无法凭空定位"}

    def _next_shot_path(self, step: dict) -> str:
        root = str(step.get("artifactDir") or
                   os.path.join(tempfile.gettempdir(), "testpike-appauto"))
        path = pathlib.Path(root) / f"step-{step.get('stepNo') or 0}-harmony.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        return str(path)

    # ── collect / teardown ─────────────────────────────────────────────────

    def collect(self, artifact: dict):
        pass
        if self._driver is None:
            return None
        root = artifact.get("artifactDir")
        if not root:
            return None
        artifacts = []
        try:
            path = self._driver.screenshot(str(
                pathlib.Path(root) / f"step-{artifact.get('stepNo') or 0}-harmony.png"))
            if path:
                artifacts.append({"kind": "screenshot", "localPath": path})
        except Exception:
            pass
        try:
            hierarchy = self._driver.dump_hierarchy()
            if not hierarchy:
                raise ValueError("空壳视图树禁入账")
            tree_file = pathlib.Path(root) / f"step-{artifact.get('stepNo') or 0}-viewtree.json"
            tree_file.parent.mkdir(parents=True, exist_ok=True)
            tree_file.write_text(json.dumps(hierarchy, ensure_ascii=False), encoding="utf-8")
            artifacts.append({"kind": "dom_snapshot", "localPath": str(tree_file),
                              "contentType": "application/json"})
        except Exception:
            pass
        return artifacts or None

    def watch_frame(self, artifact_dir: str, max_width=None):
        pass
        items = self.collect({"stepNo": 0, "artifactDir": artifact_dir})
        return monitor_frame_from_artifacts(items, max_width=max_width)

    def teardown(self) -> None:
        driver = self._driver
        if driver is None:
            return
        self._driver = None
        close = getattr(driver, "close", None)
        if close is not None:
            try:
                close()
            except Exception:
                pass
