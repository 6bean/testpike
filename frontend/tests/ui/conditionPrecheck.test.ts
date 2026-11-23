
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { checkCondition } from '@/utils/conditionPrecheck'
import { ACTION_VOCAB, actionSpecOf } from '@/config/actionVocab'
import { collectVisibleVars, hasStaleVarRef, REF_NAMESPACES } from '@/utils/stepScope'

const here = dirname(fileURLToPath(import.meta.url))
const shared = JSON.parse(
  readFileSync(resolve(here, '../../../shared/condition_expr_cases.json'), 'utf-8'),
)

describe('conditionPrecheck：两端同源夹具负向族=文法外一律拒（R3 共享清单）', () => {
  for (const c of shared.negatives) {
    it(`拒绝 ${JSON.stringify(c.expr)}（${c.why}）`, () => {
      const r = checkCondition(c.expr, [])
      expect(r.ok, `应拒绝：${c.why}`).toBe(false)
      expect(r.error && r.error.length).toBeGreaterThan(0)
    })
  }
})

describe('conditionPrecheck：两端同源夹具正向族=文法内按作用域放行', () => {
  for (const c of shared.positives) {
    it(`放行 ${JSON.stringify(c.expr)}`, () => {
      const r = checkCondition(c.expr, Object.keys(c.scope))
      expect(r.ok, r.error).toBe(true)
    })
  }
})

describe('conditionPrecheck：作用域点名与语义细节镜像 Python 侧', () => {
  it('未定义变量点名（人话含变量名与可用清单）', () => {
    const r = checkCondition('nope == 1', ['item', 'index'])
    expect(r.ok).toBe(false)
    expect(r.error).toContain('未定义变量')
    expect(r.error).toContain('nope')
    expect(r.error).toContain('item')
  })
  it('点路径只校验首段（item.price 对 known=[item] 合法）', () => {
    expect(checkCondition('item.price > 100', ['item']).ok).toBe(true)
    const bad = checkCondition('promo.stackable', [])
    expect(bad.ok).toBe(false)
    expect(bad.error).toContain('promo')
  })
  it('大小比较字面量禁字符串操作数（与 Python 侧同源语义）', () => {
    expect(checkCondition("'a' < 'b'").ok).toBe(false)
    expect(checkCondition("'1' > 0").ok).toBe(false)
    expect(checkCondition('1 < 2').ok).toBe(true)
    expect(checkCondition('true >= 1').ok).toBe(true)
  })
  it('不传作用域=仅文法校验（条件构建器草稿态不拦未定型变量）', () => {
    expect(checkCondition('anything == 1').ok).toBe(true)
  })
  it('and/or/not 与 &&/||/! 符号等价（关键字小写=Python 权威侧词法实态，大写落为未定义变量）', () => {
    expect(checkCondition('a == 1 and a == 1').ok).toBe(true)
    expect(checkCondition('!(a == 2) || a == 1', ['a']).ok).toBe(true)
    expect(checkCondition('a == 1 AND a == 1', ['a']).ok).toBe(false)
  })
  it('布尔可与数值互比、字符串不与数值跨型相等', () => {
    expect(checkCondition('flag == 1', ['flag']).ok).toBe(true)
    expect(checkCondition("'1' == 1", []).ok).toBe(false)
  })
})

