#!/usr/bin/env bash
# Shared slot-stack bootstrap for this chain's detached E2E windows (single
# source, R40: every window script consumes these helpers instead of copying
# the transient-unit recipe). Guarantees admin/core/api/ai-service + the vite
# preview of the built bundle are live on THIS chain's slot ports, never on
# another slot's: ports and unit names derive from the loaded chain env.
#
# Caller contract: REPO_ROOT set, `tools/ops/load_chain_env.sh` sourced, SLOT
# (the TP_CHAIN_SLOT key of the loaded env), LIVE (tick log) and WINDOW_TAG
# (log prefix) exported, and `say` defined by the caller.

# Fail loudly when a role's sources are newer than that same role's artifact.
# The comparison must be per-role: measuring every module against one jar both
# raises false reds (observed 2026-11-13: core sources vs the admin jar -> a
# freshly repacked core flagged stale, window preflight burned for nothing) and
# lets a genuinely stale module pass. Roles come from the caller (default = the
# three user-facing services); a requested role with no built jar is a red,
# never a silent pass.
check_jars_fresh() {
  local roles="$*" role jar newest rc=0 basis out mrc built
  [ -n "$roles" ] || roles="admin core api"
  for role in $roles; do
    jar="$(ls -t "$REPO_ROOT/$role"/target/testpike-${role}-*.jar 2>/dev/null \
      | grep -Ev 'original|plain|sources' | head -1)"
    if [ -z "$jar" ]; then
      say "$WINDOW_TAG: FAIL $role has no built jar under $role/target (package it first)"
      rc=1
      continue
    fi
    # Manifest-first: the sidecar written at successful `mvn package`
    # pins the exact build moment of these bytes; repackaged mtimes are not
    # evidence. Without a matching manifest the gate falls back to the
    # conjunctive mtime comparison and announces it on the verdict line.
    basis=""
    out="$(python3 "$REPO_ROOT/tools/ops/jar_manifest.py" check "$REPO_ROOT" "$role" "$jar" 2>/dev/null)"
    mrc=$?
    if [ "$mrc" -eq 0 ]; then
      built="${out##*built_at=}"; built="${built%% *}"
      basis=" manifest@${out##*head=}"
      newest="$(find "$REPO_ROOT/$role/src/main" -name '*.java' -newermt "@$built" 2>/dev/null | head -1)"
    else
      if [ "$mrc" -eq 2 ]; then basis=" (no build manifest; mtime conjunctive fallback)"
      else basis=" (manifest invalid for these bytes; mtime conjunctive fallback)"; fi
      newest="$(find "$REPO_ROOT/$role/src/main" -name '*.java' -newer "$jar" 2>/dev/null | head -1)"
    fi
    if [ -n "$newest" ]; then
      say "$WINDOW_TAG: FAIL $role jar stale vs $newest (build first, do not start the window on a stale jar)$basis"
      rc=1
    else
      say "$WINDOW_TAG: OK $role jar covers sources$basis"
    fi
  done
  if [ "$rc" -eq 0 ]; then
    echo "JARFRESH-EXIT=0 (fresh: $roles)"
  else
    echo "JARFRESH-EXIT=1"
  fi
  return $rc
}

# Reconcile this slot's RabbitMQ vhost + permissions before booting the stack.
# A slot released by a retired chain has its vhost deleted with the slot, so a
# window launched on a recycled slot died with AMQP reply-code 530 "vhost not
# found" (observed 2026-11-22 slot1: core unit flapped, window EXIT=3). The
# reconciler tool is owner-scoped and idempotent (PUT on an existing vhost is a
# no-op), so running it on every window start is free and closes the missing-
# bootstrap structural gap instead of relying on a manual step.
ensure_slot_mq_vhost() {
  if ! out="$(python3 "$REPO_ROOT/tools/ops/ensure_slot_vhost.py" 2>&1)"; then
    say "$WINDOW_TAG: FAIL mq vhost reconcile failed: $(echo "$out" | tail -1)"
    return 1
  fi
  say "$WINDOW_TAG: mq vhost $(echo "$out" | tr '\n' ' ')"
}

