/**
 * Prompt-template column-configuration wiring contract (N-3 repayment).
 * Mirrors the scheduler-health/matrix-report precedent: the page must consume
 * the shared useColumnConfig piece rather than a second implementation.
 *  - defs exist for every data column of the version table; only the locked
 *    action slot renders outside the loop, so the skeleton mirrors the
 *    visible data count plus that one action column;
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable per-column
 *    data-test anchors for the E2E spectrum;
 *  - the ID header label is a registered locale key in both languages
 *    (no hardcoded header text, locale single source);
 *  - R21 same-band merge: no row-level batch bar and two screening controls
 *    (≤ the theme threshold), so the page-level variant action "save new
 *    version" rides the filter-bar trailing slot ahead of the settings
 *    control and the now-empty header band is dropped; the search submit
 *    button stays inline at the end of the condition flow;
 *  - R25 mode declaration: no tabs and one page-surface table, so the
 *    page-level column-settings control equals the table scope.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/prompt/PromptTemplateListView.vue', import.meta.url),
  'utf8',
)
const zh = readFileSync(new URL('../../src/utils/locales/zh-CN.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../../src/utils/locales/en-US.ts', import.meta.url), 'utf8')

function promptTplBlock(src: string): string {
  const start = src.indexOf('\n  promptTpl: {')
  expect(start).toBeGreaterThan(-1)
  const end = src.indexOf('\n  }', start + 1)
  expect(end).toBeGreaterThan(start)
  return src.slice(start, end)
}

describe('prompt-template column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('prompt-template', PROMPT_TEMPLATE_COLUMNS)")
  })

  it('declares a def for every data column of the version table', () => {
    for (const key of ['id', 'name', 'domain', 'version', 'status', 'rollbackTo', 'createdAt']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('registers the ID header key in both locales', () => {
    for (const loc of [zh, en]) {
      expect(promptTplBlock(loc)).toContain("colId: 'ID',")
    }
  })

  it('feeds density state and a visible-count skeleton into the table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // the locked action column is the only slot outside the loop
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`prompt-tpl-col-${col.key}`')
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : PROMPT_TEMPLATE_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    for (const key of ['id', 'name', 'domain', 'version', 'status', 'rollbackTo', 'createdAt']) {
      expect(page).toContain(`col.key === '${key}'`)
    }
  })

  it('keeps the action column locked outside the loop after the data columns', () => {
    const loopIdx = page.indexOf('v-for="col in colCfg.visibleColumns.value"')
    const actionIdx = page.indexOf('prompt-tpl-rollback')
    expect(loopIdx).toBeGreaterThan(-1)
    expect(actionIdx).toBeGreaterThan(loopIdx)
    expect(page).toContain(':label="t(\'promptTpl.colAction\')"')
  })

  it('merges the variant action into the filter trailing slot ahead of settings (R21)', () => {
    const actionsIdx = page.indexOf('class="filter-bar-actions"')
    expect(actionsIdx).toBeGreaterThan(-1)
    const newIdx = page.indexOf('prompt-tpl-new')
    const settingsIdx = page.indexOf('<ColumnSettings')
    expect(newIdx).toBeGreaterThan(actionsIdx)
    expect(settingsIdx).toBeGreaterThan(newIdx)
    // the search submit button stays inline at the end of the condition flow
    expect(page.indexOf('prompt-tpl-search')).toBeLessThan(actionsIdx)
    // the header-only band is dropped once the variant action moved down
    expect(page).not.toContain('class="toolbar"')
  })
})
