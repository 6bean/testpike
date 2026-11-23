/**
 * Executor resource-pool quota table column-configuration wiring contract (N-3 repayment).
 * Mirrors the explore/knowledge/defect/board precedent: the page must really consume
 * the shared useColumnConfig piece, not a second implementation.
 *  - defs exist for every data column of the per-pool quota table;
 *  - density rides colCfg.state.density and the columns render through the
 *    visibleColumns loop (so hide/order/pin controls take effect on every pool card);
 *  - the ColumnSettings popover sits beside refresh in the page action band
 *    (this page carries no filter conditions; AuditLogView precedent, R21);
 *  - the loop keeps the stable data-test anchors the E2E spectrum uses
 *    (pool-card / pool-projects / pool-water / pool-quota-full).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/execution/ExecutorPoolView.vue', import.meta.url), 'utf8')

describe('executor-pool column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('pool', POOL_COLUMNS)")
  })

  it('declares a def for every data column of the quota table', () => {
    for (const key of ['projectName', 'quota', 'slotCap', 'runningPending', 'water']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state into every embedded pool table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`pool-proj-col-${col.key}`')
    // E2E-critical custom cell anchors survive the loop conversion.
    for (const anchor of ['pool-projects-', 'pool-water-', 'pool-quota-full', 'pool-card-']) {
      expect(page).toContain(anchor)
    }
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : POOL_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
  })

  it('mounts the settings popover in the page action band next to refresh', () => {
    const band = page.slice(page.indexOf('class="toolbar"'), page.indexOf('<TableSkeleton'))
    expect(band).toContain('<ColumnSettings')
    // the control block wires all five piece actions (no dead popover)
    for (const evt of [
      '@toggle="colCfg.toggle"',
      '@move="colCfg.move"',
      '@density="colCfg.setDensity"',
      '@reset="colCfg.reset"',
      '@pin="colCfg.toggleFixed"',
    ]) {
      expect(band).toContain(evt)
    }
  })
})
