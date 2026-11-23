/**
 * Matrix-report column-configuration wiring contract (N-3 repayment).
 * Mirrors the popup-rule/credential-vault/device-wall/plan-list precedent: the
 * page must really consume the shared useColumnConfig piece, not a second
 * implementation.
 *  - defs exist for every data column of the heat table; the expand slot stays
 *    outside the loop (the skeleton adds one fixed slot);
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable per-column
 *    data-test anchors for the E2E spectrum;
 *  - the page keeps its sort-exempt declaration (line 1), so the loop columns
 *    declare no sortable attribute (no second per-column list);
 *  - R25 mode declaration: no tabs and one page-surface table (the failure-step
 *    drill-down lives inside expand rows and stays honestly un-configurable),
 *    so the page-level column-settings control equals the report scope; the
 *    settings control sits ahead of refresh in the trailing slot;
 *  - SE-1: the case picker stays filterable (remote-backed option list).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/appauto/MatrixReportView.vue', import.meta.url),
  'utf8',
)

describe('matrix-report column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('matrix-report', MATRIX_REPORT_COLUMNS)")
  })

  it('declares a def for every data column of the heat table', () => {
    for (const key of ['model', 'osVersion', 'samples', 'passRate', 'message']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the heat table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // only the expand slot renders outside the loop, so the skeleton adds one slot
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`matrix-report-col-${col.key}`')
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : MATRIX_REPORT_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    // per-column custom renderers stay wired inside the single loop
    expect(page).toContain("v-if=\"col.key === 'model'\"")
    expect(page).toContain("v-else-if=\"col.key === 'osVersion'\"")
    expect(page).toContain("v-else-if=\"col.key === 'samples'\"")
    expect(page).toContain("v-else-if=\"col.key === 'passRate'\"")
    expect(page).toContain("v-else-if=\"col.key === 'message'\"")
    // the expand slot keeps its own position outside the loop
    expect(page).toContain('type="expand"')
  })

  it('keeps the sort-exempt declaration without introducing sortable in the loop', () => {
    // page-level exemption stays stated on line 1 (matrix rows are per-case bounded)
    expect(page).toContain('tp-sort-exempt')
    expect(page).not.toContain(':sortable')
  })

  it('places settings ahead of refresh and drops the dead header styles', () => {
    const settings = page.indexOf('<ColumnSettings')
    const refresh = page.indexOf('matrix-report-refresh')
    expect(settings).toBeGreaterThan(-1)
    expect(refresh).toBeGreaterThan(settings)
    expect(page).toContain('class="filter-bar-actions"')
    expect(page).not.toContain('header-actions')
    expect(page).not.toContain('page-header')
    expect(page).not.toContain('page-title')
  })

  it('keeps the E2E-critical anchors through the loop conversion', () => {
    for (const anchor of [
      'matrix-report-page',
      'matrix-filter-bar',
      'matrix-report-case-select',
      'matrix-report-refresh',
      'matrix-report-table',
      'matrix-report-skeleton',
      'matrix-report-summary',
      'matrix-report-idle',
      'matrix-report-empty',
      'matrix-report-error',
      'matrix-report-model',
      'matrix-report-heat-',
      'matrix-report-diff-message',
      'matrix-report-step-failures',
      'matrix-report-steps-table',
    ]) {
      expect(page).toContain(anchor)
    }
  })

  it('keeps the case picker filterable and states the R25 scope declaration', () => {
    expect(page).toContain('filterable')
    expect(page).toContain('R25 mode declaration')
  })
})
