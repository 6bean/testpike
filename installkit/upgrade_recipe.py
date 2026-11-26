# -*- coding: utf-8 -*-
"""Upgrade scenario implement (S6) — bring an installed checkout onto newer
code without touching the data volumes.

Pipeline owned by install.sh --scenario=upgrade, one subcommand per step:

  fetch       fast-forward the tracked branch only (never rewrites local
              history; a dirty tree or a diverged branch refuses by name);
  baseline    first real upgrade against a schema-init-built database: the
              migration ledger is empty while every V file is already
              embodied, so the file set of the OLD commit is marked applied
              WITHOUT executing anything (replay would crash the live schema);
  migrate     apply the files added since (ledger-bound, resumable mid-way)
              then verify the expected table set against information_schema;
  images      deliver any newly referenced middleware images through the
              relay, then rebuild the application images for the installed
              resource tier's profiles;
  rollout     rolling re-create with the same profile set;
  probe       bounded wait for the admin service to answer (hot-updatable
              UPGRADE_PROBE_TIMEOUT_S), refusing with the literal log entry.

Database access and credentials never inline here: everything routes through
migrate_apply's psql channel (which mirrors the install .env keys), and no
step ever echoes a secret value.
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import host_ports
import image_relay
import migrate_apply
import resource_tiers
import s1_bootstrap

REPO_ROOT = Path(__file__).resolve().parents[1]
COMPOSE_FILE = REPO_ROOT / "docker-compose.yml"
MIGRATIONS_DIR = REPO_ROOT / "db" / "migration"
HEADS_NAME = "upgrade-heads.json"


def _load_tree_env():
    """Mirror the compose env precedence: exported values win, the project
    .env fills the gaps, so DB_URL/DB_USERNAME resolve identically here and
    in docker-compose interpolation."""
    try:
        for key, value in host_ports.read_dotenv(str(REPO_ROOT)).items():
            os.environ.setdefault(key, value)
    except OSError:
        pass


def _git(*args):
    proc = subprocess.run(["git", *args], cwd=str(REPO_ROOT),
                          capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError("git %s failed: %s" % (" ".join(args),
                                                  (proc.stderr or "").strip()[:300]))
    return (proc.stdout or "").strip()


def _git_stream(*args):
    """Run a git command with its output streamed to the console; returns rc."""
    return subprocess.run(["git", *args], cwd=str(REPO_ROOT), text=True).returncode


def _heads_path(state_dir):
    return Path(state_dir) / HEADS_NAME


def cmd_fetch(args):
    state = Path(args.state_dir)
    state.mkdir(parents=True, exist_ok=True)
    _load_tree_env()
    try:
        dirty = [ln for ln in _git("status", "--porcelain", "--untracked-files=no").splitlines() if ln.strip()]
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    if dirty:
        print("refused: the checkout has uncommitted changes (%d file(s)) — the "
              "upgrade never touches local edits. Commit or move them aside, then "
              "re-run this command." % len(dirty), file=sys.stderr)
        for ln in dirty[:10]:
            print("  " + ln, file=sys.stderr)
        return 2
    try:
        branch = _git("rev-parse", "--abbrev-ref", "HEAD")
        if branch == "HEAD":
            print("refused: the checkout is detached (installed at an exact commit). "
                  "Checkout the tracked branch first, then re-run.", file=sys.stderr)
            return 2
        old = _git("rev-parse", "HEAD")
        if _git_stream("fetch", "origin", branch) != 0:
            print("refused: git fetch origin %s failed — check network/remote access, "
                  "then re-run the same command" % branch, file=sys.stderr)
            return 1
        remote = _git("rev-parse", "origin/" + branch)
        if old == remote:
            _write_heads(state, {"old": old, "new": old, "pulled": False})
            print("code already at the newest published commit (%s): PULLED=0"
                  % old[:10])
            return 0
        ff = subprocess.run(["git", "merge-base", "--is-ancestor", old, remote],
                            cwd=str(REPO_ROOT)).returncode == 0
        if not ff:
            print("refused: origin/%s is not a fast-forward of this checkout "
                  "(local commits ahead or branch rewritten upstream). The upgrade "
                  "never rewrites history — rebase or reset this checkout first, "
                  "then re-run." % branch, file=sys.stderr)
            return 2
        if _git_stream("merge", "--ff-only", "origin/" + branch) != 0:
            print("refused: fast-forward merge failed despite the ancestry check — "
                  "inspect git status output above, then re-run", file=sys.stderr)
            return 1
        new = _git("rev-parse", "HEAD")
        _write_heads(state, {"old": old, "new": new, "pulled": True})
        print("code fast-forwarded %s -> %s (PULLED=1)" % (old[:10], new[:10]))
        return 0
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1


def _write_heads(state, payload):
    (state / HEADS_NAME).write_text(json.dumps(payload, indent=1), encoding="utf-8")


def cmd_current_head(args):
    print(_git("rev-parse", "--short", "HEAD"))
    return 0


def _read_heads(state_dir):
    path = _heads_path(Path(state_dir))
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        return None


def _resolve_db():
    _load_tree_env()
    return migrate_apply.db_from_url()


def cmd_baseline(args):
    heads = _read_heads(args.state_dir)
    if heads is None:
        print("refused: no upgrade fetch record found (run the upgrade scenario "
              "from its start; this step needs the recorded old commit)", file=sys.stderr)
        return 1
    if not heads.get("pulled"):
        print("no code change this run: migration ledger untouched")
        return 0
    try:
        db = _resolve_db()
        migrate_apply._ensure_ledger(db)
        recorded = migrate_apply._applied_names(db)
    except RuntimeError as exc:
        print("refused: cannot read the migration ledger — %s (the PostgreSQL "
              "container must be running; if middleware is down run the repair "
              "scenario first)" % exc, file=sys.stderr)
        return 1
    if recorded:
        print("ledger already aligned (%d files recorded): baseline skipped" % len(recorded))
        return 0
    worktree = Path(args.state_dir) / "upgrade-old-base"
    if worktree.exists():
        _git_stream("worktree", "remove", "--force", str(worktree))
    try:
        if _git_stream("worktree", "add", "--detach", str(worktree), heads["old"]) != 0:
            print("refused: could not materialize the old commit for the baseline "
                  "set — see git output above", file=sys.stderr)
            return 1
        old_dir = worktree / "db" / "migration"
        if not old_dir.is_dir():
            print("refused: the recorded old commit predates db/migration — nothing "
                  "to baseline, run migrate directly instead", file=sys.stderr)
            return 1
        count = migrate_apply.mark_baseline(db, old_dir, allow_main=True)
        print("ledger baseline marked from %s (%d files recorded without execution)"
              % (heads["old"][:10], count))
        return 0
    finally:
        if worktree.exists():
            _git_stream("worktree", "remove", "--force", str(worktree))


def cmd_migrate(args):
    heads = _read_heads(args.state_dir) if args.state_dir else None
    if heads is not None and not heads.get("pulled"):
        print("no code change this run: migrations skipped")
        return 0
    try:
        db = _resolve_db()
        result = migrate_apply.apply(db, MIGRATIONS_DIR, allow_main=True)
        for path in result["applied"]:
            print("applied: %s" % path.name)
        print("migrations done: applied=%d skipped=%d database=%s"
              % (len(result["applied"]), len(result["skipped"]), db))
        report = migrate_apply.verify(db, [MIGRATIONS_DIR], allow_main=True)
    except RuntimeError as exc:
        print("refused: %s — fix what is named, then re-run the same command to "
              "resume (the ledger makes completed files replay-free)" % exc,
              file=sys.stderr)
        return 1
    if report["missing"]:
        print("MISSING %d table(s) out of %d expected on %s:"
              % (len(report["missing"]), report["checked"], db), file=sys.stderr)
        for table in report["missing"]:
            print("  - %s" % table, file=sys.stderr)
        return 1
    print("schema verified: all %d expected tables present on %s"
          % (report["checked"], db))
    return 0


def _profile_args(answers_file):
    tier = None
    try:
        with open(answers_file, encoding="utf-8") as fh:
            tier = json.load(fh).get("tier")
    except (OSError, ValueError):
        pass
    args = []
    for profile in resource_tiers.compose_profiles(tier):
        args += ["--profile", profile]
    return args


def _compose_base():
    return [os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip() or "docker",
            "compose", "-f", str(COMPOSE_FILE)]


def cmd_images(args):
    if os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip():
        print("image build: skipped in compose substitute mode (test shape)")
        return 0
    profiles = _profile_args(args.answers_file)
    if not image_relay.ensure_for_compose(str(COMPOSE_FILE),
                                          profiles=profiles[1::3] or (),
                                          printer=print):
        print("new middleware images were not delivered — follow the recovery "
              "options printed above, then re-run the same command", file=sys.stderr)
        return 1
    rc = subprocess.run(_compose_base() + profiles + ["build"], cwd=str(REPO_ROOT)).returncode
    if rc != 0:
        print("image rebuild failed (exit %s) — the docker output above is the "
              "reason" % rc, file=sys.stderr)
    return rc


def cmd_rollout(args):
    if os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip():
        print("rolling re-create: skipped in compose substitute mode (test shape)")
        return 0
    rc = subprocess.run(_compose_base() + _profile_args(args.answers_file)
                        + ["up", "-d"], cwd=str(REPO_ROOT)).returncode
    if rc != 0:
        print("rolling re-create failed (exit %s) — the docker output above names "
              "the service; after fixing, re-run the same command to resume" % rc,
              file=sys.stderr)
    return rc


def cmd_probe(args):
    _load_tree_env()
    deadline = time.time() + float(os.environ.get("UPGRADE_PROBE_TIMEOUT_S", "300"))
    url = s1_bootstrap.resolve_admin_base_url()
    while time.time() < deadline:
        if s1_bootstrap.admin_ready(url):
            try:
                head = _git("rev-parse", "--short", "HEAD")
            except RuntimeError:
                head = "unknown"
            print("upgrade verified: admin answers at %s (head %s); login and "
                  "demo entry follow the first-install guide" % (url, head))
            return 0
        time.sleep(5)
    print("admin service still not answering at %s within the probe window — look "
          "at docker compose logs admin; after fixing, re-run the same command "
          "(completed checkpoints stay, only this step re-runs)" % url, file=sys.stderr)
    return 1


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="upgrade_recipe.py",
        description="S6 upgrade implement (fetch / ledger baseline / migrate / "
                    "images / rollout / probe)")
    sub = parser.add_subparsers(dest="command", required=True)
    p_fetch = sub.add_parser("fetch")
    p_fetch.add_argument("--state-dir", required=True)
    p_fetch.set_defaults(func=cmd_fetch)
    p_head = sub.add_parser("current-head")
    p_head.set_defaults(func=cmd_current_head)
    p_base = sub.add_parser("baseline")
    p_base.add_argument("--state-dir", required=True)
    p_base.set_defaults(func=cmd_baseline)
    p_mig = sub.add_parser("migrate")
    p_mig.add_argument("--state-dir", default="")
    p_mig.set_defaults(func=cmd_migrate)
    p_img = sub.add_parser("images")
    p_img.add_argument("--answers-file", required=True)
    p_img.set_defaults(func=cmd_images)
    p_roll = sub.add_parser("rollout")
    p_roll.add_argument("--answers-file", required=True)
    p_roll.set_defaults(func=cmd_rollout)
    p_probe = sub.add_parser("probe")
    p_probe.set_defaults(func=cmd_probe)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
