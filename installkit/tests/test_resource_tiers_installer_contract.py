# -*- coding: utf-8 -*-
"""T293b open-delivery installer contracts: resource tiers, component matrix,
checksum verification, unattended path and failure teaching in install.log.

L2 unit face (pytest subprocess + module import idiom of test_installkit.py).
The real container/machine install behavior lands with the clean-machine
verification package; this file pins the capability contracts only.
"""
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
INSTALL_SH = ROOT / "install.sh"
TIERS_PY = ROOT / "resource_tiers.py"
BOOTSTRAP_PY = ROOT / "s1_bootstrap.py"
sys.path.insert(0, str(ROOT))

import resource_tiers  # noqa: E402


# ---------------------------------------------------------------- tiers data
def test_three_tiers_defined_with_lightweight_default():
    assert set(resource_tiers.TIERS) == {"lightweight", "standard", "full"}
    assert resource_tiers.DEFAULT_TIER == "lightweight"
    # capability mapping grows monotonically: standard is a superset of
    # lightweight, full is a superset of standard (no component swaps out)
    assert set(resource_tiers.TIERS["lightweight"]["components"]) <= set(
        resource_tiers.TIERS["standard"]["components"])
    assert set(resource_tiers.TIERS["standard"]["components"]) <= set(
        resource_tiers.TIERS["full"]["components"])


def test_tier_requirements_are_matrix_sums_not_literals():
    for tier, spec in resource_tiers.TIERS.items():
        disk = sum(resource_tiers.COMPONENTS[c]["disk_mb"]
                   for c in spec["components"])
        mem = sum(resource_tiers.COMPONENTS[c]["mem_mb"]
                   for c in spec["components"])
        req = resource_tiers.tier_requirements(tier)
        assert req["disk_mb"] == disk
        assert req["mem_mb"] == mem


def test_render_tiers_text_carries_generated_numbers():
    text = resource_tiers.render_tiers_text()
    req = resource_tiers.tier_requirements("standard")
    assert "Lightweight" in text and "Standard" in text and "Full" in text
    # numbers shown to the user must equal the programmatic sums (GB, 1 decimal)
    assert resource_tiers._gb(req["disk_mb"]) in text
    assert resource_tiers._gb(req["mem_mb"]) in text
    # the machine's own free capacity is displayed as the decision anchor
    cap = resource_tiers.machine_capacity()
    assert cap["disk_gb"] >= 0 and cap["mem_gb"] > 0
    assert resource_tiers._gb(int(cap["disk_gb"] * 1024)) in text


def test_machine_capacity_is_measured_not_hardcoded(monkeypatch):
    calls = {"n": 0}
    import types

    def fake_disk_usage(_path):
        calls["n"] += 1
        return types.SimpleNamespace(total=1024**4, free=777 * 1024**3, used=0)

    monkeypatch.setattr("shutil.disk_usage", fake_disk_usage)
    cap = resource_tiers.machine_capacity()
    assert calls["n"] == 1
    assert abs(cap["disk_gb"] - 777) < 1


# ------------------------------------------------------------------- the ask
def test_ask_eof_takes_default_tier_and_merges_answers(tmp_path):
    answers_file = tmp_path / "answers-S1.json"
    answers_file.write_text(json.dumps({"role": "platform", "engines": ["minium"]}),
                            encoding="utf-8")
    answers = resource_tiers.ask(input_text="", answers_path=str(answers_file))
    assert answers["tier"] == resource_tiers.DEFAULT_TIER
    # merge, never rewrite: earlier wizard keys survive
    saved = json.loads(answers_file.read_text(encoding="utf-8"))
    assert saved["role"] == "platform" and saved["engines"] == ["minium"]
    assert saved["tier"] == "lightweight"


