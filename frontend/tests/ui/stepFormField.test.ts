
import { describe, expect, it } from 'vitest'
import { actionSpecOf } from '@/config/actionVocab'
import {
  applyFieldEdit,
  LOCATOR_FIELDS,
  toControlValue,
  type LocatorModel,
} from '@/utils/stepFormField'

function field(key: string) {
  const spec = actionSpecOf('expect')
  const f = spec?.fields.find((x) => x.key === key)
  if (!f) throw new Error(`vocab field missing: expect.${key}`)
  return f
}
const gotoUrl = actionSpecOf('goto')!.fields[0]
const itemsField = actionSpecOf('loop')!.fields.find((f) => f.key === 'items')!
const headersField = actionSpecOf('http.request')!.fields.find((f) => f.key === 'headers')!
const targetField = field('target')
const inputFieldValue = actionSpecOf('input')!.fields.find((f) => f.key === 'value')!
const exactField = field('exact')
const minField = field('min')

describe('toControlValue（step→控件初值）', () => {
  it('text/enum 缺键与存在值', () => {
    expect(toControlValue({ action: 'goto' }, gotoUrl)).toBe('')
    expect(toControlValue({ action: 'goto', url: 'https://a' }, gotoUrl)).toBe('https://a')
    expect(toControlValue({ action: 'expect' }, field('state'))).toBe('')
  })
  it('number/bool 缺键=空/null 与 false', () => {
    expect(toControlValue({ action: 'expect' }, minField)).toBeNull()
    expect(toControlValue({ action: 'expect', min: 3 }, minField)).toBe(3)
    expect(toControlValue({ action: 'expect' }, exactField)).toBe(false)
    expect(toControlValue({ action: 'expect', exact: true }, exactField)).toBe(true)
  })
  it('stringList 缺键=[]，存在值原样数组', () => {
    expect(toControlValue({ action: 'loop', count: 2 }, itemsField)).toEqual([])
    expect(toControlValue({ action: 'loop', items: ['a', 'b'] }, itemsField)).toEqual(['a', 'b'])
  })
  it('json 型：缺键=空串，存在值=pretty JSON 文本', () => {
    expect(toControlValue({ action: 'http.request' }, headersField)).toBe('')
    expect(toControlValue({ action: 'http.request', headers: { A: 1 } }, headersField)).toBe(
      JSON.stringify({ A: 1 }, null, 2),
    )
  })
  it('locator 型：六子字段模型（§2.3），chain 缺=[]，非对象脏值=空模型', () => {
    const m = toControlValue({ action: 'click' }, targetField) as LocatorModel
    expect(Object.keys(m).sort()).toEqual([...LOCATOR_FIELDS.map((f) => f.key)].sort())
    expect(m.css).toBe('')
    expect(m.chain).toEqual([])
    const m2 = toControlValue(
      { action: 'click', target: { css: '#a', chain: ['css', 'text'] } },
      targetField,
    ) as LocatorModel
    expect(m2.css).toBe('#a')
    expect(m2.chain).toEqual(['css', 'text'])
    expect((toControlValue({ action: 'click', target: 'bad' }, targetField) as LocatorModel).css).toBe('')
  })
})

