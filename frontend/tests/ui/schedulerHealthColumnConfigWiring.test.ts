/**
 * Scheduler-health column-configuration wiring contract (N-3 repayment).
 * Mirrors the matrix-report/popup-rule/credential-vault/device-wall precedent:
 * the page must really consume the shared useColumnConfig piece, not a second
 * implementation.
 *  - defs exist for every data column of the health table; nothing renders
 *    outside the loop, so the skeleton mirrors the visible count one-to-one;
 *  - density rides colCfg.state.density and columns render through the
 *    visibleColumns loop (hide/order/pin take effect) with stable per-column
 *    data-test anchors for the E2E spectrum;
 *  - server-side custom sorting stays single-sourced through SORT_KEY_MAP:
 *    every column except the composed status badge declares sortable 'custom'
 *    (the whitelist below the loop decides what actually orders server-side);
 *  - R25 mode declaration: no tabs and one page-surface table, so the
 *    page-level column-settings control equals the table scope; the trailing
 *    slot order is variant action first (run now), then settings, then
 *    refresh (R21 same-band merge);
 *  - SE-1: the run-now dialog job picker stays filterable.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/system/SchedulerHealthView.vue', import.meta.url),
  'utf8',
)

describe('scheduler-health column-config wiring', () => {
  it('imports and instantiates the shared useColumnConfig piece', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain("const colCfg = useColumnConfig('scheduler-health', SCHEDULER_HEALTH_COLUMNS)")
  })

  it('declares a def for every data column of the health table', () => {
    for (const key of ['jobName', 'description', 'status', 'lockedBy', 'lockedAt', 'lockUntil']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('feeds density state and a one-to-one dynamic skeleton into the table', () => {
    expect(page).toContain(':size="colCfg.state.density"')
    // no locked slot renders outside the loop, so the skeleton mirrors the visible count
    expect(page).toContain(':cols="colCfg.visibleColumns.value.length"')
  })

  it('renders data columns through the visibleColumns loop with stable anchors', () => {
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain('`scheduler-health-col-${col.key}`')
    // flexible columns fall back to min-width, fixed ones keep their width
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : SCHEDULER_HEALTH_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    // per-column custom renderers stay wired inside the single loop
    for (const key of ['jobName', 'description', 'status', 'lockedBy', 'lockedAt', 'lockUntil']) {
      expect(page).toContain(`col.key === '${key}'`)
    }
  })

  it('keeps the composed status badge honestly unsorted', () => {
    expect(page).toContain(":sortable=\"col.key === 'status' ? false : 'custom'\"")
    // SORT_KEY_MAP stays the single source for what orders server-side
    expect(page).toContain('const SORT_KEY_MAP: Record<string, string>')
    for (const key of ['jobName', 'description', 'lockedBy', 'lockedAt', 'lockUntil']) {
      expect(page).toContain(`${key}:`)
    }
    expect(page).not.toContain('status:')
  })

  it('orders the trailing slot variant-first and drops the dead header band', () => {
    const runNow = page.indexOf('scheduler-run-now-open')
    const settings = page.indexOf('<ColumnSettings')
    const refresh = page.indexOf('scheduler-health-reload')
    expect(runNow).toBeGreaterThan(-1)
    expect(settings).toBeGreaterThan(runNow)
    expect(refresh).toBeGreaterThan(settings)
    expect(page).toContain('class="filter-bar-actions"')
    expect(page).not.toContain('page-header')
    expect(page).not.toContain('header-actions')
    expect(page).not.toContain('header-title')
    expect(page).not.toContain('page-subtitle')
  })

  it('keeps the E2E-critical anchors through the loop conversion', () => {
    for (const anchor of [
      'scheduler-health-page',
      'scheduler-health-filter-bar',
      'scheduler-health-keyword',
      'scheduler-health-reload',
      'scheduler-health-table',
      'schedulerhealthview-skeleton',
      'scheduler-health-pagination',
      'scheduler-run-now-open',
      'scheduler-run-dialog',
      'scheduler-run-job-select',
      'scheduler-run-job-option',
      'scheduler-run-dialog-hint',
      'scheduler-run-cancel',
      'scheduler-run-confirm',
      'scheduler-health-desc',
      'scheduler-health-status',
    ]) {
      expect(page).toContain(anchor)
    }
  })

  it('keeps the run-now job picker filterable and states the R25 scope declaration', () => {
    expect(page).toContain('filterable')
    expect(page).toContain('R25 mode declaration')
  })
})