# Pure clock comparison: did <pid> start at/after the artifact's last write?
# Split from the port lookup so the self-test can pin both branches against
# a known pid (this shell) without depending on a booted stack.
_pid_is_current_against() { # pid jar -> 0 current, 1 superseded
  local pid="$1" jar="$2" elapsed started mtime
  elapsed=$(ps -o etimes= -p "$pid" 2>/dev/null | tr -d ' ')
  [ -n "$elapsed" ] || return 1
  started=$(( $(date +%s) - elapsed ))
  mtime=$(stat -c %Y "$jar" 2>/dev/null) || return 1
  [ "$started" -ge "$mtime" ]
}

# The listener pid on a port. Owner comes from the PORT, never from a unit
# name: the same slot port may be served by the transient tp-e2e unit or by
# a service-form unit an earlier repair round started by hand (observed
# 2026-11-22 slot1: the superseded admin JVM sat behind tp-svc-admin-8181,
# so a unit-name lookup read "no process" and would pass a stale jar — this
# self-test's first shape — while the health probe still answered 200).
_role_port_pid() { # port -> pid (empty when none)
  ss -tlnpH "sport = :$1" 2>/dev/null | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -1
}

# The systemd user unit that currently owns <pid>'s lifecycle: the last
# component of its cgroup path minus the type suffix. Transient units created
# with systemd-run --user live under the user manager's segment, so that
# basename is directly addressable by systemctl --user. Empty when the
# process sits on a segment/root itself (no addressable unit) or the cgroup is
# unreadable — the caller treats undecidable as "no owner unit", which keeps
# the nominal restart path and lets the pid-change assertion below catch any
# false green.
_pid_owner_unit() { # pid -> unit name (empty when none)
  local cg last
  cg=$(awk -F'::' '/^0::/{print $2}' "/proc/$1/cgroup" 2>/dev/null | tail -1)
  [ -n "$cg" ] || return 0
  last="${cg##*/}"
  case "$last" in
    *.service|*.scope) echo "${last%.*}" ;;
    *) echo "" ;;
  esac
}