describe('applyFieldEdit（控件→step 不可变回写）', () => {
  it('写入/改写不副作用源对象，已有键保持键序', () => {
    const src = { action: 'goto', url: 'old' }
    const out = applyFieldEdit(src, gotoUrl, 'new')
    expect(src.url).toBe('old')
    expect(out.step).toEqual({ action: 'goto', url: 'new' })
    const src2 = { action: 'input', target: { css: '#a' }, value: 'v' }
    const out2 = applyFieldEdit(src2, inputFieldValue, 'w')
    expect(Object.keys(out2.step)).toEqual(['action', 'target', 'value'])
  })
  it('空值=删键（text 空串 / number 空 / bool false / stringList 全空行）', () => {
    expect(applyFieldEdit({ action: 'goto', url: 'x' }, gotoUrl, '').step).toEqual({ action: 'goto' })
    expect(applyFieldEdit({ action: 'expect', min: 3 }, minField, null).step).toEqual({ action: 'expect' })
    expect(applyFieldEdit({ action: 'expect', exact: true }, exactField, false).step).toEqual({ action: 'expect' })
    expect(applyFieldEdit({ action: 'expect', exact: true }, exactField, false).ok).toBe(true)
    const ignoreField = actionSpecOf('console.assert')!.fields.find((f) => f.key === 'ignorePatterns')!
    expect(applyFieldEdit({ action: 'console.assert', ignorePatterns: ['x'] }, ignoreField, ['  ', '']).step)
      .toEqual({ action: 'console.assert' })
  })
  it('T244②：loop items=preserveEmptyList，空值保留 items: [] 不删键', () => {
    expect(itemsField.preserveEmptyList).toBe(true)
    expect(applyFieldEdit({ action: 'loop', items: ['a'] }, itemsField, ['  ', '']).step)
      .toEqual({ action: 'loop', items: [] })
    expect(applyFieldEdit({ action: 'loop' }, itemsField, []).step)
      .toEqual({ action: 'loop', items: [] })
    expect(applyFieldEdit({ action: 'loop' }, itemsField, [' a ', '', 'b']).step)
      .toEqual({ action: 'loop', items: ['a', 'b'] })
  })
  it('stringList 逐行 trim 且丢弃空行', () => {
    const out = applyFieldEdit({ action: 'loop' }, itemsField, [' a ', '', 'b'])
    expect(out.ok).toBe(true)
    expect(out.step).toEqual({ action: 'loop', items: ['a', 'b'] })
  })
  it('json 型：合法文本→解析值写入；非法→ok=false+error 且 step 原样；空→删键', () => {
    const ok = applyFieldEdit({ action: 'http.request' }, headersField, '{"A": 1}')
    expect(ok.ok).toBe(true)
    expect(ok.step).toEqual({ action: 'http.request', headers: { A: 1 } })
    const bad = applyFieldEdit({ action: 'http.request', headers: { old: 1 } }, headersField, '{oops')
    expect(bad.ok).toBe(false)
    expect(bad.error).toBeTruthy()
    expect(bad.step).toEqual({ action: 'http.request', headers: { old: 1 } })
    expect(applyFieldEdit({ action: 'http.request', headers: { a: 1 } }, headersField, '  ').step).toEqual({
      action: 'http.request',
    })
  })
  it('number 型：数字串转换、非数 error、数值直存', () => {
    expect(applyFieldEdit({ action: 'expect' }, minField, 5).step).toEqual({ action: 'expect', min: 5 })
    const out = applyFieldEdit({ action: 'expect', min: 1 }, minField, 'abc' as unknown as number)
    expect(out.ok).toBe(false)
    expect(out.step).toEqual({ action: 'expect', min: 1 })
  })
  it('locator：非空子字段组装对象；chain 逐行清洗；全空=删 target 键', () => {
    const out = applyFieldEdit({ action: 'click', value: 'keep' }, targetField, {
      css: '#a',
      xpath: '',
      text: '登录',
      role: '',
      accessibilityId: '',
      chain: ['css', '', 'text'],
    })
    expect(out.step).toEqual({ action: 'click', value: 'keep', target: { css: '#a', text: '登录', chain: ['css', 'text'] } })
    const cleared = applyFieldEdit({ action: 'click', target: { css: '#a' } }, targetField, {
      css: '',
      xpath: '',
      text: '',
      role: '',
      accessibilityId: '',
      chain: [],
    })
    expect(cleared.step).toEqual({ action: 'click' })
  })
  it('词表外动作字段防御：number 布尔脏值不误删', () => {
    const out = applyFieldEdit({ action: 'custom.act', timeoutMs: true } as Record<string, unknown>, {
      key: 'timeoutMs',
      label: '超时(ms)',
      type: 'number',
    } as (typeof minField), '200')
    expect(out.ok).toBe(true)
    expect((out.step as Record<string, unknown>).timeoutMs).toBe(200)
  })
})
