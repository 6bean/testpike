
import json
import os
import re
import signal
import socket
import subprocess
import time
import uuid
from datetime import datetime

import httpx
import pytest
from playwright.sync_api import expect, sync_playwright

from issues_monitor import assert_no_browser_issues, create_issues_monitor, selftest_issue_monitoring

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(_HERE)))


def _load_scripts_module(name: str):
    import importlib.util
    path = os.path.join(_REPO_ROOT, "scripts", name + ".py")
    spec = importlib.util.spec_from_file_location("tp_" + name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"单源件缺失: {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_topology = _load_scripts_module("slot_topology")
_db_target = _load_scripts_module("db_target")
_db_target.bootstrap_env(cwd=_REPO_ROOT)


def _site_url(env_key: str, port_key: str, suffix: str = "") -> str:
    pass
    value = os.environ.get(env_key, "").strip()
    if value:
        return value
    base = _topology.base_port(port_key, _topology.shipped_base(port_key))
    slot = _topology.slot_of_database(_db_target.db_spec()[2])
    port = _topology.slot_port(base, slot)
    return f"http://127.0.0.1:{port}{suffix}"


BASE_URL = _site_url("TP_E2E_BASE_URL", "VITE_PORT")
API_BASE = _site_url("TP_E2E_API_BASE", "ADMIN_PORT", "/api/v1")
API_ORIGIN = API_BASE[: -len("/api/v1")] if API_BASE.endswith("/api/v1") else API_BASE
ADMIN_USERNAME = os.environ.get("TP_ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.environ.get("ADMIN_INIT_PASSWORD", "Admin@123")

ARTIFACT_DIR = os.path.join(os.path.dirname(__file__), "artifacts", "testpike-frontend")
os.makedirs(ARTIFACT_DIR, exist_ok=True)

SAFE_STATIC = ["favicon", ".woff", ".ttf", ".eot", "apple-touch-icon", "site.webmanifest"]


# ---------- Verification-window budget controls (hot-configurable) ----------
# Why this exists: per-file marks raise the per-test timeout to 300/480/900/1500
# seconds, so a single red test in a slow family occupies a worker for up to 25
# minutes and a triage rerun of a few dozen reds reads as a multi-hour window.
# A window now declares its own budget through the environment instead of editing
# pytest.ini or the marks mid-flight (thresholds stay tunable, never hardcoded):
#   TP_E2E_TIMEOUT_S             default per-test timeout (unset = pytest.ini value)
#   TP_E2E_TIMEOUT_METHOD        thread | signal (unset = pytest.ini value)
#   TP_E2E_TIMEOUT_CAP_S         ceiling applied to raised per-file marks
#                                (unset = marks honored as written)
#   TP_E2E_DEFAULT_TIMEOUT_MS    Playwright action/navigation default (unset = 15000)
#   TP_E2E_AI_TERMINAL_TIMEOUT_S shared AI-terminal waiter deadline (unset = 1200)
# The cap is opt-in per window on purpose: lowering a wait below real provider
# latency would turn slow-green into false-red, so fast triage windows use the cap
# and the final verification round runs without it.
def _env_int(name: str):
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return None
    try:
        return int(raw)
    except ValueError:
        print(f"[WARN] {name}={raw!r} is not an integer; ignored")
        return None


def pytest_configure(config):
    """Apply environment-declared timeout budget before any test runs."""
    cap = _env_int("TP_E2E_TIMEOUT_S")
    if cap:
        config.option.timeout = cap
    method = os.environ.get("TP_E2E_TIMEOUT_METHOD")
    if method in ("signal", "thread"):
        config.option.timeout_method = method


def pytest_collection_modifyitems(items):
    """Clamp raised per-file timeouts when the window declares a ceiling."""
    ceiling = _env_int("TP_E2E_TIMEOUT_CAP_S")
    if not ceiling:
        return
    clamped = 0
    for item in items:
        marker = item.get_closest_marker("timeout")
        declared = marker.args[0] if marker and marker.args else None
        if isinstance(declared, (int, float)) and declared > ceiling:
            item.add_marker(pytest.mark.timeout(ceiling))
            clamped += 1
    if clamped:
        print(f"[e2e-budget] clamped {clamped} raised per-test timeout(s) to "
              f"{ceiling}s for this window")


# ---------- Teardown guard (single source, reused by the session reclaim) ----------
# A timeout breaks the Playwright driver connection mid-call, and the pending
# close() then spins in the sync greenlet loop instead of returning: a session
# that should have ended in seconds keeps burning a whole core with no progress
# line (measured: 250s of CPU after two 10s timeouts). Every teardown action
# therefore runs against a deadline, and on expiry only THIS session's driver
# subtree is reclaimed - a machine-wide pkill has killed another chain's live
# browser before (cross-chain E2E wipeout), so reclaim stays process-tree scoped.
def _descendant_pids(root: int) -> set:
    try:
        out = subprocess.run(["ps", "--ppid", str(root), "-o", "pid="],
                             capture_output=True, text=True, timeout=5).stdout
        kids = {int(p) for p in out.split()}
    except Exception:
        return set()
    acc: set = set()
    for kid in kids:
        acc.add(kid)
        acc |= _descendant_pids(kid)
    return acc


def _reclaim_drivers(root: int, sig: int = 15) -> int:
    """Signal the playwright driver processes inside one session's own tree."""
    hit = 0
    for pid in _descendant_pids(root):
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as handle:
                cmd = handle.read().decode("utf-8", "replace")
        except Exception:
            continue
        if "playwright/driver/node" in cmd:
            try:
                os.kill(pid, sig)
                hit += 1
            except Exception:
                pass
    return hit


def _bounded_close(action, label: str, budget_s: int | None = None,
                   reclaim_on_abort: bool = False) -> bool:
    """Run a teardown action under a deadline; True means it completed cleanly.

    Uses the same real-timer interruption that makes the timeout plugin work for
    the test phase: the handler raises inside the blocked call, so the wait ends
    in the thread that owns the Playwright greenlet. (Calling close() from a
    helper thread instead is rejected by Playwright itself with "Cannot switch to
    a different thread", which leaves the driver process behind.)

    reclaim_on_abort is for the session-scoped actions only: once browser.close or
    playwright.stop had to be aborted the link is unusable anyway, so the session's
    own driver subtree is reclaimed instead of lingering to the next window.
    """
    budget = budget_s or (_env_int("TP_E2E_TEARDOWN_BUDGET_S") or 10)

    class _TeardownDeadline(Exception):
        pass

    def _fire(signum, frame):
        raise _TeardownDeadline(f"{label} exceeded {budget}s")

    previous = signal.getsignal(signal.SIGALRM)
    signal.signal(signal.SIGALRM, _fire)
    prior_delay, _ = signal.setitimer(signal.ITIMER_REAL, budget)
    try:
        action()
        return True
    except Exception as exc:
        aborted = isinstance(exc, _TeardownDeadline)
        hit = _reclaim_drivers(os.getpid(), sig=9) if (aborted and reclaim_on_abort) else 0
        print(f"[WARN] {label} raised {type(exc).__name__}: {str(exc)[:120]}"
              + (f"; reclaimed {hit} driver process(es) of this session only" if hit else ""))
        return False
    finally:
        # Hand the alarm back exactly as found: these guards sit on session-scoped
        # finalizers (outside any test item, so the timeout plugin never armed a
        # deadline there), but a caller inside an item phase must keep its own.
        signal.setitimer(signal.ITIMER_REAL,
                         prior_delay if prior_delay and prior_delay > 0 else 0)
        signal.signal(signal.SIGALRM, previous)


def _dump_artifacts(page, item, longrepr, collectors):
    pass
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    # Parametrize ids carry "/" and other path characters (e.g. "[/#/cluster/upgrade]");
    # using them raw turns the artifact name into a nonexistent nested directory and the
    # failure dump raises FileNotFoundError, aborting the whole session (efgreeng 2026-11-04).
    safe_name = re.sub(r"[^\w.\[\]-]", "_", item.name)
    name = f"{safe_name}-{ts}"
    png_path = os.path.join(ARTIFACT_DIR, f"{name}.png")
    txt_path = os.path.join(ARTIFACT_DIR, f"{name}.txt")
    try:
        page.screenshot(path=png_path, full_page=True)
    except Exception as e:  # noqa: BLE001
        png_path = f"截图失败: {e}"

    lines = [
        f"测试: {item.name}",
        f"时间: {ts}",
        f"失败信息: {longrepr}",
        "",
        "== 页面 URL ==",
        page.url,
        "",
        "== 入参（该测试发出的全部业务请求）==",
    ]
    reqs = collectors.get("requests", [])
    for r in reqs:
        lines.append(f"  {r['method']} {r['url']} body={r.get('body') or ''}")
    lines.append("")
    lines.append("== 服务器返回（4xx/5xx 及相关响应）==")
    for resp in collectors.get("responses", []):
        if resp["status"] >= 400 or resp["status"] < 200:
            lines.append(f"  {resp['status']} {resp['url']} body={resp.get('body') or ''}")
    lines.append("")
    lines.append("== 请求失败归因（requestfailed）==")
    failed_reqs = collectors.get("network_failed", [])
    for f in failed_reqs[:30]:
        lines.append(f"  {f}")
    if len(failed_reqs) > 30:
        lines.append(f"  ...共 {len(failed_reqs)} 条，仅列前 30")
    lines.append("")
    lines.append("== Console ==")
    for c in collectors.get("console_errors", []):
        lines.append(f"  [error] {c}")
    for w in collectors.get("console_warnings", []):
        lines.append(f"  [warn] {w}")
    lines.append("")
    lines.append("== 页面文本快照 ==")
    try:
        lines.append(page.locator("body").inner_text(timeout=3000)[:4000])
    except Exception as e:  # noqa: BLE001
        lines.append(f"文本快照失败: {e}")

    with open(txt_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return png_path, txt_path


@pytest.hookimpl(tryfirst=True, hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    rep = outcome.get_result()
    if rep.when == "call" and rep.failed:
        page = item.funcargs.get("page")
        collectors = item.funcargs.get("collectors")
        if page is not None and collectors is not None:
            png, txt = _dump_artifacts(page, item, rep.longreprtext, collectors)
            rep.sections.append(("E2E 失败现场", f"截图: {png}\n说明: {txt}"))


def attach_monitors(page):
    pass
    collectors = {
        "console_errors": [],
        "console_warnings": [],
        "network_4xx": [],
        "network_errors": [],
        "network_failed": [],
        "requests": [],
        "responses": [],
        "browser_issues": [],
    }

    # Iron rule 5c: CDP Audits.issueAdded capture (zero tolerance, no whitelist).
    collectors["browser_issues"] = create_issues_monitor(page)

    def on_console(msg):
        if msg.type == "error":
            collectors["console_errors"].append(msg.text)
        elif msg.type == "warning":
            collectors["console_warnings"].append(msg.text)

    def on_request(req):
        if req.resource_type in ("fetch", "xhr"):
            body = None
            try:
                post = req.post_data
                if post:
                    body = post[:500]
            except Exception:  # noqa: BLE001
                pass
            collectors["requests"].append({"method": req.method, "url": req.url, "body": body})

    def on_response(resp):
        status = resp.status
        url = resp.url
        if "/api/" in url:
            body = None
            try:
                body = resp.text()[:500]
            except Exception:  # noqa: BLE001
                pass
            collectors["responses"].append({"status": status, "url": url, "body": body})
            if status >= 500:
                collectors["network_errors"].append({"url": url, "status": status})
            elif status >= 400:
                collectors["network_4xx"].append({"url": url, "status": status})

    page.on("console", on_console)
    page.on("request", on_request)
    page.on("response", on_response)

    # Attribution capture (r13 window 2026-11-21: ERR_NETWORK_CHANGED console
    # errors carried no URL, so the environment family could not be pinned to
    # the endpoint that died). Deliberately NOT an independent failure source:
    # SPA navigation cancels in-flight requests (net::ERR_ABORTED) routinely,
    # and promoting that to red would be a new false-failure class, not a
    # tightening. It only enriches the message when console errors already red
    # the test (see assert_no_errors).
    def on_request_failed(req):
        collectors["network_failed"].append(f"{req.method} {req.url} :: {req.failure}")

    page.on("requestfailed", on_request_failed)
    return collectors


def assert_no_errors(collectors, test_name=""):
    pass
    errors = []

    if collectors["console_errors"]:
        errors.append(f"Console errors: {collectors['console_errors'][:5]}")

    real_warnings = [
        w for w in collectors.get("console_warnings", [])
        if ("Vue warn" in w and any(k in w for k in (
            "not defined", "not a function", "failed to resolve", "Invalid prop", "was accessed during render"
        ))) or "[intlify] Not found" in w
    ]
    if real_warnings:
        errors.append(f"Vue warnings: {real_warnings[:5]}")

    if collectors["network_errors"]:
        errors.append(f"Network 5xx: {collectors['network_errors'][:5]}")

    real_4xx = []
    for e in collectors["network_4xx"]:
        url = e["url"]
        if e["status"] == 404:
            if any(s in url for s in SAFE_STATIC):
                continue
        real_4xx.append(e)
    if real_4xx:
        errors.append(f"Network 4xx (real errors): {real_4xx[:5]}")

    # Iron rule 5c: any Issues-panel entry fails (zero tolerance, no whitelist).
    # Reuse the shared assert (single source of the reporting format).
    try:
        assert_no_browser_issues(collectors.get("browser_issues", []), test_name)
    except AssertionError as e:
        errors.append(str(e))

    # Enrich an already-red verdict with requestfailed attribution. The capture
    # landed in 9a772e1e8 (2026-11-21 02:44) but no consumer ever read it, so
    # the recurring ERR_NETWORK_CHANGED family could not be pinned to the
    # endpoint that died (R43 drilldown: a collector with zero consumers spins
    # silently). Still NOT an independent failure source -- SPA navigation
    # cancels in-flight requests routinely; this only names them when console
    # errors already fail the test.
    if errors and collectors.get("network_failed"):
        errors.append(
            f"Request failures (attribution): {collectors['network_failed'][:8]}"
        )

    assert not errors, f"Errors in {test_name}: {'; '.join(errors)}"


def whitelist_expected_reject(collectors, url_suffix, status=409):
    """Single-source exemption for one intentionally triggered business reject.

    Spectra that exercise the optimistic-lock flow expect HTTP 409 (business
    code 40901), and the duplicate-gate spectrum expects one HTTP 400 (code
    40001) refusal POST; Chromium also logs a generic "Failed to load
    resource" console line for any 4xx resource load. Only the named
    endpoint's entries of the given status and the matching console noise are
    dropped — every other 4xx/5xx/console entry still fails the
    zero-tolerance check (narrow per-endpoint pattern per the
    test_e10_review_lock / test_f4_api_spec precedent, generalized so a
    third consumer does not copy the block — R40 single source).
    Returns the dropped count; callers assert == expected occurrences so a
    missing conflict can never turn into a silent pass.
    """
    before = len(collectors["network_4xx"])
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not (e["status"] == status and e["url"].endswith(url_suffix))
    ]
    dropped = before - len(collectors["network_4xx"])
    collectors["console_errors"] = [
        c for c in collectors["console_errors"] if f"status of {status}" not in c
    ]
    return dropped


MAIN_SLOT_DB = _db_target.MAIN_DB
require_db_url = _db_target.require_db_url
db_spec = _db_target.db_spec
db_connect = _db_target.db_connect
slot_of_target = lambda: _db_target.slot_of(db_spec()[2])

def require_env(key: str) -> str:
    """Read one configuration value or name the key that is missing (values never printed)."""
    value = os.environ.get(key, "").strip()
    if not value:
        raise RuntimeError(
            f"配置缺失 {key}：该键由仓库 .env 提供（凭据与装机值禁内联进代码或测试），"
            f"装配命令 python3 scripts/db_target.py bootstrap")
    return value


_S3_KEYS = ("S3_ENDPOINT", "S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_BUCKET")


def s3_config() -> dict:
    pass
    missing = [k for k in _S3_KEYS if not os.environ.get(k)]
    if missing:
        raise RuntimeError(
            f"对象存储配置缺失 {missing}：这些键由仓库 .env 提供（值勿内联到代码），"
            f"跑测试前装配本链配置：python3 scripts/db_target.py bootstrap")
    return {"endpoint": os.environ["S3_ENDPOINT"], "bucket": os.environ["S3_BUCKET"],
            "access_key": os.environ["S3_ACCESS_KEY"],
            "secret_key": os.environ["S3_SECRET_KEY"]}


def s3_client():
    """The one S3-protocol client builder for E2E (consumers must not rebuild it - R40).

    Talks to the SeaweedFS S3 gateway (or any cloud endpoint) with AWS Signature V4.
    The explicit signature_version pin is load-bearing: SeaweedFS canonicalizes the
    Content-Type header for SigV2 presigns, so a V2 fallback breaks real browser/
    executor uploads with 403 (measured 2026-11-16 in the switch design record).
    """
    import boto3
    from botocore.config import Config
    cfg = s3_config()
    return boto3.client("s3", endpoint_url=cfg["endpoint"],
                        aws_access_key_id=cfg["access_key"],
                        aws_secret_access_key=cfg["secret_key"],
                        region_name="us-east-1", verify=False,
                        config=Config(signature_version="s3v4"))


def s3_bucket() -> str:
    return s3_config()["bucket"]


def middleware_container(kind: str) -> str:
    """Container names come from the topology, never from a literal in a spec."""
    return _topology.container(kind)


# ---------- mock-server plane process control (single source, R40) ----------
# Consumers: test_t362c (recording journey) and test_t362d (inspector promote).
# Why the old two-pattern kill went stale: the host dev-stack also runs
# mock-server as a fat jar ("java -jar mock-server/target/<name>.jar", the
# 11-03 jar hosting form). That command line contains neither the main class
# nor the mvn launcher markers, so a resident jar instance survived the kill,
# squatted 18085/18086 and pinned its own space key — the exact root cause of
# the t362c red3b failure. The pattern list below must cover every hosting
# form; add a pattern here, never in a test file again.
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.dirname(os.path.abspath(__file__)))))


