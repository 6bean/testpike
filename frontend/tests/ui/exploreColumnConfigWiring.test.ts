/**
 * Exploration-session list column-configuration wiring contract (N-3 repayment).
 * Mirrors the knowledge/defect/board precedent: the page must really consume
 * the shared useColumnConfig piece, not a second implementation.
 *  - defs exist for every data column of the session table;
 *  - DataTablePage receives :column-config (width write-back) and :size
 *    (density), and the columns render through the visibleColumns loop
 *    instead of a static list (so hide/order controls take effect);
 *  - the ColumnSettings popover sits in the page action band next to refresh
 *    (this page carries no filter conditions; AuditLogView precedent),
 *    never in a header band (R21 view-state actions placement);
 *  - the loop keeps the stable data-test anchors the E2E spectrum uses and
 *    the header-sort set aligned with the server-side SORT_KEY_MAP whitelist.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/explore/ExploreListView.vue', import.meta.url), 'utf8')

describe('exploration-session column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("import DataTablePage from '@/components/DataTablePage.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('explore', EXPLORE_COLUMNS)")
  })

  it('declares a def for every data column of the session table', () => {
    for (const key of ['id', 'goalText', 'startUrl', 'status', 'stepsUsed', 'costCents']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds width/density state back into the table shell', () => {
    expect(page).toContain(':column-config="colCfg"')
    expect(page).toContain(':size="colCfg.state.density"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`explore-col-${col.key}`')
    // E2E-critical custom cell anchors survive the loop conversion.
    for (const anchor of ['explore-status-', 'explore-open-', 'explore-cancel-']) {
      expect(page).toContain(anchor)
    }
    // header sort stays pinned to the server-side whitelist keys only
    expect(page).toContain("const EXPLORE_SORTABLE = ['id', 'goalText', 'startUrl', 'status', 'stepsUsed', 'costCents']")
    expect(page).toContain(":sortable=\"EXPLORE_SORTABLE.includes(col.key) ? 'custom' : false\"")
    // the row-action column is not part of the configurable loop
    expect(page).toContain('<el-table-column :label="t(\'explore.colActions\')" width="180" fixed="right">')
  })

  it('mounts the settings popover in the page action band', () => {
    const band = page.slice(page.indexOf('toolbar-right'), page.indexOf('<TableSkeleton'))
    expect(band).toContain('<ColumnSettings')
    // the control block wires all five piece actions (no dead popover)
    for (const evt of [
      '@toggle="colCfg.toggle"',
      '@move="colCfg.move"',
      '@density="colCfg.setDensity"',
      '@reset="colCfg.reset"',
      '@pin="colCfg.toggleFixed"',
    ]) {
      expect(band).toContain(evt)
    }
  })
})
