#!/usr/bin/env bash
# Shared bootstrap for the K8s executor-pool delivery scripts.
#
# Design rules this file implements:
#   - Every install-time value is read from the chain environment (.env layered
#     through tools/ops/load_chain_env.sh) or an explicit EXECUTOR_POOL_*
#     override. No host port, database name or registry is hardcoded here.
#   - Derived values read the installer .env first, then slot overlays; they
#     never take an inherited process port as a base and add an offset.
#   - Image references must be pinned: either an immutable digest or an
#     explicit tag plus a sha256 that this script re-verifies. Floating tags
#     need a deliberate opt-out and are never the default.

set -euo pipefail

fleet_log() { echo "[fleet] $*" >&2; }
fleet_die() { echo "[fleet][error] $*" >&2; exit 1; }

# Locate the repository root from this file's location, then load exactly one
# configuration source: the chain env loader (never a bare ". ./.env").
fleet_prepare() {
  local self_dir root
  self_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  root="$(cd "${self_dir}/../../.." && pwd)"
  FLEET_REPO_ROOT="${root}"
  if [ ! -f "${root}/tools/ops/load_chain_env.sh" ]; then
    fleet_die "chain env loader not found under ${root}/tools/ops — run these scripts from a checkout of this repository"
  fi
  # shellcheck source=/dev/null
  . "${root}/tools/ops/load_chain_env.sh"
  command -v helm >/dev/null 2>&1 || fleet_die "helm client is required (delivery-window toolchain; see the fleet verification wave before claiming a live apply)"
  command -v kubectl >/dev/null 2>&1 || fleet_die "kubectl is required"
  FLEET_CHART_DIR="${FLEET_CHART_DIR:-${root}/deploy/fleet/charts/tp-executor}"
  FLEET_NAMESPACE="${EXECUTOR_POOL_NAMESPACE:-tp-fleet}"
  FLEET_RELEASE="${EXECUTOR_POOL_RELEASE:-tp-executor}"
}

# In-cluster address the runners register against. Explicit override wins; the
# fallback derives from the loaded env's API port mapping so a slot chain can
# never silently register against another chain's ledger.
fleet_api_url() {
  if [ -n "${EXECUTOR_POOL_API_URL:-}" ]; then
    echo "${EXECUTOR_POOL_API_URL}"
  elif [ -n "${EXECUTOR_POOL_IN_CLUSTER_API_URL:-}" ]; then
    # Normal delivery: an in-cluster Service DNS name, no host port involved.
    echo "${EXECUTOR_POOL_IN_CLUSTER_API_URL}"
  else
    fleet_die "set EXECUTOR_POOL_IN_CLUSTER_API_URL (e.g. http://tp-api:8082) or EXECUTOR_POOL_API_URL explicitly"
  fi
}

# Build the pinned image reference and re-verify the digest when provided.
# Policy: digest pinning passes; tag+sha256 requires the sha256 to appear in
# the resolved manifest reference when a registry inspector is available,
# otherwise the pair is echoed for the operator's pre-flight evidence; a bare
# moving tag is rejected unless allowFloating is set to 1 on purpose.
fleet_image_ref() {
  local repo="${EXECUTOR_POOL_IMAGE_REPOSITORY:-}" tag="${EXECUTOR_POOL_IMAGE_TAG:-}" digest="${EXECUTOR_POOL_IMAGE_SHA256:-}"
  [ -n "${repo}" ] || fleet_die "EXECUTOR_POOL_IMAGE_REPOSITORY is required"
  if [ -n "${digest}" ]; then
    case "${digest}" in sha256:*) ;; *) digest="sha256:${digest}" ;; esac
    echo "${repo}@${digest}"
    return 0
  fi
  if [ -n "${tag}" ] && [ "${tag}" != "latest" ]; then
    fleet_log "image is tag-pinned (${tag}); production pools should pin EXECUTOR_POOL_IMAGE_SHA256 from the release manifest so the digest is re-verified at apply time"
    echo "${repo}:${tag}"
    return 0
  fi
  [ "${EXECUTOR_POOL_ALLOW_FLOATING_IMAGE:-0}" = "1" ] \
    && { echo "${repo}:${tag:-latest}"; return 0; }
  fleet_die "unpinned image: provide EXECUTOR_POOL_IMAGE_SHA256 (preferred) or EXECUTOR_POOL_IMAGE_TAG; floating tags only with EXECUTOR_POOL_ALLOW_FLOATING_IMAGE=1"
}

# Helm value overrides shared by install and upgrade. Kept in one function so
# the delivery paths can never drift apart; the platform-side pool values
# generator endpoint (fleet pack 2b) will feed the same --values channel and
# this list stays as the env-driven floor.
fleet_common_sets() {
  local ref
  ref="$(fleet_image_ref)"
  FLEET_SETS=(
    "--set" "image.repository=${ref%@sha256:*}"
  )
  case "${ref}" in
    *@*) FLEET_SETS+=("--set" "image.digest=${ref#*@}") ;;
    *:*) FLEET_SETS+=("--set" "image.tag=${ref##*:}") ;;
  esac
  FLEET_SETS+=(
    "--set" "platform.apiUrl=$(fleet_api_url)"
    "--set" "pool.endTypes=${EXECUTOR_POOL_END_TYPES:-web}"
    "--set" "pool.browsers=${EXECUTOR_POOL_BROWSERS:-chromium}"
    "--set" "pool.capacity=${EXECUTOR_POOL_CAPACITY:-2}"
    "--set" "pool.groupIds=${EXECUTOR_POOL_GROUP_IDS:-}"
    "--set" "pool.envIds=${EXECUTOR_POOL_ENV_IDS:-}"
    "--set" "pool.version=${EXECUTOR_POOL_VERSION:-1.0.0}"
  )
}