def core_base_url() -> str:
    """Core service base URL for rule loading (chain-slot overlay or shipped default)."""
    return os.environ.get("CORE_BASE_URL", "http://localhost:8083")


def mock_mgmt_port() -> int:
    """Mock-server management port (chain-slot env key: MOCK_SERVER_MANAGEMENT_PORT).

    Reading a divergent key name (MOCK_MGMT_PORT) silently fell back to the
    main-slot port and collided with the resident plane — keep key names in
    lockstep with scripts/chain_slot.py output.
    """
    return int(os.environ.get("MOCK_SERVER_MANAGEMENT_PORT", "18085"))


def mock_traffic_port() -> int:
    """Mock-server traffic port (chain-slot env key: MOCK_SERVER_TRAFFIC_PORT)."""
    return int(os.environ.get("MOCK_SERVER_TRAFFIC_PORT", "18086"))


def rabbit_virtual_host() -> str:
    """RabbitMQ vhost the mock plane publishes to (chain-slot env key: MQ_VHOST).

    mock-server's own application.yml does not bind spring.rabbitmq.virtual-host
    to MQ_VHOST (core does), so a test-spawned plane would silently publish
    recording frames and hit rows to the main-slot vhost — the slot core then
    reads back zero rows. Pass the resolved vhost explicitly until the product
    yml carries the key.
    """
    return os.environ.get("MQ_VHOST", "/")


