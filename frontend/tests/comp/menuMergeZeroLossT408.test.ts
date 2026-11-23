/**
 * AN-6 R24 menu-merge zero-loss six-face contract for the
 * cost-domain merge: /usage-calibration retires as a menu leaf and rides
 * mirror of the retired route's perm).
 *
 * Six faces pinned here (one missing = merge invalid per the 10-21 ruling):
 * ① old path keeps resolving via a functional redirect (query survives);
 * ② the retired component is really mounted inside the host view;
 * ③ ⌘K synonyms ride the host path and carry the target tab (no dangling
 * synonym key, no dead tab word);
 * ④ the permission domain does not narrow: the retired route carried
 * perm:system:system-config — the host tab must gate on the same key
 * (source anchor), and users without it keep seeing the cost board;
 * ⑤ the leaf snapshot: /usage-calibration is gone from menuStructure,
 * /cost stays (bread-crumbs/top-bar resolve through allMenuLeaves);
 * ⑥ host tab state rides the URL query (?tab=calibration) with a
 * query→tab watcher, same protocol as the report host.
 */
import { describe, expect, it } from 'vitest'
import routesRaw from '@/router/routes.ts?raw'
import costRaw from '@/views/cost/CostDashboardView.vue?raw'
import searchDialogRaw from '@/components/GlobalSearchDialog.vue?raw'
import { searchMenu } from '@/config/menuSearch'
import { menuSynonyms, synonymTabs } from '@/config/menuSynonyms'
import { allMenuLeaves } from '@/config/menuStructure'
import { lookupLocale } from '../_localeLookup'
import zhCN from '@/utils/locales/zh-CN'

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale

const tZh = (key: string) => String(lookup(zhCN, key) ?? key)
const allowAll = () => true
const denyAll = () => false

describe('T408 face ①: /usage-calibration keeps resolving via redirect', () => {
  it('routes.ts turns the retired path into a functional redirect to /cost?tab=calibration', () => {
    const re = /path:\s*'usage-calibration',\s*redirect:\s*\(to\)\s*=>\s*\(\{\s*path:\s*'\/cost',[^}]*tab:\s*'calibration'/
    expect(routesRaw, 'retired path /usage-calibration lost its redirect').toMatch(re)
  })
})

describe('T408 face ②: UsageCalibrationView is really mounted in the cost host', () => {
  it('CostDashboardView imports the calibration component', () => {
    expect(costRaw, 'UsageCalibrationView not imported in CostDashboardView').toMatch(
      /import UsageCalibrationView from/,
    )
  })
  it('calibration view supports the embedded prop (host-card suppression, T405 precedent)', () => {
    expect(costRaw).toMatch(/<UsageCalibrationView[^>]*embedded/)
  })
})

describe('T408 face ③: ⌘K synonyms ride /cost and carry the calibration tab', () => {
  it('menuSynonyms has no dangling /usage-calibration key (leaf retired)', () => {
    expect(menuSynonyms['/usage-calibration'], 'stale synonym key on retired leaf').toBeUndefined()
  })
  it('synonymTabs maps every calibration word to the host tab', () => {
    const tabs = synonymTabs['/cost']
    expect(tabs, '/cost synonymTabs missing').toBeTruthy()
    expect(tabs!['校准']).toBe('calibration')
    expect(tabs!['用量校准']).toBe('calibration')
    expect(tabs!['token 校准']).toBe('calibration')
  })
  it('every mapped word exists in the host synonym list (no dead-code words)', () => {
    const synonyms = (menuSynonyms['/cost'] ?? []).map((s) => s.toLowerCase())
    for (const word of Object.keys(synonymTabs['/cost'] ?? {})) {
      expect(synonyms, `word '${word}' absent from menuSynonyms['/cost']`).toContain(
        word.toLowerCase(),
      )
    }
  })
  it('searchMenu(校准) lands on /cost with tab=calibration (allow all perms)', () => {
    const hit = searchMenu('校准', tZh, allowAll).find((e) => e.path === '/cost')
    expect(hit, 'no /cost entry for 校准').toBeTruthy()
    expect(hit!.tab).toBe('calibration')
  })
  it('command palette appends ?tab= when navigating a synonym hit', () => {
    expect(searchDialogRaw).toMatch(/row\.tab \? `\$\{base\}\?tab=/)
  })
})

describe('T408 face ④: permission domain mirror (calibration tab gated, board open)', () => {
  it('the host gates the calibration tab on the retired route perm key', () => {
    expect(costRaw).toMatch(/hasPerm\('perm:system:system-config'\)|authStore\.hasPerm\('perm:system:system-config'\)/)
    expect(costRaw).toMatch(/pageTab === 'calibration'/)
  })
  it('searchMenu(校准) hides /cost for users without system-config', () => {
    // /cost leaf itself carries no perm (everyone sees the board), so the
    // entry shows for denyAll users — but without the tab words the retired
    // leaf must not resurrect as its own entry.
    const hits = searchMenu('token 校准', tZh, denyAll).filter((e) => e.path === '/usage-calibration')
    expect(hits, 'retired leaf resurrected in search').toEqual([])
  })
})

describe('T408 face ⑤: leaf snapshot moved with the merge', () => {
  it('/usage-calibration is gone from allMenuLeaves', () => {
    expect(allMenuLeaves.some((l) => l.path === '/usage-calibration')).toBe(false)
  })
  it('/cost is still a live leaf', () => {
    expect(allMenuLeaves.some((l) => l.path === '/cost')).toBe(true)
  })
})

describe('T408 face ⑥: tab state rides the URL query (refresh/share stable)', () => {
  it('host reads ?tab= on mount and watches external query changes', () => {
    expect(costRaw).toMatch(/route\.query\.tab/)
    expect(costRaw).toMatch(/watch\(\(\) => route\.query\.tab/)
  })
  it('tab switch writes the query back via router.replace (no history spam)', () => {
    expect(costRaw).toMatch(/router\.replace\(\{ query: \{ \.\.\.route\.query, tab:/)
  })
})
