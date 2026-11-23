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
用法: install.sh --scenario=<场景> [选项]
场景（S1-S5，别名同义）:
  first      S1 首装体验包（角色问答+compose/四服务/执行器+样例种子+试跑命令）
  scale-out  S2 扩容：新机器凭一次性入池令牌 enroll+心跳打点
  scale-in   S3 缩容/退池：先 drain，只卸载 installkit 自己安装的东西
  engines    S4 换/加装引擎：消费描述符安装配方（recipe.py）
  repair     S5 修复/体检：幂等重跑缺件补齐+结构化报告
选项:
  --role=<executor|ai-node|platform>   scale-out 机器画像（令牌值域闸）
  --token=<一次性入池令牌>              scale-out 必填（机群页生成命令携带）
  --base-url=<http://host:8082>        api 服务地址（体系外只经 api 入口，禁直连 core）
  --mirror-base-url=<内网镜像根>        配方安装命令镜像改道（§9.5）
  --engine=<引擎code>                  engines(S4) 必填：换/加装哪个引擎
  --action=<install|uninstall>         engines(S4) 配方动作（缺省 install）
  --dry-run                            engines(S4) 只打印将执行的配方命令不执行
  --restart                            清空本场景位点从头再跑
  --tier=<lightweight|standard|full>   S1 资源档位显式指定（缺省=问答；三档数字由组件矩阵程序化生成）
  --unattended                         全程零追问走默认值（与交互问答同一代码路径，CI/管道环境不挂死）
环境变量取值位（配置键+出厂缺省，改 .env/导出即可热更，脚本永不提权）：
  INSTALLKIT_UNIT_DOMAIN=user|system   service-agent 单元域（缺省 user=免 sudo 用户域；system=/etc/systemd/system 全机域）
  --help                               本说明
全部场景幂等可重跑、中断可续（步骤位点文件）、全程日志落盘、失败即人话原因。
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
        echo "步骤[$desc]已完成（位点续跑跳过）"
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
        echo "[$(date +%T)] 教学：步骤[$desc]失败。修复后原样重跑同一条命令即断点续跑：bash installkit/install.sh --scenario=$SCENARIO"
        echo "[$(date +%T)] 教学：拿不准原因时先跑体检修复（幂等补装缺件）：bash installkit/install.sh --scenario=repair；本日志=$LOG_FILE"
    } >> "$LOG_FILE"
    echo "步骤[$desc]失败——原因见上方人话输出；修复后重跑同一命令即从断点续跑；体检修复用 --scenario=repair" >&2
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

