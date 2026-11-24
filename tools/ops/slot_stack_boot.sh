#!/usr/bin/env bash
# Boot this chain's slot dev stack on slot ports (offset derived from the slot).
# Reuses the single-source launcher semantics (tools/ops/service_launcher.sh);
# no second copy of launch logic lives here (R40).
#
# Outcome anchor: the final line of the boot log is either
# SLOT-BOOT-EXIT=0 (all sockets LISTEN, ready for slot E2E) or
# SLOT-BOOT-EXIT=1 with the failed stage named just above it.
# Heartbeat: every stage appends to the live log per R9.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
export REPO_ROOT

# A systemd transient unit starts with CWD=HOME; the slot-identity resolution
# below reads the CURRENT working tree, so anchor the CWD to this repo first
# (otherwise chain_slot.py sees no slot and the boot aborts with SLOT_ENV_MISSING).
cd "$REPO_ROOT" || { echo "CWD_UNREACHABLE: $REPO_ROOT" >&2; exit 2; }

# Slot env for this chain: resolved, never named (main chain has none by design).
CHAIN_SLOT="$(python3 "$REPO_ROOT/scripts/chain_slot.py" env-path --no-create 2>/dev/null || true)"
SLOT_ENV="${SLOT_ENV:-$CHAIN_SLOT}"
if [ -z "$SLOT_ENV" ]; then
  echo "SLOT_ENV_MISSING: this chain has no slot env" >&2
  echo "  resolve it: python3 \"$REPO_ROOT/scripts/chain_slot.py\" env-path" >&2
  exit 2
fi
# Slot env (relative to REPO_ROOT as the launcher units source it): DB/redis/
# port overrides for chain slot 1. Secrets stay out of this file (R1) — the
# unit sources ./.env first, then this override layer.
export TP_SLOT_ENV="${TP_SLOT_ENV:-$SLOT_ENV}"
# Slot number is derived from the slot env file name (e.g. "<chain>.slot3.env"),
# never hardcoded: per-slot log roots keep parallel chains from colliding (R47).
SLOT_NO="$(basename "$SLOT_ENV" .env | sed 's/.*slot//')"
LIVE="${TP_LIVE_LOG:-/tmp/testpike-tick-live.log}"
LOG_DIR="/tmp/tp-slot${SLOT_NO}-stack"
BOOT_LOG="$LOG_DIR/boot.log"
mkdir -p "$LOG_DIR"
# Launcher helpers resolve jars by module-relative paths (core/target/...),
# so the shell CWD must be REPO_ROOT. Transient units start in $HOME — without
# this cd the present-jar lookup fails and every boot burns a rebuild then
# aborts with "produced no jar" (root cause of the three slot1 FAILs).
cd "$REPO_ROOT" || { echo "cd $REPO_ROOT failed" | tee -a "$BOOT_LOG"; exit 1; }

step() { echo "[$(date +%T)] slot${SLOT_NO}-boot: $*" | tee -a "$LIVE" >> "$BOOT_LOG"; }

# Ports come from the slot env through the single-source accessor — a
# hardcoded slot band here would boot one chain onto another's sockets (R47).
slot_value() {
  python3 "$REPO_ROOT/scripts/chain_slot.py" slot-value "$1" --env "$SLOT_ENV" --default "${2:-}"
}
ADMIN_P="$(slot_value ADMIN_PORT 8081)"
API_P="$(slot_value API_PORT 8082)"
CORE_P="$(slot_value CORE_PORT 8083)"
VITE_P="$(slot_value VITE_PORT 5173)"

# shellcheck source=/dev/null
source "$REPO_ROOT/tools/ops/service_launcher.sh"

# A slot released by a retired chain loses its MQ vhost with the slot, and a
# freshly issued slot never had one: core would then die in the AMQP close
# loop ("Socket closed"), health never turns green and the window burns its
# whole boot budget for nothing (observed 2026-11-22 slot4 journey-pack
# BOOT_FAIL). Reconcile through the delivery-face single source
# (tools/ops/ensure_slot_vhost.py — idempotent, owner-scoped to MQ_VHOST)
# before launching any service; failure aborts loudly, never falls through.
step "stage=mq-vhost-reconcile"
if ! vhost_out="$(python3 "$REPO_ROOT/tools/ops/ensure_slot_vhost.py" 2>&1)"; then
    step "FAIL mq vhost reconcile: $(printf '%s\n' "$vhost_out" | tail -1)"
    echo "SLOT-BOOT-EXIT=1" >> "$BOOT_LOG"
    exit 1
fi
step "mq vhost $(printf '%s\n' "$vhost_out" | tr '\n' ' ')"

step "stage=jar-build (core/admin/api from this worktree; first start call triggers mvn package)"
tp_start_unit core "$CORE_P" "$LOG_DIR/core.log" || { step "FAIL jar-build/core"; echo "SLOT-BOOT-EXIT=1" >> "$BOOT_LOG"; exit 1; }
tp_start_unit admin "$ADMIN_P" "$LOG_DIR/admin.log" || { step "FAIL admin-$ADMIN_P"; echo "SLOT-BOOT-EXIT=1" >> "$BOOT_LOG"; exit 1; }
tp_start_unit api "$API_P" "$LOG_DIR/api.log" || { step "FAIL api-$API_P"; echo "SLOT-BOOT-EXIT=1" >> "$BOOT_LOG"; exit 1; }

# SKIP_FRONTEND=1 boots the API tier only — for L3 windows there is no browser
# consumer, and waiting on vite (node deps under a worktree are commonly absent)
# burned a whole boot round (slot3 , 2026-11-06). E2E callers keep the
# full stack by leaving the switch unset.
EXPECT_PORTS="$CORE_P $ADMIN_P $API_P"
if [ "${SKIP_FRONTEND:-0}" = "1" ]; then
    step "stage=vite-$VITE_P skipped (SKIP_FRONTEND=1, API-only window)"
else
    step "stage=vite-$VITE_P (serves this worktree's frontend under test)"
    export TP_ADMIN_PORT="$ADMIN_P" TP_API_PORT="$API_P"
    tp_start_vite_unit "$VITE_P" "$LOG_DIR/vite.log" || { step "FAIL vite-$VITE_P"; echo "SLOT-BOOT-EXIT=1" >> "$BOOT_LOG"; exit 1; }
    EXPECT_PORTS="$EXPECT_PORTS $VITE_P"
fi

step "stage=health-wait"
deadline=$(( $(date +%s) + 300 ))
ok=1
while [ "$(date +%s)" -lt "$deadline" ]; do
    still=""
    for p in $EXPECT_PORTS; do
        ss -ltn 2>/dev/null | grep -q ":$p\b" || still="$still $p"
    done
    if [ -z "$still" ]; then ok=0; break; fi
    sleep 10
done
if [ "$ok" -eq 0 ]; then
    step "stage=done ports $ADMIN_P/$API_P/$CORE_P${EXPECT_PORTS##* } all LISTEN"
    echo "SLOT-BOOT-EXIT=0" >> "$BOOT_LOG"
else
    step "FAIL sockets not up after health-wait:$still"
    echo "SLOT-BOOT-EXIT=1" >> "$BOOT_LOG"
    exit 1
fi
