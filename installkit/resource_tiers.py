# -*- coding: utf-8 -*-
pass
import argparse
import hashlib
import io
import json
import os
import shutil
import sys

import instance_names

DEFAULT_TIER = "lightweight"

COMPONENTS = {
    "postgres": {
        "tiers": ["lightweight", "standard", "full"],
        "disk_mb": 500, "mem_mb": 256,
        "probe": "docker inspect -f '{{.State.Running}}' " + instance_names.name("postgres"),
        "install": "docker compose up -d postgres",
        "rollback": "docker rm -f " + instance_names.name("postgres"),
    },
    "redis": {
        "tiers": ["lightweight", "standard", "full"],
        "disk_mb": 100, "mem_mb": 128,
        "probe": "docker inspect -f '{{.State.Running}}' " + instance_names.name("redis"),
        "install": "docker compose up -d redis",
        "rollback": "docker rm -f " + instance_names.name("redis"),
    },
    "rabbitmq": {
        "tiers": ["lightweight", "standard", "full"],
        "disk_mb": 200, "mem_mb": 256,
        "probe": "docker inspect -f '{{.State.Running}}' " + instance_names.name("rabbitmq"),
        "install": "docker compose up -d rabbitmq",
        "rollback": "docker rm -f " + instance_names.name("rabbitmq"),
    },
    "seaweedfs": {
        "tiers": ["lightweight", "standard", "full"],
        "disk_mb": 300, "mem_mb": 256,
        "probe": "docker inspect -f '{{.State.Running}}' " + instance_names.name("seaweedfs"),
        "install": "docker compose up -d seaweedfs",
        "rollback": "docker rm -f " + instance_names.name("seaweedfs"),
    },
    "backend-services": {
        "tiers": ["lightweight", "standard", "full"],
        "disk_mb": 1600, "mem_mb": 3072,
        "probe": "curl -sf http://localhost:8083/actuator/health",
        "install": "docker compose --profile app up -d core api admin ai-service",
        "rollback": "docker compose --profile app down core api admin ai-service",
    },
    "frontend-spa": {
        "tiers": ["standard", "full"],
        "disk_mb": 200, "mem_mb": 64,
        "probe": "docker inspect -f '{{.State.Running}}' testpike-nginx",
        "install": "docker compose --profile app up -d frontend",
        "rollback": "docker rm -f testpike-nginx",
    },
    "prometheus": {
        "tiers": ["full"],
        "disk_mb": 800, "mem_mb": 512,
        "probe": "docker inspect -f '{{.State.Running}}' tp-prometheus",
        "install": "docker compose --profile observability up -d tp-prometheus",
        "rollback": "docker rm -f tp-prometheus",
    },
    "grafana": {
        "tiers": ["full"],
        "disk_mb": 400, "mem_mb": 256,
        "probe": "docker inspect -f '{{.State.Running}}' tp-grafana",
        "install": "docker compose --profile observability up -d tp-grafana",
        "rollback": "docker rm -f tp-grafana",
    },
    "executor": {
        # Numbers are image-build measurements, not guesses: disk 487MB is
        # the unpacked footprint of testpike/executor (archive layer sum
        # 117,964,067 B, window anchor IMG-EXEC2-SIZE-BYTES); mem 64MB
        # covers the measured import-time peak RSS 47,528 KiB plus heartbeat
        # loop headroom (one-shot probe inside the built image).
        # Values pinned by tests/test_resource_tiers_installer_contract.py.
        "tiers": ["full"],
        "disk_mb": 487, "mem_mb": 64,
        "probe": "docker inspect -f '{{.State.Running}}' testpike-executor",
        "install": "docker compose --profile executor up -d tp-executor tp-fixtures",
        "rollback": "docker rm -f testpike-executor testpike-fixtures",
    },
}

