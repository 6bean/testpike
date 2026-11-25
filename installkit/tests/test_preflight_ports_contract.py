"""Contract: the installer preflight must probe the *effective* host ports.

A runner that exports the compose band keys without touching .env (the
clean-machine matrix cells do exactly this) previously left the preflight
reporting a literal shipped list - ports no stack in that band ever bound,
and worse, container-internal numbers (8081) instead of the compose host
mappings (18081). Resolution now shares the single source in
installkit/host_ports.py: process env > tree .env > compose shipped
defaults parsed from docker-compose.yml itself (no second literal list).
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "installkit"))

import host_ports  # noqa: E402


def test_shipped_defaults_parsed_from_compose_single_source():
    defaults = host_ports.compose_shipped_defaults(ROOT)
    assert defaults.get("PG_HOST_PORT") == "15432"
    assert defaults.get("APP_ADMIN_HOST_PORT") == "18081"
    assert defaults.get("APP_API_HOST_PORT") == "18082"
    assert defaults.get("SWF_S3_HOST_PORT") == "8333"
    # Every band key resolves from compose - a key with no mapping here is a drift bug.
    missing = [k for k in host_ports.HOST_PORT_KEYS if k not in defaults]
    assert missing == []


def test_effective_resolution_precedence(tmp_path):
    compose = tmp_path / "docker-compose.yml"
    compose.write_text(
        'services:\n  pg:\n    ports:\n      - "${PG_HOST_PORT:-15432}:5432"\n'
        '  admin:\n    ports:\n      - "${APP_ADMIN_HOST_PORT:-18081}:8081"\n',
        encoding="utf-8")
    envfile = tmp_path / ".env"
    envfile.write_text("PG_HOST_PORT=25400\n", encoding="utf-8")

    # process env wins over .env
    pairs = dict(host_ports.effective_host_ports(
        str(tmp_path), environ={"PG_HOST_PORT": "25401"}))
    assert pairs["PG_HOST_PORT"] == "25401"
    # .env wins over compose shipped default
    pairs = dict(host_ports.effective_host_ports(str(tmp_path), environ={}))
    assert pairs["PG_HOST_PORT"] == "25400"
    # absent everywhere falls back to the compose shipped default
    assert pairs["APP_ADMIN_HOST_PORT"] == "18081"


def test_install_sh_preflight_has_no_literal_port_list():
    text = open(os.path.join(ROOT, "installkit", "install.sh"), encoding="utf-8").read()
    assert not re.search(r"--ports=[\d,]+", text), \
        "install.sh must not hardcode probe ports (R48: resolve band keys via single source)"
    assert "--ports=auto" in text


def test_pkg_probe_auto_flag_resolves_without_occupancy_failures(tmp_path):
    # --ports=auto is accepted and prints one report line per resolvable key.
    import subprocess
    script = os.path.join(ROOT, "installkit", "pkg_probe.py")
    proc = subprocess.run([sys.executable, script, "preflight", "--require=",
                           "--ports=auto"],
                          capture_output=True, text=True, timeout=60)
    assert proc.returncode == 0
    resolved = {port for _, port in host_ports.effective_host_ports(ROOT)}
    assert any(("port %s" % port) in proc.stdout for port in resolved)


def test_pkg_probe_source_ships_english_only():
    """The preflight probe ships English-only operator text (no i18n track)."""
    src = open(os.path.join(ROOT, "installkit", "pkg_probe.py"),
               encoding="utf-8").read()
    offenders = [line for line in src.splitlines()
                 if any("\u4e00" <= ch <= "\u9fff" for ch in line)]
    assert not offenders, (
        "install surface must not carry CJK operator text; offending lines: %s"
        % offenders[:3])
