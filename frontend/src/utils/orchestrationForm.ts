/**
 * orchestration form kernel — TS mirror of the UX pre-gate.
 * Authority = common ApiOrchestrationContract (Java, core save gate); this
 * file mirrors the same vocabulary and plain-language verdicts so the form
 * builder can pinpoint a bad row before submit. Both ends consume the same
 * fixture shared/orchestration_form_cases.json (assertionForm.ts / R3
 * condition_expr precedent) — one vocabulary, no second verdict copy.
 */

/** Precondition field domain (mirror PRECONDITION_FIELDS): last-hop status or a pool variable. */
export const PRECONDITION_FIELDS = ['status', 'var'] as const

/** Comparison operators shared by status and var equality forms. */
export const COMPARE_OPS = ['eq', 'ne'] as const

/** Existence operators (var-only). */
export const EXISTENCE_OPS = ['exists', 'absent'] as const

/** Status-only membership operator (expect = non-empty integer array). */
export const IN_OP = 'in'

/** Pattern operator delegating its grammar to the expect matcher mirror. */
export const MATCH_OP = 'matches'

/** ops candidates per precondition field (dropdown single source). */
export const PRECONDITION_STATUS_OPS = ['eq', 'ne', 'in']
export const PRECONDITION_VAR_OPS = ['exists', 'absent', 'eq', 'ne', 'matches']

/** Loop modes (mirror LOOP_MODES): fixed count or repeat-until. */
export const LOOP_MODES = ['count', 'until'] as const

/** Bounds mirrored from ApiOrchestrationContract constants. */
export const LOOP_TIMES_MAX = 100
export const LOOP_MAX_TIMES_BOUND = 100
export const LOOP_DELAY_MS_MAX = 3_600_000

/** matchType enum (mirror ExpectMatchContract.MATCH_TYPES). */
const MATCH_TYPES = ['contains', 'exact', 'range', 'regex']

/** bounds enum (mirror ExpectMatchContract.BOUNDS). */
const BOUNDS = ['closed', 'left-open', 'open', 'right-open']

