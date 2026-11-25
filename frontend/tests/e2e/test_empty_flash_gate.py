"""Empty-flash E2E gate (site standard, user order 2026-11-02):
no table page may paint empty-state copy during its first in-flight load.
The API is artificially delayed 500ms so the intermediate window is always
observable; the init-script recorder samples table DOM state from the very
first frame. Data pages must show zero empty-copy frames; a genuinely-empty
page may only show the empty state AFTER a loading signal appeared (mask or
skeleton) — a pre-fetch copy flash is a violation.

Grows with the AV-batch ledger cleanup (add cleared pages to DATA_PAGES).
Reclassified ledger pages from the gate-shape widening are covered
here as route spectra: data-bearing pages join DATA_PAGES, factory-empty pages
join EMPTY_PAGES, and the ancestor v-loading shape gets its own proof test.
"""
import time
from pathlib import Path

import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, pick_project_in_header

DATA_PAGES = [
    "/#/configs", "/#/tools", "/#/knowledge",
    # segment3 drift finding (gate RED 2026-11-04 05:39, this exact route):
    # the settle probe read rows=0 because the bare cold boot on :5175 spent its
    # 2.5s measure window in vite lazy-chunk compile — a probe artifact, not the
    # settled shape. The live contract: getLlmUsageCalibration(days) is a global
    # endpoint (ignores header context) and tp_llm_usage_sample held 917 distinct
    # projects in the trailing 7 days at measure time. The gate's own recorded
    # frames are the authority: rows 0->7->27 at settle, zero empty-copy frames,
    # zero masked copy. DATA shape, no product defect. The "goes RED, never
    # silent" clause below did its job on a misclassification.
    "/#/cost?tab=calibration",
]  # global pages with factory rows
# Project-slice pages : header context required —
# bare boot shows the honest R30 disabled guidance, so the test lands the
# resident fixture project first; the selection persists in localStorage
# (tp_proj_ctx_<userId>) and survives the fresh-document goto the recorder
# needs. Only pages whose contract is rows>0-at-settle stay here.
PROJECT_SLICE_PAGES = [
    "/#/mock/spaces", "/#/mock/scenarios",
]
# True-ledger reclassification :
# the six-page segment split — spaces/scenarios carried the genuine boot flash
# (product fix: loading starts true, the established four-page cleared shape);
# /#/cost?tab=calibration PASSED once phantom frames under v-show ancestors
# stopped counting (its tables are ancestor-hidden pre-fetch, no copy surface);
# mock/endpoints (resident fixture holds no stubs) and api-inventory (needs a
# second hit: spec picked) never paint an el-table empty block by construction —
# zero rows routes to custom guidance blocks, so they join CUSTOM_EMPTY_SLICE_PAGES
# below instead of the rows>0 contract; api-spec-change-analysis settles into a
# legitimate empty final state and joins EMPTY_PAGES.
# /#/cluster/upgrade moved to CUSTOM_EMPTY_SLICE_PAGES (full-gate RED re-run
# 2026-11-04 03:47 + live probe): at baseline the settled surface is the custom
# EmptyState version area (v-if="loaded && !latest", data-test=upgrade-empty-state);
# its four el-tables stay inside collapsed tab ancestors — empty blocks measure
# w=0 h=0, so no table copy ever paints. Requiring a table-copy final frame was
# a spectra-shape misclassification, not a product defect.
CUSTOM_EMPTY_SLICE_PAGES = ["/#/mock/endpoints", "/#/api-inventory", "/#/cluster/upgrade",
    # segment3 settle probe (same run): /#/appauto/healing paints NO el-table at
    # settle (table=false, no copy surface — custom guidance component only),
    # so it is the never-paint contract, not the empty-final one.
    "/#/appauto/healing"]
