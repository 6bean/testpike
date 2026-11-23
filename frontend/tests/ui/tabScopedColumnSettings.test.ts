/**
 * Tab-scoped view-state settings contract (R25 mode 1: scope-aware single
 * piece, Ant ProTable options parity).
 *
 * Reported defect: a ColumnSettings/density piece parked outside an el-tabs
 * block keeps ONE viewKey while only one tab's table consumes it -- switching
 * tabs leaves the panel acting on a table the user cannot see (silent scope
 * failure). Disk truth = the two page templates + the shared popover piece
 * (same posture as tagMergeBandT22 / titleDedupContract readers, no memory
 * lists). Assertions:
 *   - exactly ONE <ColumnSettings> per page (single instance, no per-tab fork),
 *   - one settingsFor(tabKey, viewKey, ...) declaration per el-tab-pane name,
 *   - configurable panes bind their OWN pane (column-config / size / v-for),
 *   - fixed-view panes declare a named disabled reason (i18n dual track) and
 *     their table stays honestly unbound (no dead controls),
 *   - the popover rows/density/disabled state follow currentPane,
 *   - viewKeys unique across the tabbed pages (no table shares a key),
 *   - mode-1 declaration annotation present on each page.
 * Composable unit probes cover: pane routing on tab switch, per-pane panel
 * row sets, localStorage isolation between tabs, reload-persistence math,
 * fixed-view disabled reason, duplicate-key fail-fast, unknown-tab inertia.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { nextTick, ref } from 'vue'
import { useTabScopedSettings } from '@/composables/tabScopedColumnSettings'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'
import { lookupLocale } from '../_localeLookup'

const SRC = join(__dirname, '..', '..', 'src')
const tagSrc = readFileSync(join(SRC, 'views', 'tag', 'TagListView.vue'), 'utf8')
const factorySrc = readFileSync(join(SRC, 'views', 'datafactory', 'DataFactoryListView.vue'), 'utf8')
const settingsBtnSrc = readFileSync(join(SRC, 'components', 'ColumnSettings.vue'), 'utf8')

// The dotted-key walk is a contract concern every UI suite shares: consume the single source
// instead of growing another copy - a copy is how the same missing key starts passing here and
// failing there.
const lookup = lookupLocale

/** Inner markup of one <el-tab-pane name="x"> block (flat panes only). */
function paneBlock(src: string, tabKey: string): string {
  const m = new RegExp(`<el-tab-pane\\b[^>]*\\bname="${tabKey}"[^>]*>([\\s\\S]*?)</el-tab-pane>`).exec(src)
  expect(m, `page must render an el-tab-pane name="${tabKey}"`).not.toBeNull()
  return m[1]
}

