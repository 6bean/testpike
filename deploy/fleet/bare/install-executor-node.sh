#!/usr/bin/env bash
# Install (or idempotently re-apply) the testPike service-agent on a bare
# (non-Kubernetes) node: one-time enroll exchange (N5 pairing through the
# api /internal channel — never a second pairing implementation), pinned
# payload, credential file, systemd unit hosting.
#
# Required environment:
#   EXECUTOR_NODE_BASE_URL          api endpoint this node registers against
#   EXECUTOR_NODE_ENROLL_TOKEN      one-time token from the fleet enroll
#                                   wizard; not needed when this node is
#                                   already paired (idempotent re-apply)
# Optional:
#   EXECUTOR_NODE_PACKAGE_URL       local .tgz path or https URL for the
#                                   pinned node payload; when set,
#                                   EXECUTOR_NODE_PACKAGE_SHA256 is required
#   EXECUTOR_NODE_DRAIN_WAIT_SECONDS / *_UNIT_DIR / *_ENV_FILE / *_STATE_DIR /
#   *_INSTALL_ROOT / *_AGENT_UNIT / *_PYTHON   redirections for containers,
#   staging hosts and the contract test-suite (all default to production
#   paths; nothing is hardcoded inside this script body).
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bare-lib.sh"

bare_prepare "${BASH_SOURCE[0]}"
[ -n "${BARE_BASE_URL}" ] \
  || bare_die "EXECUTOR_NODE_BASE_URL is required (the api endpoint this node registers against, e.g. http://<platform-host>:<api-port>)"
bare_require_writable "${BARE_UNIT_DIR}" "$(dirname "${BARE_ENV_FILE}")" \
                      "${BARE_STATE_DIR}" "${BARE_INSTALL_ROOT}"
# Distro preflight before anything lands: a host that cannot run the unit
# (systemd absent, user domain without linger) must be named with its real
# fix path instead of getting a dead /etc/systemd/system file.
"${BARE_PYTHON}" "${BARE_DISTRO_PROFILE}" preflight --domain "${BARE_UNIT_DOMAIN}" \
  || bare_die "systemd preflight refused this host — the reason above names the fix path"
bare_fetch_package

WAS_PAIRED=0
if [ -s "${BARE_ENV_FILE}" ] && grep -q '^SERVICE_AGENT_TOKEN=' "${BARE_ENV_FILE}"; then
  WAS_PAIRED=1
  token="$(sed -n 's/^SERVICE_AGENT_TOKEN=//p' "${BARE_ENV_FILE}" | head -1)"
else
  [ -n "${EXECUTOR_NODE_ENROLL_TOKEN:-}" ] \
    || bare_die "this node is not paired yet: issue a one-time token on the fleet page (executor-pool enroll wizard), then re-run with EXECUTOR_NODE_ENROLL_TOKEN=<token> (the token is exchanged once and never stored in shell history by this script)"
  "${BARE_PYTHON}" "${BARE_ENROLL_CLIENT}" enroll \
    --base-url "${BARE_BASE_URL}" --token "${EXECUTOR_NODE_ENROLL_TOKEN}" \
    || bare_die "enroll exchange failed; the platform response above names the reason (token expired, fingerprint bound elsewhere, or endpoint unreachable)"
  token="${EXECUTOR_NODE_ENROLL_TOKEN}"
fi

bare_write_env "${token}"
bare_render_unit
systemctl daemon-reload
if [ "${WAS_PAIRED}" = "1" ] && systemctl is-active --quiet "${BARE_UNIT_NAME}"; then
  systemctl restart "${BARE_UNIT_NAME}"
  bare_log "already paired — re-applied the pinned payload/unit and restarted ${BARE_UNIT_NAME}"
else
  systemctl enable --now "${BARE_UNIT_NAME}"
  bare_log "service-agent installed and started; the node now heartbeats and consumes fleet commands under mutual authentication"
fi
bare_log "watch this node come online on the fleet page pool card (no manual refresh needed)"
