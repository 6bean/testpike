#!/usr/bin/env bash
# Rolling upgrade of an executor pool to a newly pinned image, coordinated
# with graceful drain: maxUnavailable=0 in the chart keeps every old runner
# serving until its replacement is registered, and the preStop hook plus the
# long termination grace let in-flight tasks finish before the pod exits. The
# platform-side lease reclaim is the timeout fallback for stragglers.
#
# The gold image reference comes from the environment; the fleet values
# generator endpoint (delivery pack 2b) will hand it over programmatically so
# operators never transcribe a tag by hand.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/fleet-lib.sh"

fleet_prepare
[ -n "${EXECUTOR_POOL_IMAGE_TAG:-}${EXECUTOR_POOL_IMAGE_SHA256:-}" ] \
  || fleet_die "an upgrade must name the target: set EXECUTOR_POOL_IMAGE_SHA256 (preferred) or EXECUTOR_POOL_IMAGE_TAG"
fleet_common_sets
PRE_REV="$(helm -n "${FLEET_NAMESPACE}" get values "${FLEET_RELEASE}" -o json 2>/dev/null || true)"
if [ -z "${PRE_REV}" ]; then
  fleet_die "release '${FLEET_RELEASE}' not found in namespace '${FLEET_NAMESPACE}' — run install-executor-pool.sh first"
fi
helm upgrade "${FLEET_RELEASE}" "${FLEET_CHART_DIR}" \
  --namespace "${FLEET_NAMESPACE}" \
  "${FLEET_SETS[@]}" \
  --annotation "testpike.io/upgrade-triggered-at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if ! kubectl -n "${FLEET_NAMESPACE}" rollout status "deployment/${FLEET_RELEASE}" \
  --timeout "${EXECUTOR_POOL_ROLLOUT_TIMEOUT:-900s}"; then
  fleet_log "rollout did not settle; platform lease reclaim will retire stale registrations. Inspect pods, then roll back with: helm -n ${FLEET_NAMESPACE} rollback ${FLEET_RELEASE}"
  exit 1
fi
fleet_log "pool '${FLEET_RELEASE}' rolling update settled"