function assertTemplateContract(src: string, file: string): void {
  const paneNames = [...src.matchAll(/<el-tab-pane\b[^>]*\bname="([\w-]+)"/g)].map((m) => m[1])
  expect(paneNames.length, `${file}: must declare at least two static el-tab-pane names`).toBeGreaterThanOrEqual(2)

  // every pane declares exactly one scoped settings view (tabKey -> viewKey ->
  // defs | null + reason): each tab is either configured or honestly disabled,
  // never silently sharing another tab's view
  const decls = [...src.matchAll(/settingsFor\(\s*'([\w-]+)'\s*,\s*'([\w-]+)'\s*,\s*(null|[A-Z_][A-Z0-9_]*)/g)]
  const declTabKeys = decls.map((m) => m[1])
  const viewKeys = decls.map((m) => m[2])
  expect(declTabKeys.length, `${file}: pane declarations must be unique per tabKey`).toBe(new Set(declTabKeys).size)
  expect([...declTabKeys].sort(), `${file}: every el-tab-pane needs a settings declaration`).toEqual(
    [...paneNames].sort(),
  )
  expect(new Set(viewKeys).size, `${file}: each pane needs its own viewKey (no shared key)`).toBe(viewKeys.length)

  // scope-aware single piece: one popover on the page, fully routed through
  // the active pane (rows, density, disabled state all follow currentPane)
  expect(
    (src.match(/<ColumnSettings\b/g) ?? []).length,
    `${file}: must carry exactly one ColumnSettings piece`,
  ).toBe(1)
  for (const binding of [
    ':columns="currentPane.settingsRows.value"',
    ':density="currentPane.state.density"',
    ':density-disabled="currentPane.densityDisabled.value"',
    ':disabled="currentPane.settingsDisabled.value"',
    ':disabled-tooltip="',
    '@toggle="currentPane.toggle"',
    '@move="currentPane.move"',
    '@density="currentPane.setDensity"',
    '@reset="currentPane.reset"',
    '@pin="currentPane.toggleFixed"',
  ]) {
    expect(src, `${file}: popover must bind ${binding}`).toContain(binding)
  }

  // per-pane wiring: configurable panes bind their OWN config object into
  // their own table; fixed panes keep an unbound (honest) table + a named
  // disabled reason resolved in both locales
  for (const [tabKey, , defsArg] of decls.map((m) => [m[1], m[2], m[3]] as const)) {
    const block = paneBlock(src, tabKey)
    if (defsArg === 'null') {
      expect(block, `${file}: fixed pane '${tabKey}' table must stay unbound (no dead wiring)`).not.toMatch(
        /:column-config=/,
      )
      const reason = new RegExp(`settingsFor\\(\\s*'${tabKey}'[^)]*disabledReasonKey:\\s*'([\\w.]+)'`).exec(src)
      expect(reason, `${file}: fixed pane '${tabKey}' must name a disabled-reason i18n key`).not.toBeNull()
      for (const [name, loc] of [['zh-CN', zhCN], ['en-US', enUS]] as const) {
        const v = lookup(loc, reason![1])
        expect(typeof v === 'string' && v.length > 0, `${name}: ${reason![1]} must exist`).toBe(true)
      }
      // honest absence must also be machine-readable for the S-9 gate
      expect(src, `${file}: fixed pane needs a tp-scope-exempt annotation + reason`).toMatch(/tp-scope-exempt/)
    } else {
      const idMatch = new RegExp(`const (\\w+) = settingsHost\\.settingsFor\\(\\s*'${tabKey}'`).exec(src)
      expect(idMatch, `${file}: configurable pane '${tabKey}' must be const-bound`).not.toBeNull()
      const id = idMatch![1]
      expect(block, `${file}: pane '${tabKey}' table must bind :column-config="${id}.cfg"`).toContain(
        `:column-config="${id}.cfg"`,
      )
      expect(block, `${file}: pane '${tabKey}' table must bind :size="${id}.state.density"`).toContain(
        `:size="${id}.state.density"`,
      )
      expect(block, `${file}: pane '${tabKey}' columns must v-for over ${id}.visibleColumns.value`).toContain(
        `in ${id}.visibleColumns.value`,
      )
      expect(block, `${file}: pane '${tabKey}' table must sort through its own binding`).not.toMatch(/colCfg/)
    }
  }

  // mode declaration per the tab-sync ruling (three modes, declared in-page)
  expect(/scope-aware single settings piece/i.test(src), `${file}: must annotate the chosen tab-sync mode`).toBe(
    true,
  )
}

describe('tab-scoped settings: page template contracts (disk truth)', () => {
  it('tag page routes the popover through the active pane; stats is an honestly disabled fixed view', () => {
    assertTemplateContract(tagSrc, 'TagListView.vue')
  })

  it('datafactory page routes the popover through the active pane; both tables bind their own config', () => {
    assertTemplateContract(factorySrc, 'DataFactoryListView.vue')
  })

  it('viewKeys are unique across the tabbed pages (no table shares another key)', () => {
    const keys = [tagSrc, factorySrc].flatMap((src) =>
      [...src.matchAll(/settingsFor\(\s*'[\w-]+'\s*,\s*'([\w-]+)'/g)].map((m) => m[1]),
    )
    expect(keys.length, 'four pane declarations expected across the two pages').toBe(4)
    expect(new Set(keys).size, `viewKeys must be unique, got ${keys.join(',')}`).toBe(keys.length)
  })

  it('popover honors fixed-view panes honestly: disabled button + pointed tooltip + density lock', () => {
    expect(settingsBtnSrc).toMatch(/disabled\?:\s*boolean/)
    expect(settingsBtnSrc).toMatch(/disabledTooltip\?:\s*string/)
    expect(settingsBtnSrc).toMatch(/densityDisabled\?:\s*boolean/)
    // popover trigger suppressed while disabled + tooltip only speaks when disabled
    expect(settingsBtnSrc).toMatch(/<el-popover[^>]*:disabled="disabled"/)
    expect(settingsBtnSrc).toMatch(/t\('column\.fixedViewUnsupported'\)/)
    // density switches lock for panes whose table cannot react to density
    expect(settingsBtnSrc).toMatch(/:disabled="densityDisabled"/)
    // legacy anchors survive (existing E2E depends on them)
    expect(settingsBtnSrc).toContain('data-test="column-settings-btn"')
    expect(settingsBtnSrc).toContain('data-test="column-settings-pop"')
  })

  it('generic disabled-tooltip fallback rides the i18n dual track (zh + en)', () => {
    for (const [name, loc] of [['zh-CN', zhCN], ['en-US', enUS]] as const) {
      const v = lookup(loc, 'column.fixedViewUnsupported')
      expect(typeof v === 'string' && v.length > 0, `${name}: column.fixedViewUnsupported must exist`).toBe(true)
    }
  })
})

describe('tab-scoped settings: composable unit probes', () => {
  const DEFS_A = [
    { key: 'name', labelKey: 'a.name', width: null },
    { key: 'caseCount', labelKey: 'a.count', width: 120 },
  ]
  const DEFS_B = [
    { key: 'name', labelKey: 'b.name', width: null },
    { key: 'passCount', labelKey: 'b.pass', width: 100 },
    { key: 'passRate', labelKey: 'b.rate', width: null },
  ]

  function installFakeStorage(): Map<string, string> {
    const store = new Map<string, string>()
    ;(globalThis as Record<string, unknown>).localStorage = {
      getItem: (k: string) => (store.has(k) ? store.get(k)! : null),
      setItem: (k: string, v: string) => store.set(k, String(v)),
      removeItem: (k: string) => store.delete(k),
    }
    return store
  }

  it('routes panes by active tab and swaps the panel row set (the outfit change)', () => {
    installFakeStorage()
    const active = ref('a')
    const host = useTabScopedSettings(active)
    const a = host.settingsFor('a', 'uTabA', DEFS_A)
    const b = host.settingsFor('b', 'uTabB', DEFS_B)
    expect(host.currentPane.value).toBe(a)
    active.value = 'b'
    expect(host.currentPane.value).toBe(b)
    const rowsA = a.settingsRows.value.map((r) => r.key).sort()
    const rowsB = b.settingsRows.value.map((r) => r.key).sort()
    expect(rowsA).toEqual(['caseCount', 'name'])
    expect(rowsB).toEqual(['name', 'passCount', 'passRate'])
    expect(rowsA).not.toEqual(rowsB)
    // unknown tab = inert (nothing silently acts on an invisible table)
    active.value = 'zzz'
    expect(host.currentPane.value.configurable).toBe(false)
    expect(host.currentPane.value.settingsDisabled.value).toBe(true)
  })

  it('tabs keep independent state: one tab\'s edits never touch the other, reload restores', async () => {
    const store = installFakeStorage()
    const active = ref('a')
    const host = useTabScopedSettings(active)
    const a = host.settingsFor('a', 'uTabC', DEFS_A)
    const b = host.settingsFor('b', 'uTabD', DEFS_B)
    b.toggle('passCount')
    b.setDensity('small')
    await nextTick()
    // a was never mutated -> no storage write, no state drift
    expect(store.has('tp-colcfg-uTabC')).toBe(false)
    expect(a.state.columns.find((c) => c.key === 'name')!.visible).toBe(true)
    expect(a.state.density).toBe('default')
    const persisted = JSON.parse(store.get('tp-colcfg-uTabD')!)
    expect(persisted.columns.find((c: { key: string }) => c.key === 'passCount').visible).toBe(false)
    expect(persisted.density).toBe('small')
    // same-key columns across panes stay isolated (b hides 'name', a keeps it)
    b.toggle('name')
    await nextTick()
    expect(a.state.columns.find((c) => c.key === 'name')!.visible).toBe(true)
    // reload math: a fresh instance on the same viewKey restores the hidden state
    const host2 = useTabScopedSettings(ref('c'))
    const b2 = host2.settingsFor('c', 'uTabD', DEFS_B)
    expect(b2.state.columns.find((c) => c.key === 'passCount')!.visible).toBe(false)
    expect(b2.state.columns.find((c) => c.key === 'name')!.visible).toBe(false)
    expect(b2.state.density).toBe('small')
  })

  it('fixed-view pane: settings disabled with a named reason, density locked, actions inert, no storage write', async () => {
    const store = installFakeStorage()
    const active = ref('a')
    const host = useTabScopedSettings(active)
    const f = host.settingsFor('f', 'uTabF', null, { disabledReasonKey: 'page.chartFixedView' })
    expect(f.configurable).toBe(false)
    expect(f.settingsDisabled.value).toBe(true)
    expect(f.densityDisabled.value).toBe(true)
    expect(f.disabledReason.value).toBe('page.chartFixedView')
    f.toggle('whatever')
    f.setDensity('small')
    await nextTick()
    expect(store.size).toBe(0)
    expect(f.state.density).toBe('default')
    expect(f.visibleColumns.value).toEqual([])
    expect(f.settingsRows.value).toEqual([])
  })

  it('duplicate viewKey / tabKey fail fast (a shared key IS the defect)', () => {
    installFakeStorage()
    const active = ref('a')
    const host = useTabScopedSettings(active)
    host.settingsFor('one', 'dupKey', DEFS_A)
    expect(() => host.settingsFor('two', 'dupKey', DEFS_B)).toThrowError(/dupKey/)
    expect(() => host.settingsFor('one', 'otherKey', DEFS_B)).toThrowError(/one/)
  })
})
