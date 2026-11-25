#!/usr/bin/env bash
# Launch a detached verification window the right way: preflight first, hard wall
# always, terminal anchor always, and one heartbeat line so a poll round can tell
# "still running" from "stalled" without guessing.
#
# Why this exists (measured on the AO close snapshot): a window without a declared
# budget ran 2h04m, one of its triage reruns (53m) had to be voided because the
# service under test was still running a stale jar, and a rerun whose teardown hung
# sat for 250s+ of CPU with no output while the chain reported "still running".
# The three guards below are mechanical, not conventional:
#   * no window starts without a passing preflight (or an explicit override that
#     prints a WARN, so the exception is on the record);
#   * every window carries a wall-clock cap, so it can never outlive its own ETA;
#   * every window writes its terminal anchor, so the next round reads a verdict.
#
# Usage:
#   run_verification_window.sh --name tp-e2e-triage --log /tmp/x.log \
#       [--node-list /tmp/x.txt] [--cap-seconds 3600] [--heartbeat-seconds 240] \
#       [--skip-preflight] -- <command ...>
# Print the poll recipe at the end; the chain relays read the ledger, not prose.
set -u
# Authority is THIS FILE's own repo, never an inherited REPO_ROOT export: an
# observed 2026-11-13 carrier leak (the gateway carried the second chain's
# REPO_ROOT) made a main-chain window cd into another chain's worktree and
# rehost that chain's lane services. A call by absolute path resolves to the
# tree holding the launcher; an inherited stale value is silently ignored.
REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel 2>/dev/null || echo $HOME/code/testPike)"
LEDGER="${TP_WINDOW_LEDGER:-/tmp/tp-windows.ledger.tsv}"
NAME="" LOG="" ANCHOR="" NODELIST="" CAP="${TP_WINDOW_CAP_SECONDS:-3600}" OWNER_TAG="${TP_WINDOW_OWNER:-chain}"
HEART="${TP_WINDOW_HEARTBEAT_SECONDS:-240}" SKIP_PRE="" CMD=""
# Explicit environment passthrough (--setenv KEY=VALUE, repeatable). Needed
# because a nested systemd-run does NOT propagate the caller's environment
# (observed r7b/r8: relay-exported TP_L4_ROOT was lost in the inner unit and
# the window silently ran on the default path). These lines are written into
# the generated payload after the chain-env load, so the tree's own .env still
# wins over anything the launcher inherited — passthrough is opt-in per key.
SETENV=()
LIVE="${TP_WINDOW_LIVE_FILE:-/tmp/testpike-tick-live.log}"
# One list for every slot-owned key a launcher might leak (identity + data
# plane + service base URLs). Referenced by both the preflight guard below and
# the generated payload, so a new key is dropped everywhere by editing here.
# Raw port keys and the preflight's own service list belong here too: a leaked
# slot4 ADMIN_PORT/CORE_PORT makes conftest fall back to the foreign port band
# (the window would drive another chain's admin), and a leaked
# TP_PREFLIGHT_SERVICE_PORTS makes THIS chain's preflight audit another
# chain's services - both observed 2026-11-07 on the main chain.
LEAK_GUARD_KEYS=(TP_CHAIN_SLOT TP_CHAIN_NAME DB_URL REDIS_DATABASE REDIS_HOST REDIS_PORT REDIS_HOST_PORT REDIS_PASSWORD MQ_VHOST MQ_AMQP_HOST_PORT MQ_MGMT_HOST_PORT S3_BUCKET S3_ENDPOINT S3_ACCESS_KEY S3_SECRET_KEY SWF_META_DB_NAME SWF_S3_HOST_PORT SWF_MASTER_HOST_PORT SWF_FILER_HOST_PORT TP_ADMIN_BASE TP_API_BASE TP_CORE_BASE TP_AIS_BASE TP_MOCK_MGMT_BASE TP_DLQ_EGRESS_ENABLED ADMIN_PORT API_PORT CORE_PORT AISERVICE_PORT SERVER_PORT CORE_BASE_URL STORAGE_LOCAL_PUBLIC_BASE_URL TP_PREFLIGHT_SERVICE_PORTS TP_E2E_BASE_URL TP_E2E_API_BASE)

