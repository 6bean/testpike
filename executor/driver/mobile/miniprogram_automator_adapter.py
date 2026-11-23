
from __future__ import annotations

import pathlib

from actions_common import APP_NAMESPACE, IR_VERBS, app_action_violation, app_verb
from driver.mobile.bridge_process import NodeBridge
from driver.mobile.contracts import (
    MP_DEFAULT_AUTO_PORT,
    bridge_watch_frame,
    mp_double_tap_interval_ms,
    parse_collect_frame,
    verb_capability_violation,
    webview_step_violation,
)

_BRIDGE_SCRIPT = str(pathlib.Path(__file__).resolve().parent / "automator_bridge.mjs")

_ERROR_TYPE_SCRIPT = 1
_ERROR_TYPE_ENV = 2

_PROFILE_REQUIRED_KEYS: tuple = ("projectPath",)


class AutomatorAdapter:
    pass

    SUPPORTED_PLATFORMS: tuple = ("MP",)
    SUPPORTED_DEVICE_KINDS: tuple = ("DEVTOOLS_IDE",)
    CAPABILITIES: tuple = IR_VERBS

    def __init__(self, descriptor: dict | None = None,
                 bridge: NodeBridge | None = None,
                 timeout_sec: float = 60.0):
        self.descriptor = descriptor or {}
        self.timeout_sec = timeout_sec
        self._bridge = bridge
        self._prepared = False

    # ── prepare ────────────────────────────────────────────────────────────

    def prepare(self, ctx: dict) -> None:
        profile = self._gate_env_profile(ctx)
        self._gate_lease(ctx)
        if self._bridge is None:
            self._bridge = NodeBridge(script_path=_BRIDGE_SCRIPT,
                                      timeout_sec=self.timeout_sec)
        if not self._bridge.alive():
            self._bridge.start()
        self._bridge.call("prepare", {
            "cliPath": str(profile.get("cliPath") or "").strip() or None,
            "projectPath": profile.get("projectPath"),
            "autoPort": int(profile.get("autoPort") or MP_DEFAULT_AUTO_PORT),
            "leaseRef": ctx.get("leaseRef"),
            "runId": ctx.get("runId"),
        })
        self._prepared = True

    def _gate_env_profile(self, ctx: dict) -> dict:
        profile = ctx.get("envProfile")
        if not isinstance(profile, dict) or not profile:
            raise ValueError(
                "小程序会话准备失败：任务信封缺环境档案（envProfile）——请在用例所属项目"
                "的「环境档案库」建一份微信开发者工具档案（项目路径必备）并在建单时选用"
                "（档案=机器环境项目级可复用，改一处全生效）")
        missing = [k for k in _PROFILE_REQUIRED_KEYS
                   if not str(profile.get(k) or "").strip()]
        if missing:
            raise ValueError(
                f"环境档案缺少必备键 {missing}（automator 会话=微信开发者工具项目路径起步，"
                f"cliPath/autoPort 可选）——请到「环境档案库」补齐后重试")
        return profile

    def _gate_lease(self, ctx: dict) -> None:
        lease = ctx.get("leaseRef")
        if not isinstance(lease, dict) or not (lease.get("deviceId") or lease.get("serial")):
            raise ValueError("小程序会话准备失败：设备租约未确认（信封 leaseRef 缺设备标识）"
                             "——会话必须先由派发定版占机，请确认任务经 APP 派发链下发")

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
        params = dict(step)
        params["verb"] = verb
        if verb == "doubleTap":
            params["intervalMs"] = mp_double_tap_interval_ms()
        try:
            res = self._bridge.call("execute", params)
        except Exception as e:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": f"引擎桥执行异常: {e}"}
        return res

    # ── collect / teardown ─────────────────────────────────────────────────

    def collect(self, artifact: dict):
        if not self._prepared or self._bridge is None:
            return None
        try:
            res = self._bridge.call("collect", {
                "stepNo": artifact.get("stepNo"),
                "artifactDir": artifact.get("artifactDir")})
        except Exception:
            return None
        return parse_collect_frame(res)

    def watch_frame(self, artifact_dir: str, max_width=None):
        pass
        return bridge_watch_frame(self._prepared, self._bridge, artifact_dir,
                                  max_width=max_width)

    def teardown(self) -> None:
        bridge = self._bridge
        if bridge is None:
            return
        try:
            bridge.call("teardown", {})
        except Exception:
            pass
        finally:
            self._prepared = False
            bridge.stop()
            self._bridge = None