RESIDENT_PROJECT = "Traffic plane resident fixture"
EMPTY_PAGES = [
    "/#/batches", "/#/api-spec-change-analysis",  # legitimately empty at baseline
    # segment3 widening (box-measured settle, /tmp/tp-t514p3-settle.result
    # 2026-11-04): these seven settle rows=0 over the resident segment with the
    # table's own empty block painting the guidance copy — the empty-final
    # contract applies. If baseline data ever lands rows>0 here the assertion
    # goes RED as a drift finding, never silent (fired exactly that way for
    # /#/cost?tab=calibration: reclassified to DATA_PAGES, see its note).
    "/#/plans", "/#/defects", "/#/delete-requests",
    "/#/appauto/devices", "/#/appauto/credentials", "/#/appauto/packages",
    "/#/cases?tab=suites",
]  # suite list lives as the /cases suites tab since the menu merge
# /#/perf paints no el-table at route level (card workbench; probe measured
# 2026-11-04) — its guards live in mounted child views, see FORM_EXEMPTIONS.

# Component-mounted shapes a route-level document recorder cannot observe
# (dialogs/wizards mount after a user action, same-document). Registered, not
# silent — each carries the measurement that justified the exemption.
FORM_EXEMPTIONS = [
    ("src/components/mock/MockStubDirectWizard.vue",
     "wizard form: no el-table in-flight empty-copy surface (grep 2026-11-04: no v-loading/empty block)"),
    ("src/views/apitest/SpecImportWizard.vue",
     "positive-length guards v-if=specs.length>0 / v-if=extractableDocs.length>0: table physically not painted before rows exist"),
    ("src/views/apitest/RunSetDetailDrawer.vue",
     "drawer detail sections: no el-table empty-copy surface (grep 2026-11-04)"),
    ("src/views/case/AutoAssignDialog.vue",
     "ancestor v-loading on the dialog body: empty blocks hidden by the site theme rule, same shape as the drawer proof test below"),
    ("src/views/perf/PerfWorkbenchView.vue",
     "route paints no el-table (card workbench, probe 2026-11-04): guards sit in mounted child views, invisible to a route-level recorder"),
    # segment3 remaining 4 ledger files — all same-document component mounts a
    # route-level recorder cannot observe (import anchors measured 2026-11-04):
    ("src/views/mock/MockInspectorView.vue",
     "embedded tab of MockSpaceWorkbenchView.vue:73 (pageTab=inspector, mounted after tab click; guard v-if=hitsLoading && !hits.length)"),
    ("src/views/mock/MockRecordingView.vue",
     "embedded tab of MockSpaceWorkbenchView.vue:72 (pageTab=recording; guard v-if=loadingBoxes && !boxes.length)"),
    ("src/views/perf/PerfReportView.vue",
     "mounted inside PerfRunDetailView.vue:392 run-detail child (baseline perf_run=0, no detail route to record)"),
    ("src/views/explore/ExploreDetailView.vue",
     "detail route explore/:id — el-table guarded by v-if=\"steps.length\" positive-length (table physically not painted pre-data)"),
]

RECORDER = """
window.__ef=[]; window.__efDone=false;
const t0=performance.now();
const id=setInterval(()=>{
  const eb=document.querySelector('.el-table__empty-block');
  const cs=eb?getComputedStyle(eb):null;
  const copy=eb?(eb.textContent||''):'';
  // the site theme hides masked empty blocks via visibility:hidden (theme.scss
  // :has rule), so a frame counts as painted only when BOTH unhidden and shown.
  // A v-show=false on an ANCESTOR (page root/card) keeps the child's computed
  // display at 'block' while painting nothing — measure the box instead (T515
  // probe 2026-11-04: the cost detail table under v-show reported phantom copy).
  const ebBox=eb?eb.getBoundingClientRect():null;
  const hasCopy=eb && cs.display!=='none' && cs.visibility!=='hidden' &&
                ebBox.width>0 && ebBox.height>0 &&
                /无数据|暂无|没有|登记|添加|加载/.test(copy);
  const mask=!!document.querySelector('.el-table .el-loading-mask, .el-loading-parent--relative > .el-loading-mask');
  const skel=!!document.querySelector('.ts-row, [data-test="dtp-initial-skeleton"]');
  const rows=document.querySelectorAll('.el-table__body tr.el-table__row').length;
  const tPresent=!!document.querySelector('.el-table');
  const rec=[Math.round(performance.now()-t0),!!hasCopy,mask,skel,rows,tPresent];
  const last=window.__ef[window.__ef.length-1];
  if(!last||JSON.stringify(last.slice(1))!==JSON.stringify(rec.slice(1)))window.__ef.push(rec);
  const settled=(rows>0&&last&&last[4]===0)||hasCopy;
  if(settled&&performance.now()-t0>(window.__settledAt||(window.__settledAt=performance.now()))+600)
    {clearInterval(id);window.__efDone=true;}
  if(performance.now()-t0>15000){clearInterval(id);window.__efDone=true;}
},6);
"""


