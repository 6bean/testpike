# -*- coding: utf-8 -*-
""" pack B: host service-agent unit tests (03 §2.8/§2.9 agent consumption face).

Locked semantics (design doc §7 + 16 row, review note R-1):
- managed-service catalogue is DATA (five rows this batch); the agent code carries
 no per-service branches;
- platform-service upgrade/rollback: sha256 mismatch = refuse the swap, leave a
 plain-word trace, never restart the unit;
- unknown command type / missing payload keys = fail-closed honest rejection
 (mirrors executor command_consumer discipline, no silent success);
- agent self-upgrade = land the new package + mark exit-after-ack (systemd
 Restart=always relaunches the new version; in-process self-replacement is
 forbidden);
- component report assembly: probe failure = honest absence, never a placeholder
 version; empty list = key omitted (old-core zero drift).

L2 injects fetch/spawn (network + systemctl are environment facilities); the
real chain is proven at pack C L3/smoke per the verification-layering rule.
"""
import json
import hashlib
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest

import service_agent as sa


# ── catalogue (R-1: adding a platform service = edit rows, not code) ─────────

def test_catalogue_six_platform_families_as_data():
    families = [row.family for row in sa.MANAGED_SERVICES]
    assert families == ["core", "admin", "api", "ai-service", "mock-server", "frontend"]
    # every row is pure data: family + systemd unit + release layout
    for row in sa.MANAGED_SERVICES:
        assert row.unit and row.layout in (sa.LAYOUT_BUILD_INFO, sa.LAYOUT_DIST_VERSION)


# ── component report assembly ────────────────────────────────────────────────

def _make_release(tmp, family, version=None, layout=None):
    """Materialize one managed service release dir under a fake install root."""
    row = layout or next(r for r in sa.MANAGED_SERVICES if r.family == family)
    current = tmp / family / "current"
    release = tmp / family / "releases" / (version or "1.2.3")
    release.mkdir(parents=True, exist_ok=True)
    if row.layout == sa.LAYOUT_BUILD_INFO:
        (release / "META-INF" / "build-info").mkdir(parents=True, exist_ok=True)
        # not extracted: agent reads the jar's build-info.properties entry;
        # sidecar file mirrors what CI writes next to the link target.
        (release / "build-info.properties").write_text(
            "build.version=%s\nbuild.time=2026-10-13T00:00:00.000Z\n" % version,
            encoding="utf-8")
    else:
        (release / "version.json").write_text(
            json.dumps({"version": version}), encoding="utf-8")
    current.parent.mkdir(parents=True, exist_ok=True)
    if current.is_symlink() or current.exists():
        current.unlink()
    current.symlink_to(release)
    return row


def test_collect_components_reads_current_release_versions(tmp_path):
    for family, version in (("core", "1.2.3"), ("admin", "1.2.3"),
                            ("api", "1.2.4"), ("ai-service", "1.2.3")):
        _make_release(tmp_path, family, version)
    # frontend is a dist-layout row
    row = next(r for r in sa.MANAGED_SERVICES if r.family == "frontend")
    _make_release(tmp_path, "frontend", "0.9.1", row)
    out = sa.collect_components(tmp_path)
    got = {r["family"]: r["currentVersion"] for r in out}
    assert got == {"core": "1.2.3", "admin": "1.2.3", "api": "1.2.4",
                   "ai-service": "1.2.3", "frontend": "0.9.1"}
    # every row carries exactly the two protocol keys
    for r in out:
        assert set(r.keys()) == {"family", "currentVersion"}


def test_collect_components_probe_failure_omits_family(tmp_path):
    """Missing link/junk version file = honest absence (no placeholder lies)."""
    _make_release(tmp_path, "core", "1.0.0")
    # admin dir exists but current link is dangling; others simply absent
    (tmp_path / "admin").mkdir()
    (tmp_path / "admin" / "current").symlink_to(tmp_path / "admin" / "nope")
    out = sa.collect_components(tmp_path)
    assert [r["family"] for r in out] == ["core"]


def test_agent_self_version_reported_as_service_agent(tmp_path):
    rows = sa.build_report(tmp_path, agent_version=sa.AGENT_VERSION)
    assert {"family": sa.FAMILY_SERVICE_AGENT, "currentVersion": sa.AGENT_VERSION} in rows


# ── command execution: platform-service upgrade ──────────────────────────────

