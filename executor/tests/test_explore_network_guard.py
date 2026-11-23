"""Explore write-guard detection layer (design pack 1/2, executor side).

The site-survey and explore-act browsers must never assume that a read-only
action list is enough: an SPA input can fire an autosave POST that the action
whitelist cannot see. This module closes that blind spot from the network
side, reusing the existing passive response feed (NetObserver) as the single
collection source instead of opening a second CDP listener (R40 single
implementation rule).

Pinned behaviour (adjudicated design doc, three-piece plan piece 1/2):
 * load-phase requests form the per-page "natural request surface" baseline;
 * a write verb (POST/PUT/DELETE/PATCH) seen after an action step and NOT in
 the baseline emits a write_observed event attributed to that step;
 * every response with status >= 400 is recorded as a (url, method, status)
 triple classified error_type=1 (product defect: broken reference or
 failing endpoint);
 * every requestfailed is classified error_type=3 (environment: connection
 reset / timeout / name resolution);
 * recorded urls redact query values, keeping only parameter names.

Zero mock: pure judgement layer over the same entry shapes NetObserver
produces; the fake page only carries the event subscription surface.
"""
from __future__ import annotations

import unittest

from driver.explore_network_guard import ExploreNetworkGuard
from network_guard_fakes import FakeObserver as _FakeObserver
from network_guard_fakes import FakePage as _FakePage


class _FakeFailedRequest:
    def __init__(self, url: str, method: str, failure_text: str):
        self.url = url
        self.method = method
        self.failure = _FakeFailure(failure_text)


class _FakeFailure:
    def __init__(self, text: str):
        self.text = text


def _run(load_entries, steps, failed=()):
    """Drive one guard: baseline -> begin_action(step) -> scan -> account.

    Entries are (url, method[, status]) tuples fed through the fake observer
    between the scan points.
    """
    page, observer = _FakePage(), _FakeObserver()
    guard = ExploreNetworkGuard(page, observer)
    for tup in load_entries:
        observer.add(*tup)
    guard.end_baseline()
    for step_no, entries in steps.items():
        guard.begin_action(step_no)
        for tup in entries:
            observer.add(*tup)
    for failed_req in failed:
        page.fire("requestfailed", failed_req)
    guard.collect()
    return guard.account()


class BaselineSurfaceTest(unittest.TestCase):
    def test_load_phase_posts_are_baseline_not_write_observed(self):
        account = _run([("http://app.test/api/list", "POST"),
                        ("http://app.test/api/me", "GET")], {})
        self.assertEqual(account["writeEvents"], [])
        surface = {(e["method"], e["url"]) for e in account["baselineSurface"]}
        self.assertIn(("POST", "http://app.test/api/list"), surface)


class WriteObservedTest(unittest.TestCase):
    def test_write_after_action_outside_baseline_flagged_with_step(self):
        account = _run([], steps={
            1: [("http://app.test/api/notes/save?draftId=7&token=abc", "POST")]})
        events = account["writeEvents"]
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["stepNo"], 1)
        self.assertEqual(events[0]["method"], "POST")
        # query values are redacted, parameter names kept (operation-facing)
        self.assertEqual(events[0]["url"], "http://app.test/api/notes/save?draftId&token")

    def test_write_seen_in_baseline_is_not_flagged(self):
        account = _run(
            [("http://app.test/api/poll?sid=1", "POST")],
            {2: [("http://app.test/api/poll?sid=9", "POST")]})
        self.assertEqual(account["writeEvents"], [])

    def test_get_after_action_never_flagged(self):
        account = _run([], steps={
            1: [("http://app.test/api/search?word=cat", "GET")]})
        self.assertEqual(account["writeEvents"], [])

    def test_delete_put_patch_are_write_verbs(self):
        account = _run([], steps={
            3: [("http://app.test/api/x/1", "DELETE"),
                ("http://app.test/api/x/2", "PUT"),
                ("http://app.test/api/x/3", "PATCH")]})
        methods = sorted(e["method"] for e in account["writeEvents"])
        self.assertEqual(methods, ["DELETE", "PATCH", "PUT"])


class FailureAttributionTest(unittest.TestCase):
    def test_client_and_server_errors_are_product_defect(self):
        account = _run([], steps={
            1: [("http://app.test/api/ref?id=5", "GET", 404),
                ("http://app.test/api/query", "POST", 500)]})
        failures = {(e["url"], e["method"], e["status"]) for e in account["failures"]}
        self.assertIn(("http://app.test/api/ref?id", "GET", 404), failures)
        self.assertIn(("http://app.test/api/query", "POST", 500), failures)
        self.assertTrue(all(e["errorType"] == 1 for e in account["failures"]))

    def test_request_failed_is_environment(self):
        account = _run([], steps={}, failed=[
            _FakeFailedRequest("http://app.test/api/slow?t=1", "GET",
                               "net::ERR_CONNECTION_RESET")])
        self.assertEqual(len(account["failures"]), 1)
        entry = account["failures"][0]
        self.assertEqual(entry["errorType"], 3)
        self.assertEqual(entry["failureText"], "net::ERR_CONNECTION_RESET")

    def test_status_below_400_not_a_failure(self):
        account = _run([], steps={
            1: [("http://app.test/api/ok", "GET", 304),
                ("http://app.test/api/ok2", "GET", 200)]})
        self.assertEqual(account["failures"], [])


if __name__ == "__main__":
    unittest.main()
