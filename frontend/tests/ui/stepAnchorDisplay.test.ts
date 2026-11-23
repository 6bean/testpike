/**
 * L2 contract for the step evidence anchor display (generation-accuracy batch,
 * report A display face): the step table must show a readable trace source for
 * the three registered anchor kinds and an honest empty for everything it
 * cannot read — never a crash, never an invented label.
 *
 * Shape authority = core CaseStepAnchorNormalizer (kind=elem/source/contract);
 * these specs pin only the reading side, and the locale parity assertions keep
 * both language packs shipping the same keys.
 */
import { describe, expect, it } from 'vitest'
import { formatStepAnchor, parseStepAnchor, type AnchorTranslate } from '@/utils/stepAnchorDisplay'
import { lookupLocale } from '../_localeLookup'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

/** Minimal named-interpolation over a real locale tree (missing key = loud failure). */
function makeT(locale: unknown): AnchorTranslate {
  return (key: string, named?: Record<string, string | number>) => {
    const raw = lookupLocale(locale, key)
    if (typeof raw !== 'string') throw new Error(`locale key missing: ${key}`)
    return raw.replace(/\{(\w+)\}/g, (_m, param: string) => String(named?.[param] ?? ''))
  }
}

const zh = makeT(zhCN)

describe('parseStepAnchor：三型锚形态读取', () => {
  it('elem 单值 ref 折叠进 refs 清单', () => {
    const view = parseStepAnchor(JSON.stringify({ kind: 'elem', ref: '登录按钮' }))
    expect(view?.kind).toBe('elem')
    expect(view?.refs).toEqual(['登录按钮'])
  })

  it('elem 多值 refs 保序去空', () => {
    const view = parseStepAnchor('{"kind":"elem","refs":["提交按钮"," 弹窗关闭 ",""]}')
    expect(view?.refs).toEqual(['提交按钮', '弹窗关闭'])
  })

  it('source 型带需求/文档/原文块号', () => {
    const view = parseStepAnchor('{"kind":"source","requirementId":12,"documentId":45,"chunkIds":[7,8]}')
    expect(view).toMatchObject({ kind: 'source', requirementId: 12, documentId: 45, chunkIds: [7, 8] })
  })

  it('contract 型读折叠端点键', () => {
    const view = parseStepAnchor('{"kind":"contract","ref":"POST /api/v1/orders"}')
    expect(view?.endpoint).toBe('POST /api/v1/orders')
  })
})

describe('parseStepAnchor：读不懂即无锚（诚实空位，禁崩渲染链）', () => {
  it('null / 空串 / 空白串', () => {
    expect(parseStepAnchor(null)).toBeNull()
    expect(parseStepAnchor(undefined)).toBeNull()
    expect(parseStepAnchor('   ')).toBeNull()
  })

  it('坏 JSON 与裸值/数组', () => {
    expect(parseStepAnchor('{"kind":"elem"')).toBeNull()
    expect(parseStepAnchor('"登录按钮"')).toBeNull()
    expect(parseStepAnchor('[1,2]')).toBeNull()
  })

  it('未注册的 kind 不硬凑文案', () => {
    expect(parseStepAnchor('{"kind":"unknown","ref":"x"}')).toBeNull()
  })

  it('elem 有 kind 无引用值、source 无任何编号、contract 空键 = 无可显示', () => {
    expect(formatStepAnchor('{"kind":"elem"}', zh)).toBeNull()
    expect(formatStepAnchor('{"kind":"source"}', zh)).toBeNull()
    expect(formatStepAnchor('{"kind":"contract","ref":"  "}', zh)).toBeNull()
  })
})

describe('formatStepAnchor：上屏文案（zh 语言包实值）', () => {
  it('elem 单值与多值顿号分隔', () => {
    expect(formatStepAnchor('{"kind":"elem","ref":"登录按钮"}', zh)).toBe('页面元素：登录按钮')
    expect(formatStepAnchor('{"kind":"elem","refs":["登录框","登录按钮"]}', zh)).toBe('页面元素：登录框、登录按钮')
  })

  it('source 三段拼接（需求/文档/原文块）', () => {
    expect(formatStepAnchor('{"kind":"source","requirementId":12,"documentId":45,"chunkIds":[7,8]}', zh)).toBe(
      '需求 #12 · 文档 #45 · 原文块 7, 8',
    )
  })

  it('source 仅有文档号时不编造需求号', () => {
    expect(formatStepAnchor('{"kind":"source","documentId":45}', zh)).toBe('文档 #45')
  })

  it('contract 端点键直出', () => {
    expect(formatStepAnchor('{"kind":"contract","ref":"GET /api/v1/cases"}', zh)).toBe('接口契约：GET /api/v1/cases')
  })

  it('无锚载荷 = null（页面渲染诚实空位）', () => {
    expect(formatStepAnchor(null, zh)).toBeNull()
    expect(formatStepAnchor('not json', zh)).toBeNull()
  })
})

describe('语言包对账：zh/en 键集一致', () => {
  const KEYS = [
    'case.stepEvidence',
    'case.stepAnchorElem',
    'case.stepAnchorRequirement',
    'case.stepAnchorDocument',
    'case.stepAnchorChunks',
    'case.stepAnchorContract',
    'case.stepAnchorListSep',
  ]
  it.each(KEYS)('%s 双语在位', (key) => {
    expect(typeof lookupLocale(zhCN, key)).toBe('string')
    expect(typeof lookupLocale(enUS, key)).toBe('string')
  })

  it('en 文案可插值渲染（同一格式化件双语通用）', () => {
    const en = makeT(enUS)
    expect(formatStepAnchor('{"kind":"elem","refs":["Login","Submit"]}', en)).toBe('Page element: Login, Submit')
  })
})
