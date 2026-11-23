
import { describe, expect, it } from 'vitest'
import {
  crossLayerDrop,
  moveInPlace,
  useStepDragSort,
} from '@/composables/useStepDragSort'

type Tree = { id: string; children?: Tree[] }

function buf(...descs: string[]) {
  return descs.map((d) => ({ description: d, isAssert: 0 }))
}

describe('moveInPlace（拖拽位移唯一原语，禁各消费点复制 splice 逻辑）', () => {
  it('正常位移', () => {
    const list = ['a', 'b', 'c', 'd']
    moveInPlace(list, 0, 2)
    expect(list).toEqual(['b', 'c', 'a', 'd'])
  })
  it('同位/越界/负值=零位移', () => {
    const list = ['a', 'b']
    moveInPlace(list, 1, 1)
    moveInPlace(list, -1, 0)
    moveInPlace(list, 0, 5)
    moveInPlace(list, 9, 0)
    expect(list).toEqual(['a', 'b'])
  })
})

describe('useStepDragSort（buffer/撤销栈/diff 内核）', () => {
  it('reset 建立基线：dirty=false、栈清空', () => {
    const k = useStepDragSort()
    k.reset(buf('A', 'B', 'C'))
    expect(k.dirty.value).toBe(false)
    expect(k.canUndo.value).toBe(false)
    expect(k.ordered.value.map((s) => s.description)).toEqual(['A', 'B', 'C'])
  })

  it('拖拽会话：开始压栈→悬停位移→dirty→undo 回会话前且 dirty 归零', () => {
    const k = useStepDragSort()
    k.reset(buf('A', 'B', 'C'))
    k.beginDrag(0)
    k.dragHoverTo(2)
    expect(k.ordered.value.map((s) => s.description)).toEqual(['B', 'C', 'A'])
    expect(k.dirty.value).toBe(true)
    expect(k.canUndo.value).toBe(true)
    k.undo()
    expect(k.ordered.value.map((s) => s.description)).toEqual(['A', 'B', 'C'])
    expect(k.dirty.value).toBe(false)
    expect(k.canUndo.value).toBe(false)
  })

  it('一次会话多次悬停只压栈一次（撤销回会话起点，非逐步回退）', () => {
    const k = useStepDragSort()
    k.reset(buf('A', 'B', 'C', 'D'))
    k.beginDrag(0)
    k.dragHoverTo(1)
    k.dragHoverTo(2)
    k.dragHoverTo(3)
    k.endDrag()
    expect(k.ordered.value.map((s) => s.description)).toEqual(['B', 'C', 'D', 'A'])
    k.undo()
    expect(k.ordered.value.map((s) => s.description)).toEqual(['A', 'B', 'C', 'D'])
  })

  it('movedSet=与基线不同位下标集合', () => {
    const k = useStepDragSort()
    k.reset(buf('A', 'B', 'C'))
    k.beginDrag(0)
    k.dragHoverTo(2)
    expect([...k.movedSet.value].sort()).toEqual([0, 1, 2])
    k.undo()
    expect(k.movedSet.value.size).toBe(0)
  })

  it('diffRows=逐序号 before/after 对照行', () => {
    const k = useStepDragSort()
    k.reset(buf('A', 'B'))
    k.beginDrag(0)
    k.dragHoverTo(1)
    expect(k.diffRows.value).toEqual([
      { seq: 1, before: 'A', after: 'B' },
      { seq: 2, before: 'B', after: 'A' },
    ])
  })

  it('撤销栈容量 ≤50：51 次会话后栈顶=最近快照、长度钉死 50', () => {
    const k = useStepDragSort()
    k.reset(buf('A', 'B', 'C'))
    for (let i = 0; i < 51; i++) {
      k.beginDrag(i % 2)
      k.dragHoverTo((i % 2) === 0 ? 2 : 0)
      k.endDrag()
    }
    expect(k.undoStack.value.length).toBe(50)
  })

  it('reset 换服务端基准=清栈重置换代（保存成功 load() 后 buffer 对齐）', () => {
    const k = useStepDragSort()
    k.reset(buf('A', 'B'))
    k.beginDrag(0)
    k.dragHoverTo(1)
    const epochBefore = k.dragEpoch.value
    k.reset(buf('X', 'Y', 'Z'))
    expect(k.ordered.value.map((s) => s.description)).toEqual(['X', 'Y', 'Z'])
    expect(k.undoStack.value.length).toBe(0)
    expect(k.dirty.value).toBe(false)
    expect(k.dragEpoch.value).toBe(epochBefore + 1)
  })

  it('confirm 载荷：toOrderedSteps 重编 stepNo 1..N 携 isAssert', () => {
    const k = useStepDragSort()
    k.reset([
      { description: 'A', isAssert: 0 },
      { description: 'B', isAssert: 1 },
    ])
    k.beginDrag(0)
    k.dragHoverTo(1)
    expect(k.toOrderedSteps()).toEqual([
      { stepNo: 1, description: 'B', isAssert: 1 },
      { stepNo: 2, description: 'A', isAssert: 0 },
    ])
  })

  it('键盘链：抓取→↑↓ 逐步移动且每步可撤销→Esc 释放', () => {
    const k = useStepDragSort()
    k.reset(buf('A', 'B', 'C'))
    k.toggleKeyboardGrab(0)
    expect(k.keyboardGrab.value).toBe(0)
    k.keyboardMove(1)
    expect(k.ordered.value.map((s) => s.description)).toEqual(['B', 'A', 'C'])
    expect(k.keyboardGrab.value).toBe(1)
    k.keyboardMove(1)
    expect(k.ordered.value.map((s) => s.description)).toEqual(['B', 'C', 'A'])
    k.cancelKeyboardGrab()
    expect(k.keyboardGrab.value).toBe(-1)
    k.keyboardMove(-1)
    expect(k.ordered.value.map((s) => s.description)).toEqual(['B', 'C', 'A'])
    k.undo()
    k.undo()
    expect(k.ordered.value.map((s) => s.description)).toEqual(['A', 'B', 'C'])
  })

  it('键盘越界钉死端点（首位再上移/末位再下移=原地）', () => {
    const k = useStepDragSort()
    k.reset(buf('A', 'B'))
    k.toggleKeyboardGrab(0)
    k.keyboardMove(-1)
    expect(k.keyboardGrab.value).toBe(0)
    expect(k.ordered.value.map((s) => s.description)).toEqual(['A', 'B'])
    k.keyboardMove(1)
    k.keyboardMove(1)
    expect(k.keyboardGrab.value).toBe(1)
    k.keyboardMove(1)
    expect(k.keyboardGrab.value).toBe(1)
  })
})

