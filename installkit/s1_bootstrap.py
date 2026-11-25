# -*- coding: utf-8 -*-
pass
import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import httpx

import resource_tiers
import mq_vhost
import image_relay  # image delivery for the compose stages (accelerator detour)
import host_ports  # host-port band resolution (compose precedence, single source)
import instance_names
import demo_seed

REPO_ROOT = Path(__file__).resolve().parents[1]
COMPOSE_FILE = "docker-compose.yml"

SERVICE_GUIDE = (
    ("admin", "8081", "user-facing management API"),
    ("api", "8082", "external integration entry (executors/installer scripts talk to this one only)"),
    ("core", "8083", "service layer (scheduling/config/audit)"),
    ("ai-service", "18084", "AI orchestration (internal only)"),
)


def load_answers(answers_path):
    pass
    try:
        with open(answers_path, encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def compose_stages(answers_path, repo_root=None):
    pass
    answers = load_answers(answers_path)
    role = answers.get("role") or "platform"
    if role != "platform":
        return []
    root = str(repo_root or REPO_ROOT)
    base = ["docker", "compose", "-f", os.path.join(root, COMPOSE_FILE)]
    stages = [{"phase": "middleware", "cmd": base + ["up", "-d"]}]
    profile_args = []
    for profile in resource_tiers.compose_profiles(answers.get("tier")):
        profile_args += ["--profile", profile]
    if profile_args:
        stages.append({"phase": "services", "cmd": base + profile_args + ["up", "-d"]})
    return stages


def compose_plan(answers_path, repo_root=None):
    pass
    return [stage["cmd"] for stage in compose_stages(answers_path, repo_root=repo_root)]


def _execute(cmd):
    pass
    override = os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip()
    if override and cmd[:2] == ["docker", "compose"]:
        cmd = [override] + cmd[2:]
    return subprocess.run(cmd).returncode


# Output tail kept for classification: docker prints the real cause early and
# repeats it at the end, so a bounded window is enough — the recovery note has
# to name the offending value, not just point back at the console.
_STAGE_TAIL_CHARS = 8000


def _execute_tee(cmd):
    """Run a compose stage, echoing docker output live, and return (rc, tail)."""
    override = os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip()
    if override and cmd[:2] == ["docker", "compose"]:
        cmd = [override] + cmd[2:]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, bufsize=1)
    lines = []
    for line in (proc.stdout or ()):
        sys.stdout.write(line)
        sys.stdout.flush()
        lines.append(line)
    rc = proc.wait()
    return rc, "".join(lines)[-_STAGE_TAIL_CHARS:]


_INPROBE_RE = re.compile(r'service "([^"]+)" didn\'t complete')
_UNHEALTHY_RE = re.compile(r"container ([A-Za-z0-9_.-]+) is unhealthy")


def _surface_dependency_logs(stage_cmd, tail):
    """Print the real stderr of what a failed bring-up stage actually blames.

    Two shapes hide the cause behind a bare marker: a one-shot dependency
    prints `service "X" didn't complete successfully` without its own logs,
    and an app service prints `container Y is unhealthy` without the startup
    error. The stage claims the cause is on screen, so this fetches and
    echoes both (compose logs for the one-shot, docker logs for the container).
    The returned text joins the tail for classification.
    """
    if os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip():
        return ""
    chunks = []
    for name in dict.fromkeys(_INPROBE_RE.findall(tail)):
        probe = list(stage_cmd) + ["logs", "--tail", "40", name]
        chunks.append(_probe_logs(probe, "logs from %s (the failing dependency)" % name))
    for container in dict.fromkeys(_UNHEALTHY_RE.findall(tail)):
        probe = ["docker", "logs", "--tail", "60", container]
        chunks.append(_probe_logs(probe, "logs from %s (the unhealthy service)" % container))
    return "\n".join(chunks)


def _probe_logs(probe, header):
    try:
        out = subprocess.run(probe, capture_output=True, text=True,
                             stderr=subprocess.STDOUT, timeout=60).stdout
    except (OSError, subprocess.TimeoutExpired):
        return ""
    logs = "\n".join(out.splitlines()[-60:])
    if logs.strip():
        print("--- %s ---" % header)
        print(logs)
    return logs


