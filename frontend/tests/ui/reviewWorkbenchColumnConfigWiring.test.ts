/**
 * Review-workbench column-configuration wiring contract (N-3 repayment).
 * Mirrors the step-healing/ai-sample-board precedent: the page must consume
 * the shared useColumnConfig piece rather than a second implementation.
 *  - defs exist for every data column of the review queue table; the locked
 *    selection checkbox and the fixed action slot render outside the loop,
 *    so the skeleton mirrors the visible data count plus those two slots;
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable per-column
 *    data-test anchors for the E2E spectrum;
 *  - server-driven sorting survives the loop: caseNo/priority/confidence/
 *    updatedAt keep the custom-sortable binding through the shared map, the
 *    long title cell keeps its overflow-tooltip behaviour;
 *  - R21: the settings control rides the filter-bar trailing slot; the
 *    screening selects and the reset control stay in the condition flow;
 *  - R25 mode declaration: no tabs and one page-surface table, so the
 *    page-level column-settings control equals the table scope.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/review/ReviewWorkbenchView.vue', import.meta.url),
  'utf8',
)

const DATA_KEYS = ['caseNo', 'title', 'priority', 'confidence', 'status', 'updatedAt']

describe('review-workbench column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('review-workbench', REVIEW_WORKBENCH_COLUMNS)")
  })

  it('declares a def for every data column of the review queue table', () => {
    for (const key of DATA_KEYS) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and a visible-count skeleton into the table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // selection checkbox + fixed action slot are the two locked columns outside the loop
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 2"')
    expect(page).not.toContain(':cols="8"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`review-col-${col.key}`')
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : REVIEW_WORKBENCH_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    for (const key of DATA_KEYS) {
      expect(page).toContain(`col.key === '${key}'`)
    }
  })

  it('keeps server-driven sorting and the title tooltip through the loop', () => {
    expect(page).toContain(":sortable=\"REVIEW_WORKBENCH_SORT_BY[col.key] ? 'custom' : false\"")
    expect(page).toContain(':sort-by="REVIEW_WORKBENCH_SORT_BY[col.key]"')
    expect(page).toContain("caseNo: 'case_no'")
    expect(page).toContain("updatedAt: 'updated_at'")
    expect(page).toContain(':show-overflow-tooltip="col.key === \'title\'"')
    expect(page).toContain(":default-sort=\"{ prop: 'priority', order: 'ascending' }\"")
  })

  it('keeps the selection checkbox and the action column locked outside the loop', () => {
    const loopIdx = page.indexOf('v-for="col in colCfg.visibleColumns.value"')
    const selectionIdx = page.indexOf('type="selection"')
    const detailIdx = page.indexOf('review-detail-')
    expect(selectionIdx).toBeGreaterThan(-1)
    expect(selectionIdx).toBeLessThan(loopIdx)
    expect(loopIdx).toBeGreaterThan(-1)
    expect(detailIdx).toBeGreaterThan(loopIdx)
    expect(page).toContain(":label=\"t('case.action')\"")
  })

  it('places the settings control in the filter trailing slot behind the condition flow (R21)', () => {
    const actionsIdx = page.indexOf('class="filter-bar-actions"')
    expect(actionsIdx).toBeGreaterThan(-1)
    const settingsIdx = page.indexOf('<ColumnSettings')
    expect(settingsIdx).toBeGreaterThan(actionsIdx)
    // the existing screening controls and the reset stay in the condition flow
    expect(page.indexOf('review-filter-status')).toBeLessThan(actionsIdx)
    expect(page.indexOf('review-filter-priority')).toBeLessThan(actionsIdx)
    expect(page.indexOf('review-filter-confidence')).toBeLessThan(actionsIdx)
    expect(page.indexOf('review-reset')).toBeLessThan(actionsIdx)
  })

  it('declares the R25 scope-aware mode on the page surface', () => {
    expect(page).toContain('R25 mode declaration')
    expect(page).toContain("viewKey 'review-workbench'")
  })
})