while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="$2"; shift 2 ;;
    --log) LOG="$2"; shift 2 ;;
    --anchor) ANCHOR="$2"; shift 2 ;;
    --node-list) NODELIST="$2"; shift 2 ;;
    --cap-seconds) CAP="$2"; shift 2 ;;
    --heartbeat-seconds) HEART="$2"; shift 2 ;;
    --skip-preflight) SKIP_PRE="1"; shift ;;
    --owner) OWNER_TAG="$2"; shift 2 ;;
    --setenv) SETENV+=("$2"); shift 2 ;;
    --) shift; CMD="$*"; break ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
missing=""
[ -z "$NAME" ] && missing="$NAME --name"
[ -z "$LOG" ] && missing="$LOG --log"
[ -z "$ANCHOR" ] && missing="$ANCHOR --anchor"
[ -z "$CMD" ] && missing="$CMD -- <command>"
if [ -n "$missing" ]; then
  echo "missing required argument(s):${missing}" >&2
  echo "usage: run_verification_window.sh --name N --log L --anchor A [--node-list F] [--cap-seconds S] -- <command ...>" >&2
  exit 2
fi
# Truncation guard (observed 2026-11-07, third hit of the quoting trap): an
# unquoted "cd X && pytest ..." is split by the CALLER's shell, so the launcher
# receives only "cd X" as the window command, it succeeds instantly, and the
# anchor reports a green verdict for a test that never ran. A bare cd as the
# whole command is never a real window - refuse it here instead of lying later.
if [ "${CMD%% *}" = cd ] && ! [[ "$CMD" == *"&&"* || "$CMD" == *";"* ]]; then
  echo "[reject] window command is a bare 'cd' with no payload - the caller's shell split the && pipeline; quote the whole command as one argument after --" >&2
  exit 2
fi
# Flattened-wrapper guard (observed 2026-11-13, fourth hit of the quoting trap):
# a caller passing "-- bash -c 'cd X && pytest ...'" loses the inner quotes to
# CMD="$*", so the payload runs a no-op subshell cd and the real command in the
# launcher's directory - the window then collects zero tests, exits 5, and any
# last-line reader that trusts the anchor shape reports a clean verdict for a
# run that never happened. Refuse payloads that still carry a nested shell
# wrapper: recipes belong in a .sh file passed by path.
if [[ "$CMD" =~ (^|[[:space:]])bash[[:space:]]+-c[[:space:]] || "$CMD" =~ (^|[[:space:]])sh[[:space:]]+-c[[:space:]] ]]; then
  echo "[reject] window command contains a flattened 'bash -c' wrapper - quoting is lost through the launcher argument list and the payload would run in the wrong directory. Write the recipe into a .sh file and pass it as: -- bash /path/to/recipe.sh" >&2
  exit 2
fi
# Anchor names must not carry the trailing "=": the harness appends "=<rc>", so
# an anchor passed as "X=" would emit "X==<rc>" and pollute prefix greps.
ANCHOR="${ANCHOR%=}"

# 1) Refuse to stack on a window that is still alive under the same name.
if systemctl --user is-active --quiet "tp-${NAME}" 2>/dev/null; then
  echo "[reject] unit tp-${NAME} is still active; poll it instead of relaunching" >&2
  exit 3
fi

# 2) Preflight: the environment must be current and reachable before the window.
# Drop inherited chain-identity exports first: a long-lived launcher (the agent
# gateway) can carry another chain's slot exports, and exported names always
# shadow values re-read from .env, so both the preflight below and the payload
# would silently run on that foreign data plane (observed 2026-11-07: a leaked
# slot4 export made the main chain's preflight report slot-consistency NOGO).
# The payload repeats the same drop; doing it here too covers the preflight,
# which runs before the payload is generated.
for _leaked in "${LEAK_GUARD_KEYS[@]}"; do
  if [ -n "${!_leaked:-}" ]; then
    echo "[WARN] clearing inherited ${_leaked} from the launcher environment (this tree's chain env is the single source)" >&2
    unset "${_leaked}"
  fi
