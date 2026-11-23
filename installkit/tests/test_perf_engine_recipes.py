# -*- coding: utf-8 -*-
""" contract tests for the k6/Gatling install recipes (GA-4 supply
side, static files only — no DB contact).

Pinned semantics:
- recipes must stay consumable by recipe.py (probe/install/uninstall per host,
 three host profiles complete);
- K6 official asset names were probed live (HTTP 200) on 2026-10-25 and then
 frozen here; URLs must agree with version_pin (no drifted dead links);
- the Gatling AGPL body never ships in the repository — recipes carry only
 download commands plus manual_steps guidance;
- the two executor capability gates (capabilities.py posture) are mirrored in
 the probe commands: K6 = k6 on PATH; GATLING = java>=17 plus GATLING_LIB dir.
"""
import json
from pathlib import Path

import pytest
import recipe as rp

RECIPE_DIR = Path(__file__).resolve().parents[1] / "recipes"

K6_PIN = "v2.3.0"
GATLING_PIN = "3.16.0"


def _load(name):
    path = RECIPE_DIR / name
    assert path.is_file(), "recipe must ship in-repo: %s" % path
    return json.loads(path.read_text(encoding="utf-8"))


@pytest.fixture(params=["recipe-perf-k6.json", "recipe-perf-gatling.json"])
def perf_recipe(request):
    return _load(request.param)


def test_recipes_parse_and_cover_three_hosts(perf_recipe):
    hosts = perf_recipe["hosts"]
    assert set(hosts) == {"linux", "macos", "windows"}, "三宿主画像齐（S 链口径）"
    for host in hosts:
        assert rp.commands_for(perf_recipe, "probe", host), "%s probe 命令非空" % host
        assert rp.commands_for(perf_recipe, "install", host), "%s install 命令非空" % host
        assert rp.commands_for(perf_recipe, "uninstall", host), "%s uninstall 命令非空" % host


def test_upgrade_action_reuses_install_section(perf_recipe):
    # §11.0 U1 semantics: upgrade = reinstall to the pinned version; with no
    # explicit upgrade section it must reuse install (one ledger, not two).
    cmds = rp.commands_for(perf_recipe, "upgrade", "linux")
    assert cmds == rp.commands_for(perf_recipe, "install", "linux")


def test_k6_recipe_pins_official_assets_that_exist():
    rec = _load("recipe-perf-k6.json")
    assert rec["version_pin"] == K6_PIN
    install = " ".join(rp.commands_for(rec, "install", "linux"))
    assert "k6-%s-linux-amd64.tar.gz" % K6_PIN in install
    assert install.count("github.com/grafana/k6/releases/download/") == 1
    mac = " ".join(rp.commands_for(rec, "install", "macos"))
    # Live probe correction (2026-10-25): macOS ships .zip, not .tar.gz —
    # the tar.gz form 404s, so the recipe must pin the real asset name.
    assert "k6-%s-macos-amd64.zip" % K6_PIN in mac


def test_gatling_body_never_ships_and_capability_two_gates_are_probed():
    rec = _load("recipe-perf-gatling.json")
    assert rec["version_pin"] == GATLING_PIN
    joined = json.dumps(rec, ensure_ascii=False)
    assert "gatling-bundle-%s.zip" % GATLING_PIN in joined, "download command pins the official bundle asset"
    # The AGPL engine body never ships: only the .json recipe may live in-repo.
    artifacts = list(RECIPE_DIR.glob("*gatling*"))
    assert all(p.suffix == ".json" for p in artifacts)
    probe = " ".join(rp.commands_for(rec, "probe", "linux"))
    assert "java" in probe and "GATLING_LIB" in probe, "mirrors the executor two-gate check"
    steps = " ".join(rp.manual_steps(rec))
    assert "GATLING_LIB" in steps and "AGPL" in steps, "manual steps carry the placement guidance"


def test_doctor_examine_reports_missing_not_invalid(tmp_path, perf_recipe):
    import doctor
    # A structurally valid recipe (hosts + version_pin) must land in the
    # ok/missing states — never recipe-invalid (host coverage judgement works).
    status, _probes, _reason = doctor.examine(perf_recipe, "linux")
    assert status in ("ok", "missing"), "valid recipe only reaches ok/missing: %s" % status
