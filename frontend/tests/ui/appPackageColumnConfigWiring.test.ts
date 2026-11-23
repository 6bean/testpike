/**
 * App package ledger column-configuration wiring contract (N-3 repayment).
 * Mirrors the mock-spaces precedent shape: the page must really consume the
 * shared useColumnConfig piece, not a second implementation.
 *  - a def exists for every data column of the package ledger (the locked
 *    action column stays outside the loop);
 *  - density rides colCfg.state.density and the columns render through the
 *    visibleColumns loop (hide/order/pin take effect), with stable data-test
 *    anchors for the E2E spectrum;
 *  - this page is sort-exempt (the full per-project registry is served at once
 *    ordered by id DESC), so no column may claim a sortable header — the
 *    tp-sort-exempt declaration must stay honest, no dead second whitelist;
 *  - R25 mode declaration: the page carries no tabs and exactly one table, so
 *    the page-level settings popover equals the table scope (scope-aware single
 *    control per TB group); the control rides the filter-bar trailing slot
 *    ahead of refresh and behind the migrated create entry (FB merge band).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/appauto/AppPackageView.vue', import.meta.url), 'utf8')

describe('app-package column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('app-package', APP_PACKAGE_COLUMNS)")
  })

  it('declares a def for every data column of the package ledger', () => {
    for (const key of ['name', 'packageName', 'version', 'platform', 'artifact', 'source', 'permRef', 'updated']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the package table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // the locked action column always renders, so the skeleton adds one slot
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`package-col-${col.key}`')
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':min-width="col.width ? undefined : APP_PACKAGE_FLEX_MIN[col.key]"')
    // the per-key renderers keep working through the loop
    for (const anchor of [
      'package-name',
      'package-version',
      'package-artifact-uploaded',
      'package-artifact-missing',
      'package-source',
    ]) {
      expect(page).toContain(anchor)
    }
    // page/table-level anchors the E2E spectrum relies on
    for (const anchor of ['package-filter-bar', 'package-table', 'package-refresh', 'package-create-btn']) {
      expect(page).toContain(anchor)
    }
  })

  it('mounts the settings control in the filter-bar tail behind create and ahead of refresh (R25/FB)', () => {
    expect(page).toContain('class="filter-bar-actions"')
    const createAt = page.indexOf('data-test="package-create-btn"')
    const settingsAt = page.indexOf('<ColumnSettings')
    const refreshAt = page.indexOf('data-test="package-refresh"')
    expect(createAt).toBeGreaterThan(-1)
    expect(settingsAt, 'settings control must ride the trailing slot').toBeGreaterThan(-1)
    expect(createAt, 'page-level variant action stays the slot first item').toBeLessThan(settingsAt)
    expect(settingsAt, 'view-state settings control precedes refresh').toBeLessThan(refreshAt)
    expect(page).toContain('@density="colCfg.setDensity"')
    expect(page).toContain('@pin="colCfg.toggleFixed"')
  })

  it('keeps the sort-exempt declaration honest: no sortable header in the loop', () => {
    expect(page).toContain('tp-sort-exempt')
    expect(page).not.toContain('sortable')
  })
})
