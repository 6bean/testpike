/**
 * LLM config list column-configuration wiring contract (N-3 repayment).
 * Mirrors the api-token/mock-endpoints/role/notification/delete-request precedent:
 * the page must really consume the shared useColumnConfig piece, not a second
 * implementation.
 *  - defs exist for every data column of the ledger table (the locked action
 *    column stays outside the loop);
 *  - density rides colCfg.state.density and the columns render through the
 *    visibleColumns loop (hide/order/pin take effect), with stable data-test
 *    anchors for the E2E spectrum;
 *  - this page is sort-exempt (hand-provisioned rows served in full), so the
 *    loop must not claim any sortable header — no dead second whitelist;
 *  - R25 mode declaration: the page carries no tabs and exactly one table, so
 *    the page-level settings popover equals the table scope (mode "scope-aware
 *    single control" per TB group);
 *  - the per-column custom renderers (default tag, type tag, masked key,
 *    status tag, thousands separator) keep working through the loop.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/system/LlmConfigListView.vue', import.meta.url), 'utf8')

describe('llm-config column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('llm', LLM_COLUMNS)")
  })

  it('declares a def for every data column of the config table', () => {
    for (const key of [
      'name',
      'modelType',
      'provider',
      'modelName',
      'apiKeyMasked',
      'baseUrl',
      'status',
      'description',
      'promptLimitTokens',
    ]) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the config table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // the locked action column always renders, so the skeleton adds one slot
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`llm-col-${col.key}`')
    // page/table-level anchors the E2E spectrum relies on
    for (const anchor of ['llm-config-page', 'llm-table', 'llm-type-filter', 'llm-create', 'llm-edit', 'llm-delete']) {
      expect(page).toContain(anchor)
    }
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : LLM_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    // sort-exempt page: the loop never claims a sortable header
    expect(page).not.toContain('sortable')
    // numeric column keeps its right alignment through the loop
    expect(page).toContain("col.key === 'promptLimitTokens' ? 'right' : undefined")
    // per-column custom renderers stay wired inside the single loop
    expect(page).toContain("v-if=\"col.key === 'name'\"")
    expect(page).toContain("v-else-if=\"col.key === 'modelType'\"")
    expect(page).toContain("v-else-if=\"col.key === 'apiKeyMasked'\"")
    expect(page).toContain("v-else-if=\"col.key === 'status'\"")
    expect(page).toContain("v-else-if=\"col.key === 'promptLimitTokens'\"")
    // edit/delete row-command anchors survive the loop conversion
    expect(page).toContain('llm-edit')
    expect(page).toContain('llm-delete')
    // the locked action column keeps its own right-pinned slot outside the loop
    // (widened 160->230 when the golden-replay adopt entry joined edit/delete;
    // assertion-lag fix, the adopt anchor is pinned here as well)
    expect(page).toContain('width="230" fixed="right"')
    expect(page).toContain('data-test="llm-adopt-ledger"')
  })

  it('mounts the settings popover after the toolbar action controls with full piece wiring', () => {
    const band = page.slice(page.indexOf('<div class="toolbar-right">'))
    const bandBlock = band.slice(0, band.indexOf('\n        </div>'))
    expect(bandBlock).toContain('<ColumnSettings')
    // the control block wires all five piece actions (no dead popover)
    for (const evt of [
      '@toggle="colCfg.toggle"',
      '@move="colCfg.move"',
      '@density="colCfg.setDensity"',
      '@reset="colCfg.reset"',
      '@pin="colCfg.toggleFixed"',
    ]) {
      expect(bandBlock).toContain(evt)
    }
    // the popover rows bind the merged state+label view of the piece
    expect(bandBlock).toContain(':columns="settingsRows"')
    // settings sit after the create button (no filter bar on this page)
    expect(bandBlock.indexOf('llm-create')).toBeLessThan(bandBlock.indexOf('<ColumnSettings'))
  })
})
