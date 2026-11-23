"""Shared fakes for the network-guard wiring spectra (R40 single source).

``FakeObserver`` stands in for NetObserver with the same plain-dict entry
shape, and ``FakePage`` records event handlers the way Playwright's page does
(fire() drives the requestfailed branch). Both are imported by the guard-unit,
survey-wiring and action-attribution spectra instead of each re-declaring
them; nothing here touches the browser.
"""
from __future__ import annotations


class FakeObserver:
    """Stands in for NetObserver: holds the same plain-dict entry shape."""

    def __init__(self):
        self.entries = []

    def add(self, url: str, method: str, status: int = 200) -> None:
        self.entries.append({"url": url, "method": method, "status": status})


class FakePage:
    """Records subscriptions; fire() replays an event to its handlers."""

    def __init__(self):
        self.handlers = {}

    def on(self, event: str, handler) -> None:
        self.handlers.setdefault(event, []).append(handler)

    def fire(self, event: str, arg) -> None:
        for handler in self.handlers.get(event, []):
            handler(arg)
