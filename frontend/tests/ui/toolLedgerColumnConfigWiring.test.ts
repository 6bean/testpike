/**
 * Tool ledger column-configuration wiring contract (N-3 repayment).
 * Same posture as the skill-ledger/training-sample precedent: the page
 * consumes the shared useColumnConfig piece, never a second implementation.
 *  - defs cover every data column of the manage ledger (the locked action
 *    column stays outside the loop);
 *  - density rides colCfg.state.density, columns render through the
 *    visibleColumns loop, sortable stays single-sourced on SORT_KEY_MAP;
 *  - FB/R21 revision 3 (merge band): no row-level batch bar and 4 condition
 *    controls (at the threshold), so the create variant action rides the
 *    .filter-bar-actions trailing slot with the settings control (variant
 *    first, then settings) and the emptied header shell is removed;
 *  - R25 mode declaration: the control lives inside the manage-only section
 *    below the tabs (honest absence for the market view).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/tool/ToolListView.vue', import.meta.url),
  'utf8',
)

describe('tool ledger column-config wiring (N-3)', () => {
  it('consumes the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('tool', TOOL_COLUMNS)")
    expect(page).toContain('const settingsRows = computed(')
  })

  it('declares a def for every data column of the manage ledger', () => {
    for (const key of ['name', 'typeLabel', 'category', 'execKind', 'status', 'currentVersion', 'updatedAt']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('binds density, dynamic skeleton and the visibleColumns loop', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`tool-col-${col.key}`')
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : TOOL_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    expect(page).toContain(`:sortable="SORT_KEY_MAP[col.key] ? 'custom' : false"`)
    expect(page).toContain(`:align="col.key === 'currentVersion' ? 'center' : undefined"`)
  })

  it('keeps custom cell renderers wired inside the single loop', () => {
    for (const key of ['name', 'category', 'execKind', 'status', 'currentVersion']) {
      expect(page).toContain(`col.key === '${key}'`)
    }
    // the write-semantics hint (display-only; authority stays in core) rides
    // inside the name branch untouched
    expect(page).toContain('data-test="tool-write-tag"')
  })

  it('rides the merged trailing slot without a header shell (R21 rev 3)', () => {
    expect(page).toContain('class="filter-bar-actions"')
    expect(page).not.toContain('class="header-actions"')
    expect(page).not.toContain('class="page-header"')
    const actions = page.indexOf('tool-filter-actions')
    expect(actions).toBeGreaterThan(-1)
    expect(page.indexOf('tool-create-btn')).toBeGreaterThan(actions)
    expect(page.indexOf('<ColumnSettings')).toBeGreaterThan(page.indexOf('tool-create-btn'))
    expect(page.indexOf('<ColumnSettings')).toBeLessThan(page.indexOf('tool-table'))
  })

  it('keeps the query submit inline at the end of the condition flow', () => {
    expect(page.indexOf('data-test="tool-search"')).toBeLessThan(page.indexOf('tool-filter-actions'))
  })

  it('declares the R25 honest-absence scope for the tabbed page', () => {
    expect(page).toContain('R25 mode declaration')
    expect(page).toContain('honest absence')
  })

  it('preserves the E2E-critical anchors through the loop conversion', () => {
    for (const anchor of [
      'toollistview-filter-bar',
      'tool-table',
      'tool-keyword',
      'tool-type-select',
      'tool-status-select',
      'tool-search',
      'tool-create-btn',
      'tool-status-tag',
      'tool-reject-reason',
      'tool-desc',
      'toollistview-skeleton',
    ]) {
      expect(page).toContain(anchor)
    }
  })
})
