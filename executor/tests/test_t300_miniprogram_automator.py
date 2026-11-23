
from __future__ import annotations

import pathlib
import subprocess
import sys

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from actions_common import IR_VERBS
from driver.mobile.contracts import capability_violations
from driver.mobile.engine_factory import mobile_engine_factory

V145_AUTOMATOR_DESCRIPTOR = {
    "code": "MINIPROGRAM_AUTOMATOR",
    "status": 1,
    "runtimeType": "NODE_BRIDGE",
    "platformCodes": ["MP"],
    "deviceKindCodes": ["DEVTOOLS_IDE"],
}

ENV_PROFILE = {"appid": "wxdemo0001", "projectPath": "/tmp/wx-demo",
               "cliPath": "/opt/wxcli", "autoPort": 9420}

BRIDGE_SCRIPT = EXECUTOR_ROOT / "driver" / "mobile" / "automator_bridge.mjs"
FAKE_ENGINE = HERE / "fixtures" / "automator_fake_engine.mjs"


def _recording_bridge():
    pass

    class Recorder:
        def __init__(self):
            self.started = False
            self.stopped = False
            self.calls: list[tuple[str, dict]] = []
            self.extra_env = {}
            self.collect_frame = {"kind": "screenshot", "localPath": "/tmp/a.png"}

        def start(self):
            self.started = True

        def alive(self):
            return self.started

        def call(self, method, params=None):
            self.calls.append((method, params or {}))
            if method == "execute":
                return {"result": "pass"}
            if method == "collect":
                return self.collect_frame
            return {"ok": True}

        def stop(self):
            self.stopped = True

    return Recorder()


def _ctx(**over):
    return dict({"envProfile": dict(ENV_PROFILE),
                 "leaseRef": {"deviceId": 7, "serial": "devtools-1"},
                 "runId": "r1"}, **over)



def test_factory_loads_real_automator_adapter():
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    adapter = mobile_engine_factory(dict(V145_AUTOMATOR_DESCRIPTOR))
    assert isinstance(adapter, AutomatorAdapter)
    assert type(adapter).__name__ != "StubEngineAdapter"


def test_adapter_self_description_mp_devtools():
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    assert AutomatorAdapter.SUPPORTED_PLATFORMS == ("MP",)
    assert set(AutomatorAdapter.SUPPORTED_DEVICE_KINDS) == {"DEVTOOLS_IDE"}
    assert tuple(AutomatorAdapter.CAPABILITIES) == tuple(IR_VERBS)


def test_descriptor_capability_reconciliation_clean():
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    adapter = AutomatorAdapter(descriptor=dict(V145_AUTOMATOR_DESCRIPTOR))
    assert capability_violations(adapter, V145_AUTOMATOR_DESCRIPTOR) is None


def test_bridge_reuses_shared_node_bridge_component():
    pass
    import inspect

    from driver.mobile import miniprogram_automator_adapter as mod
    src = inspect.getsource(mod)
    assert "from driver.mobile.bridge_process import NodeBridge" in src
    for banned in ("subprocess.Popen", "threading.Thread"):
        assert banned not in src, f"适配器禁自带 {banned}（桥进程管理单点=bridge_process）"



def test_prepare_rejects_missing_env_profile_in_plain_words():
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    adapter = AutomatorAdapter(descriptor=dict(V145_AUTOMATOR_DESCRIPTOR))
    adapter._bridge = _recording_bridge()
    with pytest.raises(ValueError) as exc:
        adapter.prepare({"leaseRef": {"deviceId": 7, "serial": "d1"}})
    text = str(exc.value)
    assert "环境档案" in text and "档案库" in text, f"缺档案须人话指路：{text}"


def test_prepare_rejects_profile_missing_project_path():
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    adapter = AutomatorAdapter(descriptor=dict(V145_AUTOMATOR_DESCRIPTOR))
    adapter._bridge = _recording_bridge()
    with pytest.raises(ValueError) as exc:
        adapter.prepare(_ctx(envProfile={"appid": "wx1"}))
    assert "projectPath" in str(exc.value)