TIERS = {
    "lightweight": {
        "label": "Lightweight",
        "blurb": "middleware + four backend services; AI case generation and review "
                 "out of the box; case execution is carried by the executor of the "
                 "Full tier (default tier)",
        "components": [c for c, m in COMPONENTS.items() if "lightweight" in m["tiers"]],
        "compose_profiles": [],
    },
    "standard": {
        "label": "Standard",
        "blurb": "lightweight tier + application images and the frontend site; "
                 "the full web UI works out of the box",
        "components": [c for c, m in COMPONENTS.items() if c in [
            k for k, v in COMPONENTS.items() if "standard" in v["tiers"]]],
        "compose_profiles": ["app"],
    },
    "full": {
        "label": "Full",
        "blurb": "standard tier + Prometheus/Grafana observability stack + executor "
                 "and fixture targets; run real traffic-light executions right after install",
        "components": list(COMPONENTS),
        "compose_profiles": ["app", "observability", "executor"],
    },
}

_TIER_ALIASES = {}
for _key, _spec in TIERS.items():
    _TIER_ALIASES[_key] = _key
    _TIER_ALIASES[_spec["label"]] = _key
for _i, _key in enumerate(("lightweight", "standard", "full"), start=1):
    _TIER_ALIASES[str(_i)] = _key
_TIER_ALIASES.update({"轻量": "lightweight", "标准": "standard", "完整": "full"})


def _gb(mb):
    pass
    return "%.1fGB" % (mb / 1024.0)


def tier_requirements(tier):
    pass
    spec = TIERS[tier]
    disk = sum(COMPONENTS[c]["disk_mb"] for c in spec["components"])
    mem = sum(COMPONENTS[c]["mem_mb"] for c in spec["components"])
    return {"tier": tier, "disk_mb": disk, "mem_mb": mem}


