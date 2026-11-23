
from __future__ import annotations

import os
import re
import shutil
import socket
import subprocess
import tempfile
from collections.abc import Callable
from pathlib import Path
from typing import ClassVar

from driver.net_throttle import (
    NetThrottleError,
    ThrottleProxy,
    parse_network_profile_args,
)

ADB_BINARY = "adb"

NET_TIMELINE_FILENAME = "tp-net-timeline.jsonl"
PROXY_ADVERTISE_HOST_ENV = "TESTPIKE_PROXY_ADVERTISE_HOST"
_PROXY_CLEAR_VALUE = ":0"
_PROXY_GET_ROW: list[str] = ["shell", "settings", "get", "global", "http_proxy"]
_PROXY_CLEAN_VALUES = frozenset({_PROXY_CLEAR_VALUE, "null", ""})

_ERROR_TYPE_SCRIPT = 1
_ERROR_TYPE_ENV = 2

ADB_FAMILY_VERBS: tuple = (
    "deepLink", "openUrl", "rotation", "home",
    "clearData", "grantPermission", "wakeUnlock", "networkProfile",
    "install", "uninstall", "verifyVersion",
)

def _dumpsys_pkg_args(pkg: str) -> list[str]:
    return ["shell", "dumpsys", "package", pkg]

_VERSION_NAME_RE = re.compile(r"versionName=(\S+)")

_ADB_MISSING_DETAIL = (f"本执行机未找到 {ADB_BINARY} 命令（Android SDK 运行时，"
                       "testPike 不随包分发）：请自行安装 adb 并加入 PATH 后重试"
                       "（缺件归因禁静默降级换引擎）")

_ROTATION_VALUES = {"portrait": "0", "landscape": "1",
                    "reverse_landscape": "2", "reverse_portrait": "3",
                    "landscapeRight": "2", "portraitUpsideDown": "3"}


def _require(step: dict, key: str, verb: str) -> str:
    val = str(step.get(key) or "").strip()
    if not val:
        raise ValueError(f"{verb} 缺 {key} 参数（步骤信封为空）——请修正脚本步参数")
    return val


