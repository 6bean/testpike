
from __future__ import annotations

import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from actions_common import APP_NAMESPACE, IR_VERBS, app_action_violation, app_verb
from driver.mobile.adb_verb_runner import ADB_FAMILY_VERBS, AdbVerbRunner
from driver.mobile.contracts import (
    monitor_frame_from_artifacts,
    verb_capability_violation,
    webview_step_violation,
)

MAESTRO_BINARY = "maestro"

_ERROR_TYPE_SCRIPT = 1
_ERROR_TYPE_ENV = 2

_STEP_BUDGET_SEC_ENV_KEY = "APPAUTO_MAESTRO_STEP_TIMEOUT_SEC"
_STEP_BUDGET_SEC_DEFAULT = 120.0


class MaestroFlowError(ValueError):
    pass


def _locator_json(step: dict) -> str | None:
    pass
    for key in ("testId", "accessibilityId"):
        if step.get(key):
            return json.dumps({"id": str(step[key])}, ensure_ascii=False)
    if step.get("text"):
        return json.dumps({"text": str(step["text"])}, ensure_ascii=False)
    if step.get("placeholder"):
        return json.dumps({"id": str(step["placeholder"])}, ensure_ascii=False)
    return None


def _require_loc(verb: str, loc: str | None) -> str:
    if loc is None:
        raise MaestroFlowError(
            f"动词 {verb} 需要定位器（testId/text/placeholder）或坐标（x/y），"
            "信封两者皆缺——请修正脚本步参数")
    return loc


def _command_for(verb: str, step: dict,
                 screenshot_path: str | None) -> str | None:
    pass
    loc = _locator_json(step)
    point = (json.dumps({"point": f"{step['x']},{step['y']}"})
             if step.get("x") is not None and step.get("y") is not None else None)
    if verb == "launch":
        return "launchApp"
    if verb == "close":
        return "stopApp"
    if verb == "back":
        return "back"
    if verb == "screenshot":
        return f"takeScreenshot: {screenshot_path or ''}".rstrip()
    if verb == "tap":
        return f"tapOn: {point or _require_loc('tap', loc)}"
    if verb == "longPress":
        merged = json.loads(point or _require_loc("longPress", loc))
        if step.get("duration"):
            merged["duration"] = int(step["duration"])
        return f"tapOn: {json.dumps(merged, ensure_ascii=False)}"
    if verb == "input":
        text = str(step.get("text") if step.get("text") is not None
                   else step.get("value") or "")
        return f"inputText: {json.dumps(text, ensure_ascii=False)}"
    if verb == "clearInput":
        return "eraseText: true"
    if verb == "swipe":
        return f"swipe: {json.dumps({'direction': str(step.get('direction') or 'up')})}"
    if verb == "scroll":
        return "scroll"
    if verb in ("assertVisible", "waitVisible"):
        return f"assertVisible: {_require_loc(verb, loc)}"
    if verb == "assertNotVisible":
        return f"assertNotVisible: {_require_loc('assertNotVisible', loc)}"
    if verb == "assertText":
        if not step.get("text"):
            raise MaestroFlowError("断言文本缺 text 参数——请修正脚本步参数")
        return f"assertVisible: {json.dumps({'text': str(step['text'])}, ensure_ascii=False)}"
    return None


def build_flow_yaml(verb: str, step: dict, ctx: dict,
                    screenshot_path: str | None = None) -> str:
    pass
    if verb == "doubleTap":
        target = (json.dumps({"point": f"{step['x']},{step['y']}"})
                  if step.get("x") is not None and step.get("y") is not None
                  else _require_loc("doubleTap", _locator_json(step)))
        body = f"- tapOn: {target}\n- tapOn: {target}"
    else:
        cmd = _command_for(verb, step, screenshot_path)
        if cmd is None:
            raise MaestroFlowError(
                f"动词 {verb} 未在 Maestro 命令映射表落位（该引擎 CLI 无对应输出面，"
                "引擎侧缺口，禁静默成功）——请换引擎执行该步或走能力位")
        body = "- " + cmd
    app_id = str(step.get("pkg") or step.get("appId")
                 or ctx.get("appId") or "com.sixbean.testpike.unknown")
    return f"appId: {app_id}\n---\n{body}\n"


def parse_maestro_junit(xml_text: str | None, exit_code: int,
                        stderr_tail: str = "") -> dict:
    pass
    if not xml_text or not xml_text.lstrip().startswith("<"):
        return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                "detail": ("Maestro 未产出 JUnit 结果文件（引擎进程异常）"
                           if not xml_text else "Maestro 结果文件非 XML（产物脏）")
                + (f"；引擎输出尾：{stderr_tail[:400]}" if stderr_tail else "")}
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                "detail": f"Maestro JUnit 解析失败（产物脏）；引擎输出尾：{stderr_tail[:400]}"}
    suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
    failure = next((n for s in suites for n in s.iter("failure")), None)
    error = next((n for s in suites for n in s.iter("error")), None)
    bad = next((n for n in (failure, error) if n is not None), None)
    if bad is None:
        skipped = any(int(s.get("skipped") or 0) > 0 for s in suites)
        if skipped or exit_code != 0:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": "Maestro 步骤未真实执行（跳过或进程非零退出且无失败明细）"
                              + (f"；引擎输出尾：{stderr_tail[:400]}" if stderr_tail else "")}
        return {"result": "pass"}
    message = str(bad.get("message") or "").strip()
    if not message:
        out = bad.find("system-out")
        message = (out.text or "").strip() if out is not None else ""
    is_error = bad is error
    return {"result": "fail",
            "errorType": _ERROR_TYPE_ENV if is_error else _ERROR_TYPE_SCRIPT,
            "detail": (message or ("Maestro 引擎异常" if is_error else "Maestro 断言失败"))
                      + (f"；引擎输出尾：{stderr_tail[:400]}" if stderr_tail else "")}


