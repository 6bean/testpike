#!/usr/bin/env python3
"""Preflight for a detached verification window: fail in seconds, not in 76 minutes.

Why: a full window is expensive (measured 2h04m for the AO close snapshot: static
gates 1m26s, backend L2 44m25s, L4 journeys 2m43s, full E2E 76m02s). Launching one
without checking that the stack under it is current and reachable burns the whole
window on an environment condition instead of a product result: one triage rerun
(53 minutes) had to be voided because the running core service was still serving a
stale jar, and the next window inherited a stalled teardown.

Checks (each prints one named line; FAIL blocks, WARN does not):
  artifact-freshness  backend sources newer than the artifact the service runs
  service-health      every endpoint the window will drive over HTTP
  db-baseline         factory/baseline rows present (check only: never --repair
                      inside a window, that is a known rig defect)
  ai-queue-backlog    generations waiting ahead of AI-terminal specs
  stray-drivers       playwright driver processes from dead sessions
  window-budget       declared node list vs declared cap (worst-case math)

Usage:
  e2e_window_preflight.py [--repo-root .] [--node-list F] [--workers N]
      [--eta-cap-s N] [--out JSON] [--selftest]
Exit: 0 = GO, 1 = NOGO (a named reason is always printed).
Credentials are never printed or inlined: DB access reads env only.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path   # module-level: check_runner_unit reads /proc/uptime
                        # (two functions used to hide a local import; that is how a bare
                        #  Path reference slipped in)

# Modules whose artifact must not be older than their sources.
BACKEND_MODULES = os.environ.get(
    "TP_PREFLIGHT_MODULES", "core,admin,api,ai-service").split(",")
# Ports and host:port:path triples the window drives. A literal band may only
# serve as the shipped default: on a slot chain the launcher loads the chain
# env (ADMIN_PORT/CORE_PORT/AISERVICE_PORT/SERVER_PORT/VITE_PORT) before this
# probe runs, and grading the main band from an inherited literal would fail
# the slot's own fresh instances with "stale runtime" on ports that belong to
# another chain (R48: derived values read the install/slot env, never a
# hardcoded band; env-tunable overrides stay for exotic layouts).
def _preflight_env(environ, name: str, shipped: str) -> str:
    raw = environ.get(name, "")
    return raw.strip() or shipped


def probe_targets(environ):
    """Pure derivation of (service_ports, endpoints) from an env mapping —
    split out so the two-sided selftest can plant a slot band and clear it."""
    service = _preflight_env(
        environ, "TP_PREFLIGHT_SERVICE_PORTS",
        # The shipped default covers every backend module the slot stack boots
        # (e2e_slot_stack ensure_backend_stack starts all four roles, so no
        # legitimate window loses its listener to this widening). Measured
        # 2026-11-21: a running core JVM predated the merged queue-routing
        # change by 16616s and was caught only because core sat on the probe;
        # api and ai-service JVMs were outside the surface entirely and had
        # to be hot-swapped by hand. Narrow-scope recipes keep declaring a
        # subset through TP_PREFLIGHT_SERVICE_PORTS; a recorded per-module
        # swap deferral stays available through TP_PREFLIGHT_ACCEPT_STALE.
        "core:%s,admin:%s,api:%s,ai-service:%s" % (
            _preflight_env(environ, "CORE_PORT", "8083"),
            _preflight_env(environ, "ADMIN_PORT", "8081"),
            _preflight_env(environ, "API_PORT", "8082"),
            _preflight_env(environ, "AISERVICE_PORT", "18084")))
    endpoints = _preflight_env(
        environ, "TP_PREFLIGHT_ENDPOINTS",
        ",".join([
            "localhost:%s:actuator/health" % _preflight_env(environ, "ADMIN_PORT", "8081"),
            "localhost:%s:actuator/health" % _preflight_env(environ, "CORE_PORT", "8083"),
            "localhost:%s:actuator/health" % _preflight_env(environ, "AISERVICE_PORT", "18084"),
            "localhost:%s:" % _preflight_env(environ, "VITE_PORT", "5173"),
        ]))
    return service.split(","), endpoints.split(",")


SERVICE_PORTS, ENDPOINTS = probe_targets(os.environ)
MARK_TIMEOUT_RE = re.compile(r"pytest\.mark\.timeout\(\s*(\d+)")
JDBC_RE = re.compile(r"jdbc:postgresql://([^:/]+):?(\d*)/([\w-]+)")


def newest_source(repo_root, module):
    newest, sample = 0, ""
    module = module.strip()
    if not module or not os.path.isdir(os.path.join(repo_root, module)):
        return None, None
    probe = subprocess.run(["git", "-C", repo_root, "ls-files", "-z", module],
                           capture_output=True, text=True, timeout=60)
    for rel in filter(None, probe.stdout.split("\0")):
        if not rel.endswith((".java", ".sql", ".yml", ".yaml", ".properties")):
            continue
        try:
            stamp = os.stat(os.path.join(repo_root, rel)).st_mtime
        except Exception:
            continue
        if stamp > newest:
            newest, sample = stamp, rel
    return (newest or None), sample


def find_artifact(repo_root, module):
    base = os.path.join(repo_root, module.strip(), "target")
    if not os.path.isdir(base):
        return None, None
    best = (0, None)
    for name in os.listdir(base):
        if name.endswith(".jar"):
            stamp = os.stat(os.path.join(base, name)).st_mtime
            if stamp > best[0]:
                best = (stamp, os.path.join(base, name))
    return best


def artifact_verdict(module, origin, change_stamp, art_stamp, art_path, accept_stale=""):
    """One module's artifact-freshness verdict as a (level, module, message) row.

    A recorded downgrade, never a silent one -- the same record that relaxes the
    runtime row relaxes the jar row (measured 2026-11-18: a window consuming only
    admin+core was blocked by api/ai-service jars whose sources another in-flight
    chain keeps dirty, so rebuilding could never catch the moving mtime).
    """
    age = int(change_stamp - art_stamp)
    if age <= 0:
        return ("PASS", module, f"artifact covers the newest {origin} change "
                                f"({-age}s of margin)")
    message = (f"newest {origin} change is {age}s after "
               f"{os.path.basename(art_path or '')}")
    reason = _accept_reason(module, accept_stale)
    if reason:
        return ("WARN", module, f"{message}; downgraded by record: {reason}")
    return ("FAIL", module, f"{message}; rebuild and swap before the window")


def check_freshness(repo_root, accept_stale=None):
    """Artifact must be at least as new as the module's last real change.

    Raw file mtimes are not evidence: a checkout or rebase rewrites them without
    touching content, which previously produced a false "stale jar" blocker.
    """
    rows = []
    records = ACCEPT_STALE if accept_stale is None else accept_stale
    for module in BACKEND_MODULES:
        change_stamp, origin = module_change_stamp(repo_root, module)
        art_stamp, art_path = find_artifact(repo_root, module)
        if art_stamp is None or not change_stamp:
            rows.append(("WARN", module, "no artifact or no resolvable change; skipped"))
            continue
        rows.append(artifact_verdict(module, origin, change_stamp, art_stamp,
                                     art_path, records))
    return rows


# The repository this probe lives in; the selftest runs freshness assertions against
# real history so both branches are exercised with exact expected values.
REPO_UNDER_TEST = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
# SERVICE_PORTS / ENDPOINTS are derived once above via probe_targets() — a
# second literal assignment here shadowed the env-derived values and made slot
# chains grade the main band (removed 2026-11-08, R48 single source).
# A recorded downgrade, never a silent one: "admin=reason text" turns that module's
# runtime blocker into WARN and the reason is printed on the verdict line.
ACCEPT_STALE = os.environ.get("TP_PREFLIGHT_ACCEPT_STALE", "")


def _boot_time() -> float:
    with open("/proc/stat") as handle:
        for line in handle:
            if line.startswith("btime"):
                return float(line.split()[1])
    return 0.0


def process_start(pid: str) -> float:
    """Process start epoch (jiffies since boot resolved against /proc btime)."""
    try:
        with open(f"/proc/{pid}/stat") as handle:
            fields = handle.read().rsplit(")", 1)[1].split()
        return _boot_time() + int(fields[19]) / os.sysconf("SC_CLK_TCK")
    except Exception:
        return 0.0


def listening_pid(port: str) -> str:
    proc = subprocess.run(["ss", "-ltnp", f"sport = :{port}"],
                          capture_output=True, text=True, timeout=30)
    match = re.search(r"pid=(\d+)", proc.stdout)
    return match.group(1) if match else ""


def module_change_stamp(repo_root, module):
    """When the module's code last really changed: committed, or dirty on disk.

    A rebuilt jar's mtime is not evidence (repackaging refreshes it without any
    behaviour change), so staleness is judged against source history and the
    working tree, which is what a running process can or cannot contain.
    """
    for sub in ("src/main", "src"):
        probe = subprocess.run(["git", "-C", repo_root, "log", "-1", "--format=%ct",
                                "--", f"{module}/{sub}"],
                               capture_output=True, text=True, timeout=60)
        committed = int(probe.stdout.strip() or 0)
        dirty = 0
        status = subprocess.run(["git", "-C", repo_root, "status", "--porcelain",
                                 "--", f"{module}/{sub}"],
                                capture_output=True, text=True, timeout=60)
        for line in status.stdout.splitlines():
            path = line[-1].strip() if len(line) > 3 else ""
            # Renames print "old -> new"; the new path is the one on disk.
            path = path.split(" -> ")[-1]
            if not path:
                continue
            try:
                dirty = max(dirty, int(os.stat(os.path.join(repo_root, path)).st_mtime))
            except Exception:
                continue
        if committed or dirty:
            return max(committed, dirty), ("committed" if committed >= dirty else "dirty")
    return 0, "unknown"


def check_runtime(repo_root, accept_stale=ACCEPT_STALE, services=None, start_resolver=None):
    """The service answering the window must be running the module's newest code.

    Comparing files in the repository is not enough: a service started before the
    last change answers with old behaviour no matter what the tree says, and a
    window driven against it produces product verdicts that belong to no commit
    (measured: one 53-minute rerun voided exactly this way).
    """
    rows = []
    resolve_start = start_resolver or process_start
    for entry in (services if services is not None else SERVICE_PORTS):
        module, _, port = entry.strip().partition(":")
        if not module or not port:
            continue
        pid = listening_pid(port)
        if not pid:
            rows.append(("FAIL", f"{module}@{port}",
                         "no listener - start it through tools/ops/service_launcher.sh"))
            continue
        started = resolve_start(pid)
        changed, origin = module_change_stamp(repo_root, module)
        if not started or not changed:
            rows.append(("WARN", f"{module}@{port}",
                         "start time or change stamp unresolvable; freshness unverified"))
            continue
        drift = int(changed - started)
        if drift > 0:
            reason = _accept_reason(module, accept_stale)
            label = f"{module}@{port}"
            if reason:
                rows.append(("WARN", label,
                             f"service predates the newest {origin} change by {drift}s; "
                             f"downgraded by record: {reason}"))
            else:
                rows.append(("FAIL", label,
                             f"pid {pid} started {drift}s before the newest {origin} change "
                             f"- rebuild and swap, or record an accept-stale reason"))
        else:
            rows.append(("PASS", f"{module}@{port}",
                         f"pid {pid} covers the newest {origin} change (-{-drift}s margin)"))
    return rows


def _accept_reason(module, accept_stale):
    for item in (accept_stale or "").split(";"):
        name, _, reason = item.strip().partition("=")
        if name == module and reason:
            return reason
    return ""


def check_health(timeout_s=6):
    """Every endpoint the window drives traffic against."""
    rows = []
    for entry in ENDPOINTS:
        entry = entry.strip()
        if not entry:
            continue
        hostport, path = entry.rsplit(":", 1)
        url = f"http://{hostport}/{path.lstrip('/')}"
        started = time.time()
        try:
            with urllib.request.urlopen(url, timeout=timeout_s) as resp:
                code = resp.getcode()
            rows.append(("PASS", hostport, f"HTTP {code} in {time.time()-started:.1f}s")
                        if code < 500 else ("FAIL", hostport, f"HTTP {code}"))
        except Exception as exc:
            rows.append(("FAIL", hostport, f"{type(exc).__name__}: {str(exc)[:80]}"))
    return rows


def _load_db_target(repo_root):
    """Import scripts/db_target.py (single source for this chain's database), or None.

    Shared by the slot-consistency grade and the baseline grade so the two can never
    resolve the chain database through different lenses.
    """
    import importlib.util
    from pathlib import Path
    mod = Path(repo_root) / "scripts" / "db_target.py"
    if not mod.is_file():
        return None
    spec = importlib.util.spec_from_file_location("tp_db_target_preflight", mod)
    if spec is None or spec.loader is None:
        return None
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def _chain_database_name(repo_root, chain_cwd=None):
    """(database, None) when this chain's own database resolves; (None, why-not) when not.

    There is deliberately no fallback to the main database: a slot chain grading the
    main slot's ledger is blocked by the main chain's transient drift while its own
    drift stays invisible — the window preflight must grade the baseline of the very
    database the window will write into.
    """
    loaded = _load_db_target(repo_root)
    if loaded is None:
        return None, "scripts/db_target.py not importable"
    from pathlib import Path
    try:
        return loaded.db_spec()[2], None
    except Exception:
        pass
    try:
        loaded.bootstrap_env(cwd=str(Path(chain_cwd or repo_root).resolve()))
        return loaded.db_spec()[2], None
    except Exception as exc:
        return None, f"{type(exc).__name__}: {str(exc)[:120]}"


def check_baseline(repo_root, chain_cwd=None):
    tool = os.path.join(repo_root, "tools", "ensure_baseline.py")
    if not os.path.isfile(tool):
        return [("WARN", "db-baseline", "reconciler not present; skipped")]
    dbname, why = _chain_database_name(repo_root, chain_cwd)
    if dbname is None:
        return [("FAIL", "db-baseline", f"cannot resolve this chain's database ({why}); "
                                        f"refusing to grade another slot by default")]
    proc = subprocess.run([sys.executable, tool, "--db", dbname],
                          capture_output=True, text=True, timeout=300, cwd=repo_root)
    tail = (proc.stdout or proc.stderr).strip().splitlines()[-1:] or ["no output"]
    verdict = "PASS" if proc.returncode == 0 else "FAIL"
    return [(verdict, "db-baseline", f"db={dbname} rc={proc.returncode} check only "
                                     f"(never --repair inside a window): {tail[0][:120]}")]


def check_slot_consistency(repo_root, chain_cwd=None):
    """Every isolation key must describe the SAME slot, or the window is cross-wired.

    A chain can hold a slot env for slot 1 while its effective port still sits on the
    main band (or its DB name on another slot's database): then its browser traffic
    writes into another chain's stack and the failure shows up there as a mystery red.
    The check reads only what the window will actually use (the environment), and never
    prints values.

    The slot arithmetic itself lives in scripts/db_target.py (single source, R40): the
    window is graded by the same code the probes use, so the two can never disagree.
    """
    grade = _db_target_wiring(repo_root, chain_cwd)
    if grade is None:
        return [("WARN", "slot-consistency",
                 "scripts/db_target.py not importable; wiring check skipped")]
    findings = [("PASS" if level == "PASS" else "FAIL", "slot-consistency", message)
                for level, message in grade]
    return findings or [("WARN", "slot-consistency", "no verdict produced")]


def _load_topology():
    """Load scripts/slot_topology.py; the gate, the probes and this check share it."""
    import importlib.util
    from pathlib import Path
    mod = Path(REPO_UNDER_TEST) / "scripts" / "slot_topology.py"
    spec = importlib.util.spec_from_file_location("tp_slot_topology", mod)
    if spec is None or spec.loader is None:
        raise RuntimeError(f'topology unavailable at {mod}')
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def _topology_template():
    """Factory template database name from the topology (no second default here)."""
    try:
        return _load_topology().template_database()
    except Exception:
        return None

def _db_target_wiring(repo_root, chain_cwd=None):
    """Call the single source's wiring grader, or None when it is unavailable.

    repo_root says where the code lives; chain_cwd says which chain is running. They
    differ for a slot chain (its worktree) and the distinction is the whole point: the
    same database URL is legitimate for one chain and a cross-chain write for another.
    """
    from pathlib import Path
    loaded = _load_db_target(repo_root)
    if loaded is None:
        return None
    return loaded.wiring_findings(cwd=str(Path(chain_cwd or repo_root).resolve()))


def grade_drift(mine: set, base: set):
    """Pure comparison of a slot database against the factory template.

    Kept separate from the connection so both sides can be proven with planted sets:
    a real schema drift cannot be manufactured inside a live slot.
    """
    missing, extra = base - mine, mine - base
    if not missing and not extra:
        return "PASS", ""
    sample = ", ".join(f"{t}.{c}" for t, c in (sorted(missing) or sorted(extra))[:4])
    if not missing:
        # Ahead-only is the normal shape of an unmerged chain branch: the slot already
        # replayed this branch's migrations while the factory template still describes
        # main. Nothing is missing, so window results stay valid against the slot schema.
        # The blocker this check owns is the slot lagging the template (silently missing
        # migrations) — that case keeps failing below.
        return ("WARN", f"ahead of the template ({len(extra)} extra; e.g. {sample}) "
                        f"- own unmerged migrations replayed into the slot; re-clone once "
                        f"the branch lands on main")
    return ("FAIL", f"behind the template ({len(missing)} column(s) missing, {len(extra)} extra; e.g. {sample}) "
                    f"- re-clone the slot from the rebuilt template or apply the outstanding "
                    f"migrations before drawing conclusions from this window")


def check_slot_baseline_drift():
    """A slot created before the newest schema baseline silently misses migrations.

    The slot database is a clone of the factory template, and the template is rebuilt
    whenever db/schema-init.sql changes. That covers NEW slots; an already-running slot
    keeps the schema it was cloned with, so a chain can test against a database that no
    longer matches the code it just merged. Compare the live slot database against the
    template and name the difference instead of trusting timestamps.
    """
    url = os.environ.get("DB_URL", "")
    m = re.search(r"jdbc:postgresql://([^:/]+):?(\d*)/([\w-]+)", url)
    slot_db = m.group(3) if m else ""
    if not slot_db or slot_db == "testpike":
        return [("PASS", "slot-baseline-drift",
                  "main slot is the resident baseline; per-slot drift not applicable")]
    template = os.environ.get("TP_SLOT_TEMPLATE_DB") or _topology_template()
    try:
        import psycopg2
    except Exception:
        return [("WARN", "slot-baseline-drift", "no postgres driver; skipped")]
    if not os.environ.get("DB_PASSWORD"):
        return [("WARN", "slot-baseline-drift", "no DB handle in env; skipped")]
    try:
        conn = psycopg2.connect(host=m.group(1) or "localhost", port=m.group(2) or "5432",
                                dbname=slot_db, user=os.environ.get("DB_USERNAME", ""),
                                password=os.environ["DB_PASSWORD"], connect_timeout=10)
        with conn, conn.cursor() as cur:
            cur.execute("select table_name, column_name from information_schema.columns "
                        "where table_schema='public' order by 1,2")
            mine = set(cur.fetchall())
        conn.close()
        conn = psycopg2.connect(host=m.group(1) or "localhost", port=m.group(2) or "5432",
                                dbname=template, user=os.environ.get("DB_USERNAME", ""),
                                password=os.environ["DB_PASSWORD"], connect_timeout=10)
        with conn, conn.cursor() as cur:
            cur.execute("select table_name, column_name from information_schema.columns "
                        "where table_schema='public' order by 1,2")
            base = set(cur.fetchall())
        conn.close()
    except Exception as exc:
        return [("WARN", "slot-baseline-drift",
                 f"{type(exc).__name__}: {str(exc)[:70]}; check skipped")]
    verdict, detail = grade_drift(mine, base)
    if verdict == "PASS":
        return [("PASS", "slot-baseline-drift", f"{slot_db} matches the factory template")]
    if verdict == "WARN":
        return [("WARN", "slot-baseline-drift", f"{slot_db} is {detail}")]
    return [("FAIL", "slot-baseline-drift", f"{slot_db} is {detail}")]


def check_ai_backlog():
    """Generations queued ahead of AI-terminal specs (soft signal, env-driven).

    Queried through the driver with env-only credentials (no password in argv,
    nothing printed but the count). Degrades to WARN when the DB handle or the
    driver is unavailable: a missing soft signal never blocks a window.
    """
    match = JDBC_RE.search(os.environ.get("DB_URL", ""))
    user = os.environ.get("DB_USERNAME", "")
    password = os.environ.get("DB_PASSWORD", "")
    if not match or not user or not password:
        return [("WARN", "ai-queue", "no DB handle in env; backlog check skipped")]
    try:
        import psycopg2  # env-provided driver; absence is a skip, not a failure
    except Exception:
        return [("WARN", "ai-queue", "no postgres driver on this interpreter; skipped")]
    table = os.environ.get("TP_PREFLIGHT_AI_TASK_TABLE", "tp_ai_task")
    limit = int(os.environ.get("TP_PREFLIGHT_AI_BACKLOG", 3))
    try:
        conn = psycopg2.connect(host=match.group(1) or "localhost",
                                port=match.group(2) or "5432",
                                dbname=match.group(3) or "",
                                user=user, password=password, connect_timeout=10)
        with conn, conn.cursor() as cursor:  # type: ignore[union-attr]
            cursor.execute(f"select count(*) from {table} where status in (0, 1, 2)")
            pending = int(cursor.fetchone()[0])
        conn.close()
    except Exception as exc:
        return [("WARN", "ai-queue",
                 f"{type(exc).__name__}: {str(exc)[:80]}; check skipped")]
    level = "WARN" if pending > limit else "PASS"
    return [(level, "ai-queue", f"{pending} task(s) waiting in {table} "
                                f"(soft limit {limit}: long-wait families queue behind them)")]


def check_strays(older_than_s=600):
    """Driver processes reparented to init: evidence of a session that never closed."""
    probe = subprocess.run(["ps", "-eo", "pid=,ppid=,etimes=,cmd="],
                           capture_output=True, text=True, timeout=30)
    orphans = []
    for line in probe.stdout.splitlines():
        parts = line.split(None, 3)
        if len(parts) < 4 or parts[1] != "1":
            continue
        if "playwright/driver/node" not in parts[3]:
            continue
        if int(parts[2]) > older_than_s:
            orphans.append((parts[0], parts[2]))
    if not orphans:
        return [("PASS", "stray-drivers", "no orphaned playwright driver process")]
    return [("WARN", "stray-drivers",
             f"{len(orphans)} orphaned driver(s) older than {older_than_s}s "
             f"(pids {', '.join(p for p, _ in orphans[:6])}); they belong to unknown "
             f"owners - reclaim by pid only after confirming no other chain window runs")]


def spec_timeout(specs_root, spec, default):
    path = os.path.join(specs_root, spec)
    if not os.path.isfile(path):
        return default
    with open(path, errors="replace") as handle:
        head = "".join(handle.readlines()[:80])
    marks = MARK_TIMEOUT_RE.findall(head)
    return int(marks[0]) if marks else default


def check_budget(args, workers, cap_s, default_timeout):
    repo_root, specs_root = args.repo_root, args.specs_root
    node_list = args.node_list
    if not node_list:
        return [("PASS", "window-budget", "no node list declared (full-suite window)")]
    with open(node_list) as handle:
        nodes = [line.strip() for line in handle if line.strip()]
    if not nodes:
        return [("PASS", "window-budget", "empty list: nothing to budget")]
    total = sum(spec_timeout(specs_root, node.split("::")[0], default_timeout)
                for node in nodes)
    wall = total / max(1, workers)
    slow = sum(1 for node in nodes
               if spec_timeout(specs_root, node.split("::")[0], default_timeout) >= 300)
    level = "PASS" if wall <= cap_s else "FAIL"
    return [(level, "window-budget",
             f"{len(nodes)} node(s), worst case {total}s worker-time / {workers} workers "
             f"= ~{int(wall)}s wall (cap {cap_s}s), {slow} under a raised per-file mark")]



def runner_settle_window_s():
    """Settle window, hot-configurable; 0 = explicit opt-out for queue-free spectra."""
    return int(os.environ.get("TP_PREFLIGHT_RUNNER_SETTLE_S", "90"))


# Doors whose E2E usage enqueues rows on the executor claim queue: the survey
# door dispatches an explore session and every step submits an act task, so a
# spectrum driving one is only observable while a chain runner is serving the
# slot's api port. Single source for the pattern (R40) — window recipes ask
# this module instead of each carrying its own guess about its spectrum shape.
EXECUTOR_QUEUE_DOOR_RE = re.compile(r"web-elements/survey|explore/sessions")


def resolve_spec_source(specs_root, rel):
    """Open a declared node's spec source, accepting either path shape.

    Window recipes declare nodes repo-root-relative ("frontend/tests/e2e/x.py")
    because that is what pytest targets and the budget check were built from,
    while this tool resolves against the specs root ("<repo>/frontend"). Without
    the fallback every repo-relative declaration reads as an unreadable spec,
    and an unreadable spec is what lets a queue-driven window look queue-free.
    """
    repo_root = os.path.dirname(os.path.abspath(specs_root))
    rel = (rel or "").strip().lstrip("./")
    candidates = [
        os.path.join(specs_root, rel),
        os.path.join(repo_root, rel),
        os.path.join(specs_root, rel[len("frontend/"):]
                     if rel.startswith("frontend/") else rel),
    ]
    for path in candidates:
        try:
            with open(path, errors="replace") as handle:
                return handle.read()
        except OSError:
            continue
    return None


def queue_consuming_specs(specs_root, nodes):
    """Declared nodes whose spec source drives an executor-queue door.

    Returns `[(spec path as declared, matched door literal), ...]` in node
    order. A spec no candidate path can resolve is not a hit — the budget check
    owns the "spec file missing" row and double-reporting it here would blur the
    cause — but an unresolvable declaration never gets to read as queue-free,
    because queue_need_report counts unresolved nodes as unknown and exits non-zero.
    """
    hits, seen = [], set()
    for node in nodes or []:
        rel = (node.split("::")[0] or "").strip().lstrip("./")
        if not rel or rel in seen:
            continue
        seen.add(rel)
        source = resolve_spec_source(specs_root, rel)
        if source is None:
            continue
        found = EXECUTOR_QUEUE_DOOR_RE.search(source)
        if found:
            hits.append((rel, found.group(0)))
    return hits


def unresolved_specs(specs_root, nodes):
    """Declared nodes whose spec source could not be read at all.

    Kept separate from the hit list so the verdict stays three-state: an
    unreadable declaration is not evidence of anything and must not be able to
    earn a window its runner waiver (strict-testing P18).
    """
    missing, seen = [], set()
    for node in nodes or []:
        rel = (node.split("::")[0] or "").strip().lstrip("./")
        if not rel or rel in seen:
            continue
        seen.add(rel)
        if resolve_spec_source(specs_root, rel) is None:
            missing.append(rel)
    return missing


def queue_need_verdict(specs_root, nodes):
    """('yes'|'no'|'unknown', hits) for one declared node list."""
    if not nodes:
        return "unknown", []
    if unresolved_specs(specs_root, nodes):
        return "unknown", []
    hits = queue_consuming_specs(specs_root, nodes)
    return ("yes" if hits else "no"), hits


def grade_runner_waiver(waiver_declared, queue_hits):
    pass
    if not waiver_declared:
        return True, ""
    if queue_hits:
        return True, ("配方声明本谱不走执行器队列，但声明节点里有 "
                      + str(len(queue_hits))
                      + " 个用例驱动执行器队列门（"
                      + "、".join(f"{spec}:{door}" for spec, door in queue_hits)
                      + "），该声明作废，按严格口径判执行器在位")
    return False, ""


def _owned_orphan_runner_pids(root=None):
    """Runner processes whose working directory is inside the given checkout."""
    import os
    root = str(root or Path(__file__).resolve().parents[2])
    pids = []
    try:
        entries = os.listdir("/proc")
    except OSError:
        return pids
    for entry in entries:
        if not entry.isdigit():
            continue
        try:
            with open(f"/proc/{entry}/cmdline", "rb") as fh:
                cmd = fh.read().replace(b"\0", b" ").decode("utf-8", "ignore")
            if "runner.py" not in cmd:
                continue
            cwd = os.path.realpath(f"/proc/{entry}/cwd")
        except OSError:
            continue
        if cwd == root or cwd.startswith(root + os.sep):
            pids.append(int(entry))
    return pids


def grade_runner_unit(active_state, elapsed_s, window_s, owned_orphans=(),
                      required=True):
    """Pure judgement for one runner-unit reading -> preflight rows.

    required=False is the window-declared opt-out for spectra that never
    consume the executor claim queue (they drive their own protocol probe):
    an absent runner is then a named WARN, not a blocker. The declaration
    channel is TP_PREFLIGHT_RUNNER_REQUIRED=0, set by the window driver that
    knows its spectrum's shape; the default stays strict (R43: exemption
    branch ships with a hit case in the selftest).
    """
    if not required and active_state == "inactive" and not owned_orphans:
        return [("WARN", "runner-unit",
                 "runner 缺席但本窗已声明不走执行器队列（TP_PREFLIGHT_RUNNER_REQUIRED=0）："
                 "排队类判据不适用；若本谱后续出现执行器排队用例须撤销该声明")]
    if active_state is None:
        return [("WARN", "runner-unit", "执行器单元状态读不到（systemctl --user 不可用？），"
                                        "排队/回收类夹具可能静默失效，起窗自行判稳前先补此面")]
    if active_state == "inactive":
        if owned_orphans:
            return [("WARN", "runner-unit",
                     f"受管单元缺席，但本树有未受管 runner 进程 pid={' '.join(map(str, owned_orphans))}"
                     "：夹具按属主可探测，窗口停/恢复仍生效；建议下次起窗收编为受管单元，禁长期裸启")]
        return [("FAIL", "runner-unit",
                 "本链 runner 缺席（无受管单元也无本树进程）：窗口停/恢复执行器会静默失效，"
                 "待派发行被别的执行器秒领，先起本链受管单元再起窗")]
    if elapsed_s is None:
        return [("WARN", "runner-unit", "单元状态变更时刻读不到，无法判是否已过稳定窗")]
    if window_s <= 0:
        return [("WARN", "runner-unit", f"稳定窗检查被显式关闭（SETTLE_S=0，{elapsed_s:.0f}s since change）")]
    if elapsed_s < window_s:
        return [("FAIL", "runner-unit", f"执行器单元 {elapsed_s:.0f}s 前刚变更（<稳定窗 {window_s}s）："
                                        f"待派发/在飞态未稳，排队类谱面此刻起窗必出假红；等稳或改期")]
    return [("OK", "runner-unit", f"settled {elapsed_s:.0f}s ago (window {window_s}s)")]




def _default_runner_unit() -> str:
    """This chain's runner unit, slot-suffixed: a fixed name made it a host-wide singleton and
    one chain's stop command landed on another chain's process."""
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
    import chain_slot
    return f"tp-e2e-runner-slot{chain_slot.current_slot()}"

def check_runner_unit(window_s=None, probe=None, nodes=None, specs_root=None):
    """Seconds since the executor runner unit changed state (monotonic clock based).

    The recipe's env declaration is treated as a *waiver claim*, not as a fact:
    when the declared node list drives an executor-queue door the claim is void
    and the runner absence blocks the window again (see grade_runner_waiver).
    """
    unit = os.environ.get("TP_PREFLIGHT_RUNNER_UNIT") or f"{_default_runner_unit()}.service"
    waiver_declared = os.environ.get("TP_PREFLIGHT_RUNNER_REQUIRED", "1").strip() == "0"
    queue_hits = queue_consuming_specs(
        specs_root or str(Path(__file__).resolve().parents[2] / "frontend"),
        nodes if nodes is not None else _declared_nodes_from_env())
    required, waiver_note = grade_runner_waiver(waiver_declared, queue_hits)
    window_s = runner_settle_window_s() if window_s is None else window_s
    if probe is not None:
        active_state, change_mono = probe()
    else:
        try:
            out = subprocess.run(["systemctl", "--user", "show", unit,
                                  "-p", "ActiveState", "-p", "StateChangeTimestampMonotonic"],
                                 capture_output=True, text=True, timeout=10).stdout or ""
        except Exception as exc:
            return [("WARN", "runner-unit", f"探测失败 {type(exc).__name__}: {str(exc)[:80]}")]
        m_act = re.search(r"ActiveState=(\S+)", out)
        m_chg = re.search(r"StateChangeTimestampMonotonic=(\d+)", out)
        active_state = m_act.group(1) if m_act else None
        change_mono = int(m_chg.group(1)) if m_chg and m_chg.group(1) != "0" else None
    elapsed_s = None
    if active_state not in (None, "inactive") and change_mono is not None:
        try:
            mono_now = float(Path("/proc/uptime").read_text().split()[0]) * 1_000_000.0
            elapsed_s = max(0.0, (mono_now - change_mono) / 1_000_000.0)
        except Exception as exc:
            # never fold a broken read into "unknown" silently - it hides tool defects
            print(f"[preflight] runner-unit probe read failed: {type(exc).__name__}: {exc}")
            elapsed_s = None
    orphans = ()
    if active_state == "inactive":
        orphans = tuple(_owned_orphan_runner_pids(Path(__file__).resolve().parents[2]))
    rows = grade_runner_unit(active_state, elapsed_s, window_s, orphans,
                             required=required)
    if waiver_note:
        rows = [("WARN", "runner-waiver", waiver_note)] + list(rows)
    return rows


def _declared_nodes_from_env():
    """Node list this window declares, for callers that did not pass one.

    Window scripts export TP_E2E_NODE_LIST so a queue-driven spectrum cannot be
    handed a blanket runner waiver simply because the caller skipped the arg.
    """
    path = os.environ.get("TP_E2E_NODE_LIST", "").strip()
    if not path or not os.path.isfile(path):
        return []
    with open(path, errors="replace") as handle:
        return [line.strip() for line in handle if line.strip()]

def run(args):
    sections = [
        ("artifact-freshness", lambda: check_freshness(args.repo_root, args.accept_stale)),
        ("service-runtime", lambda: check_runtime(args.repo_root, args.accept_stale)),
        ("service-health", check_health),
        ("db-baseline", lambda: check_baseline(args.repo_root,
                                               os.environ.get("TP_CHAIN_CWD"))),
        ("slot-consistency", lambda: check_slot_consistency(args.repo_root,
                                                             os.environ.get("TP_CHAIN_CWD"))),
        ("slot-baseline-drift", check_slot_baseline_drift),
        ("ai-queue", check_ai_backlog),
        ("stray-drivers", lambda: check_strays(args.stray_age_s)),
        ("window-budget", lambda: check_budget(args, args.workers,
                                               args.eta_cap_s, args.default_timeout)),
        ("runner-unit", lambda: check_runner_unit(
            nodes=([line.strip() for line in open(args.node_list, errors="replace")
                    if line.strip()] if args.node_list else None),
            specs_root=args.specs_root)),
    ]
    report, blocked = [], []
    print(f"[preflight] {' '.join(os.uname()[0:2])} | workers={args.workers} "
          f"| eta cap={args.eta_cap_s}s")
    for name, runner in sections:
        try:
            rows = runner()
        except Exception as exc:  # a broken check must never fake a green
            rows = [("FAIL", name, f"check itself errored {type(exc).__name__}: "
                                   f"{str(exc)[:100]}")]
        for level, subject, detail in rows:
            print(f"  [{level:>4}] {subject:<18} {detail}")
            report.append({"check": name, "level": level, "subject": subject,
                           "detail": detail})
            if level == "FAIL":
                blocked.append(f"{subject}: {detail}")
    verdict = "NOGO" if blocked else "GO"
    print(f"PREFLIGHT-VERDICT={verdict}" + (f" ({len(blocked)} blocking reason(s))"
                                            if blocked else ""))
    if args.out:
        with open(args.out, "w") as handle:
            json.dump({"verdict": verdict, "checks": report}, handle,
                      ensure_ascii=False, indent=1)
    return 1 if blocked else 0


def selftest():
    """Two-sided: every blocker must fire on a planted defect and stay silent on a clean one."""
    import tempfile
    import threading
    from http.server import BaseHTTPRequestHandler, HTTPServer
    from types import SimpleNamespace

    # Port derivation both ways (R48): a slot chain's env must move the probe
    # targets, and with no env the shipped defaults must still answer — a
    # silently-empty derivation would blind every freshness/runtime check.
    slot_sp, slot_ep = probe_targets({
        "CORE_PORT": "8483", "ADMIN_PORT": "8481", "API_PORT": "8482",
        "AISERVICE_PORT": "18484", "VITE_PORT": "5573"})
    assert ("core:8483" in slot_sp and "admin:8481" in slot_sp
            and "api:8482" in slot_sp and "ai-service:18484" in slot_sp
            and len(slot_sp) == 4), slot_sp
    assert any(t.startswith("localhost:8481:") for t in slot_ep), slot_ep
    assert any(t.startswith("localhost:5573:") for t in slot_ep), slot_ep
    base_sp, base_ep = probe_targets({})
    assert ("core:8083" in base_sp and "admin:8081" in base_sp
            and "api:8082" in base_sp and "ai-service:18084" in base_sp), base_sp
    assert any(t.startswith("localhost:18084:") for t in base_ep), base_ep
    print("PROBE-TARGETS two-sided OK")

    def _Args(root, specs, nodes):
        return SimpleNamespace(repo_root=root, specs_root=specs, node_list=nodes)

    with tempfile.TemporaryDirectory() as workdir:
        repo = os.path.join(workdir, "repo")
        mod = os.path.join(repo, "core", "target")
        os.makedirs(mod)
        os.makedirs(os.path.join(repo, "src"))
        # artifact older than source -> must block
        with open(os.path.join(mod, "app.jar"), "w") as handle:
            handle.write("x")
        os.utime(os.path.join(mod, "app.jar"), (0, 1000))
        stale = find_artifact(repo, "core")
        fresh_art = (stale[0] == 1000)
        assert fresh_art, "artifact probe must read the planted jar"

        # Artifact rows honour the same record as runtime rows, both directions:
        # no record blocks, a record downgrades to WARN printing the reason.
        blocked = artifact_verdict("api", "committed", 2000, 1000,
                                   "repo/api/target/testpike-api.jar", "")
        assert blocked[0] == "FAIL", f"stale jar without record must block: {blocked}"
        relaxed = artifact_verdict("api", "committed", 2000, 1000,
                                   "repo/api/target/testpike-api.jar",
                                   "api=window never calls 8082")
        assert relaxed[0] == "WARN" and "never calls 8082" in relaxed[2], \
            f"a recorded downgrade must warn, never hide: {relaxed}"
        covered = artifact_verdict("api", "committed", 1000, 2000,
                                   "repo/api/target/testpike-api.jar", "")
        assert covered[0] == "PASS", f"fresh artifact must pass: {covered}"

        list_dir = os.path.join(repo, "frontend", "tests", "e2e")
        os.makedirs(list_dir)
        with open(os.path.join(list_dir, "slow_spec.py"), "w") as handle:
            handle.write("pytestmark = pytest.mark.timeout(1500)\n")
        specs = os.path.join(repo, "frontend")
        assert spec_timeout(specs, "tests/e2e/slow_spec.py", 180) == 1500, \
            "raised mark must be read for the budget"
        assert spec_timeout(specs, "tests/e2e/absent_spec.py", 180) == 180, \
            "missing spec falls back, never crashes"

        nodes = os.path.join(workdir, "nodes.txt")
        with open(nodes, "w") as handle:
            handle.write("tests/e2e/slow_spec.py::a\n" * 40)
        blocked_rows = check_budget(_Args(repo, os.path.join(repo, "frontend"),
                                          nodes), 4, 600, 180)
        assert blocked_rows[0][0] == "FAIL", "15000s over 4 workers must exceed a 600s cap"
        ok_rows = check_budget(_Args(repo, os.path.join(repo, "frontend"),
                                       nodes), 4, 60000, 180)
        assert ok_rows[0][0] == "PASS", "same list inside the cap must not block"

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.end_headers()

            def log_message(self, format, *a):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        port = server.server_address[1]
        threading.Thread(target=server.serve_forever, daemon=True).start()

        # Runtime freshness: the answering process must cover the newest change.
        # Fixed start times against this repo's real history make both sides exact.
        stamp, origin = module_change_stamp(REPO_UNDER_TEST, "admin")
        assert stamp > 0, f"change probe must resolve admin history, got {origin}"  # repo checkout required
        live = f"admin:{port}"
        stale = check_runtime(REPO_UNDER_TEST, services=[live],
                              start_resolver=lambda pid: stamp - 3600)
        assert stale[0][0] == "FAIL", \
            f"a process older than the newest {origin} change must block: {stale}"
        recorded = check_runtime(REPO_UNDER_TEST, accept_stale="admin=swap deferred",
                                 services=[live], start_resolver=lambda pid: stamp - 3600)
        assert recorded[0][0] == "WARN" and "swap deferred" in recorded[0][2], \
            f"a recorded downgrade must warn, never hide: {recorded}"
        current = check_runtime(REPO_UNDER_TEST, services=[live],
                                start_resolver=lambda pid: stamp + 60)
        assert current[0][0] == "PASS", f"a service newer than every change must pass: {current}"
        dead = check_runtime(REPO_UNDER_TEST, services=[f"admin:{port + 1}"],
                             start_resolver=lambda pid: stamp + 60)
        assert dead[0][0] == "FAIL", "a missing listener must block the window"

        global ENDPOINTS
        keep = ENDPOINTS
        ENDPOINTS = [f"127.0.0.1:{port}:actuator/health", "127.0.0.1:1:actuator/health"]
        health = check_health(timeout_s=3)
        ENDPOINTS = keep
        server.shutdown()
        levels = {row[1]: row[0] for row in health}
        assert levels[f"127.0.0.1:{port}"] == "PASS", "live endpoint must pass"
        assert levels["127.0.0.1:1"] == "FAIL", "dead endpoint must block"

        assert check_strays(older_than_s=10 ** 9)[0][0] == "PASS", \
            "no driver older than 31 years"

        # Slot wiring: the same database URL is legitimate for one chain and a
        # cross-chain write for another, so the fixture plants a worktree context.
        import tempfile
        keep_env = dict(os.environ)
        try:
            with tempfile.TemporaryDirectory() as sandbox:
                wt = os.path.join(sandbox, "testPike-wt-selftest")
                os.mkdir(wt)
                os.environ["TP_SLOT_ENV_DIR"] = sandbox
                topo = _load_topology()
                # The main-chain fixtures must present a directory that resolves to the
                # main chain's identity: the chain token comes from the directory name,
                # so running this selftest from a worktree repo would otherwise grade
                # the "main on slot 0" scenarios against the worktree's own slot chain
                # and the fixture would only pass in the main checkout (not hermetic).
                main_like = os.path.join(sandbox, topo.repo_name())
                os.mkdir(main_like)
                slot2_db = topo.slot_database(2)
                admin_2 = str(topo.slot_port(topo.shipped_base("ADMIN_PORT"), 2))
                core_2 = str(topo.slot_port(topo.shipped_base("CORE_PORT"), 2))
                os.environ.update({"DB_URL": f"jdbc:postgresql://localhost:5432/{slot2_db}",
                                   "ADMIN_PORT": admin_2, "CORE_PORT": core_2})
                agree = check_slot_consistency(REPO_UNDER_TEST, wt)
                assert agree[0][0] == "PASS" and "slot 2" in agree[0][2], agree

                os.environ["CORE_PORT"] = "8083"          # main band port, slot2 database
                mixed = check_slot_consistency(REPO_UNDER_TEST, wt)
                assert mixed[0][0] == "FAIL", f"a mixed slot wiring must block: {mixed}"
                assert "CORE_PORT=8083 is slot 0" in mixed[0][2], mixed[0][2]

                os.environ.update({"DB_URL": f"jdbc:postgresql://localhost:5432/{topo.main_database()}",
                                   "ADMIN_PORT": str(topo.shipped_base("ADMIN_PORT")),
                                   "CORE_PORT": str(topo.shipped_base("CORE_PORT"))})
                landed = check_slot_consistency(REPO_UNDER_TEST, wt)
                assert landed[0][0] == "FAIL" and "MAIN database" in landed[0][2], \
                    f"a worktree landing on the main slot must block: {landed}"

                main_ok = check_slot_consistency(REPO_UNDER_TEST, main_like)
                assert main_ok[0][0] == "PASS", f"the main chain on slot 0 must pass: {main_ok}"

                os.environ["DB_URL"] = f"jdbc:postgresql://localhost:5432/{topo.slot_database(1)}"
                inherited = check_slot_consistency(REPO_UNDER_TEST, main_like)
                assert inherited[0][0] == "FAIL" and "main chain must run on slot 0" in inherited[0][2], \
                    f"an inherited slot env on the main chain must block: {inherited}"
                os.environ["DB_URL"] = f"jdbc:postgresql://localhost:5432/{topo.main_database()}"

                # Baseline grading must follow the chain's own slot. A stub reconciler
                # records which database it was asked to grade, so the slot-OK against
                # main-drift pair is provable without touching docker.
                import shutil
                base_repo = os.path.join(sandbox, "baserepo")
                os.makedirs(os.path.join(base_repo, "scripts"))
                os.makedirs(os.path.join(base_repo, "tools"))
                for name in ("db_target.py", "slot_topology.py"):
                    shutil.copy(os.path.join(REPO_UNDER_TEST, "scripts", name),
                                os.path.join(base_repo, "scripts", name))
                stub_log = os.path.join(sandbox, "graded.log")
                open(stub_log, "a").close()
                stub_src = (
                    "import argparse, os, sys\n"
                    "ap = argparse.ArgumentParser()\n"
                    "ap.add_argument('--db', default='testpike')\n"
                    "a = ap.parse_args()\n"
                    "open(os.environ['TP_BASE_STUB_LOG'], 'a').write(a.db + chr(10))\n"
                    "red = [d for d in os.environ.get('TP_BASE_STUB_RED', '').split(',') if d]\n"
                    "print('BASELINE_FAIL' if a.db in red else 'BASELINE_OK')\n"
                    "sys.exit(1 if a.db in red else 0)\n")
                with open(os.path.join(base_repo, "tools", "ensure_baseline.py"), "w") as handle:
                    handle.write(stub_src)
                os.environ["TP_BASE_STUB_LOG"] = stub_log
                os.environ["TP_BASE_STUB_RED"] = topo.main_database()
                os.environ["DB_URL"] = f"jdbc:postgresql://localhost:5432/{slot2_db}"
                slot_row = check_baseline(base_repo, wt)
                assert slot_row[0][0] == "PASS" and f"db={slot2_db}" in slot_row[0][2], \
                    f"a slot chain must grade its own database despite main drift: {slot_row}"
                os.environ["DB_URL"] = f"jdbc:postgresql://localhost:5432/{topo.main_database()}"
                main_row = check_baseline(base_repo, main_like)
                assert main_row[0][0] == "FAIL", \
                    f"main-slot drift must still block the main chain: {main_row}"
                graded = open(stub_log).read().split()
                assert graded == [slot2_db, topo.main_database()], \
                    f"the reconciler must run on the resolved chain database: {graded}"

            # Drift grading is a pure set comparison, so both sides are provable:
            # a slot missing a migration column blocks (silent schema lag is the
            # owned risk), an identical schema passes, and an ahead-only slot
            # (unmerged chain migrations replayed into its own database) is a
            # named warning, not a blocker — blocking it would NOGO every parallel
            # chain window on its own work-in-progress schema.
            ok, why = grade_drift({("t", "a")}, {("t", "a")})
            assert (ok, why) == ("PASS", ""), f"an identical schema must pass: {(ok, why)}"
            behind, detail = grade_drift({("t", "a")}, {("t", "a"), ("t", "b")})
            assert behind == "FAIL" and "behind the template" in detail and "t.b" in detail, detail
            ahead, detail2 = grade_drift({("t", "a"), ("t", "z")}, {("t", "a")})
            assert ahead == "WARN" and "ahead of the template" in detail2 and "t.z" in detail2, detail2
        finally:
            os.environ.clear()
            os.environ.update(keep_env)
        rows = grade_runner_unit("active", 409020.0 - 409010.0, 90)
        assert rows and rows[0][0] == "FAIL" and "刚变更" in rows[0][2], \
            f"a runner mid-transition must block the window: {rows}"
        assert grade_runner_unit("active", 600.0, 90)[0][0] == "OK", "settled runner passes"
        assert grade_runner_unit("inactive", None, 90)[0][0] == "FAIL", "absent runner blocks"
        assert grade_runner_unit("inactive", None, 90, required=False)[0][0] == "WARN", \
            "window-declared queue-free opt-out warns instead of blocking"
        assert grade_runner_unit("active", 10.0, 90, required=False)[0][0] == "FAIL", \
            "the opt-out only covers absence, a mid-transition unit still blocks"
        assert grade_runner_unit("inactive", None, 90, owned_orphans=(4242,))[0][0] == "WARN", \
            "an owned unmanaged runner is a warning, not a false 'missing' alarm"
        assert grade_runner_unit(None, None, 90)[0][0] == "WARN", "unreadable state warns"
        assert grade_runner_unit("active", 10.0, 0)[0][0] == "WARN", "opt-out must say so"
        # Waiver integrity (observed 2026-11-20): a board recipe declared itself
        # queue-free while its node list also ran the survey spectrum, whose acts
        # are claimed off the executor queue. With no chain runner the acts sat
        # — a silent false red that cost whole windows. A declared waiver is only
        # honored when NO declared node consumes the queue.
        with tempfile.TemporaryDirectory() as sandbox:
            specs = os.path.join(sandbox, "frontend", "tests", "e2e")
            os.makedirs(specs)
            Path(os.path.join(specs, "test_survey_like.py")).write_text(
                "page.route(\"**/api/v1/web-elements/survey**\", fake)\n",
                encoding="utf-8")
            Path(os.path.join(specs, "test_board_like.py")).write_text(
                "page.goto(\"/ledger#board\")\n", encoding="utf-8")
            hits = queue_consuming_specs(os.path.join(sandbox, "frontend"),
                                         ["tests/e2e/test_survey_like.py::test_a",
                                          "tests/e2e/test_board_like.py::test_b"])
            assert [h[0] for h in hits] == ["tests/e2e/test_survey_like.py"], \
                f"only the queue-driven spec is named: {hits}"
            assert queue_consuming_specs(os.path.join(sandbox, "frontend"),
                                         ["tests/e2e/test_board_like.py::test_b"]) == [], \
                "a board-only spec must not be counted as queue-driven"
            assert queue_consuming_specs(os.path.join(sandbox, "frontend"),
                                         ["tests/e2e/test_absent.py::test_x"]) == [], \
                "a spec file that cannot be read is not a hit (budget check owns that row)"
            required, note = grade_runner_waiver(waiver_declared=True, queue_hits=hits)
            assert required and "作废" in note, \
                f"a waiver over a queue-driven node list must be void: {(required, note)}"
            required, note = grade_runner_waiver(waiver_declared=True, queue_hits=[])
            assert not required and note == "", "an honest queue-free waiver still applies"
            required, note = grade_runner_waiver(waiver_declared=False, queue_hits=hits)
            assert required and note == "", "strict declaration stays strict"
            blocked = grade_runner_unit("inactive", None, 90, required=required)
            assert blocked[0][0] == "FAIL", "the void waiver must land on the blocking row"
        live = check_runner_unit()
        assert live and live[0][0] in ("OK", "WARN", "FAIL"), f"live probe returns a row: {live}"
    print("[selftest] two-sided PASS (budget blocks on over-cap and clears inside cap; "
          "health passes a live endpoint and blocks a dead one; spec probe reads the "
          "raised mark and falls back on a missing spec; stray check silent on clean; "
          "worktree/main-slot/mixed-band wirings block and honest ones pass; baseline "
          "grades the chain's own database despite main drift and still blocks it on "
          "the main chain; drift grading blocks behind, warns ahead-only, passes identical)")
    return 0


def queue_need_report(node_list, specs_root):
    """Printable answer for window recipes: does this node list need a runner?

    Recipes must not re-implement the door pattern (single source), so they ask
    this tool and branch on the printed verdict. Three states, never two: no
    node list, an unreadable one, an empty one, or a declaration whose spec file
    cannot be found is NOT evidence of being queue-free — each reports unknown
    and exits non-zero, because a detector that folded its own failure into "no"
    would hand the recipe a license to waive the runner check (strict-testing P18).
    """
    if not node_list:
        print("PREFLIGHT-QUEUE-NEED=unknown")
        print("  [queue] no node list declared: the window runs whole spec files, "
              "so the spectrum shape is not provable queue-free")
        return 1
    try:
        with open(node_list, errors="replace") as handle:
            nodes = [line.strip() for line in handle if line.strip()]
    except OSError as exc:
        print("PREFLIGHT-QUEUE-NEED=unknown")
        print(f"  [queue] node list unreadable: {type(exc).__name__}: {str(exc)[:120]}")
        return 1
    verdict, hits = queue_need_verdict(specs_root, nodes)
    print("PREFLIGHT-QUEUE-NEED=" + verdict)
    if verdict == "unknown":
        print("  [queue] declaration unreadable: "
              + ", ".join(unresolved_specs(specs_root, nodes) or ["empty node list"]))
        return 1
    for spec, door in hits:
        print(f"  [queue] {spec} drives {door}")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Preflight a detached verification window (fail in seconds).")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--specs-root", default=None,
                        help="E2E spec root (default <repo-root>/frontend)")
    parser.add_argument("--node-list")
    parser.add_argument("--workers", type=int,
                        default=int(os.environ.get("TP_E2E_WORKERS", 4)))
    parser.add_argument("--eta-cap-s", type=int,
                        default=int(os.environ.get("TP_E2E_ETA_CAP_S", 3600)))
    parser.add_argument("--default-timeout", type=int,
                        default=int(os.environ.get("TP_E2E_TIMEOUT_S", 180)))
    parser.add_argument("--accept-stale", default=ACCEPT_STALE,
                        help='recorded downgrade, e.g. "admin=swap deferred to quiet window"')
    parser.add_argument("--stray-age-s", type=int,
                        default=int(os.environ.get("TP_PREFLIGHT_STRAY_AGE_S", 600)))
    parser.add_argument("--out")
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--queue-need", action="store_true",
                        help="print PREFLIGHT-QUEUE-NEED=yes|no for --node-list and exit "
                             "(window recipes use it instead of guessing their spectrum shape)")
    args = parser.parse_args(argv)
    if args.specs_root is None:
        args.specs_root = os.path.join(args.repo_root, "frontend")
    if args.selftest:
        return selftest()
    if args.queue_need:
        return queue_need_report(args.node_list, args.specs_root)
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
