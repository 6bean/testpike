
from __future__ import annotations

import json
import os

import pytest

import app_worker
import explore_worker


def _mobile_env(action: str, *, engine: dict | None = None,
               serial: str | None = "emulator-5554",
               arguments: dict | None = None, target: str | None = "mobile") -> str:
    env = {"kind": "explore-act", "action": action}
    if target is not None:
        env["target"] = target
    env["engine"] = engine if engine is not None else {
        "code": "FAKE-L2", "platformCodes": ["ANDROID"],
        "deviceKindCodes": ["EMULATOR"]}
    if serial is not None:
        env["leaseRef"] = {"serial": serial}
    env["arguments"] = arguments or {}
    return json.dumps(env, ensure_ascii=False)


class _FakeTreeAdapter:
    pass

    SUPPORTED_PLATFORMS: tuple = ("ANDROID",)
    SUPPORTED_DEVICE_KINDS: tuple = ("EMULATOR",)

    def __init__(self, tree_payload: object = None):
        self.tree_payload = tree_payload if tree_payload is not None else [
            {"text": "登录", "bounds": {"x": 10, "y": 20, "width": 100, "height": 40}}]
        self.calls: list[tuple] = []

    def prepare(self, ctx: dict) -> None:
        self.calls.append(("prepare", ctx.get("leaseRef")))

    def execute(self, step: dict) -> dict:
        self.calls.append(("execute", step.get("action"), dict(step)))
        return {"result": "pass"}

    def collect(self, artifact: dict):
        self.calls.append(("collect",))
        art_dir = artifact.get("artifactDir")
        os.makedirs(art_dir, exist_ok=True)
        path = os.path.join(art_dir, f"step-{artifact.get('stepNo')}-viewtree.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(self.tree_payload, f, ensure_ascii=False)
        return [{"kind": "dom_snapshot", "localPath": path,
                 "contentType": "application/json"}]

    def teardown(self) -> None:
        self.calls.append(("teardown",))


@pytest.fixture
def fake_engine(monkeypatch):
    pass
    holder: dict = {}

    def _install(adapter):
        holder["adapter"] = adapter
        holder["task"] = None
        real = app_worker.run_app_task

        def spy(task: dict) -> object:
            holder["task"] = task
            return real(task)

        monkeypatch.setattr(app_worker, "mobile_engine_factory",
                            lambda descriptor: adapter)
        monkeypatch.setattr(explore_worker, "run_app_task", spy)
        return holder

    return _install



def test_mobile_tap_delegates_single_app_step(fake_engine):
    holder = fake_engine(_FakeTreeAdapter())
    outcome = explore_worker.run_explore_task(_mobile_env("tap"), "/tmp")
    assert outcome.result == "pass"
    app_env = json.loads(holder["task"]["actionJson"])
    assert app_env["kind"] == "app-auto-run"
    assert [s["action"] for s in app_env["steps"]] == ["app.tap"]
    adapter = holder.get("adapter")
    assert adapter is not None
    verbs = [c[1] for c in adapter.calls if c[0] == "execute"]
    assert verbs == ["app.tap"]
    assert [c[0] for c in adapter.calls] == ["prepare", "execute", "collect", "teardown"]


def test_mobile_arguments_pass_through_to_app_step(fake_engine):
    holder = fake_engine(_FakeTreeAdapter())
    explore_worker.run_explore_task(
        _mobile_env("tap", arguments={"x": 320, "y": 640}), "/tmp")
    step = json.loads(holder["task"]["actionJson"])["steps"][0]
    assert step.get("x") == 320 and step.get("y") == 640


def test_mobile_step_keeps_lease_unaddressable(fake_engine):
    pass
    holder = fake_engine(_FakeTreeAdapter())
    explore_worker.run_explore_task(_mobile_env("back"), "/tmp")
    app_env = json.loads(holder["task"]["actionJson"])
    assert app_env["leaseRef"] == {"serial": "emulator-5554"}
    lease_ref = next(c for c in holder["adapter"].calls if c[0] == "prepare")[1]
    assert isinstance(lease_ref, dict) and "deviceId" not in lease_ref



def test_tree_snapshot_returns_tree_text_in_detail(fake_engine):
    fake_engine(_FakeTreeAdapter([{"text": "首页"}, {"text": "我的"}]))
    outcome = explore_worker.run_explore_task(_mobile_env("tree-snapshot"), "/tmp")
    assert outcome.result == "pass"
    detail = outcome.steps_timeline[-1]["detail"]
    assert "首页" in detail and "我的" in detail


def test_tree_snapshot_trims_over_budget(fake_engine):
    big = [{"text": "节点" * 2000} for _ in range(5)]
    fake_engine(_FakeTreeAdapter(big))
    outcome = explore_worker.run_explore_task(_mobile_env("tree-snapshot"), "/tmp")
    detail = outcome.steps_timeline[-1]["detail"]
    assert len(detail) <= explore_worker.SNAPSHOT_MAX_CHARS + 64
    assert "truncated" in detail or "截断" in detail


def test_tree_snapshot_without_evidence_is_honest(fake_engine, monkeypatch):
    class _NoTree(_FakeTreeAdapter):
        def collect(self, artifact: dict):
            return None

    fake_engine(_NoTree())
    outcome = explore_worker.run_explore_task(_mobile_env("tree-snapshot"), "/tmp")
    detail = outcome.steps_timeline[-1]["detail"]
    assert "不可得" in detail



@pytest.mark.parametrize("action", [
    "uninstall", "install", "clearData", "grantPermission",
    "page-snapshot", "click-path", "launch"])
def test_mobile_action_outside_roam_whitelist_fails_plainly(fake_engine, action):
    adapter = _FakeTreeAdapter()
    holder = fake_engine(adapter)
    outcome = explore_worker.run_explore_task(_mobile_env(action), "/tmp")
    assert outcome.result == "fail"
    assert outcome.error_type == 3
    assert "白名单" in (outcome.error_detail or "")
    assert holder["task"] is None and adapter.calls == []


def test_mobile_envelope_without_engine_fails(fake_engine):
    fake_engine(_FakeTreeAdapter())
    env = json.dumps({"kind": "explore-act", "target": "mobile",
                      "action": "tap", "leaseRef": {"serial": "s1"}})
    outcome = explore_worker.run_explore_task(env, "/tmp")
    assert outcome.result == "fail"
    assert "engine" in outcome.error_detail or "引擎" in outcome.error_detail


def test_mobile_envelope_without_serial_fails(fake_engine):
    fake_engine(_FakeTreeAdapter())
    outcome = explore_worker.run_explore_task(
        _mobile_env("tap", serial=None), "/tmp")
    assert outcome.result == "fail"
    assert "serial" in outcome.error_detail or "设备" in outcome.error_detail


def test_web_envelope_still_requires_url():
    pass
    outcome = explore_worker.run_explore_task(
        json.dumps({"kind": "explore-act", "action": "page-snapshot"}), "/tmp")
    assert outcome.result == "fail"
    assert "url" in outcome.error_detail


def test_kind_still_guarded_for_mobile_shape():
    outcome = explore_worker.run_explore_task(
        json.dumps({"kind": "app-auto-run", "target": "mobile", "action": "tap"}),
        "/tmp")
    assert outcome.result == "fail"
    assert "explore-act" in outcome.error_detail
