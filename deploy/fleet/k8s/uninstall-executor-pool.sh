#!/usr/bin/env bash
# Remove an executor pool release. Drains honestly: scale the deployment to
# zero first so runners stop claiming and finish in-flight work within the
# termination grace, wait for the ledger to reflect zero online replicas at
# this release name, then uninstall the chart. Cluster-scoped objects are not
# created by this chart (namespaced ServiceAccount only), so no leftovers.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/fleet-lib.sh"

fleet_prepare
if ! helm -n "${FLEET_NAMESPACE}" status "${FLEET_RELEASE}" >/dev/null 2>&1; then
  fleet_log "release '${FLEET_RELEASE}' is not present in namespace '${FLEET_NAMESPACE}'; nothing to do"
  exit 0
fi
kubectl -n "${FLEET_NAMESPACE}" scale "deployment/${FLEET_RELEASE}" --replicas=0
fleet_log "scaled to zero; waiting ${EXECUTOR_POOL_DRAIN_WAIT_SECONDS:-120}s for in-flight tasks to finish inside the termination grace"
sleep "${EXECUTOR_POOL_DRAIN_WAIT_SECONDS:-120}"
helm -n "${FLEET_NAMESPACE}" uninstall "${FLEET_RELEASE}" --wait --timeout "${EXECUTOR_POOL_UNINSTALL_TIMEOUT:-300s}"
fleet_log "release '${FLEET_RELEASE}' removed; registrations age out through lease reclaim if the runners do not check out explicitly"