#  host service-agent unit registration — platform profile only. The agent
# owns the platform upgrade/rollback consumption face, and systemd
# Restart=always is its self-upgrade relaunch mechanism (the unit ExecStart
# prefers the staged package service_agent.next when an agent-upgrade landed
# one). The unit file is materialised through installkit/distro_profile.py —
# the SAME renderer + preflight single source the bare trio already consumes
# (the old inline sed copy here was the booked second implementation; wiring
# it over is this package). Domain knob (R48 shipped default):
# INSTALLKIT_UNIT_DOMAIN=user keeps the no-sudo user systemd domain
# (WSL friendly); =system targets the full-machine domain and render refuses
# there when the directory is not writable — this trio never elevates.
# Preflight runs BEFORE anything lands: a host without systemd PID1, or the
# user domain without linger, gets its real fix path named instead of a unit
# file that dies after logout. Credentials land in a 600 env file under
# STATE_DIR only (red line 4), never inline in the unit and never in logs.
register_service_agent() {
    local unit_domain units_dir sysd_scope env_file unit_file py_abs entry
    unit_domain="${INSTALLKIT_UNIT_DOMAIN:-user}"
    case "$unit_domain" in
        user)   units_dir="${INSTALLKIT_SYSTEMD_DIR:-$HOME/.config/systemd/user}"
                sysd_scope="--user" ;;
        system) units_dir="${INSTALLKIT_SYSTEMD_DIR:-/etc/systemd/system}"
                sysd_scope="" ;;
        *)      say "INSTALLKIT_UNIT_DOMAIN 取值「$unit_domain」不在值域 user|system —— user=免 sudo 用户域（缺省），system=全机域（要求单元目录可写，脚本永不提权）"
                return 1 ;;
    esac
    if ! py_abs="$(command -v "$PY")"; then
        say "找不到解释器「$PY」——service-agent 单元无法注册（先装 python3 再重跑同一命令）"
        return 1
    fi
    if ! "$py_abs" "$DIR/distro_profile.py" preflight --domain "$unit_domain"; then
        say "本机 systemd 预检未通过，service-agent 单元未注册——按上方原因处理后重跑同一命令；全机域形态可改 INSTALLKIT_UNIT_DOMAIN=system"
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
    say "service-agent 单元已注册并拉起（$unit_domain 域 $unit_file；配置落 $env_file 权限 600）——部件版本将经心跳上报台账"
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
        say "将执行的配方命令（宿主=$host，动作=$ACTION，--dry-run 不实际执行）："
        printf '%s\n' "$cmds"
        return 0
    fi
    if [ "$ACTION" = "uninstall" ]; then
        if ! plan="$("$PY" "$DIR/ledger.py" uninstall-plan --state-dir "$STATE_DIR" 2>&1)"; then
            echo "$plan" >&2
            return 1
        fi
        if ! printf '%s\n' "$plan" | cut -f1 | grep -qx -- "$ENGINE"; then
            say "引擎「$ENGINE」账外=用户手装件——installkit 永不触碰（卸载只删自装；确要卸载请自行执行）"
            return 0
        fi
    fi
    while IFS= read -r cmd; do
        [ -z "$cmd" ] && continue
        say "执行配方命令：$cmd"
        run_recipe_cmd "$cmd" || return 1
    done <<< "$cmds"
    # 装机账同步（卸载边界「只删自装」记账半边）：装成功=记账、卸成功=销账；
    # 账键 (engine,host) 与钉缺省全在 ledger.py 单源，shell 侧零解析；记账失败=
    # 步骤失败（账实不符比不装更危险——后续卸载只认账）。
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
    say "引擎「$ENGINE」$ACTION 配方命令执行完毕（后续能力面由执行器首启上报）"
    return 0
}

# S3 退池卸载段实装（「只删自装」）：查账→账内件逐引擎复用
# apply_engine_recipe 单源跑配方 uninstall 命令+卸毕销账；账外件根本不进清单。
# 位点键含引擎（坑 B：禁全局同 id——多引擎退池会被续跑语义静默跳过）；
# 账内件配方不在盘且无拉取口=诚实跳过点名+账面保留（卸不掉不销账，账实一致）。
purge_self_installed() {
    local code pin purge
    if ! purge="$("$PY" "$DIR/ledger.py" uninstall-plan --state-dir "$STATE_DIR" 2>&1)"; then
        echo "$purge" >&2
        return 1
    fi
    if [ -z "$purge" ]; then
        say "本机无 installkit 自装引擎件（装机账为空）——退池零卸载零触碰，用户手装件永不在此列"
        return 0
    fi
    while IFS="$(printf '\t')" read -r code pin; do
        [ -z "$code" ] && continue
        RECIPE_FILE="$STATE_DIR/recipe-$code.json"
        ACTION="uninstall"
        ENGINE="$code"
        run_step "uninstall-engine:$code" "卸载自装引擎「$code」" purge_one_engine || return 1
    done <<< "$purge"
    say "退池卸载段执行完毕（只删自装，账面已随卸毕销账）"
    return 0
}

purge_one_engine() {
    if [ ! -f "$RECIPE_FILE" ]; then
        if [ -n "$BASE_URL" ] && [ -n "$TOKEN" ] && \
           "$PY" "$DIR/enroll_client.py" fetch-recipe --base-url "$BASE_URL" \
                --engine "$ENGINE" --out "$RECIPE_FILE" >/dev/null; then
            say "引擎「$ENGINE」卸载配方经 api 只读口拉回落盘"
        else
            say "引擎「$ENGINE」卸载跳过——配方不在盘且未提供 --token/--base-url 拉取口；账面保留（卸不掉不销账，账实一致）"
            return 0
        fi
    fi
    apply_engine_recipe
}

