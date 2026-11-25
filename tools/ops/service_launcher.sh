#!/usr/bin/env bash
# testPike dev-stack service launcher — one service = one JVM (java -jar hosting).
# Single source for launch/stop/jar-build semantics (R40): ops scripts and
# verification harnesses consume this file, no second copy of the logic.
#
# Why: `mvn spring-boot:run` keeps an extra host JVM (~250-400M) per service
# solely to supervise the forked app child. Direct `java -jar` hosting drops
# the host; unit semantics (auto-restart, log location, port, JVM dev flags)
# are unchanged.
#
# Credential red line: callers must `source .env` (or the slot env) before
# invoking; nothing here inlines or echoes secrets.

# Jar naming authority: target/testpike-<module>-*.jar (plain jar excluded).
# Ports default to the AGENTS.md port table and accept env overrides so
# chain-slot runs (slot env exports *_PORT) reuse the same launcher.
# REPO_ROOT falls back to this file's own repo so sourcing this launcher
# alone is safe (the former silent-dependency form produced "cd: null
# directory" and a false "launched" line when the caller left it unset).
REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

TP_JAVA_OPTS="${TP_JAVA_OPTS:--XX:TieredStopAtLevel=1}"   # dev JVM flags, same values spring-boot:run used

# Continuous-log governance : one service = one role log, and every
# append-only log gets a capped archive roll instead of a history-wiping
# truncate. Paths/rolling logic single-sourced here (R40) — ops scripts and
# the startup self-check all consume these, no second copy.
# shellcheck source=log_roll.sh
. "$(dirname "${BASH_SOURCE[0]}")/log_roll.sh"
tp_svc_log_path() { # role -> per-role service log path (slot overlay honored)
    # Slot identity is part of the name: a chain slot that wrote its role log
    # into the shared /tmp/testpike-svc-<role>.log made attribution impossible
    # (two chains appending one file = the noise ledger cannot name the owner
    # of a signature). slot0 keeps the historic name so the main stack and the
    # ledger baseline stay stable; every other slot gets a "-slot<N>" suffix,
    # still matched by the ledger's "/tmp/testpike-svc-*.log" glob family.
    # Identity source is the chain env key TP_CHAIN_SLOT (never a hardcoded
    # slot number here, R48); TP_SVC_LOG_DIR remains the directory override.
    local _slot="${TP_CHAIN_SLOT:-0}"
    if [ "$_slot" = "0" ]; then
        echo "${TP_SVC_LOG_DIR:-/tmp}/testpike-svc-$1.log"
    else
        echo "${TP_SVC_LOG_DIR:-/tmp}/testpike-svc-$1-slot${_slot}.log"
    fi
}

tp_jar() { # module -> jar path ("" when absent); absolute so every launch
    # channel resolves the jar regardless of the caller's working directory
    # (2026-11-04 evidence: relative paths broke "Unable to access jarfile"
    # when the mock-server unit was re-launched from a different cwd).
    ls "$REPO_ROOT/$1"/target/testpike-"$1"-*.jar 2>/dev/null | grep -v '\.original$' | head -1
}

tp_ensure_jar() { # module -> prints jar path on stdout ONLY (status notes go to stderr so callers can capture the path cleanly)
    local jar
    jar="$(tp_jar "$1")"
    if [ -n "$jar" ]; then
        # Freshness gate: rebuild when any module source (or the shared common/)
        # is newer than the packaged jar. The old check only built a MISSING jar,
        # so a present-but-stale one booted as-is and the whole verification
        # window ran on old code (R46: stale services void results; observed on
        # slot3 2026-11-06 — core jar 01:41 vs core-side commit 05:37).
        if [ -n "$(find "$REPO_ROOT/$1/src" "$REPO_ROOT/common/src" -type f \
                \( -name '*.java' -o -name '*.xml' -o -name '*.yml' -o -name '*.yaml' -o -name '*.properties' \) \
                -newer "$jar" -print -quit 2>/dev/null)" ]; then
            echo "JAR_STALE $1: sources newer than $jar, rebuilding" >&2
            jar=""
        else
            echo "JAR_PRESENT $1=$jar" >&2; echo "$jar"; return 0
        fi
    fi
    echo "JAR_BUILD $1: mvn -pl $1 -am package -DskipTests" >&2
    ( cd "$REPO_ROOT" && mvn -q -pl "$1" -am package -DskipTests ) || return 1
    ( cd "$REPO_ROOT" && python3 tools/ops/jar_manifest.py write "$REPO_ROOT" "$1" >/dev/null 2>&1 ) \
      || echo "MANIFEST-WRITE-WARN $1 (gates fall back to mtime conjunctive)" >&2
    tp_jar "$1"
}