def test_prepare_rejects_missing_lease_with_zero_new_lock():
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    adapter = AutomatorAdapter(descriptor=dict(V145_AUTOMATOR_DESCRIPTOR))
    adapter._bridge = _recording_bridge()
    with pytest.raises(ValueError) as exc:
        adapter.prepare(_ctx(leaseRef={}))
    assert "租约" in str(exc.value)


def test_prepare_passes_profile_fields_into_bridge():
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    adapter = AutomatorAdapter(descriptor=dict(V145_AUTOMATOR_DESCRIPTOR))
    bridge = _recording_bridge()
    adapter._bridge = bridge
    adapter.prepare(_ctx())
    method, params = bridge.calls[0]
    assert method == "prepare"
    assert params.get("projectPath") == "/tmp/wx-demo"
    assert params.get("cliPath") == "/opt/wxcli"
    assert params.get("autoPort") == 9420
    assert params.get("leaseRef") == {"deviceId": 7, "serial": "devtools-1"}


def test_prepare_defaults_auto_port_when_profile_omits_it():
    pass
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    adapter = AutomatorAdapter(descriptor=dict(V145_AUTOMATOR_DESCRIPTOR))
    bridge = _recording_bridge()
    adapter._bridge = bridge
    profile = dict(ENV_PROFILE)
    profile.pop("autoPort")
    adapter.prepare(_ctx(envProfile=profile))
    assert bridge.calls[0][1].get("autoPort") == 9420



def _prepared_adapter():
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    adapter = AutomatorAdapter(descriptor=dict(V145_AUTOMATOR_DESCRIPTOR))
    bridge = _recording_bridge()
    adapter._bridge = bridge
    adapter.prepare(_ctx())
    return adapter, bridge


def test_all_sixteen_verbs_dispatch_to_bridge():
    adapter, bridge = _prepared_adapter()
    for verb in IR_VERBS:
        res = adapter.execute({"action": f"app.{verb}", "verb": verb})
        assert res.get("result") == "pass", f"{verb} 分发失败: {res}"
    methods = [m for m, _ in bridge.calls]
    assert methods.count("execute") == len(IR_VERBS)
    sent_verbs = [p.get("verb") for m, p in bridge.calls if m == "execute"]
    assert sent_verbs == list(IR_VERBS), "桥侧动词名须按 IR 基线逐枚触达"


def test_out_of_baseline_verb_rejected_in_plain_words():
    adapter, _ = _prepared_adapter()
    res = adapter.execute({"action": "app.selfDestruct", "verb": "selfDestruct"})
    assert res.get("result") == "fail"
    detail = res.get("detail") or ""
    assert "selfDestruct" in detail and "不在" in detail, "越界动词须人话点名动词名"


def test_bare_step_without_prepare_rejected():
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    adapter = AutomatorAdapter(descriptor=dict(V145_AUTOMATOR_DESCRIPTOR))
    adapter._bridge = _recording_bridge()
    res = adapter.execute({"action": "app.tap", "verb": "tap", "selector": ".btn"})
    assert res.get("result") == "fail"
    assert "prepare" in (res.get("detail") or "") or "未准备" in (res.get("detail") or "")


def test_bridge_error_becomes_structured_fail():
    class BoomBridge:
        def start(self):
            pass

        def alive(self):
            return True

        def call(self, method, params=None):
            if method == "prepare":
                return {"ok": True}
            raise RuntimeError("桥方法 execute 返回错误：开发者工具未登录")

        def stop(self):
            pass

    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    adapter = AutomatorAdapter(descriptor=dict(V145_AUTOMATOR_DESCRIPTOR))
    adapter._bridge = BoomBridge()
    adapter.prepare(_ctx())
    res = adapter.execute({"action": "app.tap", "verb": "tap", "selector": ".btn"})
    assert res.get("result") == "fail"
    assert "开发者工具未登录" in (res.get("detail") or "")



