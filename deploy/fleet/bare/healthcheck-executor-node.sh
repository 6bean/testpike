#!/usr/bin/env bash
# Six-check healthcheck for a bare executor node (the node-side consumer of
# the fleet_doctor single-source judge — never a second check implementation).
#
# What it can answer locally is judged; what only the platform ledger knows
# (heartbeat age, capacity, running count, gold version) degrades honestly to
# "unknown" unless the operator or the cluster page supplies a facts document
# via EXECUTOR_NODE_FACTS_JSON. Exit codes: 0 all green, 1 any red,
# 2 any unknown with no red.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bare-lib.sh"

bare_prepare "${BASH_SOURCE[0]}"

facts="$("${BARE_PYTHON}" - \
    "${BARE_ENV_FILE}" "${BARE_BASE_URL}" \
    "${EXECUTOR_NODE_FACTS_JSON:-}" "${EXECUTOR_NODE_NAME:-$(hostname)}" \
    "${BARE_REPO_ROOT}/installkit" <<'PY'
import json, os, sys
env_file, base_url, facts_file, name, kit_dir = sys.argv[1:6]
if facts_file and os.path.exists(facts_file):
    with open(facts_file, encoding="utf-8") as fh:
        sys.stdout.write(fh.read())
    raise SystemExit(0)
enrolled = False
base = base_url
if os.path.isfile(env_file):
    pairs = {}
    with open(env_file, encoding="utf-8") as fh:
        for line in fh:
            if "=" in line and not line.startswith("#"):
                k, v = line.strip().split("=", 1)
                pairs[k] = v
    enrolled = bool(pairs.get("SERVICE_AGENT_TOKEN"))
    base = base or pairs.get("SERVICE_AGENT_BASE_URL") or ""
queue_reachable = None
if base:
    sys.path.insert(0, kit_dir)
    from enroll_client import _get  # transport single source
    probe = _get(base, "/internal/cluster/installkit-config")
    queue_reachable = probe is not None and str(probe.get("code")) == "0"
fact = {
    "name": name,
    "enrolled": enrolled,
    "heartbeat_age_seconds": None,
    "version": os.environ.get("EXECUTOR_NODE_VERSION") or None,
    "gold_version": os.environ.get("EXECUTOR_GOLD_VERSION") or None,
    "queue_reachable": queue_reachable,
    "capacity": None,
    "running_cnt": None,
}
sys.stdout.write(json.dumps({"nodes": [fact]}))
PY
)"

printf '%s' "${facts}" | "${BARE_PYTHON}" "${BARE_REPO_ROOT}/installkit/fleet_doctor.py" --facts -
