"""Contracts for the opt-in observability tier of the delivery compose.

Why these tests exist: the app-tier images only become operationally useful for
an open-source install when Prometheus can scrape the Spring Boot actuators and
Grafana arrives with a working datasource out of the box. These assertions pin
the four properties that make the tier honest rather than decorative:
  - every backend actually exposes /actuator/prometheus (dependency + exposure),
  - the scrape config covers all four services through compose service names,
  - Grafana ships with the Prometheus datasource already provisioned,
  - the tier is opt-in (profile-gated) and every host-facing port or credential
    is an env key with a shipped default, so the default `docker compose up`
    surface never changes.
"""

import re
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
COMPOSE = REPO / "docker-compose.yml"
PROM_CONFIG = REPO / "deploy" / "observability" / "prometheus.yml"
GRAFANA_DS = (REPO / "deploy" / "observability" / "grafana" / "provisioning"
              / "datasources" / "prometheus.yml")

BACKEND_MODULES = ["core", "admin", "api", "ai-service"]
IN_NETWORK_ACTUATORS = {
    "tp-core": 8083,
    "tp-admin": 8081,
    "tp-api": 8082,
    "tp-ai-service": 8084,
}
OBSERVABILITY_SERVICES = ("tp-prometheus", "tp-grafana")


def _compose() -> dict:
    return yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))


def test_backends_ship_prometheus_registry_dependency():
    for module in BACKEND_MODULES:
        pom = (REPO / module / "pom.xml").read_text(encoding="utf-8")
        assert "micrometer-registry-prometheus" in pom, (
            f"{module}: /actuator/prometheus needs the registry on the classpath")


def test_actuator_exposure_includes_prometheus():
    for module in BACKEND_MODULES:
        text = (REPO / module / "src" / "main" / "resources"
                / "application.yml").read_text(encoding="utf-8")
        match = re.search(r"exposure:\s*\n\s*include:[^\n]*", text)
        assert match is not None and "prometheus" in match.group(0), (
            f"{module}: actuator exposure must list the prometheus endpoint")


def test_compose_declares_observability_tier_as_opt_in():
    services = _compose()["services"]
    for name in OBSERVABILITY_SERVICES:
        assert name in services, f"{name}: observability tier is missing"
        assert services[name].get("profiles") == ["observability"], (
            f"{name}: the tier must not join the default up surface")
        image = services[name].get("image", "")
        assert ":" in image, f"{name}: image must pin an explicit version tag"


def test_observability_ports_are_env_keyed_with_shipped_defaults():
    text = COMPOSE.read_text(encoding="utf-8")
    for key in ("PROMETHEUS_HOST_PORT", "GRAFANA_HOST_PORT"):
        assert re.search(rf"\$\{{{key}:-\d+\}}", text), (
            f"{key}: host-facing port must be an env key with a shipped default")


def test_grafana_admin_password_is_env_indirected():
    text = COMPOSE.read_text(encoding="utf-8")
    assert re.search(r"\$\{GRAFANA_ADMIN_PASSWORD:-[^}]+\}", text), (
        "grafana login must come from the install-time .env, never a baked secret")


def test_prometheus_mounts_the_scrape_config():
    assert PROM_CONFIG.is_file(), "the scrape config must live in the repo"
    prom = _compose()["services"]["tp-prometheus"]
    volumes = " ".join(str(v) for v in prom.get("volumes", []))
    assert "deploy/observability/prometheus.yml" in volumes, (
        "the prometheus service must mount the checked-in scrape config")


def test_prometheus_scrapes_every_backend_actuator():
    cfg = yaml.safe_load(PROM_CONFIG.read_text(encoding="utf-8"))
    jobs = cfg["scrape_configs"]
    assert len(jobs) == 1, "one job covering the fleet keeps the tier simple"
    assert jobs[0].get("metrics_path") == "/actuator/prometheus"
    targets = {t for block in jobs[0]["static_configs"] for t in block["targets"]}
    for service, port in IN_NETWORK_ACTUATORS.items():
        assert f"{service}:{port}" in targets, (
            f"{service}:{port} missing from the scrape targets")


def test_grafana_is_provisioned_with_prometheus_datasource():
    doc = yaml.safe_load(GRAFANA_DS.read_text(encoding="utf-8"))
    datasources = doc["datasources"]
    assert isinstance(datasources, list) and datasources, (
        "grafana must ship at least one provisioned datasource")
    prom = [d for d in datasources if d.get("type") == "prometheus"]
    assert prom, "a prometheus datasource must be provisioned by default"
    assert "tp-prometheus" in prom[0]["url"], (
        "the datasource must address the compose service, not the host")
    graf = _compose()["services"]["tp-grafana"]
    volumes = " ".join(str(v) for v in graf.get("volumes", []))
    assert "provisioning" in volumes, (
        "grafana must mount the provisioning directory")
