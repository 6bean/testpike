#!/usr/bin/env python3
pass
import os
import sys

LANGS = ("zh", "en")
DEFAULT_LANG = "zh"
LANG_ENV_KEY = "INSTALLKIT_LANG"

MESSAGES = {
    "host.not_in_domain": {
        "zh": "宿主画像「{host}」不在值域——必须是 {supported} 之一"
              "（可用 --host 显式指定）",
        "en": "Host profile \"{host}\" is outside the supported range——"
              "it must be one of {supported} (pass --host explicitly to override)",
    },
    "recipe.empty_recipe": {
        "zh": "该引擎安装配方未实装（空配方）——拒绝凭空猜安装命令，"
              "请在引擎中心补配方或等待后续版本实装",
        "en": "This engine has no install recipe yet (empty recipe)——refusing to "
              "guess install commands; add the recipe in Engine Center or wait for "
              "a later release",
    },
    "recipe.hosts_invalid": {
        "zh": "安装配方 hosts 段结构非法（应为宿主画像字典）",
        "en": "Install recipe \"hosts\" section is malformed (expected a host-profile "
              "dictionary)",
    },
    "recipe.host_not_covered": {
        "zh": "配方未提供该宿主画像（当前宿主：{host}；已覆盖：{covered}）",
        "en": "The recipe provides no profile for this host (current host: {host}; "
              "covered: {covered})",
    },
    "recipe.pin_missing": {
        "zh": "配方缺版本钉（version_pin）——反向钉改写无从定位目标版本，"
              "已拒绝执行回滚（禁产出改写不出的假回滚），请先补配方版本钉",
        "en": "Recipe has no version pin (version_pin)——reverse pin rewrite has "
              "nothing to target; rollback refused (a fake rollback is worse). "
              "Add the version pin to the recipe first",
    },
    "ledger.corrupt": {
        "zh": "installed.json 损坏——装机账读不出，无法保证卸载只删自装；"
              "请人工核对重建该文件（或跑 list 看报错细节），再跑体检恢复账面",
        "en": "installed.json is unreadable (damaged ledger)——uninstall can no "
              "longer guarantee \"only remove self-installed\"; rebuild the file "
              "by hand (run list for details), then re-run the health check",
    },
    "ledger.report_missing": {
        "zh": "缺少体检报告 doctor-report.json——repair 流程必先体检（跑 repair 场景"
              "即自动先探），账侧拒绝凭空猜补装计划",
        "en": "Health-check report doctor-report.json is missing——repair always "
              "checks first (run the repair scenario to probe); the ledger refuses "
              "to guess a refill plan",
    },
    "ledger.report_corrupt": {
        "zh": "体检报告 doctor-report.json 损坏——repair 重跑体检即刷新",
        "en": "Health-check report doctor-report.json is damaged——re-run repair to "
              "refresh it",
    },
    "ledger.engine_code_invalid": {
        "zh": "引擎 code「{engine}」非法——不得含键分隔符 |",
        "en": "Engine code \"{engine}\" is invalid——the key separator | is not "
              "allowed",
    },
    "doctor.copy_hint": {
        "zh": "复制诊断报告 doctor-report.json 贴给管理员，或到引擎中心「体检」页"
              "（A1 env-doctor）换取可读的修复步骤（报告已做脱敏自检，禁贴含明文密钥的原始日志）",
        "en": "Copy the health-check report doctor-report.json to your administrator, "
              "or open the Engine Center health panel (A1 env-doctor) for plain-language "
              "fix steps (the report passes a masking self-check——never paste raw logs "
              "containing plaintext keys)",
    },
    "doctor.all_ok": {
        "zh": "全部在位引擎探测通过（本次体检无缺件）",
        "en": "All engines present passed their probes (no missing components "
              "in this check)",
    },
    "doctor.missing_line": {
        "zh": "缺件 {count} 项——修复：install.sh --scenario=engines --engine=<code> "
              "--base-url=<api址> 重装补齐，或 repair 补装实装后重跑体检",
        "en": "{count} component(s) missing——fix: install.sh --scenario=engines "
              "--engine=<code> --base-url=<api-address>, or re-run repair after the "
              "refill step lands",
    },
    "doctor.runtime_missing": {
        "zh": "缺运行时 node（需求 {required}，来源=引擎配方声明）——repair 补齐段"
              "会自动按本机画像装好，无需手动操作",
        "en": "Runtime node missing (required {required}, declared by the engine "
              "recipe)——the repair step auto-installs it for this host profile, "
              "no manual action needed",
    },
    "doctor.runtime_below": {
        "zh": "运行时 node v{current} 版本不足（需求 {required}，来源=引擎配方声明）"
              "——repair 补齐段会自动升级，无需手动操作",
        "en": "Runtime node v{current} is below the required {required} (declared by "
              "the engine recipe)——the repair step upgrades it automatically, "
              "no manual action needed",
    },
    "doctor.mcp_ok": {
        "zh": "MCP 传输握手正常（{base}：未授权请求被正确拒绝；详情 {detail}）",
        "en": "MCP transport handshake OK ({base}: unauthenticated requests are "
              "refused as expected; detail: {detail})",
    },
    "doctor.mcp_shape_gap": {
        "zh": "MCP 传输形制缺口（{base}）：{detail}——外部智能体接入前须先修复 api 服务的认证门",
        "en": "MCP transport shape gap ({base}): {detail} — fix the api service "
              "auth gate before connecting external agents",
    },
    "doctor.mcp_auth_gap": {
        "zh": "MCP 授权握手失败（{base}）：{detail}——检查 TP_MCP_TOKEN 是否为有效 API 令牌且未被停用",
        "en": "MCP authenticated handshake failed ({base}): {detail} — check that "
              "TP_MCP_TOKEN holds a valid, enabled API token",
    },
    "doctor.mcp_unreachable": {
        "zh": "MCP 端点不可达（{base}）：{detail}——确认 api 服务已启动且地址正确",
        "en": "MCP endpoint unreachable ({base}): {detail} — verify the api service "
              "is running at this address",
    },
    "fleet.identity_fail": {
        "zh": "节点 {name} 没有有效的接入身份（未完成一次性注册或注册令牌已失效）——"
              "在服务端重新签发一次性令牌，再在该节点重跑 enroll 接入流程",
        "en": "Node {name} has no valid enroll identity (one-time enrollment missing "
              "or the token expired)——issue a new one-time token on the server and "
              "re-run the enroll flow on that node",
    },
    "fleet.identity_unknown": {
        "zh": "节点 {name} 的接入身份读不到（台账该列无上报）——"
              "确认该节点已登记进执行器台账后重跑本体检",
        "en": "Enroll identity for node {name} is unavailable (column never reported) "
              "——confirm the node is registered in the executor ledger, then re-run",
    },
    "fleet.heartbeat_fail": {
        "zh": "节点 {name} 最近一次心跳已过 {age} 秒，超过离线阈值 {offline} 秒——"
              "在该节点执行 systemctl status tp-service-agent 查看代理进程与网络，恢复后心跳自动回新鲜",
        "en": "Node {name} last heartbeat was {age}s ago, past the {offline}s offline "
              "threshold——run systemctl status tp-service-agent on that node; the "
              "heartbeat recovers on its own once the agent is up",
    },
    "fleet.heartbeat_unknown": {
        "zh": "节点 {name} 尚无心跳记录（从未上报）——"
              "确认该节点已安装并启动 tp-service-agent 服务代理",
        "en": "Node {name} has no heartbeat on record——install and start the "
              "tp-service-agent service on that node",
    },
    "fleet.heartbeat_skew": {
        "zh": "节点 {name} 心跳时间比当前时刻超前 {age} 秒（时钟偏移）——"
              "先校准该节点 NTP 时钟，否则离线判定不可信",
        "en": "Node {name} heartbeat timestamp is {age}s ahead of now (clock skew)"
              "——sync the node clock over NTP first, or the offline verdict cannot be trusted",
    },
    "fleet.version_fail": {
        "zh": "节点 {name} 当前版本 {version} 低于金版本 {gold}——"
              "到「升级中心」对该节点发起滚动升级",
        "en": "Node {name} runs version {version}, behind the gold version {gold}"
              "——start a rolling upgrade for it in the Upgrade Center",
    },
    "fleet.version_unknown": {
        "zh": "节点 {name} 无法比对版本（自身版本未上报或金版本基准未设置）——"
              "确认代理上报组件版本，并在「升级中心」设定金版本",
        "en": "Node {name} cannot be version-compared (own version unreported or no "
              "gold baseline set)——verify the agent component report and set the gold "
              "version in the Upgrade Center",
    },
    "fleet.queue_fail": {
        "zh": "节点 {name} 命令队列双向鉴权不可达（控制面下发未获有效回执）——"
              "核对该节点代理令牌未被吊销且到控制端地址的网络放行，修复后重跑本体检",
        "en": "Command queue for node {name} is unreachable under mutual "
              "authentication (no valid ack to a dispatched command)——check the agent "
              "token has not been revoked and the route to the control address is open, "
              "then re-run",
    },
    "fleet.queue_unknown": {
        "zh": "节点 {name} 的队列连通性未探测——补发探测数据后重跑本体检",
        "en": "Queue connectivity for node {name} was not probed——attach probe data "
              "and re-run",
    },
    "fleet.capacity_full": {
        "zh": "节点 {name} 并发额度已占满（在跑 {running}/{capacity}）——"
              "等待在跑任务结束，或接入更多节点后再派发",
        "en": "Node {name} has no concurrency headroom ({running}/{capacity} running)"
              "——wait for in-flight tasks to finish or attach more nodes before dispatching",
    },
    "fleet.capacity_unknown": {
        "zh": "节点 {name} 容量/在跑数未上报——无法判断余量与可排水性；"
              "确认该节点代理版本支持容量上报",
        "en": "Node {name} reports no capacity/running counters——headroom and drain "
              "reachability cannot be judged; confirm the agent version reports capacity",
    },
    "fleet.ledger_inconsistent": {
        "zh": "节点 {name} 在跑数 {running} 与容量 {capacity} 不自洽（应在 0~{capacity} 之间）——"
              "重启该节点代理按其真实上报，账面恢复前该节点不可排水下线",
        "en": "Node {name} running count {running} is outside the 0..{capacity} window "
              "of its capacity——restart the agent to re-report truthfully; do not drain "
              "this node until the ledger recovers",
    },
}

_warned = set()


def current_lang():
    pass
    return (os.environ.get(LANG_ENV_KEY) or DEFAULT_LANG).strip().lower()


def t(key, lang=None, **fmt):
    pass
    chosen = (lang or current_lang())
    rows = MESSAGES[key]
    if chosen not in LANGS:
        if key not in _warned:
            _warned.add(key)
            print(f"语言「{chosen}」不在值域（{' / '.join(LANGS)}）——本次按中文继续（{key}）",
                  file=sys.stderr)
        chosen = DEFAULT_LANG
    text = rows[chosen]
    return text.format(**fmt) if fmt else text
