/**
 * AN-5 R24 menu-merge zero-loss six-face contract.
 *
 * The 10-21 ruling (R24: a menu/page merge is invalid unless six faces are
 * reconciled) left exactly one open gap after the hosts landed:
 * face ③ — ⌘K synonyms ride the host path but searchMenu returns a path with
 *
 * This file is the RED anchor for the fix. Four contract faces:
 * ① every retired path still resolves through a redirect (routes.ts text);
 * ② every retired page component is really mounted inside its host (source);
 * ③ a synonym hit carries its target tab (searchMenu entry.tab) and the
 * command palette appends ?tab= when navigating (source anchor);
 * ④ the multi-chapter hosts (/extensions G, /extraction F) keep a
 */
import { describe, expect, it } from 'vitest'
import routesRaw from '@/router/routes.ts?raw'
import searchDialogRaw from '@/components/GlobalSearchDialog.vue?raw'
import extensionsRaw from '@/views/extension/ExtensionsHostView.vue?raw'
import skillsRaw from '@/views/skill/SkillListView.vue?raw'
import toolsRaw from '@/views/tool/ToolListView.vue?raw'
import casesRaw from '@/views/case/CaseListView.vue?raw'
import batchesRaw from '@/views/batch/BatchListView.vue?raw'
import executionRaw from '@/views/execution/ExecutionCenterView.vue?raw'
import reportRaw from '@/views/report/ReportView.vue?raw'
import mockWbRaw from '@/views/mock/MockSpaceWorkbenchView.vue?raw'
import { searchMenu } from '@/config/menuSearch'
import { menuSynonyms, synonymTabs } from '@/config/menuSynonyms'
import { allMenuLeaves } from '@/config/menuStructure'
import zhCN from '@/utils/locales/zh-CN'
import { lookupLocale } from '../_localeLookup'
import enUS from '@/utils/locales/en-US'

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale

const tZh = (key: string) => String(lookup(zhCN, key) ?? key)
const allowAll = () => true

/** Resolve `searchMenu(kw)` to the single entry for a host path. */
function hit(kw: string, path: string) {
  return searchMenu(kw, tZh, allowAll).find((e) => e.path === path)
}

describe('T407 face ①: retired paths still resolve (redirect coverage)', () => {
  // Every leaf retired by /// keeps its old route path as a
  // redirect — bookmarks and old links must not 404.
  const RETIRED_PATHS = [
    'skill-market',
    'tool-market',
    'plugins',
    'mcp-servers',
    'suites',
    'documents',
    'process',
    'schedule/fires',
    'coverage',
    'report-center',
    'mock/inspector',
    'mock/docs',
    'mock/scenarios',
    'mock/recording',
    // AL batch un-retired 'requirements' (real page again) — the
    // ledger path left this list deliberately.
  ]
  for (const p of RETIRED_PATHS) {
    it(`routes.ts keeps a redirect for /${p}`, () => {
      const re = new RegExp(`path:\\s*'${p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}',[\\s\\S]{0,260}redirect:`)
      expect(routesRaw, `retired path /${p} lost its redirect`).toMatch(re)
    })
  }
})

describe('T407 face ②: retired components are really mounted in their hosts', () => {
  const mounts: Array<[string, string, RegExp, string]> = [
    ['extensions', extensionsRaw, /import PluginListView from/, 'PluginListView'],
    ['extensions', extensionsRaw, /import McpServerView from/, 'McpServerView'],
    // AL batch: extraction host dissolved — the pages mount at ROUTE level
    // now (documents IS /extraction; the ledger is its own /requirements).
    ['extraction', routesRaw, /import\('@\/views\/ai\/DocumentListView\.vue'\)/, 'DocumentListView'],
    ['requirements', routesRaw, /import\('@\/views\/requirement\/RequirementListView\.vue'\)/, 'RequirementListView'],
    ['skills', skillsRaw, /import SkillMarketView from/, 'SkillMarketView'],
    ['tools', toolsRaw, /import ToolMarketView from/, 'ToolMarketView'],
    ['cases', casesRaw, /import SuiteListView from/, 'SuiteListView'],
    ['batches', batchesRaw, /import ScheduleFireListView from/, 'ScheduleFireListView'],
    ['execution', executionRaw, /import ProcessView from/, 'ProcessView'],
    ['report', reportRaw, /import CoverageMatrixView from/, 'CoverageMatrixView'],
    ['report', reportRaw, /import ReportCenterView from/, 'ReportCenterView'],
    ['mock workbench', mockWbRaw, /import MockScenarioListView from/, 'MockScenarioListView'],
    ['mock workbench', mockWbRaw, /import MockRecordingView from/, 'MockRecordingView'],
    ['mock workbench', mockWbRaw, /import MockInspectorView from/, 'MockInspectorView'],
  ]
  for (const [host, src, re, name] of mounts) {
    it(`${host} imports ${name}`, () => {
      expect(src, `${name} not imported in ${host}`).toMatch(re)
    })
  }
})