describe('actionVocab：04 词表→表单字段单一映射（配置完整性钉）', () => {
  it('动作名唯一且全部带契约源标注', () => {
    const names = ACTION_VOCAB.map((s) => s.action)
    expect(new Set(names).size).toBe(names.length)
    for (const spec of ACTION_VOCAB) {
      expect(spec.contract.startsWith('04 §'), spec.action).toBe(true)
      for (const f of spec.fields) {
        expect(f.key.length, `${spec.action}.${f.key}`).toBeGreaterThan(0)
        expect(f.label.length, `${spec.action}.${f.key}`).toBeGreaterThan(0)
      }
    }
  })
  it('容器动作结构：loop=items/count 二选一+var+steps；if=condition+steps+elseSteps', () => {
    const loop = actionSpecOf('loop')
    expect(loop?.container).toBe(true)
    expect(loop?.fields.map((f) => f.key)).toEqual(
      expect.arrayContaining(['items', 'count', 'var', 'steps']),
    )
    expect(loop?.fields.find((f) => f.key === 'steps')?.type).toBe('steps')
    const cond = actionSpecOf('if')?.fields.find((f) => f.key === 'condition')
    expect(cond?.type).toBe('condition')
    expect(actionSpecOf('if')?.fields.map((f) => f.key)).toEqual(
      expect.arrayContaining(['elseSteps']),
    )
  })
  it('expect v3 扩展字段 matchType 枚举=contains/exact/regex/range', () => {
    const mt = actionSpecOf('expect')?.fields.find((f) => f.key === 'matchType')
    expect(mt?.enum).toEqual(['contains', 'exact', 'regex', 'range'])
  })
  it('sql.exec=AI 禁自动产（manualOnly 标记，表单展示口径）', () => {
    expect(actionSpecOf('sql.exec')?.manualOnly).toBe(true)
    expect(actionSpecOf('goto')?.manualOnly ?? false).toBe(false)
  })
  it('B11 非 UI 四断言原语 + http.request + sql.query 全在词表', () => {
    for (const a of ['console.assert', 'net.assert', 'http.request', 'sql.assert', 'sql.exec', 'sql.query']) {
      expect(actionSpecOf(a), a).toBeDefined()
    }
  })
})

describe('stepScope：迭代变量作用域候选（TC-B12-02 半区）', () => {
  const steps = [
    { action: 'goto' },
    {
      action: 'loop',
      items: ['a', 'b'],
      steps: [
        { action: 'input' },
        {
          action: 'if',
          condition: 'item == "a"',
          steps: [{ action: 'click' }],
          elseSteps: [
            { action: 'loop', count: 2, var: 'round', steps: [{ action: 'wait' }] },
          ],
        },
      ],
    },
    { action: 'expect' },
  ]

  it('items 形态默认 item、var 自定义覆写、count 形态默认 index', () => {
    expect(collectVisibleVars(steps, [{ branch: 'steps', index: 1 }])).toEqual(['item'])
  })
  it('嵌套作用域自外向内累积（if then 链路=外层 loop 变量可见）', () => {
    const path = [
      { branch: 'steps' as const, index: 1 },
      { branch: 'steps' as const, index: 1 },
      { branch: 'steps' as const, index: 0 },
    ]
    expect(collectVisibleVars(steps, path)).toEqual(['item'])
  })
  it('elseSteps 链路同样继承外层并累积本层自定义 var', () => {
    const path = [
      { branch: 'steps' as const, index: 1 },
      { branch: 'elseSteps' as const, index: 1 },
      { branch: 'steps' as const, index: 0 },
    ]
    expect(collectVisibleVars(steps, path)).toEqual(['item', 'round'])
  })
  it('拖出容器后失效引用判定（{{nope}} 红标，ENV/VAR 命名空间豁免）', () => {
    expect(hasStaleVarRef('hello {{item}}', [])).toBe('item')
    expect(hasStaleVarRef('hello {{item}}', ['item'])).toBeNull()
    expect(hasStaleVarRef('{{ENV:URL}} 与 {{VAR:1.data.id}}', [])).toBeNull()
  })
  it('根层/循环外不可见迭代变量', () => {
    expect(collectVisibleVars(steps, [{ branch: 'steps', index: 2 }])).toEqual([])
    expect(collectVisibleVars(steps, [])).toEqual([])
  })
  it('路径越界=防御式回退到可解析前缀（拖拽竞态下禁抛异常）', () => {
    expect(collectVisibleVars(steps, [{ branch: 'steps', index: 99 }])).toEqual([])
  })
  it('引用候选保留命名空间=ENV/DATA/PACK/VAR 四员', () => {
    expect(REF_NAMESPACES).toEqual(['ENV', 'DATA', 'PACK', 'VAR'])
  })
})
