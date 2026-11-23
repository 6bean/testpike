
from __future__ import annotations

import pathlib
import time

from actions_common import APP_NAMESPACE, IR_VERBS, app_action_violation, app_verb
from driver.mobile.adb_verb_runner import ADB_FAMILY_VERBS, AdbVerbRunner
from driver.mobile.bridge_process import NodeBridge
from driver.mobile.contracts import (
    bridge_watch_frame,
    parse_collect_frame,
    verb_capability_violation,
    webview_step_violation,
)

_BRIDGE_SCRIPT = str(pathlib.Path(__file__).resolve().parent / "mobilewright_bridge.mjs")

_ERROR_TYPE_SCRIPT = 1
_ERROR_TYPE_ENV = 2


class MobilewrightAdapter:
    pass

    SUPPORTED_PLATFORMS: tuple = ("ANDROID",)
    SUPPORTED_DEVICE_KINDS: tuple = ("EMULATOR", "REAL")
    CAPABILITIES: tuple = IR_VERBS

    def __init__(self, descriptor: dict | None = None,
                 bridge: NodeBridge | None = None,
                 timeout_sec: float = 60.0):
        self.descriptor = descriptor or {}
        self.timeout_sec = timeout_sec
        self._bridge = bridge
        self._prepared = False
        self._last_extracted: str | None = None
        self._adb = AdbVerbRunner()
        self._adb_serial: str | None = None
 # Native recording ledger: every step this adapter actually executed is
 # self-reported here. The scrcpy fallback level samples adb getevent kernel
 # events, which never see framework-level injections, so the engine itself
 # must keep the record for recording sessions (recording.py level 1).
        self._native_events: list[dict] = []

    def prepare(self, ctx: dict) -> None:
 # One recording session == one task run: start the ledger clean.
        self._native_events = []
        lease = ctx.get("leaseRef") or {}
        serial = lease.get("serial") or ctx.get("serial")
        self._adb_serial = serial
        if self._bridge is None:
            self._bridge = NodeBridge(
                script_path=_BRIDGE_SCRIPT,
                timeout_sec=self.timeout_sec,
                extra_env={"MOBILEWRIGHT_DISABLE_TELEMETRY": "1"})
        extra = getattr(self._bridge, "extra_env", None)
        if isinstance(extra, dict):
            extra.setdefault("MOBILEWRIGHT_DISABLE_TELEMETRY", "1")
        if not self._bridge.alive():
            self._bridge.start()
        self._bridge.call("prepare", {"serial": serial, "runId": ctx.get("runId")})
        self._prepared = True

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
        if verb in ADB_FAMILY_VERBS:
            res = self._adb.run(verb, step, serial=self._adb_serial)
            if res.get("result") == "pass":
                self._note_executed_step(verb, step, "adb-direct")
            return res
        params = dict(step)
        params["verb"] = verb
        try:
            res = self._bridge.call("execute", params)
        except Exception as e:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": f"引擎桥执行异常: {e}"}
        if res.get("result") == "pass":
            self._note_executed_step(verb, step, "engine-bridge")
        if res.get("result") == "pass" and res.get("extracted") is not None:
            self._last_extracted = res["extracted"]
        return res

    def record_events(self) -> list[dict]:
        """Native recording hook (recording.py level 1 consumer surface).

        Returns the self-reported action frames of the current session as
        normalized-whitelist rows; the assembly layer owns normalization,
        this side only reports what actually executed.
        """
        return list(self._native_events)

    def _note_executed_step(self, verb: str, step: dict | None,
                            surface: str) -> None:
        step = step or {}
        target = (step.get("testId") or step.get("accessibilityId")
                  or step.get("selector"))
        if target is None and step.get("x") is not None and step.get("y") is not None:
            target = f"{step.get('x')},{step.get('y')}"
        row: dict = {"kind": str(verb), "ts": time.time(), "target": target,
                     "detail": f"引擎自报动作帧（执行面：{surface}）"}
        text = step.get("text")
        if isinstance(text, str) and text:
            row["text"] = text
        self._native_events.append(row)

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
        self._adb.clear_network_profile(serial=self._adb_serial)
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
