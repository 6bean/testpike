
import { describe, expect, it } from 'vitest'
import {
  countScriptLimits,
  diffScriptFields,
  parseScriptSourceForModeSwitch,
  prettifyScriptSource,
} from '@/utils/scriptSource'

describe('parseScriptSourceForModeSwitch（源码→树 切换闸，TC-B12-03）', () => {
  it('合法最小文档：ok 且 steps 原样返回（改动保留不回滚）', () => {
    const doc = { version: 'v2', steps: [{ action: 'goto', target: 'css:#a', value: 'http://x' }] }
    const r = parseScriptSourceForModeSwitch(JSON.stringify(doc))
    expect(r.ok).toBe(true)
    if (r.ok) expect(r.steps).toEqual(doc.steps)
  })

  it('非法 JSON：拒切+人话点名行列（多行输入定位到出错行）', () => {
    const text = '{\n  "version": "v2",\n  "steps": [ { "action": "click" , }\n]\n}'
    const r = parseScriptSourceForModeSwitch(text)
    expect(r.ok).toBe(false)
    if (!r.ok) {
      expect(r.errors.length).toBe(1)
      expect(r.errors[0].line).toBe(3)
      expect(r.errors[0].column).toBeGreaterThan(0)
      expect(r.errors[0].reason).toMatch(/JSON/)
    }
  })

  it('非法 JSON 无行列信息时回退 line=1（人话兜底不崩）', () => {
    const r = parseScriptSourceForModeSwitch('{oops')
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.errors[0].line).toBeGreaterThanOrEqual(1)
  })

  it('根非对象（数组/标量）：拒切点名顶层', () => {
    const r = parseScriptSourceForModeSwitch('[1,2]')
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.errors[0].reason).toMatch(/对象/)
  })

  it('steps 缺失或非数组：拒切点名 steps', () => {
    const miss = parseScriptSourceForModeSwitch('{"version":"v2"}')
    expect(miss.ok).toBe(false)
    if (!miss.ok) expect(miss.errors[0].reason).toMatch(/steps/)
    const bad = parseScriptSourceForModeSwitch('{"steps":{}}')
    expect(bad.ok).toBe(false)
    if (!bad.ok) expect(bad.errors[0].reason).toMatch(/steps/)
  })

  it('未知 action：拒切+按步路径点名（词表单源 actionSpecOf，深校验权威仍在 core 保存链）', () => {
    const r = parseScriptSourceForModeSwitch(
      JSON.stringify({ steps: [{ action: 'goto' }, { action: 'teleport' }] }),
    )
    expect(r.ok).toBe(false)
    if (!r.ok) {
      expect(r.errors[0].reason).toContain('teleport')
      expect(r.errors[0].reason).toContain('steps[1]')
    }
  })

  it('step 缺 action / 元素非对象：逐项点名路径，多错并列不截断（信息完整不截断）', () => {
    const r = parseScriptSourceForModeSwitch(
      JSON.stringify({ steps: [{ target: 'css:#a' }, 'oops', { action: 'loop', steps: 'notarray' }] }),
    )
    expect(r.ok).toBe(false)
    if (!r.ok) {
      expect(r.errors.length).toBe(3)
      expect(r.errors.map((e) => e.reason).join('|')).toMatch(/action/)
      expect(r.errors[1].reason).toMatch(/对象|元素/)
      expect(r.errors[2].reason).toMatch(/steps/)
    }
  })

  it('嵌套子步（loop.steps / if.elseSteps）递归校验，路径含父位', () => {
    const r = parseScriptSourceForModeSwitch(
      JSON.stringify({
        steps: [{ action: 'if', condition: 'x == 1', elseSteps: [{ action: 'nope' }] }],
      }),
    )
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.errors[0].reason).toContain('steps[0].elseSteps[0]')
  })

  it('loop/if 合法嵌套：放行（解释支 M4 已在词表，切换闸零误拦）', () => {
    const r = parseScriptSourceForModeSwitch(
      JSON.stringify({
        steps: [{ action: 'loop', items: '{{DATA:a}}', steps: [{ action: 'click', target: 'css:#b' }] }],
      }),
    )
    expect(r.ok).toBe(true)
  })

  it('空白文本=空文档拒切（源码模式不允许清空整份脚本，人话点名 steps）', () => {
    const r = parseScriptSourceForModeSwitch('   ')
    expect(r.ok).toBe(false)
  })
})

