#!/usr/bin/env python3
# -*- coding: utf-8 -*-
pass
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import doctor
import pkg_probe

MINIMUM_PAT = re.compile(r">?=\s*(\d+)\.(\d+)")


def parse_version(raw):
    pass
    m = re.search(r"(\d+)\.(\d+)\.(\d+)", raw or "")
    return (int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None


def satisfies(version, minimum):
    pass
    if version is None:
        return False
    if minimum is None:
        return True
    return version[:len(minimum)] >= minimum


def current_node_version():
    pass
    if shutil.which("node") is None:
        return None
    try:
        proc = subprocess.run(["node", "--version"], capture_output=True, text=True,
                              timeout=15, check=False)
    except (subprocess.TimeoutExpired, OSError):
        return None
    return parse_version(proc.stdout)


def requirement_lines(state_dir):
    pass
    rows = []
    for _code, recipe, _err in doctor.collect_recipes(state_dir):
        if not isinstance(recipe, dict):
            continue
        line = (recipe.get("runtime_requires") or {}).get("node")
        m = MINIMUM_PAT.search(line or "")
        if m:
            rows.append(((int(m.group(1)), int(m.group(2))), line))
    return rows


def requirement_from_recipes(state_dir):
    pass
    pairs = [p for p, _line in requirement_lines(state_dir)]
    return max(pairs) if pairs else None


def requirement_line(state_dir):
    pass
    rows = requirement_lines(state_dir)
    if not rows:
        return None
    return max(rows, key=lambda x: x[0])[1]


def _nvm_plan(minimum):
    pass
    major = minimum[0] if minimum else 22
    return [
        "curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash",
        "bash -lc 'source ~/.nvm/nvm.sh && nvm install %d && nvm alias default %d'" % (major, major),
    ]


def _nvm_windows_plan(minimum):
    pass
    want = "%d.%d" % minimum if minimum else "22"
    return ["winget install -e --id CoreyButler.NVMforWindows",
            "nvm install %s && nvm use %s" % (want, want)]


def bootstrap_plan(pm, minimum, win_lts_behind=False):
    pass
    if pm not in pkg_probe.PREREQ_CMD:
        raise ValueError("unregistered package-manager profile '%s' — supported: %s; "
                         "for other distributions install node per the official docs "
                         "(%s), then re-run the same check"
                         % (pm, " / ".join(pkg_probe.PREREQ_CMD),
                            pkg_probe.PREREQ_DOCS["node"]))
    if minimum is None:
        return [pkg_probe.install_command(pm, "node")]
    if pm in ("apt", "dnf"):
        return _nvm_plan(minimum)
    if pm == "winget" and win_lts_behind:
        return _nvm_windows_plan(minimum)
    return [pkg_probe.install_command(pm, "node")]


def plan_if_needed(present, version, minimum, pm):
    pass
    if present and satisfies(version, minimum):
        return []
    return bootstrap_plan(pm, minimum)


def _resolve_pm(args):
    return args.assume_pm or pkg_probe.pkg_manager_for(pkg_probe.host_os(), pkg_probe.read_os_release())


def cmd_needs(args):
    pass
    if doctor.collect_recipes(args.state_dir):
        return 0
    answers = {}
    try:
        answers = json.loads(Path(args.answers_file).read_text(encoding="utf-8") or "{}")
    except (OSError, ValueError):
        answers = {}
    if isinstance(answers, dict) and answers.get("engines"):
        return 0
    return 1


def _run_plan_cmd(cmd):
    pass
    override = os.environ.get("INSTALLKIT_RUNTIME_CMD_BIN", "").strip()
    return subprocess.run(override if override else cmd, shell=True)


def cmd_check(args):
    pass
    minimum = requirement_from_recipes(args.state_dir)
    version = current_node_version()
    need_text = "node>=%d.%d" % minimum if minimum else "any node version is fine"
    print("Runtime requirement: %s (source: engine recipe runtime_requires)" % need_text)
    if version is None:
        print("node runtime missing — the node command cannot be found on this machine's PATH")
    elif satisfies(version, minimum):
        print("node runtime v%d.%d.%d satisfies the requirement" % version)
    else:
        print("node runtime v%d.%d.%d is below the required version (>=%d.%d)" % (version + minimum))
    pm = _resolve_pm(args)
    if version is not None and satisfies(version, minimum):
        return 0
    if pm is None:
        # Unknown profile: the machine cannot be self-repaired, so the exit
        # must stay actionable — literal docs URL plus the verbatim re-check
        # line (recovery entry, per the three-element error guidance rule).
        print("package-manager profile unknown — install node manually per the official docs: %s"
              % pkg_probe.PREREQ_DOCS["node"])
        print("  after installing node, re-run this check: python3 installkit/runtime_bootstrap.py check --state-dir %s"
              % args.state_dir)
        return 0
    for cmd in bootstrap_plan(pm, minimum):
        print("  refill command: %s" % cmd)
    print("  how to apply: python3 installkit/runtime_bootstrap.py ensure --state-dir %s --yes "
          "runs them automatically (install.sh does the same during install)" % args.state_dir)
    return 0


def cmd_ensure(args):
    pass
    minimum = requirement_from_recipes(args.state_dir)
    version = current_node_version()
    if version is not None and satisfies(version, minimum):
        print("Runtime already satisfied (node v%d.%d.%d) — zero refill" % version)
        return 0
    pm = _resolve_pm(args)
    try:
        plan = bootstrap_plan(pm, minimum)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    if not args.yes:
        print("Runtime missing or too old; the refill commands below would run (without --yes this is plan-only, nothing is executed):")
        for cmd in plan:
            print("  %s" % cmd)
        print("After confirming, re-run the same command with --yes, or copy each command above and run them manually")
        return 0
    for cmd in plan:
        print("Applying refill: %s" % cmd)
        proc = _run_plan_cmd(cmd)
        if proc.returncode != 0:
            print("refill command failed (exit code %s) — see the output above for the cause; after fixing it, re-run the same command and it resumes from this step"
                  % proc.returncode, file=sys.stderr)
            return proc.returncode
    after = current_node_version()
    if after is None:
        print("refill commands finished but node is still not detectable — open a new terminal and re-run the check (PATH takes effect in a new session)")
        return 0
    if satisfies(after, minimum):
        print("Runtime refill complete (node v%d.%d.%d)" % after)
    else:
        need_text = "node>=%d.%d" % minimum if minimum else "any version is fine"
        print("refill commands finished but this machine's node is still v%d.%d.%d (required %s) — PATH may need a fresh shell; reopen it and run the check again to confirm"
              % (after + (need_text,)))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(prog="runtime_bootstrap.py",
                                     description="engine runtime bootstrap (requirement = recipe declaration, install method = profile command table)")
    sub = parser.add_subparsers(dest="command", required=True)
    p_check = sub.add_parser("check", help="read-only report: recipe runtime requirement vs local probe")
    p_needs = sub.add_parser("needs", help="stage gate: does this install/upgrade need the runtime stage (consumed by S1/S4)")
    p_ensure = sub.add_parser("ensure", help="apply the profile refill plan when the runtime is missing or too old (without --yes only prints the plan)")
    for p in (p_check, p_ensure):
        p.add_argument("--state-dir", required=True, help="directory holding the recipe-*.json state files")
        p.add_argument("--assume-pm", default=None,
                       help="explicit package-manager profile (apt/dnf/apk/brew/winget/choco; auto-detected by default)")
    p_ensure.add_argument("--yes", action="store_true", help="explicit consent to execute the refill commands")
    p_needs.add_argument("--state-dir", required=True)
    p_needs.add_argument("--answers-file", default="",
                         help="S1 wizard answers JSON (non-empty engines = the runtime stage is needed)")
    args = parser.parse_args(argv)
    if args.command == "check":
        return cmd_check(args)
    if args.command == "needs":
        return cmd_needs(args)
    return cmd_ensure(args)


if __name__ == "__main__":
    sys.exit(main())
