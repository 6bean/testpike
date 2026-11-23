
import { describe, expect, it } from 'vitest'
import {
  buildStepNodes,
  flattenStepNodes,
  scanStaleRefs,
  type StepTreeNode,
} from '@/utils/stepTreeModel'
import { containerBranches } from '@/config/actionVocab'
import type { ScopeStep } from '@/utils/stepScope'

function nodeIds(nodes: StepTreeNode[]): string[] {
  return nodes.flatMap((n) => [n.id, ...(n.children ? nodeIds(n.children) : [])])
}

describe('buildStepNodes 树构建', () => {
  it('线性步骤=无 children 节点，id 逐层编号', () => {
    const steps: ScopeStep[] = [
      { action: 'goto', url: 'https://a' },
      { action: 'click', target: { css: '#b' } },
    ]
    const nodes = buildStepNodes(steps)
    expect(nodes).toHaveLength(2)
    expect(nodes[0].children).toBeUndefined()
    expect(nodes[0].elseStart).toBeNull()
    expect(nodes[0].depth).toBe(0)
    expect(nodes[1].step).toEqual(steps[1])
    expect(nodes[0].id).toBe('n.0')
    expect(nodes[1].id).toBe('n.1')
  })

  it('loop 容器=children 来自 steps，elseStart=null', () => {
    const steps: ScopeStep[] = [
      { action: 'loop', items: ['a', 'b'], steps: [{ action: 'click', target: { css: '#x' } }] },
    ]
    const nodes = buildStepNodes(steps)
    expect(nodes[0].children).toHaveLength(1)
    expect(nodes[0].elseStart).toBeNull()
    expect(nodes[0].children![0].id).toBe('n.0.0')
    expect(nodes[0].children![0].depth).toBe(1)
    expect(nodes[0].children![0].branch).toBe('steps')
  })

  it('if 容器=steps+elseSteps 拼接进 children，elseStart=then 长度', () => {
    const steps: ScopeStep[] = [
      {
        action: 'if',
        condition: 'x == 1',
        steps: [{ action: 'expect', text: 'then' }],
        elseSteps: [{ action: 'expect', text: 'else' }],
      },
    ]
    const nodes = buildStepNodes(steps)
    expect(nodes[0].children).toHaveLength(2)
    expect(nodes[0].elseStart).toBe(1)
    expect(nodes[0].children![1].branch).toBe('elseSteps')
  })

  it('if 无 elseSteps=不造空分支（elseStart=null，children 只含 then）', () => {
    const steps: ScopeStep[] = [
      { action: 'if', condition: 'ok', steps: [{ action: 'screenshot', name: 'shot' }] },
    ]
    const nodes = buildStepNodes(steps)
    expect(nodes[0].children).toHaveLength(1)
    expect(nodes[0].elseStart).toBeNull()
  })

  it('未知动作（行业命名空间）按非容器处理，不进 B12 词表也保留原样', () => {
    const steps: ScopeStep[] = [{ action: 'erp.create_order', params: { sku: 'a' } }]
    const nodes = buildStepNodes(steps)
    expect(nodes[0].children).toBeUndefined()
  })

  it('嵌套深度 id 逐层点分（04 §4 深度≤5 由 core 校验，模型不裁）', () => {
    const steps: ScopeStep[] = [
      {
        action: 'loop',
        count: 3,
        steps: [
          {
            action: 'if',
            condition: 'index == 1',
            steps: [{ action: 'expect', text: 'deep' }],
          },
        ],
      },
    ]
    const nodes = buildStepNodes(steps)
    expect(nodeIds(nodes)).toEqual(['n.0', 'n.0.0', 'n.0.0.0'])
  })
})

