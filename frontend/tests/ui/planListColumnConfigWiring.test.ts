/**
 * Plan-list ledger column-configuration wiring contract (N-3 repayment).
 * Mirrors the project/delete-request/mock-spaces/perf-workbench precedent:
 * the page must really consume the shared useColumnConfig piece, not a
 * second implementation.
 *  - defs exist for every data column of the plan table (the locked action
 *    column stays outside the loop);
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable data-test
 *    anchors for the E2E spectrum;
 *  - server-side sorting survives the loop and stays single-sourced: the
 *    SORT_KEY_MAP (already the whitelist for the request payload) decides
 *    the per-column sortable flag, so the composed period column never
 *    claims a sort the backend would reject;
 *  - FB/R21 same-band merge: this page carries no row-level batch bar and
 *    exactly one filter condition (<= the themed 4-control threshold), so
 *    the create variant action rides the .filter-bar-actions trailing slot
 *    ahead of the column-settings control and the emptied header-actions
 *    shell is gone;
 *  - R25 mode declaration: no tabs and one table, so the page-level
 *    settings popover equals the table scope (scope-aware single control).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/plan/PlanListView.vue', import.meta.url), 'utf8')

describe('plan-list column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('plan', PLAN_COLUMNS)")
  })

  it('declares a def for every data column of the plan ledger table', () => {
    for (const key of ['name', 'targetVersion', 'status', 'caseCount', 'assigneeCount', 'period', 'createdAt']) {
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
    expect(page).toContain('`plan-col-${col.key}`')
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : PLAN_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    // sortable decision stays single-sourced on the existing SORT_KEY_MAP
    // whitelist (no second per-column list)
    expect(page).toContain(":sortable=\"SORT_KEY_MAP[col.key] ? 'custom' : false\"")
    expect(page).toContain(":show-overflow-tooltip=\"col.key === 'name' || undefined\"")
    expect(page).toContain(
      ":align=\"col.key === 'caseCount' || col.key === 'assigneeCount' ? 'center' : undefined\"",
    )
    // per-column custom renderers stay wired inside the single loop
    expect(page).toContain("v-if=\"col.key === 'name'\"")
    expect(page).toContain("v-else-if=\"col.key === 'targetVersion'\"")
    expect(page).toContain("v-else-if=\"col.key === 'status'\"")
    expect(page).toContain("v-else-if=\"col.key === 'period'\"")
    expect(page).toContain("v-else-if=\"col.key === 'createdAt'\"")
    // the locked action column keeps its own right-pinned slot outside the loop
    expect(page).toContain('width="230" fixed="right"')
  })

  it('keeps the E2E-critical anchors through the loop conversion', () => {
    for (const anchor of [
      'plan-list-page',
      'plan-table',
      'plan-filter-status',
      'plan-pagination',
      'plan-create-btn',
      'plan-name-link',
      'plan-status-tag',
      'plan-empty-create',
      'plan-empty-no-project',
      'planlistview-skeleton',
    ]) {
      expect(page).toContain(anchor)
    }
  })

  it('merges the create variant action ahead of the settings control in the band tail (FB/R21)', () => {
    // R25 mode declaration is stated on the page (TB group wording anchor)
    expect(page).toContain('R25 mode declaration')
    expect(page).toContain('class="filter-bar-actions"')
    expect(page).not.toContain('class="header-actions"')
    expect(page.indexOf('plan-create-btn')).toBeLessThan(page.indexOf('<ColumnSettings'))
    expect(page.indexOf('<ColumnSettings')).toBeLessThan(page.indexOf('plan-table'))
  })
})
