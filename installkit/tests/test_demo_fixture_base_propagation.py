# -*- coding: utf-8 -*-
"""T293c traffic-light gap: the fixture base address must flow from one config key.

The demo seeds build every case URL from the built-in fixture site address.
Before this pack only the perf scenario consumed ``TP_FIXTURE_BASE``; the
sample environment ``baseUrl`` and the data-factory pool ``url`` were frozen
literals, so a slot stack that re-publishes the fixture port (compose
``TP_FIXTURE_HOST_PORT``) could never run the seeded cases from a container —
the seeded rows pointed at an address nobody owns.

Contract pinned here: one helper resolves the base (env key with the shipped
default), and every URL-bearing seed payload consumes it, host and port both.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import demo_seed

SHIPPED_DEFAULT = "http://127.0.0.1:8899"


def _patched(monkeypatch, value):
    monkeypatch.delenv("TP_FIXTURE_BASE", raising=False)
    if value is not None:
        monkeypatch.setenv("TP_FIXTURE_BASE", value)


def test_fixture_base_helper_reads_the_env_key_with_shipped_default(monkeypatch):
    _patched(monkeypatch, None)
    assert demo_seed.fixture_base() == SHIPPED_DEFAULT
    _patched(monkeypatch, "http://host.docker.internal:8895")
    assert demo_seed.fixture_base() == "http://host.docker.internal:8895"


def test_center_environment_base_url_follows_the_configured_base(monkeypatch):
    """Environment payload carries the host+port, not just the path — cases
    resolve their target through the environment profile (SSRF red line)."""
    _patched(monkeypatch, "http://host.docker.internal:8895")
    payload = demo_seed.env_payload()
    assert payload["baseUrl"] == "http://host.docker.internal:8895"


def test_data_factory_pool_url_follows_the_configured_base(monkeypatch):
    _patched(monkeypatch, "http://host.docker.internal:8895")
    content = demo_seed.json.loads(demo_seed.data_factory_payload(1, 2)["content"])
    assert content["url"] == "http://host.docker.internal:8895/api/order"


def test_perf_scenario_and_environment_share_one_base_source(monkeypatch):
    _patched(monkeypatch, "http://host.docker.internal:8895")
    env_base = demo_seed.env_payload()["baseUrl"]
    perf = demo_seed.perf_scenario_payload(1, 2)
    step_url = perf["steps"][0].get("url") or perf["steps"][0].get("urlPath")
    assert step_url and step_url.startswith(env_base)


def test_shipped_defaults_keep_the_zero_config_install_unchanged(monkeypatch):
    """A fresh install overrides nothing: every face must still aim at the
    published default port so 'install and run' keeps working with zero keys."""
    _patched(monkeypatch, None)
    assert demo_seed.env_payload()["baseUrl"] == SHIPPED_DEFAULT
    content = demo_seed.json.loads(demo_seed.data_factory_payload(1, 2)["content"])
    assert content["url"] == SHIPPED_DEFAULT + "/api/order"
