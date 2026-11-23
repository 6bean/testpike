/**
 * Notification-center inbox table column-configuration wiring contract (N-3 repayment).
 * Mirrors the pool/explore/knowledge/defect/board precedent: the page must really consume
 * the shared useColumnConfig piece, not a second implementation.
 *  - defs exist for every data column of the inbox table (action column stays pinned outside);
 *  - density rides colCfg.state.density and the columns render through the visibleColumns
 *    loop (hide/order/pin take effect), with stable data-test anchors for the E2E spectrum;
 *  - R25 mode declaration: the page carries two tabs (list + prefs) and the prefs tab has no
 *    table, so the settings popover rides the list tab's own toolbar (mode "control placement"
 *    per rulings TB group) instead of a page-level control with a silent-fail scope;
 *  - sortable="custom" columns keep server-side sorting through the loop conversion.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/notification/NotificationCenterView.vue', import.meta.url), 'utf8')

describe('notification-center column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('notification', NOTIF_COLUMNS)")
  })

  it('declares a def for every data column of the inbox table', () => {
    for (const key of ['eventType', 'title', 'content', 'readFlag', 'createdAt']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the inbox table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`notif-col-${col.key}`')
    // E2E-critical custom cell anchors survive the loop conversion.
    for (const anchor of ['notif-row-', 'notif-unread-dot', 'notif-mark-read-', 'notif-goto-']) {
      expect(page).toContain(anchor)
    }
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : NOTIF_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    // server-side sorting keeps its per-column custom flag through the loop
    expect(page).toContain(':sortable="NOTIF_SORTABLE.has(col.key) ? \'custom\' : false"')
    expect(page).toContain(':show-overflow-tooltip="col.key === \'content\'"')
  })

  it('mounts the settings popover inside the list tab toolbar (R25 control placement)', () => {
    const listTab = page.slice(page.indexOf('name="list"'), page.indexOf('</el-tab-pane>'))
    expect(listTab).toContain('<ColumnSettings')
    // the control block wires all five piece actions (no dead popover)
    for (const evt of [
      '@toggle="colCfg.toggle"',
      '@move="colCfg.move"',
      '@density="colCfg.setDensity"',
      '@reset="colCfg.reset"',
      '@pin="colCfg.toggleFixed"',
    ]) {
      expect(listTab).toContain(evt)
    }
    // the popover rows bind the merged state+label view of the piece
    expect(listTab).toContain(':columns="settingsRows"')
  })
})