def test_ask_accepts_named_tier_and_cli_override(tmp_path):
    answers_file = tmp_path / "answers-S1.json"
    answers = resource_tiers.ask(input_text="标准\n", answers_path=str(answers_file))
    assert answers["tier"] == "standard"
    answers = resource_tiers.ask(input_text="lightweight\n", tier="full",
                                answers_path=str(answers_file))
    # explicit --tier from CLI wins over what the stream said
    assert answers["tier"] == "full"


def test_ask_junk_falls_back_human_no_crash(tmp_path, capsys):
    answers_file = tmp_path / "answers-S1.json"
    answers = resource_tiers.ask(input_text="铂金版\n", answers_path=str(answers_file))
    assert answers["tier"] == resource_tiers.DEFAULT_TIER
    out = capsys.readouterr().out
    assert "铂金版" in out and "default" in out  # names the bad input, then continues


# ------------------------------------------------------- component matrix plan
def test_install_matrix_steps_carry_probe_install_rollback():
    steps = resource_tiers.install_matrix("full")
    assert steps, "full tier must produce a plan"
    for step in steps:
        for key in ("id", "probe", "install", "rollback"):
            assert key in step and step[key], "%s missing %s" % (step, key)


def test_installed_components_are_skipped_idempotently():
    full_ids = [s["id"] for s in resource_tiers.install_matrix("full")]
    probed = {s["id"]: (s["id"] != "prometheus") for s in resource_tiers.install_matrix("full")}
    plan = resource_tiers.plan_execution("full", installed_probe=probed)
    todo = [s["id"] for s in plan["run"]]
    skipped = [s["id"] for s in plan["skip"]]
    assert "prometheus" in todo and "prometheus" not in skipped, "未装件必须进 run 列"
    assert set(skipped) == set(full_ids) - {"prometheus"}
    assert set(todo) | set(skipped) == set(full_ids)


def test_rollback_stack_is_reverse_of_run_order():
    plan = resource_tiers.plan_execution("full")
    run_ids = [s["id"] for s in plan["run"]]
    assert plan["rollback_stack"] == list(reversed(run_ids))


# ------------------------------------------------------------------- checksum
def test_verify_sha256_pass_and_human_failure_path(tmp_path):
    import hashlib
    artifact = tmp_path / "engine-bundle.tgz"
    artifact.write_bytes(b"demo payload")
    good = hashlib.sha256(artifact.read_bytes()).hexdigest()
    assert resource_tiers.verify_sha256(str(artifact), good) is True
    with pytest.raises(ValueError) as exc:
        resource_tiers.verify_sha256(str(artifact), "0" * 64)
    msg = str(exc.value)
    # R63 three elements: names the file, the two digests, and where to fix
    assert "engine-bundle.tgz" in msg and "0" * 64 in msg and good[:8] in msg
    assert "installkit.mirror-base-url" in msg or "re-download" in msg.lower()


def test_matrix_artifacts_declare_sha256_fields():
    for name, comp in resource_tiers.COMPONENTS.items():
        art = comp.get("artifact")
        if art:
            assert set(art) >= {"url", "sha256"}, name


# ------------------------------------------------------------- install.sh CLI
def _read_install_sh():
    return INSTALL_SH.read_text(encoding="utf-8")


def test_install_sh_exposes_tier_and_unattended_flags():
    src = _read_install_sh()
    assert "--tier=" in src and "--unattended" in src
    assert "resource_tiers.py" in src, "wizard must consume the single-source tier module"
    # unattended must be the SAME code path (empty input stream), not a branch
    # that skips the module entirely
    assert "--unattended" in src and "ask" in src