done
# Preflight reads the environment as-is (no DB handle => honest WARN/FAIL, by
# design there is no implicit fallback), so load THIS tree's chain env through
# the one validated entry before running it — same entry the payload uses.
if ! . "${REPO_ROOT}/tools/ops/load_chain_env.sh" >/dev/null 2>&1; then
  echo "[reject] cannot load this tree's chain env before preflight for window ${NAME}" >&2
  exit 8
fi
# Explicit passthrough configures the SYNC PREFLIGHT, not just the payload.
# The leak guard above drops inherited values by key NAME, so it also drops a
# calling recipe's own honest subset declaration (observed 2026-11-22 r13b/r14:
# the dual real-client recipe exported TP_PREFLIGHT_SERVICE_PORTS for
# core/admin/api, the guard cleared it, the preflight fell back to the
# four-module shipped default and NOGO'd a healthy api-tier-only window on
# ai-service "no listener" - launcher exit 4 before the window was ever built).
# --setenv is opt-in per key at the call site, which is exactly the evidence
# the guard cannot see through an inherited value; honoring it here keeps the
# declaration honest without reopening the inherited-shadowing hole.
for _kv in ${SETENV[@]+"${SETENV[@]}"}; do
  export "${_kv%%=*}=${_kv#*=}"
done
if [ -z "$SKIP_PRE" ]; then
  PRE_ARGS=(--repo-root "$REPO_ROOT" --out "${LOG}.preflight.json")
  [ -n "$NODELIST" ] && PRE_ARGS+=(--node-list "$NODELIST")
  if ! /usr/bin/python3 "$REPO_ROOT/tools/verification/e2e_window_preflight.py" "${PRE_ARGS[@]}"; then
    echo "[reject] preflight NOGO for window ${NAME}: fix the named reason (or pass --skip-preflight knowingly)" >&2
    exit 4
  fi
else
  echo "[WARN] preflight skipped on request for window ${NAME} (stale-artifact and contention classes are then invisible until the window reports them)"
fi

PAYLOAD="$(mktemp /tmp/${NAME}-payload.XXXXXX.sh)"
START_TS="$(date +%s)"
cat > "$PAYLOAD" <<EOF
#!/usr/bin/env bash
# Generated by run_verification_window.sh; do not edit by hand.
# A unit's environment is inherited wholesale from whoever ran systemd-run,
# so a leaked slot export from another chain would shadow this chain's own
# .env (sourced assignments never override already-exported names). Drop the
# identity and data-plane handles first, then let this tree's install-time
# .env be the single source (R47 chain-environment isolation).
unset ${LEAK_GUARD_KEYS[*]}
cd "${REPO_ROOT}" || exit 9
: > "${LOG}"
# Chain configuration loads through the ONE validated entry (identity,
# symlink and slot-consistency checks are fail-closed) — a raw
# "set -a; source .env" here would run the window on a config nobody checked.
if ! . "${REPO_ROOT}/tools/ops/load_chain_env.sh" 2>>"${LOG}"; then
  echo "${ANCHOR}=8" >> "${LOG}"
  echo "[\$(date +%T)] window ${NAME} REJECT chain-env load failed (see ${LOG})" >> "${LIVE}"
  exit 8
fi
# Explicitly forwarded values (see --setenv at the top): written after the
# chain-env load so an opt-in key from the relay still lands inside the unit.
$(for _kv in ${SETENV[@]+"${SETENV[@]}"}; do echo "export ${_kv%%=*}=\"${_kv#*=}\""; done)
(
  while :; do
    elapsed=\$(( \$(date +%s) - ${START_TS} ))
    echo "[\$(date +%T)] window ${NAME} alive elapsed=\${elapsed}s cap=${CAP}s unit=tp-${NAME}" \\
      >> "${LIVE}"
    sleep ${HEART}
  done
) &
HB=\$!
trap 'kill \$HB 2>/dev/null || true' EXIT
echo "[\$(date +%T)] window ${NAME} start cap=${CAP}s log=${LOG}" >> "${LIVE}"
# bash -c wrapper: the command is a shell pipeline, not a single executable, and
# "timeout" cannot run shell builtins like cd directly.
timeout ${CAP} bash -c '${CMD}' >> "${LOG}" 2>&1
rc=\$?
# The verdict anchor belongs to the window itself. When the command already
# echoed it (the chained-suite recipe ends with 'echo ANCHOR=\$A'), the harness
# must NOT append a second same-prefix line: the inner 'bash -c' exits with the
# status of its LAST command (that echo, always 0), so an unconditional wrapper
# line would overwrite a red verdict with a false green for every last-line
# reader (observed 2026-11-06: suite rc=1 yet monitor read '...==0' as done).
if grep -q "^${ANCHOR}=" "${LOG}" 2>/dev/null; then
  echo "${ANCHOR}-WRAP=\${rc}" >> "${LOG}"
