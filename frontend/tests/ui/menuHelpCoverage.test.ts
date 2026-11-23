
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { allMenuLeaves } from '../../src/config/menuStructure'
import zhCN from '../../src/utils/locales/zh-CN'
import { lookupLocale } from '../_localeLookup'
import enUS from '../../src/utils/locales/en-US'

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale

// Seed surface of the help chapters (read from the migration file, not from a
// running DB) so the leaf<->chapter pairing gate stays runnable everywhere.
const helpChapterSeedSql = readFileSync(
  new URL('../../../db/migration/V438__help_doc_program_dashboard_chapter.sql', import.meta.url),
  'utf-8',
)

// Seed surface of the flow-canvas help chapter (same live-scan style).
const flowCanvasChapterSeedSql = readFileSync(
  new URL('../../../db/migration/V480__help_doc_flow_canvas_chapter.sql', import.meta.url),
  'utf-8',
)

describe('T343 包④ ? 悬浮说明 100% 覆盖契约', () => {
  it('叶子总数=56 且每叶都带 helpDomain+helpTipKey（零漏叶）', () => {
    // Count correction (stale-assertion class, not introduced here): the mock
    // endpoint inventory, scenario and recording pages added leaves without
    // bumping this number, so the assertion was already red on HEAD (62 leaves
    // on disk vs 59 asserted). With this package's inventory leaf the scanned
    // truth is 63 — value taken from the live scan, never from memory.
    // Contract change-analysis queue leaf added here: live scan says 64.
    // net -1 leaf: /process retired into the /execution tab (P-1 merge).
    // half B net -1 again: /schedule/fires retired into the /batches tab.
    // AN-3 half A net -1: /coverage retired into the /report tab.
    // AN-3 half B net -1: /report-center retired into the /report tab.
    // AN-4 net -4: /mock/docs, /mock/scenarios, /mock/recording and
    // /mock/inspector retired into the per-space workbench tabs.
    // AN-5 net -5: /skill-market /tool-market /plugins /mcp-servers
    // merged into /extensions (and the market tabs of /skills //tools),
    // /suites merged into the /cases tab, /requirements + /documents merged
    // into /extraction — live scan says 51.
    // AN-6 net -1: /usage-calibration retired into the /cost tab.
    // GB-2 net +1: /perf/runs joined as the project-wide load-run ledger
    // leaf (live scan says 51).
    // stage 3 net +1: /ai/chat standalone chat page joined the AI
    // capability group — live scan says 53 (asset pool leaf, .
    // AL batch net +1: /requirements split back out of the /extraction tab as its
    // own leaf — live scan says 54 .
    // Journey orchestration net +1: /journeys leaf beside /batches — live scan says 55.
    // net +1: /mq-dead-letter ops leaf in the system group — live scan says 56.
    // net +1: /judge/evalset leaf joined the results group with its
    // help chapter Y|2 in the same batch — live rescan says 61.
    // net +1: /judge/runs leaf joined the same group with its help
    // chapter Y|3 in the same batch — live rescan says 62.
    // net +1: /login-states leaf joined the appauto group beside the
    // vault with its help chapter X|93 in the same batch — live rescan says 63.
    expect(allMenuLeaves.length).toBe(63)
    // Y-wave net +1: /programs console leaf in the system group.
    // Y-wave net +1: /program-dashboard leaf (quality dashboard) joined
    // the results group with its help chapter in the same batch — live rescan says 58.
    // W5 app-automation phase 2 net +1: /elements library leaf joined the
    // device group with its help chapter in the same batch.
    // W5 app-automation phase 2 net +1: /appauto/flow-canvas leaf joined the
    // same group with its help chapter in the same batch — live rescan says 60.
    const missing = allMenuLeaves.filter((l) => !l.helpDomain || !l.helpTipKey).map((l) => l.path)
    expect(missing, `漏映射叶子=${JSON.stringify(missing)}`).toEqual([])
  })

  it('helpDomain 域码格式大写短码，且在 zh/en 双轨 help.domain 栏目名命中', () => {
    for (const leaf of allMenuLeaves) {
      expect(leaf.helpDomain, `path=${leaf.path} helpDomain 非法`).toMatch(/^[A-Z]+$/)
      const key = `help.domain.${leaf.helpDomain}`
      expect(typeof lookup(zhCN, key), `zh 缺域栏目名 path=${leaf.path} domain=${leaf.helpDomain}`).toBe('string')
      expect(typeof lookup(enUS, key), `en 缺域栏目名 path=${leaf.path} domain=${leaf.helpDomain}`).toBe('string')
    }
  })

  it('helpTipKey 全在 layout.leafHelp.* 命名空间下，且 zh/en 双轨命中非空', () => {
    for (const leaf of allMenuLeaves) {
      expect(leaf.helpTipKey.startsWith('layout.leafHelp.'), `path=${leaf.path} 悬浮键不在命名空间`).toBe(true)
      const zh = lookup(zhCN, leaf.helpTipKey)
      const en = lookup(enUS, leaf.helpTipKey)
      expect(typeof zh, `zh 缺悬浮文案 path=${leaf.path}`).toBe('string')
      expect((zh as string).length, `zh 悬浮文案过短 path=${leaf.path}`).toBeGreaterThanOrEqual(8)
      expect(typeof en, `en 缺悬浮文案 path=${leaf.path}`).toBe('string')
      expect((en as string).length, `en 悬浮文案过短 path=${leaf.path}`).toBeGreaterThanOrEqual(8)
    }
  })

  it('七旅程每组用到的 help 域码，组内全部落位后新域 T/Z 至少各有一叶（V218 补章消费面）', () => {
    const domains = new Set(allMenuLeaves.map((l) => l.helpDomain))
    expect(domains.has('T'), '接口测试页应映射新域 T').toBe(true)
    expect(domains.has('Z'), '造数与资产组应映射新域 Z').toBe(true)
    // /api-test is the only T-domain consumer; the dataAssets leaves are all Z
    // (: /suites merged into the /cases tab; 11-16 user ruling: credential vault +
    // login-state ledger joined the group, the vault chapter moved to Z via V535,
    // making Z multi-chapter - overview anchors ride along for the plain leaves)
    expect(allMenuLeaves.find((l) => l.path === '/api-test')?.helpDomain).toBe('T')
    const zPaths = allMenuLeaves.filter((l) => l.helpDomain === 'Z').map((l) => l.path)
    expect(zPaths.sort()).toEqual(['/appauto/credentials', '/asset-pool', '/data-factories', '/explore', '/login-states', '/role-bindings', '/tags'])
  })

  it('同一叶子悬浮键不得与其他叶子重复（重复=改一漏一假覆盖）', () => {
    const keys = allMenuLeaves.map((l) => l.helpTipKey)
    expect(new Set(keys).size).toBe(keys.length)
  })

  
  it('多章域（K/X/I/F/T/D/G/H/Q）每叶必带 helpAnchorKey 且 zh/en 双轨等值命中（漏配红）', () => {
    const MULTI_CHAPTER_DOMAINS = ['K', 'X', 'I', 'F', 'T', 'D', 'G', 'H', 'Q', 'Y', 'Z']
    const leaves = allMenuLeaves.filter((l) => MULTI_CHAPTER_DOMAINS.includes(l.helpDomain))
    expect(leaves.length).toBeGreaterThanOrEqual(19)
    const missing = leaves.filter((l) => !l.helpAnchorKey).map((l) => `${l.helpDomain}:${l.path}`)
    expect(missing, `多章域漏锚叶=${JSON.stringify(missing)}`).toEqual([])
    for (const leaf of leaves) {
      const zh = lookup(zhCN, leaf.helpAnchorKey as string)
      const en = lookup(enUS, leaf.helpAnchorKey as string)
      expect(typeof zh, `zh 缺锚键 ${leaf.helpAnchorKey} path=${leaf.path}`).toBe('string')
      expect((zh as string).length, `zh 锚文案过短 path=${leaf.path}`).toBeGreaterThanOrEqual(2)
      expect(en, `en 锚键须与 zh 等值（匹配标识非展示文案）path=${leaf.path}`).toBe(zh)
    }
  })

  it('非多章域叶子禁带 helpAnchorKey（单章域带锚=无效冗余，防口径漂移）', () => {
    const MULTI_CHAPTER_DOMAINS = ['K', 'X', 'I', 'F', 'T', 'D', 'G', 'H', 'Q', 'Y', 'Z']
    const stray = allMenuLeaves
      .filter((l) => !MULTI_CHAPTER_DOMAINS.includes(l.helpDomain) && l.helpAnchorKey)
      .map((l) => l.path)
    expect(stray, `单章域误带锚=${JSON.stringify(stray)}`).toEqual([])
  })

  
  it('菜单显示名全站唯一（zh 面值级，两页同名即红）', () => {
    const names = allMenuLeaves.map((l) => lookup(zhCN, l.titleKey) as string)
    const dup = names.filter((n, i) => n && names.indexOf(n) !== i)
    expect([...new Set(dup)], `同名菜单=${JSON.stringify(dup)}`).toEqual([])
  })

  it('多章域同域叶子锚值互异（共用锚=全跳同一章，假覆盖）', () => {
    const MULTI_CHAPTER_DOMAINS = ['K', 'X', 'I', 'F', 'T', 'D', 'G', 'H', 'Q', 'Y', 'Z']
    const offenders: string[] = []
    for (const dom of MULTI_CHAPTER_DOMAINS) {
      const leaves = allMenuLeaves.filter((l) => l.helpDomain === dom && l.helpAnchorKey)
      const anchors = leaves.map((l) => lookup(zhCN, l.helpAnchorKey as string) as string)
      const dups = anchors.filter((a, i) => a && anchors.indexOf(a) !== i)
      if (dups.length) offenders.push(`${dom}:${JSON.stringify([...new Set(dups)])}`)
    }
    expect(offenders, `同域共用锚=${JSON.stringify(offenders)}`).toEqual([])
  })

  /**
 * Y-wave pairing gate: the program-set quality dashboard leaf and its
 * help chapter must land in the same batch. The anchor value must be a real
 * chapter title of domain Q (seeded by db/migration/V438__help_doc_program_dashboard_chapter.sql)
 * so the "?" entry reaches this page's own chapter instead of falling back
 * to the domain's first chapter (R16 fake-coverage guard, live-scan form).
 */
  it('项目集质量大盘叶与帮助章配对（锚值=V438 章题，zh/en 等值）', () => {
    const leaf = allMenuLeaves.find((l) => l.path === '/program-dashboard')
    expect(leaf, 'menuStructure 缺 /program-dashboard 叶').toBeTruthy()
    expect(leaf?.helpDomain, '大盘叶应映射 Q 域').toBe('Q')
    expect(leaf?.helpAnchorKey).toBe('layout.leafHelpAnchor.programDashboard')
    const zhAnchor = lookup(zhCN, 'layout.leafHelpAnchor.programDashboard')
    const enAnchor = lookup(enUS, 'layout.leafHelpAnchor.programDashboard')
    expect(zhAnchor, 'zh 缺大盘锚键').toBe('项目集质量大盘')
    expect(enAnchor, 'en 锚键须与 zh 等值（匹配标识非展示文案）').toBe(zhAnchor)
    const zhTip = lookup(zhCN, 'layout.leafHelp.programDashboard')
    const enTip = lookup(enUS, 'layout.leafHelp.programDashboard')
    expect(typeof zhTip, 'zh 缺大盘悬浮文案').toBe('string')
    expect((zhTip as string).length, 'zh 悬浮文案过短').toBeGreaterThanOrEqual(8)
    expect(typeof enTip, 'en 缺大盘悬浮文案').toBe('string')
    expect((enTip as string).length, 'en 悬浮文案过短').toBeGreaterThanOrEqual(8)
    // The chapter row must exist in the seed surface, not only in a running DB:
    // scan the migration file for the (domain_code='Q', title prefixed by the anchor).
    const seed = helpChapterSeedSql.match(/\('Q', '项目集质量大盘[^']*'/)
    expect(seed, 'V438 迁移缺 Q 域大盘章行').toBeTruthy()
  })

  /**
   * Flow-canvas pairing gate (same style as the program-dashboard gate above):
   * the /appauto/flow-canvas leaf and its help chapter must land in one batch.
   * The anchor value equals the real chapter title prefix seeded for domain X
   * in the flow-canvas chapter migration, so the '?' entry reaches this page's
   * own chapter instead of falling back to the domain's first chapter.
   */
  it('流程画布叶与帮助章配对（锚值=章题，zh/en 等值）', () => {
    const leaf = allMenuLeaves.find((l) => l.path === '/appauto/flow-canvas')
    expect(leaf, 'menuStructure 缺 /appauto/flow-canvas 叶').toBeTruthy()
    expect(leaf?.helpDomain, '画布叶应映射 X 域').toBe('X')
    expect(leaf?.helpAnchorKey).toBe('layout.leafHelpAnchor.flowCanvas')
    const zhAnchor = lookup(zhCN, 'layout.leafHelpAnchor.flowCanvas')
    const enAnchor = lookup(enUS, 'layout.leafHelpAnchor.flowCanvas')
    expect(zhAnchor, 'zh 缺画布锚键').toBe('流程画布')
    expect(enAnchor, 'en 锚键须与 zh 等值（匹配标识非展示文案）').toBe(zhAnchor)
    const chapterSeed = flowCanvasChapterSeedSql.match(/\('X', '流程画布[^']*'/)
    expect(chapterSeed, '画布章行未与叶同批落迁移').toBeTruthy()
  })
})
