
from __future__ import annotations

import pathlib
import subprocess
import sys

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from actions_common import IR_VERBS
from driver.mobile.adb_verb_runner import ADB_FAMILY_VERBS
from driver.mobile.contracts import capability_violations
from driver.mobile.engine_factory import mobile_engine_factory
from driver.mobile.mobilewright_adapter import MobilewrightAdapter

V145_DESCRIPTOR = {
    "code": "MOBILEWRIGHT",
    "status": 1,
    "platformCodes": ["ANDROID", "IOS"],
    "deviceKindCodes": ["EMULATOR", "SIMULATOR", "REAL"],
}

FAKE_ENGINE = HERE / "fixtures" / "mw_fake_engine.mjs"
BRIDGE_SCRIPT = EXECUTOR_ROOT / "driver" / "mobile" / "mobilewright_bridge.mjs"


def _recording_bridge():
    pass

    class Recorder:
        def __init__(self):
            self.started = False
            self.stopped = False
            self.calls: list[tuple[str, dict]] = []
            self.extra_env = {}

        def start(self):
            self.started = True

        def alive(self):
            return self.started

        def call(self, method, params=None):
            self.calls.append((method, params or {}))
            if method == "execute":
                return {"result": "pass"}
            if method == "collect":
                return {"kind": "screenshot", "localPath": "/tmp/x.png"}
            return {"ok": True}

        def stop(self):
            self.stopped = True

    return Recorder()



def test_factory_loads_real_mobilewright_adapter():
    adapter = mobile_engine_factory(dict(V145_DESCRIPTOR))
    assert isinstance(adapter, MobilewrightAdapter)
    assert type(adapter).__name__ != "StubEngineAdapter"


def test_adapter_declares_android_emulator_real():
    assert MobilewrightAdapter.SUPPORTED_PLATFORMS == ("ANDROID",)
    assert set(MobilewrightAdapter.SUPPORTED_DEVICE_KINDS) == {"EMULATOR", "REAL"}


def test_descriptor_capability_reconciliation_clean():
    adapter = MobilewrightAdapter(descriptor=dict(V145_DESCRIPTOR))
    assert capability_violations(adapter, V145_DESCRIPTOR) is None


def test_capabilities_follow_ir_baseline_single_source():
    assert tuple(MobilewrightAdapter.CAPABILITIES) == tuple(IR_VERBS)



class _RecordingAdbForT297:
    pass

    def __init__(self):
        self.runs: list[tuple] = []

    def run(self, verb, step, serial=None):
        self.runs.append((verb, serial))
        return {"result": "pass"}


def test_all_sixteen_verbs_dispatch_to_bridge():
    adapter = MobilewrightAdapter(descriptor=dict(V145_DESCRIPTOR))
    bridge = _recording_bridge()
    adapter._bridge = bridge
    fake_adb = _RecordingAdbForT297()
    adapter._adb = fake_adb
    adapter.prepare({"runId": "r1", "leaseRef": {"serial": "em-1"}})
    bridge_verbs = [v for v in IR_VERBS if v not in ADB_FAMILY_VERBS]
    for verb in IR_VERBS:
        res = adapter.execute({"action": f"app.{verb}", "verb": verb})
        assert res.get("result") == "pass", f"{verb} 分发失败: {res}"
    methods = [m for m, _ in bridge.calls]
    assert methods.count("execute") == len(bridge_verbs)
    sent_verbs = [p.get("verb") for m, p in bridge.calls if m == "execute"]
    assert sent_verbs == list(bridge_verbs), "桥侧动词名须按桥映射面逐枚触达"
    assert [v for v, _ in fake_adb.runs] == list(ADB_FAMILY_VERBS), \
        "adb 族动词须逐枚落 Python adb 面且不触桥"


def test_out_of_baseline_verb_rejected_in_plain_words():
    adapter = MobilewrightAdapter(descriptor=dict(V145_DESCRIPTOR))
    adapter._bridge = _recording_bridge()
    adapter.prepare({})
    res = adapter.execute({"action": "app.selfDestruct", "verb": "selfDestruct"})
    assert res.get("result") == "fail"
    detail = res.get("detail") or ""
    assert "selfDestruct" in detail and "不在" in detail, "越界动词须人话点名动词名"


