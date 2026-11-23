/**
 * stage 3 — case-write proposal folding for the draft card (pure
 * logic, no Vue, no network).
 *
 * A CASE_WRITE draft body is the case-field document the confirmation chain
 * sends to CaseService (title / precondition / expectedResult / priority /
 * confidence / endType / phase / steps). The card shows WHICH case fields
 * the proposal writes (create) and WHAT CHANGES against the case as it
 * stands now (edit) — a human-readable fold, never a raw JSON dump.
 *
 * The fold reuses the RepairFieldChange shape from chatRepairDiff so one
 * diff vocabulary renders both draft kinds (red line: no second presentation
 * model). Unparsable input returns null everywhere — the card then shows its
 * honest "cannot compare" line instead of an empty diff implying "nothing
 * changes".
 */
import type { RepairFieldChange } from './chatRepairDiff'

/** Display order of the case-field vocabulary a proposal may write; keys
 *  outside the vocabulary sort after it so nothing silently disappears. */
export const PROPOSAL_FIELD_ORDER = ['title', 'precondition', 'expectedResult', 'priority', 'confidence', 'endType', 'phase', 'steps'] as const

/** Stable ordering: known fields in vocabulary order, unknown keys sorted last. */
export function proposalFieldOrder(fields: string[]): string[] {
  const rank = (f: string) => {
    const i = (PROPOSAL_FIELD_ORDER as readonly string[]).indexOf(f)
    return i < 0 ? PROPOSAL_FIELD_ORDER.length : i
  }
  return [...fields].sort((a, b) => (rank(a) - rank(b)) || a.localeCompare(b))
}

/** Count sentence input for a proposal's steps array; null when absent/unparsable. */
export function stepSummary(actionJson: string | null | undefined): { steps: number; asserts: number } | null {
  const body = parseObject(actionJson)
  if (body == null) return null
  const steps = body['steps']
  if (!Array.isArray(steps)) return null
  let asserts = 0
  for (const step of steps) {
    if (step && typeof step === 'object' && Number((step as Record<string, unknown>)['isAssert']) === 1) asserts += 1
  }
  return { steps: steps.length, asserts }
}

/** One readable line per scalar field the proposal actually writes (steps
 *  excluded — they fold through stepSummary/diffCaseFields). Null when the
 *  body cannot be parsed. */
export function caseWriteFieldLines(actionJson: string | null | undefined): Array<{ field: string; value: unknown }> | null {
  const body = parseObject(actionJson)
  if (body == null) return null
  const lines: Array<{ field: string; value: unknown }> = []
  for (const key of proposalFieldOrder(Object.keys(body))) {
    if (key === 'steps') continue
    const value = body[key]
    if (value === undefined || value === null) continue
    lines.push({ field: key, value })
  }
  return lines
}

/**
 * Field-level diff of a CASE_WRITE edit against the CURRENT case snapshot.
 * Only fields the proposal writes are examined (absent/null = writes
 * nothing). Steps compare by content identity — a stepNo renumber alone is
 * noise, never a shown change. Unparsable proposal or missing current case
 * returns null (honest "cannot compare").
 */
export function diffCaseFields(
  currentCase: Record<string, unknown> | null | undefined,
  proposalJson: string,
): RepairFieldChange[] | null {
  const after = parseObject(proposalJson)
  if (after == null || currentCase == null) return null
  const changes: RepairFieldChange[] = []
  for (const key of proposalFieldOrder(Object.keys(after))) {
    const proposed = after[key]
    if (proposed === undefined || proposed === null) continue
    const current = currentCase[key]
    if (key === 'steps') {
      if (!Array.isArray(proposed)) continue
      const now = Array.isArray(current) ? current : []
      if (JSON.stringify(normalizeSteps(now)) !== JSON.stringify(normalizeSteps(proposed))) {
        changes.push({ field: key, state: 'changed', before: now, after: proposed })
      }
      continue
    }
    if (sameValue(current, proposed)) continue
    changes.push({
      field: key,
      state: current === undefined || current === null ? 'added' : 'changed',
      before: current,
      after: proposed,
    })
  }
  return changes
}

/**
 * Readable line per proposed step (number + description + assert flag), for
 * the card's step zone. Returns null when steps are absent or no entry
 * carries a description — the caller then shows the count sentence instead
 * of empty lines. Lives here (pure module) so card and spectra share one
 * fold (no second copy of the step reading logic).
 */
export function readableStepLines(
  actionJson: string | null | undefined,
): Array<{ stepNo: number; description: string; isAssert: boolean }> | null {
  const body = parseObject(actionJson)
  if (body == null) return null
  const steps = body['steps']
  if (!Array.isArray(steps) || steps.length === 0) return null
  const lines: Array<{ stepNo: number; description: string; isAssert: boolean }> = []
  steps.forEach((step, i) => {
    if (!step || typeof step !== 'object') return
    const raw = step as Record<string, unknown>
    const description = typeof raw['description'] === 'string' ? (raw['description'] as string).trim() : ''
    if (!description) return
    lines.push({
      stepNo: typeof raw['stepNo'] === 'number' ? (raw['stepNo'] as number) : i + 1,
      description,
      isAssert: Number(raw['isAssert']) === 1,
    })
  })
  return lines.length > 0 ? lines : null
}

/** Steps identity = (trimmed description, isAssert flag) per entry. */
function normalizeSteps(steps: unknown[]): unknown[] {
  return steps.map((s) => {
    if (!s || typeof s !== 'object') return s
    const raw = s as Record<string, unknown>
    return {
      description: typeof raw['description'] === 'string' ? (raw['description'] as string).trim() : raw['description'],
      isAssert: Number(raw['isAssert']) === 1 ? 1 : 0,
    }
  })
}

function parseObject(json: string | null | undefined): Record<string, unknown> | null {
  if (json == null || json.trim() === '') return null
  try {
    const raw = JSON.parse(json) as unknown
    return raw && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function sameValue(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a === null || a === undefined || b === null || b === undefined) return false
  if (typeof a !== typeof b) return false
  if (typeof a !== 'object') return false
  return JSON.stringify(a) === JSON.stringify(b)
}
