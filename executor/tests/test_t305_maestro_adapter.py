
from __future__ import annotations

import json
import os
import pathlib
import sys

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from actions_common import IR_VERBS
from driver.mobile.contracts import capability_violations
from driver.mobile.engine_factory import mobile_engine_factory

V145_MAESTRO_DESCRIPTOR = {
    "code": "MAESTRO",
    "status": 1,
    "runtimeType": "CLI_SUBPROC",
    "platformCodes": ["ANDROID", "IOS"],
    "deviceKindCodes": ["EMULATOR", "SIMULATOR", "REAL"],
}

SHIM_SRC = '''#!__PYTHON__
import json
import os
import sys

args = sys.argv[1:]
out = None
flow = None
i = 0
while i < len(args):
    if args[i] == "--output":
        out = args[i + 1]
        i += 2
        continue
    if not args[i].startswith("-"):
        flow = args[i]
    i += 1

flow_text = open(flow, encoding="utf-8").read() if flow else ""
shot_env = os.environ.get("SHIM_SHOT_DST")
if "takeScreenshot" in flow_text:
    dst = shot_env or flow_text.split("takeScreenshot:")[-1].strip().splitlines()[0]
    src = os.environ.get("SHIM_SHOT_SRC")
    if src:
        import shutil
        shutil.copy(src, dst)
    open(dst + ".log", "w").write("shot ok\\n")
    print("SHIM-SHOT " + dst)

if os.environ.get("SHIM_HANG"):
    import time
    time.sleep(30)

mode = os.environ.get("SHIM_MODE", "pass")
msg = os.environ.get("SHIM_FAILMSG", "Tap on element failed")
if mode == "pass":
    body = '<testsuite name="maestro" tests="1" skipped="0" failures="0" errors="0" time="2.0"><testcase name="flow" classname="maestro" time="2.0"/></testsuite>'
    rc = 0
elif mode == "notxml":
    body = "not-xml-at-all"
    rc = 1
else:
    tag = "error" if mode == "error" else "failure"
    nfail = 1 if tag == "failure" else 0
    nerr = 1 if tag == "error" else 0
    body = ('<testsuite name="maestro" tests="1" skipped="0" failures="'
            + str(nfail) + '" errors="' + str(nerr) + '" time="1.0">'
            '<testcase name="flow" classname="maestro" time="1.0">'
            + '<' + tag + ' message="' + msg + '">stacktrace-tail</' + tag + '>'
            + '<system-out>cmd trace</system-out></testcase></testsuite>')
    rc = 1
if out:
    open(out, "w", encoding="utf-8").write(body)
sys.stderr.write("maestro shim stderr tail\\n")
sys.exit(rc)
'''


