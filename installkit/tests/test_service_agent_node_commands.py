# -*- coding: utf-8 -*-
"""service-agent consumption of host start-stop and self-check commands
(03 §2.9 value domain, T294b pack B).

Locked semantics (mirrors the t354/t355 discipline):
- platform-service-control = systemctl <action> on one managed family unit;
  action domain start/stop/restart is fail-closed at the agent, a missing or
  out-of-domain key is refused with the valid values named in the receipt;
- node-self-check = one honest line per managed service (unit state via
  systemctl is-active, version from the local release ledger, health probe
  where an address is configured). A red cell in the report is still a
  successfully delivered check — never a fake green, never a command failure;
- both types are agent-consumed only: engine-*/scale-in remain on the
  executor runner face and keep being refused here, and the refusal now lists
  the extended agent domain.

L2 injects spawn/probe (systemctl + actuator are environment facilities);
the real chain is proven at the pack-level L3/smoke window per layering.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import service_agent as sa

CONTROL = "platform-service-control"
SELFCHECK = "node-self-check"


class _Env:
    """Injectable execution environment (spawn is scriptable per argv)."""

    def __init__(self, root, spawn=None, probe=None):
        self.root = root
        self.spawn_calls = []
        self.fetch_calls = []
        self._spawn = spawn or (lambda argv: 0)
        self.probe = probe or (lambda url: True)
        self.sleep = lambda seconds: None

    def fetch(self, url):
        self.fetch_calls.append(url)
        return b"unused"

    def spawn(self, argv):
        self.spawn_calls.append(argv)
        return self._spawn(argv)


def _cmd(ctype, **payload):
    return {"id": 9, "claimedBy": 3, "commandType": ctype, "payload": payload}


def _core_release(tmp):
    pass
    row = next(r for r in sa.MANAGED_SERVICES if r.family == "core")
    release = tmp / row.family / "releases" / "1.2.3"
    release.mkdir(parents=True, exist_ok=True)
    (release / "build-info.properties").write_text(
        "build.version=1.2.3\n", encoding="utf-8")
    current = tmp / row.family / "current"
    current.symlink_to(release)


# ── platform-service-control ─────────────────────────────────────────────────

def test_control_start_invokes_systemctl_start_with_named_receipt(tmp_path):
    env = _Env(tmp_path)
    res = sa.execute_command(_cmd(CONTROL, service="core", action="start"), env)
    assert res.success, res.detail
    assert ["systemctl", "start", "tp-core.service"] in env.spawn_calls
    assert "启动" in res.detail and "tp-core.service" in res.detail
    assert not res.exit_after_ack, "a service start never retires the agent"


def test_control_stop_and_restart_map_to_their_verbs(tmp_path):
    for action, verb in (("stop", "停止"), ("restart", "重启")):
        env = _Env(tmp_path)
        res = sa.execute_command(_cmd(CONTROL, service="admin", action=action), env)
        assert res.success, res.detail
        unit = next(r.unit for r in sa.MANAGED_SERVICES if r.family == "admin")
        assert ["systemctl", action, unit] in env.spawn_calls
        assert verb in res.detail


def test_control_missing_action_fails_closed_and_names_the_domain(tmp_path):
    res = sa.execute_command(_cmd(CONTROL, service="core"), _Env(tmp_path))
    assert not res.success
    assert "action" in res.detail
    assert "start" in res.detail and "stop" in res.detail and "restart" in res.detail


def test_control_unknown_action_is_refused(tmp_path):
    res = sa.execute_command(_cmd(CONTROL, service="core", action="pause"), _Env(tmp_path))
    assert not res.success
    assert "pause" in res.detail, "refusal must name the offending value"


def test_control_missing_service_is_refused_with_family_list(tmp_path):
    res = sa.execute_command(_cmd(CONTROL, action="start"), _Env(tmp_path))
    assert not res.success
    assert "core" in res.detail, "valid family codes must be listed for the operator"


def test_control_systemctl_failure_lands_honest_receipt(tmp_path):
    env = _Env(tmp_path, spawn=lambda argv: 5)
    res = sa.execute_command(_cmd(CONTROL, service="core", action="stop"), env)
    assert not res.success
    assert "5" in res.detail and "tp-core.service" in res.detail


# ── node-self-check ──────────────────────────────────────────────────────────

def test_self_check_reports_every_service_honestly(tmp_path):
    _core_release(tmp_path)
    admin_unit = next(r.unit for r in sa.MANAGED_SERVICES if r.family == "admin")

    def spawn(argv):
        # one stopped family among otherwise-active units
        return 0 if argv[-1] != admin_unit else 3

    env = _Env(tmp_path, spawn=spawn)
    res = sa.execute_command(_cmd(SELFCHECK), env)
    assert res.success, "a check that found a red cell is itself a delivered command"
    for row in sa.MANAGED_SERVICES:
        assert row.family in res.detail
    assert "运行中" in res.detail
    assert "已停止" in res.detail, "the stopped unit must show up, never be folded into green"
    assert "未知" in res.detail, "families without a local release report an honest unknown"


def test_self_check_single_service_filter(tmp_path):
    env = _Env(tmp_path)
    res = sa.execute_command(_cmd(SELFCHECK, service="core"), env)
    assert res.success
    assert "tp-core.service" in " ".join(
        str(a) for a in env.spawn_calls) or "core" in res.detail
    other_unit = next(r.unit for r in sa.MANAGED_SERVICES if r.family == "api")
    assert all(a[-1] != other_unit for a in env.spawn_calls), \
        "a filtered check must not spawn systemctl for other families"


def test_self_check_unknown_service_code_refused(tmp_path):
    res = sa.execute_command(_cmd(SELFCHECK, service="mysqld"), _Env(tmp_path))
    assert not res.success
    assert "mysqld" in res.detail and "core" in res.detail


# ── dispatch domain extension ────────────────────────────────────────────────

def test_unknown_type_refusal_lists_the_extended_agent_domain():
    res = sa.execute_command(_cmd("engine-install", engines=["X"]), _Env(Path(".")))
    assert not res.success
    assert CONTROL in res.detail and SELFCHECK in res.detail, \
        "the fail-closed refusal must enumerate everything service-agent does accept"