def test_double_tap_interval_shared_single_source():
    pass
    import inspect

    from driver.mobile import contracts as mp_contracts
    from driver.mobile import miniprogram_automator_adapter, minium_adapter

    helper = getattr(mp_contracts, "mp_double_tap_interval_ms", None)
    assert helper is not None, "doubleTap 间隔键须抽入 contracts 公共件（单源）"
    for mod in (minium_adapter, miniprogram_automator_adapter):
        assert "mp_double_tap_interval_ms" in inspect.getsource(mod), \
            f"{mod.__name__} 须消费公共件而非各抄一份"


def test_double_tap_interval_env_override(monkeypatch):
    from driver.mobile.contracts import mp_double_tap_interval_ms
    monkeypatch.delenv("APPAUTO_MP_DOUBLE_TAP_INTERVAL_MS", raising=False)
    assert mp_double_tap_interval_ms() == 200
    monkeypatch.setenv("APPAUTO_MP_DOUBLE_TAP_INTERVAL_MS", "50")
    assert mp_double_tap_interval_ms() == 50
    monkeypatch.setenv("APPAUTO_MP_DOUBLE_TAP_INTERVAL_MS", "非法值")
    assert mp_double_tap_interval_ms() == 200, "非法值回退缺省（禁炸步进）"


def test_double_tap_step_carries_interval_to_bridge():
    adapter, bridge = _prepared_adapter()
    res = adapter.execute({"action": "app.doubleTap", "verb": "doubleTap",
                           "selector": ".btn"})
    assert res.get("result") == "pass"
    sent = [p for m, p in bridge.calls if m == "execute"][-1]
    assert sent.get("intervalMs") == 200, "doubleTap 间隔经信封参数下传（键投影单点=公共件）"


# ---------- collect / teardown ----------

def test_collect_passes_through_artifact():
    adapter, _ = _prepared_adapter()
    art = adapter.collect({"stepNo": 3, "artifactDir": "/tmp/t300-art"})
    assert art == {"kind": "screenshot", "localPath": "/tmp/a.png"}


def test_collect_artifacts_list_form_parsed():
    pass
    adapter, bridge = _prepared_adapter()
    bridge.collect_frame = {"artifacts": [
        {"kind": "screenshot", "localPath": "/tmp/a.png"},
        {"kind": "dom_snapshot", "localPath": "/tmp/a.json",
         "contentType": "application/json"}]}
    art = adapter.collect({"stepNo": 3, "artifactDir": "/tmp/t300-art"})
    assert isinstance(art, list) and {a["kind"] for a in art} == {"screenshot",
                                                                  "dom_snapshot"}


def test_teardown_stops_bridge():
    adapter, bridge = _prepared_adapter()
    adapter.teardown()
    assert bridge.stopped is True
    assert ("teardown", {}) in [(m, p) for m, p in bridge.calls]



CASE_STEPS = [
    {"action": "app.launch", "verb": "launch", "page": "/pages/index"},
    {"action": "app.tap", "verb": "tap", "selector": "#login"},
    {"action": "app.longPress", "verb": "longPress", "selector": "#file"},
    {"action": "app.doubleTap", "verb": "doubleTap", "selector": "#zoom"},
    {"action": "app.input", "verb": "input", "selector": "#kw", "text": "测试"},
    {"action": "app.clearInput", "verb": "clearInput", "selector": "#kw"},
    {"action": "app.swipe", "verb": "swipe", "selector": "#list",
     "x1": 10, "y1": 100, "x2": 10, "y2": 20},
    {"action": "app.scroll", "verb": "scroll", "selector": "#list"},
    {"action": "app.back", "verb": "back"},
    {"action": "app.screenshot", "verb": "screenshot", "stepNo": 10},
    {"action": "app.assertVisible", "verb": "assertVisible", "selector": "#home"},
    {"action": "app.assertNotVisible", "verb": "assertNotVisible", "selector": "#ghost"},
    {"action": "app.assertText", "verb": "assertText", "selector": "#title", "text": "欢迎"},
    {"action": "app.waitVisible", "verb": "waitVisible", "selector": "#home", "timeout": 3},
    {"action": "app.extractText", "verb": "extractText", "selector": "#title"},
    {"action": "app.close", "verb": "close"},
]


