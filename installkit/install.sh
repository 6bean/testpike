#!/usr/bin/env bash
# =============================================================================
# =============================================================================
set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${INSTALLKIT_STATE_DIR:-$HOME/.testpike-installkit/state}"
LOG_DIR="${INSTALLKIT_LOG_DIR:-$HOME/.testpike-installkit/logs}"
PY="${PYTHON_BIN:-python3}"

usage() {
    cat <<'USAGE'
Usage: install.sh --scenario=<scenario> [options]
Scenarios (S1-S5, aliases accepted):
  first      S1 first install (role questions + compose/services/executor + demo seed + sample run commands)
  scale-out  S2 scale out: enroll a new machine with a one-time token + heartbeat reports
  scale-in   S3 scale in / remove: drain first, uninstall only what installkit itself installed
  engines    S4 add/replace an engine: run the install recipe published as a descriptor (recipe.py)
  repair     S5 repair / health check: idempotent rerun that fills missing pieces + structured report
Options:
  --role=<executor|ai-node|platform>   scale-out machine profile (token value-domain gate)
  --token=<one-time enroll token>      required for scale-out (copied from the fleet-page generated command)
  --base-url=<http://host:8082>        api service address (external machines go through the api entry only, never core directly)
  --mirror-base-url=<intranet mirror root> mirror rewrite base for recipe install commands
  --engine=<engine code>               required for engines (S4): which engine to add/replace
  --action=<install|uninstall>         engines (S4) recipe action (default: install)
  --dry-run                            engines (S4) prints the recipe commands without executing them
  --restart                            clears this scenario checkpoints and reruns from the start
  --tier=<lightweight|standard|full>   S1 resource tier explicit choice (default: ask; the three tiers are generated from the component matrix)
  --unattended                         no questions, take every default (same code path as the interactive wizard, so CI/piped runs never hang)
Environment keys (config key + shipped default; edit .env or export, no elevation is ever used):
  INSTALLKIT_UNIT_DOMAIN=user|system   service-agent unit domain (default user = no-sudo user domain; system = full-machine domain under /etc/systemd/system)
  TP_DOCKER_RELAY=on|off               image relay master switch (default on: when an image is missing and the official registry is unreachable, fetch it through a mirror accelerator)
  TP_DOCKER_MIRRORS=<addr>[,<addr>...] accelerator candidates and order (default docker.m.daocloud.io,docker.1ms.run,docker.1panel.live; set an intranet registry to use it)
  TP_DOCKER_ACCELERATE=on|off          allow merging registry-mirrors into a writable daemon.json and restarting the engine (default on; if other containers are running, print the command instead of restarting)
  TP_DOCKER_PROBE_TIMEOUT_S=6          registry reachability probe timeout (seconds)
  TP_DOCKER_DIRECT_TIMEOUT_S=120       direct pull timeout per image (seconds); on timeout switch to the accelerator
  TP_DOCKER_RELAY_TIMEOUT_S=900        pull timeout through the accelerator (seconds)
  --help                               this text
Every scenario is idempotent, resumable from checkpoints (state file per step), fully logged to disk, and every failure names the cause and the fix entry.
USAGE
}

log() {
    [ -n "${LOG_FILE:-}" ] && echo "[$(date +%T)] $*" >> "$LOG_FILE"
}

run_step() {
    local id="$1" desc="$2"
    shift 2
    if grep -q "^step:$id$" "$STATE" 2>/dev/null; then
        log "skip=$id"
        echo "Step [$desc] already completed (checkpoint resume, skipped)"
        return 0
    fi
    log "step=$id start: $desc"
    if "$@"; then
        echo "step:$id" >> "$STATE"
        log "step=$id done"
        return 0
    fi
    log "step=$id failed"
    {
        echo "[$(date +%T)] guide: step [$desc] failed. After fixing, rerun the same command to resume from the checkpoint: bash installkit/install.sh --scenario=$SCENARIO"
        echo "[$(date +%T)] guide: unsure about the cause? Run the repair scenario first (idempotent, fills missing pieces): bash installkit/install.sh --scenario=repair; this log=$LOG_FILE"
    } >> "$LOG_FILE"
    echo "Step [$desc] failed -- cause and fix entry are in the output above and this log; after fixing, rerun the same command to resume from the checkpoint; for the automatic repair path use --scenario=repair" >&2
    return 1
}

