"""Write-guard wiring through the site-survey path (design piece 1/2 wiring).

The detection layer (driver.explore_network_guard) classifies traffic; this
test pins that the classification actually reaches the survey accounting:

  * the BFS loop reads a per-page network window from the browser protocol
    (optional ``network_account`` — a browser without it keeps working);
  * a page whose load-time surface contains a write verb, or that fired a
    write after an action, is marked snapshot-only and its candidate rows are
    annotated, so the risk ledger can be upserted downstream;
  * every >=400 / requestfailed triple rides the page frame (interface health
    card and error_type attribution consume it later);
  * the worker page-frame detail json carries writeEvents/netFailures/
    snapshotOnly so the single step door relays them to core.

Fake browsers hold plain dicts only — no network, no mock framework.
"""
from __future__ import annotations

import unittest

from driver.explore_network_guard import ExploreNetworkGuard
from driver.site_survey_loop import run_site_survey
from driver.webdriver import (
    RunOutcome,  # noqa: F401  (import smoke: wiring layer types)
)
from site_survey_worker import _page_frame_detail

BASE = "http://t.example/pr/index.html"


from network_guard_fakes import FakeObserver as _FakeObserver
from network_guard_fakes import FakePage as _FakePage


def _guard():
    return ExploreNetworkGuard(_FakePage(), _FakeObserver())


class _WriteRiskBrowser:
    """In-memory site with one autosave page (POST in the load surface)."""

    def __init__(self, accounts):
        self._accounts = accounts
        self._index = 0

    def goto(self, url):
        self._index += 1
        return url

    def wait_network_idle(self):
        pass

    def network_account(self):
        return self._accounts[min(self._index, len(self._accounts)) - 1]

    def link_anchors(self):
        return []

    def has_password_input(self):
        return False

    def collect_candidates(self):
        return [{"elementId": "e1", "role": "button"}]


class _LegacyBrowser:
    """Survey browser without the network protocol (zero-regression face)."""

    def goto(self, url):
        return url

    def wait_network_idle(self):
        pass

    def link_anchors(self):
        return []

    def has_password_input(self):
        return False

    def collect_candidates(self):
        return [{"elementId": "e1", "role": "button"}]


def _pages():
    return {BASE: _WriteRiskBrowser([{
        "baselineSurface": [{"url": "http://t.example/api/save?token", "method": "POST",
                             "status": 200},
                            {"url": "http://t.example/api/list?page", "method": "GET",
                             "status": 500}],
        "writeEvents": [],
        "writeSurface": [{"url": "http://t.example/api/save?token", "method": "POST",
                          "status": 200}],
        "failures": [{"url": "http://t.example/api/list?page", "method": "GET",
                      "status": 500, "errorType": 1, "failureText": None},
                     {"url": "http://t.example/api/gone", "method": "GET",
                      "status": 404, "errorType": 1, "failureText": None}],
    }])}


def _rules(start=BASE, **over):
    rules = {"startUrl": start, "maxPages": 5, "intervalMs": 0}
    rules.update(over)
    return rules


