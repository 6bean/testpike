/**
 * SKILL ledger column-configuration wiring contract (N-3 repayment).
 * Mirrors the training-sample/plan-list precedent: the page must really
 * consume the shared useColumnConfig piece, not a second implementation.
 *  - defs exist for every data column of the manage ledger table (the locked
 *    action column stays outside the loop);
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable data-test
 *    anchors for the E2E spectrum;
 *  - server-side sorting survives the loop and stays single-sourced: the
 *    SORT_KEY_MAP (already the whitelist for the request payload) decides
 *    the per-column sortable flag;
 *  - FB/R21 revision 3 (merge band): no row-level batch bar and 3 condition
 *    controls, so the page-level variant actions (recycle entry + create)
 *    ride the .filter-bar-actions trailing slot with the settings control
 *    (variant first, then settings) and no header-actions shell exists;
 *  - R25 mode declaration: the settings control lives inside the manage-only
 *    section below the tabs (honest absence for the market/memory views).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/skill/SkillListView.vue', import.meta.url),
  'utf8',
)

describe('skill ledger column-config wiring (N-3)', () => {
  it('consumes the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('skill', SKILL_COLUMNS)")
    expect(page).toContain('const settingsRows = computed(')
  })

  it('declares a def for every data column of the manage ledger', () => {
    for (const key of ['name', 'typeLabel', 'status', 'currentVersion', 'updatedAt']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('binds density, dynamic skeleton and the visibleColumns loop', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`skill-col-${col.key}`')
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : SKILL_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    expect(page).toContain(`:sortable="SORT_KEY_MAP[col.key] ? 'custom' : false"`)
    expect(page).toContain(`:align="col.key === 'currentVersion' ? 'center' : undefined"`)
  })

  it('keeps custom cell renderers wired inside the single loop', () => {
    for (const key of ['name', 'status', 'currentVersion']) {
      expect(page).toContain(`col.key === '${key}'`)
    }
  })

  it('rides the merged trailing slot without a header shell (R21 rev 3)', () => {
    expect(page).toContain('class="filter-bar-actions"')
    expect(page).not.toContain('class="header-actions"')
    expect(page).not.toContain('class="page-header"')
    const actions = page.indexOf('skill-filter-actions')
    expect(actions).toBeGreaterThan(-1)
    // variant actions stay before the settings control inside the slot
    expect(page.indexOf('skill-create-btn')).toBeGreaterThan(actions)
    expect(page.indexOf('<ColumnSettings')).toBeGreaterThan(page.indexOf('skill-create-btn'))
    expect(page.indexOf('<ColumnSettings')).toBeLessThan(page.indexOf('skill-table'))
  })

  it('keeps the query submit inline at the end of the condition flow', () => {
    expect(page.indexOf('data-test="skill-search"')).toBeLessThan(page.indexOf('skill-filter-actions'))
  })

  it('declares the R25 honest-absence scope for the tabbed page', () => {
    expect(page).toContain('R25 mode declaration')
    expect(page).toContain('honest absence')
  })

  it('keeps the recycle entry permission gate on the moved button', () => {
    expect(page).toContain(`v-if="auth.hasPerm('perm:tool:tool-market')" :icon="Delete" data-test="skill-recycle-open"`)
  })

  it('preserves the E2E-critical anchors through the loop conversion', () => {
    for (const anchor of [
      'skill-list-page',
      'skill-table',
      'skill-keyword',
      'skill-type-select',
      'skill-status-select',
      'skill-search',
      'skill-create-btn',
      'skill-recycle-open',
      'skill-status-tag',
      'skill-reject-reason',
      'skill-preview',
      'skilllistview-skeleton',
    ]) {
      expect(page).toContain(anchor)
    }
  })
})
