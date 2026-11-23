
import { readFileSync } from 'node:fs'
import { describe, it, expect } from 'vitest'
import { buildMenuSearchEntries, searchMenu } from '../../src/config/menuSearch'
import { menuSynonyms } from '../../src/config/menuSynonyms'
import { allMenuLeaves } from '../../src/config/menuStructure'
import zhCN from '../../src/utils/locales/zh-CN'
import { lookupLocale } from '../_localeLookup'
import enUS from '../../src/utils/locales/en-US'

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale

const tZh = (key: string) => String(lookup(zhCN, key) ?? key)
const tEn = (key: string) => String(lookup(enUS, key) ?? key)
const allowAll = () => true
const denyAll = () => false

describe('T343 包② 菜单搜索索引件', () => {
  it('索引覆盖全部菜单叶子且无重复（与 allMenuLeaves 一一对应）', () => {
    const entries = buildMenuSearchEntries()
    expect(entries.map((e) => e.path).sort()).toEqual(allMenuLeaves.map((l) => l.path).sort())
  })

  it('每叶 titleKey+组键在 zh/en 双轨命中（禁悬空键）', () => {
    for (const e of buildMenuSearchEntries()) {
      expect(lookup(zhCN, e.titleKey), `${e.path} titleKey zh 缺`).toBeTruthy()
      expect(lookup(enUS, e.titleKey), `${e.path} titleKey en 缺`).toBeTruthy()
      for (const g of e.groupTitleKeys) {
        expect(lookup(zhCN, g), `${e.path} 组键 ${g} zh 缺`).toBeTruthy()
        expect(lookup(enUS, g), `${e.path} 组键 ${g} en 缺`).toBeTruthy()
      }
    }
  })

  it('同义词表键必须命中真实叶子 path（禁悬空 path 打错字）', () => {
    const leafPaths = new Set(allMenuLeaves.map((l) => l.path))
    for (const key of Object.keys(menuSynonyms)) {
      expect(leafPaths.has(key), `同义词表悬空 path=${key}`).toBe(true)
      expect(menuSynonyms[key].length, `${key} 同义词表为空`).toBeGreaterThan(0)
    }
  })

  it('中文同义词命中：「抢设备」直达预约日历', () => {
    const hits = searchMenu('抢设备', tZh, allowAll)
    expect(hits.map((h) => h.path)).toContain('/appauto/reservations')
  })

  it('功能名直达：路径与方案甲基线一致（缺陷/压测/仪表盘/预约）', () => {
    expect(searchMenu('缺陷', tZh, allowAll).map((h) => h.path)).toContain('/defects')
    expect(searchMenu('压测', tZh, allowAll).map((h) => h.path)).toContain('/perf')
    expect(searchMenu('仪表盘', tZh, allowAll).map((h) => h.path)).toContain('/dashboard')
    expect(searchMenu('设备预约', tZh, allowAll).map((h) => h.path)).toContain('/appauto/reservations')
  })

  it('大小写不敏感+英文可搜（REPORT 命中报告族）', () => {
    const hits = searchMenu('report', tEn, allowAll)
    expect(hits.length, 'report 应命中报告族').toBeGreaterThan(0)
    expect(hits.some((h) => h.path.startsWith('/report') || h.path === '/test-report' || h.path === '/report-center')).toBe(true)
  })

  it('权限过滤与菜单渲染同口径：无权限时 /users 不出现、全员面 /projects 仍出现', () => {
    expect(searchMenu('用户', tZh, denyAll).map((h) => h.path)).not.toContain('/users')
    expect(searchMenu('项目', tZh, denyAll).map((h) => h.path)).toContain('/projects')
    expect(searchMenu('用户', tZh, allowAll).map((h) => h.path)).toContain('/users')
  })

  it('空关键词=零结果；命中数有上限（面板不淹没）', () => {
    expect(searchMenu('   ', tZh, allowAll)).toEqual([])
    expect(searchMenu('a', tEn, allowAll).length).toBeLessThanOrEqual(8)
  })

  it('GlobalSearchDialog 双源契约：业务 GET /search 四类保留+菜单索引接入（源码锚，零回归防线）', () => {
    const src = readFileSync(new URL('../../src/components/GlobalSearchDialog.vue', import.meta.url), 'utf8')
    expect(src).toContain('globalSearch(')
    for (const c of ['cases', 'requirements', 'defects', 'projects']) {
      expect(src, `业务类别 ${c} 丢失`).toContain(`push('${c}'`)
    }
    expect(src).toContain('searchMenu')
    expect(src).toContain('catMenuPages')
  })
})
