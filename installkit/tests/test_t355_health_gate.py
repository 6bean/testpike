# -*- coding: utf-8 -*-
""" pack A: agent-local health gate unit tests (AF design 8A.4, decision D).

Locked semantics:
- after a platform-service swap + restart the agent probes the service health
 endpoint locally — N consecutive UPs inside a startup budget = success, and
 the receipt carries the gate evidence (probe count / first-UP latency);
- first DOWN after the service already came UP = immediate local rollback to
 the pre-operation release (seconds-scale self-rescue — the platform control
 plane may itself be down; never wait for platform-side liveness judgement);
- startup budget exhausted without reaching the consecutive requirement = the
 same local rollback, receipt = plain-word "failed + auto-rolled back";
- old payloads without health fields fail open to the V224 seed defaults
 (5 probes x 2s interval, 90s startup budget) — missing optional keys must
 never crash the agent nor refuse an otherwise valid command;
- no health url at all (payload override absent and the catalogue row carries
 none, e.g. frontend/nginx) = gate skipped, honestly stated in the receipt.

fetch/spawn/probe/sleep are environment facilities injected at L2; the live
bad-jar self-rescue chain is pack E (L4) per the verification-layering rule.
"""
import hashlib
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest

import service_agent as sa


def _make_release(root, family, version):
    """Materialize one release dir + current symlink for a managed family."""
    row = next(r for r in sa.MANAGED_SERVICES if r.family == family)
    release = root / family / "releases" / version
    release.mkdir(parents=True, exist_ok=True)
    sidecar = release / ("build-info.properties"
                         if row.layout == sa.LAYOUT_BUILD_INFO else "version.json")
    sidecar.write_text("build.version=%s\n" % version
                       if row.layout == sa.LAYOUT_BUILD_INFO else '{"version": "%s"}' % version,
                       encoding="utf-8")
    link = root / family / "current"
    link.parent.mkdir(parents=True, exist_ok=True)
    if link.is_symlink() or link.exists():
        link.unlink()
    link.symlink_to(release)
    return row


class ScriptedProbe:
    """Health probe stub: returns the scripted results in order, repeating
    the last value once the script is exhausted (so a budget loop is fast)."""

    def __init__(self, results):
        self.results = list(results)
        self.calls = []

    def __call__(self, url):
        self.calls.append(url)
        idx = len(self.calls) - 1
        return self.results[idx] if idx < len(self.results) else self.results[-1]


class GateEnv:
    """Injectable environment for gate tests (L2 stands in every facility)."""

    def __init__(self, root, probe):
        self.root = root
        self.artifact = b"payload"
        self.sha = hashlib.sha256(self.artifact).hexdigest()
        self.fetch_calls = []
        self.spawn_calls = []
        self.probe = probe
        self.sleep = lambda seconds: None  # never burn wall clock at L2

    def fetch(self, url):
        self.fetch_calls.append(url)
        return self.artifact

    def spawn(self, argv):
        self.spawn_calls.append(argv)
        return 0


def _cmd(service, **payload):
    return {"id": 9, "claimedBy": 3, "commandType": "platform-service-upgrade",
            "payload": {"service": service, "target_version": "2.0.0",
                        "artifact_url": "https://mirror/%s.jar" % service,
                        **payload}}


# ── defaults contract (drift pin against the V224 seed values) ───────────────

def test_agent_defaults_mirror_v224_health_gate_seed():
    """Decision D defaults live in K8 keys; the agent's payload-less fallback
    must carry the same numbers or old queued commands drift silently."""
    assert sa.HEALTH_GATE_DEFAULT_PROBE_COUNT == 5
    assert sa.HEALTH_GATE_DEFAULT_INTERVAL_SECONDS == 2
    assert sa.HEALTH_GATE_DEFAULT_STARTUP_BUDGET_SECONDS == 90


# ── pass path: evidence in the receipt ───────────────────────────────────────

def test_gate_pass_after_starting_downs_reports_evidence(tmp_path):
    """DOWN before the first UP = still booting (tolerated within budget);
    reaching N consecutive UPs = success + gate evidence in the receipt."""
    _make_release(tmp_path, "admin", "1.0.0")
    probe = ScriptedProbe([False, False, True, True, True])
    env = GateEnv(tmp_path, probe)
    res = sa.execute_command(_cmd("admin", sha256=env.sha, health_url="http://h/actuator/health",
                                  health_probe_count=3,
                                  health_probe_interval_seconds=0,
                                  health_startup_budget_seconds=10), env)
    assert res.success, res.detail
    assert "health gate" in res.detail and "3" in res.detail
    assert probe.calls == ["http://h/actuator/health"] * 5
    assert (tmp_path / "admin" / "current").resolve().name == "2.0.0"
    # exactly one restart: the new version came healthy, no rollback restart
    assert env.spawn_calls == [["systemctl", "restart", "tp-admin.service"]]


