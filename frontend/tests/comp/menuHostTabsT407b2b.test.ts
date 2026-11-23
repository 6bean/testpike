/**
 * AN-5 (skills/tools half) — tab-host layout contract.
 *
 * segment 1 retired /skill-market and /tool-market as menu leaves and turned
 * the old paths into query-preserving redirects (?tab=market); gave
 * the merged pages the embedded frame switch. This file is the RED anchor for
 * the two list pages become the tab hosts themselves —
 * - the host renders an el-tabs bar (data-test=<slug>-page-tabs) and the
 * tab children as <span data-test="<slug>-tab-manage"> / "-tab-market"
 * labels, so ⌘K/redirect/breadcrumb land on one visible carrier;
 * - the market child is rendered with `embedded` (no frame-in-frame);
 * - the tab state is URL-first: query value wins on entry, unknown keys
 * fall back to the manage tab, switching writes the query back through
 * router.replace (refresh/share/back stable, no history spam);
 * - the hosts are NOT keep-alive cached (MainLayout whitelist), so setup
 * reads the query — but an in-app navigation that only changes ?tab=
 * reuses the instance, hence a route.query.tab watcher is mandatory.
 *
 * Co-moved with the slot-2 migration ruling (16 §53: same cut, no second edit):
 * the header "query" button is a view-state action and must leave
 * header-actions for the trailing filter-bar-actions slot, and the
 * data-test=skill-search / tool-search anchors ride the move unchanged —
 * nine E2E files click them (broken anchors = fake-green, banned).
 *
 * The /cases?tab=suites host lands in the second half of (next segment).
 */
import { describe, expect, it } from 'vitest'
// Source-as-text reads ride the vite ?raw suffix ( precedent).
import skillRaw from '@/views/skill/SkillListView.vue?raw'
import toolRaw from '@/views/tool/ToolListView.vue?raw'

type HostSpec = {
  name: string
  src: string
  slug: string
  child: string
}

const HOSTS: HostSpec[] = [
  { name: 'SkillListView', src: skillRaw, slug: 'skill', child: 'SkillMarketView' },
  { name: 'ToolListView', src: toolRaw, slug: 'tool', child: 'ToolMarketView' },
]

describe('T407 slice 2b: skills/tools pages carry the market tabs', () => {
  it.each(HOSTS)('%s renders the tab bar with manage/market labels', ({ src, slug }) => {
    expect(src, `${slug}: host tabs bar missing`).toContain(`data-test="${slug}-page-tabs"`)
    expect(src, `${slug}: manage tab label anchor missing`).toContain(`data-test="${slug}-tab-manage"`)
    expect(src, `${slug}: market tab label anchor missing`).toContain(`data-test="${slug}-tab-market"`)
  })

  it.each(HOSTS)('%s renders its market child embedded inside the host', ({ src, child }) => {
    expect(src, `${child} not imported`).toMatch(new RegExp(`import ${child} from`))
    expect(src, `${child} not rendered embedded`).toMatch(new RegExp(`<${child}\\s+embedded`))
  })

  it.each(HOSTS)('%s keeps the tab state URL-first with replace write-back', ({ src, slug }) => {
    // Query wins on entry; unknown keys fall back to manage. Assertion-lag
    // fix : both hosts resolve the query through a tabFromQuery
    // helper (skill extended the union with the memory tab, tool kept the
    // single ternary), so the helper contract is pinned, not one spelling.
    expect(src, `${slug}: query-first tab resolver missing`).toMatch(
      /function tabFromQuery[\s\S]{0,240}route\.query\.tab/,
    )
    expect(src, `${slug}: resolver must fall back to the manage tab`).toMatch(
      /return[^\n]*'manage'/,
    )
    expect(src, `${slug}: pageTab must initialize from the resolver`).toMatch(
      /const pageTab = ref<[^>]+>\(tabFromQuery\(\)\)/,
    )
    // Switching writes back through replace (undefined = default tab, no history spam).
    expect(src, `${slug}: replace write-back missing`).toContain(
      'void router.replace({ query: { ...route.query, tab: tab === \'manage\' ? undefined : tab } })',
    )
    // In-app navigation that only changes ?tab= reuses the instance (hosts are
    // outside the keep-alive whitelist), so the query watcher is mandatory.
    expect(src, `${slug}: route.query.tab watcher missing`).toMatch(
      /watch\(\(\) => route\.query\.tab[\s\S]{0,120}pageTab\.value =/,
    )
  })
})

describe('T416 co-moved slot-2 migration, R21 revision 2: the host query button is inline at the filter-group tail', () => {
  it.each(HOSTS)('%s keeps the query button inline before the merged trailing slot', ({ src, slug, name }) => {
    // Assertion-lag fix : the revision-3 same-band merge split the
    // emptied header band shell and gave the hosts a .filter-bar-actions
    // trailing slot for the variant actions. The pinned intent is unchanged:
    // R21 revision 2 puts the query submit inline right after the last
    // condition control — before the slot, never pushed into it.
    expect(/<div class="header-actions">/.test(src), `${name}: header band shell must stay removed`).toBe(false)
    expect(src, `${name}: ${slug}-search anchor lost`).toContain(`data-test="${slug}-search"`)
    const bar = /<div class="filter-bar"[^>]*>([\s\S]*?)<\/div>\s*(?:<TableSkeleton|<el-table)/.exec(src)
    expect(bar, `${name}: filter-bar block shape changed`).toBeTruthy()
    const group = bar![1]
    expect(group, `${name}: query button must sit inside the filter group`).toContain(
      `data-test="${slug}-search"`,
    )
    const slotIdx = group.indexOf('class="filter-bar-actions"')
    expect(slotIdx, `${name}: merged trailing slot missing`).toBeGreaterThan(-1)
    expect(group.indexOf(`data-test="${slug}-search"`), `${name}: submit must stay inline before the slot`).toBeLessThan(slotIdx)
    expect(group.slice(slotIdx), `${name}: submit must never ride the trailing slot`).not.toContain(
      `data-test="${slug}-search"`,
    )
  })
})
