
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile

import pytest

from actions_common import IR_VERBS
from runner import _worker_for

_EXECUTOR_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))



def test_task_type_6_routes_to_app_worker():
    pass
    assert _worker_for(6).endswith(os.path.join("app_worker.py"))
    assert _worker_for(1).endswith(os.path.join("task_worker.py"))



def _descriptor(code: str = "FAKE-L2", runtime_type: str = "PYTHON",
                status: int = 1, **extra) -> dict:
    d = {
        "code": code,
        "runtimeType": runtime_type,
        "status": status,
        "platformCodes": ["ANDROID"],
        "deviceKindCodes": ["EMULATOR"],
        "capabilities": {v: True for v in IR_VERBS},
    }
    d.update(extra)
    return d


def test_fake_stub_declares_contract_three_dims():
    pass
    from driver.mobile.engine_factory import mobile_engine_factory
    from driver.mobile import stub_adapter

    adapter = mobile_engine_factory(_descriptor())
    assert adapter.SUPPORTED_PLATFORMS == ("ANDROID",)
    assert adapter.SUPPORTED_DEVICE_KINDS == ("EMULATOR",)
    assert tuple(adapter.CAPABILITIES) == tuple(IR_VERBS), "动词位图键名必须单源 IR_VERBS"
    assert isinstance(adapter, stub_adapter.StubEngineAdapter)


def test_factory_rejects_unknown_code_with_plain_words():
    from driver.mobile.engine_factory import mobile_engine_factory
    with pytest.raises(ValueError) as exc:
        mobile_engine_factory(_descriptor(code="NO-SUCH-ENGINE"))
    msg = str(exc.value)
    assert "NO-SUCH-ENGINE" in msg and "不在引擎装载面" in msg


def test_factory_rejects_retired_engine():
    pass
    from driver.mobile.engine_factory import mobile_engine_factory
    with pytest.raises(ValueError) as exc:
        mobile_engine_factory(_descriptor(code="HYPIUM", status=0))
    assert "已停用" in str(exc.value)


def test_factory_rejects_missing_descriptor():
    from driver.mobile.engine_factory import mobile_engine_factory
    with pytest.raises(ValueError) as exc:
        mobile_engine_factory(None)
    assert "引擎描述符缺失" in str(exc.value)


def test_factory_runtime_fallback_loads_by_module_path():
    pass
    from driver.mobile.engine_factory import mobile_engine_factory
    with tempfile.TemporaryDirectory() as td:
        pkg = os.path.join(td, "ltmp_engine_pkg")
        os.makedirs(pkg)
        open(os.path.join(pkg, "__init__.py"), "w").close()
        with open(os.path.join(pkg, "imp.py"), "w", encoding="utf-8") as f:
            f.write("class TempStub:\n"
                    "    SUPPORTED_PLATFORMS = ('ANDROID',)\n"
                    "    SUPPORTED_DEVICE_KINDS = ('EMULATOR',)\n"
                    "    def __init__(self, descriptor):\n"
                    "        self.descriptor = descriptor\n")
        sys.path.insert(0, td)
        try:
            desc = _descriptor(code="TEMP-L2",
                               loaderModule="ltmp_engine_pkg.imp",
                               loaderClass="TempStub")
            obj = mobile_engine_factory(desc)
            assert type(obj).__name__ == "TempStub"
        finally:
            sys.path.remove(td)


def test_factory_no_scattered_engine_branches():
    pass
    mobile_dir = os.path.join(_EXECUTOR_DIR, "driver", "mobile")
    engine_codes = ("MOBILEWRIGHT", "MAESTRO", "MINIUM", "AUTOMATOR",
                    "APPIUM", "XCUITEST", "HMNEXTAUTO")
    for name in os.listdir(mobile_dir):
        if not name.endswith(".py") or name == "engine_factory.py":
            continue
        text = open(os.path.join(mobile_dir, name), encoding="utf-8").read()
        assert not any(f'== "{c}"' in text or f'== "{c.lower()}"' in text
                       for c in engine_codes), f"{name} 存在散落引擎判型分支"



