
from __future__ import annotations

import os
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from actions_common import IR_VERBS
from driver.mobile.adb_verb_runner import ADB_FAMILY_VERBS, AdbVerbRunner
from driver.mobile.contracts import verb_capability_violation

ADSHIM_SRC = '''#!/bin/sh
echo "$@" >> "$ADB_SHIM_LOG"
for a in "$@"; do
  if [ "$a" = "clear" ]; then echo Success; fi
done
if [ -n "$ADB_SHIM_FAIL" ]; then
  echo "shim-boom" >&2
  exit 1
fi
exit 0
'''


def _install_adb_shim(tmp_path, monkeypatch, fail=False):
    pass
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "adb"
    shim.write_text(ADSHIM_SRC, encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))
    log = tmp_path / "adb-argv.log"
    monkeypatch.setenv("ADB_SHIM_LOG", str(log))
    if fail:
        monkeypatch.setenv("ADB_SHIM_FAIL", "1")
    else:
        monkeypatch.delenv("ADB_SHIM_FAIL", raising=False)
    return log


def _argv_rows(log_path):
    if not log_path.exists():
        return []
    return [line.strip() for line in log_path.read_text(encoding="utf-8").splitlines()]



def test_adb_family_subset_of_ir_verbs():
    assert set(ADB_FAMILY_VERBS).issubset(set(IR_VERBS))
    assert set(ADB_FAMILY_VERBS) == {
        "home", "deepLink", "openUrl", "clearData", "grantPermission",
        "wakeUnlock", "rotation",
        "install", "uninstall", "verifyVersion",
        "networkProfile",
    }
    assert list(ADB_FAMILY_VERBS) == [v for v in IR_VERBS
                                      if v in ADB_FAMILY_VERBS], \
        "族序须与 IR_VERBS 一致（保序律=适配器逐枚对账面）"


def test_best_effort_absent_verbs_not_in_adb_family():
    pass
    for v in ("background", "foreground"):
        assert v not in ADB_FAMILY_VERBS



def test_home_and_wakeunlock_argv_shapes():
    runner = AdbVerbRunner()
    assert runner.planner("home", {}) == [
        ["shell", "input", "keyevent", "KEYCODE_HOME"]]
    rows = runner.planner("wakeUnlock", {})
    assert any("224" in " ".join(r) for r in rows), "亮屏需 POWER 键"
    assert any("82" in " ".join(r) for r in rows), "解锁需 keyevent 82"
    assert any("swipe" in " ".join(r) for r in rows), "解锁需上滑"


def test_deeplink_and_openurl_carry_url():
    runner = AdbVerbRunner()
    deep = runner.planner("deepLink", {"url": "myapp://page/detail"})
    assert "myapp://page/detail" in " ".join(" ".join(r) for r in deep)
    openurl = runner.planner("openUrl", {"url": "https://example.com"})
    text = " ".join(" ".join(r) for r in openurl)
    assert "https://example.com" in text
    assert "BROWSABLE" in text, "openUrl=系统浏览器外链需 BROWSABLE 类别"