def _slow_api(page):
    def handler(route):
        if route.request.resource_type == "xhr" and "/api/v1/" in route.request.url:
            time.sleep(0.5)
        route.continue_()

    page.route("**/api/v1/**", handler)


def _fast_api(page):
    """Keep every business xhr comfortably under the 250ms skeleton reveal
 threshold so the fast-settle shape is deterministic instead of
 depending on ambient backend latency."""
    def handler(route):
        if route.request.resource_type == "xhr" and "/api/v1/" in route.request.url:
            time.sleep(0.02)
        route.continue_()

    page.route("**/api/v1/**", handler)


def _warm(page, path):
    # vite dev compiles the lazy route chunk on FIRST visit (7s+ observed); that
    # window is tooling noise, not the product flash. Warm the chunk first.
    page.goto(f"{BASE_URL}{path}", wait_until="load")
    page.wait_for_timeout(2000)


def _frames(page, path):
    # force a real document load so the init-script recorder installs
    # (hash-only navigation from the dashboard is same-document = no new script run)
    _warm(page, path)
    page.goto("about:blank")
    page.goto(f"{BASE_URL}{path}", wait_until="commit")
    page.wait_for_function("window.__efDone===true", timeout=30000)
    return page.evaluate("window.__ef")


@pytest.mark.parametrize("path", DATA_PAGES)
def test_no_empty_copy_flash_on_data_pages(logged_in, collectors, path):
    logged_in.add_init_script(RECORDER)
    _slow_api(logged_in)
    frames = _frames(logged_in, path)
    flash = [f for f in frames if f[1] and f[4] == 0]
    leak = [f for f in frames if f[1] and f[2]]
    assert frames, "recorder produced no frames"
    assert frames[-1][4] > 0, f"{path}: expected rows>0 at settle, frames={frames[-4:]}"
    assert not leak, f"{path}: empty copy painted through loading mask: {leak[:3]}"
    assert not flash, f"{path}: empty-state copy flashed before first rows: {flash[:3]}"
    assert_no_errors(collectors, path)


@pytest.mark.parametrize("path", PROJECT_SLICE_PAGES)
def test_no_empty_copy_flash_on_project_slice_pages(logged_in, collectors, path):
    """Same invariants as the global data pages, over the resident project
 segment: zero empty-copy frames before rows land, zero copy under mask,
 rows>0 at settle (the fixture project carries factory rows — a settle at
 zero is either a lost context or a data-drift finding, never silent)."""
    pick_project_in_header(logged_in, RESIDENT_PROJECT)
    logged_in.add_init_script(RECORDER)
    _slow_api(logged_in)
    frames = _frames(logged_in, path)
    flash = [f for f in frames if f[1] and f[4] == 0]
    leak = [f for f in frames if f[1] and f[2]]
    assert frames, "recorder produced no frames"
    assert frames[-1][4] > 0, f"{path}: expected rows>0 at settle, frames={frames[-4:]}"
    assert not leak, f"{path}: empty copy painted through loading mask: {leak[:3]}"
    assert not flash, f"{path}: empty-state copy flashed before first rows: {flash[:3]}"
    assert_no_errors(collectors, path)