def machine_capacity(path=None):
    pass
    usage = shutil.disk_usage(path or os.path.expanduser("~"))
    disk_gb = usage.free / (1024.0 ** 3)
    try:
        with open("/proc/meminfo", encoding="utf-8") as fh:
            mem_kb = int(fh.readline().split()[1])  # MemTotal
        mem_gb = mem_kb / (1024.0 ** 2)
    except OSError:
        mem_gb = (os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")) / (1024.0 ** 3)
    return {"disk_gb": disk_gb, "mem_gb": mem_gb}


def render_tiers_text():
    pass
    cap = machine_capacity()
    lines = ["Resource tiers (disk/memory needs are generated from the component matrix, for reference):"]
    for i, key in enumerate(("lightweight", "standard", "full"), start=1):
        req = tier_requirements(key)
        mark = " (default)" if key == DEFAULT_TIER else ""
        lines.append("  %d. %s%s -- %s; needs disk %s / memory %s (incl. base footprint)" % (
            i, TIERS[key]["label"], mark, TIERS[key]["blurb"],
            _gb(req["disk_mb"]), _gb(req["mem_mb"])))
    lines.append("  Available on this machine now: disk ~%s / memory ~%s" % (
        _gb(int(cap["disk_gb"] * 1024)), _gb(int(cap["mem_gb"] * 1024))))
    return "\n".join(lines)


def compose_profiles(tier):
    pass
    return list(TIERS.get(tier or DEFAULT_TIER, TIERS[DEFAULT_TIER])["compose_profiles"])


def ask(input_text=None, answers_path=None, tier=None, unattended=False):
    pass
    stream = io.StringIO(input_text if input_text is not None else "")
    if input_text is None and not unattended:
        stream = sys.stdin
    print(render_tiers_text())
    line = None
    if stream is not sys.stdin:
        raw = stream.readline()
        line = raw.strip() if raw else None
    else:
        try:
            raw = stream.readline()
            line = raw.strip() if raw else None
        except (EOFError, ValueError):
            line = None
    if tier:
        chosen = _TIER_ALIASES.get(str(tier).strip().lower()) or _TIER_ALIASES.get(str(tier).strip())
        if not chosen:
            raise ValueError('tier "%s" is out of range -- it must be one of '
                             "lightweight / standard / full "
                             "(entering 1/2/3 in the wizard also works)" % tier)
    elif line is None or line == "":
        chosen = DEFAULT_TIER
    else:
        chosen = _TIER_ALIASES.get(line.lower()) or _TIER_ALIASES.get(line)
        if not chosen:
            print('  ↳ "%s" is not an option; continuing with the default "%s" '
                  "(later steps unaffected)" % (line, TIERS[DEFAULT_TIER]["label"]))
            chosen = DEFAULT_TIER
    answers = {}
    if answers_path and os.path.exists(answers_path):
        try:
            with open(answers_path, encoding="utf-8") as fh:
                answers = json.load(fh)
            if not isinstance(answers, dict):
                answers = {}
        except (OSError, ValueError):
            answers = {}
    answers["tier"] = chosen
    if answers_path:
        with open(answers_path, "w", encoding="utf-8") as fh:
            json.dump(answers, fh, ensure_ascii=False, indent=2)
    print("  ↳ resource tier = %s (%s)" % (chosen, TIERS[chosen]["label"]))
    return answers


def install_matrix(tier):
    pass
    if tier not in TIERS:
        raise ValueError('tier "%s" is out of range -- choose lightweight / standard / full' % tier)
    steps = []
    for name in TIERS[tier]["components"]:
        comp = COMPONENTS[name]
        steps.append({
            "id": name,
            "probe": comp["probe"],
            "install": comp["install"],
            "rollback": comp["rollback"],
            "artifact": comp.get("artifact"),
        })
    return steps


def plan_execution(tier, installed_probe=None):
    pass
    installed_probe = installed_probe or {}
    steps = install_matrix(tier)
    run, skip = [], []
    for step in steps:
        (skip if installed_probe.get(step["id"]) else run).append(step)
    return {"run": run, "skip": skip,
            "rollback_stack": [s["id"] for s in reversed(run)]}


def verify_sha256(path, expected):
    pass
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    if actual.lower() != str(expected).strip().lower():
        raise ValueError(
            "artifact checksum mismatch: %s actual=%s expected=%s. Re-download the "
            "component (or switch to an internal mirror and re-run the same command; "
            "mirror base URL lives in System settings -> Installer configuration, "
            "key installkit.mirror-base-url)"
            % (os.path.basename(path), actual, expected))
    return True


def main(argv=None):
    parser = argparse.ArgumentParser(prog="resource_tiers.py",
                                     description="resource tier wizard + component install matrix (tier/component mapping registered in this file)")
    sub = parser.add_subparsers(dest="command", required=True)
    p_ask = sub.add_parser("ask", help="one-line tier prompt (EOF = default tier), result merged into answers")
    p_ask.add_argument("--answers-file", required=True)
    p_ask.add_argument("--tier", default="", help="explicit tier from CLI (takes priority over the prompt stream)")
    p_ask.add_argument("--unattended", action="store_true", help="zero prompts: empty input stream falls back to the default")
    sub.add_parser("render", help="print the three-tier panel (programmatic numbers)")
    p_matrix = sub.add_parser("matrix", help="print the plan for a tier (run/skip/rollback_stack)")
    p_matrix.add_argument("--tier", default=DEFAULT_TIER)
    p_verify = sub.add_parser("verify", help="verify a file's sha256 (failure prints the reason and exits)")
    p_verify.add_argument("--file", required=True)
    p_verify.add_argument("--sha256", required=True)
    args = parser.parse_args(argv)
    if args.command == "ask":
        ask(answers_path=args.answers_file, tier=args.tier or None,
            unattended=args.unattended)
        return 0
    if args.command == "render":
        print(render_tiers_text())
        return 0
    if args.command == "matrix":
        plan = plan_execution(args.tier)
        print("Will install: %s" % ", ".join(s["id"] for s in plan["run"]))
        print("Already installed, skipped: %s" % (", ".join(s["id"] for s in plan["skip"]) or "none"))
        print("Rollback order on failure: %s" % " <- ".join(plan["rollback_stack"]))
        return 0
    if args.command == "verify":
        verify_sha256(args.file, args.sha256)
        print("Checksum OK: %s" % os.path.basename(args.file))
        return 0
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(2)
