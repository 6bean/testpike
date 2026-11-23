#!/usr/bin/env bash
# Install (or idempotently re-apply) one executor pool into a Kubernetes
# namespace from environment-driven values. Safe to re-run: this is
# `helm upgrade --install` under the hood.
#
# Required environment (see fleet-lib.sh for the pinning policy):
#   EXECUTOR_POOL_IN_CLUSTER_API_URL   in-cluster URL runners register to
#   EXECUTOR_POOL_IMAGE_REPOSITORY     image repository
#   EXECUTOR_POOL_IMAGE_SHA256         digest pin (preferred), or
#   EXECUTOR_POOL_IMAGE_TAG            tag pin
# Optional: EXECUTOR_POOL_NAMESPACE, EXECUTOR_POOL_RELEASE, pool.* overrides.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/fleet-lib.sh"

fleet_prepare
fleet_common_sets
helm upgrade --install "${FLEET_RELEASE}" "${FLEET_CHART_DIR}" \
  --namespace "${FLEET_NAMESPACE}" --create-namespace \
  "${FLEET_SETS[@]}"
kubectl -n "${FLEET_NAMESPACE}" rollout status "deployment/${FLEET_RELEASE}" \
  --timeout "${EXECUTOR_POOL_ROLLOUT_TIMEOUT:-300s}"
fleet_log "pool '${FLEET_RELEASE}' applied in namespace '${FLEET_NAMESPACE}'; nodes self-register through the api service — watch the fleet page pool card for the online count"