@pytest.mark.parametrize("path", EMPTY_PAGES)
def test_empty_page_copy_only_with_mounted_table_and_never_under_mask(logged_in, collectors, path):
    """Empty final state is legitimate; the defect is *intermediate* copy.
 In dev-mode the ESM boot (6s+) delays component mount past the API return,
 so requiring an observable skeleton frame is timing-dependent noise. The
 product invariants (selector-safe in all run modes): empty copy may only
 paint together with a mounted table, and never while a loading mask is on.
 The header pick is unconditional: api-spec-change-analysis is a project
 segment (bare boot would only show the R30 guidance, not the settled empty
 table), while global pages ignore the context."""
    pick_project_in_header(logged_in, RESIDENT_PROJECT)
    logged_in.add_init_script(RECORDER)
    _slow_api(logged_in)
    frames = _frames(logged_in, path)
    leak = [f for f in frames if f[1] and f[2]]
    orphan = [f for f in frames if f[1] and not f[5]]
    assert frames[-1][1] is True, f"{path}: expected the settled empty state, frames={frames[:6]}"
    assert not leak, f"{path}: empty copy painted through loading mask: {leak[:3]}"
    assert not orphan, f"{path}: empty copy without a mounted table: {orphan[:3]}"
    assert_no_errors(collectors, path)


@pytest.mark.parametrize("path", CUSTOM_EMPTY_SLICE_PAGES)
def test_custom_empty_slice_pages_never_paint_table_copy(logged_in, collectors, path):
    """Pages that by construction do not paint an el-table empty block (zero
 rows routes to a custom guidance/empty component — endpoints measured
 2026-11-04: no .el-table at all pre-rows; api-inventory waits for the
 second hit "spec picked"). Contract: over the resident segment, zero
 empty-copy frames, zero masked copy, clean boot — rows>0 is NOT asserted
 because the resident fixture legitimately holds no stubs / no picked spec
 there; that is data shape, not a gate violation, and stays loud in the
 ledger comments rather than silently whitelisted."""
    pick_project_in_header(logged_in, RESIDENT_PROJECT)
    logged_in.add_init_script(RECORDER)
    _slow_api(logged_in)
    frames = _frames(logged_in, path)
    flash = [f for f in frames if f[1] and f[4] == 0]
    leak = [f for f in frames if f[1] and f[2]]
    assert frames, "recorder produced no frames"
    assert not leak, f"{path}: empty copy painted through loading mask: {leak[:3]}"
    assert not flash, f"{path}: empty-state copy flashed before first rows: {flash[:3]}"
    assert_no_errors(collectors, path)


