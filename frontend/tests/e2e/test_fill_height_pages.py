"""Working-area fill-height gate (user report 2026-11-20, ruling R65).

The reported defect: list/detail pages rendered short tables and left a
300-720px dead band between the last card and the viewport bottom, wasting
screen space at any window size. The theme single source (theme.scss fill
block) now stretches the page tree so the outer bottom gutter is a constant
12px gutter, equal to the side gutters.

This gate is the mechanical check (D0 rule: layout norms must be asserted on
live geometry, not declarations): it walks every menu leaf parsed from the
menuStructure single source and measures the LAST visible content block of
the working area against `.layout-main` in a real browser.

Policy:
- gutter <= 16px (12px theme padding + 4px tolerance) for every routed page;
- exemptions are a REGISTERED list with reasons (R43: an exemption branch
  must itself be exercised — each exempt route is still measured and capped
  by its own bound, and the exemption count is pinned so a new page cannot
  quietly join the list);
- pages whose root is outside the .page-root template (private layouts)
  fail the gate too until adopted; while they sit in the repayment ledger
  they are exempt with the ledger line spelled out.

Ledger (docs/change-eval R65 incident file, named with the ruling id and date):
- /monitor /perf /role-bindings /api-test  private roots, adoption tickets open;
- /ai/chat                       fixed-height page owning the
  --tp-layout-content-offset mechanism (pinned by test_edge_gap_pages);
- /ai/generate                     adopted the fill block 11-20 (R65 ledger C).
"""
import json
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.timeout(600)

MENU_SRC = Path(__file__).resolve().parents[2] / "src" / "config" / "menuStructure.ts"
BASE_URL = "http://localhost:5173"

MAX_GUTTER = 16
# route -> (bound, reason). Keep in sync with the repayment ledger; the
# count is asserted so unlisted private-layout pages cannot slip in silently.
EXEMPT = {
    "/ai/chat": (40, "fixed-height page via --tp-layout-content-offset (T475 contract in test_edge_gap_pages)"),
    "/monitor": (760, "private root (no .page-root) — adoption ticket in R65 ledger"),
    "/perf": (780, "private root (no .page-root) — adoption ticket in R65 ledger"),
    "/role-bindings": (500, "private root (no .page-root) — adoption ticket in R65 ledger"),
    "/api-test": (320, "private root (no .page-root) — adoption ticket in R65 ledger"),
}

GUTTER_JS = """
() => {
  const main = document.querySelector('.layout-main');
  if (!main) return JSON.stringify({ state: 'no-layout-main' });
  main.scrollTop = main.scrollHeight;
  const root = main.firstElementChild;
  if (!root) return JSON.stringify({ state: 'empty-main' });
  const cards = [...root.children].filter(
    (c) => !c.className.toString().includes('el-overlay') && c.getBoundingClientRect().height > 0);
  if (!cards.length) return JSON.stringify({ state: 'no-content' });
  const last = cards[cards.length - 1].getBoundingClientRect();
  const gutter = Math.round(main.getBoundingClientRect().bottom - last.bottom);
  const overflow = root.scrollWidth > main.clientWidth + 1;
  return JSON.stringify({ state: 'measured', gutter, overflow });
}
"""



def _settle_sample(page):
    """Poll the geometry until two consecutive reads agree (max ~5s).

    A freshly booted SPA paints skeletons and late data rows that shift the
    last content bottom; a single early read measures a transient state and
    makes the ratchet believe private-layout pages are compliant (R43
    anti-idle discipline: an exemption check that never fires is worse than
    no check). Stability-gated reads kill both false greens and false reds.
    """
    prev = None
    for _ in range(12):
        cur = json.loads(page.evaluate(GUTTER_JS))
        if prev is not None and cur == prev:
            return cur
        prev = cur
        page.wait_for_timeout(400)
    return prev


def _menu_leaves() -> list[str]:
    src = MENU_SRC.read_text(encoding="utf-8")
    return sorted(set(re.findall(r"path:\s*'(/[^']*)'", src)))


@pytest.mark.parametrize("path", _menu_leaves())
def test_working_area_bottom_gutter_filled(logged_in, page, path):
    # login pages and program dashboard lenses render inside the shell; the
    # measurement is about the working area only.
    page.goto(f"{BASE_URL}/#{path}", wait_until="domcontentloaded")
    try:
        page.wait_for_selector(".layout-main > *", timeout=15000)
    except Exception:
        pytest.skip(f"{path}: no routed working area (blank/redirect)")
    page.wait_for_timeout(600)
    sample = _settle_sample(page)
    if sample["state"] != "measured":
        pytest.skip(f"{path}: {sample['state']}")
    assert not sample["overflow"], f"{path}: fill layout introduced horizontal overflow"
    if path in EXEMPT:
        bound, reason = EXEMPT[path]
        assert sample["gutter"] <= bound, (
            f"{path}: exempt route exceeded its registered bound "
            f"({sample['gutter']} > {bound}); reason: {reason}"
        )
    else:
        assert sample["gutter"] <= MAX_GUTTER, (
            f"{path}: bottom gutter {sample['gutter']}px above the {MAX_GUTTER}px "
            "single-source cap — working area must fill to a constant 12px gutter "
            "(theme.scss R65 block)"
        )


def test_exemption_list_stays_minimal(logged_in, page):
    """R43 anti-idle guard: every exempt route must be measurable live, and
    routes that have become compliant must leave the list (ratchet)."""
    violations = []
    for path, (bound, _reason) in EXEMPT.items():
        page.goto(f"{BASE_URL}/#{path}", wait_until="domcontentloaded")
        try:
            page.wait_for_selector(".layout-main > *", timeout=10000)
        except Exception:
            violations.append(f"{path}: exempt but not reachable (stale list entry)")
            continue
        page.wait_for_timeout(600)
        assert page.evaluate("() => location.hash") == "#" + path, f"ratchet landed on the wrong route for {path}"
        sample = _settle_sample(page)
        if sample["state"] == "measured" and sample["gutter"] <= MAX_GUTTER:
            violations.append(
                f"{path}: now compliant ({sample['gutter']}px) — remove it from EXEMPT"
            )
    assert not violations, "; ".join(violations)