def _run_stage(cmd):
    """Single entry for the compose bring-up stages (returns rc plus captured tail).

    The substitute binary has nothing worth classifying, so that path keeps the
    plain int runner and an empty tail.
    """
    if os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip():
        return _execute(cmd), ""
    return _execute_tee(cmd)


def relay_stage_images(compose_file, profiles=()):
    """Deliver this stage's images before compose starts pulling them.

    No-ops in substitute mode (the test double has no image store) and never
    elevates: it re-tags mirror pulls locally, or merges accelerator entries
    into the daemon config only when that file is already writable.
    """
    if os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip():
        return True
    return image_relay.ensure_for_compose(compose_file, profiles=profiles, printer=print)


def stage_failure_note(tail):
    """Recovery note for a failed bring-up stage (None keeps the generic wording)."""
    import failure_guide
    return failure_guide.note_for_failure(tail)


def four_services_guide(tier=None):
    pass
    source_lines = ["      cd %s && mvn spring-boot:run, or run it resident: java -jar target/%s.jar under a systemd unit"
                    % (name, name) for name, port, purpose in SERVICE_GUIDE]
    if "app" in resource_tiers.compose_profiles(tier):
        lines = ["The four services are already up with the compose app profile (admin/api/core/ai-service; started by the tier projection — this script never runs or stops them for you):",
                 "  - Status check: docker compose ps; readiness probe: curl http://127.0.0.1:<admin host port>/actuator/health"]
        lines.append("  - Developer option (run from source for debugging, not the shipped delivery path; stop the matching compose service first):")
        lines.extend(source_lines)
        return lines
    lines = ["How to start the four services (this script never starts them for you: when a port is occupied it will not touch someone else's service — confirm and start it yourself):",
             "  - Shipped delivery path (recommended one-line start): cd to the repo root && docker compose --profile app up -d, "
             "then check readiness with curl http://127.0.0.1:<admin host port>/actuator/health (host port = APP_ADMIN_HOST_PORT in the install .env, default 8081)"]
    lines.append("  - Developer option (run from source, per service):")
    lines.extend(source_lines)
    return lines


DEFAULT_ADMIN_BASE_URL = "http://localhost:8081"


def resolve_admin_base_url(explicit=""):
    pass
    if (explicit or "").strip():
        return explicit.strip()
    override = (os.environ.get("INSTALLKIT_SEED_BASE_URL") or "").strip()
    if override:
        return override
    try:
        dotenv_map = host_ports.read_dotenv(str(REPO_ROOT))
    except OSError:
        dotenv_map = {}
    port = (host_ports.overlay(dotenv_map).get("APP_ADMIN_HOST_PORT") or "").strip()
    if port:
        return "http://localhost:%s" % port
    return DEFAULT_ADMIN_BASE_URL


def seed_demo_command(base_url=None):
    pass
    url = base_url or resolve_admin_base_url()
    return "python3 installkit/s1_bootstrap.py seed-demo --base-url %s" % url


def admin_ready(base_url, timeout=5.0):
    pass
    try:
        resp = httpx.post(base_url.rstrip("/") + "/api/v1/auth/login",
                          json={"username": demo_seed.env_val(demo_seed.ENV_USER_KEY, default="admin"),
                                "password": demo_seed.env_val(demo_seed.ENV_PASSWORD_KEY)},
                          timeout=timeout)
        resp.raise_for_status()
        return bool(resp.json().get("data", {}).get("accessToken"))
    except Exception:
        return False


# Install-time secret shape rules, single source (R40): consumed by the
# compose-step pre-check here and by the factory-default parity contract
# test. A shipped default that fails the product's own boot-time floor turns
# every untouched fresh install into a crash loop, so both carriers must be
# proven against these rules. Empty values stay legal where the feature has
# an honest fallback (cipher off, compose fallback default).
SECRET_SHAPE_RULES = (
    ("CONFIG_AES_KEY",
     lambda v: re.fullmatch(r"(?i)[0-9a-f]{32}|[0-9a-f]{48}|[0-9a-f]{64}", v) is not None,
     "must be pure hex of exactly 32, 48 or 64 characters (a 16/24/32-byte "
     "AES key); generate a valid one with: openssl rand -hex 32"),
    ("SSO_JWT_SECRET",
     lambda v: len(v) >= 32,
     "must be at least 32 characters (the admin JWT signing floor below that "
     "refuses boot); generate one with: openssl rand -hex 16"),
)