def test_rotation_orientation_map_and_bad_value_human():
    runner = AdbVerbRunner()
    rows = runner.planner("rotation", {"orientation": "landscape"})
    flat = " ".join(" ".join(r) for r in rows)
    assert "accelerometer_rotation" in flat and "user_rotation" in flat
    assert " 1" in flat or flat.endswith("1")
    res = runner.run("rotation", {"orientation": "diagonal"}, serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 1
    assert "orientation" in res["detail"] or "landscape" in res["detail"]


def test_grant_permission_one_command_per_permission():
    runner = AdbVerbRunner()
    rows = runner.planner("grantPermission",
                          {"pkg": "com.demo", "permissions": ["a.PERM1", "a.PERM2"]})
    assert [r for r in rows if r[:3] == ["shell", "pm", "grant"]] == [
        ["shell", "pm", "grant", "com.demo", "a.PERM1"],
        ["shell", "pm", "grant", "com.demo", "a.PERM2"],
    ]


def test_missing_required_params_script_fail_in_plain_words():
    runner = AdbVerbRunner()
    for verb, step in (("deepLink", {}), ("openUrl", {}),
                       ("clearData", {}), ("grantPermission", {"pkg": "com.demo"})):
        res = runner.run(verb, step, serial="em-1")
        assert res["result"] == "fail" and res["errorType"] == 1, f"{verb} 缺参须脚本面归因"



def test_run_executes_real_subprocess_with_serial(tmp_path, monkeypatch):
    log = _install_adb_shim(tmp_path, monkeypatch)
    runner = AdbVerbRunner()
    res = runner.run("home", {}, serial="emulator-5554")
    assert res["result"] == "pass", res
    rows = _argv_rows(log)
    assert rows == ["-s emulator-5554 shell input keyevent KEYCODE_HOME"]


def test_run_without_serial_omits_target_flag(tmp_path, monkeypatch):
    log = _install_adb_shim(tmp_path, monkeypatch)
    res = AdbVerbRunner().run("home", {})
    assert res["result"] == "pass"
    assert not _argv_rows(log)[0].startswith("-s")


def test_cleardata_requires_success_output(tmp_path, monkeypatch):
    pass
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "adb"
    shim.write_text(
        '#!/bin/sh\necho "$@" >> "$ADB_SHIM_LOG"\n'
        'for a in "$@"; do\n'
        '  if [ "$a" = "clear" ]; then echo "Failure: not found"; fi\n'
        'done\nexit 0\n',
        encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))
    log = tmp_path / "adb-argv.log"
    monkeypatch.setenv("ADB_SHIM_LOG", str(log))
    res = AdbVerbRunner().run("clearData", {"pkg": "com.demo"}, serial="em-1")
    assert res["result"] == "fail"
    assert res["errorType"] == 2
    assert "Failure" in res["detail"]


