
from __future__ import annotations

import json
import pathlib
import sys

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

import app_worker
from driver.mobile.contracts import step_references_credential


class AlwaysPass:
    def prepare(self, ctx):
        pass

    def execute(self, step):
        return {"result": "pass"}


def _task(step, monkeypatch):
    monkeypatch.setattr(app_worker, "_return_lease", lambda *a: None)
    return {
        "taskId": 90001,
        "actionJson": json.dumps({
            "kind": "app-auto-run",
            "engine": {"code": "FAKE", "status": 1},
            "steps": [step],
        }, ensure_ascii=False),
        "_adapterOverride": AlwaysPass(),
    }


def test_residual_cred_placeholder_fails_closed(monkeypatch):
    pass
    outcome = app_worker.run_app_task(_task({
        "action": "app.input", "target": {"text": "密码框"}, "value": "{{CRED:pwd}}",
    }, monkeypatch))
    assert outcome.result == "fail", (
        "残留凭据占位符禁静默当字面文本输入（假绿登录）: "
        f"{outcome.steps_timeline}")
    assert not outcome.steps_timeline, "首错终局=零步落账（校验先行同律）"
    assert "凭据" in outcome.error_detail and "注入" in outcome.error_detail, \
        f"违例须人话点名凭据注入断链: {outcome.error_detail}"


def test_nested_fields_residual_also_caught(monkeypatch):
    pass
    outcome = app_worker.run_app_task(_task({
        "action": "app.tap",
        "target": {"chain": ["xpath"], "xpath": "//input[@hint='{{CRED:k}}']"},
    }, monkeypatch))
    assert outcome.result == "fail"
    assert "{{CRED:k}}" in outcome.error_detail, \
        f"须点名残留引用键（零实值纪律）: {outcome.error_detail}"


def test_normal_steps_unaffected_by_gate(monkeypatch):
    pass
    outcome = app_worker.run_app_task(_task({
        "action": "app.input", "target": {"text": "搜索框"}, "value": "{{ENV:KW}}",
    }, monkeypatch))
    assert outcome.result == "pass", outcome.error_detail


@pytest.mark.parametrize("ref", ["{{CRED:pwd}}", "${CRED:pwd}", "前缀{{CRED:a}}后缀"])
def test_sensitive_flag_recognizes_both_forms(ref):
    pass
    assert step_references_credential(
        {"action": "app.input", "value": ref}) is True


def test_sensitive_flag_plain_step_stays_false():
    assert step_references_credential(
        {"action": "app.tap", "target": "登录"}) is False