def _write_task(td: str, task: dict) -> tuple[str, str]:
    task_f = os.path.join(td, "task.json")
    out_f = os.path.join(td, "outcome.json")
    with open(task_f, "w", encoding="utf-8") as f:
        json.dump(task, f, ensure_ascii=False)
    return task_f, out_f


def _run_worker(task: dict) -> dict:
    from app_worker import run_app_task
    return run_app_task(task).to_dict()


def _envelope(steps: list[dict], kind: str = "app-auto-run",
              engine: dict | None = None) -> str:
    env = {"kind": kind, "engine": engine or _descriptor(), "steps": steps,
           "runId": 9001, "leaseRef": {"deviceId": 1, "serial": "lease-l2-1"}}
    return json.dumps(env, ensure_ascii=False)


def test_worker_happy_path_emits_pass_and_teardown_and_lease_hook(monkeypatch):
    import app_worker

    calls: list[str] = []

    class Spy:
        def prepare(self, ctx):
            calls.append("prepare")

        def execute(self, step):
            calls.append(f"execute:{step['action']}")
            return {"result": "pass"}

        def collect(self, artifact):
            return None

        def teardown(self):
            calls.append("teardown")

    lease_done: list[dict] = []
    monkeypatch.setattr(app_worker, "_return_lease",
                        lambda task, ref, reason: lease_done.append(ref))
    outcome = app_worker.run_app_task({
        "taskId": 1,
        "actionJson": _envelope([{"action": "app.tap", "selector": "登录按钮"}]),
        "_adapterOverride": Spy(),
    })
    assert outcome.result == "pass"
    assert calls == ["prepare", "execute:app.tap", "teardown"], "四段契约顺序"
    assert lease_done == [{"deviceId": 1, "serial": "lease-l2-1"}], \
        "teardown 后租约归还钩子必触达（leaseRef=dict 契约统一）"


def test_worker_prepare_failure_still_runs_teardown(monkeypatch):
    pass
    import app_worker

    calls: list[str] = []

    class BadPrepare:
        def prepare(self, ctx):
            raise RuntimeError("设备离线")

        def execute(self, step):
            raise AssertionError("prepare 失败后禁执行")

        def collect(self, artifact):
            return None

        def teardown(self):
            calls.append("teardown")

    lease_done: list[dict] = []
    monkeypatch.setattr(app_worker, "_return_lease",
                        lambda task, ref, reason: lease_done.append(ref))
    outcome = app_worker.run_app_task({
        "taskId": 2,
        "actionJson": _envelope([{"action": "app.tap"}]),
        "_adapterOverride": BadPrepare(),
    })
    assert outcome.result == "fail" and outcome.error_type == 3
    assert "设备离线" in outcome.error_detail
    assert calls == ["teardown"] and lease_done == [
        {"deviceId": 1, "serial": "lease-l2-1"}]


def test_worker_rejects_wrong_envelope_kind():
    out = _run_worker({"taskId": 3, "actionJson": _envelope(
        [{"action": "app.tap"}], kind="perf-run")})
    assert out["result"] == "fail" and out["error_type"] == 3
    assert "信封 kind 非法" in out["error_detail"]


def test_worker_rejects_non_baseline_verb():
    pass
    out = _run_worker({"taskId": 4, "actionJson": _envelope([{"action": "app.fly"}])})
    assert out["result"] == "fail"
    assert "app.fly" in out["error_detail"] and "基线" in out["error_detail"]
    assert out["steps_timeline"] == [], "首步越界=校验先于执行，零步落账"