def _secret_keys_note(root):
    """Refuse malformed install-time secrets before any image build.

    Same precedence the containers get (exported env over project .env).
    Returns None when every configured value is well-formed; the note names
    each offending key and its fix command, never a value.
    """
    try:
        dotenv_map = host_ports.read_dotenv(root)
    except OSError:
        dotenv_map = {}
    problems = []
    for key, ok, why in SECRET_SHAPE_RULES:
        value = (os.environ.get(key) or dotenv_map.get(key) or "").strip()
        if value and not ok(value):
            problems.append("%s in .env is not a usable value: it %s." % (key, why))
    if not problems:
        return None
    return ("\n".join(problems)
            + "\nWrite the corrected values to the named keys in .env, then "
              "re-run the same command to resume from the breakpoint. The key "
              "values themselves are never echoed here.")


def cmd_compose(args):
    stages = compose_stages(args.answers_file, repo_root=args.repo_root or None)
    if not stages:
        print("This machine's role is executor-only: skipping the compose bring-up — "
              "this node only needs the engine runtime installed and registered as an executor (use --scenario=scale-out to join the pool)")
        return 0
    root = args.repo_root or str(REPO_ROOT)
    compose_file = os.path.join(root, COMPOSE_FILE)
    if not os.path.exists(compose_file):
        print("compose bring-up failed: %s not found — run this script inside the testPike repository "
              "(or point --repo-root at the repo root)" % compose_file, file=sys.stderr)
        return 1
    print("Bringing up middleware (postgres/redis/rabbitmq/seaweedfs; already in place = idempotent pass)...")
    key_gate = _secret_keys_note(root)
    if key_gate:
        print(key_gate, file=sys.stderr)
        return 1
    # Image delivery is decided here, not by hoping compose can reach the hub:
    # a host with a blocked registry gets the accelerator detour before the
    # first pull is attempted (missing images only, nothing local is touched).
    if not relay_stage_images(compose_file):
        print("middleware images were not delivered — follow the recovery options printed above, then re-run the same command.", file=sys.stderr)
        return 1
    rc, tail = _run_stage(stages[0]["cmd"])
    if rc != 0:
        tail += _surface_dependency_logs(stages[0]["cmd"], tail)
        print("compose bring-up failed (exit code %s) — the docker output above is the reason; "
              "after fixing it, re-run the same command and it resumes from the breakpoint" % rc, file=sys.stderr)
        note = stage_failure_note(tail)
        if note:
            print(note, file=sys.stderr)
        return rc
    print("Middleware ready.")
    if os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip():
        print("MQ vhost reconcile: skipped in compose substitute mode (test shape has no real message broker)")
    else:
        ok, notes = mq_vhost.ensure_vhost()
        for line in notes:
            print(line, file=None if ok else sys.stderr)
        if not ok:
            return 1
    for stage in stages[1:]:
        rc, tail = _run_stage(stage["cmd"])
        if rc != 0:
            tail += _surface_dependency_logs(stage["cmd"], tail)
            print("tier service bring-up failed (exit code %s) — the docker output above is the reason; "
                  "after fixing it, re-run the same command and it resumes from the breakpoint" % rc, file=sys.stderr)
            note = stage_failure_note(tail)
            if note:
                print(note, file=sys.stderr)
            return rc
    answers = load_answers(args.answers_file)
    for line in four_services_guide(tier=answers.get("tier")):
        print(line)
    return 0


