
from __future__ import annotations

import json

from actions_common import IR_VERBS


class _RecorderAdapter:
    pass

    SUPPORTED_PLATFORMS: tuple = ("ANDROID",)
    SUPPORTED_DEVICE_KINDS: tuple = ("EMULATOR",)
    CAPABILITIES: tuple = IR_VERBS

    def __init__(self):
        self.executed: list = []
        self.prepare_called = 0
        self.teardown_called = 0

    def prepare(self, ctx: dict) -> None:
        self.prepare_called += 1

    def execute(self, step: dict) -> dict:
        self.executed.append(dict(step))
        return {"result": "pass"}

    def collect(self, artifact: dict):
        return None

    def teardown(self) -> None:
        self.teardown_called += 1


def _task(steps: list, envelope_extra: dict | None = None,
          env_vars: dict | None = None) -> dict:
    envelope = {"kind": "app-auto-run", "runId": 1,
                "leaseRef": {"serial": "emulator-5554"},
                "engine": {"code": "FAKE-L2", "runtimeType": "PYTHON", "status": 1,
                           "platformCodes": ["ANDROID"], "deviceKindCodes": ["EMULATOR"],
                           "capabilities": {v: True for v in IR_VERBS}},
                "steps": steps}
    envelope.update(envelope_extra or {})
    task = {"taskId": 1, "actionJson": json.dumps(envelope)}
    if env_vars is not None:
        task["envVars"] = env_vars
    return task


def _run(task: dict):
    import app_worker

    recorder = _RecorderAdapter()
    task["_adapterOverride"] = recorder
    outcome = app_worker.run_app_task(task)
    return outcome, recorder


def test_industry_param_resolves_env_placeholder_in_step():
    pass
    step = {"action": "app.input", "selector": "#terminal",
            "text": "{{ENV:pos_terminal_id}}"}
    outcome, recorder = _run(_task([step], {"industryParams":
                                            {"pos_terminal_id": "T-88"}}))
    assert outcome.result == "pass", outcome.error_detail
    assert len(recorder.executed) == 1
    assert recorder.executed[0]["text"] == "T-88"


def test_explicit_env_vars_take_precedence_over_industry_params():
    pass
    step = {"action": "app.input", "selector": "#store",
            "text": "{{ENV:store_code}}"}
    outcome, recorder = _run(_task([step], {"industryParams": {"store_code": "IND"}},
                                   env_vars={"store_code": "EXPLICIT"}))
    assert outcome.result == "pass", outcome.error_detail
    assert recorder.executed[0]["text"] == "EXPLICIT"


def test_missing_key_fails_closed_with_key_named_and_zero_engine_start():
    pass
    step = {"action": "app.input", "selector": "#x", "text": "{{ENV:absent_key}}"}
    outcome, recorder = _run(_task([step], {"industryParams": {"other": "1"}}))
    assert outcome.result == "fail"
    assert outcome.error_type == 3
    assert "absent_key" in (outcome.error_detail or "")
    assert recorder.prepare_called == 0
    assert recorder.executed == []


def test_legacy_envelope_without_industry_key_passes_step_through():
    pass
    step = {"action": "app.input", "selector": "#x", "text": "{{ENV:whatever}}"}
    outcome, recorder = _run(_task([step]))
    assert outcome.result == "pass", outcome.error_detail
    assert recorder.executed[0]["text"] == "{{ENV:whatever}}"


def test_nested_step_values_resolve_industry_params():
    pass
    step = {"action": "app.swipe",
            "from": {"x": "{{ENV:pos_x}}", "items": ["{{ENV:pos_y}}"]}}
    outcome, recorder = _run(_task([step], {"industryParams":
                                            {"pos_x": "10", "pos_y": "20"}}))
    assert outcome.result == "pass", outcome.error_detail
    resolved = recorder.executed[0]["from"]
    assert resolved["x"] == "10"
    assert resolved["items"] == ["20"]