const PATTERN_MAX_LENGTH = 200
const NESTED_QUANTIFIER = /\([^()]*[*+][^()]*\)\s*[*+]|\([^()]*\{[0-9]+,?\d*\}[^()]*\)\s*[*+{]/
const STATUS_MIN = 100
const STATUS_MAX = 599

/** Pool variable names: dotted identifier path (mirror VAR_NAME of the Java contract). */
const VAR_NAME = /^[A-Za-z_]\w*(\.[A-Za-z_]\w*)*$/

type Row = Record<string, unknown>

/** Absent/null/empty-object = no gate; anything non-object (incl. arrays, scalars) = shape violation. */
function shapeOf(row: unknown, shapeMsg: string): Row | null | 'bad' {
  if (row === null || row === undefined) return null
  if (typeof row !== 'object' || Array.isArray(row)) return 'bad'
  const rec = row as Row
  return Object.keys(rec).length === 0 ? null : rec
}

// ---------- expect-matcher delegation (mirror ExpectMatchContract) ----------

function matchTypeViolation(op: unknown): string | null {
  const value = String(op ?? '')
  if (MATCH_TYPES.includes(value)) return null
  return `matchType 仅支持 ${[...MATCH_TYPES].sort().join(' / ')}，当前值「${value}」`
}

function rangeViolation(row: Row): string | null {
  const min = row.min
  const max = row.max
  if (min == null && max == null) {
    return '区间断言至少给定 min 或 max 一端'
  }
  for (const [key, value] of [['min', min], ['max', max]] as const) {
    if (value != null && (typeof value !== 'number' || Number.isNaN(value))) {
      return `区间断言的 ${key} 必须是数值，当前值「${String(value)}」`
    }
  }
  const bounds = row.bounds
  if (bounds != null && !BOUNDS.includes(String(bounds))) {
    return `bounds 仅支持 ${[...BOUNDS].sort().join(' / ')}，当前值「${String(bounds)}」`
  }
  return null
}

function regexPatternViolation(row: Row): string | null {
  const pattern = row.pattern
  if (pattern == null || String(pattern).trim() === '') {
    return '正则断言还差匹配规则：请在 expect 填写正则表达式（例 ^ok$）'
  }
  const value = String(pattern)
  if (value.length > PATTERN_MAX_LENGTH) {
    return `正则 pattern 超长：${value.length} 字符，上限 ${PATTERN_MAX_LENGTH}`
  }
  if (NESTED_QUANTIFIER.test(value)) {
    return '正则 pattern 含嵌套量词（如 (a+)+ 形态），已拒——防灾难性回溯拖垮执行器，请改写为非嵌套形态'
  }
  try {
    void new RegExp(value)
  } catch {
    return `正则 pattern 不可编译：${value}`
  }
  return null
}

/** Delegation point: matchType absent/blank = legacy contains/exact semantics, no extra check. */
function expectMatchViolation(row: Row): string | null {
  const matchType = row.matchType
  if (matchType == null || String(matchType).trim() === '') return null
  const enumViolation = matchTypeViolation(matchType)
  if (enumViolation) return enumViolation
  if (matchType === 'range') return rangeViolation(row)
  if (matchType === 'regex') return regexPatternViolation(row)
  return null
}

// ---------- precondition ----------

function statusPreconditionViolation(row: Row): string | null {
  const op = String(row.op ?? '')
  if (!COMPARE_OPS.includes(op as (typeof COMPARE_OPS)[number]) && op !== IN_OP) {
    return `状态码前置条件仅支持 eq / ne / in，当前值「${op}」`
  }
  const expect = row.expect
  if (op === IN_OP) {
    if (!Array.isArray(expect) || expect.length === 0) {
      return '状态码 in 前置条件的 expect 必须是非空整数数组，例 [200, 201]'
    }
    for (const item of expect) {
      const range = httpStatusRange(item)
      if (range) return range
    }
    return null
  }
  return httpStatusRange(expect)
}

function varPreconditionViolation(row: Row): string | null {
  const name = String(row.name ?? '')
  if (name.trim() === '' || !VAR_NAME.test(name)) {
    return `变量前置条件的 name 必须是点分标识符（例 token 或 order.state），当前值「${name}」`
  }
  const op = String(row.op ?? '')
  if (EXISTENCE_OPS.includes(op as (typeof EXISTENCE_OPS)[number])) return null
  if (COMPARE_OPS.includes(op as (typeof COMPARE_OPS)[number])) {
    if (row.expect == null) {
      return `变量 ${op} 前置条件必须给出 expect 比较值`
    }
    return null
  }
  if (op === MATCH_OP) {
    if (typeof row.expect !== 'string') {
      return '变量 matches 前置条件的 expect 必须是字符串模式值'
    }
    return expectMatchViolation(row)
  }
  return `变量前置条件仅支持 exists / absent / eq / ne / matches，当前值「${op}」`
}

function httpStatusRange(value: unknown): string | null {
  if (typeof value !== 'number' || !Number.isInteger(value)
    || value < STATUS_MIN || value > STATUS_MAX) {
    return '状态码前置条件 expect 必须是 100–599 的整数，例 200'
  }
  return null
}

/** Single precondition row verdict (null = legal / absent; plain text otherwise). */
export function validatePreconditionRow(row: unknown): string | null {
  const shape = shapeOf(row, '前置条件必须是一个表单对象（field/op/expect），当前不是对象形状')
  if (shape === null) return null
  if (shape === 'bad') return '前置条件必须是一个表单对象（field/op/expect），当前不是对象形状'
  const field = String(shape.field ?? '')
  if (!PRECONDITION_FIELDS.includes(field as (typeof PRECONDITION_FIELDS)[number])) {
    return `前置条件字段仅支持 ${[...PRECONDITION_FIELDS].sort().join(' / ')}`
      + `（上一跳状态码 / 变量池键名），当前值「${field}」`
  }
  return field === 'status' ? statusPreconditionViolation(shape) : varPreconditionViolation(shape)
}

// ---------- loop ----------

function delayViolation(row: Row): string | null {
  const delay = row.delayMs
  if (delay == null) return null
  if (typeof delay !== 'number' || !Number.isInteger(delay)
    || delay < 0 || delay > LOOP_DELAY_MS_MAX) {
    return `循环间隔 delayMs 必须是 0~${LOOP_DELAY_MS_MAX} 的整数毫秒`
  }
  return null
}

/** Single loop row verdict (null = legal / absent; until loops require the stop row). */
export function validateLoopRow(row: unknown): string | null {
  const shape = shapeOf(row, '循环配置必须是一个表单对象（mode/times/until），当前不是对象形状')
  if (shape === null) return null
  if (shape === 'bad') return '循环配置必须是一个表单对象（mode/times/until），当前不是对象形状'
  const mode = String(shape.mode ?? '')
  if (!LOOP_MODES.includes(mode as (typeof LOOP_MODES)[number])) {
    return `循环模式仅支持 ${[...LOOP_MODES].sort().join(' / ')}（固定次数 / 条件停止），当前值「${mode}」`
  }
  const delay = delayViolation(shape)
  if (delay) return delay
  if (mode === 'count') {
    const times = shape.times
    if (typeof times !== 'number' || !Number.isInteger(times)) {
      return `固定次数循环必须给出整数 times（1~${LOOP_TIMES_MAX}）`
    }
    if (times < 1 || times > LOOP_TIMES_MAX) {
      return `固定次数循环 times 必须在 1~${LOOP_TIMES_MAX} 之间，当前值「${String(times)}」`
    }
    return null
  }
  const maxTimes = shape.maxTimes
  if (typeof maxTimes !== 'number' || !Number.isInteger(maxTimes)
    || maxTimes < 1 || maxTimes > LOOP_MAX_TIMES_BOUND) {
    return `条件停止循环必须给出整数 maxTimes 保护上限（1~${LOOP_MAX_TIMES_BOUND}），防止永不满足时停不下来`
  }
  const until = shape.until
  const untilShape = shapeOf(until, '')
  if (untilShape === null) {
    return '条件停止循环必须给出 until 停止条件行（field/op/expect 表单行）'
  }
  if (untilShape === 'bad') {
    return '前置条件必须是一个表单对象（field/op/expect），当前不是对象形状'
  }
  return validatePreconditionRow(untilShape)
}