class LoopWiringTest(unittest.TestCase):
    def test_write_risk_page_marks_frame_and_rows(self):
        account = run_site_survey(_rules(), _pages()[BASE])
        # single page run: frame carries the network window
        frame = account["journal"][0]
        self.assertEqual(len(frame["writeEvents"]) + len(frame["writeSurface"]), 1)
        self.assertTrue(frame["snapshotOnly"])
        self.assertEqual(len(frame["netFailures"]), 2)
        self.assertEqual(frame["netFailures"][0]["errorType"], 1)
        row = account["candidate_rows"][0]
        self.assertEqual(row["probeMode"], "snapshot-only")
        # tallies stay untouched: a risk page is not a failed page
        self.assertEqual(account["pages_ok"], 1)
        self.assertEqual(account["pages_failed"], 0)

    def test_legacy_browser_without_protocol_still_surveys(self):
        account = run_site_survey(_rules(), _LegacyBrowser())
        frame = account["journal"][0]
        self.assertEqual(frame["status"], "ok")
        self.assertFalse(frame["snapshotOnly"])
        self.assertEqual(frame["netFailures"], [])

    def test_clean_page_frame_carries_no_risk_rows(self):
        account = run_site_survey(_rules(), _pages_ok_browser())
        frame = account["journal"][0]
        self.assertFalse(frame["snapshotOnly"])
        self.assertEqual(frame["writeSurface"], [])
        self.assertNotIn("probeMode", account["candidate_rows"][0])

    def test_account_read_failure_never_crashes_the_run(self):
        class Broken(_LegacyBrowser):
            def network_account(self):
                raise RuntimeError("observer blew up")

        account = run_site_survey(_rules(), Broken())
        self.assertEqual(account["journal"][0]["status"], "ok")


class _OkBrowserWithAccount(_WriteRiskBrowser):
    """Clean page: GET-only surface, no failures."""

    def __init__(self):
        super().__init__([{
            "baselineSurface": [{"url": "http://t.example/api/list?page",
                                 "method": "GET", "status": 200}],
            "writeEvents": [], "writeSurface": [], "failures": []}])


def _pages_ok_browser():
    return _OkBrowserWithAccount()


class WorkerDetailTest(unittest.TestCase):
    def test_frame_detail_carries_network_window(self):
        frame = {"url": BASE, "pageKey": "pr/index", "status": "ok", "note": "",
                 "candidates": 1,
                 "writeSurface": [{"url": "http://t.example/api/save",
                                   "method": "POST", "status": 200}],
            "writeEvents": [], "netFailures": [
                {"url": "http://t.example/api/list?page", "method": "GET",
                 "status": 500, "errorType": 1, "failureText": None}],
            "snapshotOnly": True}
        detail = _page_frame_detail(frame, {"counters": {}}, pool=None)
        self.assertTrue(detail["snapshotOnly"])
        self.assertEqual(detail["writeSurface"], frame["writeSurface"])
        # empty buckets never ride the payload (light-payload discipline)
        self.assertNotIn("writeEvents", detail)
        self.assertEqual(detail["netFailures"][0]["status"], 500)

    def test_clean_frame_detail_stays_light(self):
        frame = {"url": BASE, "status": "ok", "note": "", "candidates": 0,
                 "writeSurface": [], "writeEvents": [], "netFailures": [],
                 "snapshotOnly": False}
        detail = _page_frame_detail(frame, {"counters": {}}, pool=None)
        self.assertNotIn("snapshotOnly", detail)
        self.assertNotIn("netFailures", detail)


class GuardPageWindowTest(unittest.TestCase):
    def test_per_page_window_isolates_surface_and_writes(self):
        observer = _FakeObserver()
        guard = ExploreNetworkGuard(_FakePage(), observer)
        guard.begin_page(BASE)
        observer.add("http://t.example/api/save?token", "POST")
        page1 = guard.page_account()
        self.assertEqual(len(page1["writeSurface"]), 1)
        self.assertTrue(guard.has_write_risk())

        guard.begin_page("http://t.example/pr/p2.html")
        observer.add("http://t.example/api/list?page", "GET")
        page2 = guard.page_account()
        self.assertEqual(page2["writeSurface"], [])
        self.assertEqual(len(page1["writeSurface"]), 1)

    def test_account_keeps_session_totals(self):
        observer = _FakeObserver()
        guard = ExploreNetworkGuard(_FakePage(), observer)
        guard.begin_page(BASE)
        observer.add("http://t.example/api/x?a", "GET", 503)
        account = guard.account()
        self.assertEqual(account["failures"][0]["errorType"], 1)
        self.assertEqual(len(guard.page_account()["failures"]), 1)


if __name__ == "__main__":
    unittest.main()