say() {
    echo "$1"
    log "say: $1"
}

CMD_BIN="${INSTALLKIT_CMD_BIN:-}"
run_recipe_cmd() {
    local cmd="$1"
    log "recipe-cmd: $cmd"
    if [ -n "$CMD_BIN" ]; then
        eval "$CMD_BIN ${cmd#* }"
    else
        eval "$cmd"
    fi
}

detect_host() {
    case "$(uname -s 2>/dev/null)" in
        Darwin) echo macos ;;
        MINGW*|MSYS*|CYGWIN*|Windows_NT) echo windows ;;
        *) echo linux ;;
    esac
}

# systemctl entry point (SYSTEMCTL_BIN test double, same replace-the-binary
# idiom as INSTALLKIT_CMD_BIN above; production keeps the real binary).
SYSD="${SYSTEMCTL_BIN:-systemctl}"

#  host service-agent unit registration -- platform profile only. The agent
# owns the platform upgrade/rollback consumption face, and systemd
# Restart=always is its self-upgrade relaunch mechanism (the unit ExecStart
# prefers the staged package service_agent.next when an agent-upgrade landed
# one). The unit file is materialised through installkit/distro_profile.py --
# the SAME renderer + preflight single source the bare trio already consumes
# (the old inline sed copy here was the booked second implementation; wiring
# it over is this package). Domain knob (shipped default per install-config rules):
# INSTALLKIT_UNIT_DOMAIN=user keeps the no-sudo user systemd domain
# (WSL friendly); =system targets the full-machine domain and render refuses
# there when the directory is not writable -- this trio never elevates.
# Preflight runs BEFORE anything lands: a host without systemd PID1, or the
# user domain without linger, gets its real fix path named instead of a unit
# file that dies after logout. Credentials land in a 600 env file under
# STATE_DIR only (credentials red line), never inline in the unit and never in logs.
register_service_agent() {
    local unit_domain units_dir sysd_scope env_file unit_file py_abs entry
    unit_domain="${INSTALLKIT_UNIT_DOMAIN:-user}"
    case "$unit_domain" in
        user)   units_dir="${INSTALLKIT_SYSTEMD_DIR:-$HOME/.config/systemd/user}"
                sysd_scope="--user" ;;
        system) units_dir="${INSTALLKIT_SYSTEMD_DIR:-/etc/systemd/system}"
                sysd_scope="" ;;
        *)      say "INSTALLKIT_UNIT_DOMAIN value \"$unit_domain\" is outside the allowed values user|system -- user = no-sudo user domain (default), system = full-machine domain (requires a writable unit directory; this script never elevates)"
                return 1 ;;
    esac
    if ! py_abs="$(command -v "$PY")"; then
        say "Interpreter \"$PY\" not found -- the service-agent unit cannot be registered (install python3 first, then rerun the same command)"
        return 1
    fi
    if ! "$py_abs" "$DIR/distro_profile.py" preflight --domain "$unit_domain"; then
        say "Local systemd preflight did not pass, the service-agent unit was not registered -- fix what the reason above names, then rerun the same command; for the full-machine domain set INSTALLKIT_UNIT_DOMAIN=system"
        return 1
    fi
    env_file="$STATE_DIR/service-agent.env"
    unit_file="$units_dir/tp-service-agent.service"
    mkdir -p "$units_dir" || return 1
    # Relaunch contract: prefer the staged agent package (agent-upgrade lands
    # it in STATE_DIR) so the next systemd start runs the new code.
    entry="$DIR/service_agent.py"
    [ -f "$STATE_DIR/service_agent.next" ] && entry="$STATE_DIR/service_agent.next"
    (umask 077
     printf 'SERVICE_AGENT_BASE_URL=%s\nSERVICE_AGENT_TOKEN=%s\nSERVICE_AGENT_INSTALL_ROOT=%s\nSERVICE_AGENT_STATE_DIR=%s\n' \
         "$BASE_URL" "$TOKEN" \
         "${SERVICE_AGENT_INSTALL_ROOT:-$HOME/.testpike/services}" "$STATE_DIR" \
         > "$env_file") || return 1
    chmod 600 "$env_file"
    "$py_abs" "$DIR/distro_profile.py" render \
        --template "$DIR/tp-service-agent.service" \
        --out "$unit_file" \
        --domain "$unit_domain" \
        --python "$py_abs" \
        --agent-entry "$entry" \
        --env-file "$env_file" || return 1
    "$SYSD" $sysd_scope daemon-reload || return 1
    "$SYSD" $sysd_scope enable tp-service-agent.service || return 1
    "$SYSD" $sysd_scope restart tp-service-agent.service || return 1
    say "service-agent unit registered and started ($unit_domain domain, $unit_file; configuration stored at $env_file with mode 600) -- component versions will reach the ledger through heartbeat reports"
    return 0
}

