/**
 * Engine-center platform-registry column-configuration wiring contract (N-3 repayment).
 * Mirrors the app-package/training-sample/mock-spaces precedent: the page must
 * really consume the shared useColumnConfig piece, not a second implementation.
 * - defs exist for every data column of the platform table (the locked action
 * column stays outside the loop);
 * - density rides colCfg.state.density and columns render through the
 * visibleColumns loop (hide/order/pin take effect) with stable data-test
 * anchors for the E2E spectrum;
 * - the page keeps its sort-exempt declaration (line 1): the engine/platform
 * registries are hand-provisioned sets served in full, so the loop columns
 * declare no sortable attribute (no second per-column list);
 * - FB/R21: the settings control rides the existing .filter-bar-actions
 * trailing slot ahead of refresh (same order as the app-package segment);
 * - R25 mode declaration: no tabs and one configurable table (the engine
 * section renders as cards, not a table), so the page-level settings
 * popover equals the platform-table scope — scope-aware single control.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/appauto/EngineCenterView.vue', import.meta.url),
  'utf8',
)

describe('engine-center platform column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('engine-platform', ENGINE_PLATFORM_COLUMNS)")
  })

  it('declares a def for every data column of the platform table', () => {
    for (const key of ['code', 'name', 'deviceKinds', 'carriers', 'note', 'status']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the platform table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // the locked action column always renders, so the skeleton adds one slot
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`engine-col-${col.key}`')
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : ENGINE_PLATFORM_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    expect(page).toContain(':show-overflow-tooltip="ENGINE_PLATFORM_TOOLTIP.has(col.key)"')
    // per-column custom renderers stay wired inside the single loop
    expect(page).toContain("v-if=\"col.key === 'deviceKinds'\"")
    expect(page).toContain("v-else-if=\"col.key === 'carriers'\"")
    expect(page).toContain("v-else-if=\"col.key === 'note'\"")
    expect(page).toContain("v-else-if=\"col.key === 'status'\"")
    // the locked action column keeps its own slot outside the loop
    expect(page).toContain('v-if="canPlatformManage"')
    expect(page).toContain('width="100" fixed="right"')
  })

  it('keeps the sort-exempt declaration without introducing sortable in the loop', () => {
    // page-level exemption stays stated on line 1 (hand-provisioned full registry)
    expect(page).toContain('tp-sort-exempt')
    expect(page).not.toContain(':sortable')
  })

  it('keeps the E2E-critical anchors through the loop conversion', () => {
    for (const anchor of [
      'engine-center-page',
      'engine-center-filter-bar',
      'engine-center-refresh',
      'engine-platform-table',
      'platform-manage-btn',
      'engine-center-grid',
    ]) {
      expect(page).toContain(anchor)
    }
  })

  it('keeps the settings control in the band tail ahead of refresh (FB/R21 order)', () => {
    // R25 mode declaration is stated on the page (TB group wording anchor)
    expect(page).toContain('R25 mode declaration')
    expect(page).toContain('class="filter-bar-actions"')
    expect(page).toContain('<ColumnSettings')
    expect(page.indexOf('<ColumnSettings')).toBeLessThan(page.indexOf('data-test="engine-center-refresh"'))
  })

  it('merges column state with labelKey for the settings popover rows', () => {
    expect(page).toContain('const settingsRows = computed(')
    expect(page).toContain(':columns="settingsRows"')
    expect(page).toContain('@density="colCfg.setDensity"')
    expect(page).toContain('@pin="colCfg.toggleFixed"')
  })
})