class MaestroAdapter:
    pass

    SUPPORTED_PLATFORMS: tuple = ("ANDROID",)
    SUPPORTED_DEVICE_KINDS: tuple = ("EMULATOR", "REAL")
    CAPABILITIES: tuple = IR_VERBS

    def __init__(self, descriptor: dict | None = None,
                 timeout_sec: float | None = None,
                 artifact_dir: str | None = None):
        self.descriptor = descriptor or {}
        self.timeout_sec = timeout_sec or _step_budget_sec()
        self._prepared = False
        self._ctx: dict = {}
        self._adb = AdbVerbRunner()
        self._adb_serial: str | None = None
        self.artifact_root = str(artifact_dir or
                                 pathlib.Path(tempfile.gettempdir()) /
                                 "testpike-appauto")
        self._artifact_dir = pathlib.Path(self.artifact_root)
        self._run_seq = 0

    def prepare(self, ctx: dict) -> None:
        if shutil.which(MAESTRO_BINARY) is None:
            raise RuntimeError(
                f"本执行器未找到 {MAESTRO_BINARY} 命令（Apache-2.0 单二进制，Java 17+，"
                "testPike 不随包分发）：请在执行机自行安装 Maestro CLI 并加入 PATH "
                "后重试（装机指引=maestro.dev 官方文档，缺件归因禁静默降级换引擎）")
        lease = ctx.get("leaseRef") or {}
        if not lease.get("deviceId"):
            raise ValueError("Maestro 会话准备失败：设备租约未确认（信封 leaseRef "
                             "缺设备标识），禁止裸跑引擎")
        self._adb_serial = lease.get("serial")
        self._ctx = dict(ctx)
        root = pathlib.Path(self.artifact_root) / f"maestro-{ctx.get('taskId') or ctx.get('runId') or 'run'}"
        root.mkdir(parents=True, exist_ok=True)
        self._artifact_dir = root
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
            return self._adb.run(verb, step, serial=self._adb_serial)
        self._run_seq += 1
        tag = f"{self._run_seq}-{step.get('stepNo') or 0}"
        shot_path = str(self._artifact_dir / f"step-{tag}-maestro.png")
        try:
            flow = build_flow_yaml(verb, step, self._ctx, screenshot_path=shot_path)
        except MaestroFlowError as e:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": str(e)}
        flow_file = self._artifact_dir / f"flow-{tag}.yaml"
        flow_file.write_text(flow, encoding="utf-8")
        junit_file = self._artifact_dir / f"junit-{tag}.xml"
        argv = [MAESTRO_BINARY, "test", "--no-analytics",
                "--format", "junit", "--output", str(junit_file), str(flow_file)]
        try:
            proc = subprocess.Popen(argv, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, text=True)
        except OSError as e:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": f"Maestro 引擎进程拉起失败: {e}"}
        self._proc = proc
        try:
            _stdout, stderr = proc.communicate(timeout=self.timeout_sec)
            exit_code = proc.returncode
        except subprocess.TimeoutExpired:
            proc.kill()
            try:
                proc.communicate(timeout=5)
            except Exception:
                pass
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": f"Maestro 引擎执行预算超时（>{self.timeout_sec:.0f}s），"
                              "已定向终止引擎进程"}
        stderr_tail = (stderr or "")[-4000:]
        (self._artifact_dir / f"stderr-{tag}.log").write_text(
            stderr_tail or "", encoding="utf-8")
        self._last_tag = tag
        xml_text = junit_file.read_text(encoding="utf-8") if junit_file.exists() else None
        res = parse_maestro_junit(xml_text, exit_code, stderr_tail)
        if (res.get("result") == "pass" and verb == "screenshot"
                and not pathlib.Path(shot_path).exists()):
            res = {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                   "detail": "Maestro 报告步成功但未产出截图文件（产物与结果矛盾）"}
        return res

    def collect(self, artifact: dict):
        pass
        if not self._prepared:
            return None
        root = pathlib.Path(self._artifact_dir)
        step_no = artifact.get("stepNo")
        items: list[dict] = []
        shots = (sorted(root.glob(f"step-*-{step_no}-maestro.png"))
                 if step_no is not None else sorted(root.glob("step-*-maestro.png")))
        for p in shots[-1:]:
            if p.exists() and p.stat().st_size > 0:
                items.append({"kind": "screenshot", "localPath": str(p),
                              "contentType": "image/png"})
        logs = sorted(root.glob(f"stderr-*-{step_no}.log" if step_no is not None
                                else "stderr-*.log"))
        for p in logs[-1:]:
            if p.exists() and p.stat().st_size > 0:
                items.append({"kind": "log_tail", "localPath": str(p),
                              "contentType": "text/plain"})
        return items or None

    def watch_frame(self, artifact_dir: str, max_width=None):
        pass
        if not self._prepared:
            return None
        shots = sorted(self._artifact_dir.glob("step-*-maestro.png"))
        latest = [s for s in shots if s.stat().st_size > 0]
        if not latest:
            return None
        return monitor_frame_from_artifacts(
            [{"kind": "screenshot", "localPath": str(latest[-1])}], max_width=max_width)

    def teardown(self) -> None:
        self._prepared = False
        self._adb.clear_network_profile(serial=self._adb_serial)


def _step_budget_sec() -> float:
    pass
    raw = os.environ.get(_STEP_BUDGET_SEC_ENV_KEY, "").strip()
    try:
        return max(float(raw) if raw else _STEP_BUDGET_SEC_DEFAULT, 1.0)
    except ValueError:
        return _STEP_BUDGET_SEC_DEFAULT