# S5 补段位点：体检报告×装机账→需求 diff（缺件/钉漂移，判定全在
# ledger.py 单源，账外手装件永不触碰），逐引擎复用 apply_engine_recipe 单源补装。
# 配方不在盘=先经 api 只读口拉取（需 --token 执行器令牌+--base-url），凭空猜命令=禁；
# 拉不到=该引擎诚实跳过点名，禁假称已补。
refill_engine() {
    local plan code reason
    if ! plan="$("$PY" "$DIR/ledger.py" refill-plan --state-dir "$STATE_DIR" 2>&1)"; then
        echo "$plan" >&2
        return 1
    fi
    if [ -z "$plan" ]; then
        say "无可补：体检无缺件且装机账钉与配方一致（体检位点每次真跑重探，本补装段幂等不重复外呼）"
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
                say "引擎「$code」配方经 api 只读口拉回落盘"
            else
                say "引擎「$code」补装跳过（$reason）——配方不在盘且未提供 --token/--base-url 拉取口；请机群页重新生成补装命令或先跑 engines 场景拉配方"
                continue
            fi
        fi
        say "补装引擎「$code」（$reason）"
        apply_engine_recipe || return 1
    done <<< "$plan"
    say "补装段执行完毕（坏件重装=按配方钉版本重装，装机账随装毕同步）"
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
        *) echo "未识别参数：$arg" >&2; usage >&2; exit 2 ;;
    esac
done

# S1-S5 编号别名归一 canonical 场景名（位点文件按 canonical 命名，禁两套账）
case "$SCENARIO" in
    S1|s1) SCENARIO=first ;;
    S2|s2) SCENARIO=scale-out ;;
    S3|s3) SCENARIO=scale-in ;;
    S4|s4) SCENARIO=engines ;;
    S5|s5) SCENARIO=repair ;;
esac

if [ -z "$SCENARIO" ]; then
    echo "缺少 --scenario=<场景>" >&2
    usage >&2
    exit 2
