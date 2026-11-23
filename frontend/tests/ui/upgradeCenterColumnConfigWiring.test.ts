/**
 * Upgrade center tab-scoped column settings wiring contract (N-3 / R25 mode 1).
 * The page-level settings piece must outfit to the active tab (single scoped
 * host, per-pane viewKeys), the plans ledger must really render through the
 * pane's visibleColumns loop (static-binding pretence = the fake-green this
 * contract exists to catch), and the three fixed-view panes must be declared
 * with an honest disabled reason (no invisible no-op).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(
  new URL('../../src/views/cluster/UpgradeCenterView.vue', import.meta.url),
  'utf8',
)
const zh = readFileSync(new URL('../../src/utils/locales/zh-CN.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../../src/utils/locales/en-US.ts', import.meta.url), 'utf8')

describe('upgrade center tab-scoped column settings wiring (R25 mode 1, N-3)', () => {
  it('consumes the shared tab-scoped host, not a second implementation', () => {
    expect(page).toContain("import { useTabScopedSettings } from '@/composables/tabScopedColumnSettings'")
    expect(page).toContain("import ColumnSettings from '@/components/ColumnSettings.vue'")
    expect(page).toContain('const settingsHost = useTabScopedSettings(activeTab)')
    expect(page).toContain('const currentPane = settingsHost.currentPane')
  })

  it('declares one pane per el-tab-pane with unique viewKeys', () => {
    expect(page).toContain("settingsFor('plans', 'upgradePlans', PLANS_COLUMNS)")
    for (const pane of ['manifest', 'matrix', 'insight']) {
      expect(page).toContain(`settingsFor('${pane}', 'upgrade${pane[0].toUpperCase()}${pane.slice(1)}', null, { disabledReasonKey: 'upgrade.${pane}FixedViewHint' })`)
    }
  })

  it('the popover routes through currentPane (follows the active tab)', () => {
    expect(page).toContain(':columns="currentPane.settingsRows.value"')
    expect(page).toContain(':disabled="currentPane.settingsDisabled.value"')
    expect(page).toContain(':disabled-tooltip="currentPane.disabledReason.value ? t(currentPane.disabledReason.value) : \'\'"')
    expect(page).toContain('@density="currentPane.setDensity"')
  })

  it('plans ledger columns render through the visibleColumns loop with live bindings', () => {
    expect(page).toContain('v-for="col in plansPane.visibleColumns.value"')
    expect(page).toContain(':size="plansPane.state.density"')
    expect(page).toContain(':width="col.width ?? undefined"')
    expect(page).toContain(':min-width="col.width ? undefined : PLANS_FLEX_MIN[col.key]"')
    expect(page).toContain(':fixed="col.fixed || false"')
    expect(page).toContain('`upgrade-plans-col-${col.key}`')
    for (const key of ['id', 'scope', 'targetEngineCodes', 'status', 'manifestVersion', 'brakeReason', 'operator', 'reviewedBy', 'createdAt']) {
      expect(page).toContain(`{ key: '${key}'`)
    }
  })

  it('locks the action column outside the configurable loop', () => {
    const loopEnd = page.indexOf('v-for="col in plansPane.visibleColumns.value"')
    const actionsCol = page.indexOf(':label="t(\'upgrade.colActions\')" width="230" fixed="right"', loopEnd)
    expect(actionsCol).toBeGreaterThan(loopEnd)
    // No v-for attribute on the actions column element itself.
    const actionsTagStart = page.lastIndexOf('<el-table-column', actionsCol)
    expect(page.slice(actionsTagStart, actionsCol)).not.toContain('v-for=')
  })

  it('fixed-view panes carry the S-9 annotation tokens', () => {
    for (const pane of ['manifest', 'matrix', 'insight']) {
      const idx = page.indexOf(`name="${pane}">`)
      expect(idx).toBeGreaterThan(-1)
      const before = page.slice(Math.max(0, idx - 400), idx)
      expect(before).toContain('tp-pane-fixed-view')
      expect(before).toContain('tp-scope-exempt')
    }
  })

  it('every named i18n key exists in both locales', () => {
    for (const key of ['colPlanId', 'manifestFixedViewHint', 'matrixFixedViewHint', 'insightFixedViewHint']) {
      expect(zh).toContain(`${key}:`)
      expect(en).toContain(`${key}:`)
    }
  })
})