def test_install_sh_failure_teaching_written_to_install_log(tmp_path):
    state = tmp_path / "state"
    logs = tmp_path / "logs"
    env = dict(os.environ)
    env.update({
        "INSTALLKIT_STATE_DIR": str(state),
        "INSTALLKIT_LOG_DIR": str(logs),
        "INSTALLKIT_SEED_BASE_URL": "http://127.0.0.1:59911",
        # fail the compose step on purpose to reach the teaching branch
        "INSTALLKIT_COMPOSE_BIN": "/bin/false",
    })
    proc = subprocess.run(
        ["bash", str(INSTALL_SH), "--scenario=first", "--unattended",
         "--restart"],
        env=env, capture_output=True, text=True, timeout=180,
        cwd=str(ROOT.parent))
    log_text = ""
    for f in sorted(logs.glob("*.log")):
        log_text += f.read_text(encoding="utf-8")
    # the step that failed must teach: resume command + repair hint + log path
    assert "guide:" in log_text or "resume from the checkpoint" in log_text, proc.stdout[-2000:] + proc.stderr[-2000:]
    assert "install.sh --scenario=first" in log_text
    assert "--scenario=repair" in log_text


# -------------------------------------------------- tier to compose profiles
def test_compose_plan_maps_tier_to_compose_profiles(tmp_path):
    sys.path.insert(0, str(ROOT))
    import s1_bootstrap

    def write(tier=None):
        p = tmp_path / ("answers-%s.json" % (tier or "none"))
        data = {"role": "platform"}
        if tier:
            data["tier"] = tier
        p.write_text(json.dumps(data), encoding="utf-8")
        return str(p)

    # Staged bring-up: middleware first, service profiles second (reconcile sits
    # between the two, see installkit/tests/test_compose_stage_order_contract.py).
    base = s1_bootstrap.compose_plan(write(), repo_root=str(ROOT.parent))
    assert len(base) == 1 and "--profile" not in " ".join(base[0]), \
        "legacy answers without tier keep the pre-T293b default surface"
    light = s1_bootstrap.compose_plan(write("lightweight"), repo_root=str(ROOT.parent))
    assert len(light) == 1 and "--profile" not in " ".join(light[0])
    std = s1_bootstrap.compose_plan(write("standard"), repo_root=str(ROOT.parent))
    assert len(std) == 2 and "--profile app" in " ".join(std[1]) \
        and "--profile" not in " ".join(std[0])
    fullp = s1_bootstrap.compose_plan(write("full"), repo_root=str(ROOT.parent))
    joined = " ".join(fullp[1])
    assert len(fullp) == 2 and "app" in fullp[1] and "observability" in fullp[1] \
        and "--profile" not in " ".join(fullp[0])


# ------------------------------------------------ executor supply-surface row
def test_component_matrix_has_executor_entry_measured_not_guessed():
    """The executor row exists and carries the image-build measurements.

 disk_mb=487: unpacked image footprint reported by `docker images` for
 testpike/executor:(archive layer sum 117,964,067 B, anchor
 IMG-EXEC2-SIZE-BYTES). mem_mb=64: peak RSS 47,528 KiB from a one-shot
 `import runner` probe executed inside the built image, rounded up to
 leave headroom for the heartbeat loop. Pinning the exact values here
 makes the measurement the contract - silent literal edits fail CI.
 """
    comp = resource_tiers.COMPONENTS["executor"]
    assert comp["tiers"] == ["full"]
    assert comp["disk_mb"] == 487
    assert comp["mem_mb"] == 64
    for key in ("probe", "install", "rollback"):
        assert comp[key], "component row needs the %s command slot" % key
    assert "tp-executor" in comp["install"] and "tp-fixtures" in comp["install"]


def test_executor_enters_only_the_full_tier_totals():
    light = set(resource_tiers.TIERS["lightweight"]["components"])
    std = set(resource_tiers.TIERS["standard"]["components"])
    full = set(resource_tiers.TIERS["full"]["components"])
    assert "executor" not in light and "executor" not in std
    assert "executor" in full
    extra = full - std
    assert extra <= {"prometheus", "grafana", "executor"}
    delta = (resource_tiers.tier_requirements("full")["disk_mb"]
             - resource_tiers.tier_requirements("standard")["disk_mb"])
    assert delta == sum(resource_tiers.COMPONENTS[c]["disk_mb"] for c in extra)
