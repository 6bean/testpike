# -*- coding: utf-8 -*-
"""Preflight self-repair contracts for missing prerequisites.

The reported gap: the preflight printed an install command per host profile
and then waited for a human to run it, so a clean machine still failed later
with an unrelated-looking error. The iron rule is that the installer, once
explicitly run by the user, consented to zero-prerequisite repair: it must
install what its package-manager profile can install, re-probe, and only fall
back to a literal recovery route when it structurally cannot act (no profile,
no privilege, repair failed, or self-repair switched off by hot-reload key).

Four-way acceptance matrix, plus two safety corners:
  A clean machine with a known profile repairs itself and verifies.
  B unknown profile: honest manual route, never an empty "see docs".
  C attempted repair that does not land ends in a named failure state.
  D present prerequisite: zero side effects.
  E non-root context: the installer never elevates; it prints the exact
    command for the operator instead of running sudo.
  F TP_PKG_SELF_REPAIR=0 disables acting while keeping the same route text.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pkg_probe  # noqa: E402

APT_RELEASE = 'ID=ubuntu\nID_LIKE="debian ubuntu"\nPRETTY_NAME="Ubuntu 22.04"\n'


def _profile(monkeypatch, os_release=APT_RELEASE):
    monkeypatch.setattr(pkg_probe, "host_os", lambda: "linux")
    monkeypatch.setattr(pkg_probe, "read_os_release", lambda: os_release)


def _missing_until_installed(monkeypatch, state):
    def fake_which(name):
        return "/usr/bin/%s" % name if state.get(name) else None
    monkeypatch.setattr(pkg_probe.shutil, "which", fake_which)


def test_known_profile_repairs_and_verifies(monkeypatch, capsys):
    """A: root context runs the profile command, re-probes, reports verified."""
    _profile(monkeypatch)
    monkeypatch.setattr(pkg_probe, "is_effective_root", lambda: True)
    state = {}
    _missing_until_installed(monkeypatch, state)
    calls = []

    def fake_execute(cmd):
        calls.append(cmd)
        state["docker"] = True
        return 0

    monkeypatch.setattr(pkg_probe, "execute", fake_execute)
    rc = pkg_probe.preflight(["docker"], [])
    assert rc == 0
    assert calls == ["apt-get update && apt-get install -y docker.io docker-compose-v2"], \
        "root context must run the sudo-free form of the profile command"
    out = capsys.readouterr().out
    assert "installed and verified" in out
    assert "did not land" not in out


def test_unknown_profile_prints_manual_route(monkeypatch, capsys):
    """B: no registered profile -> per-item official docs link plus resume line."""
    _profile(monkeypatch, os_release='ID=void\n')
    monkeypatch.setattr(pkg_probe, "is_effective_root", lambda: True)
    _missing_until_installed(monkeypatch, {})
    executed = []
    monkeypatch.setattr(pkg_probe, "execute", lambda cmd: executed.append(cmd) or 0)
    rc = pkg_probe.preflight(["git"], [])
    assert rc == 0
    assert executed == [], "unknown profile must not run anything"
    out = capsys.readouterr().out
    assert "git-scm.com" in out, "route must name a literal documentation entry"
    assert "installkit/install.sh" in out, "route must name the rerun entry literally"


def test_failed_repair_ends_in_named_failure_state(monkeypatch, capsys):
    """C: repair command ran but the prerequisite is still absent -> failure rc."""
    _profile(monkeypatch)
    monkeypatch.setattr(pkg_probe, "is_effective_root", lambda: True)
    _missing_until_installed(monkeypatch, {})
    monkeypatch.setattr(pkg_probe, "execute", lambda cmd: 100)
    rc = pkg_probe.preflight(["node"], [])
    assert rc == pkg_probe.REPAIR_FAILED_RC
    out = capsys.readouterr().out
    assert "exit code 100" in out
    assert "apt-get install -y nodejs npm" in out, "terminal state repeats the exact runnable command"
    assert "installkit/install.sh" in out


def test_present_prerequisite_does_nothing(monkeypatch, capsys):
    """D: which() already resolves -> zero execution, unchanged report."""
    _profile(monkeypatch)
    monkeypatch.setattr(pkg_probe, "is_effective_root", lambda: True)
    monkeypatch.setattr(pkg_probe.shutil, "which", lambda name: "/usr/bin/%s" % name)
    calls = []
    monkeypatch.setattr(pkg_probe, "execute", lambda cmd: calls.append(cmd) or 0)
    rc = pkg_probe.preflight(["docker", "git"], [])
    assert rc == 0
    assert calls == []
    out = capsys.readouterr().out
    assert out.count("present") == 2


def test_non_root_never_elevates(monkeypatch, capsys):
    """E: without root the installer prints the sudo form; it runs nothing."""
    _profile(monkeypatch)
    monkeypatch.setattr(pkg_probe, "is_effective_root", lambda: False)
    _missing_until_installed(monkeypatch, {})
    calls = []
    monkeypatch.setattr(pkg_probe, "execute", lambda cmd: calls.append(cmd) or 0)
    rc = pkg_probe.preflight(["python3"], [])
    assert rc == 0
    assert calls == [], "the installer never elevates privileges on its own"
    out = capsys.readouterr().out
    assert "sudo apt-get install -y python3 python3-pip" in out
    assert "installkit/install.sh" in out


def test_self_repair_hot_reload_key_off(monkeypatch, capsys):
    """F: TP_PKG_SELF_REPAIR=0 keeps root context inert but still routes the fix."""
    _profile(monkeypatch)
    monkeypatch.setattr(pkg_probe, "is_effective_root", lambda: True)
    state = {}
    _missing_until_installed(monkeypatch, state)
    monkeypatch.setenv("TP_PKG_SELF_REPAIR", "0")
    calls = []
    monkeypatch.setattr(pkg_probe, "execute", lambda cmd: calls.append(cmd) or 0)
    rc = pkg_probe.preflight(["docker"], [])
    assert rc == 0
    assert calls == []
    out = capsys.readouterr().out
    assert "TP_PKG_SELF_REPAIR" in out, "off-state route must name the switch key"
    assert "apt-get install -y docker.io" in out
