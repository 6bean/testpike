"""Executor-runner control must be ownership-scoped and slot-named (R47 projection).

Plain logic tests: no browser, no services. They exist because the previous control probed any
unit mentioning runner.py, so one chain's window-stop could stop another chain's runner.
"""
import os
import re
import sys
from pathlib import Path

from conftest import runner_unit_name, unit_is_ours  # noqa: F401

HERE = Path(__file__).resolve().parents[2]          # <repo>/frontend


def test_foreign_unit_is_not_ours(monkeypatch):
    monkeypatch.setattr("conftest._proc_cwd", lambda pid: "/home/other/code/testPike-wt-otherchain")
    monkeypatch.setattr("conftest.subprocess.run",
                        lambda *a, **k: type("R", (), {"stdout": "0\n", "returncode": 0})())
    assert unit_is_ours("tp-e2e-runner-slot9.service") is False


def test_own_unit_is_ours(monkeypatch):
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    monkeypatch.setattr("conftest._proc_cwd", lambda pid: os.path.join(root, "executor"))
    assert unit_is_ours("tp-e2e-runner-slot0.service", main_pid="4242") is True


def test_unit_name_carries_this_chains_real_slot(monkeypatch):
    import conftest
    sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
    import chain_slot

    monkeypatch.setattr(conftest, "_this_slot", lambda: 7)
    assert runner_unit_name() == "tp-e2e-runner-slot7", "槽 7 必须体现在单元名上"
    monkeypatch.setattr(conftest, "_this_slot", lambda: 0)
    assert runner_unit_name() == "tp-e2e-runner-slot0"
    # the live value must equal the single source, not a default that happens to look right
    assert conftest._this_slot() == chain_slot.current_slot(cwd=str(HERE.parent))


def test_slot_number_is_not_silently_zero_for_a_worktree(monkeypatch):
    """A worktree whose slot env cannot be resolved must raise, not fall back to slot 0."""
    sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
    import chain_slot
    import slot_topology as topo

    def boom(*a, **k):
        raise RuntimeError("no slot env")

    monkeypatch.setattr(chain_slot, "env_path_for", boom)
    monkeypatch.setattr(topo, "is_worktree", lambda cwd=None: True)
    try:
        chain_slot.current_slot(cwd="/tmp/pretend-worktree")
    except RuntimeError as exc:
        assert "slot0" not in str(exc) or "禁按 slot0" in str(exc), exc
    else:
        raise AssertionError("worktree 解析失败却被当成 slot0 = 静默错判")


def test_no_singleton_unit_name_left_in_control_code():
    src = (HERE / "tests/e2e/conftest.py").read_text(encoding="utf-8")
    bare = re.findall(r'--unit=tp-e2e-runner"', src)
    assert not bare, f"creation site still writes the shared singleton name: {bare}"