class _Env:
    """Injectable execution environment (value object; L2 stands in fetch/spawn)."""

    def __init__(self, root, artifact=b"payload", spawn=None):
        self.root = root
        self.artifact = artifact
        self.fetch_calls = []
        self.spawn_calls = []
        # health-gate facilities : L2 never touches a real
        # actuator endpoint — inject a scripted probe and a no-op sleep
        self.probe = lambda url: True
        self.sleep = lambda seconds: None
        self._sha = hashlib.sha256(artifact).hexdigest()

    def fetch(self, url):
        self.fetch_calls.append(url)
        return self.artifact

    def spawn(self, argv):
        self.spawn_calls.append(argv)
        return 0

    @property
    def artifact_sha(self):
        return self._sha


def _cmd(ctype, **payload):
    return {"id": 9, "claimedBy": 3, "commandType": ctype, "payload": payload}


def test_upgrade_happy_path_swaps_link_and_restarts_unit(tmp_path):
    _make_release(tmp_path, "admin", "1.0.0")
    env = _Env(tmp_path)
    res = sa.execute_command(_cmd(
        "platform-service-upgrade", service="admin", target_version="2.0.0",
        artifact_url="https://mirror/releases/2.0.0/admin.jar",
        sha256=env.artifact_sha), env)
    assert res.success, res.detail
    # release landed under the versioned dir and the current link moved atomically
    link = tmp_path / "admin" / "current"
    assert link.resolve().name == "2.0.0"
    assert (link / "admin.jar").read_bytes() == b"payload"
    # restart targets the catalogued systemd unit and nothing else
    assert ["systemctl", "restart", "tp-admin.service"] in env.spawn_calls


def test_upgrade_digest_mismatch_refuses_swap_keeps_old_version(tmp_path):
    _make_release(tmp_path, "admin", "1.0.0")
    env = _Env(tmp_path, artifact=b"corrupted build artifact")
    res = sa.execute_command(_cmd(
        "platform-service-upgrade", service="admin", target_version="2.0.0",
        artifact_url="https://mirror/admin.jar",
        sha256="0" * 64), env)
    assert not res.success
    # plain-word refusal names the digest mismatch (trace for the operator)
    assert "校验" in res.detail or "digest" in res.detail.lower()
    # old release still current, unit never restarted
    assert (tmp_path / "admin" / "current").resolve().name == "1.0.0"
    assert env.spawn_calls == []


def test_upgrade_unknown_service_refused(tmp_path):
    env = _Env(tmp_path)
    res = sa.execute_command(_cmd(
        "platform-service-upgrade", service="mysqld", target_version="2.0.0",
        artifact_url="https://mirror/x", sha256=env.artifact_sha), env)
    assert not res.success
    # plain-word refusal names the valid family set sourced from the catalogue
    # (mock-server is a managed family since ; a stray code like mysqld
    # must still be refused)
    assert "admin" in res.detail


def test_missing_payload_keys_fail_closed(tmp_path):
    env = _Env(tmp_path)
    for payload in (
        {"target_version": "1", "artifact_url": "u", "sha256": env.artifact_sha},      # no service
        {"service": "core", "artifact_url": "u", "sha256": env.artifact_sha},          # no version
        {"service": "core", "target_version": "1", "sha256": env.artifact_sha},        # no url
        {"service": "core", "target_version": "1", "artifact_url": "u"},               # no digest
        {"service": "core", "target_version": "1", "artifact_url": "u", "sha256": "zz"},  # bad hex
    ):
        res = sa.execute_command(_cmd("platform-service-upgrade", **payload), env)
        assert not res.success, payload
    assert env.spawn_calls == []


def test_unknown_command_type_refused_no_side_effects(tmp_path):
    env = _Env(tmp_path)
    res = sa.execute_command(_cmd("engine-install", engines=["playwright"]), env)
    assert not res.success
    assert env.fetch_calls == [] and env.spawn_calls == []


def test_rollback_consumes_pinned_target_version(tmp_path):
    """Rollback = upgrade mechanics against the reverse-pinned version (no guessing)."""
    _make_release(tmp_path, "core", "2.0.0")
    _make_release(tmp_path, "core", "1.9.0")  # previous release still on disk
    # point current back at 2.0.0 first so rollback visibly moves it
    link = tmp_path / "core" / "current"
    link.unlink()
    link.symlink_to(tmp_path / "core" / "releases" / "2.0.0")
    env = _Env(tmp_path)
    res = sa.execute_command(_cmd(
        "platform-service-rollback", service="core", target_version="1.9.0",
        artifact_url="https://mirror/core-1.9.0.jar", sha256=env.artifact_sha), env)
    assert res.success, res.detail
    assert link.resolve().name == "1.9.0"
    assert ["systemctl", "restart", "tp-core.service"] in env.spawn_calls


# ── agent self-upgrade: land + exit-after-ack (never self-replace) ───────────