describe('diffScriptFields（保存 diff 预览展示形态，镜像 core ActionJsonDiffCalculator）', () => {
  it('04 顶层字段中文标签+定义序（FIELD_LABELS 单点对齐 core）', () => {
    const changes = diffScriptFields(
      { version: 'v2', title: '旧目标' },
      { version: 'v2', title: '新目标' },
    )
    expect(changes).toHaveLength(1)
    expect(changes[0].field).toBe('title')
    expect(changes[0].label).toBe('测试目标')
    expect(changes[0].before).toBe('旧目标')
    expect(changes[0].after).toBe('新目标')
  })

  it('steps 数组整体比较，before/after=动作列表供前端逐条高亮', () => {
    const s1 = [{ action: 'goto' }]
    const s2 = [{ action: 'goto' }, { action: 'click' }]
    const changes = diffScriptFields({ steps: s1 }, { steps: s2 })
    expect(changes).toHaveLength(1)
    expect(changes[0].field).toBe('steps')
    expect(changes[0].label).toBe('操作步骤')
    expect(changes[0].after).toEqual(s2)
  })

  it('键序不同值相同=语义等值不报（JSON 语义相等，镜像 Java JsonNode.equals）', () => {
    const changes = diffScriptFields(
      { env: { a: 1, b: [1, { c: 2 }] } },
      { env: { b: [1, { c: 2 }], a: 1 } },
    )
    expect(changes).toHaveLength(0)
  })

  it('缺失 vs 显式 null 归一为无值不报；值与 null 变化要报', () => {
    expect(diffScriptFields({}, { metadata: null })).toHaveLength(0)
    expect(diffScriptFields({ metadata: 'x' }, { metadata: null })).toHaveLength(1)
  })

  it('未知顶层扩展键=已知字段序后并集升序补报（契约扩展键不静默丢）', () => {
    const changes = diffScriptFields({ zeta: 1, alpha: 1 }, { zeta: 2, alpha: 2 })
    expect(changes.map((c) => c.field)).toEqual(['alpha', 'zeta'])
    const mixed = diffScriptFields({ steps: [], extra: 1 }, { steps: [{ action: 'goto' }], extra: 2 })
    expect(mixed.map((c) => c.field)).toEqual(['steps', 'extra'])
  })
})

describe('countScriptLimits（三键用量条计数，镜像 ScriptLimitGuard walk 口径）', () => {
  it('平铺步骤：nodes=对象数，loops=0，depth=1；非对象元素跳过', () => {
    const c = countScriptLimits([{ action: 'goto' }, 'junk', { action: 'click' }] as never)
    expect(c).toEqual({ nodes: 2, loops: 0, maxDepth: 1 })
  })

  it('loop/if 容器与 elseSteps 递归 +1，loop 单独计数，深度取最大', () => {
    const c = countScriptLimits([
      {
        action: 'loop',
        steps: [
          { action: 'if', condition: 'x == 1', steps: [{ action: 'click' }], elseSteps: [{ action: 'wait' }] },
        ],
      },
      { action: 'goto' },
    ] as never)
    expect(c.nodes).toBe(5)
    expect(c.loops).toBe(1)
    expect(c.maxDepth).toBe(3)
  })

  it('空 steps=零计数（新脚本初始态用量条不炸）', () => {
    expect(countScriptLimits([])).toEqual({ nodes: 0, loops: 0, maxDepth: 0 })
  })
})

describe('prettifyScriptSource（源码模式格式化按钮，轻拦单源=checkJsonText）', () => {
  it('合法 JSON 重排两空格缩进', () => {
    const r = prettifyScriptSource('{"steps":[{"action":"goto"}]}')
    expect(r.ok).toBe(true)
    if (r.ok) expect(r.text).toContain('\n  "steps"')
  })

  it('非法 JSON 拒格式化（不改动原文，改动保留 R2）', () => {
    expect(prettifyScriptSource('{oops').ok).toBe(false)
  })
})
