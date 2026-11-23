
from __future__ import annotations

import os
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from actions_common import IR_VERBS
from driver.mobile.adb_verb_runner import ADB_FAMILY_VERBS, AdbVerbRunner

PKG_SHIM_SRC = '''#!/bin/sh
echo "$@" >> "$ADB_SHIM_LOG"
for a in "$@"; do
  if [ "$a" = "install" ]; then echo "${ADB_SHIM_INSTALL_OUT:-Success}"; fi
  if [ "$a" = "uninstall" ]; then echo "${ADB_SHIM_UNINSTALL_OUT:-Success}"; fi
  if [ "$a" = "dumpsys" ]; then printf '%s\\n' "${ADB_SHIM_DUMPSYS_OUT}"; fi
done
exit 0
'''


def _install_pkg_shim(tmp_path, monkeypatch, install_out=None,
                      uninstall_out=None, dumpsys_out=None):
    pass
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "adb"
    shim.write_text(PKG_SHIM_SRC, encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))
    log = tmp_path / "adb-argv.log"
    monkeypatch.setenv("ADB_SHIM_LOG", str(log))
    for key, val in (("ADB_SHIM_INSTALL_OUT", install_out),
                     ("ADB_SHIM_UNINSTALL_OUT", uninstall_out),
                     ("ADB_SHIM_DUMPSYS_OUT", dumpsys_out)):
        if val is None:
            monkeypatch.delenv(key, raising=False)
        else:
            monkeypatch.setenv(key, val)
    return log


def _argv_rows(log_path):
    if not log_path.exists():
        return []
    return [line.strip() for line in log_path.read_text(encoding="utf-8").splitlines()]



def test_package_verbs_join_adb_family_subset_of_ir():
    assert {"install", "uninstall", "verifyVersion"} <= set(ADB_FAMILY_VERBS)
    assert set(ADB_FAMILY_VERBS) <= set(IR_VERBS)