else
  echo "${ANCHOR}=\${rc}" >> "${LOG}"
fi
[ \$rc -eq 124 ] && echo "[\$(date +%T)] window ${NAME} HIT WALL after ${CAP}s (no verdict inside it; treat results as incomplete)" >> "${LIVE}"
echo "[\$(date +%T)] window ${NAME} exit=\${rc}" >> "${LIVE}"
# T548: every window ships its verdict together with the service-log noise
# ledger row (device-generated, single shape). Non-gating for the test
# anchor: baselined debt is a separate clearing ledger (T552); the row file
# existing next to the log is the audit hook the poll tick reads.
python3 "${REPO_ROOT}/tools/log_noise_ledger.py" line > "${LOG}.lognoise" 2>&1 || true
echo "[\$(date +%T)] window ${NAME} log-noise row -> ${LOG}.lognoise: \$(head -1 "${LOG}.lognoise" 2>/dev/null)" >> "${LIVE}"
EOF
chmod +x "$PAYLOAD"

# 3) Detach into its own transient unit so a gateway/session exit cannot reap it
#    (documented pitfall: nohup children inherit the caller's cgroup and die with it).
#    --user scope: this stack's services live in the user manager (system-level
#    systemd-run is refused here and produced a silent "spawn failed").
systemd-run ${TP_WINDOW_SCOPE:+$TP_WINDOW_SCOPE} --user --collect --unit="tp-${NAME}" \
  --working-directory="$REPO_ROOT" \
  /usr/bin/bash "$PAYLOAD" >/dev/null 2>&1 || { echo "[reject] systemd-run failed for tp-${NAME}" >&2; exit 5; }
sleep 2
if ! systemctl --user is-active --quiet "tp-${NAME}"; then
  echo "[warn] unit tp-${NAME} is not active 2s after start - read ${LOG} before assuming anything"
fi
# One live row per owner: earlier windows of this chain are no longer worth
# tracking (the monitor retires a row only when its wake is delivered), so
# clear this owner's stale rows before registering the new window.
if [ -f "$LEDGER" ]; then
  tmp_ledger=$(mktemp "${LEDGER}.tmp.XXXXXX") || tmp_ledger=""
  if [ -n "$tmp_ledger" ]; then
    awk -F'\t' -v o="$OWNER_TAG" '$7!=o' "$LEDGER" > "$tmp_ledger" \
      && mv "$tmp_ledger" "$LEDGER" || rm -f "$tmp_ledger"
  fi
fi
# The slot column (8) names the launching chain: the log-noise ledger gives
# each window log to that chain's own account instead of guessing from the
# directory name; a row without the column reads as unattributed.
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%FT%T)" "$NAME" "$LOG" "$ANCHOR" "$CAP" \
  "${NODELIST:-full-suite}" "$OWNER_TAG" "${TP_CHAIN_SLOT:-0}" >> "$LEDGER"

echo "[launched] unit=tp-${NAME} log=${LOG} anchor=${ANCHOR} cap=${CAP}s heartbeat=${HEART}s ledger=${LEDGER}"
echo "[poll-recipe] tail -5 ${LOG}; systemctl is-active tp-${NAME}; "
echo "              verdict = last line matching ${ANCHOR}= (absent + active = still running; "
echo "              absent + inactive = stalled, reclaim by pid)"
