#!/usr/bin/env bash
# Remove the service-agent from a bare node — drains first, mirroring the
# k8s uninstall semantics: stop the claim intake (agent, plus the optional
# runner unit), wait the drain window for in-flight tasks, only then take the
# unit and the credential file down. Idempotent: a node that never received
# the unit (or was already cleaned) exits 0 with an honest note.
#
# Credentials are removed with the unit; state (release links, staging slots)
# is kept unless EXECUTOR_NODE_PURGE_STATE=1 says otherwise.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bare-lib.sh"

bare_prepare "${BASH_SOURCE[0]}"
bare_require_writable "${BARE_UNIT_DIR}" "$(dirname "${BARE_ENV_FILE}")"
unit="${BARE_UNIT_DIR}/${BARE_UNIT_NAME}.service"
if [ ! -f "${unit}" ]; then
  bare_log "unit ${BARE_UNIT_NAME} is not installed on this host — nothing to do"
  exit 0
fi

drain_wait="${EXECUTOR_NODE_DRAIN_WAIT_SECONDS:-120}"
bare_log "draining: stopping fleet claim intake, then waiting ${drain_wait}s for in-flight tasks to finish before teardown"
systemctl stop "${BARE_UNIT_NAME}"
if [ -n "${EXECUTOR_NODE_RUNNER_UNIT:-}" ]; then
  systemctl stop "${EXECUTOR_NODE_RUNNER_UNIT}"
  bare_log "runner unit ${EXECUTOR_NODE_RUNNER_UNIT} stopped; it will not claim new tasks"
fi
sleep "${drain_wait}"
systemctl disable "${BARE_UNIT_NAME}" >/dev/null 2>&1 || true
rm -f "${unit}"
systemctl daemon-reload
rm -f "${BARE_ENV_FILE}"
if [ "${EXECUTOR_NODE_PURGE_STATE:-0}" = "1" ]; then
  rm -rf "${BARE_STATE_DIR}"
  bare_log "state directory ${BARE_STATE_DIR} purged"
fi
bare_log "${BARE_UNIT_NAME} removed; the platform ledger ages this node out through lease reclaim — the fleet page list re-reads silently, no manual refresh needed"
