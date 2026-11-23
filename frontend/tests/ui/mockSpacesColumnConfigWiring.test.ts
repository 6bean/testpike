/**
 * Mock spaces list column-configuration wiring contract (N-3 repayment).
 * Mirrors the llm/mock-endpoints/api-token precedent shape: the page must really
 * consume the shared useColumnConfig piece, not a second implementation.
 *  - a def exists for every data column of the spaces ledger (the locked action
 *    column stays outside the loop);
 *  - density rides colCfg.state.density and the columns render through the
 *    visibleColumns loop (hide/order/pin take effect), with stable data-test
 *    anchors for the E2E spectrum;
 *  - this page is sort-exempt (the full per-project ledger is served at once and
 *    windowed client-side), so no column may claim a sortable header — the
 *    tp-sort-exempt declaration must stay honest, no dead second whitelist;
 *  - R25 mode declaration: the page carries no tabs and exactly one table, so
 *    the page-level settings popover equals the table scope (scope-aware single
 *    control per TB group); the control rides the filter-bar trailing slot
 *    ahead of refresh (FB group: view-state actions live in the band tail).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/mock/MockSpacesView.vue', import.meta.url), 'utf8')

describe('mock-spaces column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('mock-spaces', MOCK_SPACES_COLUMNS)")
  })

  it('declares a def for every data column of the spaces ledger', () => {
    for (const key of ['name', 'spaceKey', 'status', 'version', 'priority', 'authMode', 'missPolicy', 'limits']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the spaces table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // the locked action column always renders, so the skeleton adds one slot
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`mock-spaces-col-${col.key}`')
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':min-width="col.width ? undefined : MOCK_SPACES_FLEX_MIN[col.key]"')
    // the per-key renderers keep working through the loop
    for (const anchor of ['mock-spaces-status', 'mock-spaces-policy-select', 'mock-spaces-policy-static']) {
      expect(page).toContain(anchor)
    }
    // page/table-level anchors the E2E spectrum relies on
    for (const anchor of ['mock-spaces-filter-bar', 'mock-spaces-table', 'mock-spaces-refresh', 'mock-spaces-pager']) {
      expect(page).toContain(anchor)
    }
  })

  it('mounts the settings control in the filter-bar tail ahead of refresh (R25/FB)', () => {
    expect(page).toContain('class="filter-bar-actions"')
    expect(page.indexOf('<ColumnSettings')).toBeGreaterThan(-1)
    expect(page.indexOf('<ColumnSettings')).toBeLessThan(page.indexOf('mock-spaces-refresh'))
    expect(page).toContain('@density="colCfg.setDensity"')
    expect(page).toContain('@pin="colCfg.toggleFixed"')
  })

  it('keeps the sort-exempt declaration honest: no sortable header in the loop', () => {
    expect(page).toContain('tp-sort-exempt')
    expect(page).not.toContain('sortable')
  })
})
