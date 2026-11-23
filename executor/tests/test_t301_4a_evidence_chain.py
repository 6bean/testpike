
from __future__ import annotations

import json
import pathlib
import subprocess

import pytest

_HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = _HERE.parent
FAKE_ENGINE = _HERE / "fixtures" / "mw_fake_engine.mjs"
BRIDGE_SCRIPT = EXECUTOR_ROOT / "driver" / "mobile" / "mobilewright_bridge.mjs"


def _envelope(steps: list) -> str:
    return json.dumps({"kind": "app-auto-run", "runId": 7,
                       "leaseRef": {"deviceId": 1, "serial": "lease-l2-1"},
                       "engine": {"code": "FAKE-L2"},
                       "steps": steps}, ensure_ascii=False)



def test_worker_collect_routes_artifacts_to_pending(tmp_path, monkeypatch):
    pass
    import app_worker

    shot = tmp_path / "step-1.png"
    shot.write_bytes(b"fakepng")

    class Art:
        def prepare(self, ctx):
            pass

        def execute(self, step):
            return {"result": "pass"}

        def collect(self, artifact):
            return {"kind": "screenshot", "localPath": str(shot)}

        def teardown(self):
            pass

    monkeypatch.setattr(app_worker, "_return_lease", lambda *a: None)
    outcome = app_worker.run_app_task({
        "taskId": 11,
        "actionJson": _envelope([{"action": "app.tap", "testId": "登录"}]),
        "_adapterOverride": Art(),
    })
    assert outcome.result == "pass"
    assert len(outcome.pending_artifacts) == 1, \
        "collect 产物必须进 pending_artifacts（runner 唯一上传消费通道）"
    item = outcome.pending_artifacts[0]
    assert item["stepNo"] == 1 and item["kind"] == "screenshot"
    assert item["localPath"] == str(shot)
    assert item.get("fileName"), "fileName 缺=grant 落盘名不可控"
    assert item.get("contentType") == "image/png"
    row = outcome.steps_timeline[0]
    assert "localPath" not in json.dumps(row, ensure_ascii=False), \
        f"时间线行禁残留本机路径（core jsonb 泄漏断链）: {row}"


def test_worker_collects_failed_step_evidence(tmp_path, monkeypatch):
    pass
    import app_worker

    shot = tmp_path / "step-1.png"
    shot.write_bytes(b"fakepng")
    tree = tmp_path / "step-1.json"
    tree.write_text('{"nodes": []}', encoding="utf-8")
    seen: list = []

    class FailOnTap:
        def prepare(self, ctx):
            pass

        def execute(self, step):
            if step["action"] == "app.tap":
                return {"result": "fail", "errorType": 2, "detail": "控件未找到"}
            return {"result": "pass"}

        def collect(self, artifact):
            seen.append(dict(artifact))
            return [{"kind": "screenshot", "localPath": str(shot)},
                    {"kind": "dom_snapshot", "localPath": str(tree),
                     "contentType": "application/json"}]

        def teardown(self):
            pass

    monkeypatch.setattr(app_worker, "_return_lease", lambda *a: None)
    outcome = app_worker.run_app_task({
        "taskId": 12,
        "actionJson": _envelope([{"action": "app.tap"}, {"action": "app.back"}]),
        "_adapterOverride": FailOnTap(),
    })
    assert outcome.result == "fail"
    assert len(seen) == 1 and seen[0]["stepNo"] == 1, \
        "失败步必须触发 collect（三联图=失败瞬间现场，禁失败即跳过采集）"
    kinds = {i["kind"] for i in outcome.pending_artifacts}
    assert kinds == {"screenshot", "dom_snapshot"}
    assert all(i["stepNo"] == 1 for i in outcome.pending_artifacts)
    by_kind = {i["kind"]: i for i in outcome.pending_artifacts}
    assert by_kind["dom_snapshot"].get("contentType") == "application/json"
    assert by_kind["screenshot"].get("slot") == "screenshot", \
        "失败步产物带 evidence 槽位（runner 据此回写 row.evidence）"
    assert by_kind["dom_snapshot"].get("slot") == "view_tree"
    row = outcome.steps_timeline[0]
    assert row["result"] == "fail"
    assert "localPath" not in json.dumps(row, ensure_ascii=False)


def test_worker_single_dict_collect_backward_compatible(tmp_path, monkeypatch):
    pass
    import app_worker

    class NoneArt:
        def prepare(self, ctx):
            pass

        def execute(self, step):
            return {"result": "pass"}

        def collect(self, artifact):
            return None

        def teardown(self):
            pass

    monkeypatch.setattr(app_worker, "_return_lease", lambda *a: None)
    outcome = app_worker.run_app_task({
        "taskId": 13,
        "actionJson": _envelope([{"action": "app.tap"}]),
        "_adapterOverride": NoneArt(),
    })
    assert outcome.result == "pass" and outcome.pending_artifacts == []



