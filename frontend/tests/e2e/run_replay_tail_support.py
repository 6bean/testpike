"""Single source for the revisit journey steps shared by the login-state
E2E files: opening the row-level replay entry from the run history and
settling the tail window until enough frames are rendered.

Both the probe-revisit journey and the keep-alive journey click the same
``run-history-replay-<token>`` door and then reconcile the DOM frame count
against the stored trail; keeping that flow here stops the two journeys
from drifting apart when the replay furniture changes.
"""

import time

from playwright.sync_api import expect


def open_replay_tail_window(page, run_token):
    """Click the replay entry for one run token and return the tail window
    locator once the frame area has started rendering (or honestly reports
    it is empty)."""
    replay_btn = page.locator(f'[data-test="run-history-replay-{run_token}"]')
    expect(replay_btn).to_be_visible(timeout=15000)
    replay_btn.click()
    window = page.locator('[data-test="run-history-tail-window"]')
    expect(window).to_be_visible(timeout=15000)
    page.wait_for_selector(".task-tail-empty, .task-tail-row", timeout=20000)
    return window


def wait_for_rendered_rows(page, window, target):
    """Poll the tail window within a bounded deadline until it renders at
    least ``target`` frame rows; returns the last observed row count so the
    caller can assert its own equality/upper-bound expectation."""
    dom_rows = 0
    deadline = time.time() + 30
    while time.time() < deadline:
        dom_rows = window.locator(".task-tail-row").count()
        if dom_rows >= target:
            break
        page.wait_for_timeout(500)
    return dom_rows
