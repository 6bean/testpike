
import { describe, expect, it } from 'vitest'
import { buildStepNodes, flattenStepNodes, newStepOf, nodePathOf } from '@/utils/stepTreeModel'

describe('nodePathOf（节点 id → 拖拽路径反查）', () => {
  it('还原 buildStepNodes 生成的点分路径', () => {
    expect(nodePathOf('n.0')).toEqual([0])
    expect(nodePathOf('n.2.5')).toEqual([2, 5])
  })
  it('根层与非树 id 返回空路径（根=drop 目标 [] 语义一致）', () => {
    expect(nodePathOf('')).toEqual([])
    expect(nodePathOf('root')).toEqual([])
    expect(nodePathOf('n.a.b')).toEqual([])
  })
  it('与 buildStepNodes 往返一致：第 i 个根节点 id 反查=[i]', () => {
    const nodes = buildStepNodes([{ action: 'goto' }, { action: 'click' }])
    expect(nodes.map((n) => nodePathOf(n.id))).toEqual([[0], [1]])
  })
})

describe('newStepOf（词表默认值新增步骤工厂）', () => {
  it('容器动作带必备空子列表：loop/if 的 steps=[]，if 无 elseSteps 键（缺键不造语义）', () => {
    expect(newStepOf('loop')).toEqual({ action: 'loop', steps: [] })
    expect(newStepOf('if')).toEqual({ action: 'if', steps: [] })
    expect('elseSteps' in newStepOf('if')).toBe(false)
  })
  it('普通动作仅 action 骨架（字段留给表单填，禁预填业务默认值）', () => {
    expect(newStepOf('goto')).toEqual({ action: 'goto' })
    expect(newStepOf('sql.exec')).toEqual({ action: 'sql.exec' })
  })
  it('词表外动作=action only 骨架（保存链由 core 白名单权威拒绝，编辑器不复制校验）', () => {
    expect(newStepOf('pos.scan')).toEqual({ action: 'pos.scan' })
  })
  it('工厂产物可过 build/flatten 往返字面一致（新增步即刻入树可渲染）', () => {
    const fresh = [newStepOf('if'), newStepOf('goto')]
    const round = flattenStepNodes(buildStepNodes(fresh))
    expect(JSON.stringify(round)).toBe(JSON.stringify(fresh))
  })
})
