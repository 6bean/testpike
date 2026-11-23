# -*- coding: utf-8 -*-
"""Compose staging order and privilege adaptation contracts.

Two lessons from the clean-machine matrix windows on 2026-11-22:

* r50 (ubuntu22.04 / standard tier): one ``docker compose up -d --profile app``
  starts middleware and the app tier together, so ``core`` comes up against a
  fresh RabbitMQ that has no custom vhost yet. Its healthcheck never turns
  green, compose aborts the whole command and the vhost reconciler — which ran
  after the plan — never gets a chance. The plan must therefore be staged:
  middleware first, reconcile next, service profiles last.

* r50 also showed the prerequisite install line ``sudo apt-get install ...``
  failing with exit 127 inside a root container image that ships no sudo. A
  root context does not need the elevation prefix, so the single command table
  adapts instead of every caller re-typing its own variant.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pkg_probe  # noqa: E402
import s1_bootstrap  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def _answers(tmp_path, tier=None):
    data = {"role": "platform"}
    if tier:
        data["tier"] = tier
    path = tmp_path / "answers-S1.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return str(path)


def _compose_args(tmp_path, tier=None):
    return argparse.Namespace(
        answers_file=_answers(tmp_path, tier), repo_root=str(ROOT))


@pytest.fixture(autouse=True)
def _no_compose_stub(monkeypatch):
    """Reconcile must be exercised for real here: the stub flag short-circuits it."""
    monkeypatch.delenv("INSTALLKIT_COMPOSE_BIN", raising=False)


# ------------------------------------------------------- staging shape
def test_middleware_stage_precedes_service_stage_for_projected_tiers(tmp_path):
    stages = s1_bootstrap.compose_stages(_answers(tmp_path, "standard"), repo_root=str(ROOT))
    assert [s["phase"] for s in stages] == ["middleware", "services"], \
        "the app tier must be a second stage, never folded into the middleware bring-up"
    joined_middleware = " ".join(stages[0]["cmd"])
    assert "--profile" not in joined_middleware, \
        "middleware stage starting with the app profile is exactly the ordering that stalls"
    joined_services = " ".join(stages[1]["cmd"])
    assert "--profile app" in joined_services, "the projected app profile rides stage two"


def test_lightweight_tier_has_no_service_stage(tmp_path):
    stages = s1_bootstrap.compose_stages(_answers(tmp_path, "lightweight"), repo_root=str(ROOT))
    assert [s["phase"] for s in stages] == ["middleware"], \
        "a tier that projects no compose profiles keeps the single bring-up command"
    # Legacy answers with no recorded tier keep the same single-stage shape.
    legacy = s1_bootstrap.compose_stages(_answers(tmp_path), repo_root=str(ROOT))
    assert [s["phase"] for s in legacy] == ["middleware"]


def test_compose_plan_is_the_flat_view_of_the_stages(tmp_path):
    plan = s1_bootstrap.compose_plan(_answers(tmp_path, "standard"), repo_root=str(ROOT))
    assert plan == [s["cmd"] for s in
                    s1_bootstrap.compose_stages(_answers(tmp_path, "standard"), repo_root=str(ROOT))], \
        "one source for both views (no second profile vocabulary)"


def test_executor_role_still_produces_no_stages(tmp_path):
    path = tmp_path / "answers.json"
    path.write_text(json.dumps({"role": "executor"}), encoding="utf-8")
    assert s1_bootstrap.compose_stages(str(path), repo_root=str(ROOT)) == []


# ------------------------------------------------------- wiring order
def test_reconcile_runs_between_the_two_stages(tmp_path, monkeypatch):
    order = []

    def fake_execute(cmd):
        joined = " ".join(cmd) if isinstance(cmd, list) else str(cmd)
        order.append("services" if "--profile" in joined else "middleware")
        return 0

    def fake_ensure(**kwargs):
        order.append("vhost")
        return True, ["vhost ready"]

    monkeypatch.setattr(s1_bootstrap, "_execute", fake_execute)
    monkeypatch.setattr(s1_bootstrap.mq_vhost, "ensure_vhost", fake_ensure)
    monkeypatch.setattr(s1_bootstrap, "four_services_guide",
                        lambda tier=None: order.append("guide") or ["g"])
    assert s1_bootstrap.cmd_compose(_compose_args(tmp_path, "standard")) == 0
    assert order == ["middleware", "vhost", "services", "guide"], \
        "the vhost has to exist before the app tier boots, and the guide comes last"


def test_reconcile_failure_blocks_the_service_stage(tmp_path, monkeypatch):
    order = []

    def fake_execute(cmd):
        joined = " ".join(cmd) if isinstance(cmd, list) else str(cmd)
        order.append("services" if "--profile" in joined else "middleware")
        return 0

    def fake_ensure(**kwargs):
        order.append("vhost")
        return False, ["cannot reach the management API"]

    monkeypatch.setattr(s1_bootstrap, "_execute", fake_execute)
    monkeypatch.setattr(s1_bootstrap.mq_vhost, "ensure_vhost", fake_ensure)
    assert s1_bootstrap.cmd_compose(_compose_args(tmp_path, "standard")) == 1
    assert order == ["middleware", "vhost"], \
        "starting core against a missing vhost only reproduces the failure it warns about"


def test_middleware_failure_stops_before_reconcile(tmp_path, monkeypatch):
    order = []
    monkeypatch.setattr(s1_bootstrap, "_execute", lambda cmd: order.append("middleware") or 1)
    monkeypatch.setattr(s1_bootstrap.mq_vhost, "ensure_vhost",
                        lambda **kwargs: order.append("vhost") or (True, []))
    assert s1_bootstrap.cmd_compose(_compose_args(tmp_path, "standard")) == 1
    assert order == ["middleware"], "no reconcile against a queue that never came up"


def test_service_stage_failure_is_reported(tmp_path, monkeypatch):
    def fake_execute(cmd):
        joined = " ".join(cmd) if isinstance(cmd, list) else str(cmd)
        return 0 if "--profile" not in joined else 1

    monkeypatch.setattr(s1_bootstrap, "_execute", fake_execute)
    monkeypatch.setattr(s1_bootstrap.mq_vhost, "ensure_vhost", lambda **kwargs: (True, []))
    assert s1_bootstrap.cmd_compose(_compose_args(tmp_path, "standard")) == 1


# ------------------------------------------------------- privilege adaptation
def test_root_context_drops_sudo_from_every_segment(monkeypatch):
    monkeypatch.setattr(pkg_probe, "is_effective_root", lambda: True)
    cmd = pkg_probe.install_command("apt", "docker")
    assert "sudo" not in cmd, "a root context runs the package manager directly"
    assert cmd.startswith("apt-get update"), "the rest of the single-source line is untouched"
    assert " && apt-get install" in cmd, "every segment loses the prefix, not just the first"


def test_non_root_context_keeps_the_sudo_guidance(monkeypatch):
    monkeypatch.setattr(pkg_probe, "is_effective_root", lambda: False)
    cmd = pkg_probe.install_command("apt", "node")
    assert cmd.startswith("sudo "), "guidance for an unprivileged host keeps the elevation prefix"


def test_alpine_line_has_no_sudo_to_drop(monkeypatch):
    monkeypatch.setattr(pkg_probe, "is_effective_root", lambda: True)
    assert pkg_probe.install_command("apk", "node") == "apk add --no-cache nodejs npm"


def test_apply_privilege_is_idempotent_for_root_only_lines():
    assert pkg_probe.apply_privilege("nvm install 22", root=True) == "nvm install 22"
    assert pkg_probe.apply_privilege("brew install node", root=True) == "brew install node"


# ------------------------------------------------------- matrix cell honesty
def test_readiness_loop_is_skipped_when_the_install_step_fails():
    sys.path.insert(0, str(ROOT / "tools" / "verification"))
    import clean_matrix_window

    command = clean_matrix_window.cell_command({"profile": "ubuntu2204", "tier": "standard"})
    assert "tries=0; until" not in command, \
        "a semicolon after the install step lets the readiness loop run on a dead cell"
    assert "tries=0 && until" in command

    # The fixed shape really short-circuits: the sentinel may never print.
    probe = subprocess.run(
        ["sh", "-c", "false && tries=0 && until false; do echo NOT-READY; break; done"],
        capture_output=True, text=True)
    assert "NOT-READY" not in probe.stdout, probe.stdout
