
from __future__ import annotations

import json
import os
import pathlib
import tempfile
import time

from actions_common import APP_NAMESPACE, IR_VERBS, app_action_violation, app_verb
from driver.mobile.contracts import (
    MP_DEFAULT_AUTO_PORT,
    monitor_frame_from_artifacts,
    mp_double_tap_interval_ms,
    require_step_param,
    verb_capability_violation,
    webview_step_violation,
)

_ERROR_TYPE_SCRIPT = 1
_ERROR_TYPE_ENV = 2

_PROFILE_REQUIRED_KEYS: tuple = ("appid", "projectPath")


def _load_minium():
    pass
    import minium
    return minium


def _double_tap_interval_sec() -> float:
    pass
    return mp_double_tap_interval_ms() / 1000.0


class MiniumAdapter:
    pass

    SUPPORTED_PLATFORMS: tuple = ("MP",)
    SUPPORTED_DEVICE_KINDS: tuple = ("DEVTOOLS_IDE", "EMULATOR")
    CAPABILITIES: tuple = IR_VERBS

    def __init__(self, descriptor: dict | None = None):
        self.descriptor = descriptor or {}
        self._session = None

    # ── prepare ────────────────────────────────────────────────────────────

    def prepare(self, ctx: dict) -> None:
        profile = self._gate_env_profile(ctx)
        self._gate_lease(ctx)
        minium = self._gate_import()
        conf = {
            "project_path": profile.get("projectPath"),
            "dev_port": int(profile.get("autoPort") or MP_DEFAULT_AUTO_PORT),
            "platform": "wx",
        }
        self._session = minium.Minium(conf)

    def is_prepared(self) -> bool:
        return self._session is not None

    def _gate_env_profile(self, ctx: dict) -> dict:
        profile = ctx.get("envProfile")
        if not isinstance(profile, dict) or not profile:
            raise ValueError(
                "小程序会话准备失败：任务信封缺环境档案（envProfile）——请在用例所属项目"
                "的「环境档案库」建一份微信开发者工具档案（appid/项目路径必备）并在建单时选用"
                "（档案=机器环境项目级可复用，改一处全生效）")
        missing = [k for k in _PROFILE_REQUIRED_KEYS if not str(profile.get(k) or "").strip()]
        if missing:
            raise ValueError(
                f"环境档案缺少必备键 {missing}（小程序档案=appid+projectPath 起步，"
                f"cliPath/autoPort 可选）——请到「环境档案库」补齐后重试")
        return profile

    def _gate_lease(self, ctx: dict) -> None:
        lease = ctx.get("leaseRef")
        if not isinstance(lease, dict) or not (lease.get("deviceId") or lease.get("serial")):
            raise ValueError("小程序会话准备失败：设备租约未确认（信封 leaseRef 缺设备标识）"
                             "——会话必须先由派发定版占机，请确认任务经 APP 派发链下发")

    def _gate_import(self):
        try:
            return _load_minium()
        except ImportError as e:
            raise RuntimeError(
                "本执行器未安装 minium（pip install minium==1.6.1，iOS 真机 extra 可选），"
                f"无法拉起小程序会话——请经装机件补装后重试（缺件归因: {e}）") from e

    # ── execute ────────────────────────────────────────────────────────────

    def execute(self, step: dict) -> dict:
        action = step.get("action")
        verb = step.get("verb") or app_verb(action) or str(action or "")
        if verb not in IR_VERBS:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": app_action_violation(APP_NAMESPACE + verb)
                              + "——请修正脚本或走能力位"}
        violation = webview_step_violation(self.descriptor, step,
                                           engine_implemented=False)
        if violation is not None:
            return violation
        if self._session is None:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": "小程序会话未准备（prepare 未执行或半途失败），禁止裸步下发"}
        caps_violation = verb_capability_violation(self.descriptor, verb)
        if caps_violation is not None:
            return caps_violation
        handler = getattr(self, f"_do_{verb}", None)
        if handler is None:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": f"动词 {verb} 未在 Minium 映射表落位（引擎侧缺口，禁静默成功）"}
        try:
            return handler(step)
        except ValueError as e:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT, "detail": str(e)}
        except Exception as e:
            text = str(e)
            locatorish = any(k in text for k in ("找不到", "元素", "element", "timeout", "超时"))
            return {"result": "fail",
                    "errorType": _ERROR_TYPE_SCRIPT if locatorish else _ERROR_TYPE_ENV,
                    "detail": f"引擎执行异常: {text}"}


    def _do_launch(self, step: dict) -> dict:
        self._session.reLaunch(str(step.get("page") or "/"))
        return {"result": "pass"}

    def _do_close(self, step: dict) -> dict:
        self._session.app.release()
        return {"result": "pass"}

    def _do_tap(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        el.tap()
        return {"result": "pass"}

    def _do_longPress(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        el.long_press(duration=step.get("duration"))
        return {"result": "pass"}

    def _do_doubleTap(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        el.tap()
        time.sleep(_double_tap_interval_sec())
        el.tap()
        return {"result": "pass"}

    def _do_input(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        el.input(str(step.get("text") or ""))
        return {"result": "pass"}

    def _do_clearInput(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        el.data("")
        return {"result": "pass"}

    def _do_swipe(self, step: dict) -> dict:
        coords = [step.get(k) for k in ("x1", "y1", "x2", "y2")]
        if any(v is None for v in coords):
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": "swipe 缺滑动起止坐标（x1/y1/x2/y2 四值必备）——请补脚本坐标或改用 scroll"}
        self._session.slide(*[int(v) for v in coords])
        return {"result": "pass"}

    def _do_scroll(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        el.scroll_to()
        return {"result": "pass"}

    def _do_back(self, step: dict) -> dict:
        self._session.navigateBack()
        return {"result": "pass"}

    def _do_screenshot(self, step: dict) -> dict:
        self._last_shot = self._session.screenshot(self._next_shot_path(step))
        return {"result": "pass"}

    def _do_assertVisible(self, step: dict) -> dict:
        el = self._session.get_element(step.get("selector"), raise_exception=False)
        if el is None:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"断言失败：页面未出现元素 {step.get('selector')!r}"}
        return {"result": "pass"}

    def _do_assertNotVisible(self, step: dict) -> dict:
        el = self._session.get_element(step.get("selector"), raise_exception=False)
        if el is not None:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"断言失败：预期不出现的元素 {step.get('selector')!r} 仍在页面"}
        return {"result": "pass"}

    def _do_assertText(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        actual = str(el.inner_text() or "")
        expected = str(step.get("text") or "")
        if actual != expected:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"文案断言不一致：预期「{expected}」，实际「{actual}」"}
        return {"result": "pass"}

    def _do_waitVisible(self, step: dict) -> dict:
        timeout = step.get("timeout")
        el = self._session.wait_for(step.get("selector"),
                                    **({"timeout": int(timeout)} if timeout else {}))
        if el is None:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"等待超时：元素 {step.get('selector')!r} 未出现"}
        return {"result": "pass"}

    def _do_extractText(self, step: dict) -> dict:
        el, err = self._locate(step)
        if err:
            return err
        return {"result": "pass", "extracted": str(el.inner_text() or "")}


    def _do_deepLink(self, step: dict) -> dict:
        url = require_step_param(step, "url", "deepLink")
        self._session.navigate_to(url)
        return {"result": "pass"}


    def _locate(self, step: dict):
        selector = str(step.get("selector") or "").strip()
        if not selector:
            return None, {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                          "detail": "缺少定位器（selector）——小程序步请按「页面路径:选择器」"
                                    "或元素选择器填写，无法凭坐标猜元素"}
        return self._session.get_element(selector), None

    def _next_shot_path(self, step: dict) -> str:
        root = str(step.get("artifactDir") or
                   os.path.join(tempfile.gettempdir(), "testpike-appauto"))
        path = pathlib.Path(root) / f"step-{step.get('stepNo') or 0}-minium.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        return str(path)

    # ── collect / teardown ─────────────────────────────────────────────────

    def collect(self, artifact: dict):
        pass
        if self._session is None:
            return None
        root = artifact.get("artifactDir")
        if not root:
            return None
        artifacts = []
        try:
            path = self._session.screenshot(str(
                pathlib.Path(root) / f"step-{artifact.get('stepNo') or 0}-minium.png"))
            if path:
                artifacts.append({"kind": "screenshot", "localPath": path})
        except Exception:
            pass
        try:
            page = self._session.page
            body = {"wxml": str(page.wxml or ""), "data": page.data}
            if not (body["wxml"] or body["data"]):
                raise ValueError("空壳视图树禁入账")
            tree_file = pathlib.Path(root) / f"step-{artifact.get('stepNo') or 0}-viewtree.json"
            tree_file.parent.mkdir(parents=True, exist_ok=True)
            tree_file.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
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
        session = self._session
        if session is None:
            return
        self._session = None
        app = getattr(session, "app", None)
        hooks = [getattr(app, "release", None),
                 getattr(session, "shutdown", None),
                 getattr(app, "shutdown", None)]
        for hook in hooks:
            if hook is None:
                continue
            try:
                hook()
            except Exception:
                pass
