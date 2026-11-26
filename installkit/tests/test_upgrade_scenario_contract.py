# -*- coding: utf-8 -*-
"""S6 upgrade scenario contracts: fetch/baseline/migrate/images/rollout/probe.

Guards the shapes that make a data-preserving git upgrade safe: fast-forward
only history movement, the ledger baseline taken from the OLD commit's file
set (never the current one, or new migrations would be marked applied without
executing), profile single-sourcing from the install answers, and checkpoint
semantics (rerun on the same head is a no-op resume; a moved head re-walks).
"""

import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import migrate_apply  # noqa: E402
import upgrade_recipe as ur  # noqa: E402

INSTALL_SH = ROOT / "install.sh"


class _NS:
    def __init__(self, **kw):
        self.__dict__.update(kw)


# --- fetch ------------------------------------------------------------------

def test_fetch_refuses_uncommitted_changes(monkeypatch, tmp_path, capsys):
    monkeypatch.setattr(ur, "_git", lambda *a: " M core/pom.xml" if a[0] == "status" else "")
    rc = ur.cmd_fetch(_NS(state_dir=str(tmp_path)))
    assert rc == 2
    assert "uncommitted" in capsys.readouterr().err


def test_fetch_already_up_to_date_records_pulled_false(monkeypatch, tmp_path, capsys):
    def fake_git(*args):
        j = " ".join(args)
        return {"status --porcelain --untracked-files=no": "",
                "rev-parse --abbrev-ref HEAD": "main",
                "rev-parse HEAD": "c" * 40,
                "rev-parse origin/main": "c" * 40}.get(j, "")
    monkeypatch.setattr(ur, "_git", fake_git)
    monkeypatch.setattr(ur, "_git_stream", lambda *a: 0)
    rc = ur.cmd_fetch(_NS(state_dir=str(tmp_path)))
    assert rc == 0
    assert "PULLED=0" in capsys.readouterr().out
    heads = json.loads((tmp_path / ur.HEADS_NAME).read_text())
    assert heads["pulled"] is False and heads["old"] == heads["new"]


def test_fetch_refuses_non_fast_forward(monkeypatch, tmp_path, capsys):
    seen = {}

    def fake_git(*args):
        j = " ".join(args)
        if j.startswith("rev-parse HEAD"):
            return "a" * 40
        if j.startswith("rev-parse origin"):
            return "b" * 40
        if j.startswith("rev-parse --abbrev"):
            return "main"
        return ""

    def fake_run(cmd, **kw):
        if "merge-base" in cmd:
            return subprocess.CompletedProcess(cmd, 1)  # not an ancestor
        raise AssertionError("unexpected run: %s" % cmd)

    monkeypatch.setattr(ur, "_git", fake_git)
    monkeypatch.setattr(ur, "_git_stream", lambda *a: 0)
    monkeypatch.setattr(subprocess, "run", fake_run)
    rc = ur.cmd_fetch(_NS(state_dir=str(tmp_path)))
    assert rc == 2
    assert "fast-forward" in capsys.readouterr().err


# --- baseline -----------------------------------------------------------------

def test_baseline_requires_fetch_record(tmp_path, capsys):
    assert ur.cmd_baseline(_NS(state_dir=str(tmp_path))) == 1
    assert "fetch" in capsys.readouterr().err


def test_baseline_skips_without_pulled_flag(tmp_path, capsys, monkeypatch):
    (tmp_path / ur.HEADS_NAME).write_text(json.dumps({"old": "x", "new": "x", "pulled": False}))
    monkeypatch.setattr(ur, "_resolve_db", lambda: "testpike")
    assert ur.cmd_baseline(_NS(state_dir=str(tmp_path))) == 0
    assert "untouched" in capsys.readouterr().out


def test_baseline_marks_only_when_ledger_empty_and_uses_old_tree(tmp_path, monkeypatch):
    (tmp_path / ur.HEADS_NAME).write_text(json.dumps({"old": "deadbeef", "new": "cafebabe", "pulled": True}))
    marked = {}

    # Materialize the old worktree so the real Path checks pass without patching
    old_dir = tmp_path / "upgrade-old-base" / "db" / "migration"
    old_dir.mkdir(parents=True)
    (old_dir / "V500__old.sql").write_text("SELECT 1;", encoding="utf-8")

    monkeypatch.setattr(ur, "_resolve_db", lambda: "testpike")
    monkeypatch.setattr(migrate_apply, "_ensure_ledger", lambda db: None)
    monkeypatch.setattr(migrate_apply, "_applied_names", lambda db: set())

    def fake_mark(db, mdir, allow_main=False):
        marked["dir"] = Path(mdir)
        marked["allow_main"] = allow_main
        return 7

    monkeypatch.setattr(migrate_apply, "mark_baseline", fake_mark)
    monkeypatch.setattr(ur, "_git_stream", lambda *a: 0)
    rc = ur.cmd_baseline(_NS(state_dir=str(tmp_path)))
    assert rc == 0, rc
    assert marked["allow_main"] is True
    assert str(old_dir) in str(marked["dir"]), \
        "baseline must read the OLD commit's file set, never the current tree"