# ── fail paths: immediate local rollback ─────────────────────────────────────

def test_gate_down_after_first_up_rolls_back_locally(tmp_path):
    """The service came UP once then went DOWN = broken release: the agent
    must re-point current at the pre-operation release and restart again —
    seconds-scale self-rescue, receipt = failure + auto-rolled back."""
    _make_release(tmp_path, "admin", "1.0.0")
    probe = ScriptedProbe([True, True, False])
    env = GateEnv(tmp_path, probe)
    res = sa.execute_command(_cmd("admin", sha256=env.sha, health_url="http://h/actuator/health",
                                  health_probe_count=3,
                                  health_probe_interval_seconds=0,
                                  health_startup_budget_seconds=10), env)
    assert not res.success
    assert "rolled back" in res.detail and "1.0.0" in res.detail
    assert (tmp_path / "admin" / "current").resolve().name == "1.0.0"
    assert env.spawn_calls == [["systemctl", "restart", "tp-admin.service"]] * 2


def test_gate_startup_budget_exhausted_rolls_back(tmp_path):
    """Never comes UP inside the budget = failure (no infinite waiting) and
    the same local rollback to the previous release."""
    _make_release(tmp_path, "core", "2.0.0")
    probe = ScriptedProbe([False])
    env = GateEnv(tmp_path, probe)
    res = sa.execute_command(_cmd("core", sha256=env.sha, health_url="http://h/actuator/health",
                                  health_probe_count=2,
                                  health_probe_interval_seconds=0,
                                  health_startup_budget_seconds=1), env)
    assert not res.success
    assert "budget" in res.detail and "rolled back" in res.detail
    assert (tmp_path / "core" / "current").resolve().name == "2.0.0"
    assert env.spawn_calls == [["systemctl", "restart", "tp-core.service"]] * 2


def test_gate_fail_with_no_previous_release_is_honest(tmp_path):
    """First-install shape (no current link before the swap): nothing to roll
    back to — the failure words must say so instead of pretending recovery."""
    probe = ScriptedProbe([False])
    env = GateEnv(tmp_path, probe)
    res = sa.execute_command(_cmd("admin", sha256=env.sha, health_url="http://h/health",
                                  health_probe_count=2,
                                  health_probe_interval_seconds=0,
                                  health_startup_budget_seconds=1), env)
    assert not res.success
    assert "no previous release" in res.detail
    # only the forward restart happened — a rollback restart on a dangling
    # pre-state would be theatre
    assert env.spawn_calls == [["systemctl", "restart", "tp-admin.service"]]


def test_gate_fail_rollback_restart_crash_lands_honest_receipt(tmp_path):
    """If even the recovery restart cannot be confirmed (hanging systemctl on
    the SECOND spawn — rollback leg), the receipt must still land with the
    unit named — never crash the agent."""
    _make_release(tmp_path, "admin", "1.0.0")
    probe = ScriptedProbe([False])
    env = GateEnv(tmp_path, probe)
    calls = []

    def spawn(argv):
        calls.append(argv)
        if len(calls) > 1:  # forward restart lands fine; recovery spawn hangs
            raise subprocess.TimeoutExpired(argv, 120)
        return 0

    env.spawn = spawn
    res = sa.execute_command(_cmd("admin", sha256=env.sha, health_url="http://h/health",
                                  health_probe_count=2,
                                  health_probe_interval_seconds=0,
                                  health_startup_budget_seconds=1), env)
    assert not res.success
    assert "tp-admin.service" in res.detail


# ── payload compatibility (old -era commands queued before this pack) ───

def test_old_payload_without_health_fields_uses_defaults(tmp_path):
    """Missing optional keys = fail-open to the defaults, never a refusal:
    the gate still runs with probe-count 5 and the catalogue url."""
    _make_release(tmp_path, "core", "1.5.0")
    probe = ScriptedProbe([True])
    env = GateEnv(tmp_path, probe)
    res = sa.execute_command({"id": 1, "claimedBy": 1,
                              "commandType": "platform-service-upgrade",
                              "payload": {"service": "core", "target_version": "2.0.0",
                                          "artifact_url": "https://m/core.jar",
                                          "sha256": env.sha}}, env)
    assert res.success, res.detail
    assert len(probe.calls) == sa.HEALTH_GATE_DEFAULT_PROBE_COUNT