fi
case "$SCENARIO" in
    first|scale-out|scale-in|engines|repair) ;;
    *)
        echo "未知场景「$SCENARIO」——可选：first / scale-out / scale-in / engines / repair（别名 S1-S5）" >&2
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
        # S1 首装体验包：预检已实装（pkg_probe 三画像）；其余位点实装随本任务后续包
        run_step preflight "环境预检" "$PY" "$DIR/pkg_probe.py" preflight \
            --require=docker,node,git --ports=auto || rc=1
 # 镜像源键拉取（全局改道键驱动）：CLI 显式指定优先；未指定且有 base-url 才拉键。
        # 拉取失败=人话降级官方源（stderr 透出不阻断问答）；stdout 命令替换只取键值零噪音。
        if [ -z "$MIRROR" ] && [ -n "$BASE_URL" ]; then
            MIRROR_FETCHED="$("$PY" "$DIR/enroll_client.py" fetch-mirror --base-url "$BASE_URL")" || true
            [ -n "$MIRROR_FETCHED" ] && MIRROR="$MIRROR_FETCHED"
            log "mirror-key fetched=$MIRROR"
        fi
 # S1 五题问答（全默认值可一路回车；红线 4 零敏感值；答案落盘供后续位点消费）
        run_step role-question "角色与引擎问答" "$PY" "$DIR/s1_wizard.py" \
            --answers-file "$STATE_DIR/answers-S1.json" --mirror-default "$MIRROR" || rc=1
 # T293b 资源三档问答（单源=resource_tiers.py，资源数字由组件矩阵程序化生成；
        # --tier= 显式指定优先，--unattended=空输入流走默认=与交互同一代码路径零旁路；
        # 档位 merge 进 answers-S1.json，compose-seed 位点经 compose_profiles 投影 profile）。
        TIER_ARGS=""
        [ -n "$TIER" ] && TIER_ARGS="$TIER_ARGS --tier $TIER"
        [ "$UNATTENDED" = 1 ] && TIER_ARGS="$TIER_ARGS --unattended"
        run_step tier-question "资源三档选择" "$PY" "$DIR/resource_tiers.py" ask \
            --answers-file "$STATE_DIR/answers-S1.json" $TIER_ARGS || rc=1
 # 刀4 S1 运行时前置位点：needs 闸判定单源在件内（有配方或答案选了引擎才需要，
        # 零需求=不建位点不记账不打扰）；--yes 依据=用户显式跑装机脚本即同意零前置自动补齐
 # （目标「无开发经验人员零前置」，交互密码风险面已由 nvm 免 sudo 主路规避，坑①）。
        if "$PY" "$DIR/runtime_bootstrap.py" needs --state-dir "$STATE_DIR" \
                --answers-file "$STATE_DIR/answers-S1.json"; then
            [ "$rc" = 0 ] && { run_step runtime-bootstrap "引擎运行时前置" \
                "$PY" "$DIR/runtime_bootstrap.py" ensure --yes --state-dir "$STATE_DIR" || rc=1; }
        fi
 # 装机脚本自身 Python 依赖引导（单源=installkit/requirements.txt；r39b 净机窗实证
 # 裸像 python3 无 httpx，s1_bootstrap 顶格 import 即 ModuleNotFoundError）。
 # 幂等=导入通过零动作；pip 走 python3 -m pip（缺 pip 的像由预检 python3 装法行补齐）；
 # 新发行版受管环境（PEP 668）退 --user 再退 --break-system-packages，失败点名出路不静默。
        if ! "$PY" -c "import httpx" >/dev/null 2>&1; then
            run_recipe_cmd "$PY -m pip install --user -q -r \"$DIR/../installkit/requirements.txt\" \
                || $PY -m pip install --break-system-packages --user -q -r \"$DIR/../installkit/requirements.txt\"" \
                || { log "installkit requirements 安装失败——出路：$PY -m pip install -r installkit/requirements.txt（缺 pip 先装 python3-pip/py3-pip）"; rc=1; }
        fi
 # S1 拉起位点实装（题 1 分支：platform=compose 中间件+四服务指引；executor=只报引擎面）
        run_step compose-seed   "服务与样例种子" "$PY" "$DIR/s1_bootstrap.py" compose \
            --answers-file "$STATE_DIR/answers-S1.json" || rc=1
        [ "$rc" = 0 ] && { run_step storage-check "对象存储就绪" "$PY" "$DIR/s1_bootstrap.py" storage \
            --answers-file "$STATE_DIR/answers-S1.json" || rc=1; }
        # 地址解析=s1 装机键单源（进程环境带宽键>树 .env>admin 出厂口）；壳侧禁回退字面猜地址（r43 格窗红根因）
        SEED_BASE_ARGS=(); [ -n "${INSTALLKIT_SEED_BASE_URL:-}" ] && SEED_BASE_ARGS=(--base-url "$INSTALLKIT_SEED_BASE_URL")
        [ "$rc" = 0 ] && { run_step seed-demo "demo data 自动播种" "$PY" "$DIR/s1_bootstrap.py" seed-demo \
            "${SEED_BASE_ARGS[@]}" || rc=1; }
        ;;
    scale-out)
        if [ -n "$ROLE" ]; then
            case "$ROLE" in
                executor|ai-node|platform) ;;
                *) echo "机器画像「$ROLE」不在值域——必须是 executor / ai-node / platform 之一（令牌签发时三画像，脚本侧禁自造角色）" >&2; exit 2 ;;
            esac
        fi
        if [ -z "$TOKEN" ]; then
            echo "缺少 --token=——入池令牌请在机群页「添加执行器/AI 节点」生成带 token 命令复制（一次性+TTL，过期重生成一条即可）" >&2
            exit 2
        fi
        if [ -z "$BASE_URL" ]; then
            echo "缺少 --base-url=——api 服务地址（如 http://<平台机>:8082），装机脚本只经 api 入口禁直连 core" >&2
            exit 2
        fi
 # 节点卡四格步骤条（03 值域 download|deps|register|healthcheck，值域闸在 core）：
        # 骨架场景下载/装依赖并入核销前打点（deps/register 真执行段随后续装机实装包补挂）。
        run_step hb-start    "开工打点" "$PY" "$DIR/enroll_client.py" heartbeat --base-url "$BASE_URL" --token "$TOKEN" \
            --step=download --step-status=running --message=开始入池核销 || rc=1
        [ "$rc" = 0 ] && { run_step enroll-token "令牌核销入池" "$PY" "$DIR/enroll_client.py" enroll --base-url "$BASE_URL" --token "$TOKEN" || rc=1; }
        [ "$rc" = 0 ] && { run_step hb-done  "收尾打点" "$PY" "$DIR/enroll_client.py" heartbeat --base-url "$BASE_URL" --token "$TOKEN" \
            --step=healthcheck --message=入池流程走完 || rc=1; }
        # Platform-profile extra position (T354): register the host service-agent
        # systemd unit (user domain; credentials land only in a 600 env file per
        # red line 4). Executor/ai-node profiles never own this step.
        if [ "$rc" = 0 ] && [ "$ROLE" = "platform" ]; then
            run_step service-agent-unit "注册 service-agent 单元" register_service_agent || rc=1
        fi
        ;;
    scale-in)
        run_step drain-check "先排空" say "退池必先 drain：停 claim→在跑任务归零→再退（沿用 AA 语义，骨架位点）" || rc=1
        run_step uninstall-boundary "卸载边界" say "安全边界声明：只卸载 installkit 自己安装的东西（版本目录+服务+台账），用户手装件永不触碰" || rc=1
 # 退池卸载段实装：查装机账逐引擎跑配方卸载命令+销账（账外件永不进清单）
        [ "$rc" = 0 ] && { run_step purge-self-installed "卸载自装引擎件" purge_self_installed || rc=1; }
 # 命令领取回执实联（03，c 一次式轮询）：带令牌+api 址才领；
        # scale-in/node-remove 纯确认型当场回执（排空旗/终态判定全在 core，脚本零判定）；
        # 需真执行的型别（engine-*）不冒充回执，留待已入池机 runner 消费循环。
        if [ -n "$TOKEN" ] && [ -n "$BASE_URL" ]; then
            [ "$rc" = 0 ] && { run_step command-ack "领取并回执机群命令" "$PY" "$DIR/enroll_client.py" poll \
                --base-url "$BASE_URL" --token "$TOKEN" --ack || rc=1; }
        else
            say "未提供 --token/--base-url——跳过命令领取回执位点（平台侧撤销/等待态亦可，装机完成后由 runner 消费兜底）"
        fi
        run_step pool-remove "退池留痕" say "机器移除审计留痕（回执已上送平台则命令行随队列台账留痕）" || rc=1
        ;;
    engines)
        if [ -z "$ENGINE" ]; then
            echo "缺少 --engine=<引擎code>——换/加装引擎必须指定引擎码（见引擎中心列表，如 mobilewright/minium/maestro）" >&2
            exit 2
        fi
        if [ -z "$BASE_URL" ]; then
            echo "缺少 --base-url=——api 服务地址（如 http://<平台机>:8082），配方经 api 只读口拉取，脚本禁读库禁直连 core" >&2
            exit 2
        fi
        case "$ACTION" in
            install|uninstall) ;;
            *) echo "配方动作「$ACTION」不在值域——必须是 install / uninstall" >&2; exit 2 ;;
        esac
        RECIPE_FILE="$STATE_DIR/recipe-$ENGINE.json"
        # 位点键含引擎与动作（禁全局同 id：换第二台/第二个引擎或 --action=uninstall
        # 若复用同 id，会被上一次装位的续跑语义静默跳过=实装面假绿）
        run_step "fetch-recipe:$ENGINE" "拉取安装配方" "$PY" "$DIR/enroll_client.py" fetch-recipe \
            --base-url "$BASE_URL" --engine "$ENGINE" --out "$RECIPE_FILE" || rc=1
 # 刀4 S4 换/加装前置链：配方已落盘=runtime_requires 版本线单源可读，
        # 装配方命令前先补齐运行时（node 缺位/版本不足→按画像装好，否则 npm 装完也跑不起来）。
        # 仅 install 动作触发（卸载不需装件）；--dry-run 只出计划不代跑（坑①延伸）。
        # needs 闸判定单源在件内（配方无运行时声明且本机 node 在位=ensure 自身零计划）。
        if [ "$ACTION" = "install" ] && "$PY" "$DIR/runtime_bootstrap.py" needs \
                --state-dir "$STATE_DIR" --answers-file "$STATE_DIR/answers-S1.json"; then
            if [ "$DRY_RUN" = 1 ]; then
                [ "$rc" = 0 ] && { say "运行时前置（--dry-run 只出计划）："
                    "$PY" "$DIR/runtime_bootstrap.py" check --state-dir "$STATE_DIR"; }
            else
                [ "$rc" = 0 ] && { run_step "runtime-bootstrap:$ENGINE" "引擎运行时前置" \
                    "$PY" "$DIR/runtime_bootstrap.py" ensure --yes --state-dir "$STATE_DIR" || rc=1; }
            fi
        fi
        [ "$rc" = 0 ] && { run_step "apply-engine:$ENGINE:$ACTION" "执行配方命令" apply_engine_recipe || rc=1; }
        # 装后 capabilities 无需本脚本处理：装机机未起 runner 时由首启 register 自然上报
 # （runner 消费循环侧装毕局部重探已随c 前半落地，单源禁两处重报）
        ;;
    repair)
 # 体检位点实装：调 doctor.py 单源件跑配方 probe 清单。
        # 只读探测零副作用=不走位点记账（重跑必重探，记账会让二次 repair 跳过真探测）。
        say "体检报告（宿主=$(detect_host)，缺件=提示级点名非阻断）："
        "$PY" "$DIR/doctor.py" --state-dir "$STATE_DIR" --host "$(detect_host)" || rc=1
 # 刀3 缺件点名→自动补齐闭环（运行时支路）：体检报告的 missing_runtime 段
        # 不再只是「报」——repair 是用户显式跑的修复命令=显式同意，带 --yes 直接按本机画像
        # 补齐（nvm 免 sudo 主路，坑①交互密码风险面规避）；补齐先于补装（引擎件装完
        # 也要运行时才跑得起来）。零运行时需求（needs 闸）=不建位点不打扰。
        if [ "$rc" = 0 ] && "$PY" "$DIR/runtime_bootstrap.py" needs \
                --state-dir "$STATE_DIR" --answers-file "$STATE_DIR/answers-S1.json"; then
            run_step runtime-ensure "运行时自动补齐" "$PY" "$DIR/runtime_bootstrap.py" \
                ensure --yes --state-dir "$STATE_DIR" || rc=1
        fi
 # 补段位点实装：需求 diff→复用配方单源补装+记账；位点记账=
        # 二次 repair 不重复外呼（幂等断言），--restart 清位点强制重补。
        [ "$rc" = 0 ] && { run_step refill "缺件补齐" refill_engine || rc=1; }
        # 复检=补装后再跑一次真探测（只读零副作用，同体检位点不走记账口径）
        if [ "$rc" = 0 ] && grep -q "^step:refill$" "$STATE" 2>/dev/null; then
            say "复检（补装后重探）："
            "$PY" "$DIR/doctor.py" --state-dir "$STATE_DIR" --host "$(detect_host)" || rc=1
        fi
        ;;
esac

log "=== scenario=$SCENARIO rc=$rc ==="
exit "$rc"
