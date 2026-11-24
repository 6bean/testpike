# -*- coding: utf-8 -*-
"""Clean-machine profile×tier matrix runner for the open-delivery batch (T293d).

Declarative recipe for the acceptance matrix: each cell names one OS profile and
one resource tier from the installer's own single sources (tier value domain is
installkit/resource_tiers.py — never a second list), and the cell command is the
real unattended install followed by the greenlight proof. ``--plan`` prints the
commands for a window launcher without executing anything; the live matrix runs
as a backgrounded verification window per the long-command discipline.

Blocked cells carry an explicit reason instead of silently vanishing: the WSL
profile waits on the machine-level bridge-NAT ticket and needs a real WSL host
(not a container profile), so it stays annotated at the queue tail rather than
burning window budget on a known-dead path.
"""
import json
import os
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_ROOT, "installkit"))

import resource_tiers  # noqa: E402  (tier single source)
import host_ports  # noqa: E402  (host-port band key single source)

PROFILES = {
    "alpine": {
        "image": "alpine:3.20",
        # Bare-image prerequisite bootstrap (apk line, sh-compatible): the
        # installer itself needs bash/python3/git/curl and compose needs the
        # docker CLI + compose plugin. Everything above this line stays the
        # installer component matrix's job — this is only the documented
        # "prerequisites" one-liner a fresh host would run before installing.
        "prereq": ("apk update && apk add --no-cache "
                   "bash python3 py3-pip git curl docker-cli docker-cli-compose"),
    },
    "ubuntu2204": {
        "image": "ubuntu:22.04",
        # ubuntu:22.04 ships bash but NOT python3/pip (r49 lesson: the cell
        # died on "python3: command not found" on every installer step); the
        # bare image also lacks the docker client pair and fetch tools.
        # Package names follow the installer's own apt repair lines in
        # installkit/pkg_probe.py (python3 + python3-pip, git, curl,
        # docker.io + docker-compose-v2).
        "prereq": ("apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y "
                   "--no-install-recommends python3 python3-pip "
                   "docker.io docker-compose-v2 git curl ca-certificates"),
    },
    # WSL is not a pullable image: it means a clean Windows+WSL host boot.
    "wsl": {"image": None},
}

TIERS = resource_tiers.TIERS

# alpine proves the apk line end-to-end on the lightest tier; ubuntu2204 proves
# the apt line plus app-profile images on the standard tier; full tier rides the
# next milestone window together with the WSL host once unblocked.
_CELLS = (
    {"profile": "alpine", "tier": "lightweight", "runnable": True},
    {"profile": "ubuntu2204", "tier": "standard", "runnable": True},
    {"profile": "wsl", "tier": "full", "runnable": False,
     "blocked_note": "机级在册票未结（WSL bridge NAT 出向故障，容器内下载全断）+"
                     "画像需真实 WSL 宿主非容器——挂环境窗注记不空转，"
                     "解封条件=机级票结案后由登记窗裁净机轮"},
)

# Machine-level workaround, not a delivery claim: on this host bridge-networked
# containers lose egress (open ticket), so matrix containers run on the host
# network. Nothing under installkit/ or deploy/ ships this flag.
_NET_NOTE = "临时面：本机 WSL bridge NAT 出向故障在册票，画像窗叠 --network=host"


def cells():
    out = []
    for c in _CELLS:
        row = dict(c)
        if row.get("runnable"):
            row["net_note"] = _NET_NOTE
        out.append(row)
    return out


# Host-port band per cell: container names are pinned via TP_CONTAINER_PREFIX,
# and the compose host mappings (every one shipped as ${KEY:-shipped default})
# must also dodge the ports a live stack already holds on this machine. Each
# runnable cell takes the next stride-wide segment of the band; the band base is
# hot-changeable via TP_MATRIX_PORT_BASE for hosts that occupy 254xx too.
# Key list single source = installkit/host_ports.py (r42 lesson: the installer
# check and this runner must resolve the same keys with the same precedence).
_HOST_PORT_KEYS = host_ports.HOST_PORT_KEYS
_BAND_STRIDE = 20


def cell_port_env(profile, tier):
    base = int(os.environ.get("TP_MATRIX_PORT_BASE", "25400"))
    order = [(c["profile"], c["tier"]) for c in _CELLS]
    idx = order.index((profile, tier))
    return {key: str(base + idx * _BAND_STRIDE + offset + 1)
            for offset, key in enumerate(_HOST_PORT_KEYS)}


def proof_executor_flag(tier):
    """Executor capability the proof may assume for this tier.

    Derived from the installer matrix itself (executor ships with the tier?),
    not a re-typed tier list: tiers without the executor component accept the
    honest queued-standby verdict instead of waiting on a missing claimant.
    """
    ships = tier in resource_tiers.COMPONENTS["executor"]["tiers"]
    return "--executor=required" if ships else "--executor=standby-ok"


