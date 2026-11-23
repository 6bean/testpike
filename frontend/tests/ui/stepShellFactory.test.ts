
import { describe, expect, it } from 'vitest'
import { makeIfShell, makeLoopShell } from '@/utils/stepShellFactory'

describe('makeLoopShell 循环壳工厂（TC-V4-01 壳自动合法 + TC-V4-02 冲突预检）', () => {
  it('无冲突缺省形态 = items+steps 骨架，迭代变量默认 item（不写 var 键）', () => {
    const r = makeLoopShell([])
    expect(r.ok).toBe(true)
    expect(r.step).toEqual({ action: 'loop', items: [], steps: [] })
  })

  it('自定义 varName 合法 = 显式携带 var 键（04 §2.1 var 覆写形态）', () => {
    const r = makeLoopShell([], 'row')
    expect(r.ok).toBe(true)
    expect(r.step).toEqual({ action: 'loop', items: [], steps: [], var: 'row' })
  })

  it('拟用名与外层可见变量重名 = 人话报错不落脏（TC-V4-02 主案）', () => {
    const r = makeLoopShell(['item', 'row'], 'row')
    expect(r.ok).toBe(false)
    expect(r.step).toBeUndefined()
    expect(r.message).toContain('"row"')
    expect(r.message).toContain('冲突')
  })

  it('缺省默认名 item 与外层 items 壳迭代变量撞名同样预检拦截', () => {
    const r = makeLoopShell(['item'])
    expect(r.ok).toBe(false)
    expect(r.message).toContain('item')
  })

  it('varName 非法名（非标识符/空白）= 人话拒（executor 变量名文法镜像，禁产脏）', () => {
    const r = makeLoopShell([], '2bad name')
    expect(r.ok).toBe(false)
    expect(r.message).toContain('变量名')
  })
})

describe('makeIfShell 分支壳工厂（TC-V4-01 else 分支自动生成在位）', () => {
  it('if 壳 = condition 空串 + then/else 双分支骨架在位', () => {
    expect(makeIfShell()).toEqual({ action: 'if', condition: '', steps: [], elseSteps: [] })
  })

  it('壳节点经 buildStepNodes→flattenStepNodes 往返零漂移（elseSteps=[] 显式空分支语义保留）', async () => {
    const { buildStepNodes, flattenStepNodes } = await import('@/utils/stepTreeModel')
    const shell = makeIfShell()
    const round = flattenStepNodes(buildStepNodes([shell]))
    expect(JSON.stringify(round)).toBe(JSON.stringify([shell]))
  })
})