def test_baseline_skips_when_ledger_already_aligned(tmp_path, monkeypatch, capsys):
    (tmp_path / ur.HEADS_NAME).write_text(json.dumps({"old": "x", "new": "y", "pulled": True}))
    monkeypatch.setattr(ur, "_resolve_db", lambda: "testpike")
    monkeypatch.setattr(migrate_apply, "_ensure_ledger", lambda db: None)
    monkeypatch.setattr(migrate_apply, "_applied_names", lambda db: {"V1__a.sql", "V2__b.sql"})
    assert ur.cmd_baseline(_NS(state_dir=str(tmp_path))) == 0
    assert "already aligned" in capsys.readouterr().out


# --- migrate --------------------------------------------------------------

def test_migrate_reports_missing_tables_by_name(monkeypatch, capsys):
    monkeypatch.setattr(ur, "_resolve_db", lambda: "testpike")
    monkeypatch.setattr(migrate_apply, "apply",
                        lambda db, d, allow_main: {"applied": [Path("V999__boom.sql")], "skipped": []})
    monkeypatch.setattr(migrate_apply, "verify",
                        lambda db, dirs, allow_main: {"checked": 3, "missing": ["tp_gone"]})
    rc = ur.cmd_migrate(_NS(state_dir="", ))
    assert rc == 1
    err = capsys.readouterr().err
    assert "tp_gone" in err, "missing objects must be named one by one"


def test_migrate_skips_without_code_change(monkeypatch, tmp_path, capsys):
    (tmp_path / ur.HEADS_NAME).write_text(json.dumps({"old": "x", "new": "x", "pulled": False}))
    assert ur.cmd_migrate(_NS(state_dir=str(tmp_path))) == 0
    assert "migrations skipped" in capsys.readouterr().out


# --- images / rollout ------------------------------------------------------

def test_substitute_mode_skips_build_and_rollout(monkeypatch):
    monkeypatch.setenv("INSTALLKIT_COMPOSE_BIN", "/bin/true")

    def explode(*a, **k):
        raise AssertionError("substitute mode must not touch docker")

    monkeypatch.setattr(subprocess, "run", explode)
    monkeypatch.setattr(ur.image_relay, "ensure_for_compose", explode)
    assert ur.cmd_images(_NS(answers_file="missing.json")) == 0
    assert ur.cmd_rollout(_NS(answers_file="missing.json")) == 0


def test_profiles_come_from_installed_tier_single_source(tmp_path, monkeypatch):
    import resource_tiers
    answers = tmp_path / "answers-S1.json"
    tier = next(t for t in resource_tiers.TIERS
                if resource_tiers.compose_profiles(t))
    answers.write_text(json.dumps({"tier": tier}), encoding="utf-8")
    seen = []

    monkeypatch.setattr(ur, "_compose_base", lambda: ["docker", "compose", "-f", "x"])
    monkeypatch.setattr(ur.image_relay, "ensure_for_compose",
                        lambda f, profiles=(), printer=None: seen.append(("relay", list(profiles))) or True)
    monkeypatch.setattr(ur, "_git_stream", lambda *a: 0)

    def fake_run(cmd, **kw):
        seen.append(list(cmd))
        return subprocess.CompletedProcess(cmd, 0)

    monkeypatch.setattr(subprocess, "run", fake_run)
    assert ur.cmd_images(_NS(answers_file=str(answers))) == 0
    expected = resource_tiers.compose_profiles(tier)
    img = [c for c in seen if isinstance(c, list) and c[-1] == "build"][0]
    assert img[img.index("--profile") + 1] == expected[0]
    roll_seen = []

    def fake_run2(cmd, **kw):
        roll_seen.append(list(cmd))
        return subprocess.CompletedProcess(cmd, 0)

    monkeypatch.setattr(subprocess, "run", fake_run2)
    assert ur.cmd_rollout(_NS(answers_file=str(answers))) == 0
    roll = roll_seen[0]
    assert roll[-2:] == ["up", "-d"]
    assert roll.count("--profile") == len(expected)


