/**
 * Plan-board column-configuration wiring contract (SE/N-3 repayment).
 * Asserts the page really consumes the shared useColumnConfig piece:
 *  - defs exist for every data column of the progress-summary table;
 *  - DataTablePage receives :column-config (width write-back) and :size
 *    (density), and the columns render through the visibleColumns loop
 *    instead of a static list (so hide/order controls take effect);
 *  - the ColumnSettings popover sits in the filter-bar trailing slot,
 *    never in a header band (R21 view-state actions placement);
 *  - the loop keeps the stable data-test anchors the E2E spectrum uses.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/plan/BoardView.vue', import.meta.url), 'utf8')

describe('plan-board column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('board', BOARD_COLUMNS)")
  })

  it('declares a def for every data column of the summary table', () => {
    for (const key of ['planName', 'statusLabel', 'caseCount', 'passed', 'failed', 'running', 'notRun', 'passRate', 'period']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds width/density state back into the table shell', () => {
    expect(page).toContain(':column-config="colCfg"')
    expect(page).toContain(':size="colCfg.state.density"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`board-col-${col.key}`')
    // E2E-critical custom cell anchors survive the loop conversion.
    for (const anchor of ['board-plan-link', 'board-status-tag', 'board-rate-bar', 'board-rate-text']) {
      expect(page).toContain(anchor)
    }
  })

  it('mounts the settings popover in the filter-bar trailing slot', () => {
    const trailing = page.slice(page.indexOf('filter-bar-actions'), page.indexOf('</div>\n      </div>'))
    expect(trailing).toContain('<ColumnSettings')
    // the control block wires all five piece actions (no dead popover)
    for (const evt of ['@toggle="colCfg.toggle"', '@move="colCfg.move"', '@density="colCfg.setDensity"', '@reset="colCfg.reset"', '@pin="colCfg.toggleFixed"']) {
      expect(trailing).toContain(evt)
    }
  })
})