def cell_command(cell):
    """Full docker-run command for one runnable cell (prereq bootstrap, install, then proof)."""
    image = PROFILES[cell["profile"]]["image"]
    tier = cell["tier"]
    compose_ns = "tp-matrix-%s-%s" % (cell["profile"], tier)
    ports = cell_port_env(cell["profile"], tier)
    admin_port = ports["APP_ADMIN_HOST_PORT"]
    # App-tier stage (r44 lesson): the installer never starts the four app
    # services (by design the wizard only seeds middleware + data and honestly
    # degrades the demo seeding while services are down), so the clean-machine
    # proof must bring the compose app profile up, wait for admin readiness
    # with a bounded hot-changeable retry, then re-run the single seed channel
    # against the live stack before the greenlight proof runs.
    app_stage = (
        "docker compose --profile app up -d && "
        # `&&` before the loop (r50 lesson): a `;` restarts the command list, so the
        # readiness loop kept polling and printed APP-TIER-NOT-READY even when the
        # install step above had already failed — the real reason got buried.
        "tries=0 && until curl -fsS http://127.0.0.1:%s/actuator/health >/dev/null 2>&1; do "
        "tries=$((tries+1)); [ \"$tries\" -le \"${TP_MATRIX_READY_TRIES:-90}\" ] || "
        "{ echo APP-TIER-NOT-READY admin=http://127.0.0.1:%s >&2; exit 1; }; sleep 2; done && "
        "python3 installkit/s1_bootstrap.py seed-demo --base-url http://127.0.0.1:%s && "
        % (admin_port, admin_port, admin_port))
    # Identity mount (host path == container path): compose inside the cell
    # resolves relative bind sources against this container's tree and hands
    # them to the HOST daemon, so any remapped path (old /opt/testpike) turns
    # real files into host-side auto-created empty directories.
    # Tier-aware acceptance (r47 lesson): only the full tier ships the
    # executor component, so lighter cells accept the honest queued-standby
    # verdict instead of waiting 120s on a capability they never install.
    install = ("cd %s && bash installkit/install.sh --scenario=first "
               "--tier=%s --unattended && "
               "%s"
               "python3 tools/verification/greenlight_demo_proof.py %s && "
               # green-only teardown (r51 lesson): a plain `compose down` skips
               # profile-gated services, so every sweep had to name the profiles
               # manually and app-tier containers lingered between rounds. The
               # recipe removes the whole namespace itself on green; on red the
               # `&&` chain stops before this segment and the scene stays for
               # triage (in-cell service logs are the evidence).
               "docker compose --profile app --profile executor "
               "--profile observability down -v"
               % (_ROOT, tier, app_stage, proof_executor_flag(tier)))
    prereq = PROFILES[cell["profile"]].get("prereq")
    # bootstrap runs with the tree already mounted at its own path so the
    # profile line can be cross-checked against it, but the installer still
    # starts only after it
    inner = ("%s && %s" % (prereq, install)) if prereq else install
    # Container names are machine-global and host ports are machine-exclusive:
    # inject the instance prefix plus the cell's own port band, and repoint the
    # values compose cannot nest-substitute (DB URL + nginx upstreams).
    env_pairs = (("COMPOSE_PROJECT_NAME", compose_ns),
                 ("TP_CONTAINER_PREFIX", compose_ns),
                 ("APP_DB_URL", "jdbc:postgresql://%s-postgres:5432/testpike" % compose_ns),
                 ("TP_ADMIN_UPSTREAM", "%s-admin" % compose_ns),
                 ("TP_API_UPSTREAM", "%s-api" % compose_ns)) + tuple(ports.items()) + (
                 ("TP_ADMIN_BASE", "http://localhost:%s" % ports["APP_ADMIN_HOST_PORT"]),
                 ("TP_FIXTURE_BASE", "http://127.0.0.1:%s" % ports["TP_FIXTURE_HOST_PORT"]))
    env_args = " ".join("-e %s=%s" % kv for kv in env_pairs)
    return ("docker run --rm --network=host "
            "-v /var/run/docker.sock:/var/run/docker.sock "
            "-v {root}:{root} "
            "{env} "
            "-e ADMIN_INIT_PASSWORD "
            "{image} sh -lc '{inner}'").format(root=_ROOT, env=env_args,
                                               image=image, inner=inner)


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--plan", action="store_true", help="print cell table and commands, run nothing")
    args = ap.parse_args(argv)
    table = cells()
    if args.plan:
        for c in table:
            if c.get("runnable"):
                print("CELL-RUNNABLE %s/%s" % (c["profile"], c["tier"]))
                print(cell_command(c))
            else:
                print("CELL-BLOCKED %s/%s reason=%s"
                      % (c["profile"], c["tier"], c.get("blocked_note")))
        return 0
    print(json.dumps(table, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