def slot_port_offset() -> int:
    """Port offset of the current chain slot (chain-slot env key: SERVER_PORT_OFFSET).

    scripts/chain_slot.py emits slot * 100 per slot; a plain checkout has no
    overlay and stays on offset 0 = main-slot ports unchanged.
    """
    return int(os.environ.get("SERVER_PORT_OFFSET", "0"))


def upstream_port(base=18899) -> int:
    """Loopback port for a test-spawned upstream echo server, slot-aware.

    A fixed literal port made two chains running the same spec collide with
    OSError Errno 98; deriving from the slot offset keeps each chain on its
    own band while staying readable in recorded target URLs.
    """
    return base + slot_port_offset()


def _port_open(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def _retire_resident_plane_unit(port: int) -> None:
    """Stop the chain-slot resident mock plane only when the unit is OUR OWN.

    A jar-launched systemd transient unit carries no main-class token on its
    command line, so the pkill patterns below never match it: it survived and
    squatted the management port (red11 evidence — "Port 18185 was already in
    use", then the test polled the resident plane's unpinned load state
    forever). Stopping goes through the owner channel and only when the unit's
    main process runs inside this checkout (unit_is_ours, R47) — another
    chain's plane is never touched.
    """
    unit = f"tp-svc-mock-server-{port}.service"
    try:
        info = subprocess.run(
            ["systemctl", "--user", "show", unit,
             "-p", "MainPID", "-p", "Description", "--value"],
            capture_output=True, text=True, timeout=20, check=False).stdout
        lines = info.splitlines()
        pid = lines[0].strip() if lines else ""
        desc = " ".join(lines[1:])
        if not pid.isdigit() or int(pid) == 0:
            return
        if not unit_is_ours(unit, pid) and REPO_ROOT not in desc:
            return
        subprocess.run(["systemctl", "--user", "stop", unit],
                       capture_output=True, timeout=90, check=False)
        deadline = time.time() + 30
        while time.time() < deadline and _port_open(port):
            time.sleep(1)
    except Exception as e:
        print(f"cleanup-warn resident-unit: {e}")


def mock_plane_kill():
    """Terminate mock-server in any launch form (class run, mvn fork, fat jar).

    The resident transient unit is retired first through the owner channel:
    pkill alone left a jar-launched plane squatting the management port.
    """
    _retire_resident_plane_unit(mock_mgmt_port())
    for pattern in ("MockserverApplicatio[n]",
                    "pl mock-server spring-boot:ru[n]",
                    "[t]estpike-mock-server-.*jar"):
        subprocess.run(["pkill", "-f", pattern], capture_output=True)
    time.sleep(1)


def mock_plane_start(space_key, interval_ms=800, core_base=None,
                     mgmt_port=None, traffic_port=None, recording_flush_ms=None):
    """Boot mock-server through mvn with the loader pinned to space_key.

    Addressing resolves from the chain-slot env overlay when not passed
    explicitly (env key + shipped default, never a hardcoded port), and the
    vhost is pinned for the reason documented on rabbit_virtual_host.
    """
    core_base = core_base if core_base is not None else core_base_url()
    mgmt_port = mgmt_port if mgmt_port is not None else mock_mgmt_port()
    traffic_port = traffic_port if traffic_port is not None else mock_traffic_port()
    argv = [
        f"--server.port={mgmt_port}",
        f"--testpike.mock.traffic-port={traffic_port}",
        f"--testpike.mock.load.core-base-url={core_base}",
        f"--testpike.mock.load.space-key={space_key or ''}",
        f"--testpike.mock.load.interval-milliseconds={interval_ms}",
        "--testpike.mock.load.staleness-warn-seconds=300",
        f"--spring.rabbitmq.virtual-host={rabbit_virtual_host()}",
    ]
    if recording_flush_ms:
        argv.append(f"--testpike.mock.recording.flush-milliseconds={recording_flush_ms}")
    log = open("/tmp/testpike-tick-live.log", "a")
    return subprocess.Popen(["mvn", "-q", "-pl", "mock-server", "spring-boot:run",
                             "-Dspring-boot.run.arguments=" + " ".join(argv)],
                            cwd=REPO_ROOT, start_new_session=True,
                            stdout=log, stderr=subprocess.STDOUT)


def slot_psql():
    """Return the psql helper bound to this chain's slot database.

    bootstrap_env re-reads the install file and the database name is taken from
    DB_URL, so a stale export left behind by another chain can never point the
    helper at rows of a different environment. E2E modules that need a direct
    database touch call this instead of carrying their own copy.
    """
    import importlib
    import sys
    for directory in (os.path.join(_REPO_ROOT, "scripts"),
                      os.path.join(_REPO_ROOT, "tools", "verification")):
        if directory not in sys.path:
            sys.path.insert(0, directory)
    _db_target.bootstrap_env(cwd=_REPO_ROOT)
    os.environ["TP_PG_DATABASE"] = (
        os.environ.get("DB_URL", "").rsplit("/", 1)[-1].split("?")[0] or "testpike")
    return importlib.import_module("_l3_db").psql


def sql_query(q: str) -> str:
    pass
    target = os.environ.get("TP_E2E_DB_NAME", "").strip() or db_spec()[2]
    full = subprocess.run(
        ["docker", "exec", middleware_container("pg"), "psql", "-U",
         require_env("DB_USERNAME"), "-d", target, "-tAc", q],
        capture_output=True, text=True)
    if full.returncode != 0:
        raise RuntimeError(f"SQL 失败: {q} -> {full.stderr[:300]}")
    return full.stdout.strip()


def redis_cli(*args: str) -> str:
    """Run redis-cli pinned to THIS chain slot's logical DB (single source).

    Plain redis-cli defaults to DB 0, which is the main slot plane; a slot
    chain's services read/write REDIS_DATABASE from the slot overlay, so any
    frame or takeover command pushed without -n lands where this chain's own
    services never look — silent cross-slot writes plus assertions that pass
    vacuously against the wrong DB.
    """
    db = os.environ.get("REDIS_DATABASE", "0")
    r = subprocess.run(
        ["docker", "exec", middleware_container("redis"), "redis-cli",
         "-n", db, *args],
        capture_output=True, text=True, timeout=15, check=False)
    assert r.returncode == 0, f"redis-cli failed: {args} -> {r.stderr[:200]}"
    return r.stdout.strip()


# ---------- AppAuto live-monitor frame fixtures (single source, R40) ----------
# The monitor frame channel is a Redis ring buffer written by the executor's
# real intake path (core MonitorFrameBuffer). Spectra that drive the monitor
# viewer or the collaborative crop capture write through these helpers instead
# of each keeping a private copy of the key names and payload shape: a second
# writer silently drifts whenever the producer's contract changes.
# Injecting at the intake boundary is NOT a mock of the chain under test — the
# api -> core -> admin bridge, the crop service and the browser all run for
# real. Where a frame would come from a physical device stays annotated
# "await real device" .

MONITOR_FRAMES_KEY_PREFIX = "appauto-monitor:frames:"
MONITOR_META_KEY_PREFIX = "appauto-monitor:meta:"


def monitor_frame_payload(task_id: int, step_no: int, image_b64: str,
                          sensitive: bool = False) -> str:
    """One buffered frame exactly as the executor books it (JPEG base64 body)."""
    return json.dumps({"taskId": task_id, "stepNo": step_no,
                       "image": image_b64, "sensitive": sensitive})


def push_monitor_frames(task_id: int, frames: list) -> None:
    """Append frames to the ring head-first (newest first on the read side)."""
    redis_cli("LPUSH", f"{MONITOR_FRAMES_KEY_PREFIX}{task_id}", *frames)
    redis_cli("EXPIRE", f"{MONITOR_FRAMES_KEY_PREFIX}{task_id}", "120")


def cleanup_monitor_frames(task_id: int) -> None:
    """Drop the ring plus its counters so the slot leaves no frame residue."""
    redis_cli("DEL", f"{MONITOR_FRAMES_KEY_PREFIX}{task_id}",
              f"{MONITOR_META_KEY_PREFIX}{task_id}")


def create_running_exec_task(api_client, pid: int, uniq: str, label: str) -> int:
    """Create a case through the real API and book a RUNNING task row.

    Returns the task id the monitor viewer polls frames for. The row is booked
    straight as status=2 (RUNNING), never 1 (PENDING): a live executor claims
    PENDING within milliseconds and returns a terminal state, which flips the
    monitor window inactive and starves the frame channel (observed as fixture
    race, not a product regression). RUNNING rows are outside the claim query
    and the 10-minute timeout sweep is far longer than any spectrum window.
    """
    r = api_client.post("/cases", json={
        "projectId": pid, "title": f"{label}{uniq}", "precondition": "无",
        "expectedResult": "OK", "priority": 1, "confidence": 2, "endType": "web",
        "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": label, "isAssert": 1}]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]
    timeline = json.dumps([
        {"stepNo": 1, "action": "goto", "startAt": "2026-10-02T10:00:00"},
        {"stepNo": 2, "action": "http.request", "startAt": "2026-10-02T10:00:05"},
    ])
    tid = int(sql_query(
        "INSERT INTO tp_exec_task (project_id, case_id, status, task_type, steps_timeline) "
        f"VALUES ({pid}, {case_id}, 2, 1, '{timeline}'::jsonb) RETURNING id").splitlines()[0])
    return tid


