# -*- coding: utf-8 -*-
"""Contract tests for per-instance container naming and matrix-cell isolation.

A clean-machine matrix cell and the slot stacks share one docker host, while
compose container names are machine-global — hardcoded ``testpike-<svc>``
names make any second stack collide with the first at ``compose up``. These
tests pin the fix: every name flows from the single-source prefix helper, the
compose file interpolates the same key, and the matrix recipe injects the
prefix plus a per-cell host-port band so a cell never lands on the shipped
default ports either.
"""
import importlib.util
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOLS = os.path.join(ROOT, "tools", "verification")
sys.path.insert(0, os.path.join(ROOT, "installkit"))

import instance_names  # noqa: E402

COMPOSE = os.path.join(ROOT, "docker-compose.yml")
ENV_EXAMPLE = os.path.join(ROOT, ".env.example")

# Keys allowed to keep the shipped literal host inside a nested default —
# compose nesting is unsupported there, so the matrix recipe injects the
# whole value instead (see test_cell_command_injects_prefix_and_port_band).
NESTED_DEFAULT_OK = (
    "${APP_DB_URL:-jdbc:postgresql://testpike-postgres",
    "${TP_ADMIN_UPSTREAM:-testpike-admin}",
    "${TP_API_UPSTREAM:-testpike-api}",
    "${S3_BUCKET:-testpike-exec}",
)


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _matrix():
    return _load(os.path.join(TOOLS, "clean_matrix_window.py"), "clean_matrix_window")


def test_helper_default_zero_drift_and_override(monkeypatch):
    monkeypatch.delenv(instance_names.PREFIX_ENV, raising=False)
    assert instance_names.name("postgres") == "testpike-postgres"
    monkeypatch.setenv(instance_names.PREFIX_ENV, "tp-matrix-alpine-lightweight")
    assert instance_names.name("seaweedfs") == "tp-matrix-alpine-lightweight-seaweedfs"
    monkeypatch.setenv(instance_names.PREFIX_ENV, "   ")
    assert instance_names.prefix() == instance_names.DEFAULT_PREFIX


def test_compose_container_names_interpolate_the_prefix():
    with open(COMPOSE, encoding="utf-8") as fh:
        text = fh.read()
    names = re.findall(r"container_name:\s*(\S+)", text)
    assert names, "compose lost its container_name lines"
    for value in names:
        assert value.startswith("${TP_CONTAINER_PREFIX:-testpike}-"), \
            "container_name not interpolated: %s" % value
        assert value.count(":-") == 1, "nested interpolation is unsupported: %s" % value


def test_compose_internal_dns_hosts_interpolate_the_prefix():
    host_token = re.compile(r"testpike-(postgres|redis|rabbitmq|seaweedfs|core|admin|api|ai-service)")
    offenders = []
    with open(COMPOSE, encoding="utf-8") as fh:
        for line in fh:
            if not host_token.search(line):
                continue
            if "container_name:" in line:
                continue  # pinned by the previous test
            if any(ok in line for ok in NESTED_DEFAULT_OK):
                continue  # explicit whole-value injection in cells
            offenders.append(line.strip())
    assert not offenders, "uninterpolated container-name host: %s" % offenders


def _assert_no_literal(path):
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    assert '"testpike-' not in src and "'testpike-" not in src, \
        "%s still hardcodes a container name literal" % path


def test_name_consumers_route_through_the_helper():
    for path in (os.path.join(ROOT, "installkit", "resource_tiers.py"),
                 os.path.join(ROOT, "installkit", "migrate_apply.py"),
                 os.path.join(ROOT, "installkit", "s1_bootstrap.py")):
        _assert_no_literal(path)
        with open(path, encoding="utf-8") as fh:
            assert "instance_names" in fh.read(), "%s bypasses the single source" % path


def test_cell_command_injects_prefix_and_port_band():
    mx = _matrix()
    alpine = {"profile": "alpine", "tier": "lightweight"}
    ubuntu = {"profile": "ubuntu2204", "tier": "standard"}
    shipped_ports = {"15432", "6380", "5672", "15672", "8333", "9000", "9001",
                     "18080", "18081", "18082", "18083", "18084", "8899"}

    def envs(cmd):
        return dict(re.findall(r"-e ([A-Z0-9_]+)=([^\s'\"]+)", cmd))

    a_env = envs(mx.cell_command(alpine))
    u_env = envs(mx.cell_command(ubuntu))
    assert a_env["TP_CONTAINER_PREFIX"] == "tp-matrix-alpine-lightweight"
    assert a_env["APP_DB_URL"] == \
        "jdbc:postgresql://tp-matrix-alpine-lightweight-postgres:5432/testpike"
    assert a_env["TP_ADMIN_UPSTREAM"] == "tp-matrix-alpine-lightweight-admin"
    assert a_env["TP_API_UPSTREAM"] == "tp-matrix-alpine-lightweight-api"
    assert "TP_ADMIN_BASE" in a_env and "TP_FIXTURE_BASE" in a_env

    port_keys = tuple(mx._HOST_PORT_KEYS)
    a_ports = {k: a_env[k] for k in port_keys}
    u_ports = {k: u_env[k] for k in port_keys}
    assert set(a_ports.values()).isdisjoint(shipped_ports)
    assert set(u_ports.values()).isdisjoint(shipped_ports)
    assert set(a_ports.values()).isdisjoint(set(u_ports.values())), \
        "two matrix cells share host ports"


def test_tp_container_prefix_documented():
    with open(ENV_EXAMPLE, encoding="utf-8") as fh:
        text = fh.read()
    assert re.search(r"^TP_CONTAINER_PREFIX=testpike\s*$", text, re.M), \
        "installer-facing key missing from the template"