# --- probe -----------------------------------------------------------------

def test_probe_deadline_names_the_url_and_log_entry(monkeypatch, capsys):
    monkeypatch.setenv("UPGRADE_PROBE_TIMEOUT_S", "0")
    monkeypatch.setattr(ur.s1_bootstrap, "resolve_admin_base_url", lambda: "http://localhost:18081")
    monkeypatch.setattr(ur.s1_bootstrap, "admin_ready", lambda url, timeout=5.0: False)
    assert ur.cmd_probe(_NS()) == 1
    err = capsys.readouterr().err
    assert "http://localhost:18081" in err and "docker compose logs admin" in err


def test_probe_success_reports_head(monkeypatch, capsys):
    monkeypatch.setenv("UPGRADE_PROBE_TIMEOUT_S", "5")
    monkeypatch.setattr(ur.s1_bootstrap, "resolve_admin_base_url", lambda: "http://localhost:18081")
    monkeypatch.setattr(ur.s1_bootstrap, "admin_ready", lambda url, timeout=5.0: True)
    monkeypatch.setattr(ur, "_git", lambda *a: "abc1234")
    assert ur.cmd_probe(_NS()) == 0
    assert "abc1234" in capsys.readouterr().out


# --- credential mapping (same dual-source class as the install batch) --------

def test_migrate_psql_user_follows_install_key(monkeypatch):
    monkeypatch.delenv("DB_USER", raising=False)
    monkeypatch.setenv("DB_USERNAME", "ops")
    cmd = migrate_apply._psql_cmd("somedb", ["-c", "select 1"])
    assert cmd[cmd.index("-U") + 1] == "ops"
    monkeypatch.setenv("DB_USER", "explicit")
    cmd = migrate_apply._psql_cmd("somedb", ["-c", "select 1"])
    assert cmd[cmd.index("-U") + 1] == "explicit"


# --- bash routing ------------------------------------------------------------

def test_help_lists_upgrade_scenario():
    proc = subprocess.run(["bash", str(INSTALL_SH), "--help"],
                          capture_output=True, text=True, timeout=30)
    assert proc.returncode == 0
    assert "upgrade" in proc.stdout and "S6" in proc.stdout


def test_upgrade_head_key_resets_checkpoints_only_when_head_moves(tmp_path):
    # Stub python: the upgrade recipe steps all succeed; current-head prints a
    # value from the environment so the test controls head movement.
    stub = tmp_path / "py.py"
    stub.write_text(
        "#!/usr/bin/env python3\n"
        "import os, sys\n"
        "print({'current-head': os.environ.get('FAKE_HEAD', 'h1')}.get(sys.argv[2], 'ok'))\n",
        encoding="utf-8")
    stub.chmod(0o755)
    state = tmp_path / "state"
    env = {"PATH": "/usr/bin:/bin", "HOME": str(tmp_path),
           "PYTHON_BIN": str(stub),
           "INSTALLKIT_STATE_DIR": str(state),
           "INSTALLKIT_LOG_DIR": str(tmp_path / "logs"),
           "FAKE_HEAD": "h1"}
    r1 = subprocess.run(["bash", str(INSTALL_SH), "--scenario=upgrade"],
                        capture_output=True, text=True, env=env, timeout=60)
    assert r1.returncode == 0, r1.stdout + r1.stderr
    done = (state / "state-upgrade.txt").read_text().split()
    assert done == ["step:upgrade-ledger", "step:upgrade-migrate", "step:upgrade-images",
                    "step:upgrade-rollout", "step:upgrade-probe"], \
        "the five checkpointed steps must run in order (fetch itself never checkpoints)"
    r2 = subprocess.run(["bash", str(INSTALL_SH), "--scenario=upgrade"],
                        capture_output=True, text=True, env=env, timeout=60)
    assert r2.returncode == 0
    assert "checkpoint resume, skipped" in r2.stdout, "same head rerun must be a no-op"
    env["FAKE_HEAD"] = "h2"
    r3 = subprocess.run(["bash", str(INSTALL_SH), "--scenario=upgrade"],
                        capture_output=True, text=True, env=env, timeout=60)
    assert r3.returncode == 0
    assert "checkpoint resume, skipped" not in r3.stdout, \
        "a moved head must re-walk the whole pipeline"
