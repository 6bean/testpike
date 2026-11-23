/**
 * Training-sample ledger column-configuration wiring contract (N-3 repayment).
 * Mirrors the plan-list/project/delete-request/mock-spaces/perf-workbench
 * precedent: the page must really consume the shared useColumnConfig piece,
 * not a second implementation.
 *  - defs exist for every data column of the sample table (the locked action
 *    column stays outside the loop);
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable data-test
 *    anchors for the E2E spectrum;
 *  - server-side sorting survives the loop and stays single-sourced: the
 *    SORT_KEY_MAP (already the whitelist for the request payload) decides
 *    the per-column sortable flag;
 *  - FB/R21: the page is a read-only ledger (no create/import variant action),
 *    so the settings control rides the .filter-bar-actions trailing slot alone
 *    and no header-actions shell exists on the page;
 *  - R25 mode declaration: no tabs and one table, so the page-level
 *    settings popover equals the table scope (scope-aware single control).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/sample/TrainingSampleListView.vue', import.meta.url),
  'utf8',
)

describe('training-sample column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('sample', SAMPLE_COLUMNS)")
  })

  it('declares a def for every data column of the sample ledger table', () => {
    for (const key of ['id', 'caseNo', 'projectId', 'sampleType', 'sourceVersion', 'createdAt']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the ledger table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // the locked action column always renders, so the skeleton adds one slot
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`sample-col-${col.key}`')
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : SAMPLE_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    // sortable decision stays single-sourced on the existing SORT_KEY_MAP
    // whitelist (no second per-column list)
    expect(page).toContain(":sortable=\"SORT_KEY_MAP[col.key] ? 'custom' : false\"")
    expect(page).toContain(":align=\"col.key === 'sourceVersion' ? 'center' : undefined\"")
    // per-column custom renderers stay wired inside the single loop
    expect(page).toContain("v-if=\"col.key === 'caseNo'\"")
    expect(page).toContain("v-else-if=\"col.key === 'projectId'\"")
    expect(page).toContain("v-else-if=\"col.key === 'sampleType'\"")
    expect(page).toContain("v-else-if=\"col.key === 'sourceVersion'\"")
    // the locked action column keeps its own slot outside the loop
    expect(page).toContain('width="90" align="center"')
  })

  it('keeps the E2E-critical anchors through the loop conversion', () => {
    for (const anchor of [
      'sample-page',
      'sample-table',
      'sample-type-select',
      'sample-search',
      'sample-pager',
      'sample-detail-open',
      'sample-type-tag',
      'sample-detail-drawer',
      'trainingsamplelistview-skeleton',
    ]) {
      expect(page).toContain(anchor)
    }
  })

  it('keeps the settings control in the band tail without a header-actions shell (FB/R21)', () => {
    // R25 mode declaration is stated on the page (TB group wording anchor)
    expect(page).toContain('R25 mode declaration')
    expect(page).toContain('class="filter-bar-actions"')
    expect(page).not.toContain('class="header-actions"')
    expect(page.indexOf('<ColumnSettings')).toBeLessThan(page.indexOf('sample-table'))
  })
})
