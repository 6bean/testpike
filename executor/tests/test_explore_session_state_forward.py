""" face P2e: the explore channel forwards the login-state envelope.

Core (claim side, P2d) now attaches ``sessionState`` onto task_type=3
explore-act payloads of survey sessions, exactly like the case-execution
channel (task_worker, face 3). The executor consumer side must hand
that envelope to the WebDriverExecutor constructor so the single context
build point (driver/session_context.storage_state_kwargs) mounts the saved
login state — no second derivation here.

Zero-drift contract: a task without the key passes ``None`` = legacy anonymous
context, byte-for-byte the pre-P2e behaviour. Mobile envelopes never receive
the web session state (the mobile branch delegates to app_worker).

L2 scope note: the executor object itself is stubbed to capture constructor
kwargs (infrastructure isolation only); the real chromium mount path is
already pinned in test_session_state_context.py and verified at L3 in the
 batch window.
"""
from __future__ import annotations

import json

import explore_worker
from driver.webdriver import RunOutcome

_WEB_ENVELOPE = json.dumps({
    "kind": "explore-act",
    "action": "page-snapshot",
    "url": "http://localhost:5173/demo",
})
_STATE_ENVELOPE = {"stateName": "approver", "kind": "storage_state",
                   "storageState": "{\"cookies\": [], \"origins\": []}"}


class _StubExecutor:
    """Captures construction kwargs, records the call order of the driver."""

    last_instance = None

    def __init__(self, **kwargs):
        _StubExecutor.last_instance = self
        self.init_kwargs = kwargs
        self.events = []

    def start(self):
        self.events.append("start")

    def run(self, script_doc, case_title=None):
        self.events.append("run")
        return RunOutcome(result="pass", duration_ms=1)

    def stop(self):
        self.events.append("stop")


def _drive(monkeypatch, tmp_path, task: dict) -> dict:
    _StubExecutor.last_instance = None
    monkeypatch.setattr(explore_worker, "WebDriverExecutor", _StubExecutor)
    task_f = tmp_path / "task.json"
    out_f = tmp_path / "outcome.json"
    task_f.write_text(json.dumps(task), encoding="utf-8")
    rc = explore_worker.main(["explore_worker.py", str(task_f), str(out_f)])
    assert rc == 0, f"main rc={rc}"
    assert json.loads(out_f.read_text(encoding="utf-8"))["result"] == "pass"
    assert _StubExecutor.last_instance is not None
    return _StubExecutor.last_instance.init_kwargs


def test_saved_login_state_rides_the_explore_task(monkeypatch, tmp_path):
    init_kwargs = _drive(monkeypatch, tmp_path, {
        "actionJson": _WEB_ENVELOPE,
        "sessionState": _STATE_ENVELOPE,
    })
    assert init_kwargs["session_state"] == _STATE_ENVELOPE


def test_absent_key_keeps_anonymous_context(monkeypatch, tmp_path):
    init_kwargs = _drive(monkeypatch, tmp_path, {"actionJson": _WEB_ENVELOPE})
    assert init_kwargs["session_state"] is None


def test_mobile_envelope_never_gains_web_session_state(monkeypatch, tmp_path):
    captured = {}
    _StubExecutor.last_instance = None

    def _fake_mobile(envelope, started):
        captured["envelope"] = envelope
        return RunOutcome(result="pass", duration_ms=1)

    monkeypatch.setattr(explore_worker, "WebDriverExecutor", _StubExecutor)
    monkeypatch.setattr(explore_worker, "_run_mobile_explore_task", _fake_mobile)
    mobile = {"kind": "explore-act", "target": "mobile", "action": "tree-snapshot",
              "engine": {"name": "uiautomator2"}, "taskId": 1, "runId": "r1",
              "leaseRef": {"serial": "emulator-5554"}}
    outcome = explore_worker.run_explore_task(
        json.dumps(mobile), str(tmp_path), session_state=_STATE_ENVELOPE)
    assert outcome.result == "pass"
    assert _StubExecutor.last_instance is None  # browser branch untouched
