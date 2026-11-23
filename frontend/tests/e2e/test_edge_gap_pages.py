"""Routed-page edge-gap spectra: pages keep the theme single-source gap.

The reported defect (#/ai/chat content frame hugged the viewport with gap 0
while every list page breathes at 12px) is pinned here as a live-browser
measurement: the visible page-card inside .layout-main must clear left,
right and top edges by >= 8px (theme .page-root padding = 12px single
source). The chat page additionally pins the height single source: the page
root must not overflow the main band (the retired 120px magic number pushed
the card 40px short and a wrong token would break again).

Surfaces covered (the three pages this segment fixed):
- /ai/chat (page-root mounted on the existing root),
- /extensions (page-root wrapper around the host card),
- /extraction (page-root wrapper around the host card).

Real chain: browser -> vite 5173 -> admin 8081 -> core 8083, real login,
scratch project seeded through the real API and purged in the tail (no
demo-project pollution). Iron rules: 4xx/5xx + console error/warning +
Issues panel all zero-tolerance; --headless=new posture comes from the
shared conftest browser fixture.
"""
import json
import uuid

import pytest
from conftest import (
    assert_no_errors,
    purge_project,
    seed_project,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)

EDGE_GAP_JS = """
([edgeMin]) => {
  const vis = e => e && e.getBoundingClientRect().width > 0 && e.getBoundingClientRect().height > 0;
  const mainEl = document.querySelector('.layout-main');
  if (!mainEl) return JSON.stringify({state: 'no-layout-main'});
  const cardEls = [...mainEl.querySelectorAll('.page-card')].filter(vis);
  if (!cardEls.length) return JSON.stringify({state: 'no-page-card-in-main'});
  const c = cardEls[0].getBoundingClientRect(), m = mainEl.getBoundingClientRect();
  const g = {left: Math.round(c.left - m.left), right: Math.round(m.right - c.right), top: Math.round(c.top - m.top)};
  const overflow = Math.round(c.bottom - m.bottom);
  return JSON.stringify({state: 'measured', gap: g, minGap: Math.min(g.left, g.right, g.top), edgeMin, overflow});
}
"""


def _assert_edge_gap(page, path: str, edge_min: int = 8):
    """Measure the visible card against .layout-main on a live, data-loaded page."""
    page.goto(f"http://localhost:5173/{path}", wait_until="domcontentloaded")
    expect(page.locator(".layout-main .page-card").first).to_be_visible(timeout=15000)
    # Data-loaded posture matters (S-4 lesson: v-if gated containers hide the
    # defect in an empty state) — callers assert the route's loaded anchor.
    sample = json.loads(page.evaluate(EDGE_GAP_JS, [edge_min]))
    assert sample["state"] == "measured", f"{path}: {sample}"
    assert sample["minGap"] >= edge_min, (
        f"{path}: edge gap {sample['gap']} below {edge_min}px single source "
        f"(theme .page-root padding=12px — the reported hug defect is back)"
    )
    return sample


def test_chat_page_edge_gap_and_height_token(logged_in, page, collectors, api_client):
    """The reported page: card breathes 12px on three sides and never overflows."""
    uniq = uuid.uuid4().hex[:8]
    pid = seed_project(api_client, uniq, "T475GAP")
    try:
        page.goto(f"http://localhost:5173/#/ai/chat?projectId={pid}",
                  wait_until="domcontentloaded")
        # Real data state: the chat kernel mounts (side column anchors prove the
        # page rendered past skeletons, so the measurement is the loaded shape).
        expect(page.locator('[data-test="chat-page-side"]')).to_be_visible(timeout=15000)
        sample = _assert_edge_gap(
            page, f"#/ai/chat?projectId={pid}")
        assert sample["overflow"] <= 0, (
            f"chat card overflows the main band by {sample['overflow']}px — "
            "the height single source (--tp-layout-content-offset) is off"
        )
        assert_no_errors(collectors, "T475 chat edge gap")
    finally:
        purge_project(api_client, pid)


def test_extension_hosts_edge_gap(logged_in, page, collectors, api_client):
    """Merged host pages keep the gap after the page-root wrapper landed."""
    uniq = uuid.uuid4().hex[:8]
    pid = seed_project(api_client, uniq, "T475HOST")
    try:
        for path, anchor in (
            (f"#/extensions?projectId={pid}", '[data-test="extensions-tabs"]'),
            (f"#/extraction?projectId={pid}", '[data-test="extraction-tabs"]'),
        ):
            page.goto(f"http://localhost:5173/{path}", wait_until="domcontentloaded")
            expect(page.locator(anchor)).to_be_visible(timeout=15000)
            _assert_edge_gap(page, path)
        assert_no_errors(collectors, "T475 extension hosts edge gap")
    finally:
        purge_project(api_client, pid)