def test_nonzero_exit_is_env_fail_with_tail(tmp_path, monkeypatch):
    _install_adb_shim(tmp_path, monkeypatch, fail=True)
    res = AdbVerbRunner().run("home", {}, serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 2
    assert "shim-boom" in res["detail"]


def test_missing_adb_binary_human_install_hint(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path / "empty"))
    res = AdbVerbRunner().run("home", {}, serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 2
    assert "自行安装" in res["detail"] or "不随包分发" in res["detail"]


def test_non_family_verb_refused_by_runner(tmp_path, monkeypatch):
    _install_adb_shim(tmp_path, monkeypatch)
    res = AdbVerbRunner().run("tap", {}, serial="em-1")
    assert res["result"] == "fail" and res["errorType"] == 1



CAPS_DESCRIPTOR = {
    "code": "MOBILEWRIGHT", "status": 1,
    "capabilities": {**{v: True for v in IR_VERBS},
                     "background": False, "foreground": False,
                     "networkProfile": False},
}


def test_capability_gate_explicit_false_rejects_in_plain_words():
    res = verb_capability_violation(CAPS_DESCRIPTOR, "networkProfile")
    assert res is not None and res["result"] == "fail"
    assert "networkProfile" in res["detail"]
    assert "MOBILEWRIGHT" in res["detail"]


def test_capability_gate_true_or_missing_key_passes():
    assert verb_capability_violation(CAPS_DESCRIPTOR, "home") is None
    assert verb_capability_violation({"code": "X"}, "home") is None, "缺键=不判（交桥默认闸）"



class _RecordingBridge:
    def __init__(self):
        self.calls = []
        self.extra_env = {}

    def start(self):
        pass

    def alive(self):
        return True

    def call(self, method, params=None):
        self.calls.append((method, params or {}))
        if method == "execute":
            return {"result": "pass"}
        return {"ok": True}

    def stop(self):
        pass


class _RecordingAdb:
    def __init__(self):
        self.runs = []

    def run(self, verb, step, serial=None):
        self.runs.append((verb, serial))
        return {"result": "pass"}


def test_mobilewright_adb_family_intercepted_before_bridge():
    from driver.mobile.mobilewright_adapter import MobilewrightAdapter

    descriptor = {"code": "MOBILEWRIGHT", "status": 1,
                  "capabilities": {v: True for v in IR_VERBS}}
    adapter = MobilewrightAdapter(descriptor=descriptor)
    bridge = _RecordingBridge()
    adapter._bridge = bridge
    adapter._adb = _RecordingAdb()
    adapter.prepare({"runId": "r1", "leaseRef": {"serial": "em-9"}})
    for verb in ADB_FAMILY_VERBS:
        res = adapter.execute({"action": f"app.{verb}", "verb": verb})
        assert res.get("result") == "pass", f"{verb}: {res}"
    sent = [m for m, _ in bridge.calls if m == "execute"]
    assert sent == [], "adb 族动词不得触桥"
    assert [v for v, _ in adapter._adb.runs] == list(ADB_FAMILY_VERBS)
    assert all(s == "em-9" for _, s in adapter._adb.runs), "租约 serial 须透传 adb 面"


def test_mobilewright_capability_false_verb_honest_reject():
    from driver.mobile.mobilewright_adapter import MobilewrightAdapter

    descriptor = dict(CAPS_DESCRIPTOR)
    descriptor["capabilities"] = {**CAPS_DESCRIPTOR["capabilities"], "background": False}
    adapter = MobilewrightAdapter(descriptor=descriptor)
    adapter._bridge = _RecordingBridge()
    adapter._adb = _RecordingAdb()
    adapter.prepare({})
    res = adapter.execute({"action": "app.background", "verb": "background",
                           "durationSec": 5})
    assert res.get("result") == "fail"
    assert "background" in (res.get("detail") or "")
    assert adapter._adb.runs == [] and not [m for m, _ in
                                            adapter._bridge.calls if m == "execute"]


def test_mobilewright_original_verbs_still_dispatch_to_bridge():
    pass
    from driver.mobile.mobilewright_adapter import MobilewrightAdapter

    adapter = MobilewrightAdapter(descriptor=dict(CAPS_DESCRIPTOR))
    bridge = _RecordingBridge()
    adapter._bridge = bridge
    adapter._adb = _RecordingAdb()
    adapter.prepare({})
    assert adapter.execute({"action": "app.tap", "verb": "tap",
                            "x": 1, "y": 2}).get("result") == "pass"
    assert [m for m, _ in bridge.calls if m == "execute"] == ["execute"]


def test_maestro_adb_family_intercepted_no_flow(tmp_path):
    from driver.mobile.maestro_adapter import MaestroAdapter

    descriptor = {"code": "MAESTRO", "status": 1}
    adapter = MaestroAdapter(descriptor=descriptor,
                             artifact_dir=str(tmp_path / "arts"))
    adapter._prepared = True
    adapter._adb_serial = "em-3"
    adapter._adb = _RecordingAdb()
    res = adapter.execute({"action": "app.clearData", "verb": "clearData",
                           "pkg": "com.demo"})
    assert res.get("result") == "pass"
    assert adapter._adb.runs == [("clearData", "em-3")]
    assert list(adapter._artifact_dir.glob("flow-*.yaml")) == [], "adb 族不得生成 flow"


def test_maestro_background_capability_reject(tmp_path):
    from driver.mobile.maestro_adapter import MaestroAdapter

    descriptor = {"code": "MAESTRO", "status": 1,
                  "capabilities": {v: v not in ("background", "foreground",
                                                "networkProfile") for v in IR_VERBS}}
    adapter = MaestroAdapter(descriptor=descriptor,
                             artifact_dir=str(tmp_path / "arts2"))
    adapter._prepared = True
    adapter._adb = _RecordingAdb()
    res = adapter.execute({"action": "app.foreground", "verb": "foreground"})
    assert res.get("result") == "fail"
    assert "foreground" in (res.get("detail") or "")
