#!/usr/bin/env bash
# Shared bootstrap for the bare (non-Kubernetes) executor-node delivery trio.
#
# Design rules (same family as deploy/fleet/k8s/fleet-lib.sh — read it first,
# this file is the node-side projection, no logic is duplicated between them):
#   - Every install-time value is read from the chain environment (loaded via
#     tools/ops/load_chain_env.sh, the single configuration source) or an
#     explicit EXECUTOR_NODE_* override. No host port or database name is
#     hardcoded here (R48 shipped-default-plus-key form throughout).
#   - Zero silent sudo: this trio never elevates. When a target is not
#     writable it stops and names the exact operator re-run path instead.
#   - Payload fetches must carry a sha256; an unsigned payload is refused.
#   - Credentials land only in the 0600 agent env file, never in stdout/logs.

bare_log() { echo "[bare] $*"; }
bare_die() { echo "[bare][error] $*" >&2; exit 1; }

# Resolve the repository root from the calling script's path, load the one
# configuration source, then materialise every writable target and tool path
# as EXECUTOR_NODE_* / SERVICE_AGENT_* driven values.
bare_prepare() {
  local script_path self_dir root
  script_path="$1"
  self_dir="$(cd "$(dirname "${script_path}")" && pwd)"
  root="$(cd "${self_dir}/../../.." && pwd)"
  BARE_REPO_ROOT="${root}"
  if [ ! -f "${root}/tools/ops/load_chain_env.sh" ]; then
    bare_die "chain env loader not found under ${root}/tools/ops — run these scripts from a checkout of this repository"
  fi
  # shellcheck source=/dev/null
  . "${root}/tools/ops/load_chain_env.sh"
  BARE_PYTHON="${EXECUTOR_NODE_PYTHON:-python3}"
  BARE_UNIT_NAME="${EXECUTOR_NODE_AGENT_UNIT:-tp-service-agent}"
  BARE_UNIT_DIR="${EXECUTOR_NODE_UNIT_DIR:-/etc/systemd/system}"
  # Unit domain knob (R48): the bare trio hosts system-domain units by
  # default; user-domain hosting must pass both the dir and the domain so
  # the renderer rewrites WantedBy correctly.
  BARE_UNIT_DOMAIN="${EXECUTOR_NODE_UNIT_DOMAIN:-system}"
  BARE_DISTRO_PROFILE="${root}/installkit/distro_profile.py"
  BARE_ENV_FILE="${EXECUTOR_NODE_ENV_FILE:-/etc/testpike/service-agent.env}"
  BARE_STATE_DIR="${EXECUTOR_NODE_STATE_DIR:-/var/lib/testpike/service-agent}"
  BARE_INSTALL_ROOT="${EXECUTOR_NODE_INSTALL_ROOT:-/opt/testpike}"
  BARE_BASE_URL="${EXECUTOR_NODE_BASE_URL:-}"
  # Entry points default to the in-repo sources; a pinned payload package
  # switches them to the landed copy (see bare_fetch_package).
  BARE_ENROLL_CLIENT="${root}/installkit/enroll_client.py"
  BARE_SERVICE_AGENT="${root}/installkit/service_agent.py"
  BARE_UNIT_TEMPLATE="${root}/installkit/tp-service-agent.service"
}

# Existence check for every write target BEFORE any side effect. A missing
# directory may be created if its nearest existing parent is writable; an
# existing directory must be writable itself. Never elevates (zero silent
# sudo) — the refusal names the exact re-run form.
bare_require_writable() {
  local dir probe
  for dir in "$@"; do
    if [ -e "${dir}" ]; then
      probe="${dir}"
      [ -d "${probe}" ] || probe="$(dirname "${probe}")"
      [ -w "${probe}" ] && continue
      bare_die "${probe} is not writable by uid $(id -u); this script never elevates silently — re-run with: sudo -E bash ${dir}/../*.sh as appropriate, or point the target keys (${EXECUTOR_NODE_UNIT_DIR:+already set}) at a writable location"
    fi
    probe="$(dirname "${dir}")"
    while [ ! -d "${probe}" ] && [ "${probe}" != "/" ]; do probe="$(dirname "${probe}")"; done
    [ -w "${probe}" ] \
      || bare_die "cannot create ${dir}: ${probe} is not writable by uid $(id -u) — re-run the same command with sudo, or export EXECUTOR_NODE_* targets under a writable prefix"
  done
}