def test_invalid_health_param_values_fall_back_to_defaults(tmp_path):
    """Junk numeric values (operator mis-fill / old producer) must degrade to
    the defaults rather than zero-probe "instant success" or a crash."""
    _make_release(tmp_path, "core", "1.5.0")
    probe = ScriptedProbe([True])
    env = GateEnv(tmp_path, probe)
    res = sa.execute_command(_cmd("core", sha256=env.sha, health_probe_count="abc",
                                  health_probe_interval_seconds="-3",
                                  health_startup_budget_seconds="0"), env)
    assert res.success, res.detail
    assert len(probe.calls) == sa.HEALTH_GATE_DEFAULT_PROBE_COUNT


def test_payload_health_url_overrides_catalogue_default(tmp_path):
    """Hosts with non-default ports (local ai-service runs on 18084 etc.)
    carry the real probe url from the enqueueing side — payload wins."""
    _make_release(tmp_path, "core", "1.0.0")
    probe = ScriptedProbe([True])
    env = GateEnv(tmp_path, probe)
    res = sa.execute_command(_cmd("core", sha256=env.sha, health_url="http://127.0.0.1:18084/actuator/health",
                                  health_probe_count=1,
                                  health_probe_interval_seconds=0,
                                  health_startup_budget_seconds=5), env)
    assert res.success, res.detail
    assert probe.calls[0] == "http://127.0.0.1:18084/actuator/health"


def test_no_health_url_anywhere_skips_gate_honestly(tmp_path):
    """frontend/nginx has no actuator: no payload url + no catalogue default =
    the gate is skipped and the receipt says so — never a fake-green probe."""
    _make_release(tmp_path, "frontend", "0.9.0")
    probe = ScriptedProbe([False])
    env = GateEnv(tmp_path, probe)
    res = sa.execute_command(_cmd("frontend", sha256=env.sha,
                                  artifact_url="https://m/frontend-dist.tar.gz"), env)
    assert res.success, res.detail
    assert "health gate skipped" in res.detail
    assert probe.calls == []


# ── rollback command is guarded by the same gate ─────────────────────────────

def test_rollback_command_goes_through_the_same_gate(tmp_path):
    """Design decision: rollback passes the health gate too — a rollback onto
    a broken ledger version must still self-rescue, not declare victory."""
    _make_release(tmp_path, "core", "2.0.0")
    _make_release(tmp_path, "core", "1.9.0")
    link = tmp_path / "core" / "current"
    link.unlink()
    link.symlink_to(tmp_path / "core" / "releases" / "2.0.0")
    probe = ScriptedProbe([False])
    env = GateEnv(tmp_path, probe)
    res = sa.execute_command({"id": 2, "claimedBy": 1,
                              "commandType": "platform-service-rollback",
                              "payload": {"service": "core", "target_version": "1.9.0",
                                          "artifact_url": "https://m/core-1.9.0.jar",
                                          "sha256": env.sha,
                                          "health_url": "http://h/health",
                                          "health_probe_count": 2,
                                          "health_probe_interval_seconds": 0,
                                          "health_startup_budget_seconds": 1}}, env)
    assert not res.success
    # recovery re-points at the pre-operation release (the newer one here)
    assert link.resolve().name == "2.0.0"
    assert env.spawn_calls == [["systemctl", "restart", "tp-core.service"]] * 2


# ── catalogue rows carry sane probe defaults (data, not code branches) ───────

def test_catalogue_rows_carry_backend_health_urls_by_default():
    """Four backend families get /actuator/health on their standard ports;
    frontend stays url-less (nginx static, gate skipped until a payload
    overrides) — adding a family = add a row, R-1 discipline."""
    urls = {row.family: getattr(row, "health_url", None) for row in sa.MANAGED_SERVICES}
    assert urls["core"] == "http://127.0.0.1:8083/actuator/health"
    assert urls["admin"] == "http://127.0.0.1:8081/actuator/health"
    assert urls["api"] == "http://127.0.0.1:8082/actuator/health"
    assert urls["ai-service"] == "http://127.0.0.1:18084/actuator/health"
    assert urls["frontend"] is None
