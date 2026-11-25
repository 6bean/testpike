# -*- coding: utf-8 -*-
"""Tier-aware acceptance contract for the demo greenlight proof (T293d r47).

Root cause pinned by the r47 clean-machine cell: the proof always waits for an
executor-claimed terminal status, but the lightweight/standard tiers install
without the executor component (single source: resource_tiers.COMPONENTS
["executor"]["tiers"] == ["full"]). A dispatched run sits queued forever, so
every lightweight cell burns its whole window on a capability the tier never
promised. Fix = the matrix declares the tier's executor capability to the
proof, and the proof reports an honest queued-standby verdict instead of a
timeout. Screen copy must not over-promise execution outside the full tier.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
INSTALLKIT = os.path.dirname(HERE)
ROOT = os.path.dirname(INSTALLKIT)
TOOLS = os.path.join(ROOT, "tools", "verification")
sys.path.insert(0, INSTALLKIT)
sys.path.insert(0, TOOLS)

import resource_tiers  # noqa: E402


def _load(path, name):
    import importlib.util
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _proof():
    return _load(os.path.join(TOOLS, "greenlight_demo_proof.py"),
                 "greenlight_demo_proof_tier_contract")


def _matrix():
    return _load(os.path.join(TOOLS, "clean_matrix_window.py"),
                 "clean_matrix_window_tier_contract")


def test_executor_component_is_full_tier_only():
    # the standby design below is derived from this single source, never a copy
    assert resource_tiers.COMPONENTS["executor"]["tiers"] == ["full"]


def test_proof_accepts_executor_capability_argument():
    ap_src = open(os.path.join(TOOLS, "greenlight_demo_proof.py"),
                  encoding="utf-8").read()
    assert '"--executor"' in ap_src, "proof must take the tier executor capability"


def test_proof_standby_verdict_line_and_values():
    g = _proof()
    # verdict values come from the module, not re-typed in the matrix or tests
    assert g.EXECUTOR_REQUIRED == "required"
    assert g.EXECUTOR_STANDBY_OK == "standby-ok"
    assert g.STATUS_STANDBY == "STANDBY"


def test_run_and_wait_reports_standby_on_queue_timeout_when_allowed():
    """Queue timeout with standby allowed = STANDBY verdict, not None."""

    class Resp:
        def __init__(self, payload):
            self._payload = payload

        def json(self):
            return self._payload

        def raise_for_status(self):
            return None

    class FakeClient:
        """POST creates the run; every GET keeps it queued (status 0)."""

        def post(self, path, **kw):
            return Resp({"code": "0", "data": {"id": 77}})

        def get(self, path, **kw):
            return Resp({"code": "0", "data": {"id": 77, "status": 0}})

    g = _proof()
    st = g.run_and_wait(FakeClient(), {}, 1, 2, max_wait_s=0,
                        executor=g.EXECUTOR_STANDBY_OK)
    assert st == g.STATUS_STANDBY, "queued standby must be a first-class verdict"
    st_forbidden = g.run_and_wait(FakeClient(), {}, 1, 2, max_wait_s=0,
                                  executor=g.EXECUTOR_REQUIRED)
    assert st_forbidden is None, "required mode must keep failing on standby"


def test_matrix_declares_tier_executor_capability_to_proof():
    m = _matrix()
    # value domain comes straight from the installer component matrix
    assert m.proof_executor_flag("lightweight") == "--executor=standby-ok"
    assert m.proof_executor_flag("standard") == "--executor=standby-ok"
    assert m.proof_executor_flag("full") == "--executor=required"
    # every runnable non-full cell wires the flag into its proof step
    for c in m.cells():
        if not c.get("runnable"):
            continue
        cmd = m.cell_command(c)
        expected = ("--executor=standby-ok" if c["tier"] != "full"
                    else "--executor=required")
        assert expected in cmd, "cell %s/%s proof step must carry the tier flag" \
            % (c["profile"], c["tier"])


def test_lightweight_blurb_does_not_promise_case_execution():
    blurb = resource_tiers.TIERS["lightweight"]["blurb"]
    assert not re.search(r"execution[^;]*(out of the box|included|works)", blurb, re.I), (
        "lightweight has no executor component; blurb over-promises: %r" % blurb)
    assert "Full" in blurb or "executor" in blurb.lower(), (
        "the capability gap must point at the tier that closes it")


def test_full_blurb_names_executor_as_the_execution_source():
    assert "executor" in resource_tiers.TIERS["full"]["blurb"].lower()


def test_center_guide_line_states_executor_dependency_honestly():
    src = open(os.path.join(INSTALLKIT, "demo_seed.py"), encoding="utf-8").read()
    lines = src.splitlines()
    starts = [i for i, ln in enumerate(lines)
              if "Guided tour: from the project list" in ln]
    assert starts, "centers guide line must exist"
    # the sentence is a multi-part print(...) continuation, gather until the
    # formatting tail so the tier note cannot hide on a continuation line
    block = " ".join(lines[starts[0]:starts[0] + 6])
    assert "Full" in block, (
        "the run-your-way journey copy must name the tier that carries the executor")
