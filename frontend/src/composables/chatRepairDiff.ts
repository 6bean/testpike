/**
 * Field-level diff for the dialog repair-draft card .
 *
 * The proposal inside a draft frame has no saved script version yet, so the
 * server-side version-line diff (GET /cases/{id}/scripts/versions/{v}/diff)
 * cannot compare against it — this module folds the two action-JSON documents
 * into the same change shape the version diff already uses (field / label /
 * before / after) so the card never invents a second presentation vocabulary
 * and never dumps raw JSON at the user.
 *
 * Comparison scope = the action JSON's TOP-LEVEL keys (steps, schema hints, …):
 * a nested step-by-step diff would need the step-key contract the script
 * version line already owns, and re-implementing it here would be a second
 * source of truth. Added/removed keys are still named key by key.
 *
 * Pure module: no Vue, no network — unit spectra and the card share one fold.
 */

/** One changed top-level field, shaped like CaseVersionChangeItem (api/case). */
export interface RepairFieldChange {
  field: string
  /** Present on both sides / added / removed — the card words it via locales. */
  state: 'changed' | 'added' | 'removed'
  before: unknown
  after: unknown
}

/**
 * Diff two action-JSON documents (wire strings). Unparsable JSON on either
 * side returns null — the caller then shows its honest "cannot compare" line
 * instead of pretending an empty diff means "nothing changes".
 */
export function diffActionJson(
  beforeJson: string | null | undefined,
  afterJson: string,
): RepairFieldChange[] | null {
  const before = parseObject(beforeJson)
  const after = parseObject(afterJson)
  if (after == null) return null
  if (beforeJson != null && beforeJson.trim() !== '' && before == null) return null
  const from = before ?? {}
  const keys = Array.from(new Set([...Object.keys(from), ...Object.keys(after)])).sort()
  const changes: RepairFieldChange[] = []
  for (const key of keys) {
    const hasBefore = Object.prototype.hasOwnProperty.call(from, key)
    const hasAfter = Object.prototype.hasOwnProperty.call(after, key)
    if (!hasBefore) {
      changes.push({ field: key, state: 'added', before: undefined, after: after[key] })
    } else if (!hasAfter) {
      changes.push({ field: key, state: 'removed', before: from[key], after: undefined })
    } else if (!sameValue(from[key], after[key])) {
      changes.push({ field: key, state: 'changed', before: from[key], after: after[key] })
    }
  }
  return changes
}

/**
 * Compact one-line rendering of one side's value for the card (the W-group
 * rule: name the object, keep it readable). Objects/arrays collapse to a
 * human count sentence — bracket shapes like `[2]` are internal notation and
 * were called out by the vision pass as bookkeeping code on screen — while
 * the full body belongs to the case detail page. The wording comes from the
 * caller's locale formatter so the module stays UI-free and unit-testable.
 */
export function compactValue(
  value: unknown,
  shape: (kind: 'array' | 'object', count: number) => string,
): string {
  if (value === undefined) return ''
  if (value === null) return 'null'
  if (typeof value === 'string') return value.length > 60 ? `${value.slice(0, 60)}…` : value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) return shape('array', value.length)
  if (typeof value === 'object') return shape('object', Object.keys(value as object).length)
  return String(value)
}

function parseObject(json: string | null | undefined): Record<string, unknown> | null {
  if (json == null || json.trim() === '') return null
  try {
    const raw = JSON.parse(json) as unknown
    return raw && typeof raw === 'object' && !Array.isArray(raw)
      ? raw as Record<string, unknown>
      : null
  } catch {
    return null
  }
}

/** Shallow equality on primitives + JSON identity for structured values. */
function sameValue(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (typeof a !== typeof b) return false
  if (a === null || b === null || typeof a !== 'object') return false
  return JSON.stringify(a) === JSON.stringify(b)
}
