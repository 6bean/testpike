"""Contracts for the executor service surface on the containerized stack.

Why these tests exist: the demo-seeded cases only prove "green/red light"
after a real install if the stack itself ships the executor (the single
execution channel, protocol docs/03) and the fixture target site it calls.
The full-function tier must therefore project to a compose profile that
brings exactly those two services, the executor image must bake no
credentials, and the fixture site must bind a remappable port so parallel
slots never collide on the shipped default.
"""

import os
import re
import subprocess
import sys
import time
from pathlib import Path

import requests
import yaml

REPO = Path(__file__).resolve().parents[2]
COMPOSE = REPO / "docker-compose.yml"
DOCKERFILE_EXECUTOR = REPO / "deploy" / "docker" / "Dockerfile.executor"
SERVE_FIXTURES = REPO / "executor" / "serve_fixtures.py"
ENV_EXAMPLE = REPO / ".env.example"

sys.path.insert(0, str(REPO / "installkit"))
import resource_tiers  # noqa: E402


def _services():
    return yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))["services"]


def test_compose_declares_executor_and_fixture_services():
    svcs = _services()
    assert "tp-executor" in svcs, "full tier cannot run seeded cases without the executor"
    assert "tp-fixtures" in svcs, "seeded cases point at the fixture target site"
    assert svcs["tp-executor"]["profiles"] == ["executor"]
    assert svcs["tp-fixtures"]["profiles"] == ["executor"]


def test_executor_service_reaches_product_through_api_only():
    """Protocol red line: executors talk to /internal/executor on the api
    service and must never be wired straight to core."""
    env = _services()["tp-executor"]["environment"]
    # instance isolation package (992ca9188): DNS hosts became
    # ${TP_CONTAINER_PREFIX:-testpike}-* interpolations; shipped default
    # resolves to the same testpike-api name, so the red line is unchanged.
    assert env["EXECUTOR_API_URL"] == \
        "http://${TP_CONTAINER_PREFIX:-testpike}-api:8082"
    deps = _services()["tp-executor"].get("depends_on", {})
    assert "testpike-api" not in env.get("CORE_BASE_URL", "")
    assert any(name.startswith("tp-api") or "testpike-api" in name for name in deps)


def test_executor_surface_bakes_no_credentials():
    """Same red line as the backend images: real values come from the
    install .env at runtime, never from committed files."""
    block = COMPOSE.read_text(encoding="utf-8")
    svc_dump = yaml.safe_dump(_services()["tp-executor"])
    for secret in ("testpike_dev", "dev-only-change-me", "dev-hmac-change-me"):
        assert secret not in svc_dump, f"{secret} must stay a shipped default, not a value"
    df = DOCKERFILE_EXECUTOR.read_text(encoding="utf-8")
    assert not re.search(r"COPY[^\n]*\.env", df), "no env file may be copied into the image"
    assert "testpike/executor:${TP_IMAGE_TAG:-local}" in block


def test_executor_dockerfile_is_layered_nonroot_and_pip_pinned_to_requirements():
    df = DOCKERFILE_EXECUTOR.read_text(encoding="utf-8")
    assert "python:3.11" in df
    assert "requirements.txt" in df, "dependency install must consume the single-source list"
    assert "USER " in df and "root" not in df.split("USER ")[-1], "runtime user is non-root"
    assert "runner.py" in df, "the claim loop is the container entrypoint"


def test_executor_container_can_resolve_host_published_fixture_port():
    """Seeded case URLs point at the host-published fixture port, so the
    executor needs the host gateway mapping to reach them in-network."""
    svcs = _services()
    hosts = svcs["tp-executor"].get("extra_hosts", [])
    assert any("host.docker.internal" in h and "host-gateway" in h for h in hosts)


def test_fixture_service_publishes_remappable_host_port():
    ports = _services()["tp-fixtures"]["ports"]
    assert "${TP_FIXTURE_HOST_PORT:-8899}:8899" in ports


def test_full_tier_projects_executor_profile_light_and_standard_unchanged():
    """Tier projection is the single source s1_bootstrap consumes; shipped
    tiers must not drift when the executor surface lands."""
    assert resource_tiers.compose_profiles("lightweight") == []
    assert resource_tiers.compose_profiles("standard") == ["app"]
    assert resource_tiers.compose_profiles("full") == ["app", "observability", "executor"]


def test_profile_membership_matches_tier_projection():
    """Every service tagged under the executor profile is exactly the set
    the full tier promises, and nothing else gains the tag."""
    svcs = _services()
    tagged = sorted(name for name, spec in svcs.items() if "executor" in (spec.get("profiles") or []))
    assert tagged == ["tp-executor", "tp-fixtures"]


def test_env_template_exposes_fixture_port_key_exactly_once():
    lines = [ln.strip() for ln in ENV_EXAMPLE.read_text(encoding="utf-8").splitlines()]
    assert sum(1 for ln in lines if ln.startswith("TP_FIXTURE_HOST_PORT=")) == 1
    assert sum(1 for ln in lines if ln.startswith("TP_IMAGE_TAG=")) == 1, \
        "duplicate template keys fork the shipped-default source"


def test_fixture_site_honours_FIXTURE_PORT_env(tmp_path):
    """Functional probe (not a static grep): boot the site on an off-default
    port and require the shipped /login page over the real socket."""
    port = 18899
    env = dict(os.environ, FIXTURE_PORT=str(port))
    proc = subprocess.Popen(
        [sys.executable, str(SERVE_FIXTURES)], env=env,
        cwd=str(SERVE_FIXTURES.parent),
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.time() + 8
        last = None
        while time.time() < deadline:
            try:
                resp = requests.get(f"http://127.0.0.1:{port}/login", timeout=1)
                if resp.status_code == 200 and "testPike" in resp.text:
                    return
                last = f"status={resp.status_code}"
            except Exception as exc:  # noqa: BLE001 - poll loop until deadline
                last = str(exc)
            time.sleep(0.3)
        raise AssertionError(f"fixture site never served /login on FIXTURE_PORT={port}: {last}")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