# True when <pid> works inside THIS chain's tree (R47 owner check, the same
# cwd-equality form service_launcher's stop_port_by_owner uses). A unit whose
# process runs in another worktree is a conflict we report, never restart.
_pid_cwd_is_tree() { # pid -> 0 tree-owned, 1 foreign/undecidable
  local cwd
  cwd=$(readlink "/proc/$1/cwd" 2>/dev/null) || return 1
  case "$cwd" in
    "$REPO_ROOT"|"$REPO_ROOT"/*) return 0 ;;
    *) return 1 ;;
  esac
}

# True when the process LISTENING on the role port carries the current jar.
# A health 200 alone cannot prove that: a rebuild landing under a running
# process leaves the old binary on its open fd (observed 2026-11-22 slot1:
# admin booted 01:13 vs jar 02:46 -> window burned; then core served a
# deleted jar -> NoClassDefFoundError on every DB request while health kept
# answering). Undecidable (no pid found) counts as superseded, never as
# green — the restart path re-boots or fails loudly, which is cheaper than
# a window lost to a stale JVM mid-run.
_role_process_is_current() { # port jar -> 0 current, 1 superseded/unknown
  local pid
  pid=$(_role_port_pid "$1")
  [ -n "$pid" ] || return 1
  _pid_is_current_against "$pid" "$2"
}

# Two-sided fixture (R43: every detector branch must prove it fires).
# Deterministic half: this shell's own pid against two temp artifacts —
# older mtime -> current, future mtime -> superseded. Live half: when the
# slot's core port has a listener, probe the port-ownership path once.
e2e_slot_stack_selftest() {
  local rc=0 probe_dir started
  probe_dir="$(mktemp -d "${TMPDIR:-/tmp}/t294-stack-selftest.XXXXXX")"
  probe_dir="$(cd "$probe_dir" && pwd)"   # macOS-style /tmp symlink would break rm -rf guards
  trap 'rm -rf "$probe_dir"' RETURN
  started=$(date -d "$(ps -o lstart= -p $$ 2>/dev/null)" +%s 2>/dev/null) || started=$(( $(date +%s) - 60 ))
  : > "$probe_dir/current.jar"; : > "$probe_dir/superseded.jar"
  touch -d "@$((started - 3600))" "$probe_dir/current.jar"
  touch -d "@$(( $(date +%s) + 120 ))" "$probe_dir/superseded.jar"
  if _pid_is_current_against $$ "$probe_dir/current.jar"; then
    say "SELFTEST: negative case clean (jar older than process -> current)"
  else
    say "SELFTEST: FAIL current jar read as superseded"; rc=1
  fi
  if _pid_is_current_against $$ "$probe_dir/superseded.jar"; then
    say "SELFTEST: FAIL superseded jar read as current"; rc=1
  else
    say "SELFTEST: positive case fired (jar newer than process -> superseded)"
  fi
  local core_pid
  core_pid=$(_role_port_pid "${CORE_PORT:-}")
  if [ -n "$core_pid" ]; then
    say "SELFTEST: live probe core port ${CORE_PORT} listener pid=$core_pid -> verdict $( _role_process_is_current "$CORE_PORT" "$(ls -t "$REPO_ROOT"/core/target/testpike-core-*.jar 2>/dev/null | grep -Ev 'original|plain|sources' | head -1)" && echo current || echo superseded )"
    local core_unit
    core_unit=$(_pid_owner_unit "$core_pid")
    if [ -n "$core_unit" ]; then
      say "SELFTEST: owner-unit derivation live: pid $core_pid sits under unit '$core_unit', tree-owned=$( _pid_cwd_is_tree "$core_pid" && echo yes || echo no )"
    else
      say "SELFTEST: FAIL owner-unit derivation empty for a live stack listener (pid $core_pid) — restart would target the nominal unit name again"; rc=1
    fi
  else
    say "SELFTEST: live probe skipped (nothing listening on core port ${CORE_PORT:-unset})"
  fi
  # Deterministic foreign-cwd fixture: a process working outside this tree
  # must read as NOT tree-owned, so the reswap path refuses to restart a
  # unit another chain's worktree holds (R47 owner discipline, both sides).
  local neg_pid
  ( cd / && exec sleep 3 ) & neg_pid=$!
  if _pid_cwd_is_tree "$neg_pid"; then
    say "SELFTEST: FAIL pid with cwd=/ read as tree-owned"; rc=1
  else
    say "SELFTEST: negative case clean (foreign cwd -> not tree-owned)"
  fi
  kill "$neg_pid" 2>/dev/null
  return $rc
}

ensure_role() { # role port unit jar
  local role="$1" port="$2" unit="$3" jar="$4"
  # Log names come from the launcher's single-source derivation (R40): this
  # helper used to copy the "role + slot" shape inline, which drifted the
  # moment the derivation changed. The caller contract already sourced the
  # chain env, so TP_CHAIN_SLOT — the slot identity the derivation reads —
  # is present here by construction.
  # shellcheck source=service_launcher.sh
  . "$REPO_ROOT/tools/ops/service_launcher.sh"
  local log
  log="$(tp_svc_log_path "$role")"
  if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:${port}/actuator/health")" = "200" ]; then
    if _role_process_is_current "$port" "$jar"; then
      say "$WINDOW_TAG: stack $role already green on $port (owner-untouched)"
      return 0
    fi
    say "$WINDOW_TAG: stack $role green on $port but the running JVM predates the current jar — reswapping before the window"
  fi
  # A transient unit can be loaded-but-unhealthy: it is still active after a
  # previous window rewrote its jar under the running JVM (health probe then
  # resets), or it lingers dead-but-loaded until --collect reaps it. Creating
  # the unit again with systemd-run fails with "already loaded or has a
  # fragment file" in both shapes, so restart first — idempotent for active
  # units and a plain boot for failed ones — and only fall back to creating
  # the transient unit when no unit object exists to restart.
  # Reswap shape (defect ticket, three parts):
  #  1. The restart target is the port OWNER's unit, resolved from the
  #     listening pid's cgroup — never the nominal tp-e2e name. Observed
  #     2026-11-22 slot1 r20: core@8183 was really held by tp-svc-core-8183,
  #     `restart tp-e2e-core-slot1` failed, the fallback systemd-run boot
  #     could not bind the occupied port, and the OLD process kept answering
  #     health 200 — a false green that only the pid-granular preflight
  #     caught, one full window late.
  #  2. A new transient unit is created only when NOTHING owns the port: if
  #     this tree owns an unrestartable unit, fail loudly instead of
  #     stacking a colliding second JVM on the same port.
  #  3. BOOT_OK requires the listener pid to have CHANGED and to carry the
  #     current jar; health 200 from the pre-restart pid is a hard red.
  local old_pid owner_unit restart_unit="$unit"
  old_pid=$(_role_port_pid "$port")
  if [ -n "$old_pid" ]; then
    owner_unit=$(_pid_owner_unit "$old_pid")
    if [ -n "$owner_unit" ] && _pid_cwd_is_tree "$old_pid"; then
      restart_unit="$owner_unit"
      [ "$owner_unit" != "$unit" ] && \
        say "$WINDOW_TAG: stack $role port owner is unit $owner_unit (pid $old_pid, this tree) — restarting the owner, not the nominal $unit"
    fi
  fi
  # Explicit --server.port pins the role's port: the slot env carries a legacy
  # SERVER_PORT key which Spring relaxed-binding would otherwise map onto
  # server.port for EVERY role (observed: core hijacked onto admin's port).
  if ! systemctl --user restart "$restart_unit" 2>/dev/null; then
    if [ -n "$old_pid" ] && [ "$restart_unit" != "$unit" ]; then
      say "$WINDOW_TAG: stack BOOT_FAIL $role owner unit $restart_unit failed to restart; refusing to create a colliding unit on $port"
      return 1
    fi
    systemd-run --user --collect --unit="$unit" /bin/bash -c \
      "cd $REPO_ROOT; . tools/ops/load_chain_env.sh; exec java -jar $jar --server.port=$port >> $log 2>&1"
  fi
  local deadline=${TP_BOOT_DEADLINE_S:-150} elapsed=0
  while [ "$elapsed" -lt "$deadline" ]; do
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:${port}/actuator/health")" = "200" ] && {
      local new_pid
      new_pid=$(_role_port_pid "$port")
      if [ -n "$old_pid" ] && [ "$new_pid" = "$old_pid" ]; then
        say "$WINDOW_TAG: stack BOOT_FAIL $role health answered by the SAME pre-restart pid $old_pid — restart did not take over the port (false green caught)"
        return 1
      fi
      if ! _role_process_is_current "$port" "$jar"; then
        say "$WINDOW_TAG: stack BOOT_FAIL $role health 200 but the listener still does not carry the current jar"
        return 1
      fi
      say "$WINDOW_TAG: stack BOOT_OK $role $port (listener pid ${new_pid:-?}${old_pid:+, replaced $old_pid})"
      return 0; }
    systemctl --user is-active "$restart_unit" >/dev/null 2>&1 || {
      say "$WINDOW_TAG: stack BOOT_FAIL $role unit $restart_unit died; log tail:"; tail -12 "$log"; return 1; }
    sleep 3; elapsed=$((elapsed + 3))
  done
  say "$WINDOW_TAG: stack BOOT_FAIL $role health timeout ${deadline}s"; return 1
}

ensure_backend_stack() { # all four roles on this slot's ports, same transient form
  # Reconcile the MQ vhost first: a slot recycled from a retired chain has no
  # vhost and core then boot-loops with AMQP 530 (observed 2026-11-22 slot1,
  # window EXIT=3). Idempotent, so it runs on every boot from here on.
  ensure_slot_mq_vhost || return 1
  ensure_role admin "$ADMIN_PORT" "tp-e2e-admin-slot${SLOT}" \
    "$REPO_ROOT/admin/target/$(basename "$(ls "$REPO_ROOT"/admin/target/*.jar | head -1)")" || return 1
  ensure_role core "$CORE_PORT" "tp-e2e-core-slot${SLOT}" \
    "$REPO_ROOT/core/target/$(basename "$(ls "$REPO_ROOT"/core/target/*.jar | head -1)")" || return 1
  ensure_role api "$API_PORT" "tp-e2e-api-slot${SLOT}" \
    "$REPO_ROOT/api/target/$(basename "$(ls "$REPO_ROOT"/api/target/testpike-api-*.jar 2>/dev/null | head -1)")" || return 1
  # ai-service is probed by the window preflight on every chain (fixed probe
  # set), so the slot stack must own it too. api belongs here as well: the
  # resident executor runner registers against /internal/executors/register
  # on the api port.
  ensure_role ai-service "$AISERVICE_PORT" "tp-e2e-aiservice-slot${SLOT}" \
    "$REPO_ROOT/ai-service/target/$(basename "$(ls "$REPO_ROOT"/ai-service/target/testpike-ai-service-*.jar 2>/dev/null | head -1)")" || return 1
}

ensure_executor_runner() { # this chain's own executor station, slot-suffixed unit name
  # Spectrum shapes that feed the executor claim queue (survey/explore acts)
  # are only observable while SOME runner serves this slot's api port: without
  # journal writes empty candidate pools — a false red that costs whole windows.
  # Owner posture (R47): the unit name carries the slot, and a live unit whose
  # process cwd is not this worktree is a conflict we report instead of touching.
  local unit="tp-e2e-runner-slot${SLOT}" pid cwd
  pid=$(systemctl --user show "$unit.service" -p MainPID --value)
  if [ "${pid:-0}" != "0" ]; then
    cwd=$(readlink "/proc/$pid/cwd" 2>/dev/null)
    if [ "$cwd" != "$REPO_ROOT" ]; then
      say "$WINDOW_TAG: FAIL runner unit $unit is live on another worktree (pid $pid cwd=${cwd:-unreadable}) — owner conflict, resolve the slot claim before launching the window"
      return 1
    fi
    say "$WINDOW_TAG: runner $unit already active (pid $pid, this worktree)"
    return 0
  fi
  say "$WINDOW_TAG: runner $unit absent -> launching this chain's executor station"
  systemd-run --user --collect --unit="$unit" /usr/bin/bash -c \
    "export PATH=\"\$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}\"; cd $REPO_ROOT; . tools/ops/load_chain_env.sh; export EXECUTOR_NAME=executor-\${TP_CHAIN_NAME:-chain}-slot${SLOT}; exec /usr/bin/python3 -u executor/runner.py >> /tmp/testpike-svc-runner-slot${SLOT}.log 2>&1" \
    || { say "$WINDOW_TAG: FAIL runner launch refused"; return 1; }
  local elapsed=0 deadline=${TP_RUNNER_BOOT_DEADLINE_S:-90}
  while [ "$elapsed" -lt "$deadline" ]; do
    pid=$(systemctl --user show "$unit.service" -p MainPID --value)
    [ "${pid:-0}" != "0" ] && { say "$WINDOW_TAG: runner UP pid=$pid after ${elapsed}s"; return 0; }
    sleep 3; elapsed=$((elapsed + 3))
  done
  say "$WINDOW_TAG: FAIL runner still not active after ${deadline}s; log tail:"
  tail -12 "/tmp/testpike-svc-runner-slot${SLOT}.log" 2>/dev/null
  return 1
}

ensure_executor_runner_settled() { # ensure_executor_runner + the preflight settle window
  # e2e_window_preflight FAILs while the runner unit changed state within
  # TP_PREFLIGHT_RUNNER_SETTLE_S (claim-queue rows would not be settled yet),
  # so a recipe that launches the station now and gates later must age it
  # first. Second consumer (fleet-bands + chat-awaiting) reached, so the
  # wait block lives here once instead of being copied per recipe (R40).
  local unit="tp-e2e-runner-slot${SLOT}.service" was
  was="$(systemctl --user show "$unit" -p ActiveState --value)"
  ensure_executor_runner || return 1
  if [ "$was" != "active" ]; then
    local settle=$(( ${TP_PREFLIGHT_RUNNER_SETTLE_S:-90} + 5 ))
    say "$WINDOW_TAG: runner launched this round; waiting ${settle}s settle window before preflight"
    sleep "$settle"
  fi
  return 0
}

derive_runner_posture() { # $1 = log tag; stdin = the spectrum's node list (R40 single source)
  # Runner posture derived from declared nodes, never asserted by hand: the
  # queue regex lives only in the preflight tool, so every launch re-reads the
  # specs. yes  -> this chain's executor station must be live (queue-feeding
  # acts otherwise journal empty candidate pools and burn whole windows on
  # false reds); no -> the designed queue-free opt-out is exported;
  # unknown -> no waiver and no auto-launch: the preflight grades the runner
  # strictly and names the gap in seconds instead of burning the cap.
  # Caller owns the failure anchor (rc 1 = station launch/health failed).
  local tag="${1:-$WINDOW_TAG}" need
  need=$(/usr/bin/python3 "$REPO_ROOT/tools/verification/e2e_window_preflight.py" \
    --repo-root "$REPO_ROOT" --queue-need --node-list /dev/stdin \
    | sed -n 's/^PREFLIGHT-QUEUE-NEED=//p')
  say "$tag: stage=runner-posture queue-need=${need:-unknown}"
  if [ "${need:-}" = "yes" ]; then
    ensure_executor_runner || return 1
  elif [ "${need:-}" = "no" ]; then
    export TP_PREFLIGHT_RUNNER_REQUIRED=0
  else
    say "$tag: WARN queue-need unreadable — runner graded strictly this window"
  fi
  return 0
}

ensure_frontend_preview() { # vite preview of the built bundle on the slot vite port
  # The proxy map in vite.config routes /api and /open to this slot's backends
  # — a bare static file server has no proxy and every relative API call would
  # hit the file server instead.
  if [ ! -d frontend/dist ] || [ -n "$(find frontend/src -newer frontend/dist/index.html -type f 2>/dev/null | head -1)" ]; then
    ( cd frontend && export PATH=$HOME/.local/bin:$PATH && timeout 600 npm run build ) || {
      say "$WINDOW_TAG: FAIL npm build"; return 1; }
  fi
  if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:${VITE_PORT}/")" != "200" ]; then
    systemd-run --user --collect --unit="tp-e2e-frontend-slot${SLOT}" /bin/bash -c \
      "export PATH=$HOME/.local/bin:/usr/local/bin:\$PATH; cd $REPO_ROOT/frontend && exec env TP_ADMIN_PORT=$ADMIN_PORT TP_API_PORT=$API_PORT npx vite preview --host 127.0.0.1 --port $VITE_PORT --strictPort >> /tmp/testpike-svc-frontend-slot${SLOT}.log 2>&1"
    for _ in $(seq 1 10); do
      [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:${VITE_PORT}/")" = "200" ] && break
      sleep 2
    done
  fi
  [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:${VITE_PORT}/")" = "200" ] || {
    say "$WINDOW_TAG: FAIL frontend serve $VITE_PORT"; return 1; }
}