def test_worker_step_failure_maps_structured_attribution(monkeypatch):
    pass
    import app_worker

    executed: list[str] = []

    class FailOnTap:
        def prepare(self, ctx):
            pass

        def execute(self, step):
            executed.append(step["action"])
            if step["action"] == "app.tap":
                return {"result": "fail", "errorType": 2,
                        "detail": "控件未找到：登录按钮"}
            return {"result": "pass"}

        def collect(self, artifact):
            return None

        def teardown(self):
            pass

    monkeypatch.setattr(app_worker, "_return_lease",
                        lambda task, ref, reason: None)
    outcome = app_worker.run_app_task({
        "taskId": 5,
        "actionJson": _envelope([{"action": "app.tap"}, {"action": "app.screenshot"}]),
        "_adapterOverride": FailOnTap(),
    })
    assert outcome.result == "fail" and outcome.error_type == 2
    assert "控件未找到" in outcome.error_detail
    assert executed == ["app.tap"], "失败即停，第二动词不得执行"
    assert [r["stepNo"] for r in outcome.steps_timeline] == [1]


def test_worker_cli_writes_outcome_file():
    pass
    task = {"taskId": 6, "actionJson": _envelope(
        [{"action": "app.launch", "arguments": {"package": "com.demo"}}])}
    with tempfile.TemporaryDirectory() as td:
        task_f, out_f = _write_task(td, task)
        rc = subprocess.run([sys.executable,
                             os.path.join(_EXECUTOR_DIR, "app_worker.py"),
                             task_f, out_f],
                            capture_output=True, text=True, timeout=60)
        assert rc.returncode == 0, rc.stderr
        with open(out_f, encoding="utf-8") as f:
            out = json.load(f)
        assert out["result"] == "pass", out["error_detail"]



def test_bridge_frame_codec_roundtrip():
    from driver.mobile.bridge_process import encode_request, parse_frame
    raw = encode_request(7, "ping", {"x": 1})
    frame = parse_frame(raw)
    assert frame["jsonrpc"] == "2.0" and frame["id"] == 7
    assert frame["method"] == "ping" and frame["params"] == {"x": 1}


_ECHO_NODE = """
import readline from 'node:readline';
const rl = readline.createInterface({ input: process.stdin });
let n = 0;
rl.on('line', (line) => {
  const f = JSON.parse(line);
  if (f.method === 'boom') { process.exit(3); }
  if (f.method === 'hang') { return; }
  process.stdout.write(JSON.stringify(
    { jsonrpc: '2.0', id: f.id, result: { echo: f.params, seq: n++ } }) + '\\n');
});
"""


def _start_bridge(timeout_sec: float = 15.0):
    from driver.mobile.bridge_process import NodeBridge
    with tempfile.NamedTemporaryFile("w", suffix=".mjs", delete=False,
                                     encoding="utf-8") as f:
        f.write(_ECHO_NODE)
        script = f.name
    bridge = NodeBridge(script, timeout_sec=timeout_sec)
    bridge.start()
    return bridge, script


def test_bridge_real_node_request_response_sequence():
    bridge, script = _start_bridge()
    try:
        r1 = bridge.call("tap", {"x": 1})
        r2 = bridge.call("tap", {"x": 2})
        assert r1["echo"] == {"x": 1} and r2["echo"] == {"x": 2}
        assert r1["seq"] == 0 and r2["seq"] == 1, "帧配对按请求序，禁串包"
    finally:
        bridge.stop()
        os.unlink(script)


def test_bridge_timeout_plain_error():
    bridge, script = _start_bridge(timeout_sec=1.5)
    try:
        with pytest.raises(TimeoutError) as exc:
            bridge.call("hang", {})
        assert "响应超时" in str(exc.value) and "hang" in str(exc.value)
    finally:
        bridge.stop()
        os.unlink(script)


def test_bridge_crash_forensics_exit_code_and_tail():
    pass
    with tempfile.NamedTemporaryFile("w", suffix=".mjs", delete=False,
                                     encoding="utf-8") as f:
        f.write("console.error('bridge fatal: sdk not found');"
                "process.exit(3);\n")
        script = f.name
    from driver.mobile.bridge_process import NodeBridge, BridgeCrashError
    bridge = NodeBridge(script, timeout_sec=5.0)
    bridge.start()
    try:
        with pytest.raises(BridgeCrashError) as exc:
            bridge.call("ping", {})
        err = exc.value
        assert err.exit_code == 3
        assert "sdk not found" in err.stderr_tail
    finally:
        bridge.stop()
        os.unlink(script)
