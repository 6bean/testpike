#!/usr/bin/env bash
# Detached E2E window for the login-state keep-alive journey (enroll ->
# double-GET pre-check -> scheduler round -> EXPIRED double stop -> replay).
# No logic is re-implemented (R40): jar freshness, the slot stack and the
# fixture station shape are consumed from the shared library, and the window
# runs through the generic launcher (preflight gate, wall cap, terminal
# anchor, owner ledger row per R46).
# Read anchors, in order (poll ticks, R9/R12):
#   KAJ-STACK-EXIT=<rc>    jars fresh + backend stack + vite preview settled
#   KAJ-FIXTURE-EXIT=<rc>  fixture station socket readiness (slot-offset port)
#   TP-KA-ALL=<rc>         pytest verdict (written inside the window payload)
#   KAJ-EXIT=<rc>          stage summary, mirrored to /tmp/tp-ka-journey-exit.anchor
set -uo pipefail
export PATH=$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:${PATH:-}
cd "$(dirname "$0")/../.." || exit 2
REPO_ROOT="$(pwd)"
LIVE=/tmp/testpike-tick-live.log
LOG_DIR=/tmp/tp-ka-journey
mkdir -p "$LOG_DIR"
ANCHOR_FILE=/tmp/tp-ka-journey-exit.anchor
WIN_NAME=login-state-keepalive-journey
say() { echo "[$(date +%T)] $*" | tee -a "$LIVE" >> "$LOG_DIR/chain.log"; }

. tools/ops/load_chain_env.sh || { say "KAJ LOAD_ENV_FAILED"; exit 2; }
SLOT="${TP_CHAIN_SLOT:?}"
export REPO_ROOT SLOT LIVE WINDOW_TAG=kajourney
# shellcheck source=/dev/null
source tools/ops/e2e_slot_stack.sh

say "KAJ start slot=$SLOT"
if check_jars_fresh && ensure_backend_stack && ensure_frontend_preview; then
  say "KAJ-STACK-EXIT=0"
else
  say "KAJ-STACK-EXIT=1"; echo "KAJ-EXIT=3" | tee "$ANCHOR_FILE" >> "$LIVE"; exit 3
fi

# Fixture station for the pre-check and the scheduler rounds: the protected
# door answers 200 only with a fresh short-lived HMAC token, 401 otherwise,
# so the journey exercises the real outbound exchange with zero stubs. The
# unit name carries this chain's slot identity (R47).
FIX_UNIT="tp-ka-fixture-slot${SLOT}"
export FIXTURE_PORT="${TP_L3_FIXTURE_PORT:-$((8899 + ${SERVER_PORT_OFFSET:-0}))}"
# Idempotent re-run support: a transient unit name cannot be launched again
# while still loaded, so reuse a live listener only when its owning process
# runs from this repo tree (owner check per the chain isolation rule).
fx_listener_pid() {
  ss -ltnp 2>/dev/null | grep ":${FIXTURE_PORT} " | grep -oP 'pid=\K[0-9]+' | head -1
}
fx_pid="$(fx_listener_pid)"
if [ -n "$fx_pid" ] && [ "$(readlink "/proc/$fx_pid/cwd" 2>/dev/null)" = "$REPO_ROOT" ]; then
  say "kajourney: fixture station pid $fx_pid already serves :$FIXTURE_PORT from this tree — reusing"
else
  systemd-run --user --collect --unit="$FIX_UNIT" /usr/bin/bash -c \
    "export PATH=\$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}; cd $REPO_ROOT; . tools/ops/load_chain_env.sh; export FIXTURE_PORT=$FIXTURE_PORT; exec /usr/bin/python3 -u executor/serve_fixtures.py >> $LOG_DIR/fixture.log 2>&1" \
    || { say "KAJ-FIXTURE-EXIT=1 (launch refused)"; echo "KAJ-EXIT=5" > "$ANCHOR_FILE"; exit 5; }
fi
fx_deadline=$(( $(date +%s) + 60 ))
until curl -s -o /dev/null --max-time 2 "http://localhost:${FIXTURE_PORT}/api/protected"; do
  [ "$(date +%s)" -lt "$fx_deadline" ] || { say "KAJ-FIXTURE-EXIT=1 (socket budget 60s)"; echo "KAJ-EXIT=5" > "$ANCHOR_FILE"; exit 5; }
  sleep 2
done
say "KAJ-FIXTURE-EXIT=0 port=$FIXTURE_PORT"

printf '%s\n' \
  "test_login_state_keepalive_journey.py::test_login_state_keepalive_journey" \
  > "$LOG_DIR/nodes.txt"

# The journey waits on real scheduler rounds and short-lived token expiry, so
# its per-test budget sits above the pytest.ini default (same posture as the
# revisit-window family).
bash tools/ops/run_verification_window.sh \
  --name "$WIN_NAME" \
  --log "$LOG_DIR/window.log" \
  --anchor TP-KA-ALL \
  --cap-seconds "${TP_KA_WINDOW_CAP_S:-1800}" \
  --node-list "$LOG_DIR/nodes.txt" \
  --owner t708-keepalive \
  -- 'cd frontend/tests/e2e && TP_E2E_BASE_URL="http://localhost:${VITE_PORT}" TP_E2E_API_BASE="http://localhost:${ADMIN_PORT}/api/v1" FIXTURE_PORT='"$FIXTURE_PORT"' TP_E2E_TIMEOUT_S=${TP_E2E_TIMEOUT_S:-900} /usr/bin/python3 -m pytest -q test_login_state_keepalive_journey.py'
launch_rc=$?
if [ "$launch_rc" -ne 0 ]; then
  say "KAJ-EXIT=6 (window launch refused rc=$launch_rc)"
  echo "KAJ-EXIT=6" > "$ANCHOR_FILE"; exit 6
fi

# Wait out the unit, then read the verdict with a 30s anchor grace (the
# payload writes its anchor right at unit exit).
deadline=$(( $(date +%s) + ${TP_KA_GUARD_DEADLINE_S:-1920} ))
while systemctl --user is-active --quiet "tp-${WIN_NAME}"; do
  [ "$(date +%s)" -lt "$deadline" ] || { say "KAJ guard hit while window still active"; break; }
  sleep 30
done
all_rc=""
for _ in $(seq 1 6); do
  # per-run window.log is the single source: the live log is shared and keeps
  # anchors of earlier windows, so grepping it after window.log let a stale
  # verdict override a fresh one (observed 2026-11-25 on the sibling recipe).
  all_rc="$(grep -hoE 'TP-KA-ALL=[0-9]+' "$LOG_DIR/window.log" 2>/dev/null | tail -1 | cut -d= -f2)"
  [ -n "$all_rc" ] && break
  sleep 5
done
[ -n "$all_rc" ] || all_rc="$(grep -hoE 'TP-KA-ALL=[0-9]+' "$LIVE" 2>/dev/null | tail -1 | cut -d= -f2)"
say "KAJ-EXIT=${all_rc:-1} verdict_anchor=${all_rc:-absent}"
{
  echo "KAJ-EXIT=${all_rc:-1}"
  echo "  window=${WIN_NAME} log=${LOG_DIR}/window.log"
} > "$ANCHOR_FILE"
[ "${all_rc:-1}" = "0" ] || exit 1