apply_engine_recipe() {
    local host cmds cmd book plan
    host="$(detect_host)"
    if ! cmds="$("$PY" "$DIR/recipe.py" "$ACTION" --recipe-file "$RECIPE_FILE" --host "$host" \
            ${MIRROR:+--mirror-base-url "$MIRROR"} 2>&1)"; then
        echo "$cmds" >&2
        return 1
    fi
    if [ "$DRY_RUN" = 1 ]; then
        say "Recipe commands that would run (host=$host, action=$ACTION, --dry-run does not execute):"
        printf '%s\n' "$cmds"
        return 0
    fi
    if [ "$ACTION" = "uninstall" ]; then
        if ! plan="$("$PY" "$DIR/ledger.py" uninstall-plan --state-dir "$STATE_DIR" 2>&1)"; then
            echo "$plan" >&2
            return 1
        fi
        if ! printf '%s\n' "$plan" | cut -f1 | grep -qx -- "$ENGINE"; then
            say "Engine \"$ENGINE\" is not in the install ledger = user-installed piece -- installkit never touches it (uninstall removes only self-installed items; remove it yourself if that is really intended)"
            return 0
        fi
    fi
    while IFS= read -r cmd; do
        [ -z "$cmd" ] && continue
        say "Running recipe command: $cmd"
        run_recipe_cmd "$cmd" || return 1
    done <<< "$cmds"
    # Install-ledger sync (the bookkeeping half of the "remove only self-installed"
    # uninstall boundary): install success records, uninstall success clears.
    # The ledger key (engine,host) and the default pin live in ledger.py only;
    # the shell side parses nothing. A failed bookkeeping = failed step
    # (a ledger that disagrees with the host is worse than not installing).
    local book
    if [ "$ACTION" = "install" ]; then
        if book="$("$PY" "$DIR/ledger.py" record --state-dir "$STATE_DIR" --engine "$ENGINE" 2>&1)"; then
            say "$book"
        else
            echo "$book" >&2
            return 1
        fi
    else
        if book="$("$PY" "$DIR/ledger.py" remove --state-dir "$STATE_DIR" --engine "$ENGINE" 2>&1)"; then
            say "$book"
        else
            echo "$book" >&2
            return 1
        fi
    fi
    say "Engine \"$ENGINE\" recipe commands finished for $ACTION (later capability reports come from the executor first boot)"
    return 0
}