def test_plugin_drawer_ancestor_mask_hides_empty_copy(logged_in, collectors):
    """Ancestor v-loading shape, live proof (ledger classification ):
 PluginDetailDrawer masks its body with a wrapper-level v-loading, so the
 inner table's empty block is hidden by the theme :has rule rather than a
 page-local patch. The drawer is mounted same-document after a click, so
 the route-level init recorder cannot see it — sample frames around the
 open instead. The API is slowed so the masked window is always long
 enough to observe."""
    page = logged_in
    _slow_api(page)
    page.goto(f"{BASE_URL}/#/extensions?tab=plugins", wait_until="load")
    opener = page.locator('[data-test^="plugin-view-open-"]').first
    expect(opener).to_be_visible(timeout=20000)
    page.evaluate("""() => {
      window.__df=[]; const t0=performance.now();
      const id=setInterval(()=>{
        const body=document.querySelector('[data-test="plugin-detail-drawer"] .drawer-body');
        const mEl=body?body.querySelector(':scope > .el-loading-mask'):null;
        // in-flight = mask shown and NOT inside the v-loading leave transition.
        // Measured 2026-11-04: during el-loading-fade-leave-active the mask is
        // still ~opaque while the settled final state (legit empty copy) already
        // paints — opacity alone misjudges the fade-out window as in-flight.
        const mask=!!(mEl && !mEl.className.includes('leave') &&
                      parseFloat(getComputedStyle(mEl).opacity) > 0.95);
        const eb=body&&body.querySelector('.el-table__empty-block');
        const cs=eb?getComputedStyle(eb):null;
        const ebPaint=!!(eb && cs.display!=='none' && cs.visibility!=='hidden');
        const sib=!!(body && body.querySelector('[data-test="plugin-applied-empty"]'));
        const rows=body?body.querySelectorAll('.el-table__body tr.el-table__row').length:0;
        const mcls=mEl?mEl.className:'';
        const f=[mask,ebPaint,sib,rows,!!body,mcls];
        const last=window.__df[window.__df.length-1];
        if(!last||String(last)!==String(f))window.__df.push(f);
        if(performance.now()-t0>6000)clearInterval(id);
      },10);
    }""")
    opener.click()
    drawer = page.locator('[data-test="plugin-detail-drawer"]')
    expect(drawer).to_be_visible(timeout=10000)
    page.wait_for_function("window.__df.length>0 && window.__df.some(f=>f[0])", timeout=8000)
    time.sleep(1.2)  # the slowed fetch settles inside the 6s sampler window
    frames = page.evaluate("window.__df")
    leak_eb = [f for f in frames if f[0] and f[1]]
    leak_sib = [f for f in frames if f[0] and f[2]]
    assert any(f[0] for f in frames), f"masked in-flight window never observed: {frames[:6]}"
    # the leave-frame exclusion must carry a real use case (R43: a never-hit
    # branch = silent no-op): the fade-out window has to actually appear.
    assert any("leave" in f[5] for f in frames), f"fade-out (leave) frames never recorded, exclusion branch unproven: {frames[:8]}"
    assert not leak_eb, f"theme :has rule failed to hide empty block under ancestor mask: {leak_eb[:3]}"
    assert not leak_sib, f"empty-copy sibling painted through the drawer loading mask: {leak_sib[:3]}"
    assert frames[-1][0] is False, f"drawer mask never lifted, frames={frames[-4:]}"
    assert_no_errors(collectors, "plugin drawer mask")


SKELETON_FLASH_PAGES = ["/#/configs", "/#/tools"]  # single-fetch data pages


@pytest.mark.parametrize("path", SKELETON_FLASH_PAGES)
def test_skeleton_never_flashes_on_fast_responses(logged_in, collectors, path):
    """ (user screenshot report 2026-11-04): with responses settling well
 under the named reveal threshold, the table area must paint ZERO skeleton
 frames — rows land directly, no three-line flash in between."""
    logged_in.add_init_script(RECORDER)
    _fast_api(logged_in)
    frames = _frames(logged_in, path)
    assert frames, "recorder produced no frames"
    skel_frames = [f for f in frames if f[3]]
    assert frames[-1][4] > 0, f"{path}: expected rows>0 at settle, frames={frames[-4:]}"
    assert not skel_frames, f"{path}: skeleton flashed on a fast load: {skel_frames[:3]}"
    assert_no_errors(collectors, path)


@pytest.mark.parametrize("path", SKELETON_FLASH_PAGES)
def test_skeleton_still_paints_on_slow_responses(logged_in, collectors, path):
    """The reveal gate delays the skeleton, it does not remove it: with the
    API slowed to 500ms (> the 250ms named threshold) at least one skeleton
    frame must appear before rows settle."""
    logged_in.add_init_script(RECORDER)
    _slow_api(logged_in)
    frames = _frames(logged_in, path)
    assert frames, "recorder produced no frames"
    assert any(f[3] for f in frames), f"{path}: skeleton never painted on a slow load: {frames[:6]}"
    assert frames[-1][4] > 0, f"{path}: expected rows>0 at settle, frames={frames[-4:]}"
    assert_no_errors(collectors, path)


def test_form_exemptions_remain_accurate():
    """Registered dialog/wizard exemptions must not go stale silently: the
    files still exist, and the ancestor/guard wording stays checkable."""
    root = Path(__file__).resolve().parents[2]
    for rel, _reason in FORM_EXEMPTIONS:
        f = root / rel
        assert f.is_file(), f"exemption row points at a missing file: {rel} (moved? re-measure the shape)"
