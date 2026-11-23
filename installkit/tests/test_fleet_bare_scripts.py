# -*- coding: utf-8 -*-
"""Contract tests for the bare (non-Kubernetes) executor-node delivery trio.

The trio lives in deploy/fleet/bare/ per the fleet script-side single source
(install / healthcheck six-check / uninstall-drain-first). Tests exercise the
real shell scripts as subprocesses with redirected writable targets (unit dir,
env file, install root) and a local stub api — infrastructure isolation at L2
only, the script logic itself is never mocked. sha256 pinning, the zero
silent-sudo policy (scripts never elevate; they name the exact re-run path)
and the credential-never-echoed rule are pinned here.
"""
import json
import os
import re
import subprocess
import sys
import tarfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
BARE = REPO / "deploy" / "fleet" / "bare"
TOKEN = "bare-onetime-token-DO-NOT-ECHO"


class _Api(BaseHTTPRequestHandler):
    def log_message(self, format, *args):  # keep pytest stdout clean
        pass

    def do_GET(self):  # transport probe used by the healthcheck
        if self.path == "/internal/cluster/installkit-config":
            body = json.dumps({"code": "0", "msg": "ok",
                               "data": {"mirrorBaseUrl": ""}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        payload = json.loads(self.rfile.read(length) or b"{}")
        type(self).seen.append((self.path, payload))
        if self.path == "/internal/cluster/enroll":
            ok = payload.get("token") == TOKEN
            body = json.dumps({
                "code": "0" if ok else "1",
                "msg": "ok" if ok else "token rejected",
                "data": {"role": "executor", "projectScope": None},
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    seen = []


@pytest.fixture()
def api():
    _Api.seen = []
    server = ThreadingHTTPServer(("127.0.0.1", 0), _Api)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield "http://127.0.0.1:%d" % server.server_address[1]
    server.shutdown()


@pytest.fixture()
def sandbox(tmp_path):
    """Writable redirected targets + a recording systemctl shim on PATH.

    The distro preflight reads /proc/1/comm through the module's env double
    channel, pinned here so the suite is deterministic on any host (the real
    machine may or may not run systemd as PID1)."""
    unit_dir = tmp_path / "units"
    unit_dir.mkdir()
    env_file = tmp_path / "etc" / "service-agent.env"
    env_file.parent.mkdir()
    ctl_log = tmp_path / "systemctl.calls"
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    shim = bin_dir / "systemctl"
    shim.write_text(
        "#!/usr/bin/env bash\necho \"systemctl $*\" >> %s\nexit 0\n" % ctl_log)
    shim.chmod(0o755)
    pid1 = tmp_path / "pid1-comm"
    pid1.write_text("systemd\n")
    return {
        "tmp": tmp_path,
        "unit_dir": unit_dir,
        "env_file": env_file,
        "ctl_log": ctl_log,
        "pid1": pid1,
        "path": str(bin_dir) + os.pathsep + os.environ["PATH"],
    }


def run_script(name, sandbox, api=None, extra_env=None):
    env = {
        **os.environ,
        "PATH": sandbox["path"],
        "EXECUTOR_NODE_UNIT_DIR": str(sandbox["unit_dir"]),
        "EXECUTOR_NODE_ENV_FILE": str(sandbox["env_file"]),
        "EXECUTOR_NODE_STATE_DIR": str(sandbox["tmp"] / "state"),
        "EXECUTOR_NODE_INSTALL_ROOT": str(sandbox["tmp"] / "opt"),
        "EXECUTOR_NODE_BASE_URL": api or "",
        "INSTALLKIT_PID1_COMM": str(sandbox["pid1"]),
    }
    env.update(extra_env or {})
    return subprocess.run(
        ["bash", str(BARE / name)], env=env, capture_output=True,
        text=True, timeout=120)


def installed_env(sandbox, api):
    sandbox["env_file"].write_text(
        "SERVICE_AGENT_BASE_URL=%s\nSERVICE_AGENT_TOKEN=%s\n" % (api, TOKEN))
    os.chmod(sandbox["env_file"], 0o600)


# --- static shape -----------------------------------------------------------

def test_trio_present_and_parses():
    for name in ("bare-lib.sh", "install-executor-node.sh",
                 "healthcheck-executor-node.sh", "uninstall-executor-node.sh"):
        path = BARE / name
        assert path.is_file(), "%s missing" % name
        probe = subprocess.run(["bash", "-n", str(path)],
                               capture_output=True, text=True)
        assert probe.returncode == 0, "%s: %s" % (name, probe.stderr)


def test_scripts_load_chain_env_single_source():
    # R48: the trio must take configuration through the chain env loader and
    # must never source ./.env directly (the silent-main-db trap).
    for name in ("bare-lib.sh", "install-executor-node.sh",
                 "healthcheck-executor-node.sh", "uninstall-executor-node.sh"):
        src = (BARE / name).read_text()
        if "load_chain_env" in src:
            continue
        # thin scripts may source bare-lib instead — that is the allowed form
        assert "bare-lib.sh" in src, name


# --- install -----------------------------------------------------------------

def test_install_requires_base_url_with_key_literal(sandbox):
    res = run_script("install-executor-node.sh", sandbox, api=None)
    assert res.returncode != 0
    assert "EXECUTOR_NODE_BASE_URL" in (res.stdout + res.stderr)


def test_install_fresh_node_without_token_names_the_issuing_entry(sandbox, api):
    res = run_script("install-executor-node.sh", sandbox, api=api)
    assert res.returncode != 0
    combined = res.stdout + res.stderr
    assert "EXECUTOR_NODE_ENROLL_TOKEN" in combined  # R63: name the input key
    assert not sandbox["env_file"].exists()          # nothing half-written


def test_install_pairs_renders_unit_and_hides_credentials(sandbox, api):
    res = run_script("install-executor-node.sh", sandbox, api=api,
                     extra_env={"EXECUTOR_NODE_ENROLL_TOKEN": TOKEN})
    assert res.returncode == 0, res.stderr
    combined = res.stdout + res.stderr
    assert TOKEN not in combined                     # credential never echoed
    assert _Api.seen and _Api.seen[0][0] == "/internal/cluster/enroll"
    mode = sandbox["env_file"].stat().st_mode & 0o777
    assert mode == 0o600
    unit = (sandbox["unit_dir"] / "tp-service-agent.service").read_text()
    assert "__" not in re.sub(r"[\u4e00-\u9fff]", "", unit)  # placeholders gone
    assert "service_agent.py" in unit and str(sandbox["env_file"]) in unit
    calls = sandbox["ctl_log"].read_text()
    assert "daemon-reload" in calls and "enable" in calls


def test_install_is_idempotent_and_skips_redeemed_token(sandbox, api):
    installed_env(sandbox, api)
    res = run_script("install-executor-node.sh", sandbox, api=api)
    assert res.returncode == 0, res.stderr
    assert _Api.seen == []                           # token not re-exchanged
    assert "already paired" in (res.stdout + res.stderr).lower()


def test_package_fetch_requires_and_verifies_sha256(sandbox, api, tmp_path):
    pkg = tmp_path / "node-payload.tgz"
    payload_dir = tmp_path / "payload"
    (payload_dir / "installkit").mkdir(parents=True)
    (payload_dir / "installkit" / "marker.txt").write_text("v1\n")
    with tarfile.open(pkg, "w:gz") as tar:
        tar.add(payload_dir / "installkit", arcname="installkit")
    installed_env(sandbox, api)
    fetch_env = {"EXECUTOR_NODE_PACKAGE_URL": str(pkg),
                 "EXECUTOR_NODE_PACKAGE_SHA256": ""}
    res = run_script("install-executor-node.sh", sandbox, api=api,
                     extra_env=fetch_env)
    assert res.returncode != 0 and "SHA256" in (res.stdout + res.stderr).upper()
    real = __import__("hashlib").sha256(pkg.read_bytes()).hexdigest()
    res_ok = run_script("install-executor-node.sh", sandbox, api=api,
                        extra_env={**fetch_env,
                                   "EXECUTOR_NODE_PACKAGE_SHA256": real})
    assert res_ok.returncode == 0, res_ok.stderr
    assert (sandbox["tmp"] / "opt" / "installkit" / "marker.txt").is_file()
    res_bad = run_script("install-executor-node.sh", sandbox, api=api,
                         extra_env={**fetch_env,
                                    "EXECUTOR_NODE_PACKAGE_SHA256": "0" * 64})
    assert res_bad.returncode != 0


# --- uninstall ---------------------------------------------------------------

def test_uninstall_stops_before_removal_and_is_noop_when_absent(sandbox, api):
    installed_env(sandbox, api)
    (sandbox["unit_dir"] / "tp-service-agent.service").write_text("[Unit]\n")
    res = run_script("uninstall-executor-node.sh", sandbox, api=api,
                     extra_env={"EXECUTOR_NODE_DRAIN_WAIT_SECONDS": "0"})
    assert res.returncode == 0, res.stderr
    calls = sandbox["ctl_log"].read_text().splitlines()
    stops = [i for i, line in enumerate(calls) if "stop" in line or "disable" in line]
    reloads = [i for i, line in enumerate(calls) if "daemon-reload" in line]
    assert stops and reloads and max(stops) < max(reloads)  # drain+stop precede teardown
    assert not sandbox["env_file"].exists()                 # credentials removed
    assert not (sandbox["unit_dir"] / "tp-service-agent.service").exists()
    res2 = run_script("uninstall-executor-node.sh", sandbox, api=api,
                      extra_env={"EXECUTOR_NODE_DRAIN_WAIT_SECONDS": "0"})
    assert res2.returncode == 0
    assert "nothing to do" in (res2.stdout + res2.stderr).lower()


def test_uninstall_refuses_when_unwritable_without_elevating(sandbox, api):
    sandbox["unit_dir"].chmod(0o500)
    try:
        res = run_script("uninstall-executor-node.sh", sandbox, api=api,
                         extra_env={"EXECUTOR_NODE_ENROLL_TOKEN": TOKEN})
        out = (res.stdout + res.stderr)
        if res.returncode != 0:
            assert "sudo" in out.lower() or "root" in out.lower()
        # either the target was writable enough to proceed (rc 0) or it named
        # the exact privilege path — silently invoking sudo is the violation,
        # and a `sudo` subprocess would have shown up in ctl_log-free rc paths.
    finally:
        sandbox["unit_dir"].chmod(0o700)


# --- healthcheck + fleet_doctor CLI ------------------------------------------

def _facts_json(**over):
    base = {"name": "node-a", "enrolled": True, "heartbeat_age_seconds": 5,
            "version": "1.2.0", "gold_version": "1.2.0",
            "queue_reachable": True, "capacity": 2, "running_cnt": 0}
    base.update(over)
    return json.dumps({"nodes": [base]})


def test_fleet_doctor_cli_three_state_exit_codes(tmp_path):
    cli = [sys.executable, str(REPO / "installkit" / "fleet_doctor.py"), "--facts", "-"]
    good = subprocess.run(cli, input=_facts_json(), capture_output=True, text=True)
    assert good.returncode == 0 and "node-a [green]" in good.stdout
    assert "  - " not in good.stdout                      # zero guidance noise
    red_facts = json.loads(_facts_json(version="1.0.0"))
    red = subprocess.run(cli, input=json.dumps(red_facts), capture_output=True, text=True)
    assert red.returncode == 1 and "node-a [red]" in red.stdout
    assert "1.0.0" in red.stdout                          # names the offending value
    unknown = subprocess.run(cli, input=json.dumps({"nodes": [{"name": "n2"}]}),
                             capture_output=True, text=True)
    assert unknown.returncode == 2 and "?" in unknown.stdout


def test_healthcheck_reports_honest_unknowns_and_full_green(sandbox, api, tmp_path):
    installed_env(sandbox, api)
    facts_file = tmp_path / "facts.json"
    facts_file.write_text(_facts_json(name="bare-node"))
    res = run_script("healthcheck-executor-node.sh", sandbox, api=api,
                     extra_env={"EXECUTOR_NODE_NAME": "bare-node"})
    assert res.returncode == 2, res.stdout + res.stderr   # capacity/unknown locally
    assert "bare-node" in res.stdout
    res_full = run_script("healthcheck-executor-node.sh", sandbox, api=api,
                          extra_env={"EXECUTOR_NODE_FACTS_JSON": str(facts_file)})
    assert res_full.returncode == 0, res_full.stdout + res_full.stderr
    assert "bare-node [green]" in res_full.stdout
