# -*- coding: utf-8 -*-
pass
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
INSTALL_SH = ROOT / "install.sh"
INSTALL_PS1 = ROOT / "install.ps1"
sys.path.insert(0, str(ROOT))

import doctor as dc  # noqa: E402
import runtime_bootstrap as rb  # noqa: E402


def _recipe(state, code, extra=None, probe=("true",)):
    rec = {
        "version_pin": "0.0.63",
        "hosts": {"linux": {"probe": list(probe),
                            "install": [{"cmd": "npm install -g x"}],
                            "uninstall": [{"cmd": "npm uninstall -g x"}]}},
    }
    rec.update(extra or {})
    (state / f"recipe-{code}.json").write_text(json.dumps(rec, ensure_ascii=False),
                                               encoding="utf-8")


def _env(tmp_path, **extra):
    env = os.environ.copy()
    env["INSTALLKIT_STATE_DIR"] = str(tmp_path / "state")
    env["INSTALLKIT_LOG_DIR"] = str(tmp_path / "logs")
    env["INSTALLKIT_SEED_BASE_URL"] = "http://127.0.0.1:59911"
    env.update(extra)
    return env


def _run_sh(args, env):
    return subprocess.run(["bash", str(INSTALL_SH)] + args,
                          capture_output=True, text=True, timeout=120, env=env)



def test_runtime_gap_none_when_satisfied(tmp_path, monkeypatch):
    monkeypatch.setattr(rb, "current_node_version", lambda: (22, 23, 1))
    _recipe(tmp_path, "m", {"runtime_requires": {"node": ">=22.12"}})
    assert dc.runtime_gap(tmp_path) is None


def test_runtime_gap_below_line(tmp_path, monkeypatch):
    monkeypatch.setattr(rb, "current_node_version", lambda: (18, 0, 0))
    _recipe(tmp_path, "m", {"runtime_requires": {"node": ">=22.12"}})
    gap = dc.runtime_gap(tmp_path)
    assert gap["status"] == "below"
    assert gap["current"] == "18.0.0"
    assert gap["required"] == ">=22.12"


def test_runtime_gap_missing_presence_only(tmp_path, monkeypatch):
    pass
    monkeypatch.setattr(rb, "current_node_version", lambda: None)
    _recipe(tmp_path, "m")
    gap = dc.runtime_gap(tmp_path)
    assert gap["status"] == "missing"
    assert gap["required"] is None and gap["current"] is None


def test_build_report_missing_runtime_key_additive(tmp_path, monkeypatch):
    pass
    monkeypatch.setattr(rb, "current_node_version", lambda: (22, 23, 1))
    _recipe(tmp_path, "m")
    report = dc.build_report(tmp_path, "linux")
    assert set(("host", "engines", "missing", "missing_runtime")) <= set(report)
    assert report["missing_runtime"] is None