# The resident traffic plane must always pin the shared inspector fixture
# space, otherwise every mock E2E door reads a "no space loaded" 503 and
# the ledger provenance rows never land (t366e3c env-staleness incident,
# ). Callers may override by passing the flags explicitly; the
# space key and core base stay hot-configurable through the environment.
# One injection helper feeds both launch channels (nohup and transient
# unit) so a unit-launched mock server gets the same defaults without a
# second copy of the logic.
tp_mock_default_args() { # [already-passed extra args...] -> one flag per line
    case "$*" in
        *mock.load.space-key*) : ;;
        *) printf '%s\n' "--testpike.mock.load.space-key=${TP_RESIDENT_SPACE_KEY:-ivo-1dc2693b-e}" ;;
    esac
    case "$*" in
        *mock.load.core-base-url*) : ;;
        *) printf '%s\n' "--testpike.mock.load.core-base-url=${TP_CORE_BASE:-http://localhost:8083}" ;;
    esac
}

tp_start() { # module port logfile [extra spring args...]
    local module="$1" port="$2" log="$3"; shift 3
    if [ "$module" = "mock-server" ]; then
        local -a _mock_defaults=()
        mapfile -t _mock_defaults < <(tp_mock_default_args "$@")
        set -- "$@" "${_mock_defaults[@]}"
    fi
    local jar boot_anchor
    [ -d "$REPO_ROOT" ] || { echo "LAUNCH_ABORT REPO_ROOT invalid: $REPO_ROOT" >&2; return 1; }
    # nohup children inherit the caller's environment, so the presence check
    # runs here (the transient-unit path checks inside ExecStart instead, where
    # its own layered env is authoritative).
    tp_precheck_required_env "$module" || return 1
    tp_precheck_port_free "$module" "$port" || return 1
    boot_anchor="$(tp_log_anchor "$log")"
    jar="$(tp_ensure_jar "$module")" || return 1
    [ -n "$jar" ] || { echo "LAUNCH_ABORT $module produced no jar" >&2; return 1; }
    ( cd "$REPO_ROOT" && nohup java $TP_JAVA_OPTS -jar "$jar" \
        --server.port="$port" "$@" >> "$log" 2>&1 & )
    echo "$module launched via jar (port=$port log=$log jar=$jar)"
    sleep 2
    pgrep -f "java $TP_JAVA_OPTS -jar .*testpike-${module}-" >/dev/null \
      || { echo "LAUNCH_ABORT $module java process died within 2s (see $log)" >&2; return 1; }
    tp_wait_boot "$module" "$port" "$log" "$boot_anchor"
}

tp_port_pid() { # port -> owning pids (authoritative stop target: never kill by cmdline guess)
    ss -ltnp 2>/dev/null | grep -E ":$1\b" | grep -oP 'pid=\K[0-9]+' | sort -u
}