def test_install_plan_uses_local_path_and_requires_both_fields():
    runner = AdbVerbRunner()
    assert runner.planner("install", {"pkg": "com.demo",
                                      "localPath": "/tmp/dl/demo.apk"}) == \
        [["install", "-r", "/tmp/dl/demo.apk"]]
    res = runner.run("install", {"pkg": "com.demo"}, serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "localPath" in res["detail"], "缺包源路径须人话点名注入通道"
    res2 = runner.run("install", {"localPath": "/tmp/a.apk"}, serial="em-1")
    assert res2["result"] == "fail" and res2["errorType"] == 1


def test_uninstall_plan_shape():
    runner = AdbVerbRunner()
    assert runner.planner("uninstall", {"pkg": "com.demo"}) == [["uninstall", "com.demo"]]
    res = runner.run("uninstall", {}, serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "pkg" in res["detail"]


def test_verify_version_plan_dumpsys_and_requires_expect_version():
    runner = AdbVerbRunner()
    assert runner.planner("verifyVersion",
                          {"pkg": "com.demo", "expectVersion": "1.2.0"}) == \
        [["shell", "dumpsys", "package", "com.demo"]]
    res = runner.run("verifyVersion", {"pkg": "com.demo"}, serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "expectVersion" in res["detail"]



def test_install_success_real_subprocess_with_serial(tmp_path, monkeypatch):
    log = _install_pkg_shim(tmp_path, monkeypatch)
    res = AdbVerbRunner().run("install", {"pkg": "com.demo",
                                          "localPath": "/tmp/dl/demo.apk"},
                              serial="emulator-5554")
    assert res["result"] == "pass", res
    assert _argv_rows(log) == ["-s emulator-5554 install -r /tmp/dl/demo.apk"]


def test_install_contradiction_output_fails(tmp_path, monkeypatch):
    pass
    _install_pkg_shim(tmp_path, monkeypatch,
                      install_out="Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]")
    res = AdbVerbRunner().run("install", {"pkg": "com.demo",
                                          "localPath": "/tmp/dl/demo.apk"}, serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 2
    assert "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in res["detail"]


def test_uninstall_success_and_contradiction(tmp_path, monkeypatch):
    log = _install_pkg_shim(tmp_path, monkeypatch)
    assert AdbVerbRunner().run("uninstall", {"pkg": "com.demo"},
                               serial="em-1")["result"] == "pass"
    assert _argv_rows(log) == ["-s em-1 uninstall com.demo"]
    _install_pkg_shim(tmp_path, monkeypatch, uninstall_out="Failure")
    res = AdbVerbRunner().run("uninstall", {"pkg": "com.demo"}, serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 2


def test_verify_version_match_passes(tmp_path, monkeypatch):
    _install_pkg_shim(tmp_path, monkeypatch,
                      dumpsys_out="Version: 7.0\n  versionName=1.2.0\n  codePath=/data/app/com.demo")
    res = AdbVerbRunner().run("verifyVersion",
                              {"pkg": "com.demo", "expectVersion": "1.2.0"},
                              serial="em-1")
    assert res["result"] == "pass", res


def test_verify_version_mismatch_fails_in_plain_words(tmp_path, monkeypatch):
    pass
    _install_pkg_shim(tmp_path, monkeypatch, dumpsys_out="  versionName=1.2.0")
    res = AdbVerbRunner().run("verifyVersion",
                              {"pkg": "com.demo", "expectVersion": "1.3.0"},
                              serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "1.2.0" in res["detail"] and "1.3.0" in res["detail"]
    assert "com.demo" in res["detail"]


def test_verify_version_not_installed_fails(tmp_path, monkeypatch):
    _install_pkg_shim(tmp_path, monkeypatch,
                      dumpsys_out="Unable to find package: com.demo")
    res = AdbVerbRunner().run("verifyVersion",
                              {"pkg": "com.demo", "expectVersion": "1.3.0"},
                              serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "未安装" in res["detail"]


def test_verify_version_missing_adb_is_env_fail(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path / "empty"))
    res = AdbVerbRunner().run("verifyVersion",
                              {"pkg": "com.demo", "expectVersion": "1.3.0"},
                              serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 2
    assert "自行安装" in res["detail"] or "不随包分发" in res["detail"]



def test_read_installed_version_returns_parsed_version(tmp_path, monkeypatch):
    _install_pkg_shim(tmp_path, monkeypatch, dumpsys_out="  versionName=2.5.1")
    out = AdbVerbRunner().read_installed_version("com.demo", serial="em-1")
    assert out["result"] == "pass" and out["version"] == "2.5.1"


def test_read_installed_version_missing_pkg_human_detail(tmp_path, monkeypatch):
    _install_pkg_shim(tmp_path, monkeypatch, dumpsys_out="Unable to find package")
    out = AdbVerbRunner().read_installed_version("com.demo")
    assert out["result"] == "fail" and "com.demo" in out["detail"]


def test_read_installed_version_missing_binary_env_detail(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path / "empty"))
    out = AdbVerbRunner().read_installed_version("com.demo")
    assert out["result"] == "fail" and out["errorType"] == 2



class _RecordingBridge:
    def __init__(self):
        self.calls = []

    def start(self):
        pass

    def alive(self):
        return True

    def call(self, method, params=None):
        self.calls.append((method, params or {}))
        return {"result": "pass"}

    def stop(self):
        pass


class _RecordingAdb:
    def __init__(self):
        self.runs = []

    def run(self, verb, step, serial=None):
        self.runs.append((verb, serial))
        return {"result": "pass"}


def test_mobilewright_package_verbs_intercepted_before_bridge():
    from driver.mobile.mobilewright_adapter import MobilewrightAdapter

    descriptor = {"code": "MOBILEWRIGHT", "status": 1,
                  "capabilities": {v: True for v in IR_VERBS}}
    adapter = MobilewrightAdapter(descriptor=descriptor)
    bridge = _RecordingBridge()
    adapter._bridge = bridge
    adapter._adb = _RecordingAdb()
    adapter.prepare({"runId": "r1", "leaseRef": {"serial": "em-9"}})
    for verb in ("install", "uninstall", "verifyVersion"):
        res = adapter.execute({"action": f"app.{verb}", "verb": verb})
        assert res.get("result") == "pass", f"{verb}: {res}"
    assert [m for m, _ in bridge.calls if m == "execute"] == [], "包管理三枚不得触桥"
    assert [v for v, _ in adapter._adb.runs] == ["install", "uninstall", "verifyVersion"]
    assert all(s == "em-9" for _, s in adapter._adb.runs), "租约 serial 须透传 adb 面"


def test_maestro_package_verbs_intercepted_no_flow(tmp_path):
    from driver.mobile.maestro_adapter import MaestroAdapter

    descriptor = {"code": "MAESTRO", "status": 1,
                  "capabilities": {v: True for v in IR_VERBS}}
    adapter = MaestroAdapter(descriptor=descriptor,
                             artifact_dir=str(tmp_path / "arts"))
    adapter._prepared = True
    adapter._adb_serial = "em-3"
    adapter._adb = _RecordingAdb()
    res = adapter.execute({"action": "app.verifyVersion", "verb": "verifyVersion",
                           "pkg": "com.demo", "expectVersion": "1.0.0"})
    assert res.get("result") == "pass"
    assert adapter._adb.runs == [("verifyVersion", "em-3")]
    assert list(adapter._artifact_dir.glob("flow-*.yaml")) == [], "包管理枚不得生成 flow"
