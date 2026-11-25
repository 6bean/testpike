#!/usr/bin/env python3
"""Executor station manager: persistent systemd user unit for the runner.

Why this exists: the runner used to be started only as a systemd-run
transient unit, which WSL restarts wipe silently — the executor row then
stays offline for hours and every survey/explore task piles up in the
queue with no claimer (observed on 2026-11-22: heartbeat stopped for
over a day, two envelope rows never claimed). A persistent enabled unit
file installed under ~/.config/systemd/user/ survives restarts and is
re-asserted by the `check` subcommand, so the gap cannot come back
unnoticed.

Subcommands:
  rebuild  idempotent install + enable + restart of the station unit,
           then wait until the executor heartbeat row is fresh.
  check    assert persistent definition in place (file, enabled), unit
           active, process owned by this worktree, heartbeat fresh.
  quiet    stop the station for a backend service swap (owner-checked);
           exit 0 = stopped now (caller owes a revive), exit 10 = nothing
           to do (unit absent/inactive), exit 1 = foreign owner refused.
           Why: the persistent runner keeps polling claim/heartbeat every
           couple of seconds; a swap that takes core/api down underneath it
           made api forward-fail and log a recurring ERROR family in the
           role logs (2026-11-26 boot rounds, 6 lines).
  revive   bring the station back after the swap and assert it is healthy;
           delegates to rebuild so the heartbeat wait stays single-sourced.
Exit codes: 0 = pass, 1 = fail (each failure prints the missing shape).
Thresholds are env-tunable, no hardcoded waits: EXECUTOR_STATION_HEARTBEAT_MAX_S
(default 120), EXECUTOR_STATION_BOOT_DEADLINE_S (default 90),
EXECUTOR_STATION_STOP_DEADLINE_S (default 20), EXECUTOR_STATION_DRAIN_S
(default 3).
"""
from __future__ import annotations

import argparse
import importlib
import os
import shlex
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import db_target  # noqa: E402  slot/DB resolution single source (R47/R48)

UNIT_PREFIX = "tp-e2e-runner-slot"  # same naming convention as the transient window launches
HEARTBEAT_MAX_S = int(os.environ.get("EXECUTOR_STATION_HEARTBEAT_MAX_S", "120"))
BOOT_DEADLINE_S = int(os.environ.get("EXECUTOR_STATION_BOOT_DEADLINE_S", "90"))
# Bounds for the quiet/revive pair (service-swap window). The runner polls
# every couple of seconds, so a short bound covers the last in-flight poll.
STOP_DEADLINE_S = int(os.environ.get("EXECUTOR_STATION_STOP_DEADLINE_S", "20"))
DRAIN_S = int(os.environ.get("EXECUTOR_STATION_DRAIN_S", "3"))


def _slot() -> int:
    return db_target.slot_of(db_target.db_spec()[2])


def executor_name() -> str:
    """Registration name for this tree's station.

    Slot 0 keeps the historical default (the offline row the unstick order
    points at); other slots follow the window-recipe convention so two
    stations never fight over one registration name.
    """
    env = os.environ.get("EXECUTOR_NAME")
    if env:
        return env
    slot = _slot()
    if slot == 0:
        return "executor-local-01"
    return f"executor-{db_target.chain_token()}-slot{slot}"


def unit_name() -> str:
    return f"{UNIT_PREFIX}{_slot()}"


def unit_file() -> Path:
    return Path.home() / ".config" / "systemd" / "user" / f"{unit_name()}.service"


