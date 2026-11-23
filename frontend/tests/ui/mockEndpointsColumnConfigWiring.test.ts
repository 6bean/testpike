/**
 * Mock endpoint inventory column-configuration wiring contract (N-3 repayment).
 * Mirrors the role/notification/pool/explore/knowledge/defect/board precedent:
 * the page must really consume the shared useColumnConfig piece, not a second
 * implementation.
 *  - defs exist for every data column of the inventory table (the locked
 *    action column stays outside the loop);
 *  - density rides colCfg.state.density and the columns render through the
 *    visibleColumns loop (hide/order/pin take effect), with stable data-test
 *    anchors for the E2E spectrum;
 *  - R25 mode declaration: this page carries no tabs and exactly one table,
 *    so the page-level settings popover equals the table scope (mode
 *    "scope-aware single control" per TB group);
 *  - the endpoint cell (method tag + path + url line), space link, status
 *    tags, hits cell and short-time renderer keep working through the loop.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/mock/MockEndpointsView.vue', import.meta.url), 'utf8')

describe('mock-endpoints column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('mock-endpoints', MOCK_ENDPOINT_COLUMNS)")
  })

  it('declares a def for every data column of the inventory table', () => {
    for (const key of ['pathPattern', 'spaceName', 'spaceStatus', 'hits7d', 'updatedAt']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the inventory table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + (canWrite ? 1 : 0)"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`mock-col-${col.key}`')
    // E2E-critical row anchors survive the loop conversion.
    for (const anchor of [
      'mock-endpoints-table',
      'mock-endpoints-space-jump',
      'mock-endpoints-copy-curl',
      'mock-endpoints-try',
      'mock-endpoints-hits',
      'mock-endpoints-act-edit',
      'mock-endpoints-pagination',
    ]) {
      expect(page).toContain(anchor)
    }
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : ENDPOINT_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    // every data column keeps custom sorting (server-side SORT_KEY_MAP unchanged)
    expect(page).toContain('sortable="custom"')
    // the hits column keeps its centered alignment through the loop
    expect(page).toContain("col.key === 'hits7d'")
    // per-column custom renderers stay wired inside the single loop
    expect(page).toContain("v-if=\"col.key === 'pathPattern'\"")
    expect(page).toContain("v-else-if=\"col.key === 'spaceName'\"")
    expect(page).toContain("v-else-if=\"col.key === 'spaceStatus'\"")
    expect(page).toContain("v-else-if=\"col.key === 'hits7d'\"")
    expect(page).toContain('<span v-else class="muted">{{ shortTime(row.updatedAt) }}</span>')
  })

  it('mounts the settings popover in the filter-bar action slot with full piece wiring', () => {
    const band = page.slice(page.indexOf('<div class="filter-bar-actions">'))
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
