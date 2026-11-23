/**
 * AI sample-board column-configuration wiring contract (N-3 repayment).
 * Mirrors the step-healing/prompt-template/scheduler-health precedent: the page
 * must consume the shared useColumnConfig piece rather than a second
 * implementation.
 *  - defs exist for every data column of the sample table; the page has no
 *    locked slot outside the loop, so the skeleton mirrors the visible data
 *    count exactly (no +1 like the action-column pages);
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable per-column
 *    data-test anchors for the E2E spectrum;
 *  - the long anchor cell keeps its overflow-tooltip behaviour through the
 *    loop (no rendering regression when columns become configurable);
 *  - the inner cell anchors the E2E spectrum already targets (step number,
 *    hit source, review tag, coordinate) survive the loop rewrite;
 *  - R21: the settings control rides the filter-bar trailing slot ahead of
 *    refresh (view-state actions only, no page-level variant action exists on
 *    this page); the leftover header-band styles are dropped as dead CSS;
 *  - R25 mode declaration: no tabs and one page-surface table, so the
 *    page-level column-settings control equals the table scope.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/appauto/AiSampleBoardView.vue', import.meta.url),
  'utf8',
)

const DATA_KEYS = ['step', 'anchor', 'hitSource', 'review', 'coord', 'device', 'createdAt']

describe('ai-sample-board column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('ai-sample-board', AI_SAMPLE_BOARD_COLUMNS)")
  })

  it('declares a def for every data column of the sample table', () => {
    for (const key of DATA_KEYS) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and a visible-count skeleton into the table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // no locked column exists outside the loop on this board, so the
    // skeleton equals the visible data count (not +1)
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`ai-samples-col-${col.key}`')
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : AI_SAMPLE_BOARD_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    for (const key of DATA_KEYS) {
      expect(page).toContain(`col.key === '${key}'`)
    }
  })

  it('keeps the anchor tooltip behaviour through the loop', () => {
    expect(page).toContain(`:show-overflow-tooltip="col.key === 'anchor'"`)
  })

  it('keeps the inner cell anchors the E2E spectrum targets', () => {
    for (const anchor of ['ai-samples-step-no', 'ai-samples-hit-source', 'ai-samples-review-tag', 'ai-samples-coord']) {
      expect(page).toContain(anchor)
    }
  })

  it('places the settings control in the filter trailing slot ahead of refresh (R21)', () => {
    const actionsIdx = page.indexOf('class="filter-bar-actions"')
    expect(actionsIdx).toBeGreaterThan(-1)
    const settingsIdx = page.indexOf('<ColumnSettings')
    const refreshIdx = page.indexOf('ai-samples-refresh')
    expect(settingsIdx).toBeGreaterThan(actionsIdx)
    expect(refreshIdx).toBeGreaterThan(settingsIdx)
    // the existing screening control and its anchor stay in the condition flow
    expect(page.indexOf('ai-samples-case-select')).toBeLessThan(actionsIdx)
  })

  it('drops the leftover header-band styles once the band is gone', () => {
    expect(page).not.toContain('.page-header {')
    expect(page).not.toContain('.header-actions {')
    expect(page).not.toContain('.page-title {')
  })
})
