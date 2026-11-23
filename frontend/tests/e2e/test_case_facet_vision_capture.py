
import json
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
for _p in (os.path.normpath(os.path.join(_HERE, "..", "..", "..", "scripts")), _HERE):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import vision_rig  # noqa: E402

from test_case_provenance_spectrum import (  # noqa: E402
    FACET_BADGE, GRADE_BADGE, seed_provenance, _open_list, _pick)

OUT = os.path.expanduser("~/.hermes/cache/ux-audit/case-facet")
CDP_PORT = int(os.environ.get("TP_CASEFACET_CDP", "9347"))

# Rect sources for the scoring sidecar: every filter-band control, the
# trailing view-state slot, the badges and the pagination row.
_FACTS_JS = """(() => {
  const rects = {};
  const put = (k, sel) => {
    const el = document.querySelector(sel);
    if (!el) { rects[k] = null; return; }
    const b = el.getBoundingClientRect();
    rects[k] = {x: Math.round(b.x), y: Math.round(b.y),
                w: Math.round(b.width), h: Math.round(b.height)};
  };
  put('facetSelect', '[data-test="case-filter-facet"]');
  put('evidenceSelect', '[data-test="case-filter-evidence"]');
  put('filterBar', '.filter-bar');
  put('trailingActions', '.filter-bar-actions');
  put('table', '[data-test="case-table"]');
  put('pagination', '[data-test="case-pagination"]');
  const badge = document.querySelector('[data-test="case-facet-badge"]');
  put('firstFacetBadge', '[data-test="case-facet-badge"]');
  rects.facetBadgeCount = document.querySelectorAll('[data-test="case-facet-badge"]').length;
  rects.evidenceBadgeCount = document.querySelectorAll('[data-test="case-evidence-badge"]').length;
  rects.rowCount = document.querySelectorAll('[data-test="case-table"] tbody tr.el-table__row').length;
  rects.pageUrl = location.href;
  // Filter-band rendered form: y-centres of the two quick filters and the
  // trailing slot decide "one row" vs "vertical stack" (T659 posture).
  const yc = (r) => r ? r.y + r.h / 2 : null;
  rects.bandSameRow = (() => {
    const a = rects.facetSelect, b2 = rects.evidenceSelect;
    if (!a || !b2) return null;
    return Math.abs(yc(a) - yc(b2)) <= Math.min(a.h, b2.h) * 0.6;
  })();
  return rects;
})()"""


def _face(page, prefix, name, tag):
    path = vision_rig.capture_face(page, OUT, prefix, name)
    facts = page.evaluate(_FACTS_JS)
    facts["frame_health"] = vision_rig.frame_health(path)
    with open(os.path.join(OUT, f"{prefix}-{name}-facts.json"), "w",
              encoding="utf-8") as fh:
        json.dump(facts, fh, ensure_ascii=False, indent=1)
    print(f"[vision] face={prefix}-{name} rows={facts.get('rowCount')} "
          f"sameRow={facts.get('bandSameRow')} url={facts.get('pageUrl')}")
    return path


def _apply_filters(page):
    _pick(page, "case-filter-facet", FACET_BADGE["chat"])
    _pick(page, "case-filter-evidence", GRADE_BADGE[1])
    page.wait_for_timeout(500)


def drive_vision(page, fx):
    _, prov = fx
    shots = []
    for theme in ("dark", "light"):
        vision_rig.set_theme(page, theme)
        vision_rig.open_route(page, "/#/dashboard")
        _open_list(page, prov)
        shots.append(_face(page, f"case-facet-{theme}", "default", "default"))
        _apply_filters(page)
        rows = page.locator('[data-test="case-table"] tbody tr.el-table__row')
        assert rows.count() == 2, f"facet∧grade cell expected 2 rows, got {rows.count()}"
        shots.append(_face(page, f"case-facet-{theme}", "filtered", "filtered"))
    assert len(shots) == 4
    print(f"[vision] SHOTS_DONE={len(shots)}")


def drive_ux(page, fx):
    _, prov = fx
    codes = []
    for theme in ("dark", "light"):
        vision_rig.set_theme(page, theme)
        vision_rig.open_route(page, "/#/dashboard")
        _open_list(page, prov)
        for posture in ("default", "filtered"):
            if posture == "filtered":
                _apply_filters(page)
            rc, verdict = vision_rig.run_ux_gate(
                CDP_PORT, f"{OUT}/case-facet-{theme}-{posture}-ux.json")
            print(f"[ux] theme={theme} posture={posture} rc={rc} {verdict}")
            codes.append(rc)
    reds = sum(1 for c in codes if c != 0)
    print(f"[ux] UX_REDS={reds} of {len(codes)} runs")
    assert reds == 0, f"experience gate red in {reds} postures (see {OUT})"


def _seed():
    api = vision_rig.conftest_client(delete_timeout=60)
    prov = seed_provenance(api)
    return api, prov


def _purge(fx):
    api, prov = fx
    try:
        from conftest import purge_project
        purge_project(api, prov["pid"])
        print(f"[cleanup] project={prov['pid']} purged")
    except Exception as exc:
        print(f"[cleanup] purge door failed: {exc}")


def test_case_facet_vision_and_ux_capture():
    os.makedirs(OUT, exist_ok=True)
    saved = list(sys.argv)
    try:
        for mode in ("vision", "ux"):
            sys.argv = ["case-facet-capture", mode]
            vision_rig.run_capture_modes(
                out_dir=OUT, cdp_port=CDP_PORT, seed=_seed, purge=_purge,
                drivers={"vision": drive_vision, "ux": drive_ux})
    finally:
        sys.argv = saved
    print(f"[capture] DONE out={OUT}")