def open_exec_task_detail(page, proj_name: str, tid: int):
    """Land the run-detail dialog of one booked task through the real UI.

 Single source for every spectrum that needs the embedded monitor window:
 pick the project in the header segment switch, find the task row by its id and
 open its detail. Returns the detail dialog locator.
 """
    inject_token(page)
    nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(
        ".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(
        has_text=proj_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
    table = page.locator('[data-test="exec-task-table"]')
    expect(table).to_be_visible(timeout=15000)
    row = table.locator("tbody tr").filter(has_text=f"#{tid}").first
    expect(row).to_be_visible(timeout=20000)
    row.locator('[data-test="exec-task-detail"]').click()
    dlg = page.locator('[data-test="exec-detail-dialog"]')
    expect(dlg).to_be_visible(timeout=10000)
    return dlg


def land_draft_frame(core_base: str, sid: int, msg_id: int, payload: str,
                     meta: dict) -> str:
    """Push one draft proposal through the orchestrator's own frame door.

    Single source for the draft-card spectra (R40): post the frame, pin the
    code-ok verdict and read back the draft ledger row the door itself must
    have written — no SQL insert allowed on the test side.

    Read-back is anchored on "rows this post minted" (id > previous max for
    the message), not on "the message has exactly one row": since the per-frame
    key change a message legitimately carries a group of drafts, and a
    message-wide readback returns every sibling row (observed as a multi-line
    value breaking int() at the second park of the group-door spectrum).
    """
    before = sql_query(
        f"SELECT COALESCE(max(id), 0) FROM tp_ai_chat_draft WHERE message_id={msg_id}")
    landed = httpx.post(f"{core_base}/internal/ai-chat/sessions/{sid}/frames",
                        json={"messageId": msg_id, "frameType": "draft",
                              "payload": payload,
                              "metaJson": json.dumps(meta, ensure_ascii=False)},
                        headers={"X-Operator-Id": sql_query(
                            "SELECT id FROM tp_user WHERE username='admin' AND deleted=0")},
                        timeout=30).json()
    assert landed.get("code") == "0", landed
    draft_id = sql_query(
        f"SELECT id FROM tp_ai_chat_draft WHERE message_id={msg_id} AND id > {before}")
    assert draft_id, "the frame door must write the draft ledger row itself (no SQL insert here)"
    assert "\n" not in draft_id, \
        f"one frame must mint exactly one draft row, got: {draft_id}"
    return draft_id


def launch_provider_stub(script_name: str, port: int, env_extra: dict) -> subprocess.Popen:
    """Spawn a tools/ provider-position stub and return its LIVE process.

 Readiness shape = the proven probe (vision_shots_mcp_knowledge_sync), one
 source for every stub launcher (debt, extract-common law). Two
 hazards the old inline copies carried: an HTTP GET through httpx can be
 routed by proxy env and reports a LISTENING stub as failed to start; and
 when the port is already held, the fresh bind dies instantly while the
 OLD holder answers the probe — callers got back a dead handle and the
 zero-hit ledger then mis-read as "stub never ran". A port held before
 launch is surfaced loudly, never reused blind: a listener of unknown
 identity may belong to another chain (resource-ownership law).
 """

    def listening() -> bool:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.3):
                return True
        except OSError:
            return False

    if listening():
        raise AssertionError(
            f"port {port} already held before stub launch ({script_name}) — "
            f"the stale listener must be reaped by its owner, never reused blind")
    tools_dir = os.path.normpath(
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../tools"))
    proc = subprocess.Popen(
        ["python3", script_name], cwd=tools_dir,
        env={**os.environ, "MOCK_LLM_PORT": str(port), **env_extra},
        stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    deadline = time.time() + 15
    while time.time() < deadline:
        if proc.poll() is not None:
            raise AssertionError(
                f"stub {script_name} exited before port {port} came up "
                f"(double bind on a held port, or a startup failure)")
        if listening():
            return proc
        time.sleep(0.15)
    proc.kill()
    raise AssertionError(f"stub {script_name} port {port} never came up within 15s")


def wait_ai_task_terminal(token: str, task_id: int, timeout: int | None = None,
                          require_success: bool = True) -> dict:
    """Poll an AI generation task until terminal (status>=3: 3=ok/4=fail); returns task detail.

 Shared AI-terminal waiter . Deadline raised
 600->1200 on measured gateway latency: with three concurrent generations
 queued behind the shared LLM gateway, tasks completed in 10-15 min
 (observed: submit 17:45:23 -> done 18:00:42 while the script stage alone
 sat ~14 min in queue). require_success=True asserts status==3 at terminal;
 False just waits. No per-file copies of this loop (extract-common law).
 The deadline must stay below the file-level pytest-timeout (AI-family
 files carrying a raised deadline set pytestmark to 1200).

 A window may state the deadline through TP_E2E_AI_TERMINAL_TIMEOUT_S so a
 triage rerun can bound queue waiting without editing call sites; an explicit
 call-site timeout always wins.
 """
    if timeout is None:
        timeout = _env_int("TP_E2E_AI_TERMINAL_TIMEOUT_S") or 1200
    h = {"Authorization": f"Bearer {token}"}
    deadline = time.time() + timeout
    t = None
    while time.time() < deadline:
        r = httpx.get(f"{API_BASE}/ai/tasks/{task_id}", headers=h, timeout=10)
        t = r.json()["data"]
        if t["status"] >= 3:
            if require_success:
                assert t["status"] == 3, f"生成未成功: {t}"
            return t
        time.sleep(2)
    raise AssertionError(f"生成任务 {task_id} {timeout}s 未到终态（最后状态: {t}）")


def wait_case_status(api, case_id: int, status: int, timeout: int = 300) -> None:
    pass
    cur = 0
    deadline = time.time() + timeout
    while time.time() < deadline:
        r = api.get(f"/cases/{case_id}")
        assert r.status_code == 200, f"查询用例失败: {r.text[:300]}"
        cur = r.json()["data"]["status"]
        if cur == status:
            return
        time.sleep(2)
    raise AssertionError(f"用例 {case_id} {timeout}s 内未到状态 {status}（当前 {cur}）")


def wait_tasks_settled(plan_id: int, timeout: int = 90) -> None:
    pass
    deadline = time.time() + timeout
    while time.time() < deadline:
        cnt = sql_query(
            f"SELECT count(*) FROM tp_exec_task t JOIN tp_exec_batch b ON t.batch_id = b.id "
            f"WHERE b.plan_id = {plan_id} AND t.status IN (1,2)")
        if cnt == "0":
            return
        time.sleep(3)


TESTER_PASSWORD = "Test@12345"


def mk_tester(api, uniq, prefix="t"):
    pass
    username = f"{prefix}tester_{uniq}"
    nick = f"{prefix}测试员{uniq[:6]}"
    r = api.post("/users", json={
        "username": username, "password": "Test@12345", "displayName": nick,
        "email": f"{username}@test.local", "phone": "", "role": 1,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建测试人员失败: {r.text[:300]}"
    return r.json()["data"], nick


def add_member(api, pid, user_id):
    pass
    r = api.post(f"/projects/{pid}/members", json={"userId": user_id, "role": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"添加成员失败: {r.text[:300]}"


# ---------- fixtures ----------
@pytest.fixture(scope="session")
def browser():
    pass
    p = sync_playwright().start()
    b = None
    try:
        # Iron rule 5c: old headless (headless=True plain) silently reports
        # zero Audits issues = fake monitoring. Chromium 151 field-tested form:
        # headed flag + --headless=new keeps the Issues pipeline live.
        b = p.chromium.launch(headless=False, args=["--headless=new"])
        # Session-level probe: a known-dirty form MUST fire an issue, else the
        # whole run is refused (never run E2E with fake monitoring).
        selftest_issue_monitoring(b)
        yield b
    finally:
        if b is not None:
            # Guarded: after a test-phase timeout the driver link is already dead
            # and a bare close() spins forever, which is how a nominally finished
            # session keeps a core busy with no output.
            _bounded_close(b.close, "browser.close", reclaim_on_abort=True)
        _bounded_close(p.stop, "playwright.stop", reclaim_on_abort=True)


@pytest.fixture(autouse=True)
def _cleanup_stray_browsers():
    pass
    yield



SCREENSHOT_MODE_KEY = "ai.script.screenshot-mode"
SCREENSHOT_MODE_EVERY_STEP = "1"
SCREENSHOT_MODE_FAIL_ONLY = "3"


def _find_sys_config(key: str) -> dict | None:
    token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)
    h = {"Authorization": f"Bearer {token}"}
    r = httpx.get(f"{API_BASE}/configs", headers=h, timeout=10)
    assert r.status_code == 200, f"读取系统配置失败: {r.status_code} {r.text[:200]}"
    return next((x for x in r.json()["data"] if x.get("configKey") == key), None)


def get_sys_config_value(key: str) -> str | None:
    cfg = _find_sys_config(key)
    return cfg.get("configValue") if cfg else None


def _set_sys_config_value(key: str, value: str) -> None:
    pass
    token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)
    h = {"Authorization": f"Bearer {token}"}
    cfg = _find_sys_config(key)
    assert cfg, f"系统配置键不存在: {key}（V40 seed 未落库？）"
    r = httpx.put(f"{API_BASE}/configs/{cfg['id']}", json={
        "configKey": key, "configValue": value,
        "groupName": cfg.get("groupName", "系统"), "valueType": cfg.get("valueType", "int"),
        "encryptFlag": cfg.get("encryptFlag", 0), "description": cfg.get("description", ""),
    }, headers=h, timeout=10)
    assert r.status_code == 200 and r.json().get("code") == "0", \
        f"更新系统配置 {key}={value} 失败: {r.text[:200]}"


def ensure_screenshot_mode_every_step() -> None:
    """Re-assert the every-step capture posture right before a trial run claims.

    The session-scoped pin alone is not cross-chain safe: a concurrent E2E session
    of another chain can have its controller sessionfinish restore mode=3 through
    the shared admin config plane (default API_BASE) mid-session. Claim-time reads
    are live, so re-asserting just before the trial-run click shrinks the race
    window to seconds without weakening the capture-evidence precondition."""
    if get_sys_config_value(SCREENSHOT_MODE_KEY) != SCREENSHOT_MODE_EVERY_STEP:
        _set_sys_config_value(SCREENSHOT_MODE_KEY, SCREENSHOT_MODE_EVERY_STEP)


def pytest_sessionstart(session):
    pass
    try:
        _set_sys_config_value(SCREENSHOT_MODE_KEY, SCREENSHOT_MODE_EVERY_STEP)
    except Exception as e:
        print(f"[WARN] 截图取证前置热更失败（screenshot 类 E2E 将失败）: {e}")


def pytest_sessionfinish(session, exitstatus):
    pass
    import os

    if os.environ.get("PYTEST_XDIST_WORKER"):
        return
    # Machine-wide "pkill -f playwright/driver/node" also kills the drivers of OTHER
    # concurrent pytest sessions from other worktrees/chains (cross-chain E2E wipeout,
    # t515 efgreenc/efgreenf 2026-11-04: AL chain's sessionfinish killed this session's
    # browser mid-run). Reclaim stays scoped to this pytest process' own descendant
    # tree, through the same single-source helper the teardown guard uses.
    _reclaim_drivers(os.getpid())
    try:
        _set_sys_config_value(SCREENSHOT_MODE_KEY, SCREENSHOT_MODE_FAIL_ONLY)
    except Exception as e:
        print(f"[WARN] screenshot-mode 恢复失败（下批会话 sessionstart 幂等自愈）: {e}")


@pytest.fixture
def page(browser):
    ctx = browser.new_context(viewport={"width": 1600, "height": 900})
    # Action/navigation budget for calls that do not state one: Playwright ships 30s,
    # which on a red assertion costs twice the failure signal for no extra truth.
    # Measured green-suite average is 30.6 worker-seconds per test, so 15s stays
    # above real render latency; TP_E2E_DEFAULT_TIMEOUT_MS reverts it per window.
    budget_ms = _env_int("TP_E2E_DEFAULT_TIMEOUT_MS") or 15000
    ctx.set_default_timeout(budget_ms)
    ctx.set_default_navigation_timeout(budget_ms)
    pg = ctx.new_page()
    yield pg
    _bounded_close(ctx.close, "context.close")


@pytest.fixture
def collectors(page):
    return attach_monitors(page)


class LoginRejected(Exception):
    pass


def teardown_extraction_case(api_client, doc_ids, pid, token=None):
    """Best-effort teardown for extraction cases (R40 single source): document rows first,
 then the scratch project segment; failures print, never mask the assertion above.

 doc_ids takes one id or an iterable; token reuses an already-issued admin session so
 bulk fixtures (31-document scale cases) do not log in per row."""
    if doc_ids:
        ids = doc_ids if isinstance(doc_ids, (list, tuple, set)) else [doc_ids]
        for did in ids:
            try:
                t = token or api_login("admin", ADMIN_PASSWORD)
                httpx.delete(f"{API_BASE}/ai/documents/{did}",
                             headers={"Authorization": f"Bearer {t}"}, timeout=30)
            except Exception as e:  # noqa: BLE001
                print(f"  doc cleanup FAIL: {e}")
    if pid:
        try:
            api_client.delete(f"/projects/{pid}/purge?force=true")
        except Exception as e:  # noqa: BLE001
            print(f"  purge FAIL: {e}")


def api_login_pair(username: str, password: str) -> tuple[str, str]:
    """Real login call -> (accessToken, refreshToken).

    E2E sessions must carry BOTH tokens like any real user session: a journey
    longer than auth.access-ttl-seconds (1800s) then self-renews through the
    R6 refresh road. An access-token-only fixture cannot renew, so it
    fabricates a 401 on the wire that no real user would ever see (WT window
    r19, journey B crossing the TTL at 1938s).
    """
    with httpx.Client(base_url=API_BASE, timeout=10) as client:
        resp = client.post("/auth/login", json={"username": username, "password": password})
        if resp.status_code == 401 or resp.status_code == 423:
            raise LoginRejected(
                f"登录被拒 {resp.status_code}: {resp.text[:300]}"
                "（密码错误/账号锁定——勿重试；确认已 `set -a && source .env && set +a`）"
            )
        assert resp.status_code == 200, f"登录 API 失败: {resp.status_code} {resp.text[:300]}"
        body = resp.json()
        if body.get("code") != "0":
            raise LoginRejected(f"登录业务失败: {body.get('msg')}")
        d = body["data"]
        return d["accessToken"], d.get("refreshToken") or ""


def api_login(username: str, password: str) -> str:
    pass
    return api_login_pair(username, password)[0]


@pytest.fixture
def logged_in(page, collectors):
    pass
    for attempt in (1, 2, 3):
        try:
            token, refresh = api_login_pair(ADMIN_USERNAME, ADMIN_PASSWORD)
            page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
            page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_refresh_token', '{refresh}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
            page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page
        except LoginRejected:
            raise
        except AssertionError:
            if attempt == 3:
                raise AssertionError(
                    "logged_in 注入后未进入工作台（页面未渲染 my-project-table）——"
                    "疑似 WSL 网络层抖动致 /users/me 失败被守卫踢回登录；已重试两次仍失败"
                )
        except Exception:
            if attempt == 3:
                raise
        page.wait_for_timeout(1500)
    raise AssertionError("unreachable")


@pytest.fixture
def api_client():
    pass
    token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)

    class Client:
        def __init__(self):
            self.headers = {"Authorization": f"Bearer {token}"}

        def post(self, url, json=None):
            return httpx.post(f"{API_BASE}{url}", json=json, headers=self.headers, timeout=10)

        def get(self, url, params=None):
            # params passthrough (single source): query-filtered reads such as
            # /appauto/devices?projectId= are asserted across suites.
            return httpx.get(f"{API_BASE}{url}", params=params, headers=self.headers, timeout=10)

        def put(self, url, json=None):
            return httpx.put(f"{API_BASE}{url}", json=json, headers=self.headers, timeout=10)

        def delete(self, url):
            return httpx.delete(f"{API_BASE}{url}", headers=self.headers, timeout=10)

    return Client()


@pytest.fixture
def uniq():
    return uuid.uuid4().hex[:8]


def inject_user_token(page, username: str, password: str):
    pass
    for attempt in (1, 2):
        token, refresh = api_login_pair(username, password)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_refresh_token', '{refresh}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page
        except AssertionError:
            if attempt == 2:
                raise AssertionError(
                    f"inject_user_token({username}) 注入后未进入工作台（my-project-table 不可见）——"
                    "疑似 WSL 网络层抖动致 /users/me 失败被守卫踢回登录；已重试一次仍失败"
                )
    raise AssertionError("unreachable")


VISIBLE_OPTS = ".el-select-dropdown:visible .el-select-dropdown__item:visible"


def filter_to_needle(page, anchor, total_count, needle_text, type_text, select=True):
    """Prove a selector is searchable under its real ledger size .

 Opens the `[data-test=anchor]` select, hard-asserts `total_count` visible
 options, types `type_text`, hard-asserts exactly one option remains and it
 contains `needle_text`; `select=True` then clicks it (pins the "target
 option reachable in <=2 clicks / 1 search" contract). No conditional
 wrappers: a count mismatch fails the spectrum (audit law 5d).
 """
    page.locator(f'[data-test="{anchor}"]').click()
    opts = page.locator(VISIBLE_OPTS)
    expect(opts).to_have_count(total_count, timeout=10000)
    page.keyboard.type(type_text)
    expect(opts).to_have_count(1, timeout=5000)
    expect(opts.first).to_contain_text(needle_text)
    if select:
        opts.first.click()


def inject_token(page):
    pass
    return inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)


def publish_shared_asset(api_client, project_id, kind, name, payload_json):
    """Create a published shared-asset head and return its row id.

 Single source for every spec that needs a real published asset row: the
 segment-pinning and asset-pool journeys must seed their own fixture instead
 of depending on an empty slot pool (R37 self-made data, R40 no second copy)."""
    resp = api_client.post("/shared-assets", json={
        "projectId": project_id, "kind": kind, "name": name, "payloadJson": payload_json})
    assert resp.status_code == 200 and str(resp.json().get("code")) == "0", \
        f"API failure: {resp.text[:300]}"
    return resp.json()["data"]


def create_program(api_client, name, description="program e2e fixture"):
    """Real-API program-set scratch row through the admin door (returns its id).

    Shared by the program-lens list and the dashboard spectra — one
    create/assert door for every program fixture (extract-common law).
    """
    r = api_client.post("/programs", json={"name": name, "description": description})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def attach_program_project(api_client, program_id, project_id):
    """Mount one scratch project onto the program set (same 200/code=0 door)."""
    r = api_client.post(f"/programs/{program_id}/projects/{project_id}")
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]


def delete_program(api_client, program_id):
    """Remove the scratch program set once the spectrum finished with it."""
    r = api_client.delete(f"/programs/{program_id}")
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]