class _GrantClient:
    def __init__(self):
        self.grants: list[dict] = []

    def grant_artifact(self, task_id, kind, file_name):
        self.grants.append({"kind": kind, "fileName": file_name})
        return {"objectKey": f"exec/{kind}/{file_name}",
                "uploadUrl": "http://s3obj.local/put"}


def test_upload_writes_evidence_slots_and_passthrough_content_type(monkeypatch,
                                                                    tmp_path):
    pass
    import runner

    shot = tmp_path / "a.png"
    shot.write_bytes(b"fakepng")
    tree = tmp_path / "a.json"
    tree.write_text('{"n":1}', encoding="utf-8")

    put_headers: list[dict] = []

    class _Resp:
        status_code = 200

    def fake_put(url, data=None, headers=None, timeout=None):
        put_headers.append(dict(headers or {}))
        return _Resp()

    monkeypatch.setattr(runner.requests, "put", fake_put)
    client = _GrantClient()
    timeline = [{"stepNo": 1, "action": "app.tap", "result": "pass"},
                {"stepNo": 2, "action": "app.assertVisible", "result": "fail"}]
    pending = [
        {"stepNo": 2, "kind": "screenshot", "fileName": "a.png",
         "localPath": str(shot), "contentType": "image/png", "slot": "screenshot"},
        {"stepNo": 2, "kind": "dom_snapshot", "fileName": "a.json",
         "localPath": str(tree), "contentType": "application/json", "slot": "view_tree"},
        {"stepNo": 1, "kind": "baseline_screenshot", "fileName": "b.png",
         "localPath": str(shot), "contentType": "image/png"},
    ]
    runner._upload_pending_artifacts(client, 99, pending, timeline)
    row2 = timeline[1]
    assert row2.get("artifactKey") == "exec/screenshot/a.png"
    ev = row2.get("evidence") or {}
    assert ev.get("screenshot") == "exec/screenshot/a.png"
    assert ev.get("view_tree") == "exec/dom_snapshot/a.json"
    assert put_headers[1].get("Content-Type") == "application/json", \
        f"文本产物 Content-Type 必须透传: {put_headers}"
    assert timeline[0].get("artifactKey") == "exec/baseline_screenshot/b.png", \
        "T264 visual 姿势不回退（无 slot 键=零漂移，artifactKey 照写）"
    assert "localPath" not in json.dumps(timeline, ensure_ascii=False)



def _node_available() -> bool:
    try:
        subprocess.run(["node", "--version"], capture_output=True, check=True)
        return True
    except Exception:  # noqa: BLE001
        return False


@pytest.mark.skipif(not _node_available(), reason="node 环境件不可达（如实标注）")
def test_mobilewright_bridge_collect_returns_shot_and_viewtree(tmp_path):
    pass
    from driver.mobile.bridge_process import NodeBridge
    from driver.mobile.mobilewright_adapter import MobilewrightAdapter

    bridge = NodeBridge(script_path=str(BRIDGE_SCRIPT), timeout_sec=15.0,
                        extra_env={"MOBILEWRIGHT_DISABLE_TELEMETRY": "1",
                                   "MW_TEST_ENGINE_MODULE": str(FAKE_ENGINE)})
    bridge.start()
    try:
        adapter = MobilewrightAdapter(descriptor={"code": "MOBILEWRIGHT",
                                                  "platformCodes": ["ANDROID"],
                                                  "deviceKindCodes": ["EMULATOR"]})
        adapter._bridge = bridge
        adapter.prepare({"runId": "r1", "leaseRef": {"serial": "fake-1"}})
        art = adapter.collect({"stepNo": 4, "artifactDir": str(tmp_path)})
        assert isinstance(art, list), f"collect 应回两件列表: {art!r}"
        kinds = {a["kind"] for a in art}
        assert kinds == {"screenshot", "dom_snapshot"}
        for a in art:
            assert a.get("localPath") and pathlib.Path(a["localPath"]).exists()
        tree = next(a for a in art if a["kind"] == "dom_snapshot")
        body = json.loads(pathlib.Path(tree["localPath"]).read_text(encoding="utf-8"))
        assert isinstance(body, list) and body[0].get("type"), \
            "视图树文件=screen.viewTree() 原样序列化，空壳冒充即红"
        adapter.teardown()
    finally:
        bridge.stop()