def _install_shim(tmp_path, monkeypatch):
    pass
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "maestro"
    shim.write_text(SHIM_SRC.replace("__PYTHON__", sys.executable), encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("PATH", str(bin_dir))
    return bin_dir


def _adapter(tmp_path, monkeypatch):
    from driver.mobile.maestro_adapter import MaestroAdapter
    a = MaestroAdapter(descriptor=dict(V145_MAESTRO_DESCRIPTOR),
                       artifact_dir=str(tmp_path / "arts"))
    return a



def test_factory_loads_real_maestro_adapter():
    obj = mobile_engine_factory(dict(V145_MAESTRO_DESCRIPTOR))
    assert type(obj).__name__ == "MaestroAdapter"


def test_adapter_declares_android_emulator_real():
    from driver.mobile.maestro_adapter import MaestroAdapter
    assert MaestroAdapter.SUPPORTED_PLATFORMS == ("ANDROID",)
    assert set(MaestroAdapter.SUPPORTED_DEVICE_KINDS) <= {"EMULATOR", "REAL", "SIMULATOR"}


def test_descriptor_capability_reconciliation_clean():
    from driver.mobile.maestro_adapter import MaestroAdapter
    assert capability_violations(MaestroAdapter(), V145_MAESTRO_DESCRIPTOR) is None


def test_capabilities_follow_ir_baseline_single_source():
    from driver.mobile.maestro_adapter import MaestroAdapter
    assert tuple(MaestroAdapter.CAPABILITIES) == tuple(IR_VERBS)


def test_disabled_descriptor_refused_by_factory():
    with pytest.raises(ValueError, match="已停用"):
        mobile_engine_factory(dict(V145_MAESTRO_DESCRIPTOR, status=0))



def test_launch_flow_yaml_has_appid_and_command(tmp_path):
    from driver.mobile.maestro_adapter import build_flow_yaml
    text = build_flow_yaml("launch", {"pkg": "com.demo.app"}, {})
    assert text.splitlines()[0].strip() == "appId: com.demo.app"
    assert "launchApp" in text


def test_tap_selector_variants_render_json_safe(tmp_path):
    from driver.mobile.maestro_adapter import build_flow_yaml
    by_id = build_flow_yaml("tap", {"testId": 'we"ird'}, {})
    assert json.dumps({"id": 'we"ird'}) in by_id
    by_text = build_flow_yaml("tap", {"text": "登录"}, {})
    assert json.dumps({"text": "登录"}, ensure_ascii=False) in by_text
    by_point = build_flow_yaml("tap", {"x": 100, "y": 200}, {})
    assert json.dumps({"point": "100,200"}) in by_point


def test_locatorless_tap_rejected_in_plain_words(tmp_path):
    from driver.mobile.maestro_adapter import MaestroFlowError, build_flow_yaml
    with pytest.raises(MaestroFlowError, match="定位器"):
        build_flow_yaml("tap", {}, {})


def test_assert_text_maps_to_visible_text_semantics():
    from driver.mobile.maestro_adapter import build_flow_yaml
    text = build_flow_yaml("assertText", {"text": "欢迎"}, {})
    assert "assertVisible" in text
    assert json.dumps({"text": "欢迎"}, ensure_ascii=False) in text


def test_input_and_clear_use_native_commands():
    from driver.mobile.maestro_adapter import build_flow_yaml
    assert json.dumps("abc", ensure_ascii=False) in build_flow_yaml(
        "input", {"text": "abc"}, {})
    assert "eraseText" in build_flow_yaml("clearInput", {}, {})



def _junit(text):
    from driver.mobile.maestro_adapter import parse_maestro_junit
    return parse_maestro_junit(text, exit_code=1)


def test_junit_pass_parses_pass():
    from driver.mobile.maestro_adapter import parse_maestro_junit
    xml = ('<testsuite tests="1" failures="0" errors="0" skipped="0">'
           '<testcase name="f"/></testsuite>')
    res = parse_maestro_junit(xml, exit_code=0)
    assert res["result"] == "pass"


def test_junit_failure_maps_script_fail_with_message():
    xml = ('<testsuite tests="1" failures="1" errors="0" skipped="0">'
           '<testcase name="f"><failure message="元素不可见：登录按钮">'
           'trace</failure></testcase></testsuite>')
    res = _junit(xml)
    assert res["result"] == "fail"
    assert "登录按钮" in res["detail"]
    assert res["errorType"] == 1


def test_junit_error_maps_fail_not_pass():
    xml = ('<testsuite tests="1" failures="0" errors="1" skipped="0">'
           '<testcase name="f"><error message="adb connection closed">'
           'trace</error></testcase></testsuite>')
    res = _junit(xml)
    assert res["result"] == "fail"
    assert res["errorType"] == 2


def test_junit_missing_or_dirty_is_env_fail():
    from driver.mobile.maestro_adapter import parse_maestro_junit
    res = parse_maestro_junit(None, exit_code=0)
    assert res["result"] == "fail"
    assert res["errorType"] == 2
    res2 = parse_maestro_junit("not-xml", exit_code=1)
    assert res2["result"] == "fail"



def test_prepare_missing_binary_raises_human_install_hint(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path / "empty"))
    a = _adapter(tmp_path, monkeypatch)
    with pytest.raises(RuntimeError, match="自行安装"):
        a.prepare({"runId": 1, "leaseRef": {"deviceId": 7, "serial": "s1"}})


