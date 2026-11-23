/**
 * layout contract — three-band action separation (R21 + R21 revision 2).
 *
 * Authority = the 2026-10-19 action-band ruling doc
 * (docs/change-eval/, FB group, task ).
 * Mirrors the S6 scanner criteria (scripts/ui_audit_struct_s6.py FB-1/FB-2)
 * inside vitest so the frontend closeout gate catches regressions even when
 * only component tests are run:
 * FB-1: the .header-actions band carries page-level variant actions only —
 * no refresh / column-settings / query-submit view-state actions.
 * FB-2: the .filter-bar-actions trailing slot carries view-state actions —
 * a query/search SUBMIT button belongs inline at the end of the
 * filter group (eye-hand continuity ≤ 1 click), not pushed right.
 * Ratchet: LEGACY_DEBT is the 2026-10-21 account (21 pages FB-1 + 2 pages
 * FB-2). Migration batches must shrink it and add each fixed
 * page to `migrated`, never grow it. A new leaking page fails the count
 * assertion; migrating a page without updating the list also fails.
 */
import { describe, expect, it } from 'vitest'

// Synthetic snippets prove the detectors themselves are not an idle wheel:
// a violation fixture must be caught, a compliant fixture must not.
const VIOLATION_HEADER = `<div class="header-actions">
  <el-button :icon="Refresh" circle @click="reload" />
  <ColumnSettings :columns="cols" />
</div>`
const VIOLATION_TRAILING = `<div class="filter-bar-actions">
  <el-button type="primary" data-test="tool-search">{{ t('common.query') }}</el-button>
</div>`
const COMPLIANT_HEADER = `<div class="header-actions">
  <el-button type="primary" @click="onCreate">{{ t('common.createCase') }}</el-button>
</div>`
const COMPLIANT_TRAILING = `<div class="filter-bar-actions">
  <el-button :icon="Refresh" circle @click="reload" />
</div>`

/** Inner text of every <div class="...cls..."> block via div pairing. */
function divRegion(src: string, cls: string): string[] {
  const out: string[] = []
  const open = new RegExp(`<div[^>]*class="[^"]*\\b${cls}\\b[^"]*"[^>]*>`, 'g')
  let m: RegExpExecArray | null
  while ((m = open.exec(src)) !== null) {
    const start = m.index + m[0].length
    let depth = 1
    let i = start
    const tok = /<div(?![\w-])|<\/div>/g
    tok.lastIndex = i
    let t: RegExpExecArray | null
    while (depth > 0 && (t = tok.exec(src)) !== null) {
      depth += t[0].startsWith('<div') ? 1 : -1
      i = t.index + t[0].length
    }
    out.push(src.slice(start, i))
  }
  return out
}

const FB1_PATTERNS: RegExp[] = [
  /:icon="Refresh"/,
  /\bRefresh\b(?!\w)/,
  /<ColumnSettings/,
  /t\(['"][^'"]*\.refresh['"]\)/,
  /t\(['"][^'"]*\.(query|search)['"]\)/,
  /data-test="[^"]*search[^"]*"/,
  /<Search\s*\/>/,
  /:icon="Search"/,
]
const FB2_PATTERNS: RegExp[] = [
  /t\(['"][^'"]*\.(query|search)['"]\)/,
  /data-test="[^"]*search[^"]*"/,
  /<Search\s*\/>/,
  /:icon="Search"/,
]

function fb1Hits(src: string): number {
  return divRegion(src, 'header-actions').filter((r) => FB1_PATTERNS.some((p) => p.test(r))).length
}
function fb2Hits(src: string): number {
  return divRegion(src, 'filter-bar-actions').filter((r) => FB2_PATTERNS.some((p) => p.test(r))).length
}

