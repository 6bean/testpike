"""Action-step write attribution for explore sessions (design pieces 1/2).

Pins the driver-side wiring the single-action explore channel needs:

  * the explore channel starts the driver with the write-guard switch ON;
    the normal case-execution channel never gets it (zero ripple);
  * inside the driver a goto step closes the load baseline, click/input steps
    attribute later traffic to their step number, and a baseline-outside write
    verb becomes a ``writeEvents`` row plus the operation-facing one-liner
    appended to that step's timeline detail;
  * network >=400 / requestfailed triples trail out as ``failures`` rows,
    except rows on mock honest-miss (passthrough) paths — the exemption
    consumes mock_notes as its single ledger source, no second exemption book;
  * trailing-key discipline: empty means the key is omitted (old core reads
    the envelope untouched); from_dict round-trips both keys.

L2 scope note: the browser primitives are stubbed to no-ops (infrastructure
isolation only); the real chromium mount of the guard feed is already pinned
in test_explore_network_guard.py and the survey frame path in
test_site_survey_write_guard_wiring.py.
"""
from __future__ import annotations

import json

import explore_worker
from driver.explore_network_guard import describe_write_event
from driver.script_limits import ScriptLimits
from driver.webdriver import RunOutcome, WebDriverExecutor, _ExecCtx

WEB_ENVELOPE = json.dumps({
    "kind": "explore-act",
    "action": "click-path",
    "url": "http://t.example/app",
    "args": {"target": {"selector": "#save"}},
})


class _StubExecutor:
    last_instance = None

    def __init__(self, **kwargs):
        _StubExecutor.last_instance = self
        self.init_kwargs = kwargs

    def start(self):
        pass

    def run(self, script_doc, case_title=None):
        return RunOutcome(result="pass", duration_ms=1)

    def stop(self):
        pass


def _drive_explore(monkeypatch, tmp_path, task: dict) -> dict:
    _StubExecutor.last_instance = None
    monkeypatch.setattr(explore_worker, "WebDriverExecutor", _StubExecutor)
    task_f = tmp_path / "task.json"
    out_f = tmp_path / "outcome.json"
    task_f.write_text(json.dumps(task), encoding="utf-8")
    rc = explore_worker.main(["explore_worker.py", str(task_f), str(out_f)])
    assert rc == 0, f"main rc={rc}"
    assert _StubExecutor.last_instance is not None
    return _StubExecutor.last_instance.init_kwargs


from network_guard_fakes import FakeObserver as _FakeObserver
from network_guard_fakes import FakePage as _FakePage


def _guarded_executor():
    """Driver instance with the guard mounted the way run() mounts it."""
    ex = WebDriverExecutor(network_guard=True)
    # Stubbed primitives have no screenshot feed; pin the fail-only mode.
    ex.limits = ScriptLimits.from_payload({"screenshot-mode": 3})
    obs = _FakeObserver()
    ex.net_observer = obs
    ex.network_guard = ex._mount_network_guard(_FakePage())
    # Browser primitives are not the subject of this wiring test.
    ex._goto = lambda page, step: None
    ex._click = lambda page, step: None
    ex._input = lambda page, step: None
    return ex, obs


def test_explore_channel_arms_the_guard_switch(monkeypatch, tmp_path):
    init_kwargs = _drive_explore(monkeypatch, tmp_path, {"actionJson": WEB_ENVELOPE})
    assert init_kwargs["network_guard"] is True


def test_guard_switch_defaults_off_for_normal_cases():
    ex = WebDriverExecutor()
    assert ex._mount_network_guard(_FakePage()) is None
    assert ex._guard_outcome_rows([]) == {}


def test_goto_baseline_then_click_attributes_write_event():
    ex, obs = _guarded_executor()
    ctx = _ExecCtx()
    # load-phase traffic (before the goto hook closes the baseline)
    obs.add("http://t.example/app", "GET", 200)
    ex._exec_step(_FakePage(), {"action": "goto", "url": "http://t.example/app"},
                  ctx, "case")
    ex._exec_step(_FakePage(), {"action": "click", "target": {"selector": "#x"}},
                  ctx, "case")
    click_no = ctx.next_no - 1
    # a POST that arrives after the action began = baseline-outside write
    obs.add("http://t.example/api/save", "POST", 200)
    steps = [{"stepNo": click_no, "action": "click", "detail": "ok"}]

    rows = ex._guard_outcome_rows(steps)
    assert rows["write_events"] == [
        {"stepNo": click_no, "method": "POST", "url": "http://t.example/api/save"}]
    assert describe_write_event(rows["write_events"][0]) in steps[0]["detail"]
    assert "failures" not in rows


def test_input_step_also_opens_the_action_window():
    ex, obs = _guarded_executor()
    ctx = _ExecCtx()
    ex._exec_step(_FakePage(), {"action": "goto", "url": "http://t.example/app"},
                  ctx, "case")
    ex._exec_step(_FakePage(), {"action": "input", "target": {"selector": "#name"},
                                "value": "x"}, ctx, "case")
    obs.add("http://t.example/api/autosave", "PUT", 200)
    rows = ex._guard_outcome_rows([{"stepNo": 2, "action": "input", "detail": None}])
    assert rows["write_events"][0]["method"] == "PUT"
    assert rows["write_events"][0]["stepNo"] == 2


def test_honest_miss_failures_are_exempted_single_source():
    ex, obs = _guarded_executor()
    ctx = _ExecCtx()
    ex._exec_step(_FakePage(), {"action": "goto", "url": "http://t.example/app"},
                  ctx, "case")
    obs.add("http://t.example/api/missing", "GET", 404)  # mock coverage miss
    obs.add("http://t.example/api/boom", "POST", 500)    # real product defect
    ex.mock_notes.append({"path": "http://mockserver/api/missing",
                          "mode": "passthrough"})

    rows = ex._guard_outcome_rows([])
    urls = [f["url"] for f in rows["net_failures"]]
    assert urls == ["http://t.example/api/boom"]


def test_trailing_keys_zero_drift_and_roundtrip():
    empty = RunOutcome(result="pass")
    payload = empty.to_dict()
    assert "writeEvents" not in payload and "failures" not in payload

    full = RunOutcome(result="pass",
                      write_events=[{"stepNo": 2, "method": "POST",
                                     "url": "http://t.example/api/save"}],
                      net_failures=[{"url": "http://t.example/api/boom",
                                     "method": "POST", "status": 500,
                                     "errorType": 1, "failureText": None}])
    payload = full.to_dict()
    assert payload["writeEvents"] and payload["failures"]
    back = RunOutcome.from_dict(json.loads(json.dumps(payload)))
    assert back.write_events == full.write_events
    assert back.net_failures == full.net_failures

    legacy = RunOutcome.from_dict({"result": "pass"})
    assert legacy.write_events == [] and legacy.net_failures == []
