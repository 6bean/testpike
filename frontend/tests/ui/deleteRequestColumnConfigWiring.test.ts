/**
 * Delete-request ledger column-configuration wiring contract (N-3 repayment).
 * Mirrors the project/api-token/mock-endpoints/role/notification/pool/explore/
 * knowledge/defect/board precedent: the page must really consume the shared
 * useColumnConfig piece, not a second implementation.
 *  - defs exist for every data column of the ledger table (the locked action
 *    column stays outside the loop);
 *  - density rides colCfg.state.density and the columns render through the
 *    visibleColumns loop (hide/order/pin take effect), with stable data-test
 *    anchors for the E2E spectrum;
 *  - server-side sorting survives the loop and stays single-sourced: the
 *    SORT_KEY_MAP (already the whitelist for the request payload) decides the
 *    per-column sortable flag, so reason/rejectReason stay honestly unsorted
 *    with no dead second list;
 *  - R25 mode declaration: this page carries no tabs and exactly one table,
 *    so the page-level settings popover equals the table scope (mode
 *    "scope-aware single control" per TB group);
 *  - the target tag+summary, status tag, reject-reason fallback and TimeCell
 *    renderers keep working through the loop.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/case/DeleteRequestView.vue', import.meta.url), 'utf8')

describe('delete-request column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('delreq', DELREQ_COLUMNS)")
  })

  it('declares a def for every data column of the ledger table', () => {
    for (const key of ['targetSummary', 'reason', 'status', 'rejectReason', 'createdAt', 'handledAt']) {
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
    expect(page).toContain('`delreq-col-${col.key}`')
    // E2E-critical anchors survive the loop conversion.
    for (const anchor of [
      'delreq-page',
      'delreq-table',
      'delreq-status-filter',
      'delreq-search',
      'delreq-pager',
      'delreq-empty',
      'delreq-empty-no-project',
    ]) {
      expect(page).toContain(anchor)
    }
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : DELREQ_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    // sortable decision stays single-sourced on the existing SORT_KEY_MAP
    // whitelist (no second per-column list), so free-text columns never claim
    // a server sort the backend would reject
    expect(page).toContain(":sortable=\"SORT_KEY_MAP[col.key] ? 'custom' : false\"")
    expect(page).toContain(
      ":show-overflow-tooltip=\"col.key === 'reason' || col.key === 'rejectReason' || undefined\"",
    )
    // per-column custom renderers stay wired inside the single loop
    expect(page).toContain("v-if=\"col.key === 'targetSummary'\"")
    expect(page).toContain("v-else-if=\"col.key === 'status'\"")
    expect(page).toContain("v-else-if=\"col.key === 'rejectReason'\"")
    expect(page).toContain("v-else-if=\"col.key === 'createdAt'\"")
    expect(page).toContain("v-else-if=\"col.key === 'handledAt'\"")
    // the row approve/reject command anchors survive the loop conversion
    expect(page).toContain('delreq-target-${row.id}')
    // the locked action column keeps its own right-pinned slot outside the loop
    expect(page).toContain('width="170" fixed="right"')
  })

  it('mounts the settings popover in the toolbar action slot with full piece wiring', () => {
    const band = page.slice(page.indexOf('<div class="toolbar-right">'))
    const bandBlock = band.slice(0, band.indexOf('</div>'))
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
  })
})
