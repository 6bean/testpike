/**
 * Perf workbench scenario-ledger column-configuration wiring contract (N-3 repayment).
 * Mirrors the run-ledger / executor-pool precedent shape: the page must really
 * consume the shared useColumnConfig piece, not a second implementation.
 *  - a def exists for every data column of the scenario ledger (the locked
 *    action column stays outside the loop);
 *  - density rides colCfg.state.density and the columns render through the
 *    visibleColumns loop (hide/order/pin take effect), with stable data-test
 *    anchors for the E2E spectrum;
 *  - header sort stays server-side through the existing SORT_KEY_MAP whitelist
 *    (single-source reuse, no dead second whitelist): a loop column is
 *    sortable only when its key sits in the map;
 *  - this page carries no filter conditions and no row-level batch bar, so the
 *    FB same-band merge applies: the create variant action rides the
 *    .filter-bar-actions trailing slot ahead of the column-settings control
 *    (tag-list precedent); the emptied header-actions shell is gone.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/perf/PerfWorkbenchView.vue', import.meta.url), 'utf8')

describe('perf-workbench column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('perf-workbench', WORKBENCH_COLUMNS)")
  })

  it('declares a def for every data column of the scenario ledger', () => {
    for (const key of ['name', 'pressure_model', 'login_strategy', 'driver_limit', 'updated_at']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density into the table and renders columns through the loop', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`perf-col-${col.key}`')
    // flexible name column falls back to min-width, fixed ones keep their width
    expect(page).toContain(':min-width="col.width ? undefined : 180"')
  })

  it('keeps server-side sort honest through the single SORT_KEY_MAP whitelist', () => {
    expect(page).toContain(':sortable="SORT_KEY_MAP[col.key] ? \'custom\' : false"')
    expect(page).toContain('@sort-change="onSortChange"')
  })

  it('merges the create variant action ahead of the settings control in the band tail (FB/R25)', () => {
    expect(page).toContain('class="filter-bar"')
    expect(page).toContain('class="filter-bar-actions"')
    expect(page).not.toContain('class="header-actions"')
    expect(page.indexOf('perf-create-btn')).toBeLessThan(page.indexOf('<ColumnSettings'))
    expect(page.indexOf('<ColumnSettings')).toBeLessThan(page.indexOf('perf-scenario-table'))
    expect(page).toContain('@density="colCfg.setDensity"')
    expect(page).toContain('@pin="colCfg.toggleFixed"')
  })

  it('keeps the locked action column and E2E anchors outside the loop', () => {
    expect(page).toContain('width="340" fixed="right"')
    for (const anchor of ['perf-scenario-table', 'perf-scenario-pager', 'perf-row-edit', 'perf-row-trend']) {
      expect(page).toContain(anchor)
    }
  })
})
