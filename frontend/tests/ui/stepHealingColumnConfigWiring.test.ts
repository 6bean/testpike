/**
 * Step-healing column-configuration wiring contract (N-3 repayment).
 * Mirrors the prompt-template/scheduler-health precedent: the page must consume
 * the shared useColumnConfig piece rather than a second implementation.
 *  - defs exist for every data column of the suggestion table; only the locked
 *    action slot renders outside the loop, so the skeleton mirrors the
 *    visible data count plus that one action column;
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable per-column
 *    data-test anchors for the E2E spectrum;
 *  - the long anchor cell keeps its overflow-tooltip behaviour through the
 *    loop (no rendering regression when columns become configurable);
 *  - R21: the settings control rides the filter-bar trailing slot ahead of
 *    refresh (view-state actions only, no page-level variant action exists on
 *    this page); the leftover header-band styles are dropped as dead CSS;
 *  - R25 mode declaration: no tabs and one page-surface table, so the
 *    page-level column-settings control equals the table scope.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/appauto/StepHealingView.vue', import.meta.url),
  'utf8',
)

const DATA_KEYS = ['step', 'anchor', 'source', 'proposed', 'reason', 'status', 'adoptedVer', 'createdAt']

describe('step-healing column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('step-healing', STEP_HEALING_COLUMNS)")
  })

  it('declares a def for every data column of the suggestion table', () => {
    for (const key of DATA_KEYS) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and a visible-count skeleton into the table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // the locked action column is the only slot outside the loop
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`step-healing-col-${col.key}`')
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : STEP_HEALING_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    for (const key of DATA_KEYS) {
      expect(page).toContain(`col.key === '${key}'`)
    }
  })

  it('keeps the anchor tooltip behaviour through the loop', () => {
    expect(page).toContain(':show-overflow-tooltip="col.key === \'anchor\'"')
  })

  it('keeps the action column locked outside the loop after the data columns', () => {
    const loopIdx = page.indexOf('v-for="col in colCfg.visibleColumns.value"')
    const adoptIdx = page.indexOf('step-healing-adopt-btn')
    expect(loopIdx).toBeGreaterThan(-1)
    expect(adoptIdx).toBeGreaterThan(loopIdx)
    expect(page).toContain(":label=\"t('appauto.healing.colActions')\"")
  })

  it('places the settings control in the filter trailing slot ahead of refresh (R21)', () => {
    const actionsIdx = page.indexOf('class="filter-bar-actions"')
    expect(actionsIdx).toBeGreaterThan(-1)
    const settingsIdx = page.indexOf('<ColumnSettings')
    const refreshIdx = page.indexOf('step-healing-refresh')
    expect(settingsIdx).toBeGreaterThan(actionsIdx)
    expect(refreshIdx).toBeGreaterThan(settingsIdx)
    // the existing screening controls and their anchors stay in the condition flow
    expect(page.indexOf('step-healing-case-select')).toBeLessThan(actionsIdx)
    expect(page.indexOf('step-healing-status-select')).toBeLessThan(actionsIdx)
  })

  it('drops the leftover header-band styles once the band is gone', () => {
    expect(page).not.toContain('.page-header {')
    expect(page).not.toContain('.header-actions {')
    expect(page).not.toContain('.page-title {')
  })
})