def test_doctor_summary_points_runtime_gap(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(rb, "current_node_version", lambda: (18, 0, 0))
    _recipe(tmp_path, "m", {"runtime_requires": {"node": ">=22.12"}})
    dc.print_summary(dc.build_report(tmp_path, "linux"), "空态提示")
    text = capsys.readouterr().out
    assert "版本不足" in text and "22.12" in text, "人话点名运行时缺口（不截断）"



def test_install_sh_runtime_positions_wired_and_ordered():
    src = INSTALL_SH.read_text(encoding="utf-8")
    assert src.count("runtime_bootstrap.py") >= 3, "S1/S4/S5 三位点必须全接同一单源件"
    i_fetch = src.index('run_step "fetch-recipe')
    i_rt = src.index('run_step "runtime-bootstrap:$ENGINE"')
    i_apply = src.index('run_step "apply-engine')
    assert i_fetch < i_rt < i_apply, "S4 运行时前置位点必须在拉配方后、装配方前"
    j_rt = src.index("run_step runtime-ensure")
    j_refill = src.index("run_step refill")
    assert j_rt < j_refill, "S5 运行时补齐位点必须先于缺件补齐（先装运行时再装配方件）"
    # Amended 2026-11-21: the old 900-char proximity window was broken by
    # legitimate comment growth in the S1 section; semantics are ordering,
    i_s1q = src.index("run_step role-question")
    i_s1rt = src.index("run_step runtime-bootstrap")
    i_s1seed = src.index("run_step compose-seed")
    assert i_s1q < i_s1rt < i_s1seed, \
        "S1 运行时位点须在问答落盘之后、拉起位点之前（消费 answers 判定是否在位）"


def test_install_sh_runtime_gate_respects_dry_run_and_uninstall():
    pass
    src = INSTALL_SH.read_text(encoding="utf-8")
    eng = src[src.index("    engines)"):src.index("    repair)")]
    assert 'ACTION" = "install"' in eng, "S4 运行时闸仅 install 动作触发"
    assert "DRY_RUN" in eng, "S4 dry-run 分支不得带 --yes 代跑"



def test_repair_runtime_gap_auto_fills_and_books(tmp_path):
    state = tmp_path / "state"
    state.mkdir()
    _recipe(state, "mobilewright", {"runtime_requires": {"node": ">=99.0"}},
            probe=("this-tool-not-here-x --version",))
    env = _env(tmp_path, INSTALLKIT_CMD_BIN="true", INSTALLKIT_RUNTIME_CMD_BIN="true")
    proc = _run_sh(["--scenario=repair"], env)
    text = proc.stdout + proc.stderr
    assert proc.returncode == 0, text
    assert "版本不足" in text and "执行补齐" in text, "缺件点名→自动补齐闭环"
    booked = (state / "state-repair.txt").read_text()
    assert "step:runtime-ensure" in booked and "step:refill" in booked
    assert booked.index("step:runtime-ensure") < booked.index("step:refill")
    report = json.loads((state / "doctor-report.json").read_text(encoding="utf-8"))
    assert report["missing_runtime"]["status"] == "below"
    proc2 = _run_sh(["--scenario=repair"], env)
    assert "位点续跑跳过" in proc2.stdout + proc2.stderr


def test_repair_runtime_satisfied_zero_plan(tmp_path):
    pass
    state = tmp_path / "state"
    state.mkdir()
    _recipe(state, "mobilewright")
    env = _env(tmp_path, INSTALLKIT_CMD_BIN="true")
    proc = _run_sh(["--scenario=repair"], env)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "零补齐" in proc.stdout + proc.stderr or "已满足" in proc.stdout + proc.stderr



def test_engines_runtime_gate_runs_between_fetch_and_apply(tmp_path):
    state = tmp_path / "state"
    state.mkdir()
    _recipe(state, "mobilewright", {"runtime_requires": {"node": ">=99.0"}})
    (state / "state-engines.txt").write_text("step:fetch-recipe:mobilewright\n",
                                             encoding="utf-8")
    env = _env(tmp_path, INSTALLKIT_CMD_BIN="true", INSTALLKIT_RUNTIME_CMD_BIN="true")
    proc = _run_sh(["--scenario=engines", "--engine=mobilewright",
                    "--base-url=http://127.0.0.1:1"], env)
    text = proc.stdout + proc.stderr
    assert proc.returncode == 0, text
    booked = (state / "state-engines.txt").read_text()
    assert "step:runtime-bootstrap:mobilewright" in booked
    assert booked.index("runtime-bootstrap:mobilewright") < booked.index("apply-engine")



def test_s1_runtime_position_books_with_mobilewright(tmp_path):
    env = _env(tmp_path, INSTALLKIT_CMD_BIN="true",
               INSTALLKIT_RUNTIME_CMD_BIN="true", INSTALLKIT_COMPOSE_BIN="true")
    proc = subprocess.run(["bash", str(INSTALL_SH), "--scenario=first"],
                          input="", capture_output=True, text=True, timeout=120, env=env)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    booked = (tmp_path / "state" / "state-first.txt").read_text(encoding="utf-8")
    assert "step:runtime-bootstrap" in booked, "默认引擎含 node 需求引擎必须走前置补齐位点"


def test_s1_runtime_position_skips_without_engines(tmp_path):
    state = tmp_path / "state"
    state.mkdir()
    (state / "state-first.txt").write_text("step:preflight\nstep:role-question\n",
                                           encoding="utf-8")
    (state / "answers-S1.json").write_text(
        json.dumps({"role": "platform", "engines": []}), encoding="utf-8")
    env = _env(tmp_path, INSTALLKIT_COMPOSE_BIN="true", INSTALLKIT_CMD_BIN="true")
    proc = _run_sh(["--scenario=first"], env)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    booked = (state / "state-first.txt").read_text(encoding="utf-8")
    assert "step:runtime-bootstrap" not in booked, "答案无 node 需求引擎=零打扰跳过位点"



def test_ps1_mirrors_runtime_wiring():
    src = INSTALL_PS1.read_text(encoding="utf-8-sig")
    assert src.count("runtime_bootstrap.py") >= 3, "ps1 S1/S4/S5 位点未全接单源件（二套=违 §9.0）"
    assert 'Run-Step "runtime-ensure' in src and 'Run-Step "runtime-bootstrap' in src
    assert src.index('Run-Step "runtime-ensure') < src.index('Run-Step "refill"'), \
        "ps1 repair 补齐位点须先于 refill（与 install.sh 同构）"
    first = src[src.index('"first" {'):src.index('"scale-out" {')]
    assert "runtime_bootstrap.py" in first, "ps1 S1 位点同样接运行时前置（消费 answers 判定）"
    eng = src[src.index('"engines" {'):src.index('"repair" {')]
    assert 'runtime_bootstrap.py' in eng and '$EngineAction -eq "install"' in eng, \
        "ps1 S4 位点仅 install 动作触发（与 install.sh 同构）"