def _fake_minium_ready(monkeypatch):
    pass
    sys.path.insert(0, str(HERE))
    from driver.mobile import minium_adapter
    from tests.test_t299_minium import FakeSession

    def fake_load():
        def factory(conf):
            session = FakeSession(conf)
            session.missing_selectors.add("#ghost")
            return session
        return type("minium_fake", (), {"Minium": staticmethod(factory)})()

    monkeypatch.setattr(minium_adapter, "_load_minium", fake_load)


def test_same_case_dual_engine_comparison_minium(monkeypatch):
    pass
    _fake_minium_ready(monkeypatch)
    adapter = mobile_engine_factory({
        "code": "MINIUM", "status": 1, "runtimeType": "PY_INPROC",
        "platformCodes": ["MP"],
        "deviceKindCodes": ["DEVTOOLS_IDE", "EMULATOR", "SIMULATOR", "REAL"]})
    adapter.prepare(_ctx())
    for step in CASE_STEPS:
        res = adapter.execute(dict(step))
        assert res.get("result") == "pass", f"Minium {step['verb']} 红: {res}"
    adapter.teardown()


def test_same_case_dual_engine_comparison_automator_real_node_bridge():
    pass
    if not _node_available():
        pytest.skip("node 环境件不可达（如实标注，L4 另刀）")
    adapter = _adapter_on_real_bridge()
    for step in CASE_STEPS:
        res = adapter.execute(dict(step))
        assert res.get("result") == "pass", f"automator {step['verb']} 红: {res}"
    art = adapter.collect({"stepNo": 10, "artifactDir": "/tmp/t300-art"})
    assert isinstance(art, list), f"真桥 collect 应回两件列表: {art!r}"
    kinds = {a["kind"] for a in art}
    assert kinds == {"screenshot", "dom_snapshot"}
    for a in art:
        assert a.get("localPath") and pathlib.Path(a["localPath"]).exists()
    import json
    tree = next(a for a in art if a["kind"] == "dom_snapshot")
    body = json.loads(pathlib.Path(tree["localPath"]).read_text(encoding="utf-8"))
    assert (body.get("data") or {}).get("greeting") == "hello", \
        "视图树文件=currentPage().data() 真值序列化，空壳冒充即红"
    adapter.teardown()



def _node_available() -> bool:
    try:
        subprocess.run(["node", "--version"], capture_output=True, check=True)
        return True
    except Exception:  # noqa: BLE001
        return False


def _adapter_on_real_bridge():
    from driver.mobile.bridge_process import NodeBridge
    from driver.mobile.miniprogram_automator_adapter import AutomatorAdapter
    bridge = NodeBridge(
        script_path=str(BRIDGE_SCRIPT),
        timeout_sec=15.0,
        extra_env={"AUTOMATOR_TEST_ENGINE_MODULE": str(FAKE_ENGINE)})
    bridge.start()
    adapter = AutomatorAdapter(descriptor=dict(V145_AUTOMATOR_DESCRIPTOR))
    adapter._bridge = bridge
    adapter.prepare(_ctx())
    return adapter


@pytest.mark.skipif(not _node_available(), reason="node 环境件不可达（如实标注，L4 另刀）")
def test_real_node_bridge_roundtrip_with_fake_engine_module():
    pass
    adapter = _adapter_on_real_bridge()
    res = adapter.execute({"action": "app.tap", "verb": "tap", "selector": "#login"})
    assert res.get("result") == "pass", res
    res = adapter.execute({"action": "app.assertText", "verb": "assertText",
                           "selector": "#title", "text": "欢迎"})
    assert res.get("result") == "pass", res
    res = adapter.execute({"action": "app.extractText", "verb": "extractText",
                           "selector": "#title"})
    assert res.get("result") == "pass" and res.get("extracted"), res
    res = adapter.execute({"action": "app.bogus", "verb": "bogus"})
    assert res.get("result") == "fail"
    res = adapter.execute({"action": "app.tap", "verb": "tap"})
    assert res.get("result") == "fail"
    assert "定位器" in (res.get("detail") or "") or "selector" in (res.get("detail") or ""), res
    adapter.teardown()
