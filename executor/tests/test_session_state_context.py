

import json

import pytest

from driver.session_context import storage_state_kwargs
from driver import webdriver


def test_core_refusal_is_relayed_verbatim():
    """Unresolvable selector: core sends {stateName, refusal} with no material.

    The precise remedy (row absent / ambiguous role listing candidates / token
    kind) is worded at the single policy source in core; the executor must
    relay that wording verbatim instead of degrading it to the generic
    "no snapshot content" text, and must still stop the run (never anonymous).
    """
    with pytest.raises(ValueError) as exc:
        storage_state_kwargs({"stateName": "ghost", "storageState": None,
                              "refusal": "登录态「ghost」在本项目不存在，请先在登录态账本采集或更换名称。"})
    assert "登录态账本" in str(exc.value), str(exc.value)


def test_no_channel_keeps_legacy_kwargs():
    assert storage_state_kwargs(None) == {}
    assert storage_state_kwargs({}) == {}


def test_json_document_envelope_becomes_storage_state_kwarg():
    snapshot = {"cookies": [{"name": "S", "value": "v", "domain": "shop.example",
                             "path": "/", "secure": True, "expires": -1}],
                "origins": []}
    kwargs = storage_state_kwargs({
        "stateName": "approver",
        "kind": "storage_state",
        "storageState": json.dumps(snapshot),
    })
    assert kwargs == {"storage_state": snapshot}


def test_object_form_storage_state_passes_through():
    snapshot = {"cookies": [], "origins": [{"origin": "https://a.example", "localStorage": []}]}
    kwargs = storage_state_kwargs({"kind": "storage_state", "storageState": snapshot})
    assert kwargs == {"storage_state": snapshot}


def test_malformed_envelope_raises_plain_value_error():
    with pytest.raises(ValueError) as err:
        storage_state_kwargs({"stateName": "x", "kind": "storage_state"})
    assert "x" in str(err.value)
    with pytest.raises(ValueError):
        storage_state_kwargs({"storageState": "{not json"})


class _FakeContext:
    def __init__(self, sink):
        self._sink = sink

    def route(self, *args, **kwargs):  # pragma: no cover - mock mount guard
        self._sink.setdefault("routes", []).append(args)

    def add_init_script(self, *args, **kwargs):  # pragma: no cover
        pass


class _FakeBrowser:
    def __init__(self):
        self.context_kwargs = []

    def new_context(self, **kwargs):
        self.context_kwargs.append(kwargs)
        return _FakeContext(self.context_kwargs)

    def close(self):
        pass


class _FakePlaywright:
    def stop(self):
        pass


class _FakeSyncApi:
    """Stand-in for the sync_playwright() handle (start() returns the kernel)."""

    @staticmethod
    def start():
        return _FakePlaywright()


def _start_executor(monkeypatch, session_state):
    captured = _FakeBrowser()
    monkeypatch.setattr(webdriver, "_launch_kernel", lambda pw, name, headless: captured)
    monkeypatch.setattr("playwright.sync_api.sync_playwright",
                        lambda: _FakeSyncApi(),
                        raising=True)
    executor = webdriver.WebDriverExecutor(
        env_base_url="http://shop.example", session_state=session_state)
    executor.start()
    return executor, captured


def test_channel_reaches_new_context_once(monkeypatch):
    snapshot = {"cookies": [{"name": "S", "value": "v"}], "origins": []}
    executor, captured = _start_executor(
        monkeypatch, {"kind": "storage_state", "storageState": json.dumps(snapshot)})
    assert len(captured.context_kwargs) == 1
    assert captured.context_kwargs[0]["storage_state"] == snapshot
    executor.stop()


def test_absent_channel_leaves_new_context_untouched(monkeypatch):
    executor, captured = _start_executor(monkeypatch, None)
    assert len(captured.context_kwargs) == 1
    assert "storage_state" not in captured.context_kwargs[0]
    executor.stop()