def cmd_seed_demo(args):
    pass
    url = resolve_admin_base_url(args.base_url)
    if not admin_ready(url):
        print("admin service not ready (%s: unreachable, not this product's endpoint, or account mismatch) — "
              "skipping the automatic seed this run; installation continues normally." % url)
        print("Once the four services are ready, re-run the command below to seed automatically (reuse-by-name is idempotent; repeating it produces no duplicate data):")
        print("  " + seed_demo_command(url))
        return 0
    try:
        pid, cid = demo_seed.ensure_sample(url)
        journey = demo_seed.ensure_journey(url)
        centers = demo_seed.ensure_centers(url)
    except (Exception, SystemExit) as exc:
        print("demo data seeding did not complete: %s (entries already created are reused by name; demo data is not an installation prerequisite and never blocks it)"
              % exc, file=sys.stderr)
        print("After fixing the cause above, re-run the same command to resume seeding (idempotent: existing entries are reused directly):"
              + seed_demo_command(url), file=sys.stderr)
        return 0
    print("demo data auto-seeding complete: sample project (id=%s) + sample case (id=%s), five-minute journey created %d items, "
          "mid-platform miniature scenarios (order/inventory centers + demo performance scenario) created %d items."
          % (pid, cid, journey.get("created", 0), centers.get("created", 0)))
    print("Experience path: open the frontend project list, enter the sample project and use one-click AI generate -> execute -> report; "
          "the list also shows the five-minute journey items, the Order Center and the Inventory Center "
          "(each carries one runnable API case; the Order Center additionally has a demo performance scenario whose target is the built-in fixture site).")
    return 0