# sha256 pinning gate for the optional payload channel. Empty expected value
# is a refusal, not a pass — the fleet script-side rule demands the manifest
# hash to be verified, so "unsigned" can never be an accidental default.
bare_verify_sha256() {
  local file="$1" expected="$2" actual
  [ -n "${expected}" ] \
    || bare_die "EXECUTOR_NODE_PACKAGE_SHA256 is required whenever EXECUTOR_NODE_PACKAGE_URL is set; unsigned payloads are refused"
  expected="${expected#sha256:}"
  actual="$(sha256sum "${file}" | cut -d' ' -f1)"
  [ "${actual}" = "${expected}" ] \
    || bare_die "sha256 mismatch for the node payload: manifest says ${expected}, the bytes hash to ${actual}; refusing to install (re-export EXECUTOR_NODE_PACKAGE_SHA256 from the release feed)"
}

# Optional payload channel: an air-gapped local path or an https URL. The
# unpacked tree replaces the in-repo agent entry points so the running unit
# always points at the pinned release, never at a drifting checkout.
bare_fetch_package() {
  [ -n "${EXECUTOR_NODE_PACKAGE_URL:-}" ] || return 0
  local tmp
  tmp="$(mktemp)"
  if [ -f "${EXECUTOR_NODE_PACKAGE_URL}" ]; then
    cp "${EXECUTOR_NODE_PACKAGE_URL}" "${tmp}" \
      || { rm -f "${tmp}"; bare_die "could not read local payload ${EXECUTOR_NODE_PACKAGE_URL}"; }
  else
    command -v curl >/dev/null 2>&1 \
      || { rm -f "${tmp}"; bare_die "curl is required to fetch EXECUTOR_NODE_PACKAGE_URL"; }
    curl -fsSL "${EXECUTOR_NODE_PACKAGE_URL}" -o "${tmp}" \
      || { rm -f "${tmp}"; bare_die "package fetch failed from ${EXECUTOR_NODE_PACKAGE_URL}"; }
  fi
  bare_verify_sha256 "${tmp}" "${EXECUTOR_NODE_PACKAGE_SHA256:-}"
  mkdir -p "${BARE_INSTALL_ROOT}"
  tar -xzf "${tmp}" -C "${BARE_INSTALL_ROOT}" \
    || { rm -f "${tmp}"; bare_die "payload did not unpack as a .tgz under ${BARE_INSTALL_ROOT}"; }
  rm -f "${tmp}"
  BARE_ENROLL_CLIENT="${BARE_INSTALL_ROOT}/installkit/enroll_client.py"
  BARE_SERVICE_AGENT="${BARE_INSTALL_ROOT}/installkit/service_agent.py"
  # distro_profile/fleet_doctor stay repo-root tools (same precedent as the
  # unit template above): the trio only runs from a checkout, and the pinned
  # payload carries exactly the files release.sh ships — no manifest drift.
  bare_log "node payload unpacked under ${BARE_INSTALL_ROOT}; agent entry points switched to the pinned copy"
}

# Persist the node credential triple with owner-only mode. Written atomically
# so a crash mid-install cannot leave a half file that systemd would load.
bare_write_env() {
  local token="$1" tmp
  mkdir -p "$(dirname "${BARE_ENV_FILE}")"
  tmp="${BARE_ENV_FILE}.tmp"
  ( umask 077
    cat > "${tmp}" <<EOF
# Rendered by deploy/fleet/bare/install-executor-node.sh — owner-only, the
# token here is this node's fleet command-queue credential (R48: this file is
# the only place any node secret lives).
SERVICE_AGENT_BASE_URL=${BARE_BASE_URL}
SERVICE_AGENT_TOKEN=${token}
SERVICE_AGENT_STATE_DIR=${BARE_STATE_DIR}
SERVICE_AGENT_INSTALL_ROOT=${BARE_INSTALL_ROOT}
EOF
  )
  chmod 600 "${tmp}"
  mv "${tmp}" "${BARE_ENV_FILE}"
}

# Materialise the systemd unit through the distro profile module (single
# render source; installkit/install.sh now renders and preflights through
# this same module as well — the inline sed copy was wired over in the
# T294b single-source micro package, so no second renderer exists).
bare_render_unit() {
  local py
  py="$(command -v "${BARE_PYTHON}" || echo "${BARE_PYTHON}")"
  mkdir -p "${BARE_UNIT_DIR}"
  "${BARE_PYTHON}" "${BARE_DISTRO_PROFILE}" render \
    --template "${BARE_UNIT_TEMPLATE}" \
    --out "${BARE_UNIT_DIR}/${BARE_UNIT_NAME}.service" \
    --domain "${BARE_UNIT_DOMAIN}" \
    --python "${py}" \
    --agent-entry "${BARE_SERVICE_AGENT}" \
    --env-file "${BARE_ENV_FILE}" \
    || bare_die "unit render failed for domain ${BARE_UNIT_DOMAIN}"
}