# --- boot verdict single source (startup-failure root cure, R53 family) -----------------
# Why: the old success criterion was "alive 2s after spawn" plus the DONE line the
# caller prints. A dev JVM can stay up 5-30s, then log APPLICATION FAILED TO START
# and exit — every restart recipe still reported success ("tail-line false green";
# measured 2026-11-05: two waves, five dead services, all tail lines passed).
# Now every launch channel: pre-check port + required config keys, then scan ONLY
# the bytes appended since this boot started (byte anchor, so a shared log file
# like tick-live never leaks a previous run's verdict) until health returns 200 or
# a failure signature names the service with its reason lines.
tp_log_anchor() { stat -c %s "$1" 2>/dev/null || echo 0; }

tp_new_log_slice() { # log anchor -> only bytes appended after that anchor
    tail -c +"$(( ${2:-0} + 1 ))" "$1" 2>/dev/null
}

tp_liveness_ok() { # module [unit] -> 0 while the booting process/unit is still alive
    if [ -n "${2:-}" ]; then
        systemctl --user is-active "$2" >/dev/null 2>&1
    else
        pgrep -f "java $TP_JAVA_OPTS -jar .*testpike-$1-" >/dev/null 2>&1
    fi
}

tp_precheck_port_free() { # module port -> abort when something already listens before boot
    local module="$1" port="$2" pid cwd
    for pid in $(tp_port_pid "$port"); do
        cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null)"
        case "$cwd" in
            "$REPO_ROOT"|"$REPO_ROOT"/*)
                echo "PRECHECK_ABORT $module: port $port still held by this tree's pid $pid (restart flow must stop it first)" >&2 ;;
            *)
                echo "PRECHECK_ABORT $module: port $port held by foreign pid $pid ($cwd)" >&2 ;;
        esac
        return 1
    done
    return 0
}

# Values are NEVER echoed — only the missing key names are reported, so a config
# mistake can be fixed from .env without the log leaking a secret (credential red line).
tp_precheck_required_env() { # module -> names-only presence check of boot-critical keys
    local module="$1" key missing=""
    case "$module" in
        mock-server|frontend) return 0 ;;
    esac
    for key in DB_URL DB_USERNAME DB_PASSWORD RABBITMQ_HOST RABBITMQ_USERNAME RABBITMQ_PASSWORD REDIS_HOST; do
        [ -n "${!key:-}" ] || missing="$missing $key"
    done
    if [ -n "$missing" ]; then
        echo "PRECHECK_ABORT $module: required config keys absent from chain env:$missing (see $REPO_ROOT/.env; values are never logged)" >&2
        return 1
    fi
    return 0
}

tp_wait_boot() { # module port log anchor [unit] -> BOOT_OK on health 200; BOOT_FAIL names service + reason
    local module="$1" port="$2" log="$3" anchor="${4:-0}" unit="${5:-}"
    local deadline="${TP_BOOT_DEADLINE_S:-120}" health_path="${TP_HEALTH_PATH:-/actuator/health}"
    local elapsed=0 slice
    while [ "$elapsed" -lt "$deadline" ]; do
        slice="$(tp_new_log_slice "$log" "$anchor")"
        if printf '%s\n' "$slice" | grep -q "APPLICATION FAILED TO START"; then
            echo "BOOT_FAIL $module (port $port): APPLICATION FAILED TO START — reason lines:" >&2
            printf '%s\n' "$slice" | grep -A 12 "APPLICATION FAILED TO START" >&2
            return 1
        fi
        if ! tp_liveness_ok "$module" "$unit"; then
            echo "BOOT_FAIL $module (port $port): process exited before health — last log lines:" >&2
            printf '%s\n' "$slice" | tail -12 >&2
            return 1
        fi
        if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 \
                "http://localhost:${port}${health_path}" 2>/dev/null)" = "200" ]; then
            echo "BOOT_OK $module (port $port, health $health_path)"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    echo "BOOT_FAIL $module (port $port): health not green within ${deadline}s — last log lines:" >&2
    tp_new_log_slice "$log" "$anchor" | tail -12 >&2
    return 1
}

# --- transient-unit mode -----------------------------------------------------
# Why: nohup children share the caller's systemd scope; when the caller's unit
# retires, the scope control-group kill drains every forked JVM at once
# (slot1 stack drain, 2026-11-04 evidence: boot script main gone -> all four
# services and their sockets disappeared together). Resident per-service
# transient units keep each JVM's lifecycle independent from whoever boots it.
TP_SLOT_ENV="${TP_SLOT_ENV:-}"          # optional slot overlay file (absolute or repo-relative), layered on top of the tree .env
# node resolution must not depend on the caller's PATH: the transient-unit
# environment is the systemd user manager's default PATH, which hides both
# nvm-style and ~/.local/bin installs (2026-11-04 evidence: the vite unit
# silently never spawned under a unit-run boot script).
if [ -z "${TP_NODE_BIN:-}" ]; then
    for _node_candidate in "$(command -v node 2>/dev/null)" \
                           "$HOME/.local/bin/node" /usr/local/bin/node /usr/bin/node; do
        [ -x "${_node_candidate:-}" ] && { TP_NODE_BIN="$_node_candidate"; break; }
    done
    TP_NODE_BIN="${TP_NODE_BIN:-}"
fi

tp_unit_name() { echo "tp-svc-$1-$2"; }

tp_start_unit() { # module port logfile [extra spring args...]
    local module="$1" port="$2" log="$3"; shift 3
    local unit jar boot_anchor
    [ -d "$REPO_ROOT" ] || { echo "LAUNCH_ABORT REPO_ROOT invalid: $REPO_ROOT" >&2; return 1; }
    # Slot-overlay leak guard (2026-11-18 evidence): a shell that exported
    # TP_SLOT_ENV=...slot6.env ran the MAIN-slot reload recipe; the value was
    # baked literally into every transient unit's ExecStart, so four main-chain
    # services served testpike_slot6 while the E2E fixtures wrote testpike —
    # reads silently saw zero rows. The overlay's slot number must equal THIS
    # chain's slot; a foreign one aborts the launch (fail-closed, never bake).
    if [ -n "${TP_SLOT_ENV:-}" ]; then
        local _ov_slot _cur_slot
        _ov_slot="$(basename "$TP_SLOT_ENV" | sed -nE 's/.*slot([0-9]+)\.env$/\1/p')"
        if [ -n "$_ov_slot" ]; then
            _cur_slot="${TP_CHAIN_SLOT:-}"
            [ -n "$_cur_slot" ] || _cur_slot="$(python3 "$REPO_ROOT/scripts/chain_slot.py" current_slot 2>/dev/null || true)"
            if [ -n "$_cur_slot" ] && [ "$_cur_slot" != "$_ov_slot" ]; then
                echo "LAUNCH_ABORT $module: TP_SLOT_ENV=slot${_ov_slot} but this chain is slot${_cur_slot} (leaked foreign overlay; unset TP_SLOT_ENV or load this chain's own)" >&2
                return 1
            fi
        fi
    fi
    unit="$(tp_unit_name "$module" "$port")"
    systemctl --user is-active "$unit" >/dev/null 2>&1 && { echo "$module unit already active: $unit"; return 0; }
    tp_precheck_port_free "$module" "$port" || return 1
    tp_roll_if_needed "$log"   # fresh start boundary: archive oversized log instead of inheriting an unbounded file
    boot_anchor="$(tp_log_anchor "$log")"
    jar="$(tp_ensure_jar "$module")" || return 1
    [ -n "$jar" ] || { echo "LAUNCH_ABORT $module produced no jar" >&2; return 1; }
    # Configuration loading goes through the one authorized entry
    # (tools/ops/load_chain_env.sh: tree .env + identity/slot consistency
    # validation, fail-closed). The former inline "set -a; . ./.env" here both
    # bypassed that validation and hard-prefixed the slot overlay with "./",
    # which broke every absolute overlay path — the core unit then died inside
    # 2s before exec java, leaving an empty service log (2026-11-04 slot1 boot
    # evidence). The overlay is layered only when the file exists; a
    # repo-relative or absolute path both resolve correctly from REPO_ROOT.
    # The whole ExecStart body runs inside one redirected brace group: the
    # pre-check's PRECHECK_ABORT line must land in the SAME service log the
    # boot verdict reads — a journal-only message would make "read the log tail"
    # blind to exactly the failure class this rule exists to catch.
    systemd-run --user --collect --unit="$unit" /usr/bin/env bash -c \
        "{ cd '$REPO_ROOT' && . '$REPO_ROOT/tools/ops/load_chain_env.sh' && { [ -z '${TP_SLOT_ENV}' ] || [ ! -f '${TP_SLOT_ENV}' ] || . '${TP_SLOT_ENV}'; } && . '$REPO_ROOT/tools/ops/service_launcher.sh' && { tp_precheck_required_env '$module' || exit 9; } && exec java $TP_JAVA_OPTS -jar '$jar' --server.port='$port' $*; } >> '$log' 2>&1" \
        >/dev/null 2>&1 || { echo "LAUNCH_ABORT $module transient unit spawn failed" >&2; return 1; }
    echo "$module launched via transient unit $unit (port=$port log=$log)"
    sleep 2
    systemctl --user is-active "$unit" >/dev/null \
      || { echo "LAUNCH_ABORT unit $unit not active (see $log)" >&2; return 1; }
    tp_wait_boot "$module" "$port" "$log" "$boot_anchor" "$unit"
}

# Resident mock-server on the transient-unit channel: a nohup launch shares
# the caller's cgroup, so a gateway restart or slot teardown silently drains
# the channel (defect 29 evidence, 2026-11). The unit keeps its lifecycle
# independent from whoever boots it; defaults are pinned through the same
# single-source injection helper as the nohup path.
tp_start_mock_unit() { # port [logfile]
    local port="$1" log="${2:-/tmp/testpike-svc-mock-server.log}"
    local -a _mock_defaults=()
    mapfile -t _mock_defaults < <(tp_mock_default_args "")
    tp_start_unit mock-server "$port" "$log" "${_mock_defaults[@]}"
}

tp_start_vite_unit() { # port logfile
    local port="$1" log="$2" unit
    unit="tp-svc-vite-$port"
    systemctl --user is-active "$unit" >/dev/null 2>&1 && { echo "vite unit already active: $unit"; return 0; }
    [ -n "$TP_NODE_BIN" ] || { echo "LAUNCH_ABORT node binary not resolved for vite unit" >&2; return 1; }
    [ -d "$REPO_ROOT/frontend" ] || { echo "LAUNCH_ABORT frontend dir missing under $REPO_ROOT" >&2; return 1; }
    # The transient unit does not inherit the caller's environment, and a
    # long-lived shell may still carry a foreign chain's exports. So the unit
    # loads its OWN chain configuration inside ExecStart (the one validated
    # entry, tools/ops/load_chain_env.sh), and the proxy targets are resolved
    # THERE: loader value (slot .env declares *_PORT) first, shipped defaults
    # second — never the caller's baked values. Without this a slot dev server
    # proxies into the MAIN chain's admin/api (journey-chain E2E hit exactly
    # this on 2026-11-05); baking the caller's ports re-introduced the same
    # class through the back door, which step 3 of the single-source task fixes.
    # Node deps and the vite config both live under frontend/ (package.json is
    # there), so the unit's cwd must be frontend/ — resolving node_modules/vite
    # from the repo root fails with MODULE_NOT_FOUND on every chain worktree
    # (the root node_modules is empty even on main; first slot4 boot hit it
    # on 2026-11-07). The chain-env loader is referenced by absolute path, so
    # the cwd change does not weaken the in-unit proxy-port resolution above.
    systemd-run --user --collect --unit="$unit" /usr/bin/env bash -c \
        "cd '$REPO_ROOT/frontend' && . '$REPO_ROOT/tools/ops/load_chain_env.sh' && exec env \
         TP_ADMIN_PORT=\"\${TP_ADMIN_PORT:-\${ADMIN_PORT:-8081}}\" \
         TP_API_PORT=\"\${TP_API_PORT:-\${API_PORT:-8082}}\" \
         '$TP_NODE_BIN' node_modules/vite/bin/vite.js --port '$port' --strictPort >> '$log' 2>&1" \
        >/dev/null 2>&1 || { echo "LAUNCH_ABORT vite unit spawn failed" >&2; return 1; }
    echo "vite launched via transient unit $unit (port=$port log=$log)"
    sleep 2
    systemctl --user is-active "$unit" >/dev/null \
        || { echo "LAUNCH_ABORT unit $unit not active (see $log)" >&2; return 1; }
}

# Wait (bounded) for one pid to truly disappear before its jar may be touched.
# Returns 0 when gone (or when there was no pid), 1 when the process is still
# alive after the bound. A JVM that survived the stop signal lazy-loads classes
# from the jar replaced under it and dies with NoClassDefFoundError, so every
# stop-then-rebuild recipe must gate on this before touching the artifact.
# Bound key SLOT_RESTART_EXIT_WAIT_S, shipped default 60s, hot-reloadable
# through the environment (no hardcoded budgets). A zombie counts as gone: its
# address space is already released, only the entry waits for the parent.
tp_wait_pid_gone() { # pid -> 0 gone within the bound, 1 still alive
    local pid="$1" waited=0 bound="${SLOT_RESTART_EXIT_WAIT_S:-60}" state
    case "$pid" in ""|0) return 0 ;; esac
    while [ -d "/proc/$pid" ]; do
        state="$(awk '{print $3}' "/proc/$pid/stat" 2>/dev/null || echo gone)"
        [ "$state" = "Z" ] && return 0
        if [ "$waited" -ge "$bound" ]; then return 1; fi
        sleep 1
        waited=$((waited + 1))
    done
    return 0
}

stop_port_by_owner() { # port [grace_seconds] -> graceful then force kill of the socket owner(s) THIS repo started
    local port="$1" grace="${2:-5}" pid cwd own
    # Remember the original owners: a JVM that has unbound its listening socket
    # mid-shutdown (graceful shutdown closes Tomcat first, then hangs on a
    # Redis/Rabbit shutdown hook) disappears from ss output while still alive,
    # and the next launch races a living predecessor — measured on 2026-11-06
    # when a core restart reported DONE while the old JVM lingered and the new
    # instance shut itself down one second after start.
    local owners=""
    for pid in $(tp_port_pid "$port"); do
        cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null)"
        case "$cwd" in
            "$REPO_ROOT"|"$REPO_ROOT"/*) owners="$owners $pid" ;;
            *)
                echo "STOP_REFUSED port $port pid $pid belongs to another worktree ($cwd); this chain must not kill it" >&2
                return 1 ;;
        esac
    done
    for pid in $owners; do kill "$pid" 2>/dev/null; done
    sleep "$grace"
    for pid in $(tp_port_pid "$port"); do
        cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null)"
        case "$cwd" in
            "$REPO_ROOT"|"$REPO_ROOT"/*) kill -9 "$pid" 2>/dev/null ;;
            *) echo "STOP_REFUSED port $port still held by foreign pid $pid ($cwd)" >&2; return 1 ;;
        esac
    done
    # Reap the remembered owners regardless of socket state, up to 15s.
    for pid in $owners; do
        local waited=0
        while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 15 ]; do
            [ "$waited" -ge "$grace" ] && kill -9 "$pid" 2>/dev/null
            sleep 1
            waited=$((waited + 1))
        done
        if kill -0 "$pid" 2>/dev/null; then
            echo "STOP_ABORT port $port owner pid=$pid survived SIGKILL (unkillable; refusing false-green restart)" >&2
            return 1
        fi
    done
    sleep 2
    pid="$(tp_port_pid "$port" | head -1)"
    [ -z "$pid" ] && return 0
    echo "STOP_ABORT port $port still held by pid=$pid" >&2
    return 1
}