def cmd_storage(args):
    pass
    # Compose-substitute mode (unit/contract tests run install.sh with a fake
    # compose binary): there is no real storage gateway to probe, so this gate
    # short-circuits honestly, exactly like the MQ vhost reconcile above.
    # Without the skip the gate hangs 60s on the S3 probe and then hard-fails
    # the whole s1 scenario in test shape (and on fresh CI runners).
    if os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip():
        print("object storage reconcile: skipped in compose substitute mode (test shape has no real storage gateway)")
        return 0
    answers = load_answers(args.answers_file)
    if answers.get("role", "platform") != "platform":
        print("This machine's role is not platform: the object-storage surface is carried by the platform node — skipping.")
        return 0
    root = args.repo_root or str(REPO_ROOT)
    try:
        # compose precedence: exported band keys (matrix cells, systemd env)
        # must resolve identically here and in docker-compose.yml interpolation
        env = host_ports.overlay(host_ports.read_dotenv(root))
    except OSError:
        print("object storage check failed: repo .env not found (run this inside the repository)", file=sys.stderr)
        return 1
    missing = [k for k in ("S3_ENDPOINT", "S3_BUCKET", "DB_USERNAME", "DB_PASSWORD",
                           "SWF_META_DB_NAME") if not env.get(k)]
    if missing:
        print("object storage check failed: .env missing keys %s (run cp .env.example .env, then fill them in)" % missing,
              file=sys.stderr)
        return 1
    pg = os.environ.get("TP_CONTAINER_PG") or instance_names.name("postgres")
    db, user = env["SWF_META_DB_NAME"], env["DB_USERNAME"]
    # 1) metadata database (same PG instance as the application, separate database)
    q = _run_cap(["docker", "exec", pg, "psql", "-U", user, "-d", "postgres", "-tA", "-c",
                  "SELECT 1 FROM pg_database WHERE datname='%s'" % db])
    if q.returncode != 0:
        print("object storage check failed: PostgreSQL container %s is unreachable (run the compose stage first)" % pg,
              file=sys.stderr)
        return 1
    if q.stdout.strip() != "1":
        c = _run_cap(["docker", "exec", pg, "psql", "-U", user, "-d", "postgres", "-c",
                      "CREATE DATABASE \"%s\" OWNER \"%s\" TEMPLATE template0 "
                      "LC_COLLATE 'C' LC_CTYPE 'C' ENCODING 'UTF8'" % (db, user)])
        if c.returncode != 0:
            print("object storage check failed: metadata database %s creation failed — %s" % (db, c.stderr.strip()[-200:]),
                  file=sys.stderr)
            # Route the raw vendor output through the failure-guide single source
            # so a duplicate-database race teaches keep-or-rename instead of only
            # dumping stderr (families unknown to the guide stay silent here).
            import failure_guide
            guide = failure_guide.note_for_failure(c.stderr, db=db)
            if guide:
                print(guide, file=sys.stderr)
            return 1
        print("metadata database %s created (a separate database in the same instance)" % db)
    else:
        print("metadata database %s already in place" % db)
    # 2) gateway container (compose single source; idempotent)
    rc = _execute(["docker", "compose", "-f", os.path.join(root, COMPOSE_FILE),
                   "up", "-d", "seaweedfs", "seaweedfs-ui-auth"])
    if rc != 0:
        print("object storage check failed: seaweedfs gateway/console auth proxy failed to start (the compose output above is the reason)",
              file=sys.stderr)
        return 1
    # 3) S3 endpoint answers (any HTTP status incl. 403 proves the signature path is live)
    import time
    import urllib.error
    import urllib.request
    deadline = time.time() + 60
    while time.time() < deadline:
        try:
            urllib.request.urlopen(env["S3_ENDPOINT"] + "/", timeout=3)
            break
        except urllib.error.HTTPError:
            break
        except OSError:
            time.sleep(2)
    else:
        print("object storage check failed: %s did not answer within 60 seconds — check docker logs %s"
              % (env["S3_ENDPOINT"], instance_names.name("seaweedfs")), file=sys.stderr)
        return 1
    # 3b) management-console auth gate: the Basic-Auth proxy must answer 401 anonymously
    ui_port = env.get("SWF_UI_FILER_PROXY_HOST_PORT", "9000")
    ui_code = ""
    try:
        import urllib.request as _u
        try:
            _u.urlopen("http://localhost:%s/" % ui_port, timeout=5)
            ui_code = "200"
        except urllib.error.HTTPError as he:
            ui_code = str(he.code)
    except OSError:
        pass
    if ui_code != "401":
        print("object storage check failed: console auth proxy (%s) did not return 401, actual=%s — check the "
              "%s container and SWF_UI_USER/SWF_UI_PASSWORD" % (ui_port, ui_code or "no answer",
              instance_names.name("seaweedfs-ui-auth")),
              file=sys.stderr)
        return 1
    print("console auth in place: anonymous requests get 401 (login at http://localhost:%s / :%s, credentials from .env SWF_UI_*)"
          % (ui_port, env.get("SWF_UI_MASTER_PROXY_HOST_PORT", "9001")))
    # 4) legacy provider value repair (guarded; authoritative semantics = V533 migration)
    _run_cap(["docker", "exec", pg, "psql", "-U", user, "-d", env.get("TP_MAIN_DB", "testpike"),
              "-c", "UPDATE tp_sys_config SET config_value='s3' "
                    "WHERE config_key='storage.provider' AND config_value='minio'"])
    print("object storage ready: the S3 gateway answers; bucket '%s' is created automatically on first app use; "
          "storage.provider=s3 can be hot-switched back to local/aliyun-oss on the system settings page." % env["S3_BUCKET"])
    return 0


def _run_cap(cmd):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=120)


def main(argv=None):
    parser = argparse.ArgumentParser(prog="s1_bootstrap.py",
                                     description="S1 first-install bring-up implement (compose plan / four-services guide / demo data auto-seed)")
    sub = parser.add_subparsers(dest="command", required=True)
    p_compose = sub.add_parser("compose", help="bring up middleware per role and print the four-services guide")
    p_compose.add_argument("--answers-file", required=True)
    p_compose.add_argument("--repo-root", default=str(REPO_ROOT))
    p_compose.set_defaults(func=cmd_compose)
    p_seed = sub.add_parser("seed-demo", help="demo data auto-seed (idempotent; degrades honestly with a re-runnable command when admin is unreachable)")
    p_seed.add_argument("--base-url", default="")
    p_seed.set_defaults(func=cmd_seed_demo)
    p_store = sub.add_parser("storage", help="object-storage one-shot check/self-repair (SeaweedFS + PG metadata)")
    p_store.add_argument("--answers-file", required=True)
    p_store.add_argument("--repo-root", default=str(REPO_ROOT))
    p_store.set_defaults(func=cmd_storage)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
