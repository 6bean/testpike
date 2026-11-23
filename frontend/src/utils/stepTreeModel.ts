
import { containerBranches } from '@/config/actionVocab'
import { collectVisibleVars, hasStaleVarRef, type ScopePathEntry, type ScopeStep } from '@/utils/stepScope'


export interface StepTreeNode {
  id: string
  depth: number
  
  branch: string
  
  fieldIndex: number
  step: ScopeStep
  children?: StepTreeNode[]
  
  elseStart: number | null
}


export interface StaleRefHit {
  nodeId: string
  
  field: string
  
  ref: string
}

function toNode(step: ScopeStep, path: number[], branch: string, fieldIndex: number): StepTreeNode {
  const id = `n.${path.join('.')}`
  const branches = containerBranches(step.action)
  const node: StepTreeNode = { id, depth: path.length - 1, branch, fieldIndex, step, elseStart: null }
  if (!branches.length) return node
  const kids: StepTreeNode[] = []
  let elseStart: number | null = null
  for (const b of branches) {
    const arr = step[b]
    const list = Array.isArray(arr) ? (arr as ScopeStep[]) : []
    if (b !== 'steps') elseStart = kids.length
    if (b !== 'steps' && !(b in step)) { elseStart = null }
    list.forEach((child, i) => kids.push(toNode(child, [...path, kids.length], b, i)))
  }
  node.children = kids
  node.elseStart = elseStart
  return node
}


export function buildStepNodes(steps: ScopeStep[]): StepTreeNode[] {
  return steps.map((s, i) => toNode(s, [i], 'steps', i))
}


export function nodePathOf(id: string): number[] {
  if (!id.startsWith('n.')) return []
  const segs = id.slice(2).split('.')
  if (!segs.length || segs.some((s) => !/^\d+$/.test(s))) return []
  return segs.map(Number)
}


export function newStepOf(action: string): ScopeStep {
  const step: ScopeStep = { action }
  for (const b of containerBranches(action)) {
    if (b === 'steps') step.steps = []
  }
  return step
}


export function flattenStepNodes(nodes: StepTreeNode[]): ScopeStep[] {
  return nodes.map((n) => {
    const { action, ...rest } = n.step
    const out: ScopeStep = { action }
    const branches = containerBranches(action)
    for (const [k, v] of Object.entries(rest)) {
      if (!branches.includes(k)) out[k] = v
    }
    if (!branches.length || !n.children) return out
    const thenKids = n.children.filter((c) => c.branch !== 'elseSteps')
    const elseKids = n.children.filter((c) => c.branch === 'elseSteps')
    for (const b of branches) {
      if (b === 'steps') out.steps = flattenStepNodes(thenKids)
      else if (n.elseStart != null) out[b] = flattenStepNodes(elseKids)
    }
    return out
  })
}


function scanStepStrings(
  step: ScopeStep,
  node: StepTreeNode,
  visibleVars: string[],
  hits: StaleRefHit[],
): void {
  const branchKeys = containerBranches(step.action)
  for (const [key, val] of Object.entries(step)) {
    if (key === 'action' || branchKeys.includes(key)) continue
    if (typeof val === 'string') {
      const stale = hasStaleVarRef(val, visibleVars)
      if (stale) hits.push({ nodeId: node.id, field: key, ref: stale })
    } else if (val && typeof val === 'object' && !Array.isArray(val)) {
      for (const [subKey, subVal] of Object.entries(val as Record<string, unknown>)) {
        if (typeof subVal !== 'string') continue
        const stale = hasStaleVarRef(subVal, visibleVars)
        if (stale) hits.push({ nodeId: node.id, field: `${key}.${subKey}`, ref: stale })
      }
    }
  }
}


export function scanStaleRefs(steps: ScopeStep[]): StaleRefHit[] {
  const hits: StaleRefHit[] = []
  const walk = (nodes: StepTreeNode[], path: ScopePathEntry[]) => {
    nodes.forEach((node) => {
      const here: ScopePathEntry[] = [...path, { branch: node.branch, index: node.fieldIndex }]
      const visible = collectVisibleVars(steps, path)
      scanStepStrings(node.step, node, visible, hits)
      if (node.children) walk(node.children, here)
    })
  }
  walk(buildStepNodes(steps), [])
  return hits
}
