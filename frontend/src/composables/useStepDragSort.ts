
import { computed, ref } from 'vue'


export type StepBufferItem = { description: string; isAssert: number }


export const UNDO_STACK_LIMIT = 50


export function moveInPlace<T>(list: T[], from: number, to: number): void {
  if (from < 0 || to < 0 || from >= list.length || to >= list.length || from === to) return
  const [moved] = list.splice(from, 1)
  list.splice(to, 0, moved)
}


export type DragTreeNode = { children?: DragTreeNode[] }

function cloneTree<T extends DragTreeNode>(nodes: T[]): T[] {
  return nodes.map((n) => ({ ...n, ...(n.children ? { children: cloneTree(n.children) } : {}) })) as T[]
}


function resolveList<T extends DragTreeNode>(tree: T[], path: number[]): T[] | undefined {
  let list: T[] | undefined = tree
  for (const idx of path) {
    if (!list || idx < 0 || idx >= list.length) return undefined
    const node = list[idx] as DragTreeNode
    list = node.children as T[] | undefined
  }
  return list
}

function pathIsPrefix(prefix: number[], path: number[]): boolean {
  return prefix.length <= path.length && prefix.every((v, i) => path[i] === v)
}


export function crossLayerDrop<T extends DragTreeNode>(
  tree: T[],
  fromPath: number[],
  toPath: number[],
  toIndex: number,
): T[] | null {
  if (!fromPath.length) return null
  if (pathIsPrefix(fromPath, toPath)) return null
  const srcParent = resolveList(tree, fromPath.slice(0, -1))
  const srcIdx = fromPath[fromPath.length - 1]
  if (!srcParent || srcIdx < 0 || srcIdx >= srcParent.length) return null

  const next = cloneTree(tree)
  const nextSrcParent = resolveList(next, fromPath.slice(0, -1))!
  const [moved] = nextSrcParent.splice(srcIdx, 1)

  const adjusted = [...toPath]
  const crossingLevel = fromPath.length - 1
  if (adjusted.length > crossingLevel && pathIsPrefix(fromPath.slice(0, -1), adjusted)) {
    if (adjusted[crossingLevel] > srcIdx) adjusted[crossingLevel] -= 1
  }
  let target: T[] | undefined = resolveList(next, adjusted)
  if (!target) {
    const parentList = resolveList(next, adjusted.slice(0, -1))
    const nodeIdx = adjusted[adjusted.length - 1]
    if (!parentList || nodeIdx < 0 || nodeIdx >= parentList.length) return null
    const created: T[] = []
    parentList[nodeIdx].children = created
    target = created
  }
  const insertAt = Math.max(0, Math.min(toIndex, target.length))
  target.splice(insertAt, 0, moved)
  return next
}


export function useStepDragSort() {
  const ordered = ref<StepBufferItem[]>([])
  const baseline = ref<StepBufferItem[]>([])
  
  const undoStack = ref<StepBufferItem[][]>([])
  
  const dragEpoch = ref(0)
  
  const keyboardGrab = ref(-1)
  const dragIndex = ref(-1)

  function snapshot(list: StepBufferItem[]): StepBufferItem[] {
    return list.map((s) => ({ ...s }))
  }

  function pushUndo(snapshotList: StepBufferItem[]): void {
    undoStack.value = [...undoStack.value, snapshotList]
    if (undoStack.value.length > UNDO_STACK_LIMIT) {
      undoStack.value = undoStack.value.slice(-UNDO_STACK_LIMIT)
    }
  }

  
  function reset(serverSteps: StepBufferItem[]): void {
    baseline.value = snapshot(serverSteps)
    ordered.value = snapshot(serverSteps)
    undoStack.value = []
    dragEpoch.value += 1
    dragIndex.value = -1
    keyboardGrab.value = -1
  }

  const dirty = computed(
    () =>
      ordered.value.length !== baseline.value.length ||
      ordered.value.some((s, i) => s.description !== baseline.value[i]?.description),
  )

  
  const movedSet = computed(() => {
    const set = new Set<number>()
    if (!dirty.value) return set
    ordered.value.forEach((s, i) => {
      if (s.description !== baseline.value[i]?.description) set.add(i)
    })
    return set
  })

  const canUndo = computed(() => undoStack.value.length > 0)

  
  const diffRows = computed(() => {
    const max = Math.max(baseline.value.length, ordered.value.length)
    const rows: { seq: number; before?: string; after?: string }[] = []
    for (let i = 0; i < max; i++) {
      rows.push({ seq: i + 1, before: baseline.value[i]?.description, after: ordered.value[i]?.description })
    }
    return rows
  })

  function beginDrag(idx: number): void {
    dragIndex.value = idx
    pushUndo(snapshot(ordered.value))
  }

  function dragHoverTo(idx: number): void {
    if (dragIndex.value < 0 || dragIndex.value === idx) return
    const next = snapshot(ordered.value)
    moveInPlace(next, dragIndex.value, idx)
    ordered.value = next
    dragIndex.value = idx
  }

  function endDrag(): void {
    dragIndex.value = -1
  }

  function toggleKeyboardGrab(idx: number): void {
    keyboardGrab.value = keyboardGrab.value === idx ? -1 : idx
  }

  
  function keyboardMove(delta: number): void {
    if (keyboardGrab.value < 0) return
    const to = keyboardGrab.value + delta
    if (to < 0 || to >= ordered.value.length) return
    if (to === keyboardGrab.value) return
    pushUndo(snapshot(ordered.value))
    const next = snapshot(ordered.value)
    moveInPlace(next, keyboardGrab.value, to)
    ordered.value = next
    keyboardGrab.value = to
  }

  function cancelKeyboardGrab(): void {
    keyboardGrab.value = -1
  }

  function undo(): void {
    const prev = undoStack.value[undoStack.value.length - 1]
    if (!prev) return
    undoStack.value = undoStack.value.slice(0, -1)
    ordered.value = prev
    dragEpoch.value += 1
  }

  
  function toOrderedSteps(): { stepNo: number; description: string; isAssert: number }[] {
    return ordered.value.map((s, i) => ({ stepNo: i + 1, description: s.description, isAssert: s.isAssert }))
  }

  
  function discard(): void {
    reset(baseline.value)
  }

  return {
    ordered,
    baseline,
    undoStack,
    dragEpoch,
    dragIndex,
    keyboardGrab,
    dirty,
    movedSet,
    canUndo,
    diffRows,
    reset,
    beginDrag,
    dragHoverTo,
    endDrag,
    toggleKeyboardGrab,
    keyboardMove,
    cancelKeyboardGrab,
    undo,
    toOrderedSteps,
    discard,
  }
}


export type StepDragSortCore = ReturnType<typeof useStepDragSort>