class AdbVerbRunner:
    pass

    _serial_tag: str | None = None
    _net_proxy: ThrottleProxy | None = None
    _net_serial: str | None = None


    def _plan_home(self, step: dict) -> list[list[str]]:
        return [["shell", "input", "keyevent", "KEYCODE_HOME"]]

    def _plan_wakeUnlock(self, step: dict) -> list[list[str]]:
        return [
            ["shell", "input", "keyevent", "224"],
            ["shell", "input", "swipe", "540", "1600", "540", "300", "300"],
            ["shell", "input", "keyevent", "82"],
        ]

    def _plan_deepLink(self, step: dict) -> list[list[str]]:
        url = _require(step, "url", "deepLink")
        return [["shell", "am", "start", "-a",
                 "android.intent.action.VIEW", "-d", url]]

    def _plan_openUrl(self, step: dict) -> list[list[str]]:
        url = _require(step, "url", "openUrl")
        return [["shell", "am", "start", "-a", "android.intent.action.VIEW",
                 "-c", "android.intent.category.BROWSABLE", "-d", url]]

    def _plan_rotation(self, step: dict) -> list[list[str]]:
        orientation = str(step.get("orientation") or "").strip()
        value = _ROTATION_VALUES.get(orientation)
        if value is None:
            raise ValueError(
                f"rotation 方向值「{orientation or '（空）'}」不在支持面——"
                "请改用 portrait/landscape（或 reverse_portrait/landscapeRight）")
        return [
            ["shell", "settings", "put", "system", "accelerometer_rotation", "0"],
            ["shell", "settings", "put", "system", "user_rotation", value],
        ]

    def _plan_clearData(self, step: dict) -> list[list[str]]:
        pkg = _require(step, "pkg", "clearData")
        return [["shell", "pm", "clear", pkg]]

    def _plan_grantPermission(self, step: dict) -> list[list[str]]:
        pkg = _require(step, "pkg", "grantPermission")
        perms = step.get("permissions")
        if not isinstance(perms, list) or not perms:
            raise ValueError("grantPermission 缺 permissions 清单（非空数组）"
                             "——请修正脚本步参数")
        return [["shell", "pm", "grant", pkg, str(p)] for p in perms]


    def _plan_install(self, step: dict) -> list[list[str]]:
        _require(step, "pkg", "install")
        local_path = _require(step, "localPath",
                              "install（包源文件缺失——请确认下载链已注入 localPath）")
        return [["install", "-r", local_path]]

    def _plan_uninstall(self, step: dict) -> list[list[str]]:
        pkg = _require(step, "pkg", "uninstall")
        return [["uninstall", pkg]]

    def _plan_verifyVersion(self, step: dict) -> list[list[str]]:
        pkg = _require(step, "pkg", "verifyVersion")
        _require(step, "expectVersion", "verifyVersion")
        return [_dumpsys_pkg_args(pkg)]


    def _plan_networkProfile(self, step: dict) -> list[list[str]]:
        addr = _require(step, "proxyAddress",
                        "networkProfile（代理地址缺失——执行面未起代理）")
        return self._proxy_set_rows(addr)

    @staticmethod
    def _proxy_set_rows(address: str) -> list[list[str]]:
        return [["shell", "settings", "put", "global", "http_proxy", address]]

    @staticmethod
    def _proxy_clear_rows() -> list[list[str]]:
        return [["shell", "settings", "put", "global", "http_proxy",
                 _PROXY_CLEAR_VALUE]]


    def audit_net_proxy(self, serial: str | None) -> dict | None:
        pass
        if shutil.which(ADB_BINARY) is None:
            return None
        proc = self._exec_row(list(_PROXY_GET_ROW), serial)
        if isinstance(proc, dict) or proc.returncode != 0:
            return None
        value = (proc.stdout or "").strip()
        if value in _PROXY_CLEAN_VALUES:
            return None
        res = self._exec_proxy_row(self._proxy_clear_rows(), serial, "已清")
        return {"serial": str(serial or ""), "proxy": value,
                "action": "cleared" if res["result"] == "pass" else "clear-failed",
                "detail": str(res.get("detail", ""))}

    @staticmethod
    def _is_emulator_serial(serial: str | None) -> bool:
        return str(serial or "").startswith(("emulator-", "127.0.0.1:",
                                             "localhost:"))

    @staticmethod
    def _lan_address() -> str:
        pass
        override = os.environ.get(PROXY_ADVERTISE_HOST_ENV, "").strip()
        if override:
            return override
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.connect(("8.8.8.8", 53))
            return str(sock.getsockname()[0])
        except OSError:
            return "127.0.0.1"
        finally:
            sock.close()

    def _run_network_profile(self, step: dict, spec,
                             serial: str | None) -> dict:
        pass
        self._retract_active_profile()
        emulator = self._is_emulator_serial(serial)
        artifact_dir = str(step.get("artifactDir") or
                           Path(tempfile.gettempdir()))
        proxy = ThrottleProxy(spec,
                              evidence_path=str(Path(artifact_dir) /
                                                NET_TIMELINE_FILENAME),
                              listen_host="127.0.0.1" if emulator else "0.0.0.0")
        try:
            proxy.start()
        except OSError as e:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": f"弱网代理起口失败（执行机端口资源/网络栈异常）：{e}"}
        self._net_proxy = proxy
        self._net_serial = serial
        address = f"{'10.0.2.2' if emulator else self._lan_address()}:{proxy.port}"
        return self._exec_proxy_row(self._proxy_set_rows(address), serial,
                                    f"弱网档位生效（代理 {address}）")

    def _exec_proxy_row(self, rows: list[list[str]], serial: str | None,
                        ok_note: str) -> dict:
        for row in rows:
            proc = self._exec_row(row, serial)
            if isinstance(proc, dict):
                return proc
            if proc.returncode != 0:
                tail = ((proc.stderr or "") + (proc.stdout or ""))[-400:]
                return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                        "detail": f"adb {' '.join(row)} 非零退出"
                                  f"（rc={proc.returncode}）：{tail.strip()}"}
        return {"result": "pass", "detail": ok_note}

    def _retract_active_profile(self) -> None:
        pass
        if self._net_proxy is None:
            return
        proxy, serial = self._net_proxy, self._net_serial
        self._net_proxy = None
        self._net_serial = None
        self._exec_proxy_row(self._proxy_clear_rows(), serial, "已清")
        proxy.stop()

    def clear_network_profile(self, serial: str | None = None) -> dict:
        pass
        if self._net_proxy is None:
            return {"result": "pass"}
        proxy = self._net_proxy
        target = str(serial or self._net_serial or "")
        self._net_proxy = None
        self._net_serial = None
        res = self._exec_proxy_row(self._proxy_clear_rows(), target or None,
                                   "已清")
        proxy.stop()
        if res["result"] != "pass":
            res = dict(res)
            res["detail"] = (f"设备（serial={target or '（未知）'}）弱网代理清除未确认："
                             + str(res.get("detail", ""))
                             + "（执行器侧端口已收，设备侧残留转巡检对账）")
        return res

    _PLANS: ClassVar[dict] = {
        "home": _plan_home,
        "wakeUnlock": _plan_wakeUnlock,
        "deepLink": _plan_deepLink,
        "openUrl": _plan_openUrl,
        "rotation": _plan_rotation,
        "clearData": _plan_clearData,
        "grantPermission": _plan_grantPermission,
        "install": _plan_install,
        "uninstall": _plan_uninstall,
        "verifyVersion": _plan_verifyVersion,
        "networkProfile": _plan_networkProfile,
    }

    _MUST_CONTAIN: ClassVar[dict] = {"clearData": "Success",
                                     "install": "Success",
                                     "uninstall": "Success"}

    def _exec_row(self, row: list[str],
                  serial: str | None) -> subprocess.CompletedProcess | dict:
        pass
        argv = [ADB_BINARY] + (["-s", serial] if serial else []) + row
        try:
            return subprocess.run(argv, capture_output=True, text=True,
                                  timeout=30, check=False)
        except OSError as e:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": f"adb 进程拉起失败: {e}"}

    def _reconcile_version(self, step: dict, dumpsys_out: str) -> dict:
        pass
        pkg = str(step.get("pkg"))
        expect = str(step.get("expectVersion"))
        found = _VERSION_NAME_RE.search(dumpsys_out or "")
        if found is None:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"设备（serial={self._serial_tag}）未安装 {pkg}，"
                              f"期望版本 {expect}——版本对账不通过，禁静默放行"}
        actual = found.group(1)
        if actual != expect:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"设备（serial={self._serial_tag}）{pkg} 现装版本 "
                              f"{actual} ≠ 期望 {expect}——版本对账不通过，"
                              "防假版本假通过，请先跑 app.install 对齐包源"}
        return {"result": "pass"}

    def read_installed_version(self, pkg: str,
                               serial: str | None = None) -> dict:
        pass
        self._serial_tag = serial
        if not str(pkg or "").strip():
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": "版本读口缺 pkg 参数——请修正调用方"}
        if shutil.which(ADB_BINARY) is None:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": _ADB_MISSING_DETAIL}
        proc = self._exec_row(_dumpsys_pkg_args(pkg), serial)
        if isinstance(proc, dict):
            return proc
        found = _VERSION_NAME_RE.search(proc.stdout or "")
        if found is None:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"设备（serial={serial}）未安装 {pkg}"
                              "（dumpsys 未读到 versionName）"}
        return {"result": "pass", "version": found.group(1)}

    def planner(self, verb: str, step: dict) -> list[list[str]]:
        pass
        plan: Callable | None = self._PLANS.get(verb)
        if plan is None:
            raise ValueError(f"动词 {verb} 不在 adb 命令族映射面（adb 族仅 "
                             + "/".join(ADB_FAMILY_VERBS) + "）")
        return plan(self, step)

    def run(self, verb: str, step: dict, serial: str | None = None) -> dict:
        pass
        if verb not in ADB_FAMILY_VERBS:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                    "detail": f"动词 {verb} 不在 adb 命令族映射面——该步不应由 adb 面执行"}
        if verb == "networkProfile":
            try:
                spec = parse_network_profile_args(
                    {k: step[k] for k in ("profile", "custom") if k in step})
            except NetThrottleError as e:
                return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT,
                        "detail": str(e)}
            if shutil.which(ADB_BINARY) is None:
                return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                        "detail": _ADB_MISSING_DETAIL}
            return self._run_network_profile(step, spec, serial)
        try:
            rows = self.planner(verb, step)
        except ValueError as e:
            return {"result": "fail", "errorType": _ERROR_TYPE_SCRIPT, "detail": str(e)}
        if shutil.which(ADB_BINARY) is None:
            return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                    "detail": _ADB_MISSING_DETAIL}
        self._serial_tag = serial
        last_stdout = ""
        for row in rows:
            proc = self._exec_row(row, serial)
            if isinstance(proc, dict):
                return proc
            if proc.returncode != 0:
                tail = ((proc.stderr or "") + (proc.stdout or ""))[-400:]
                return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                        "detail": f"adb {' '.join(row)} 非零退出"
                                  f"（rc={proc.returncode}）：{tail.strip()}"}
            last_stdout = proc.stdout or ""
            needle = self._MUST_CONTAIN.get(verb)
            if needle and needle not in last_stdout:
                return {"result": "fail", "errorType": _ERROR_TYPE_ENV,
                        "detail": f"adb {' '.join(row)} 已退出 0 但未回 {needle!r}"
                                  "（产物与结果矛盾，判定未生效）："
                                  + last_stdout.strip()[-200:]}
        if verb == "verifyVersion":
            return self._reconcile_version(step, last_stdout)
        return {"result": "pass"}