# S3 scale-in uninstall stage: consult the ledger -> for every self-installed
# engine reuse apply_engine_recipe to run the recipe uninstall commands and
# clear the ledger entry; pieces outside the ledger never enter the list.
# The checkpoint key includes the engine (a shared global id would let a
# multi-engine scale-in silently skip steps on resume); when a ledger piece has
# no recipe on disk and no fetch entry, it is honestly skipped by name and the
# ledger keeps the row (what cannot be removed is not de-booked; ledger matches host).
purge_self_installed() {
    local code pin purge
    if ! purge="$("$PY" "$DIR/ledger.py" uninstall-plan --state-dir "$STATE_DIR" 2>&1)"; then
        echo "$purge" >&2
        return 1
    fi
    if [ -z "$purge" ]; then
        say "No installkit self-installed engine pieces on this host (install ledger is empty) -- scale-in uninstalls nothing and touches nothing; user-installed pieces are never in scope here"
        return 0
    fi
    while IFS="$(printf '\t')" read -r code pin; do
        [ -z "$code" ] && continue
        RECIPE_FILE="$STATE_DIR/recipe-$code.json"
        ACTION="uninstall"
        ENGINE="$code"
        run_step "uninstall-engine:$code" "Uninstall self-installed engine $code" purge_one_engine || return 1
    done <<< "$purge"
    say "Scale-in uninstall stage finished (only self-installed pieces removed; the ledger was cleared along the way)"
    return 0
}

purge_one_engine() {
    if [ ! -f "$RECIPE_FILE" ]; then
        if [ -n "$BASE_URL" ] && [ -n "$TOKEN" ] && \
           "$PY" "$DIR/enroll_client.py" fetch-recipe --base-url "$BASE_URL" \
                --engine "$ENGINE" --out "$RECIPE_FILE" >/dev/null; then
            say "Uninstall recipe for engine \"$ENGINE\" fetched through the read-only api entry and stored"
        else
            say "Skipped uninstall of engine \"$ENGINE\" -- no recipe on disk and no --token/--base-url fetch entry; the ledger keeps the row (what cannot be removed is not de-booked; ledger matches host)"
            return 0
        fi
    fi
    apply_engine_recipe
}

# S5 refill stage: health report x install ledger -> required diff (missing
# pieces / pin drift; every decision lives in ledger.py; user-installed pieces
# outside the ledger are never touched), then reuse apply_engine_recipe to
# refill engine by engine.
# Recipe missing on disk = fetch through the read-only api entry first (needs
# --token executor token + --base-url); guessing commands is forbidden.
# A fetch failure skips that engine honestly by name -- never claim it was fixed.
refill_engine() {
    local plan code reason
    if ! plan="$("$PY" "$DIR/ledger.py" refill-plan --state-dir "$STATE_DIR" 2>&1)"; then
        echo "$plan" >&2
        return 1
    fi
    if [ -z "$plan" ]; then
        say "Nothing to refill: the health report found no missing piece and every ledger pin matches its recipe (the health stage really re-probes on every run; this refill stage is idempotent and makes no repeat call)"
        return 0
    fi
    while IFS="$(printf '\t')" read -r code reason; do
        [ -z "$code" ] && continue
        RECIPE_FILE="$STATE_DIR/recipe-$code.json"
        ACTION="install"
        ENGINE="$code"
        if [ ! -f "$RECIPE_FILE" ]; then
            if [ -n "$BASE_URL" ] && [ -n "$TOKEN" ] && \
               "$PY" "$DIR/enroll_client.py" fetch-recipe --base-url "$BASE_URL" \
                    --engine "$code" --out "$RECIPE_FILE" >/dev/null; then
                say "Recipe for engine \"$code\" fetched through the read-only api entry and stored"
            else
                say "Skipped refill of engine \"$code\" ($reason) -- no recipe on disk and no --token/--base-url fetch entry; regenerate the refill command on the fleet page or run the engines scenario first to fetch the recipe"
                continue
            fi
        fi
        say "Refilling engine \"$code\" ($reason)"
        apply_engine_recipe || return 1
    done <<< "$plan"
    say "Refill stage finished (a broken piece is reinstalled at the pinned recipe version; the install ledger syncs on completion)"
    return 0
}

SCENARIO=""
ENGINE=""
ACTION="install"
DRY_RUN=0
ROLE=""
TOKEN=""
BASE_URL=""
MIRROR=""
RESTART=0
TIER=""
UNATTENDED=0