def test_bridge_error_frame_becomes_structured_fail():
    class BoomBridge:
        def start(self):
            pass

        def alive(self):
            return True

        def call(self, method, params=None):
            if method == "prepare":
                return {"ok": True}
            raise RuntimeError("桥方法 execute 返回错误：设备掉线")

        def stop(self):
            pass

    adapter = MobilewrightAdapter(descriptor=dict(V145_DESCRIPTOR))
    adapter._bridge = BoomBridge()
    adapter.prepare({})
    res = adapter.execute({"action": "app.tap", "verb": "tap", "testId": "登录"})
    assert res.get("result") == "fail"
    assert "设备掉线" in (res.get("detail") or "")


def test_prepare_injects_telemetry_disabled():
    pass
    adapter = MobilewrightAdapter(descriptor=dict(V145_DESCRIPTOR))
    bridge = _recording_bridge()
    adapter._bridge = bridge
    adapter.prepare({"runId": "r1"})
    assert bridge.extra_env.get("MOBILEWRIGHT_DISABLE_TELEMETRY") == "1"


def test_collect_passes_through_artifact():
    adapter = MobilewrightAdapter(descriptor=dict(V145_DESCRIPTOR))
    adapter._bridge = _recording_bridge()
    adapter.prepare({})
    art = adapter.collect({"stepNo": 3, "step": {"action": "app.screenshot"}})
    assert art == {"kind": "screenshot", "localPath": "/tmp/x.png"}


def test_teardown_stops_bridge():
    adapter = MobilewrightAdapter(descriptor=dict(V145_DESCRIPTOR))
    bridge = _recording_bridge()
    adapter._bridge = bridge
    adapter.prepare({})
    adapter.teardown()
    assert bridge.stopped is True



def _node_available() -> bool:
    try:
        subprocess.run(["node", "--version"], capture_output=True, check=True)
        return True
    except Exception:  # noqa: BLE001
        return False


@pytest.mark.skipif(not _node_available(), reason="node 环境件不可达（如实标注，L4 另刀）")
def test_real_node_bridge_roundtrip_with_fake_engine_module():
    pass
    adapter = MobilewrightAdapter(descriptor=dict(V145_DESCRIPTOR))
    adapter._bridge = _real_bridge_for_fake_engine()
    adapter.prepare({"runId": "r1", "leaseRef": {"serial": "fake-1"}})
    res = adapter.execute({"action": "app.launch", "verb": "launch", "pkg": "com demo"})
    assert res.get("result") == "pass", res
    res = adapter.execute({"action": "app.tap", "verb": "tap", "testId": "登录"})
    assert res.get("result") == "pass", res
    res = adapter.execute({"action": "app.assertText", "verb": "assertText",
                           "testId": "标题", "text": "欢迎"})
    assert res.get("result") == "pass", res
    res = adapter.execute({"action": "app.bogus", "verb": "bogus"})
    assert res.get("result") == "fail"
    res = adapter.execute({"action": "app.tap", "verb": "tap"})
    assert res.get("result") == "fail"
    assert "定位器" in (res.get("detail") or ""), res
    arts = adapter.collect({"stepNo": 1, "artifactDir": "/tmp/t297-art"})
    assert isinstance(arts, list) and arts
    for a in arts:
        assert a.get("localPath") and pathlib.Path(a["localPath"]).exists()
    assert {a["kind"] for a in arts} == {"screenshot", "dom_snapshot"}
    adapter.teardown()


def _real_bridge_for_fake_engine():
    from driver.mobile.bridge_process import NodeBridge
    bridge = NodeBridge(
        script_path=str(BRIDGE_SCRIPT),
        timeout_sec=15.0,
        extra_env={"MOBILEWRIGHT_DISABLE_TELEMETRY": "1",
                   "MW_TEST_ENGINE_MODULE": str(FAKE_ENGINE)})
    bridge.start()
    return bridge