def test_agent_upgrade_lands_package_and_marks_restart_exit(tmp_path):
    state = tmp_path / "state"
    env = _Env(tmp_path)
    env.state_dir = state
    res = sa.execute_command(_cmd(
        "agent-upgrade", target_version="1.1.0",
        artifact_url="https://mirror/service_agent-1.1.0.py",
        sha256=env.artifact_sha), env)
    assert res.success
    # new package landed in the staging slot the systemd unit ExecStart reads
    landed = state / sa.AGENT_STAGING_FILE
    assert landed.read_bytes() == b"payload"
    # no systemctl call on itself, no in-process exec: exit flag is the mechanism
    assert env.spawn_calls == []
    assert res.exit_after_ack is True


def test_agent_rollback_clears_staged_package(tmp_path):
    state = tmp_path / "state"
    state.mkdir()
    (state / sa.AGENT_STAGING_FILE).write_bytes(b"old")
    env = _Env(tmp_path, artifact=b"rolled-back agent bytes")
    env.state_dir = state
    res = sa.execute_command(_cmd(
        "agent-rollback", target_version="0.9.0",
        artifact_url="https://mirror/service_agent-0.9.0.py",
        sha256=env.artifact_sha), env)
    assert res.success
    assert (state / sa.AGENT_STAGING_FILE).read_bytes() == b"rolled-back agent bytes"
    assert res.exit_after_ack is True


def test_agent_upgrade_missing_state_dir_refused(tmp_path):
    env = _Env(tmp_path)  # no state_dir attribute at all
    res = sa.execute_command(_cmd(
        "agent-upgrade", target_version="1.1.0",
        artifact_url="https://mirror/x.py", sha256=env.artifact_sha), env)
    assert not res.success


# ── heartbeat body assembly (trailing components key) ────────────────────────

def test_heartbeat_omits_components_key_when_empty(tmp_path):
    # empty platform probe => key absent entirely (old-core zero drift posture)
    assert sa.collect_components(tmp_path) == []
    assert sa.with_components({}, sa.collect_components(tmp_path)) == {}


def test_with_components_attaches_only_nonempty(tmp_path):
    _make_release(tmp_path, "core", "3.2.1")
    rows = sa.collect_components(tmp_path)
    out = sa.with_components({"step": "healthcheck"}, rows)
    assert out == {"step": "healthcheck", "components": rows}


# ── CLI surface (smoke entry the pack C systemd unit will exec) ──────────────

def test_once_command_wires_poll_execute_ack(tmp_path, monkeypatch):
    """Agent 'once' = poll (token form) -> execute -> progress+result receipts.

    Only the transport is stubbed here (L2 isolation of the api endpoint);
    command semantics run through the real execute_command above.
    """
    calls = []

    def fake_poll(base_url, token, fingerprint, limit):
        calls.append(("poll", base_url))
        return [_cmd("platform-service-upgrade", service="api",
                     target_version="5.0.0", artifact_url="https://m/api.jar",
                     sha256=hashlib.sha256(b"payload").hexdigest())]

    acks = []

    def fake_ack(base_url, command_id, claimer_id, success, detail, token, fingerprint):
        acks.append((command_id, success, detail, token, fingerprint))

    def fake_fetch(url):
        return b"payload"

    _make_release(tmp_path, "api", "4.0.0")
    monkeypatch.setattr(sa, "poll_commands", fake_poll)
    monkeypatch.setattr(sa, "ack_command", fake_ack)
    env = sa.build_env(root=tmp_path, fetch=fake_fetch, spawn=lambda argv: 0,
                       state_dir=tmp_path / "state",
                       probe=lambda url: True, sleep=lambda seconds: None)
    rc = sa.run_once(base_url="http://api:8082", token="t", env=env)
    assert rc == 0
    assert len(acks) == 1 and acks[0][1] is True
    assert (tmp_path / "api" / "current").resolve().name == "5.0.0"


def test_restart_spawn_failure_lands_honest_receipt(tmp_path):
    """L4 smoke evidence: a hanging systemctl (spawn raises) must NOT crash the
    agent — the command would sit claimed forever with no receipt. The swap
    already happened, so the failure words must name the unit for follow-up."""
    _make_release(tmp_path, "admin", "1.0.0")
    env = _Env(tmp_path)

    def exploding_spawn(argv):
        raise subprocess.TimeoutExpired(argv, 120)

    env.spawn = exploding_spawn
    res = sa.execute_command(_cmd(
        "platform-service-upgrade", service="admin", target_version="2.0.0",
        artifact_url="https://mirror/admin.jar", sha256=env.artifact_sha), env)
    assert not res.success
    # receipt names the unit and the failed restart (never a stack trace dump)
    assert "tp-admin.service" in res.detail
    assert "重启" in res.detail
