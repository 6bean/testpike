"""Slot-aware default for the executor protocol entry URL.

A worktree chain that forgot to export EXECUTOR_API_URL silently registered
against the main chain's api port, poisoning the main ledger with an
offline row (observed 2026-11-09). The default must derive from the slot's
exported API_PORT (single install-config source, R48), while an explicit
EXECUTOR_API_URL still wins.
"""
import os

from executor.config import ExecutorConfig


def _clean_env(monkeypatch):
    for key in ("EXECUTOR_API_URL", "API_PORT"):
        monkeypatch.delenv(key, raising=False)


def test_default_derives_from_slot_api_port(monkeypatch):
    _clean_env(monkeypatch)
    monkeypatch.setenv("API_PORT", "8282")
    cfg = ExecutorConfig()
    assert cfg.api_base_url == "http://localhost:8282"


def test_explicit_url_overrides_api_port(monkeypatch):
    _clean_env(monkeypatch)
    monkeypatch.setenv("API_PORT", "8282")
    monkeypatch.setenv("EXECUTOR_API_URL", "http://exec-host:9999")
    cfg = ExecutorConfig()
    assert cfg.api_base_url == "http://exec-host:9999"


def test_neither_var_falls_back_to_shipped_default(monkeypatch):
    _clean_env(monkeypatch)
    cfg = ExecutorConfig()
    assert cfg.api_base_url == "http://localhost:8082"