// Pages cleared by the migration batches join the per-rule list below
// (file basename, e.g. 'ScheduleFireListView.vue'); each rule's cap shrinks
// with its own entries, never the other rule's.
const migratedFb1: string[] = [
  'ScheduleFireListView.vue',
  'DataFactoryListView.vue',
  // migration batch batch 2 (appauto domain, 8 pages): refresh moved from the
  // header band into the filter-bar trailing slot; headerless pages drop the
  // whole header row per R23 (S-1 gate extended for the compliant shape).
  'AiSampleBoardView.vue',
  'MatrixReportView.vue',
  'StepHealingView.vue',
  'AppPackageView.vue',
  'CredentialVaultView.vue',
  'DeviceWallView.vue',
  'DeviceReservationCalendarView.vue',
  'PopupRuleApprovalView.vue',
  // migration batch batch 3 (last 11 FB-1 pages): refresh/column-settings moved to
  // the filter-bar trailing slot; query submits (docs/batch) relocated inline at
  // the end of the filter group per R21 revision 2; refresh-only headers dropped
  // whole per R23 (Board). Data-test anchors ride unchanged.
  'DocumentListView.vue',
  'ApiInventoryView.vue',
  'SpecChangeAnalysisView.vue',
  'EngineCenterView.vue',
  'BatchListView.vue',
  'UpgradeCenterView.vue',
  'MockEndpointsView.vue',
  'BoardView.vue',
  'TestReportListView.vue',
  'SchedulerHealthView.vue',
  'TagListView.vue',
]
const migratedFb2: string[] = ['SkillListView.vue', 'ToolListView.vue']
const migrated: string[] = [...migratedFb1, ...migratedFb2]

// Original account: 21 FB-1 pages + 2 FB-2 pages. Each rule's cap =
// its account minus its own migrated entries (shrinking ratchet).
const FB1_LEGACY_PAGES = 21
const FB2_LEGACY_PAGES = 2

describe('T416 action-band detectors (anti-idle-wheel fixtures)', () => {
  it('flags view-state actions parked in the header band', () => {
    expect(fb1Hits(VIOLATION_HEADER)).toBe(1)
  })
  it('flags a query submit button pushed into the trailing slot', () => {
    expect(fb2Hits(VIOLATION_TRAILING)).toBe(1)
  })
  it('passes the compliant three-band split', () => {
    expect(fb1Hits(COMPLIANT_HEADER)).toBe(0)
    expect(fb2Hits(COMPLIANT_TRAILING)).toBe(0)
  })
})

describe('T416 repo-wide ratchet: header band holds no view-state actions', () => {
  const modules = import.meta.glob('../../src/views/**/*.vue', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>

  const fb1Pages: string[] = []
  const fb2Pages: string[] = []
  for (const [path, src] of Object.entries(modules)) {
    const name = path.split('/').pop() || path
    if (!src.includes('<template>')) continue
    if (src.includes('tp-header-action-exempt')) continue
    if (fb1Hits(src) > 0) fb1Pages.push(name)
    if (!src.includes('tp-trailing-query-exempt') && fb2Hits(src) > 0) fb2Pages.push(name)
  }

  it.each(migrated.map((n) => [n]))('%s is fully migrated out of the header band', (name) => {
    const entry = Object.entries(modules).find(([p]) => p.endsWith(`/${name}`))
    expect(entry, `${name} must exist under src/views`).toBeTruthy()
    const src = entry![1]
    expect(fb1Hits(src)).toBe(0)
    expect(fb2Hits(src)).toBe(0)
  })

  it('leaking-page count never exceeds the shrinking ratchet cap', () => {
    const capFb1 = FB1_LEGACY_PAGES - migratedFb1.length
    const capFb2 = FB2_LEGACY_PAGES - migratedFb2.length
    expect(
      fb1Pages.length,
      `FB-1 leak account ${fb1Pages.join(', ')} exceeds cap ${capFb1} — migrate pages, never raise the cap`,
    ).toBeLessThanOrEqual(capFb1)
    expect(
      fb2Pages.length,
      `FB-2 leak account ${fb2Pages.join(', ')} exceeds cap ${capFb2}`,
    ).toBeLessThanOrEqual(capFb2)
  })
})
