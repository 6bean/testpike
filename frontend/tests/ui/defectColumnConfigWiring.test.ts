/**
 * Defect-library column-configuration wiring contract (SE/N-3 repayment).
 * Mirrors the plan-board precedent: the page must really consume the shared
 * useColumnConfig piece, not a second implementation.
 *  - defs exist for every data column of the library table;
 *  - DataTablePage receives :column-config (width write-back) and :size
 *    (density), and the columns render through the visibleColumns loop
 *    instead of a static list (so hide/order controls take effect);
 *  - the ColumnSettings popover sits in the filter-bar trailing slot,
 *    never in a header band (R21 view-state actions placement);
 *  - the loop keeps the stable data-test anchors the E2E spectrum uses and
 *    the header-sort set aligned with the server-side SORT_KEY_MAP whitelist.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/defect/DefectListView.vue', import.meta.url), 'utf8')

describe('defect-library column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("import DataTablePage from '@/components/DataTablePage.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('defect', DEFECT_COLUMNS)")
  })

  it('declares a def for every data column of the library table', () => {
    for (const key of ['title', 'severity', 'status', 'assigneeName', 'reporterName', 'externalKey', 'jiraSync', 'createdAt']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds width/density state back into the table shell', () => {
    expect(page).toContain(':column-config="colCfg"')
    expect(page).toContain(':size="colCfg.state.density"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`defect-col-${col.key}`')
    // E2E-critical custom cell anchors survive the loop conversion.
    for (const anchor of [
      'defect-title-link',
      'defect-auto-tag',
      'defect-severity-tag',
      'defect-status-tag',
      'defect-jira-sync-tag',
    ]) {
      expect(page).toContain(anchor)
    }
    // header sort stays pinned to the server-side whitelist keys only
    expect(page).toContain("const DEFECT_SORTABLE = ['title', 'severity', 'status', 'createdAt']")
    expect(page).toContain(":sortable=\"DEFECT_SORTABLE.includes(col.key) ? 'custom' : false\"")
  })

  it('mounts the settings popover in the filter-bar trailing slot', () => {
    const trailing = page.slice(page.indexOf('filter-bar-actions'), page.indexOf('</div>\n        </div>'))
    expect(trailing).toContain('<ColumnSettings')
    // the control block wires all five piece actions (no dead popover)
    for (const evt of [
      '@toggle="colCfg.toggle"',
      '@move="colCfg.move"',
      '@density="colCfg.setDensity"',
      '@reset="colCfg.reset"',
      '@pin="colCfg.toggleFixed"',
    ]) {
      expect(trailing).toContain(evt)
    }
  })
})
