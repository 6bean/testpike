
import { actionSpecOf, containerBranches } from '@/config/actionVocab'
import { i18n } from '@/utils/i18n'
import type { CaseVersionChangeItem } from '@/api/case'
import type { ScopeStep } from '@/utils/stepScope'
import { checkJsonText } from '@/utils/jsonForm'


function tv(key: string, params?: Record<string, unknown>): string {
  return String(i18n.global.t(key, params ?? {}))
}


export interface SourceIssue {
  line?: number
  column?: number
  reason: string
}

export interface SourceParseOk {
  ok: true
  steps: ScopeStep[]
}
export interface SourceParseFail {
  ok: false
  errors: SourceIssue[]
}

export type SourceParseResult = SourceParseOk | SourceParseFail


const FIELD_LABELS: ReadonlyArray<readonly [string, string]> = [
  ['version', 'vocab.src.version'],
  ['caseId', 'vocab.src.caseId'],
  ['title', 'vocab.src.title'],
  ['endType', 'vocab.src.endType'],
  ['setup', 'vocab.src.setup'],
  ['steps', 'vocab.src.steps'],
  ['env', 'vocab.src.env'],
  ['metadata', 'vocab.src.metadata'],
]


function locateSyntax(text: string, message: string): { line: number; column: number } {
  const lm = /line (\d+) column (\d+)/.exec(message)
  if (lm) return { line: Number(lm[1]), column: Number(lm[2]) }
  const pm = /position (\d+)/.exec(message)
  if (pm) {
    const pos = Math.min(Number(pm[1]), text.length)
    let line = 1
    let col = 0
    for (let i = 0; i < pos; i++) {
      if (text.charCodeAt(i) === 10) { line++; col = 0 } else { col++ }
    }
    return { line, column: col + 1 }
  }
  return { line: 1, column: 1 }
}


function stepPath(parent: string, key: string, index: number): string {
  return parent ? `${parent}[${index}]` : `${key}[${index}]`
}


function checkStepArray(arr: unknown[], parent: string, errors: SourceIssue[]): void {
  arr.forEach((step, i) => {
    const path = stepPath(parent, parent ? '' : 'steps', i)
    if (typeof step !== 'object' || step === null || Array.isArray(step)) {
      errors.push({ reason: tv('vocab.err.notObject', { path }) })
      return
    }
    const action = (step as Record<string, unknown>).action
    if (typeof action !== 'string' || !action) {
      errors.push({ reason: tv('vocab.err.noAction', { path }) })
      return
    }
    if (!actionSpecOf(action)) {
      errors.push({ reason: tv('vocab.err.unknownAction', { path, action }) })
      return
    }
    for (const branch of containerBranches(action)) {
      const children = (step as Record<string, unknown>)[branch]
      if (children === undefined) continue
      if (!Array.isArray(children)) {
        errors.push({ reason: tv('vocab.err.branchNotArray', { cell: `${path}.${branch}` }) })
        continue
      }
      checkStepArray(children as unknown[], `${path}.${branch}`, errors)
    }
  })
}


export function parseScriptSourceForModeSwitch(text: string): SourceParseResult {
  const syntax = checkJsonText(text)
  if (!syntax.ok) {
    let line = 1
    let column = 1
    try {
      JSON.parse(text.trim())
    } catch (e) {
      const loc = locateSyntax(text, (e as Error).message || '')
      line = loc.line
      column = loc.column
    }
    return { ok: false, errors: [{ line, column, reason: tv('vocab.err.jsonSyntax') }] }
  }
  if (syntax.value === null) {
    return { ok: false, errors: [{ line: 1, column: 1, reason: tv('vocab.err.emptyContent') }] }
  }
  let doc: unknown
  try {
    doc = JSON.parse(syntax.value)
  } catch {
    return { ok: false, errors: [{ line: 1, column: 1, reason: tv('vocab.err.jsonSyntaxPlain') }] }
  }
  if (typeof doc !== 'object' || doc === null || Array.isArray(doc)) {
    return { ok: false, errors: [{ reason: tv('vocab.err.topNotObject') }] }
  }
  const steps = (doc as Record<string, unknown>).steps
  if (!Array.isArray(steps)) {
    return { ok: false, errors: [{ reason: tv('vocab.err.noSteps') }] }
  }
  const errors: SourceIssue[] = []
  checkStepArray(steps, '', errors)
  if (errors.length) return { ok: false, errors }
  return { ok: true, steps: JSON.parse(JSON.stringify(steps)) as ScopeStep[] }
}


function normalized(v: unknown): unknown {
  return v === undefined || v === null ? null : v
}

function stableKey(v: unknown): string {
  if (v === undefined || v === null) return 'null'
  if (Array.isArray(v)) return `[${v.map(stableKey).join(',')}]`
  if (typeof v === 'object') {
    const entries = Object.entries(v as Record<string, unknown>)
      .filter(([, val]) => val !== undefined)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    return `{${entries.map(([k, val]) => `${JSON.stringify(k)}:${stableKey(val)}`).join(',')}}`
  }
  return JSON.stringify(v)
}


export function diffScriptFields(from: Record<string, unknown>, to: Record<string, unknown>): CaseVersionChangeItem[] {
  const changes: CaseVersionChangeItem[] = []
  const reported = new Set<string>()
  const labelOf = new Map(FIELD_LABELS)
  for (const [field, label] of FIELD_LABELS) {
    if (stableKey(normalized(from[field])) !== stableKey(normalized(to[field]))) {
      changes.push({ field, label: tv(label), before: normalized(from[field]), after: normalized(to[field]) })
      reported.add(field)
    }
  }
  Object.keys({ ...from, ...to })
    .filter((k) => !reported.has(k))
    .sort()
    .forEach((field) => {
      if (stableKey(normalized(from[field])) !== stableKey(normalized(to[field]))) {
        const key = labelOf.get(field)
        changes.push({ field, label: key ? tv(key) : field, before: normalized(from[field]), after: normalized(to[field]) })
      }
    })
  return changes
}


export interface ScriptWalkCounts {
  nodes: number
  loops: number
  maxDepth: number
}

export function countScriptLimits(steps: ScopeStep[], depth = 1): ScriptWalkCounts {
  const counts: ScriptWalkCounts = { nodes: 0, loops: 0, maxDepth: 0 }
  walkStepArray(steps, depth, counts)
  return counts
}

function walkStepArray(arr: unknown[], depth: number, counts: ScriptWalkCounts): void {
  for (const step of arr) {
    if (typeof step !== 'object' || step === null || Array.isArray(step)) continue
    counts.nodes++
    counts.maxDepth = Math.max(counts.maxDepth, depth)
    if ((step as Record<string, unknown>).action === 'loop') counts.loops++
    const rec = step as Record<string, unknown>
    if (Array.isArray(rec.steps)) walkStepArray(rec.steps as unknown[], depth + 1, counts)
    if (Array.isArray(rec.elseSteps)) walkStepArray(rec.elseSteps as unknown[], depth + 1, counts)
  }
}


export function prettifyScriptSource(text: string): { ok: true; text: string } | { ok: false } {
  const check = checkJsonText(text)
  if (!check.ok || check.value === null) return { ok: false }
  try {
    return { ok: true, text: JSON.stringify(JSON.parse(check.value), null, 2) }
  } catch {
    return { ok: false }
  }
}
