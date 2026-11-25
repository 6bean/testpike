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
_CONTROL_ACTIONS = {"start": "Start", "stop": "Stop", "restart": "Restart"}
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
        ("target_version (pinned version)", target_version),
        ("artifact_url (artifact download URL)", artifact_url),
        ("sha256 (64-hex digest)", sha)) if not val]
    if missing:
        return payload, "", "", CommandResult(
            False, "command payload is missing fields: " + ", ".join(missing)
            + " — refused fail-closed, the executor never guesses the payload")
    if not _SHA256_RE.match(sha):
        return payload, "", "", CommandResult(
            False, "sha256 must be exactly 64 hex characters (the current value has an "
            "invalid shape) — refusing a suspect artifact payload")
    return payload, target_version, artifact_url, None


def _verify_digest(data: bytes, sha: str) -> CommandResult | None:
    actual = hashlib.sha256(data).hexdigest()
    if actual != sha.lower():
        return CommandResult(
            False, "artifact sha256 mismatch (expected %s..., got %s...) — the bad "
                   "package never lands on the host; the old version keeps running"
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
    cause = ("never became ready within the startup budget (budget exhausted)"
             if gate.get("reason") == "budget_exhausted"
             else "the service turned unhealthy again after starting "
                  "(consecutive-UP streak broken)")
    if previous is None:
        return ("%s to %s did not pass the health gate (%s) and there is no "
                "previous release on this host to roll back to — handle unit %s "
                "manually" % (verb, target_version, cause, row.unit))
    _relink_current(fam_root, previous)
    try:
        rc = env.spawn(["systemctl", "restart", row.unit])
    except Exception as e:  # noqa: BLE001 - receipt must land even if systemctl hangs
        return ("%s to %s failed the health gate (%s); rolled back to %s but the "
                "restart result could not be confirmed (%s) — check unit %s "
                "manually" % (verb, target_version, cause, previous.name,
                              type(e).__name__, row.unit))
    if rc != 0:
        return ("%s to %s failed the health gate (%s); rolled back to %s but the "
                "rollback restart failed (systemctl exit code %s) — handle unit %s "
                "manually" % (verb, target_version, cause, previous.name, rc,
                              row.unit))
    return ("%s to %s failed: the health gate was not passed (%s); automatically "
            "rolled back to %s and restarted — the bad package is quarantined under "
            "releases/%s, the inventory was not touched"
            % (verb, target_version, cause, previous.name, target_version))


def _run_platform_command(ctype: str, cmd: dict, env: AgentEnv) -> CommandResult:
    payload = _payload(cmd)
    service = _text(payload, "service")
    if not service:
        valid = "/".join(row.family for row in MANAGED_SERVICES)
        return CommandResult(False,
                             "command payload is missing service (platform family code) — valid values: %s" % valid)
    row = next((r for r in MANAGED_SERVICES if r.family == service), None)
    if row is None:
        valid = "/".join(r.family for r in MANAGED_SERVICES)
        return CommandResult(False,
                             "unknown platform service \"%s\" — the manageable service list on this host "
                                 "(data-driven enum) is: %s"
                             % (service, valid))
    payload, target_version, artifact_url, refusal = _require(payload, env)
    if refusal:
        return refusal
    sha = _text(payload, "sha256")
    progress = getattr(env, "progress", None)
    if progress:
        progress(cmd, "fetch", "fetching artifact %s" % artifact_url)
    try:
        data = env.fetch(artifact_url)
    except Exception as e:  # noqa: BLE001 - transport failure must land as honest receipt
        return CommandResult(False, "artifact download failed: %s — the old version "
                             "keeps running" % e)
    bad = _verify_digest(data, sha)
    if bad:
        return bad
    artifact_name = os.path.basename(artifact_url.split("?")[0]) or (row.family + ".bin")
    # capture the pre-operation release BEFORE the swap: this is the rollback
    # target if the health gate fails (local immediate state, never a guess)
    previous = current_release(Path(env.root), row.family)
    _swap_release(env, row, target_version, data, artifact_name)
    if progress:
        progress(cmd, "restart", "restarting service unit %s" % row.unit)
    # A spawn failure (hanging systemctl = TimeoutExpired seen in the L4 smoke)
    # must still land an honest receipt — an uncaught raise here would leave the
    # command claimed forever with no trace for the operator.
    try:
        rc = env.spawn(["systemctl", "restart", row.unit])
    except Exception as e:  # noqa: BLE001 - transport failure = honest receipt, never crash
        return CommandResult(False,
                             "switched to version %s but the restart result could not be confirmed (%s) — "
                             "check unit %s manually and re-issue a reverse rollback command "
                             "if needed"
                             % (target_version, type(e).__name__, row.unit))
    verb = "Upgrade" if ctype.endswith("upgrade") else "Rollback"
    if rc != 0:
        return CommandResult(False,
                             "switched to version %s but the service restart failed (systemctl exit code %s) — "
                             "confirm unit %s manually and re-issue a reverse rollback "
                             "command if needed"
                             % (target_version, rc, row.unit))
    # ── agent-local health gate : the platform control plane may
    # be the very thing that died, so pass/fail is judged locally and a failed
    # gate self-rescues by re-pointing the pre-operation release ──────────────
    health_url = _text(payload, "health_url") or row.health_url
    if not health_url:
        return CommandResult(True,
                             "%s complete: %s -> %s, unit %s restarted (no health probe URL configured, "
                             "health gate skipped — stated honestly in this receipt)"
                             % (verb, row.family, target_version, row.unit))
    probe_count, interval, budget = resolve_gate_params(payload)
    if progress:
        progress(cmd, "health-gate", "probing the health gate at %s" % health_url)
    gate = run_health_gate(env, health_url, probe_count, interval, budget)
    if gate["ok"]:
        return CommandResult(True,
                             "%s complete: %s -> %s, unit %s restarted; health gate passed "
                             "(%d consecutive UP probes out of %d, first UP after %.1fs)"
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
        return CommandResult(False, "agent state directory is not configured "
                                "(SERVICE_AGENT_STATE_DIR) — there is nowhere to stage "
                                "the package; refusing to execute")
    state = Path(state)
    try:
        data = env.fetch(artifact_url)
    except Exception as e:  # noqa: BLE001
        return CommandResult(False, "agent package download failed: %s — the current "
                             "version is kept" % e)
    bad = _verify_digest(data, sha)
    if bad:
        return bad
    state.mkdir(parents=True, exist_ok=True)
    tmp = state / (AGENT_STAGING_FILE + ".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, state / AGENT_STAGING_FILE)
    verb = "Upgrade" if ctype.endswith("upgrade") else "Rollback"
    return CommandResult(True,
                         "agent %s package landed in the staging slot (target version %s) — this "
                         "process exits once the receipt is delivered and systemd "
                         "relaunches the new version" % (verb.lower(), target_version),
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
        return CommandResult(False, "service-control payload is missing service "
                             "(platform family code) — valid service codes: %s" % valid)
    row = next((r for r in MANAGED_SERVICES if r.family == service), None)
    if row is None:
        return CommandResult(False, "unknown platform service \"%s\" — the manageable service list on this host "
                                 "(data-driven enum) is: %s"
                             % (service, valid))
    action = _text(payload, "action")
    if not action:
        return CommandResult(False, "service-control payload is missing action — "
                             "valid values: start / stop / restart (target unit %s)"
                             % row.unit)
    verb = _CONTROL_ACTIONS.get(action)
    if verb is None:
        return CommandResult(False, "unknown action \"%s\" — valid service-control "
                             "actions: start / stop / restart" % action)
    progress = getattr(env, "progress", None)
    if progress:
        progress(cmd, "service-control", "%s unit %s" % (verb, row.unit))
    try:
        rc = env.spawn(["systemctl", action, row.unit])
    except Exception as e:  # noqa: BLE001 - unconfirmed result = honest receipt, never crash
        return CommandResult(False,
                             "%s result could not be confirmed (%s) — check unit %s manually "
                             "(journalctl -u %s)"
                             % (verb, type(e).__name__, row.unit, row.unit))
    if rc != 0:
        return CommandResult(False,
                             "%s failed: systemctl exit code %s (unit %s) — inspect journalctl -u %s; "
                             "the service may be in an intermediate state, re-issue the "
                             "command after confirming" % (verb, rc, row.unit, row.unit))
    return CommandResult(True, "%s complete: %s unit %s (systemctl %s, exit code 0)"
                         % (verb, row.family, row.unit, action))


def _run_self_check(cmd: dict, env: AgentEnv) -> CommandResult:
    pass
    payload = _payload(cmd)
    valid = "/".join(r.family for r in MANAGED_SERVICES)
    service = _text(payload, "service")
    if service:
        row = next((r for r in MANAGED_SERVICES if r.family == service), None)
        if row is None:
            return CommandResult(False, "unknown platform service \"%s\" — the manageable service list on this host "
                                 "(data-driven enum) is: %s"
                                 % (service, valid))
        rows = [row]
    else:
        rows = list(MANAGED_SERVICES)
    probe = getattr(env, "probe", None) or default_probe
    lines = []
    for row in rows:
        try:
            rc = env.spawn(["systemctl", "is-active", row.unit])
            state = "running" if rc == 0 else "stopped (systemctl is-active exit code %s)" % rc
        except Exception as e:  # noqa: BLE001
            state = "state could not be confirmed (%s)" % type(e).__name__
        version = read_family_version(Path(env.root), row) or "unknown"
        health_url = _text(payload, "health_url") or row.health_url
        if not health_url:
            health = "no probe URL configured"
        else:
            try:
                health = "reachable" if probe(health_url) else "unreachable"
            except Exception:  # noqa: BLE001
                health = "unreachable (probe error)"
        lines.append("%s: %s | version %s | health %s" % (row.family, state, version, health))
    return CommandResult(True,
                         "self-check report (%d families): %s" % (len(rows), "; ".join(lines)))


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
                         "this agent does not consume command type \"%s\" — service-agent executes "
                         "only: %s; other types belong to the executor runner and are "
                         "refused fail-closed here" % (ctype or "unknown", valid))


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
        print(payload.get("msg") or "command claim failed", file=sys.stderr)
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
            log.info("agent new package landed and receipt delivered; exiting so "
             "systemd can relaunch the new version")
            return 0
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="service_agent.py",
        description="host service-agent: one-click platform service upgrade/rollback "
                    "consumer + component version heartbeat reporting")
    parser.add_argument("--base-url", default=os.environ.get("SERVICE_AGENT_BASE_URL", ""))
    parser.add_argument("--token", default=os.environ.get("SERVICE_AGENT_TOKEN", ""))
    parser.add_argument("--root", default=os.environ.get("SERVICE_AGENT_INSTALL_ROOT",
                                                         "/opt/testpike/services"))
    parser.add_argument("--state-dir", default=os.environ.get("SERVICE_AGENT_STATE_DIR",
                                                              "/var/lib/testpike/service-agent"))
    parser.add_argument("--interval", type=int,
                        default=int(os.environ.get("SERVICE_AGENT_HEARTBEAT_INTERVAL", "30")))
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("report", help="print the local component version manifest as "
                       "JSON (for troubleshooting / health checks)")
    sub.add_parser("once", help="single round: claim command -> execute -> deliver "
                     "receipt (for cron / debugging)")
    sub.add_parser("run", help="daemon loop: heartbeat reporting + command "
                     "polling (systemd-managed form)")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, stream=sys.stderr,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")

    if not args.base_url or not args.token:
        print("missing --base-url / --token (or the matching environment variables) — "
          "the agent cannot reach the platform", file=sys.stderr)
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
            log.warning("heartbeat/command polling error (retrying next round): %s", e)
        time.sleep(max(args.interval, 5))
    return 0


if __name__ == "__main__":
    # Relaunch contract: the systemd unit's ExecStart picks the staged package
    # (AGENT_STAGING_FILE) when present, so exiting after an agent-upgrade
    # receipt brings up the new code — never exec into it in-process.
    sys.exit(main())