def _sh(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(list(args), capture_output=True, text=True)


def systemctl(prop: str) -> str:
    out = _sh("systemctl", "--user", "show", unit_name() + ".service",
              f"-p{prop}", "--value")
    return out.stdout.strip()


def owner_ok() -> tuple[bool, str]:
    """MainPID cwd must point at this repo tree (R47 owner discipline)."""
    pid = systemctl("MainPID")
    if not pid or pid == "0":
        return False, "no MainPID"
    cwd = os.readlink(f"/proc/{pid}/cwd") if Path(f"/proc/{pid}/cwd").exists() else ""
    return (cwd == str(ROOT), f"pid={pid} cwd={cwd or 'unreadable'}")


def heartbeat_age() -> float:
    """Seconds since the executor row's last heartbeat; raises if row missing.

    Reads through the same docker-exec psql shape as tools/ensure_baseline.py
    (the baseline ledger's DB access precedent), so the station tool does
    not depend on a python DB driver being installed.
    """
    _, _, dbname = db_target.db_spec()
    sql = ("select extract(epoch from (now() - last_heartbeat)) "
           f"from tp_executor where name = '{executor_name()}';")
    out = _sh("docker", "exec", "testpike-postgres", "psql",
              "-U", "testpike", "-d", dbname, "-tA", "-c", sql)
    if out.returncode != 0:
        raise RuntimeError(f"psql failed: {out.stderr.strip()[:200]}")
    text = out.stdout.strip()
    if not text:
        raise RuntimeError(f"executor row {executor_name()!r} missing or never heartbeated")
    return float(text)


def render_unit() -> str:
    """Unit definition generated at install time from this tree's resolved env."""
    log = f"/tmp/testpike-svc-runner-slot{_slot()}.log"
    # The inner command re-sources the chain env loader so a later .env edit
    # (ports, credentials) takes effect on restart without re-rendering.
    inner = (
        'export PATH="$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:$PATH"; '
        ". tools/ops/load_chain_env.sh; "
        f"export EXECUTOR_NAME={executor_name()}; "
        f"exec /usr/bin/python3 -u executor/runner.py >> {log} 2>&1"
    )
    slot = _slot()
    return (
        f"# testPike executor station (slot {slot}); persistent by design —\n"
        "# transient systemd-run units vanish on WSL restart and left the executor\n"
        "# offline with queued tasks unclaimed. Managed by tools/ops/executor_station.py.\n"
        "[Unit]\n"
        f"Description=testPike executor station slot {slot} (runner, persistent)\n"
        "After=network-online.target\n"
        "Wants=network-online.target\n"
        "\n[Service]\n"
        "Type=simple\n"
        f"WorkingDirectory={ROOT}\n"
        f"ExecStart=/usr/bin/bash -c {shlex.quote(inner)}\n"
        "Restart=always\n"
        "RestartSec=5\n"
        "Delegate=no\n"
        "\n[Install]\n"
        "WantedBy=default.target\n"
    )


def check(quiet: bool = False) -> int:
    """Assert the four station shapes; returns rc, prints each failure shape."""
    fails: list[str] = []
    if not unit_file().exists():
        fails.append(f"persistent unit file absent: {unit_file()}")
    enabled = systemctl("UnitFileState")
    if enabled != "enabled":
        fails.append(f"unit not enabled (UnitFileState={enabled or 'empty'} — "
                     "would not survive restart)")
    age = float("nan")
    active = systemctl("ActiveState")
    if active != "active":
        fails.append(f"unit not active (ActiveState={active or 'empty'})")
    else:
        ok, detail = owner_ok()
        if not ok:
            fails.append(f"owner check failed: {detail}")
    try:
        age = heartbeat_age()
        if age > HEARTBEAT_MAX_S:
            fails.append(f"heartbeat stale: {int(age)}s old > {HEARTBEAT_MAX_S}s")
    except Exception as exc:  # missing row/DB unreachable is a fail, never a pass
        fails.append(f"heartbeat unreadable: {exc}")
    if fails:
        for msg in fails:
            print(f"[executor-station] FAIL {msg}")
        return 1
    if not quiet:
        print(f"[executor-station] PASS unit={unit_name()} enabled active, "
              f"heartbeat fresh ({int(age)}s), executor={executor_name()}")
    return 0


def rebuild() -> int:
    """Idempotent: render + install + enable + restart, then wait for heartbeat."""
    pid = systemctl("MainPID")
    if pid and pid != "0":
        ok, detail = owner_ok()
        if not ok:
            print(f"[executor-station] FAIL refusing to touch a station owned "
                  f"by another worktree ({detail})")
            return 1
    f = unit_file()
    f.parent.mkdir(parents=True, exist_ok=True)
    new = render_unit()
    if not f.exists() or f.read_text() != new:
        f.write_text(new)
        _sh("systemctl", "--user", "daemon-reload")
    _sh("systemctl", "--user", "enable", f"{unit_name()}.service")
    rc = _sh("systemctl", "--user", "restart", f"{unit_name()}.service").returncode
    if rc != 0:
        print(f"[executor-station] FAIL restart rc={rc}")
        return 1
    deadline = time.time() + BOOT_DEADLINE_S
    while time.time() < deadline:
        if systemctl("ActiveState") == "active":
            break
        time.sleep(3)
    else:
        log_path = f"/tmp/testpike-svc-runner-slot{_slot()}.log"
        tail = subprocess.run(["tail", "-5", log_path],
                              capture_output=True, text=True).stdout
        print(f"[executor-station] FAIL unit not active after {BOOT_DEADLINE_S}s; "
              f"tail: {tail}")
        return 1
    # Wait out the first heartbeat (registration precedes the beat loop; the
    # row must go online on its own, never by hand-editing the table).
    deadline = time.time() + HEARTBEAT_MAX_S
    while time.time() < deadline:
        try:
            if heartbeat_age() <= HEARTBEAT_MAX_S:
                break
        except Exception:
            pass
        time.sleep(5)
    return check()


def quiet() -> int:
    """Stop the station so a backend swap runs without a live poller.

    Exit 0 means "I stopped it" — the caller owes a `revive` once the stack
    is healthy again. A unit that is absent or already inactive exits 10 so
    the caller can skip the revive instead of resurrecting a station the
    chain never installed. A station whose MainPID lives in a different
    worktree is never touched (R47 ownership), reported as a hard failure.
    """
    active = systemctl("ActiveState")
    pid = systemctl("MainPID")
    if active != "active" or not pid or pid == "0":
        print(f"[executor-station] quiet nothing-to-do "
              f"(ActiveState={active or 'empty'}) rc=10")
        return 10
    ok, detail = owner_ok()
    if not ok:
        print(f"[executor-station] quiet REFUSED station owned by another "
              f"worktree ({detail})")
        return 1
    rc = _sh("systemctl", "--user", "stop", f"{unit_name()}.service").returncode
    if rc != 0:
        print(f"[executor-station] quiet FAIL stop rc={rc}")
        return 1
    deadline = time.time() + STOP_DEADLINE_S
    while time.time() < deadline:
        if systemctl("MainPID") in ("", "0"):
            break
        time.sleep(1)
    else:
        print(f"[executor-station] quiet FAIL process still alive after "
              f"{STOP_DEADLINE_S}s")
        return 1
    # Let the last in-flight claim/heartbeat request land before the backend
    # disappears; the runner poll interval is a couple of seconds.
    time.sleep(DRAIN_S)
    print("[executor-station] quiet stopped (revive owed)")
    return 0


def revive() -> int:
    """Re-assert the station after a swap; ends in the same checked shape."""
    if systemctl("ActiveState") == "active":
        ok, detail = owner_ok()
        if ok:
            # Somebody (or a prior run) already revived it — assert health,
            # do not restart a working poller for no reason.
            return check()
        print(f"[executor-station] revive REFUSED foreign owner ({detail})")
        return 1
    return rebuild()


def main() -> int:
    global ROOT
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=("check", "rebuild", "quiet", "revive"))
    ap.add_argument("--tree", default=None,
                    help="manage the station of this repo tree instead of the "
                         "tree the script lives in (unstick orders point at "
                         "the main slot while the tool ships from a chain)")
    args = ap.parse_args()
    if args.tree:
        ROOT = Path(args.tree).resolve()
        sys.path[0] = str(ROOT / "scripts")
        importlib.reload(db_target)
    # Keep the process env aligned with the resolved tree before anything else.
    os.chdir(ROOT)
    db_target.bootstrap_env()
    if args.mode == "rebuild":
        return rebuild()
    if args.mode == "quiet":
        return quiet()
    if args.mode == "revive":
        return revive()
    return check()


if __name__ == "__main__":
    raise SystemExit(main())