describe('flattenStepNodes 回写', () => {
  it('往返保真：build→flatten 与源 JSON 字面一致（无 elseSteps 不造键）', () => {
    const steps: ScopeStep[] = [
      { action: 'goto', url: '{{ENV:URL}}/login' },
      {
        action: 'if',
        condition: 'promo.stackable == false',
        steps: [{ action: 'expect', text: '不可叠加' }],
        elseSteps: [{ action: 'click', target: { text: '确认下单' } }],
      },
      { action: 'loop', items: [], steps: [{ action: 'input', target: { css: '#q' }, value: '{{item}}' }] },
    ]
    const back = flattenStepNodes(buildStepNodes(steps))
    expect(back).toEqual(steps)
    expect(JSON.stringify(back)).toBe(JSON.stringify(steps))
  })

  it('空 elseSteps=[] 在位=保留空分支（显式空数组语义）', () => {
    const steps: ScopeStep[] = [
      { action: 'if', condition: 'flag', steps: [{ action: 'screenshot', name: 'a' }], elseSteps: [] },
    ]
    const back = flattenStepNodes(buildStepNodes(steps))
    expect(back[0].elseSteps).toEqual([])
    expect(JSON.stringify(back)).toBe(JSON.stringify(steps))
  })

  it('子级改动回写：改 then 分支文本只影响该分支', () => {
    const steps: ScopeStep[] = [
      {
        action: 'if',
        condition: 'x > 1',
        steps: [{ action: 'expect', text: 'a' }],
        elseSteps: [{ action: 'expect', text: 'b' }],
      },
    ]
    const nodes = buildStepNodes(steps)
    nodes[0].children![0].step.text = 'changed'
    const back = flattenStepNodes(nodes)
    expect((back[0].steps as ScopeStep[])[0].text).toBe('changed')
    expect((back[0].elseSteps as ScopeStep[])[0].text).toBe('b')
  })

  it('非容器步骤 spread 保留未知字段（version/caseId 等顶层键不经此函数，动作级自定义不丢）', () => {
    const steps: ScopeStep[] = [{ action: 'wait', ms: 100, xExtra: { keep: true } } as ScopeStep]
    const back = flattenStepNodes(buildStepNodes(steps))
    expect(back[0].xExtra).toEqual({ keep: true })
  })
})

describe('scanStaleRefs 失效引用扫描（TC-B12-02 拖出红标判定基础）', () => {
  it('循环内 {{item}} 合法、循环外同引用失效点名', () => {
    const steps: ScopeStep[] = [
      { action: 'loop', items: ['a'], steps: [{ action: 'input', target: { css: '#i' }, value: '{{item}}' }] },
      { action: 'input', target: { css: '#o' }, value: '{{item}} 残留' },
    ]
    const hits = scanStaleRefs(steps)
    expect(hits).toHaveLength(1)
    expect(hits[0].ref).toBe('item')
    expect(hits[0].field).toBe('value')
    expect(hits[0].nodeId).toBe('n.1')
  })

  it('var 自定义迭代名覆写后按覆写名判定（04 §2.1 loop 行）', () => {
    const steps: ScopeStep[] = [
      { action: 'loop', items: ['a'], var: 'row', steps: [{ action: 'expect', text: '{{row}}' }] },
    ]
    expect(scanStaleRefs(steps)).toHaveLength(0)
  })

  it('count 形态默认 index；嵌套容器自外向内累积可见', () => {
    const steps: ScopeStep[] = [
      {
        action: 'loop',
        count: 2,
        steps: [
          {
            action: 'loop',
            items: ['x'],
            steps: [{ action: 'expect', text: '{{index}}-{{item}}' }],
          },
        ],
      },
    ]
    expect(scanStaleRefs(steps)).toHaveLength(0)
  })

  it('保留命名空间 ENV/DATA/PACK/VAR 不计失效', () => {
    const steps: ScopeStep[] = [
      { action: 'goto', url: '{{ENV:URL}}/x' },
      { action: 'input', value: '{{DATA:u}} {{PACK:p.k}} {{VAR:3.data.id}}' },
    ]
    expect(scanStaleRefs(steps)).toHaveLength(0)
  })

  it('locator 对象内字符串值一并扫描（target.url 场景）', () => {
    const steps: ScopeStep[] = [
      { action: 'click', target: { text: '{{ghost}}' } },
    ]
    const hits = scanStaleRefs(steps)
    expect(hits).toHaveLength(1)
    expect(hits[0].field).toBe('target.text')
    expect(hits[0].ref).toBe('ghost')
  })

  it('空 items 循环=0 次迭代但迭代变量名仍在声明位可见（静态判定非求值）', () => {
    const steps: ScopeStep[] = [
      { action: 'loop', items: [], steps: [{ action: 'expect', text: '{{item}}' }] },
    ]
    expect(scanStaleRefs(steps)).toHaveLength(0)
  })
})

describe('分支字段名单源（红线⑥：模型不 switch 动作名）', () => {
  it('loop/if 分支字段来自 actionVocab containerBranches', () => {
    expect(containerBranches('loop')).toEqual(['steps'])
    expect(containerBranches('if')).toEqual(['steps', 'elseSteps'])
    expect(containerBranches('goto')).toEqual([])
  })
})
