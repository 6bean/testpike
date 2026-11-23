/**
 * b orchestration summary kernel — turns the jsonb texts a
 * request row already carries (preconditionJson / loopJson) into a
 * plain-language badge the flow view renders without any extra request.
 *
 * Shape rules:
 * - absent / empty / malformed text returns null: a row simply shows no
 * badge, the rail never crashes on a bad jsonb (the editor is the place
 * that pinpoints malformed orchestration rows, single point of blame);
 * - the return value is an i18n key + params, so the view hard-codes zero
 * display words and the vocabulary stays one family with the run view;
 * - the condition phrase is built from the contract vocabulary mirrored
 * in utils/orchestrationForm (field / op / expect) and is symbol-led,
 * readable under both locales without a second verdict copy.
 */

/** One renderable badge: locale key + interpolation params. */
export interface OrchestrationBadge {
  key: string
  params: Record<string, string>
}

type Row = Record<string, unknown>

/** Defensive parse: anything that is not a non-empty object yields null. */
function parse(row: string | null | undefined): Row | null {
  if (!row) return null
  try {
    const value: unknown = JSON.parse(row)
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
    const rec = value as Row
    return Object.keys(rec).length === 0 ? null : rec
  } catch {
    return null
  }
}

function text(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}

/**
 * Readable condition phrase from one contract row (precondition or loop
 * until): `status = 200`, `status in [200, 201]`, `orderId exists`,
 * `orderId matches ^ok$`. Unknown ops fall through to `field op expect`
 * instead of hiding the oddity — an honest badge beats a missing one.
 */
function conditionOf(row: Row): string {
  const field = text(row.field)
  const op = text(row.op)
  const target = field === 'var' ? text(row.name) || 'var' : field
  if (op === 'exists') return `${target} exists`
  if (op === 'absent') return `${target} absent`
  const expect = row.expect
  if (op === 'eq') return `${target} = ${text(expect)}`
  if (op === 'ne') return `${target} != ${text(expect)}`
  if (op === 'in') return `${target} in [${Array.isArray(expect) ? expect.map(text).join(', ') : text(expect)}]`
  if (op === 'matches') return `${target} matches ${text(row.pattern)}`
  return `${target} ${op} ${text(expect)}`.trim()
}

/** Precondition badge (apitest.flow.preTip), null = row carries no gate. */
export function preconditionSummary(jsonText: string | null | undefined): OrchestrationBadge | null {
  const row = parse(jsonText)
  if (!row) return null
  return { key: 'apitest.flow.preTip', params: { cond: conditionOf(row) } }
}

/**
 * Loop badge: count mode lands on apitest.flow.loopCount (times plus an
 * optional delay the view renders as its own chip), until mode on
 * apitest.flow.loopUntil (stop condition + safety ceiling).
 */
export function loopSummary(jsonText: string | null | undefined): OrchestrationBadge | null {
  const row = parse(jsonText)
  if (!row) return null
  const delayMs = Number(row.delayMs)
  const delay = Number.isFinite(delayMs) && delayMs > 0 ? `${delayMs}ms` : ''
  if (row.mode === 'until') {
    const until = row.until
    const cond = until !== null && typeof until === 'object' && !Array.isArray(until)
      ? conditionOf(until as Row)
      : '?'
    return {
      key: 'apitest.flow.loopUntil',
      params: { cond, max: text(row.maxTimes), delay },
    }
  }
  return {
    // count mode folds the delay into one chip sentence (loopCountDelay);
    // until mode keeps the delay as a separate chip the view renders from
    // params.delay — the kernel passes the real value in both shapes, the
    // key decides where it shows up.
    key: delay ? 'apitest.flow.loopCountDelay' : 'apitest.flow.loopCount',
    params: { times: text(row.times), delay },
  }
}