for arg in "$@"; do
    case "$arg" in
        --scenario=*) SCENARIO="${arg#*=}" ;;
        --engine=*)   ENGINE="${arg#*=}" ;;
        --action=*)   ACTION="${arg#*=}" ;;
        --dry-run)    DRY_RUN=1 ;;
        --role=*)     ROLE="${arg#*=}" ;;
        --token=*)    TOKEN="${arg#*=}" ;;
        --base-url=*) BASE_URL="${arg#*=}" ;;
        --mirror-base-url=*) MIRROR="${arg#*=}" ;;
        --tier=*)     TIER="${arg#*=}" ;;
        --unattended) UNATTENDED=1 ;;
        --restart)    RESTART=1 ;;
        -h|--help)    usage; exit 0 ;;
        *) echo "Unrecognized argument: $arg" >&2; usage >&2; exit 2 ;;
    esac
done

# Normalize the S1-S5 aliases to canonical scenario names (checkpoint files are
# keyed by the canonical name; keep one naming scheme, not two books)
case "$SCENARIO" in
    S1|s1) SCENARIO=first ;;
    S2|s2) SCENARIO=scale-out ;;
    S3|s3) SCENARIO=scale-in ;;
    S4|s4) SCENARIO=engines ;;
    S5|s5) SCENARIO=repair ;;
esac

if [ -z "$SCENARIO" ]; then
    echo "Missing --scenario=<scenario>" >&2
    usage >&2
    exit 2
fi
case "$SCENARIO" in
    first|scale-out|scale-in|engines|repair) ;;
    *)
        echo "Unknown scenario \"$SCENARIO\" -- choose: first / scale-out / scale-in / engines / repair (aliases S1-S5)" >&2
        exit 2 ;;
esac

mkdir -p "$STATE_DIR" "$LOG_DIR"
STATE="$STATE_DIR/state-$SCENARIO.txt"
LOG_FILE="$LOG_DIR/installkit-$(date +%Y%m%d).log"
[ "$RESTART" = 1 ] && : > "$STATE"
log "=== scenario=$SCENARIO start ==="