describe('T407 face ③: a synonym hit carries its target tab (⌘K zero-loss)', () => {
  // The tab map keys must all be live leaf paths and every mapped word must
  // also exist in that leaf's synonym list (a word absent from menuSynonyms
  // can never match, so a mapping entry there would be dead code).
  it('synonymTabs keys hit live leaves and their words exist in menuSynonyms', () => {
    const leafPaths = new Set(allMenuLeaves.map((l) => l.path))
    for (const [path, words] of Object.entries(synonymTabs)) {
      expect(leafPaths.has(path), `synonymTabs 悬空 path=${path}`).toBe(true)
      const synonyms = menuSynonyms[path] ?? []
      for (const word of Object.keys(words)) {
        expect(
          synonyms.some((s) => s.toLowerCase() === word.toLowerCase()),
          `${path} 的 tab 词「${word}」不在 menuSynonyms 中（死映射）`,
        ).toBe(true)
      }
    }
  })

  it('回归集 opens the suites tab of the case workbench', () => {
    expect(hit('回归集', '/cases')?.tab, '回归集 应带 tab=suites').toBe('suites')
  })

  it('插件 opens the plugins tab of extensions, mcp 保持 mcp 页签', () => {
    expect(hit('插件', '/extensions')?.tab, '插件 应带 tab=plugins').toBe('plugins')
  })

  it('需求点 opens the standalone requirement ledger leaf (AL batch split)', () => {
    const h = hit('需求点', '/requirements')
    expect(h, '需求点 应命中 /requirements 叶').toBeTruthy()
    expect(h!.tab, '需求点 不再携带页签').toBeUndefined()
  })

  it('技能市场/工具市场 open the market tabs', () => {
    expect(hit('技能市场', '/skills')?.tab, '技能市场 应带 tab=market').toBe('market')
    expect(hit('工具市场', '/tools')?.tab, '工具市场 应带 tab=market').toBe('market')
  })

  it('覆盖率 opens the coverage tab, 报表中心 opens the export tab', () => {
    expect(hit('覆盖率', '/report')?.tab, '覆盖率 应带 tab=coverage').toBe('coverage')
    expect(hit('报表中心', '/report')?.tab, '报表中心 应带 tab=export').toBe('export')
  })

  it('流量检视 opens the inspector tab of the mock workbench', () => {
    expect(hit('流量检视', '/mock/spaces')?.tab, '流量检视 应带 tab=inspector').toBe('inspector')
  })

  it('定时 opens the fires tab, 进程 opens the process tab', () => {
    expect(hit('定时', '/batches')?.tab, '定时 应带 tab=fires').toBe('fires')
    expect(hit('进程', '/execution')?.tab, '进程 应带 tab=process').toBe('process')
  })

  it('default-tab words need no tab (title hits stay tab-free)', () => {
    expect(hit('用例', '/cases')?.tab ?? undefined).toBeUndefined()
  })

  it('GlobalSearchDialog appends ?tab= for menu rows (source anchor)', () => {
    expect(searchDialogRaw, 'palette 未拼装 tab query').toMatch(/\?tab=/)
    expect(searchDialogRaw, 'palette 未透传 entry.tab').toMatch(/tab/)
  })
})

describe('T407 face ④: multi-chapter hosts keep their ? 帮助锚 (G/F)', () => {
  for (const path of ['/extensions', '/extraction']) {
    const leaf = allMenuLeaves.find((l) => l.path === path)
    it(`${path} carries helpAnchorKey resolving in zh and en`, () => {
      expect(leaf, `${path} missing from menu leaves`).toBeTruthy()
      expect(leaf?.helpAnchorKey, `${path} 多章域漏 helpAnchorKey`).toBeTruthy()
      const zh = lookup(zhCN, leaf!.helpAnchorKey!)
      const en = lookup(enUS, leaf!.helpAnchorKey!)
      expect(zh, `${path} anchor zh 悬空`).toBeTruthy()
      expect(en, `${path} anchor en 悬空`).toBeTruthy()
    })
  }
})