def test_prepare_and_execute_pass_via_real_subprocess(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("SHIM_MODE", "pass")
    a = _adapter(tmp_path, monkeypatch)
    a.prepare({"runId": 5, "leaseRef": {"deviceId": 7, "serial": "emu-1"}})
    res = a.execute({"action": "app.launch", "verb": "launch", "pkg": "com.demo"})
    assert res["result"] == "pass", res
    assert a.teardown() is None


def test_execute_failure_returns_structured_detail(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("SHIM_MODE", "fail")
    monkeypatch.setenv("SHIM_FAILMSG", "断言失败：页面未出现元素 '欢迎'")
    a = _adapter(tmp_path, monkeypatch)
    a.prepare({"runId": 5, "leaseRef": {"deviceId": 7, "serial": "emu-1"}})
    res = a.execute({"action": "app.assertText", "verb": "assertText", "text": "欢迎",
                     "stepNo": 2})
    assert res["result"] == "fail"
    assert "欢迎" in res["detail"]
    assert res["errorType"] == 1


def test_command_line_carries_no_analytics_flag(tmp_path, monkeypatch):
    pass
    bin_dir = _install_shim(tmp_path, monkeypatch)
    probe = bin_dir / "probe.txt"
    shim = bin_dir / "maestro"
    shim.write_text(
        SHIM_SRC.replace("__PYTHON__", sys.executable)
        .replace("args = sys.argv[1:]",
                 f"args = sys.argv[1:]\n"
                 f"open(r'{probe}', 'a').write(json.dumps(args) + chr(10))"),
        encoding="utf-8")
    os.chmod(shim, 0o755)
    monkeypatch.setenv("SHIM_MODE", "pass")
    a = _adapter(tmp_path, monkeypatch)
    a.prepare({"runId": 5, "leaseRef": {"deviceId": 7, "serial": "emu-1"}})
    a.execute({"action": "app.back", "verb": "back"})
    argv_lines = probe.read_text(encoding="utf-8").splitlines()
    assert any("--no-analytics" in ln for ln in argv_lines), argv_lines
    assert any("--format" in ln and "junit" in ln for ln in argv_lines)


def test_budget_timeout_kills_engine_and_reports(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("SHIM_HANG", "1")
    a = _adapter(tmp_path, monkeypatch)
    a.timeout_sec = 1.0
    a.prepare({"runId": 5, "leaseRef": {"deviceId": 7, "serial": "emu-1"}})
    res = a.execute({"action": "app.back", "verb": "back"})
    assert res["result"] == "fail"
    assert "预算" in res["detail"] or "超时" in res["detail"]


def test_unmapped_baseline_verb_fails_closed_no_silent_pass(tmp_path, monkeypatch):
    pass
    _install_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("SHIM_MODE", "pass")
    a = _adapter(tmp_path, monkeypatch)
    a.prepare({"runId": 5, "leaseRef": {"deviceId": 7, "serial": "emu-1"}})
    res = a.execute({"action": "app.extractText", "verb": "extractText"})
    assert res["result"] == "fail"
    assert "缺口" in res["detail"]


def test_out_of_baseline_verb_rejected(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch)
    a = _adapter(tmp_path, monkeypatch)
    a.prepare({"runId": 5, "leaseRef": {"deviceId": 7, "serial": "emu-1"}})
    res = a.execute({"action": "app.mockWxApi", "verb": "mockWxApi"})
    assert res["result"] == "fail"
    assert "IR 基线" in res["detail"]


def test_bare_step_without_prepare_refused(tmp_path, monkeypatch):
    a = _adapter(tmp_path, monkeypatch)
    res = a.execute({"action": "app.tap", "verb": "tap", "testId": "a"})
    assert res["result"] == "fail"
    assert res["errorType"] == 2



def test_collect_returns_screenshot_and_log_tail(tmp_path, monkeypatch):
    from PIL import Image
    src = tmp_path / "device.png"
    Image.new("RGB", (600, 900), (10, 20, 30)).save(src)
    _install_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("SHIM_MODE", "pass")
    monkeypatch.setenv("SHIM_SHOT_SRC", str(src))
    a = _adapter(tmp_path, monkeypatch)
    a.prepare({"runId": 5, "leaseRef": {"deviceId": 7, "serial": "emu-1"}})
    res = a.execute({"action": "app.screenshot", "verb": "screenshot", "stepNo": 3})
    assert res["result"] == "pass", res
    arts = a.collect({"stepNo": 3, "artifactDir": str(tmp_path / "arts")})
    kinds = {x["kind"] for x in arts}
    assert "screenshot" in kinds
    assert "view_tree" not in kinds
    assert all(x["localPath"] for x in arts)


def test_collect_without_shot_of_that_step_no_cross_step_impersonation(tmp_path, monkeypatch):
    pass
    _install_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("SHIM_MODE", "pass")
    a = _adapter(tmp_path, monkeypatch)
    a.prepare({"runId": 5, "leaseRef": {"deviceId": 7, "serial": "emu-1"}})
    a.execute({"action": "app.back", "verb": "back", "stepNo": 2})
    arts = a.collect({"stepNo": 2})
    kinds = {x["kind"] for x in (arts or [])}
    assert "screenshot" not in kinds


def test_watch_frame_from_collected_shot(tmp_path, monkeypatch):
    from PIL import Image
    src = tmp_path / "device.png"
    Image.new("RGB", (600, 900), (10, 20, 30)).save(src)
    _install_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("SHIM_MODE", "pass")
    monkeypatch.setenv("SHIM_SHOT_SRC", str(src))
    a = _adapter(tmp_path, monkeypatch)
    a.prepare({"runId": 5, "leaseRef": {"deviceId": 7, "serial": "emu-1"}})
    a.execute({"action": "app.screenshot", "verb": "screenshot", "stepNo": 1})
    frame = a.watch_frame(str(tmp_path / "arts"))
    assert isinstance(frame, dict) and frame.get("image"), "已有截图面必须出帧"
    import base64
    raw = base64.b64decode(frame["image"])
    assert raw[:2] == b"\xff\xd8"


def test_watch_frame_honest_none_without_shot(tmp_path, monkeypatch):
    _install_shim(tmp_path, monkeypatch)
    monkeypatch.setenv("SHIM_MODE", "pass")
    a = _adapter(tmp_path, monkeypatch)
    a.prepare({"runId": 5, "leaseRef": {"deviceId": 7, "serial": "emu-1"}})
    assert a.watch_frame(str(tmp_path / "arts")) is None
