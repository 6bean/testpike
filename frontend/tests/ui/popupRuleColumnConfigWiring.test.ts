/**
 * Popup-rule approval column-configuration wiring contract (N-3 repayment).
 * Mirrors the credential-vault/device-wall/plan-list precedent: the page must
 * really consume the shared useColumnConfig piece, not a second implementation.
 *  - defs exist for every data column of the approval table; the expand slot
 *    and the locked action column stay outside the loop;
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable per-column
 *    data-test anchors for the E2E spectrum;
 *  - the page keeps its sort-exempt declaration (line 1), so the loop columns
 *    declare no sortable attribute (no second per-column list);
 *  - FB/R21 same-band merge: no row-level batch bar and one screening control
 *    (<= the themed 4-control threshold), so the learn variant action rides the
 *    filter-bar trailing slot ahead of the settings control, settings sits
 *    ahead of refresh, and the emptied header band is gone;
 *  - R25 mode declaration: no tabs and one page-surface table, so the
 *    page-level column-settings control equals the approval scope.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/appauto/PopupRuleApprovalView.vue', import.meta.url),
  'utf8',
)

describe('popup-rule column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('popup-rule', POPUP_RULE_COLUMNS)")
  })

  it('declares a def for every data column of the approval table', () => {
    for (const key of ['title', 'sourceTask', 'from', 'status', 'createdAt']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the approval table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // expand + action columns always render, so the skeleton adds two slots
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 2"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`popup-rule-col-${col.key}`')
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : POPUP_RULE_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    expect(page).toContain(':show-overflow-tooltip="POPUP_RULE_TOOLTIP.has(col.key)"')
    // per-column custom renderers stay wired inside the single loop
    expect(page).toContain("v-if=\"col.key === 'title'\"")
    expect(page).toContain("v-else-if=\"col.key === 'sourceTask'\"")
    expect(page).toContain("v-else-if=\"col.key === 'from'\"")
    expect(page).toContain("v-else-if=\"col.key === 'status'\"")
    expect(page).toContain("v-else-if=\"col.key === 'createdAt'\"")
    // the locked action column keeps its own slot outside the loop
    expect(page).toContain('width="170" fixed="right"')
  })

  it('keeps the sort-exempt declaration without introducing sortable in the loop', () => {
    // page-level exemption stays stated on line 1 (hand-authored popup rules served in full)
    expect(page).toContain('tp-sort-exempt')
    expect(page).not.toContain(':sortable')
  })

  it('merges the same band: learn rides the trailing slot, header band is gone', () => {
    expect(page).not.toContain('header-actions')
    expect(page).not.toContain('page-header')
    const learn = page.indexOf('popup-rule-learn-btn')
    const settings = page.indexOf('<ColumnSettings')
    const refresh = page.indexOf('popup-rule-refresh')
    expect(learn).toBeGreaterThan(-1)
    expect(settings).toBeGreaterThan(learn)
    expect(refresh).toBeGreaterThan(settings)
    expect(page).toContain('class="filter-bar-actions"')
  })

  it('keeps the E2E-critical anchors through the loop conversion', () => {
    for (const anchor of [
      'popup-rule-page',
      'popup-rule-filter-bar',
      'popup-rule-status-select',
      'popup-rule-learn-btn',
      'popup-rule-refresh',
      'popup-rule-table',
      'popup-rule-title',
      'popup-rule-source-task',
      'popup-rule-from',
      'popup-rule-status-tag',
      'popup-rule-gate-off-alert',
      'popup-rule-confirm-btn',
      'popup-rule-reject-btn',
    ]) {
      expect(page).toContain(anchor)
    }
  })

  it('states the R25 scope declaration and the SE-1 fixed-enum exemption', () => {
    expect(page).toContain('R25 mode declaration')
    expect(page).toContain('data-se1-exempt')
  })
})
