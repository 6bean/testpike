/**
 * Credential-vault column-configuration wiring contract (N-3 repayment).
 * Mirrors the device-wall/engine-center/plan-list precedent: the page must
 * really consume the shared useColumnConfig piece, not a second implementation.
 *  - defs exist for every data column of the vault table (the locked action
 *    column stays outside the loop);
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable per-column
 *    data-test anchors for the E2E spectrum;
 *  - the page keeps its sort-exempt declaration (line 1): vault entries are
 *    operator-provisioned secrets, so the loop columns declare no sortable
 *    attribute (no second per-column list);
 *  - FB/R21 same-band merge stays in place (no bulk bar, one screening
 *    control): the create variant action rides the filter-bar trailing slot
 *    and the settings control sits ahead of refresh;
 *  - R25 mode declaration: no tabs and one page-surface table, so the
 *    page-level column-settings control equals the vault scope.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/appauto/CredentialVaultView.vue', import.meta.url),
  'utf8',
)

describe('credential-vault column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('credential-vault', CREDENTIAL_COLUMNS)")
  })

  it('declares a def for every data column of the vault table', () => {
    for (const key of ['name', 'credKey', 'secret', 'status', 'remark', 'updated']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and dynamic skeleton into the vault table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // the locked action column always renders, so the skeleton adds one slot
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length + 1"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`credential-col-${col.key}`')
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : CREDENTIAL_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    expect(page).toContain(':show-overflow-tooltip="CREDENTIAL_TOOLTIP.has(col.key)"')
    // per-column custom renderers stay wired inside the single loop
    expect(page).toContain("v-if=\"col.key === 'name'\"")
    expect(page).toContain("v-else-if=\"col.key === 'credKey'\"")
    expect(page).toContain("v-else-if=\"col.key === 'secret'\"")
    expect(page).toContain("v-else-if=\"col.key === 'status'\"")
    expect(page).toContain("v-else-if=\"col.key === 'updated'\"")
    // the locked action column keeps its own slot outside the loop
    expect(page).toContain('width="310" fixed="right"')
  })

  it('keeps the sort-exempt declaration without introducing sortable in the loop', () => {
    // page-level exemption stays stated on line 1 (operator-provisioned secret list)
    expect(page).toContain('tp-sort-exempt')
    expect(page).not.toContain(':sortable')
  })

  it('keeps the E2E-critical anchors through the loop conversion', () => {
    for (const anchor of [
      'credential-vault-page',
      'credential-filter-bar',
      'credential-status-select',
      'credential-create-btn',
      'credential-refresh',
      'credential-table',
      'credential-name',
      'credential-key',
      'credential-mask',
      'credential-enabled-switch',
      'credential-edit-btn',
      'credential-delete-btn',
    ]) {
      expect(page).toContain(anchor)
    }
  })

  it('keeps the trailing slot order: variant action first, settings ahead of refresh', () => {
    expect(page).toContain('class="filter-bar-actions"')
    // containment preconditions first, then the order comparisons (no vacuous -1 checks)
    expect(page).toContain('data-test="credential-create-btn"')
    expect(page).toContain('<ColumnSettings')
    const slot = page.indexOf('class="filter-bar-actions"')
    expect(page.indexOf('data-test="credential-create-btn"')).toBeGreaterThan(slot)
    expect(page.indexOf('<ColumnSettings')).toBeLessThan(page.indexOf('data-test="credential-refresh"'))
  })

  it('declares the R25 scope mode for the single page-surface table', () => {
    expect(page).toContain('R25 mode declaration')
  })

  it('merges column state with labelKey for the settings popover rows', () => {
    expect(page).toContain('const settingsRows = computed(')
    expect(page).toContain(':columns="settingsRows"')
    expect(page).toContain('@density="colCfg.setDensity"')
    expect(page).toContain('@pin="colCfg.toggleFixed"')
  })
})
