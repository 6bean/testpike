/**
 * Device-wall column-configuration wiring contract (N-3 repayment).
 * Mirrors the engine-center/app-package/plan-list precedent: the page must
 * really consume the shared useColumnConfig piece, not a second implementation.
 *  - defs exist for every data column of the wall table (the locked action
 *    column stays outside the loop);
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable data-test
 *    anchors for the E2E spectrum;
 *  - the page keeps its sort-exempt declaration (line 1): the wall shows the
 *    physically attached device pool, so the loop columns declare no sortable
 *    attribute (no second per-column list);
 *  - FB/R21 same-band merge: no bulk bar and one screening control, so the
 *    variant actions (register/onboard) ride the filter-bar trailing slot and
 *    the emptied header band is removed; the settings control sits ahead of
 *    refresh;
 *  - R25 mode declaration: no tabs and one page-surface table — the lease
 *    history table lives in a per-row drawer and stays honest-absent from the
 *    page-level popover.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/appauto/DeviceWallView.vue', import.meta.url),
  'utf8',
)

describe('device-wall column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('device-wall', DEVICE_WALL_COLUMNS)")
  })

  it('declares a def for every data column of the wall table', () => {
    for (const key of ['name', 'serial', 'owner', 'type', 'model', 'onlineState', 'occupancy', 'failCount', 'labels', 'lastSeen']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the wall table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // the locked action column always renders, so the skeleton adds one slot
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`device-col-${col.key}`')
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : DEVICE_WALL_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    expect(page).toContain(':show-overflow-tooltip="DEVICE_WALL_TOOLTIP.has(col.key)"')
    // per-column custom renderers stay wired inside the single loop
    expect(page).toContain("v-if=\"col.key === 'owner'\"")
    expect(page).toContain("v-else-if=\"col.key === 'type'\"")
    expect(page).toContain("v-else-if=\"col.key === 'model'\"")
    expect(page).toContain("v-else-if=\"col.key === 'onlineState'\"")
    expect(page).toContain("v-else-if=\"col.key === 'occupancy'\"")
    expect(page).toContain("v-else-if=\"col.key === 'failCount'\"")
    expect(page).toContain("v-else-if=\"col.key === 'labels'\"")
    expect(page).toContain("v-else-if=\"col.key === 'lastSeen'\"")
    // the locked action column keeps its own slot outside the loop
    expect(page).toContain('width="210" fixed="right"')
  })

  it('keeps the sort-exempt declaration without introducing sortable in the loop', () => {
    // page-level exemption stays stated on line 1 (hardware-bounded device pool)
    expect(page).toContain('tp-sort-exempt')
    expect(page).not.toContain(':sortable')
  })

  it('keeps the E2E-critical anchors through the loop conversion', () => {
    for (const anchor of [
      'device-wall-page',
      'device-wall-filter-bar',
      'device-wall-refresh',
      'device-wall-table',
      'device-wall-create-btn',
      'device-wall-onboard-btn',
      'device-wall-shared-badge',
      'device-wall-occupancy',
      'device-wall-health-tag',
      'device-wall-lease-table',
    ]) {
      expect(page).toContain(anchor)
    }
  })

  it('merges same-band per R21: header band gone, variant actions ahead in the slot', () => {
    // R21 merge declaration is stated on the page (FB group wording anchor)
    expect(page).toContain('R21 same-band merge')
    expect(page).not.toContain('class="page-header"')
    expect(page).not.toContain('class="header-actions"')
    expect(page).toContain('class="filter-bar-actions"')
    // containment preconditions first, then the order comparisons (no vacuous -1 checks)
    expect(page).toContain('data-test="device-wall-create-btn"')
    expect(page).toContain('data-test="device-wall-onboard-btn"')
    expect(page).toContain('<ColumnSettings')
    const slot = page.indexOf('class="filter-bar-actions"')
    expect(page.indexOf('data-test="device-wall-create-btn"')).toBeGreaterThan(slot)
    expect(page.indexOf('data-test="device-wall-onboard-btn"')).toBeGreaterThan(slot)
    expect(page.indexOf('<ColumnSettings')).toBeLessThan(page.indexOf('data-test="device-wall-refresh"'))
  })

  it('declares the R25 scope mode and keeps the drawer table honest-absent', () => {
    expect(page).toContain('R25 mode declaration')
    expect(page).toContain('honest-absent')
  })

  it('merges column state with labelKey for the settings popover rows', () => {
    expect(page).toContain('const settingsRows = computed(')
    expect(page).toContain(':columns="settingsRows"')
    expect(page).toContain('@density="colCfg.setDensity"')
    expect(page).toContain('@pin="colCfg.toggleFixed"')
  })
})
