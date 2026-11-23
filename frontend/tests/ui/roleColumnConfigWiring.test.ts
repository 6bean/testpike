/**
 * Role list table column-configuration wiring contract (N-3 repayment).
 * Mirrors the notification/pool/explore/knowledge/defect/board precedent: the page must
 * really consume the shared useColumnConfig piece, not a second implementation.
 *  - defs exist for every data column of the role table (action column stays pinned outside);
 *  - density rides colCfg.state.density and the columns render through the visibleColumns
 *    loop (hide/order/pin take effect), with stable data-test anchors for the E2E spectrum;
 *  - R25 mode declaration: this page carries no tabs and exactly one table, so the page-level
 *    settings popover equals the table scope (mode "scope-aware single control" per TB group);
 *  - the builtin tag, code cell, data-scope label, status tag and TimeCell keep rendering
 *    through the loop conversion.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/rbac/RoleListView.vue', import.meta.url), 'utf8')

describe('role-list column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('role', ROLE_COLUMNS)")
  })

  it('declares a def for every data column of the role table', () => {
    for (const key of ['name', 'code', 'dataScope', 'description', 'status', 'createdAt']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the role table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`role-col-${col.key}`')
    // E2E-critical row-action anchors survive the loop conversion.
    for (const anchor of ['role-perms', 'role-edit', 'role-delete']) {
      expect(page).toContain(anchor)
    }
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : ROLE_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    // the remark column keeps its overflow tooltip through the loop
    expect(page).toContain(':show-overflow-tooltip="col.key === \'description\'"')
    // per-column custom renderers stay wired inside the single loop
    expect(page).toContain("v-if=\"col.key === 'name'\"")
    expect(page).toContain("v-else-if=\"col.key === 'code'\"")
    expect(page).toContain("v-else-if=\"col.key === 'dataScope'\"")
    expect(page).toContain("v-else-if=\"col.key === 'status'\"")
    expect(page).toContain("v-else-if=\"col.key === 'createdAt'\"")
  })

  it('mounts the settings popover in the page toolbar next to the create action', () => {
    const toolbar = page.slice(page.indexOf('<div class="toolbar">'), page.indexOf('</div>', page.indexOf('toolbar-right')))
    expect(toolbar).toContain('<ColumnSettings')
    // the control block wires all five piece actions (no dead popover)
    for (const evt of [
      '@toggle="colCfg.toggle"',
      '@move="colCfg.move"',
      '@density="colCfg.setDensity"',
      '@reset="colCfg.reset"',
      '@pin="colCfg.toggleFixed"',
    ]) {
      expect(toolbar).toContain(evt)
    }
    // the popover rows bind the merged state+label view of the piece
    expect(toolbar).toContain(':columns="settingsRows"')
  })
})