rc=0
case "$SCENARIO" in
    first)
        # S1 first install: preflight is real (pkg_probe per host profile); the
        # remaining checkpoints are wired by later packages of this task
        run_step preflight "Environment preflight" "$PY" "$DIR/pkg_probe.py" preflight \
            --require=docker,node,git --ports=auto || rc=1
 # mirror-key fetch (driven by the global relay switch): an explicit CLI value
        # wins; only when unset and a base-url exists the key is fetched.
        # A fetch failure degrades to the official registry with a readable line
        # (stderr passes through without blocking the questions); stdout keeps
        # only the key value so shell command substitution stays noise-free.
        if [ -z "$MIRROR" ] && [ -n "$BASE_URL" ]; then
            MIRROR_FETCHED="$("$PY" "$DIR/enroll_client.py" fetch-mirror --base-url "$BASE_URL")" || true
            [ -n "$MIRROR_FETCHED" ] && MIRROR="$MIRROR_FETCHED"
            log "mirror-key fetched=$MIRROR"
        fi
 # S1 five-question wizard (Enter accepts every default; credentials red line
        # keeps secrets out; answers are stored for later checkpoints to consume)
        run_step role-question "Role and engine questions" "$PY" "$DIR/s1_wizard.py" \
            --answers-file "$STATE_DIR/answers-S1.json" --mirror-default "$MIRROR" || rc=1
 # Resource tier question (component numbers are generated from the component
        # matrix; an explicit --tier= wins; --unattended feeds an empty input
        # stream through the same code path with no side door; the chosen tier
        # merges into answers-S1.json and the compose-seed checkpoint projects it
        # through compose_profiles).
        TIER_ARGS=""
        [ -n "$TIER" ] && TIER_ARGS="$TIER_ARGS --tier $TIER"
        [ "$UNATTENDED" = 1 ] && TIER_ARGS="$TIER_ARGS --unattended"
        run_step tier-question "Resource tier selection" "$PY" "$DIR/resource_tiers.py" ask \
            --answers-file "$STATE_DIR/answers-S1.json" $TIER_ARGS || rc=1
 # S1 runtime prerequisites checkpoint: the needs decision lives in the
        # component itself (required only when a recipe exists or the answers
        # chose an engine; zero need = no checkpoint, no bookkeeping, no noise).
        # The --yes basis: running the installer explicitly is consent to fill
        # zero prerequisites automatically (target: usable with no development
        # experience; the interactive-password risk is avoided by the sudo-free
        # nvm main path).
        if "$PY" "$DIR/runtime_bootstrap.py" needs --state-dir "$STATE_DIR" \
                --answers-file "$STATE_DIR/answers-S1.json"; then
            [ "$rc" = 0 ] && { run_step runtime-bootstrap "Engine runtime prerequisites" \
                "$PY" "$DIR/runtime_bootstrap.py" ensure --yes --state-dir "$STATE_DIR" || rc=1; }
        fi
 # Bootstrapping this installer's own Python dependencies (declared set lives
        # in installkit/requirements.txt; clean-image runs proved a bare python3
        # has no httpx and s1_bootstrap fails at top-level import).
        # Idempotent: import succeeds = no action; pip runs through python3 -m pip
        # (images without pip get python3 installed by the preflight fix line);
        # on managed environments (PEP 668) fall back to --user then
        # --break-system-packages, and a final failure names the way out loudly.
        if ! "$PY" -c "import httpx" >/dev/null 2>&1; then
            run_recipe_cmd "$PY -m pip install --user -q -r \"$DIR/../installkit/requirements.txt\" \
                || $PY -m pip install --break-system-packages --user -q -r \"$DIR/../installkit/requirements.txt\"" \
                || { log "installkit requirements install failed -- way out: $PY -m pip install -r installkit/requirements.txt (if pip is missing install python3-pip/py3-pip first)"; rc=1; }
        fi
 # S1 bring-up checkpoints (question 1 branch: platform = compose middleware +
        # four services guidance; executor = reports the engine surface only)
        run_step compose-seed   "Services and demo seed" "$PY" "$DIR/s1_bootstrap.py" compose \
            --answers-file "$STATE_DIR/answers-S1.json" || rc=1
        [ "$rc" = 0 ] && { run_step storage-check "Object storage ready" "$PY" "$DIR/s1_bootstrap.py" storage \
            --answers-file "$STATE_DIR/answers-S1.json" || rc=1; }
        # Address resolution = single source for the S1 install key (process env band key > tree .env > admin shipped entry); the shell side never guesses a fallback literal (clean-window red root cause)
        SEED_BASE_ARGS=(); [ -n "${INSTALLKIT_SEED_BASE_URL:-}" ] && SEED_BASE_ARGS=(--base-url "$INSTALLKIT_SEED_BASE_URL")
        [ "$rc" = 0 ] && { run_step seed-demo "demo data auto seeding" "$PY" "$DIR/s1_bootstrap.py" seed-demo \
            "${SEED_BASE_ARGS[@]}" || rc=1; }
        ;;
    scale-out)
        if [ -n "$ROLE" ]; then
            case "$ROLE" in
                executor|ai-node|platform) ;;
                *) echo "Machine profile \"$ROLE\" is outside the allowed values -- it must be one of executor / ai-node / platform (tokens are issued for exactly these three profiles; the script never invents roles)" >&2; exit 2 ;;
            esac
        fi
        if [ -z "$TOKEN" ]; then
            echo "Missing --token= -- generate the enrollment token on the fleet page via \"Add executor / AI node\" and copy the command that carries the token (one-time + TTL; when it expires, generate a new one)" >&2
            exit 2
        fi
        if [ -z "$BASE_URL" ]; then
            echo "Missing --base-url= -- the api service address (like http://<platform host>:8082); the installer goes through the api entry only, never core directly" >&2
            exit 2
        fi
 # Node-card four-step bar (03 value domain download|deps|register|healthcheck,
        # the value-domain gate lives in core): the skeleton scenario merges the
        # download/deps beats before enrollment completes (real deps/register
        # execution stages are attached by later installer packages).
        run_step hb-start    "Start heartbeat" "$PY" "$DIR/enroll_client.py" heartbeat --base-url "$BASE_URL" --token "$TOKEN" \
            --step=download --step-status=running --message="pool enrollment started" || rc=1
        [ "$rc" = 0 ] && { run_step enroll-token "Redeem token and join the pool" "$PY" "$DIR/enroll_client.py" enroll --base-url "$BASE_URL" --token "$TOKEN" || rc=1; }
        [ "$rc" = 0 ] && { run_step hb-done  "Finish heartbeat" "$PY" "$DIR/enroll_client.py" heartbeat --base-url "$BASE_URL" --token "$TOKEN" \
            --step=healthcheck --message="enrollment flow completed" || rc=1; }
        # Platform-profile extra position: register the host service-agent
        # systemd unit (user domain; credentials land only in a 600 env file per
        # the credentials red line). Executor/ai-node profiles never own this step.
        if [ "$rc" = 0 ] && [ "$ROLE" = "platform" ]; then
            run_step service-agent-unit "Register service-agent unit" register_service_agent || rc=1
        fi
        ;;
    scale-in)
        run_step drain-check "Drain first" say "Scale-in must drain first: stop claiming -> running tasks reach zero -> then remove (same semantics as the existing pool-removal flow; skeleton checkpoint)" || rc=1
        run_step uninstall-boundary "Uninstall boundary" say "Safety boundary: only what installkit itself installed is uninstalled (version dirs + services + ledger); user-installed pieces are never touched" || rc=1
 # Scale-in uninstall stage: consult the install ledger, run the recipe
        # uninstall commands engine by engine, then de-book (pieces outside the
        # ledger never enter the list)
        [ "$rc" = 0 ] && { run_step purge-self-installed "Uninstall self-installed engine pieces" purge_self_installed || rc=1; }
 # Command-claim receipt wiring (protocol 03, one-shot polling): claim only
        # when token + api address are given;
        # scale-in/node-remove pure-confirmation types ack on the spot (drain flag
        # and final-state decisions live in core; the script judges nothing);
        # types that need real execution (engine-*) do not fake a receipt and are
        # left for the enrolled runner consumption loop.
        if [ -n "$TOKEN" ] && [ -n "$BASE_URL" ]; then
            [ "$rc" = 0 ] && { run_step command-ack "Claim and ack fleet commands" "$PY" "$DIR/enroll_client.py" poll \
                --base-url "$BASE_URL" --token "$TOKEN" --ack || rc=1; }
        else
            say "No --token/--base-url -- skipping the command claim-and-ack checkpoint (the platform side may revoke or wait anyway; after installation the runner consumption loop covers it)"
        fi
        run_step pool-remove "Scale-in trail" say "Machine removal is audit-recorded (when the receipt reached the platform, the command line is kept in the fleet ledger as well)" || rc=1
        ;;
    engines)
        if [ -z "$ENGINE" ]; then
            echo "Missing --engine=<engine code> -- adding/replacing an engine requires the engine code (see the engine center list, e.g. mobilewright/minium/maestro)" >&2
            exit 2
        fi
        if [ -z "$BASE_URL" ]; then
            echo "Missing --base-url= -- the api service address (like http://<platform host>:8082); recipes are fetched through the read-only api entry, the script never reads the database and never calls core directly" >&2
            exit 2
        fi
        case "$ACTION" in
            install|uninstall) ;;
            *) echo "Recipe action \"$ACTION\" is outside the allowed values -- it must be install / uninstall" >&2; exit 2 ;;
        esac
        RECIPE_FILE="$STATE_DIR/recipe-$ENGINE.json"
        # The checkpoint key includes engine and action (never one global id):
        # a second machine, a second engine or --action=uninstall reusing the
        # same id would be silently skipped by the resume semantics = a false
        # green on the install surface
        run_step "fetch-recipe:$ENGINE" "Fetch install recipe" "$PY" "$DIR/enroll_client.py" fetch-recipe \
            --base-url "$BASE_URL" --engine "$ENGINE" --out "$RECIPE_FILE" || rc=1
 # S4 add/replace prerequisite chain: with the recipe on disk its
        # runtime_requires version line is readable from a single source, so the
        # runtime is filled before the assembly command runs (node missing or
        # too old = install per host profile first, otherwise npm installs it but
        # nothing runs).
        # Only the install action triggers it (uninstall installs nothing);
        # --dry-run prints the plan without executing (same caution as above).
        # The needs decision lives in the component (recipe declares no runtime
        # and local node is in place = ensure produces an empty plan itself).
        if [ "$ACTION" = "install" ] && "$PY" "$DIR/runtime_bootstrap.py" needs \
                --state-dir "$STATE_DIR" --answers-file "$STATE_DIR/answers-S1.json"; then
            if [ "$DRY_RUN" = 1 ]; then
                [ "$rc" = 0 ] && { say "Runtime prerequisites (--dry-run prints the plan only):"
                    "$PY" "$DIR/runtime_bootstrap.py" check --state-dir "$STATE_DIR"; }
            else
                [ "$rc" = 0 ] && { run_step "runtime-bootstrap:$ENGINE" "Engine runtime prerequisites" \
                    "$PY" "$DIR/runtime_bootstrap.py" ensure --yes --state-dir "$STATE_DIR" || rc=1; }
            fi
        fi
        [ "$rc" = 0 ] && { run_step "apply-engine:$ENGINE:$ACTION" "Run recipe commands" apply_engine_recipe || rc=1; }
        # Post-install capabilities need nothing from this script: when the
        # machine has no runner yet, the first-boot register reports them
 # (the runner consumption loop already re-probes locally after install; keep
        # that reporting in one place only)
        ;;
    repair)
 # Health stage wiring: call doctor.py, the single-source component, over the
        # recipe probe list.
        # Read-only probes with zero side effects = no checkpoint bookkeeping
        # (every rerun must really re-probe; bookkeeping would make a second
        # repair silently skip the probe).
        say "Health report (host=$(detect_host); missing pieces are advisory by name, not blocking):"
        "$PY" "$DIR/doctor.py" --state-dir "$STATE_DIR" --host "$(detect_host)" || rc=1
        # Image reachability belongs to the same health surface: a missing image
        # is fetched through the relay right away (accelerator candidates are
        # hot-updatable),
        # and every repair really re-probes (no checkpoint bookkeeping, or the
        # second health check would silently skip it).
        if [ "$rc" = 0 ] && [ -f "$DIR/../docker-compose.yml" ]; then
            "$PY" "$DIR/image_relay.py" ensure --compose-file "$DIR/../docker-compose.yml" \
                || say "Not every image could be prepared -- fix what the output above names, then rerun the same command"
        fi
 # Missing-piece named -> auto-fix loop (runtime branch): the missing_runtime
        # section of the health report is no longer report-only -- repair is a
        # fix command the user ran explicitly = explicit consent, so --yes fills
        # per host profile directly (sudo-free nvm main path, avoiding the
        # interactive-password risk named above); the runtime goes first (engine
        # pieces still need a runtime to run). Zero runtime need (needs gate) =
        # no checkpoint created, no noise.
        if [ "$rc" = 0 ] && "$PY" "$DIR/runtime_bootstrap.py" needs \
                --state-dir "$STATE_DIR" --answers-file "$STATE_DIR/answers-S1.json"; then
            run_step runtime-ensure "Auto-fill runtime prerequisites" "$PY" "$DIR/runtime_bootstrap.py" \
                ensure --yes --state-dir "$STATE_DIR" || rc=1
        fi
 # Refill checkpoint: required diff -> reuse the recipe single source to
        # refill + bookkeep; the checkpoint bookkeeping means a second repair
        # repeats no external call (idempotent assertion); --restart clears
        # checkpoints to force a real refill.
        [ "$rc" = 0 ] && { run_step refill "Fill missing pieces" refill_engine || rc=1; }
        # Re-check = one more real probe after refilling (read-only, zero side
        # effects, same no-bookkeeping rule as the health stage)
        if [ "$rc" = 0 ] && grep -q "^step:refill$" "$STATE" 2>/dev/null; then
            say "Re-check (probe again after refill):"
            "$PY" "$DIR/doctor.py" --state-dir "$STATE_DIR" --host "$(detect_host)" || rc=1
        fi
        ;;
esac

log "=== scenario=$SCENARIO rc=$rc ==="
exit "$rc"
