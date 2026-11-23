#!/usr/bin/env python3
"""Open-source identity scrub gate.

Personal-identity literals (developer home paths, workstation hostname,
internal IP prefix, personal email, Windows user dirs, WSL drive mounts)
must never appear in tracked public code surfaces. This gate enforces
zero-tolerance over:

    tools/            full tooling layer (public, tracked wholesale)
    scripts/          shipped helper layer (vision rigs, window drivers)
    core/src          Java core service sources
    admin/src         Java admin service sources
    api/src           Java API gateway sources
    ai-service/src    Java AI service sources
    common/src        shared Java library sources
    executor/         Python Playwright/JMeter executor
    installkit/       guided installer
    cli/ shared/ deploy/ mock-server/   supporting shipped layers
    frontend/src      web UI sources
    frontend/tests    E2E / UI test sources
    db                schema and seed assets

The shipped-tooling layer scripts/ is IN scope since the 2026-11-23
open-source hardening (it travels inside the public release snapshot).

Out of scope here (internal development bookkeeping; these paths are
EXCLUDED from the public release snapshot, see the release runbook):

    PROGRESS.md       chain breakpoints
    AGENTS.md         chain rulebook
    PLAN.md           chain plan
    docs/**           task ledgers, archives, change-eval records

Usage: python3 tools/scrub_check.py   (exit 1 prints every offender line)
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Literals are assembled from fragments so this scanner stays clean under
# its own scan (zero-exemption invariant: the gate file must pass itself).
IDENTITY_LITERALS = [
    "/home/" + "bowen",
    "HQITL" + "0239",
    "10." + "212.",
    "bow" + "en@",
    "C:\\Users" + "\\",
    "C:/Use" + "rs",
    "/mnt/" + "c/",
]

SCOPED_ROOTS = [
    "tools",
    "scripts",
    "core/src",
    "admin/src",
    "api/src",
    "ai-service/src",
    "common/src",
    "executor",
    "installkit",
    "cli",
    "shared",
    "deploy",
    "mock-server",
    "frontend/src",
    "frontend/tests",
    "db",
]


def offenders():
    """Return (path, line, matched-literal) tuples for every hit."""
    args = ["git", "grep", "-In", "-I", "-F"]
    for literal in IDENTITY_LITERALS:
        args += ["-e", literal]
    args += ["--"] + SCOPED_ROOTS
    proc = subprocess.run(args, cwd=ROOT, capture_output=True, text=True)
    hits = []
    if proc.stdout.strip():
        for raw in proc.stdout.splitlines():
            head, _, _rest = raw.partition(":")
            matched = next((lit for lit in IDENTITY_LITERALS if lit in raw), "?")
            hits.append((head, matched))
    # git grep exits 1 with no hits: that is the clean answer, not an error.
    if proc.returncode not in (0, 1):
        sys.stderr.write(proc.stderr)
        raise SystemExit("git grep failed")
    return hits


def main():
    hits = offenders()
    if hits:
        print("SCRUB FAIL: personal-identity literals in public code roots:")
        seen = set()
        for path, lit in hits:
            key = (path, lit)
            if key not in seen:
                seen.add(key)
            print(f"  {path}  <contains {lit}>")
        print(f"offending files: {len({p for p, _ in hits})}")
        return 1
    print(f"SCRUB OK: zero identity literals across {len(SCOPED_ROOTS)} public roots "
          f"(patterns: {len(IDENTITY_LITERALS)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