def seed_cases(api_client, project_id, prefix, count):
    """Seed `count` real case rows under one project for worst-scale floors.

    Titles stay unique per spectrum through the caller's prefix so list-page
    assertions can pin their own rows.
    """
    for i in range(count):
        r = api_client.post("/cases", json={
            "projectId": project_id,
            "title": f"{prefix}-{i:02d}",
            "precondition": "账号已注册",
            "expectedResult": "登录成功",
            "priority": 2,
            "confidence": 3,
            "endType": "web",
            "status": 1,
        })
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]


def seed_project(api_client, uniq, tag, display_name=None):
    """Real-API scratch project (unique name/code, purged by the caller).

    Shared by the AI-chat family spectra (test_ai_chat_page, test_t451_*) and
    other fresh-slice spectra — no per-file copies of the create door
    (extract-common law). display_name overrides the chat-flavoured default
    for non-chat spectra that still want the same create/assert/return door.
    """
    name = display_name or f"对话页{tag}{uniq}"
    r = api_client.post("/projects", json={"name": name, "code": f"{tag}{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"create project failed: {r.text[:300]}"
    data = r.json()["data"]
    return data["id"] if isinstance(data, dict) else data


def purge_project(api_client, pid):
    """Sweep the chat ledger atomically, then force-delete the scratch project.

    The sealing pump of an aborted turn keeps appending frames for a beat
    (field-proven in the internal L3 dock-session spectrum): statement-by-statement
    deletes raced it (a frame re-inserted between passes held the message FK).
    The sweep therefore runs as ONE psql transaction (frames by session, frames
    by message, messages, sessions) and the loop repeats with a settle sleep
    until the ledger reads zero; bounded, then reported honestly.
    """
    sub_sessions = f"SELECT id FROM tp_ai_chat_session WHERE project_id={pid}"
    sub_msgs = f"SELECT id FROM tp_ai_chat_message WHERE session_id IN ({sub_sessions})"
    sweep = (
        # Draft rows first: the repair-draft table has no FK, so a session
        # sweep without it would silently orphan proposal rows.
        f"DELETE FROM tp_ai_chat_draft WHERE session_id IN ({sub_sessions}); "
        f"DELETE FROM tp_ai_chat_frame WHERE session_id IN ({sub_sessions}); "
        f"DELETE FROM tp_ai_chat_frame WHERE message_id IN ({sub_msgs}); "
        f"DELETE FROM tp_ai_chat_message WHERE session_id IN ({sub_sessions}); "
        f"DELETE FROM tp_ai_chat_session WHERE project_id={pid};"
    )
    time.sleep(3)
    settled = False
    for _ in range(10):
        try:
            sql_query(sweep)
            if int(sql_query(f"SELECT count(*) FROM tp_ai_chat_session WHERE project_id={pid}")) == 0:
                settled = True
                break
        except RuntimeError:
            time.sleep(3)
    if not settled:
        raise AssertionError(f"chat ledger for scratch project {pid} did not settle to zero")
    # Physical cascade delete lives on /projects/{id}/purge; plain
    # DELETE /projects/{id} is the archive (soft-delete status=2) door, which
    # returns code=0 while leaving the row — field-proven by the scratch
    # project residue (archive silently succeeded, purge never called).
    r = api_client.delete(f"/projects/{pid}/purge?force=true")
    assert r.status_code == 200, f"force-delete scratch project failed: {r.text[:200]}"


def pick_header_context(page, data_test, option_name):
    """Land one global header context switcher on a named option (single source).

    The header hosts several context selectors (project lens, program lens);
    their option sets come from the login snapshot, so an entity created AFTER
    it needs /users/me refreshed first (T372c3/W1C posture): when the option is
    absent, reload once and retry — the header never types into a remote door.
    """
    selector = f'[data-test="{data_test}"]'
    for attempt in (1, 2):
        page.locator(selector).click()
        option = page.locator(
            f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{option_name}"):visible'
        ).first
        try:
            option.wait_for(state="visible", timeout=4000 if attempt == 1 else 10000)
            option.click()
            return
        except Exception:
            page.keyboard.press("Escape")
            if attempt == 1:
                page.reload(wait_until="domcontentloaded")
                page.wait_for_selector(selector, timeout=15000)
            else:
                raise


def pick_enum_option(page, data_test, value):
    """Choose one option of a static enumeration select (SE5 posture).

    Closed-enum fields are rendered as el-select so there is no typing
    channel: spectra click the option instead of filling text, and the
    assertion below doubles as the physical-existence check for it.

    Dialogs may host several el-selects, and a just-closed popper lingers in
    the DOM while it fades out, so one blind ":visible .first" click can pin
    a vanishing copy of the same option (2026-11-19 window2 evidence: the li
    resolved visible once, then stayed unclickable for the whole 15s).
    Retrying behind an Escape collapses stale poppers first and re-opens the
    one owned by this field; a genuine product fault still fails downstream
    because callers assert the semantic result (selection value / ledger row).
    """
    for attempt in (1, 2, 3):
        if attempt > 1:
            page.keyboard.press("Escape")
            # Popper fade-out is a CSS animation, not a Playwright-managed
            # async; a fixed settle is the honest wait here.
            page.wait_for_timeout(350)
        page.locator(f'[data-test="{data_test}"]').click()
        option = page.locator(
            f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{value}"):visible'
        ).first
        try:
            option.wait_for(state="visible", timeout=5000)
            option.click(timeout=5000)
            return
        except Exception:
            if attempt == 3:
                raise


def pick_project_in_header(page, project_name):
    """Land the global header project context on one project (single select).

 anchor migration + R30 (10-25): page-level project pickers are retired
 site-wide — the header is the only project switch, and its option source is
 the injected visible-domain snapshot (authStore.projects; zero remote call
 by the AK contract). The switch mechanics are single-sourced in
 pick_header_context (R40).
 """
    pick_header_context(page, "project-ctx-select", project_name)


def pick_apitest_collection(page, coll_text):
    """Click the rail collection card whose name contains coll_text."""
    card = page.locator('[data-test^="apitest-collection-card-"]').filter(has_text=coll_text).first
    expect(card).to_be_visible(timeout=10000)
    card.click()


def pick_apitest_request(page, req_text):
    """Click the rail request row whose name/path contains req_text."""
    row = page.locator('[data-test^="apitest-request-row-"]').filter(has_text=req_text).first
    expect(row).to_be_visible(timeout=10000)
    row.click()


def open_apitest_datasource_item(page, item_anchor, menu_anchor='apitest-datasource-menu'):
    """Open a rail/workspace dropdown and click one of its items.

 The import/subscription doors (and the folded cross-domain exits) live
 in dropdown menus, so an item is only reachable after its trigger is opened.
 """
    trigger = page.locator(f'[data-test="{menu_anchor}"] button').first
    expect(trigger).to_be_enabled(timeout=5000)
    trigger.click()
    item = page.locator(f'[data-test="{item_anchor}"]:visible').first
    expect(item).to_be_visible(timeout=5000)
    item.click()


def nav_to(page, menu_text, url_fragment, target_selector, timeout=10000, submenu=None):
    pass
    if submenu:
        titles = submenu if isinstance(submenu, (list, tuple)) else (submenu,)
        for level in titles:
            title = page.locator(f'.el-sub-menu__title:has-text("{level}")').first
            sub = page.locator(f'.el-sub-menu:has(> .el-sub-menu__title:has-text("{level}"))').first
            title.wait_for(state="visible", timeout=10000)
            for _ in range(3):
                if "is-opened" in (sub.get_attribute("class") or ""):
                    break
                title.click()
                page.wait_for_timeout(350)
    menu = page.locator(f'.el-menu-item:has-text("{menu_text}")')
    for attempt in (1, 2):
        menu.first.click()
        try:
            page.wait_for_url(f"**#{url_fragment}", timeout=timeout)
            break
        except Exception:
            if attempt == 2:
                raise
            expect(menu.first).to_be_visible(timeout=5000)
    expect(page.locator(target_selector)).to_be_visible(timeout=timeout)


def hard_goto(page, url, wait_until="domcontentloaded"):
    """Force a real document navigation to one of the SPA's hash routes.

    A ``page.goto`` whose target differs from the current URL only in the hash
    fragment is a *same-document* navigation: Playwright returns without any
    document load, the router keeps rendering the route it already resolved, and
    the browser URL and the view end up out of sync (measured here: url settled
    on ``#/extraction`` while the requirements list stayed on screen, so the
    menu click that followed resolved to a duplicate navigation and never
    emitted a navigation event the downstream URL assertion could see).
    Routing through a blank document first turns the target into a genuine load,
    which is the state the navigation helpers and their assertions assume.
    """
    page.goto("about:blank")
    page.goto(url, wait_until=wait_until)


def open_journey_leaf(page, leaf_data_test, *group_path):
    """Journey-menu navigation via the data-test anchors. group_path = top-level group
    index (the menu is capped at two levels: group -> leaf; the helper keeps its
    stepwise-expand loop for backward compatibility). Shared by the appauto / upgrade
    center / resource pool page families (replaces 11 hand-written expand sequences).
    Two known races handled: clicking an already-open group collapses it, and the
    reload auto-expand transition collides with the click -- re-check the expanded
    state for up to 3 rounds before clicking the next level."""
    for idx in group_path:
        box = page.locator(f'[data-test="group-{idx}"]').first
        title = box.locator("> .el-sub-menu__title").first
        expect(title).to_be_visible(timeout=10000)
        for _ in range(3):
            if "is-opened" in (box.get_attribute("class") or ""):
                break
            title.click()
            page.wait_for_timeout(350)
    menu = page.locator(f'[data-test="{leaf_data_test}"]')
    expect(menu.first).to_be_visible(timeout=10000)
    menu.first.click()


def row_cmd(page, row, key: str):
    pass
    expect(row.locator('[data-test="row-actions"]')).to_be_visible(timeout=10000)
    more = row.locator('[data-test="ra-more"]')
    if more.count() > 0:
        more.click()
        page.locator(f'[data-test="ra-{key}"]:visible').first.click()
    else:
        row.locator(f'[data-test="ra-{key}"]').click()


def trial_run_browser(page):
    pass
    page.locator('[data-test="detail-trial-run"]').click()
    expect(page.locator('[data-test="env-select-dialog"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="env-select"]').click()
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item:not(.is-disabled)').first.click()
    page.locator('[data-test="env-select-confirm"]').click()
    expect(page.locator('[data-test="trial-result-dialog"]')).to_be_visible(timeout=10000)


def wait_trial_success(page, timeout: int = 300000) -> None:
    pass
    expect(page.locator('[data-test="exec-task-status"]')).to_contain_text("成功", timeout=timeout)


FIXTURE_LOGIN_REQ = (
    "演示门户登录页 http://localhost:8899/login：账号输入框 input[name=username]"
    "（id=username），密码输入框 input[name=password]（id=password），登录按钮 id=login-btn；"
    "输入账号密码点击登录按钮后，id=welcome 元素显示文本「登录成功显示欢迎信息」。"
    "生成一条登录成功用例：打开登录页、输入账号、输入密码、点击登录按钮、断言显示登录成功欢迎信息。"
)
FIXTURE_DEMO_PARAMS = {
    "LOGIN_URL": "http://localhost:8899/login",
    "USERNAME": "demo",
    "PASSWORD": "demo123",
    "ENV": "test",
    "PACK": "default",
    "DATA": "default",
    "LOGIN_STATE": "none",
}


def save_env_params(api, pid, env_id, params=None) -> None:
    pass
    body = {"params": params if params is not None else FIXTURE_DEMO_PARAMS}
    r = api.put(f"/projects/{pid}/envs/{env_id}/params", json=body)
    assert r.status_code == 200 and r.json().get("code") == "0", \
        f"保存环境参数失败: {r.status_code} {r.text[:300]}"


def reload_boot_snapshot(page) -> None:
    """Boot the SPA in a fresh document so the header project dropdown —
    filled from the boot-time /users/me snapshot with no refetch on open —
    includes projects created out-of-band after login. Hash-only goto is
    same-document and never refetches. Single source for the pattern that
    first landed inline in the sample-ledger test (extracted once a second
    consumer appeared)."""
    page.goto("about:blank")
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator(".dashboard-root")).to_be_visible(timeout=30000)


def ai_generate_api(pid: int, requirement: str, timeout: int | None = None) -> dict:
    pass
    token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)
    h = {"Authorization": f"Bearer {token}"}
    if timeout is None:
        # Measured provider latency (2026-11-04 windows): stage-2 script
        # generation alone ran 447s (task 736) and 646s (task 737), so the
        # legacy 600s floor kept cutting healthy pipelines. Default 900s,
        # hot-overridable via TP_E2E_AI_TERMINAL_TIMEOUT_S.
        timeout = _env_int("TP_E2E_AI_TERMINAL_TIMEOUT_S") or 900
    for attempt in (1, 2):
        r = httpx.post(f"{API_BASE}/ai/generate",
                       json={"projectId": pid, "requirement": requirement},
                       headers=h, timeout=15)
        assert r.status_code == 200, f"发起生成失败: {r.status_code} {r.text[:300]}"
        task_id = r.json()["data"]["taskId"]
        t = wait_ai_task_terminal(token, task_id, timeout=timeout, require_success=False)
        if t["status"] == 3:
            return t
        assert attempt == 1 and t.get("errorCode") == "FORMAT_ERROR", \
            f"生成失败（非 FORMAT_ERROR 偶发族，不重试）: {t}"
    raise AssertionError("unreachable")


_SCRIPT_ENV_KEY = re.compile(r"\{\{ENV:([A-Za-z_][A-Za-z0-9_]*)\}\}")
_SCRIPT_DATA_KEY = re.compile(r"\{\{DATA:([A-Za-z_][A-Za-z0-9_]*)\}\}")
_URL_KEY_RE = re.compile(r"URL|BASE")
DEMO_BASE_URL = "http://localhost:8899"
DEMO_PAGE_URL = "http://localhost:8899/login"


def _demo_value(key: str) -> str:
    pass
    k = key.upper()
    if "BASE" in k:
        return DEMO_BASE_URL
    if "URL" in k:
        return DEMO_PAGE_URL
    if "PASS" in k:
        return "demo123"
    return "demo"


def script_step_count(api, case_id) -> int:
    pass
    r = api.get(f"/cases/{case_id}/script")
    assert r.status_code == 200 and r.json().get("code") == "0", \
        f"读取脚本失败: {r.status_code} {r.text[:300]}"
    doc = r.json()["data"]["actionJson"]
    if isinstance(doc, str):
        doc = json.loads(doc)
    return len(doc.get("setup") or []) + len(doc.get("steps") or [])


def _pool_derived_value(key: str) -> str:
    pass
    return f"demo-{key.lower()}-{uuid.uuid4().hex[:8]}"


def seed_data_pool(api, pid, env_id, keys, pool_type: str = "账号") -> list:
    pass
    seeded = []
    for key in keys:
        content = (f"# poolKey={key}\n"
                   f"import json\n"
                   f"print(json.dumps([{json.dumps(_pool_derived_value(key))}]))\n")
        name = f"E2E夹具DATA {key} {uuid.uuid4().hex[:6]}"
        r = api.post("/data-factories", json={
            "projectId": pid, "envId": env_id, "type": 3,
            "name": name[:60], "content": content})
        fid = ((r.json() or {}).get("data") or {}).get("id") if r.status_code == 200 else None
        if not fid:
            continue
        r = api.post(f"/data-factories/{fid}/run")
        if r.status_code == 200 and (r.json() or {}).get("code") == "0":
            seeded.append(key)
    return seeded


def save_script_params(api, pid, env_id, case_id, phase: str = "ok") -> dict:
    pass
    r = api.get(f"/cases/{case_id}/script")
    assert r.status_code == 200 and r.json().get("code") == "0", \
        f"读取脚本失败: {r.status_code} {r.text[:300]}"
    script = r.json().get("data")
    assert script and script.get("actionJson"), f"用例 {case_id} 无脚本（占位扫描前置不成立）"
    scan_keys = sorted(set(_SCRIPT_ENV_KEY.findall(str(script["actionJson"]))))
    data_keys = sorted(set(_SCRIPT_DATA_KEY.findall(str(script["actionJson"]))))
    if data_keys:
        seed_data_pool(api, pid, env_id, data_keys)
    assert scan_keys or phase != "fail", \
        f"用例 {case_id} 脚本无 {{ENV:…}} 占位——失败相无从构造（合法契约产物，需换构造方案）"
    params = dict(FIXTURE_DEMO_PARAMS)
    for k in scan_keys:
        params.setdefault(k, _demo_value(k))
    fail_mode = None
    if phase == "fail":
        url_keys = [k for k in params
                    if _URL_KEY_RE.search(k.upper())
                    and (k in scan_keys or k == "LOGIN_URL")]
        if any(k in scan_keys for k in url_keys):
            fail_mode = "dead-url"
            dead = f"{DEMO_BASE_URL}/nope-{uuid.uuid4().hex[:8]}"
            for k in url_keys:
                params[k] = dead
        else:
            fail_mode = "missing-keys"
            for k in scan_keys:
                params.pop(k, None)
    save_env_params(api, pid, env_id, params)
    return {"keys": scan_keys, "fail_mode": fail_mode}


def first_env_id(api, pid) -> int:
    pass
    r = api.get(f"/projects/{pid}/envs")
    assert r.status_code == 200, f"环境列表失败: {r.text[:300]}"
    envs = r.json()["data"]
    assert envs, f"项目 {pid} 无执行环境"
    return envs[0]["id"]


def assert_detail_opened(page) -> str:
    pass
    import re as _re

    expect(page).to_have_url(_re.compile(r"/#/cases/\d+"), timeout=10000)
    title = page.locator('[data-test="detail-title"]').inner_text().strip()
    assert title, "详情页标题不应为空"
    return title


def _orphan_runner_pids() -> list[int]:
    pass
    repo_root = os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    pids = []
    probe = subprocess.run(["pgrep", "-f", "runner.py"],
                           capture_output=True, text=True, check=False)
    for tok in probe.stdout.split():
        try:
            cwd = os.path.realpath(f"/proc/{int(tok)}/cwd")
        except (ValueError, OSError):
            continue
        if cwd in (os.path.join(repo_root, "executor"), repo_root):
            pids.append(int(tok))
    return pids




def _repo_root() -> str:
    return os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


def _chain_slot_tool():
    """scripts/chain_slot.py, loaded once: it owns slot identity (no second derivation)."""
    mod = globals().get("_TP_CHAIN_SLOT")
    if mod is None:
        mod = _load_scripts_module("chain_slot")
        globals()["_TP_CHAIN_SLOT"] = mod
    return mod


def _this_slot() -> int:
    """This chain's slot number. Never guessed: a worktree whose slot env cannot be resolved
    raises, so a slot chain cannot mistake the main chain's units for its own."""
    return _chain_slot_tool().current_slot(cwd=_repo_root())


def runner_unit_name() -> str:
    """Unit name for THIS chain's executor runner: slot-suffixed so two chains never share one.

    A fixed name made the unit a host-wide singleton: whichever chain created it last owned it,
    and the other chain's stop/start calls landed on someone else's process.
    """
    return f"tp-e2e-runner-slot{_this_slot()}"


def runner_log_path() -> str:
    return f"/tmp/{Path(_repo_root()).name.lower()}-runner-slot{_this_slot()}.log"


def _proc_cwd(pid: int) -> str:
    try:
        return os.path.realpath(f"/proc/{int(pid)}/cwd")
    except Exception:
        return ""


def unit_is_ours(unit: str, main_pid: str = "") -> bool:
    """Ownership test for a systemd unit: its main process must run inside this checkout."""
    root = _repo_root()
    pid = main_pid.strip() if main_pid else ""
    if not pid or pid in ("0", "n/a"):
        out = subprocess.run(["systemctl", "--user", "show", unit, "-p", "MainPID", "--value"],
                             capture_output=True, text=True, check=False).stdout.strip()
        pid = out
    cwd = _proc_cwd(pid) if pid.isdigit() else ""
    return bool(cwd) and (cwd == root or cwd.startswith(root + os.sep))


def probe_executor_runner_unit() -> str:
    pass
    probe = subprocess.run(
        ["systemctl", "--user", "list-units", "--type=service", "--state=active",
         "--no-legend", "--plain"],
        capture_output=True, text=True, check=False).stdout
    for ln in probe.splitlines():
        parts = ln.split()
        if not (parts and parts[0].startswith("tp-") and "runner.py" in ln):
            continue
        unit = parts[0]
        # Ownership first: a foreign chain's runner is not ours to stop (R47).
        if unit.endswith(".service"):
            if unit_is_ours(unit):
                return unit
            continue
        info = subprocess.run(["systemctl", "--user", "show", unit, "-p", "MainPID", "--value"],
                              capture_output=True, text=True, check=False).stdout.strip()
        if unit_is_ours(unit, info):
            return unit
    orphans = _orphan_runner_pids()
    return f"pid:{orphans[0]}" if orphans else ""


def executor_runner_control(unit: str, action: str) -> None:
    pass
    if not unit:
        return
    if unit.startswith("pid:"):
        if action == "stop":
            for p in _orphan_runner_pids():
                try:
                    os.kill(p, signal.SIGTERM)
                except OSError:
                    pass
            deadline = time.time() + 6
            while time.time() < deadline and _orphan_runner_pids():
                time.sleep(0.5)
            for p in _orphan_runner_pids():
                try:
                    os.kill(p, signal.SIGKILL)
                except OSError:
                    pass
            return
        repo_root = _repo_root()
        boot = (
            "export PATH=" + os.path.expanduser("~") + "/.local/bin:/usr/bin:/bin;"
            # single loader: installer .env then THIS chain's slot env. A bare `. .env` in a
            # worktree is the main database (that file is a symlink), which is how a slot
            # chain's runner was measured holding the main chain's queue.
            f'. "{repo_root}/tools/ops/load_chain_env.sh"; '
            "exec python3 -u executor/runner.py >> " + runner_log_path() + " 2>&1")
        subprocess.run(
            ["systemd-run", "--user", f"--unit={runner_unit_name()}",
             "--working-directory=" + repo_root, "/bin/bash", "-c", boot],
            capture_output=True, text=True, check=False)
        time.sleep(2)
        return
    if action == "stop":
        subprocess.run(["systemctl", "--user", "stop", unit],
                       capture_output=True, text=True, check=False)
        return
    r = subprocess.run(["systemctl", "--user", "start", unit],
                       capture_output=True, text=True, check=False)
    if r.returncode != 0:
        repo_root = _repo_root()
        subprocess.run(
            ["systemd-run", "--user", f"--unit={unit}",
             f"--working-directory={repo_root}", "/bin/bash", "-c",
             f"export PATH={os.path.expanduser('~')}/.local/bin:/usr/bin:/bin;"
             f'. {repo_root}/tools/ops/load_chain_env.sh;'
             "exec python3 -u executor/runner.py "
             f">> {runner_log_path()} 2>&1"],
            capture_output=True, text=True, check=False)
    time.sleep(2)


def trial_run_api(api, case_id, project_id) -> int:
    pass
    r = api.get(f"/projects/{project_id}/envs")
    assert r.status_code == 200, f"环境列表失败: {r.text[:300]}"
    envs = r.json()["data"]
    assert envs, f"项目 {project_id} 无执行环境"
    env_id = envs[0]["id"]
    r = api.post(f"/cases/{case_id}/trial-run", json={"envId": env_id})
    assert r.status_code == 200 and r.json().get("code") == "0", f"试运行失败: {r.text[:300]}"
    return r.json()["data"]["taskId"]
