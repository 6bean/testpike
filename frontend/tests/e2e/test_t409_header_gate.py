""" RED anchor: site-wide table-header single-line DOM gate.

Iron law: a sortable/filter icon on a narrow column squeezes the header cell
into two lines (measured h=46px for 90-100px columns). The gate visits every
static list route declared in src/router/routes.ts (route list parsed from the
router source at collection time - never from memory), measures the rendered
height of each visible ``th .cell`` and fails on any header exceeding the
single-line budget (>26px = wrapped). Violations are printed as a JSON ledger
so the out-turn count is machine-readable for the remediation batch.

Readiness: per page we wait for the layout main region to mount, then for an
el-table header to appear when present; pages without tables yield zero
headers and cannot produce a false positive from stale pre-mount DOM.
"""
import json
import re
from pathlib import Path

import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, logged_in  # noqa: F401  (fixture registration)

pytestmark = pytest.mark.timeout(600)

# Single-line header budget: 14px font + vertical padding renders at ~23px;
# any cell taller than this carries a wrapped label (two-line = ~46px).
SINGLE_LINE_MAX_PX = 26

_ROUTES_TS = Path(__file__).resolve().parents[2] / "src" / "router" / "routes.ts"


def _static_page_paths():
    """All router paths without dynamic segments (pages renderable by URL only)."""
    src = _ROUTES_TS.read_text(encoding="utf-8")
    paths = re.findall(r"path:\s*'([^']+)'", src)
    out = []
    for p in paths:
        if ":" in p:
            continue  # dynamic detail routes need an id, out of gate scope
        if p in ("/", "/login", "/password-expired"):
            continue  # layout root redirect + pre-auth pages have no tables
        out.append("/" + p.lstrip("/"))
    # drop duplicates while keeping declaration order
    seen, ordered = set(), []
    for p in out:
        if p not in seen:
            seen.add(p)
            ordered.append(p)
    return ordered


MEASURE_JS = """
() => {
  const bad = [];
  const cells = document.querySelectorAll('.el-table th .cell');
  cells.forEach(cell => {
    const r = cell.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return;  // hidden clone / not laid out
    const h = Math.round(r.height);
    if (h > %d) {
      const th = cell.closest('th');
      bad.push({
        text: (cell.textContent || '').trim().replace(/\\s+/g, ' ').slice(0, 24),
        height: h,
        colWidth: Math.round((th ? th.getBoundingClientRect().width : r.width)),
        sortable: !!(th && th.getAttribute('class') || '').includes('is-sortable'),
      });
    }
  });
  return bad;
}
""" % SINGLE_LINE_MAX_PX


def test_t409_header_single_line_gate(logged_in):
    """Every static list page: all visible table headers render on a single line."""
    page = logged_in
    ledger = {}
    for path in _static_page_paths():
        page.goto(f"{BASE_URL}/#{path}", wait_until="domcontentloaded")
        # Readiness: the SPA only re-mounts the view on a real reload
        # (hash-only goto keeps the previous view mounted, which made this
        # gate echo a stale violation from the previously visited page in
        # the first out-turn ledger - the "/execution" row was really the
        # "/documents" chunk-count column still mounted).
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(".layout-menu")).to_be_visible(timeout=10000)
        # if the page hosts a table, wait for its header row to lay out
        table = page.locator(".el-table").first
        try:
            table.wait_for(state="visible", timeout=5000)
        except Exception:
            pass  # page without a table: legitimately zero headers
        bad = page.evaluate(MEASURE_JS)
        if bad:
            ledger[path] = bad

    total_cols = sum(len(v) for v in ledger.values())
    # Persist the machine-readable ledger next to /tmp so xdist capture cannot
    # swallow it (the assertion repr truncates).
    Path("/tmp/tp-t409-ledger.json").write_text(
        json.dumps({"pages": len(ledger), "columns": total_cols, "detail": ledger},
                   ensure_ascii=False, indent=1), encoding="utf-8")
    assert not ledger, (
        f"Header wrap: {total_cols} columns across {len(ledger)} pages exceed "
        f"the single-line budget of {SINGLE_LINE_MAX_PX}px"
    )
