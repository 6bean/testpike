# -*- coding: utf-8 -*-
"""Host service-agent .

Standalone lightweight resident daemon managed by systemd — deliberately NOT
the executor runner in disguise (design doc §7 decision B): the services being
upgraded must never own the code path that restarts them, so the agent stays a
systemd sibling unit with exactly three powers:

1. swap the ``current`` release symlink of a managed service,
2. ``systemctl restart <unit>`` for that service (never itself — self-upgrade
 lands the new package, acks, then exits and lets Restart=always relaunch),
3. read local build-info versions and batch-report them through the existing
 enroll-token heartbeat channel (api /internal, components trailing key).

The managed-service catalogue is DATA (``MANAGED_SERVICES`` rows, review note
R-1): adding a platform service = adding a row, never editing agent logic.
Command semantics are fail-closed like the executor consumer: unknown type,
missing payload key, or sha256 mismatch = honest plain-word refusal, no swap,
no restart, no fake-green receipt.

Transport reuses installkit/enroll_client.py single source (token model,
fingerprint, api-only access — out-of-system components must not talk to core
directly). Credentials come from environment only (red line: no inline secrets).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger("testpike.service-agent")

# Agent own version: CI stamps it into the landed package (docs/05 §5.2
# artifact layout); keep in sync with the release feed.
AGENT_VERSION = "1.0.0"
AGENT_STAGING_FILE = "service_agent.next"
FAMILY_SERVICE_AGENT = "service-agent"

# Release layouts (how the running version of a family is discovered locally).
LAYOUT_BUILD_INFO = "build-info"      # backend jars: sidecar build-info.properties
LAYOUT_DIST_VERSION = "dist-version"  # frontend dist: version.json

# Agent-local health gate defaults (AF decision D; K8 keys
# release.health-gate.* seeded in V224 carry the same numbers server-side and
# the enqueueing side writes them into the command payload). These constants
# are the fail-open fallback for payloads produced before the gate existed —
# an old queued command must run through the defaults, never crash.
HEALTH_GATE_DEFAULT_PROBE_COUNT = 5
HEALTH_GATE_DEFAULT_INTERVAL_SECONDS = 2
HEALTH_GATE_DEFAULT_STARTUP_BUDGET_SECONDS = 90

# ── managed-service catalogue (R-1: data rows, not code branches) ────────────

@dataclass(frozen=True)
class ManagedService:
    """One platform family the agent can operate on: ledger family code,
    systemd unit name, on-disk release layout, and the default local health
    probe url (None = no actuator endpoint, e.g. nginx static frontend — the
    gate is then honestly skipped unless the payload carries an url)."""
    family: str
    unit: str
    layout: str
    health_url: str | None = None


# Six rows (mock-server joined at : add a row here — no agent code change,
# no new scheduling). Health urls follow
# docs/05 port table; non-standard hosts override per command via payload
# health_url (the enqueueing side knows the real deployment ports).
MANAGED_SERVICES: tuple[ManagedService, ...] = (
    ManagedService("core", "tp-core.service", LAYOUT_BUILD_INFO,
                   "http://127.0.0.1:8083/actuator/health"),
    ManagedService("admin", "tp-admin.service", LAYOUT_BUILD_INFO,
                   "http://127.0.0.1:8081/actuator/health"),
    ManagedService("api", "tp-api.service", LAYOUT_BUILD_INFO,
                   "http://127.0.0.1:8082/actuator/health"),
    ManagedService("ai-service", "tp-ai-service.service", LAYOUT_BUILD_INFO,
                   "http://127.0.0.1:18084/actuator/health"),
    ManagedService("mock-server", "tp-mock-server.service", LAYOUT_BUILD_INFO,
                   "http://127.0.0.1:18085/actuator/health"),
    ManagedService("frontend", "tp-frontend.service", LAYOUT_DIST_VERSION, None),
)

_PLATFORM_TYPES = ("platform-service-upgrade", "platform-service-rollback")
_AGENT_TYPES = ("agent-upgrade", "agent-rollback")
# T294b pack B: host operation commands (no artifact at all) ride the same
# enroll-token channel but are judged separately — they must never hit the
# upgrade gate that demands target_version/artifact_url/sha256.
CONTROL_TYPE = "platform-service-control"
SELFCHECK_TYPE = "node-self-check"
_CONTROL_ACTIONS = {"start": "启动", "stop": "停止", "restart": "重启"}
_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
_DEFAULT_TIMEOUT = 30


# ── execution environment (value object; red line: no inner classes) ─────────

@dataclass
class AgentEnv:
    """Everything the executor side can touch, injectable for tests.

    root       install root holding <family>/releases/<version> + <family>/current
    fetch      url -> artifact bytes (default: plain HTTP GET)
    spawn      argv -> return code (default: subprocess systemctl)
    probe      url -> bool UP (default: actuator health GET; env-injected at L2)
    sleep      seconds -> None (default: time.sleep; injected no-op at L2)
    state_dir  agent staging slot (systemd ExecStart reads service_agent.next)
    progress   optional best-effort progress reporter for long commands
    """
    root: Path
    fetch: Callable[[str], bytes]
    spawn: Callable[[list], int]
    probe: Callable[[str], bool] | None = None
    sleep: Callable[[float], None] | None = None
    state_dir: Path | None = None
    progress: Callable | None = None


def default_fetch(url: str) -> bytes:
    """Download one release artifact (no redirects followed blindly — hosts are
    already gated server-side by the artifact whitelist at enqueue/pre-check)."""
    import urllib.request
    with urllib.request.urlopen(url, timeout=120) as resp:
        return resp.read()


def default_probe(url: str) -> bool:
    """One local health probe: Spring actuator status UP = True, anything
    else (refused, timeout, DOWN, junk body) = False. Never raises — the
    gate loop treats transport errors as plain DOWN."""
    import urllib.request
    try:
        with urllib.request.urlopen(url, timeout=5) as resp:
            body = json.loads(resp.read().decode("utf-8", "replace"))
        return isinstance(body, dict) and str(body.get("status")).upper() == "UP"
    except Exception:  # noqa: BLE001 - a failing service is exactly what we detect
        return False


def default_spawn(argv: list) -> int:
    return subprocess.run(argv, capture_output=True, text=True,
                          timeout=120, check=False).returncode


def build_env(root, fetch=None, spawn=None, state_dir=None, progress=None,
              probe=None, sleep=None) -> AgentEnv:
    return AgentEnv(root=Path(root),
                    fetch=fetch or default_fetch,
                    spawn=spawn or default_spawn,
                    probe=probe,
                    sleep=sleep,
                    state_dir=Path(state_dir) if state_dir else None,
                    progress=progress)


# ── command result (value object) ────────────────────────────────────────────

@dataclass
class CommandResult:
    """Outcome of one consumed command. exit_after_ack=True means the agent
    landed a new own-package and must exit AFTER the receipt is delivered
    (systemd Restart=always brings up the new code — never exec into it
    in-process)."""
    success: bool
    detail: str
    exit_after_ack: bool = False


# ── version discovery / component report assembly ────────────────────────────

def _read_version_file(path: Path) -> str | None:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return None
    # properties form (build-info.properties): build.version=X
    m = re.search(r"^build\.version=(.+)$", text, re.MULTILINE)
    if m and m.group(1).strip():
        return m.group(1).strip()
    # json form (frontend version.json): {"version": "X"}
    try:
        data = json.loads(text)
        v = data.get("version") if isinstance(data, dict) else None
        return str(v).strip() if v and str(v).strip() else None
    except (ValueError, AttributeError):
        return None


def current_release(root: Path, family: str) -> Path | None:
    """Resolve the on-disk current release dir of a family (None = dangling
    link / missing install = honest absence, never a guess)."""
    link = Path(root) / family / "current"
    try:
        target = link.resolve()
    except OSError:
        return None
    return target if target.is_dir() else None


def read_family_version(root: Path, row: ManagedService) -> str | None:
    rel = current_release(Path(root), row.family)
    if rel is None:
        return None
    for candidate in (rel / "build-info.properties", rel / "version.json"):
        version = _read_version_file(candidate)
        if version:
            return version
    return None


def collect_components(root) -> list[dict]:
    """Local platform-family versions as protocol rows [{family,currentVersion}].

    Probe failure = family honestly omitted (placeholder lies are forbidden);
    cheap file reads, safe to run every heartbeat round without caching."""
    rows = []
    for row in MANAGED_SERVICES:
        version = read_family_version(Path(root), row)
        if version:
            rows.append({"family": row.family, "currentVersion": version})
    return rows


def build_report(root, agent_version: str | None = None) -> list[dict]:
    """Full heartbeat component ledger: platform families + the agent itself."""
    rows = collect_components(root)
    version = agent_version if agent_version is not None else AGENT_VERSION
    if version:
        rows.append({"family": FAMILY_SERVICE_AGENT, "currentVersion": version})
    return rows


def with_components(body: dict, rows: list[dict]) -> dict:
    """Attach the trailing components key only when non-empty (empty/absent =
    old-core zero drift, same posture as every 03 trailing key)."""
    out = dict(body)
    if rows:
        out["components"] = rows
    return out


# ── command execution ────────────────────────────────────────────────────────

def _payload(cmd: dict) -> dict:
    p = cmd.get("payload")
    return p if isinstance(p, dict) else {}


def _text(payload: dict, key: str) -> str | None:
    v = payload.get(key)
    s = str(v).strip() if v is not None else ""
    return s or None


def _require(payload: dict, env: AgentEnv) -> tuple[dict, str, str, CommandResult | None]:
    """Shared key-presence gate for all four platform/agent command types.

    Returns (payload, target_version, artifact_url, refusal_or_None); digest is
    checked per type because the agent self-upgrade path also needs it."""
    target_version = _text(payload, "target_version")
    artifact_url = _text(payload, "artifact_url")
    sha = _text(payload, "sha256")
    missing = [name for name, val in (
        ("target_version（钉版版本）", target_version),
        ("artifact_url（工件地址）", artifact_url),
        ("sha256（64 位摘要）", sha)) if not val]
    if missing:
        return payload, "", "", CommandResult(
            False, "命令载荷缺字段：" + "、".join(missing) + "——缺位即拒，执行侧禁猜（fail-closed）")
    if not _SHA256_RE.match(sha):
        return payload, "", "", CommandResult(
            False, "sha256 摘要必须是 64 位十六进制（当前值形态非法）——拒绝执行坏包嫌疑载荷")
    return payload, target_version, artifact_url, None


def _verify_digest(data: bytes, sha: str) -> CommandResult | None:
    actual = hashlib.sha256(data).hexdigest()
    if actual != sha.lower():
        return CommandResult(
            False, "工件 sha256 校验不一致（期望 %s…，实得 %s…）——坏包绝不上机，保持旧版运行"
                   % (sha[:8], actual[:8]))
    return None


def _relink_current(fam_root: Path, target_dir: Path) -> None:
    """Atomically move <family>/current to target_dir.

    Single mechanism for both the forward swap and the health-gate rollback:
    staging + os.replace means an interrupted agent never leaves a dangling
    link pointing at nothing."""
    link = fam_root / "current"
    tmp_link = fam_root / "current.next"
    if tmp_link.is_symlink() or tmp_link.exists():
        tmp_link.unlink()
    tmp_link.symlink_to(target_dir)
    os.replace(tmp_link, link)


def _swap_release(env: AgentEnv, row: ManagedService, target_version: str,
                  data: bytes, artifact_name: str) -> None:
    """Materialize releases/<version> then atomically move the current symlink.

    Staging dir + rename keeps the swap crash-safe: an interrupted run leaves
    the old link untouched."""
    fam_root = env.root / row.family
    releases = fam_root / "releases"
    final = releases / target_version
    staging = releases / (target_version + ".staging")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    (staging / artifact_name).write_bytes(data)
    # write the version sidecar so the next heartbeat reports the new value
    if row.layout == LAYOUT_BUILD_INFO:
        (staging / "build-info.properties").write_text(
            "build.version=%s\nbuild.time=%s\n" % (target_version,
                                                   time.strftime("%Y-%m-%dT%H:%M:%S.000Z",
                                                                 time.gmtime())),
            encoding="utf-8")
    else:
        (staging / "version.json").write_text(
            json.dumps({"version": target_version}), encoding="utf-8")
    if final.exists():
        shutil.rmtree(final)
    staging.rename(final)
    _relink_current(fam_root, final)


# ── agent-local health gate ────────

def _positive_int(payload: dict, key: str, default: int) -> int:
    """Read one gate parameter, degrading junk/negative/zero to the default.

    A mis-filled value must never mean "instant success" (probe-count 0) or
    an endless wait (budget 0) — the defaults are the safe harbour."""
    raw = payload.get(key)
    if raw is None:
        return default
    try:
        val = int(str(raw).strip())
    except (TypeError, ValueError):
        return default
    return val if val > 0 else default


def resolve_gate_params(payload: dict) -> tuple[int, int, int]:
    """(probe_count, interval_seconds, startup_budget_seconds) for one command.

    Payload keys win (the enqueueing core reads the release.health-gate.* K8
    keys per decision D — hot-updatable, zero hardcoding server-side); absent
    or invalid keys fail open to the agent defaults so commands produced
    before the gate existed still run instead of crashing."""
    return (_positive_int(payload, "health_probe_count",
                          HEALTH_GATE_DEFAULT_PROBE_COUNT),
            _positive_int(payload, "health_probe_interval_seconds",
                          HEALTH_GATE_DEFAULT_INTERVAL_SECONDS),
            _positive_int(payload, "health_startup_budget_seconds",
                          HEALTH_GATE_DEFAULT_STARTUP_BUDGET_SECONDS))


def run_health_gate(env: AgentEnv, url: str, probe_count: int,
                    interval_seconds: int, budget_seconds: int) -> dict:
    """Probe the health endpoint until probe_count consecutive UPs.

    Returns {"ok": True, "probes", "first_up_seconds", "consecutive_ups"} on
    success, or {"ok": False, "reason": "down_after_up"|"budget_exhausted",
    "probes"} on failure. DOWN before the first UP merely means "still
    booting" and is tolerated until the startup budget runs out; the first
    DOWN AFTER an UP is decisive — the service came up and broke = a proven
    bad release, stop probing immediately instead of burning the remaining
    budget on it.
    """
    probe = getattr(env, "probe", None) or default_probe
    sleep = getattr(env, "sleep", None) or time.sleep
    started = time.monotonic()
    deadline = started + budget_seconds
    first_up: float | None = None
    consecutive = 0
    probes = 0
    while True:
        probes += 1
        up = bool(probe(url))
        if up:
            if first_up is None:
                first_up = time.monotonic() - started
            consecutive += 1
            if consecutive >= probe_count:
                return {"ok": True, "probes": probes,
                        "first_up_seconds": round(first_up or 0.0, 1),
                        "consecutive_ups": consecutive}
        else:
            if first_up is not None:
                return {"ok": False, "reason": "down_after_up", "probes": probes}
            if time.monotonic() + interval_seconds > deadline:
                return {"ok": False, "reason": "budget_exhausted", "probes": probes}
        time_passed = time.monotonic() - started
        if time_passed >= budget_seconds:
            return {"ok": False, "reason": "budget_exhausted", "probes": probes}
        sleep(min(interval_seconds, max(0.0, budget_seconds - time_passed)))


def _rollback_after_failed_gate(env: AgentEnv, row: ManagedService,
                                fam_root: Path, previous: Path | None,
                                target_version: str, verb: str,
                                gate: dict) -> str:
    """Re-point the current link at the pre-operation release and restart.

    This is the seconds-scale self-rescue the design pins: the platform
    control plane may itself be the thing that died, so recovery is fully
    local. No previous release (first install) = nothing to roll back to —
    say so plainly instead of pretending recovery."""
    cause = ("启动预算内始终未就绪（预算超时）" if gate.get("reason") == "budget_exhausted"
             else "服务起来后又转为不健康（连续通过中断）")
    if previous is None:
        return ("%s到 %s 但健康闸未通过（%s），且本机无旧版可回切——"
                "请人工处置单元 %s" % (verb, target_version, cause, row.unit))
    _relink_current(fam_root, previous)
    try:
        rc = env.spawn(["systemctl", "restart", row.unit])
    except Exception as e:  # noqa: BLE001 - receipt must land even if systemctl hangs
        return ("%s到 %s 健康闸未通过（%s），已回切旧版 %s 但重启命令未能确认结果（%s）——"
                "请人工核对单元 %s" % (verb, target_version, cause, previous.name,
                                      type(e).__name__, row.unit))
    if rc != 0:
        return ("%s到 %s 健康闸未通过（%s），已回切旧版 %s 但旧版重启失败（systemctl 退出码 %s）——"
                "请人工处置单元 %s" % (verb, target_version, cause, previous.name,
                                      rc, row.unit))
    return ("%s到 %s 失败：健康闸未通过（%s），已自动回切旧版 %s 并重启恢复——"
            "坏包已隔离在 releases/%s，台账未动"
            % (verb, target_version, cause, previous.name, target_version))


def _run_platform_command(ctype: str, cmd: dict, env: AgentEnv) -> CommandResult:
    payload = _payload(cmd)
    service = _text(payload, "service")
    if not service:
        valid = "/".join(row.family for row in MANAGED_SERVICES)
        return CommandResult(False,
                             "命令载荷缺 service（平台族码）——有效值：%s" % valid)
    row = next((r for r in MANAGED_SERVICES if r.family == service), None)
    if row is None:
        valid = "/".join(r.family for r in MANAGED_SERVICES)
        return CommandResult(False,
                             "未知平台服务「%s」——本机可管服务清单（数据化枚举）为：%s"
                             % (service, valid))
    payload, target_version, artifact_url, refusal = _require(payload, env)
    if refusal:
        return refusal
    sha = _text(payload, "sha256")
    progress = getattr(env, "progress", None)
    if progress:
        progress(cmd, "fetch", "拉取工件 %s" % artifact_url)
    try:
        data = env.fetch(artifact_url)
    except Exception as e:  # noqa: BLE001 - transport failure must land as honest receipt
        return CommandResult(False, "工件下载失败：%s——保持旧版运行" % e)
    bad = _verify_digest(data, sha)
    if bad:
        return bad
    artifact_name = os.path.basename(artifact_url.split("?")[0]) or (row.family + ".bin")
    # capture the pre-operation release BEFORE the swap: this is the rollback
    # target if the health gate fails (local immediate state, never a guess)
    previous = current_release(Path(env.root), row.family)
    _swap_release(env, row, target_version, data, artifact_name)
    if progress:
        progress(cmd, "restart", "重启服务单元 %s" % row.unit)
    # A spawn failure (hanging systemctl = TimeoutExpired seen in the L4 smoke)
    # must still land an honest receipt — an uncaught raise here would leave the
    # command claimed forever with no trace for the operator.
    try:
        rc = env.spawn(["systemctl", "restart", row.unit])
    except Exception as e:  # noqa: BLE001 - transport failure = honest receipt, never crash
        return CommandResult(False,
                             "已切到版本 %s 但重启命令未能确认结果（%s）——"
                             "请人工核对单元 %s 状态，必要时重新下发反向回滚命令"
                             % (target_version, type(e).__name__, row.unit))
    verb = "升级" if ctype.endswith("upgrade") else "回滚"
    if rc != 0:
        return CommandResult(False,
                             "已切到版本 %s 但服务重启失败（systemctl 退出码 %s）——"
                             "请人工确认单元 %s，必要时再下发反向回滚命令"
                             % (target_version, rc, row.unit))
    # ── agent-local health gate : the platform control plane may
    # be the very thing that died, so pass/fail is judged locally and a failed
    # gate self-rescues by re-pointing the pre-operation release ──────────────
    health_url = _text(payload, "health_url") or row.health_url
    if not health_url:
        return CommandResult(True,
                             "%s完成：%s → %s，单元 %s 已重启"
                             "（无健康探测地址，健康闸跳过——回执如实标注）"
                             % (verb, row.family, target_version, row.unit))
    probe_count, interval, budget = resolve_gate_params(payload)
    if progress:
        progress(cmd, "health-gate", "健康闸探测 %s" % health_url)
    gate = run_health_gate(env, health_url, probe_count, interval, budget)
    if gate["ok"]:
        return CommandResult(True,
                             "%s完成：%s → %s，单元 %s 已重启；健康闸通过（连续 %d 次 UP，"
                             "共探测 %d 次，首次 UP 耗时 %.1fs）"
                             % (verb, row.family, target_version, row.unit,
                                gate["consecutive_ups"], gate["probes"],
                                gate["first_up_seconds"]))
    return CommandResult(False,
                         _rollback_after_failed_gate(env, row,
                                                     Path(env.root) / row.family,
                                                     previous, target_version,
                                                     verb, gate))


def _run_agent_command(ctype: str, cmd: dict, env: AgentEnv) -> CommandResult:
    """Self-upgrade/rollback = land bytes in the staging slot the systemd unit
    reads, then exit AFTER the receipt; Restart=always brings up the new code.
    In-process self-replacement is forbidden (design §7)."""
    payload = _payload(cmd)
    payload, target_version, artifact_url, refusal = _require(payload, env)
    if refusal:
        return refusal
    sha = _text(payload, "sha256")
    state = getattr(env, "state_dir", None)
    if state is None:
        return CommandResult(False, "agent 状态目录未配置（SERVICE_AGENT_STATE_DIR）——"
                                    "落盘无处可去，拒绝执行")
    state = Path(state)
    try:
        data = env.fetch(artifact_url)
    except Exception as e:  # noqa: BLE001
        return CommandResult(False, "agent 包下载失败：%s——维持当前版本" % e)
    bad = _verify_digest(data, sha)
    if bad:
        return bad
    state.mkdir(parents=True, exist_ok=True)
    tmp = state / (AGENT_STAGING_FILE + ".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, state / AGENT_STAGING_FILE)
    verb = "升级" if ctype.endswith("upgrade") else "回滚"
    return CommandResult(True,
                         "agent %s包已落盘（目标版本 %s）——回执送达后本进程退出，"
                         "由 systemd 拉起新版本" % (verb, target_version),
                         exit_after_ack=True)


def _run_service_control(cmd: dict, env: AgentEnv) -> CommandResult:
    """Start/stop/restart one managed systemd unit (T294b pack B).

    No artifact involvement: the payload carries a service family code and an
    action verb mapped verbatim onto systemctl. A failed verb lands an honest
    receipt naming the exit code plus a manual-check entry (R63), never a
    silent half-state."""
    payload = _payload(cmd)
    valid = "/".join(r.family for r in MANAGED_SERVICES)
    service = _text(payload, "service")
    if not service:
        return CommandResult(False, "启停命令载荷缺 service（平台族码）——有效服务码：%s" % valid)
    row = next((r for r in MANAGED_SERVICES if r.family == service), None)
    if row is None:
        return CommandResult(False, "未知平台服务「%s」——本机可管服务清单（数据化枚举）为：%s"
                             % (service, valid))
    action = _text(payload, "action")
    if not action:
        return CommandResult(False, "启停命令载荷缺 action——有效值：start / stop / restart（目标单元 %s）"
                             % row.unit)
    verb = _CONTROL_ACTIONS.get(action)
    if verb is None:
        return CommandResult(False, "未知操作「%s」——启停命令 action 有效值：start / stop / restart"
                             % action)
    progress = getattr(env, "progress", None)
    if progress:
        progress(cmd, "service-control", "%s单元 %s" % (verb, row.unit))
    try:
        rc = env.spawn(["systemctl", action, row.unit])
    except Exception as e:  # noqa: BLE001 - unconfirmed result = honest receipt, never crash
        return CommandResult(False,
                             "%s结果无法确认（%s）——请人工核对单元 %s 状态（journalctl -u %s）"
                             % (verb, type(e).__name__, row.unit, row.unit))
    if rc != 0:
        return CommandResult(False,
                             "%s失败：systemctl 退出码 %s（单元 %s）——请人工核对 journalctl -u %s；"
                             "服务可能处于中间态，确认后可重新下发" % (verb, rc, row.unit, row.unit))
    return CommandResult(True, "%s完成：%s 单元 %s（systemctl %s，退出码 0）"
                         % (verb, row.family, row.unit, action))


def _run_self_check(cmd: dict, env: AgentEnv) -> CommandResult:
    pass
    payload = _payload(cmd)
    valid = "/".join(r.family for r in MANAGED_SERVICES)
    service = _text(payload, "service")
    if service:
        row = next((r for r in MANAGED_SERVICES if r.family == service), None)
        if row is None:
            return CommandResult(False, "未知平台服务「%s」——本机可管服务清单（数据化枚举）为：%s"
                                 % (service, valid))
        rows = [row]
    else:
        rows = list(MANAGED_SERVICES)
    probe = getattr(env, "probe", None) or default_probe
    lines = []
    for row in rows:
        try:
            rc = env.spawn(["systemctl", "is-active", row.unit])
            state = "运行中" if rc == 0 else "已停止（systemctl is-active 退出码 %s）" % rc
        except Exception as e:  # noqa: BLE001
            state = "状态无法确认（%s）" % type(e).__name__
        version = read_family_version(Path(env.root), row) or "未知"
        health_url = _text(payload, "health_url") or row.health_url
        if not health_url:
            health = "未配置探测地址"
        else:
            try:
                health = "可达" if probe(health_url) else "不可达"
            except Exception:  # noqa: BLE001
                health = "不可达（探测异常）"
        lines.append("%s：%s｜版本 %s｜健康 %s" % (row.family, state, version, health))
    return CommandResult(True,
                         "自检报告（%d 族）：%s" % (len(rows), "；".join(lines)))


def execute_command(cmd: dict, env: AgentEnv) -> CommandResult:
    """Dispatch one claimed command; every path returns an honest receipt."""
    ctype = str(cmd.get("commandType") or "")
    if ctype in _PLATFORM_TYPES:
        return _run_platform_command(ctype, cmd, env)
    if ctype in _AGENT_TYPES:
        return _run_agent_command(ctype, cmd, env)
    if ctype == CONTROL_TYPE:
        return _run_service_control(cmd, env)
    if ctype == SELFCHECK_TYPE:
        return _run_self_check(cmd, env)
    valid = "/".join(_PLATFORM_TYPES + _AGENT_TYPES + (CONTROL_TYPE, SELFCHECK_TYPE))
    return CommandResult(False,
                         "本 agent 不消费命令型「%s」——service-agent 只执行：%s；"
                         "其余型别属执行器 runner 消费面，此处 fail-closed 拒执行"
                         % (ctype or "未知", valid))


# ── transport (reuses installkit/enroll_client.py single source) ─────────────

def _client():
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import enroll_client
    return enroll_client


def poll_commands(base_url: str, token: str, fingerprint: str, limit: int):
    """Claim queued commands in enroll-token form (03 §2.9 poll).

    None = transport refused (base unreachable / server reject, reason already
    printed) — distinct from [] = genuinely nothing to do."""
    ec = _client()
    payload = ec._post(base_url, "/internal/cluster/commands/poll", token,
                       fingerprint, extra={"limit": limit})
    if payload is None:
        return None
    if str(payload.get("code")) != "0":
        print(payload.get("msg") or "命令领取失败", file=sys.stderr)
        return None
    return payload.get("data") or []


def ack_command(base_url: str, command_id, claimer_id, success: bool,
                detail: str, token: str, fingerprint: str) -> None:
    """Deliver the final receipt carrying the enrollment identity (03 §2.9
    receipts half: the api bridge folds the plaintext token into its digest and
    core reconciles digest + bound fingerprint before any write; CAS and
    terminal-state idempotency all stay server-side)."""
    ec = _client()
    ec._post(base_url, "/internal/cluster/commands/result", token, fingerprint,
             extra={"commandId": command_id, "claimerId": claimer_id,
                    "success": bool(success), "resultDetail": detail[:2000]})


def post_heartbeat(base_url: str, token: str, fingerprint: str,
                   components: list[dict]) -> None:
    """Token heartbeat with trailing components ledger (03 §2.8; empty = no key)."""
    ec = _client()
    ec._post(base_url, "/internal/cluster/enroll/heartbeat", token, fingerprint,
             extra=with_components({}, components))


def run_once(base_url: str, token: str, env: AgentEnv,
             limit: int = 5) -> int:
    """One pull-execute-ack cycle: poll in token form, run, receipt.

    Returns 0 on a clean cycle (per-command failures are honest receipts, not
    agent errors); non-zero only when polling itself was impossible."""
    ec = _client()
    fingerprint = ec.machine_fingerprint()
    commands = poll_commands(base_url, token, fingerprint, limit)
    if commands is None:
        return 1
    exit_after = False
    for cmd in commands:
        result = execute_command(cmd, env)
        ack_command(base_url, cmd.get("id"), cmd.get("claimedBy"),
                    result.success, result.detail, token, fingerprint)
        exit_after = exit_after or result.exit_after_ack
        if exit_after:
            log.info("agent 新包已落盘并回执，退出等待 systemd 拉起")
            return 0
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="service_agent.py",
        description="主机 service-agent：平台服务一键升级/回滚消费面 + 部件版本心跳上报")
    parser.add_argument("--base-url", default=os.environ.get("SERVICE_AGENT_BASE_URL", ""))
    parser.add_argument("--token", default=os.environ.get("SERVICE_AGENT_TOKEN", ""))
    parser.add_argument("--root", default=os.environ.get("SERVICE_AGENT_INSTALL_ROOT",
                                                         "/opt/testpike/services"))
    parser.add_argument("--state-dir", default=os.environ.get("SERVICE_AGENT_STATE_DIR",
                                                              "/var/lib/testpike/service-agent"))
    parser.add_argument("--interval", type=int,
                        default=int(os.environ.get("SERVICE_AGENT_HEARTBEAT_INTERVAL", "30")))
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("report", help="打印本机部件版本清单 JSON（排障/体检用）")
    sub.add_parser("once", help="单轮：领命令→执行→回执（cron/调试用）")
    sub.add_parser("run", help="常驻：心跳扩报 + 命令轮询（systemd 托管形态）")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, stream=sys.stderr,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")

    if not args.base_url or not args.token:
        print("缺少 --base-url / --token（或对应环境变量）——agent 无法接平台", file=sys.stderr)
        return 2

    state = Path(args.state_dir)
    env = build_env(root=args.root, state_dir=state,
                    progress=lambda cmd, step, msg: None)

    if args.cmd == "report":
        print(json.dumps(build_report(Path(args.root)), ensure_ascii=False))
        return 0

    ec = _client()
    fingerprint = ec.machine_fingerprint()
    if args.cmd == "once":
        return run_once(args.base_url, args.token, env)

    while True:
        try:
            post_heartbeat(args.base_url, args.token, fingerprint,
                           build_report(Path(args.root)))
            run_once(args.base_url, args.token, env)
        except Exception as e:  # noqa: BLE001 - daemon loop survives transient failures
            log.warning("心跳/命令轮询异常（下轮重试）: %s", e)
        time.sleep(max(args.interval, 5))
    return 0


if __name__ == "__main__":
    # Relaunch contract: the systemd unit's ExecStart picks the staged package
    # (AGENT_STAGING_FILE) when present, so exiting after an agent-upgrade
    # receipt brings up the new code — never exec into it in-process.
    sys.exit(main())