describe('crossLayerDrop（T198 树编辑器跨层 drop 收敛纯函数）', () => {
  const tree = (): Tree[] => [
    { id: 'a' },
    { id: 'loop', children: [{ id: 'x' }, { id: 'y' }] },
    { id: 'b' },
  ]

  it('根→容器内嵌（[1]←[0]）', () => {
    const next = crossLayerDrop(tree(), [0], [1], 1)
    expect(next).not.toBeNull()
    expect(next!.map((n) => n.id)).toEqual(['loop', 'b'])
    expect(next![0].children!.map((c) => c.id)).toEqual(['x', 'a', 'y'])
  })

  it('容器→根（子节点摘出到根尾）', () => {
    const next = crossLayerDrop(tree(), [1, 0], [], 3)
    expect(next!.map((n) => n.id)).toEqual(['a', 'loop', 'b', 'x'])
    expect(next![1].children!.map((c) => c.id)).toEqual(['y'])
  })

  it('同容器换序（toIndex 以移除后列表为基准）', () => {
    const next = crossLayerDrop(tree(), [1, 0], [1], 1)
    expect(next![1].children!.map((c) => c.id)).toEqual(['y', 'x'])
  })

  it('禁移入自身子树=拒落 null（防环）', () => {
    expect(crossLayerDrop(tree(), [1], [1, 0], 0)).toBeNull()
    expect(crossLayerDrop(tree(), [1], [1], 0)).toBeNull()
  })

  it('源路径非法：空路径/越界/中间非容器=拒落 null', () => {
    expect(crossLayerDrop(tree(), [], [0], 0)).toBeNull()
    expect(crossLayerDrop(tree(), [9], [], 0)).toBeNull()
    expect(crossLayerDrop(tree(), [0, 0], [], 0)).toBeNull()
    expect(crossLayerDrop(tree(), [1, 9], [], 0)).toBeNull()
  })

  it('目标容器非法=回退等价？否——拒落 null 且不触原树', () => {
    const src = tree()
    expect(crossLayerDrop(src, [0], [9], 0)).toBeNull()
    expect(src.map((n) => n.id)).toEqual(['a', 'loop', 'b'])
  })

  it('toIndex 越界 clamp 到端点；不可变=原树零副作用', () => {
    const src = tree()
    const next = crossLayerDrop(src, [0], [], 999)
    expect(next!.map((n) => n.id)).toEqual(['loop', 'b', 'a'])
    expect(src.map((n) => n.id)).toEqual(['a', 'loop', 'b'])
    expect(next).not.toBe(src)
    expect(next![0]).not.toBe(src[1])
  })
})
