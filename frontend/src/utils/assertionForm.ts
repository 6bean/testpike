


const JSON_PATH_SUBSET = /^\$(\.[A-Za-z_]\w*|\[[0-9]+\])*$/


export const SAVE_AS_NAME = /^(?:global\.)?[A-Za-z_]\w*$/


const MATCH_TYPES = ['contains', 'exact', 'range', 'regex']


const PATTERN_MAX_LENGTH = 200


const NESTED_QUANTIFIER = /\([^()]*[*+][^()]*\)\s*[*+]|\([^()]*\{[0-9]+,?\d*\}[^()]*\)\s*[*+{]/


export const STATUS_MIN = 100
export const STATUS_MAX = 599

/** Assertion type vocabulary (HTTP face): five plus the schema type for JSON Structure checks (script assertions stay refused). */
export const ASSERTION_TYPES = ['body', 'duration', 'extract', 'header', 'schema', 'status'] as const

/** WebSocket-face assertion vocabulary (mirrors common ApiWsContract.WS_ASSERTION_TYPES — no second copy). */
export const WS_ASSERTION_TYPES = ['ws.frequency', 'ws.order', 'ws.receive'] as const

/** Transport vocabulary mirrored from common ApiWsContract (dropdown single source). */
export const TRANSPORT_PROTOCOLS = ['http', 'ws'] as const

/** Schema-source dialects mirrored from core SCHEMA_VIAS (inline = pasted schema, contract = spec responseSchemas). */
const SCHEMA_VIAS = ['inline', 'contract']


export const HTTP_METHODS = ['DELETE', 'GET', 'HEAD', 'OPTIONS', 'PATCH', 'POST', 'PUT'] as const

/** Variable-extraction lanes (mirrors core ApiAssertionFormContract.EXTRACT_SOURCES
 *  — one owner of the three-value vocabulary; body keeps the JsonPath lane,
 *  header/cookie read the response by name). Dropdown single source: the editor
 *  iterates this list, it never re-declares the values. */
export const EXTRACT_SOURCE_OPTIONS = [
  { value: 'body', label: '响应体（JsonPath）' },
  { value: 'header', label: '响应头（按名称）' },
  { value: 'cookie', label: 'Cookie（按名称）' },
] as const

/** Lane values in the gate's display order. */
const EXTRACT_SOURCE_VALUES: string[] = EXTRACT_SOURCE_OPTIONS.map((option) => option.value)

/** Optional ws window budget check (mirrors core wsWindowViolation; absent = executor default 3000). */
function wsWindowViolation(row: Record<string, unknown>): string | null {
  const window = row.withinMs
  if (window === null || window === undefined) {
    return null
  }
  const value = typeof window === 'string' && /^\d+$/.test(window.trim())
    ? Number(window.trim())
    : (typeof window === 'number' ? window : Number.NaN)
  if (!Number.isInteger(value) || value < 1) {
    return `ws 断言窗口 withinMs 必须是 ≥1 的整数毫秒（缺省=3000），当前值「${String(window)}」`
  }
  return null
}

function wsReceiveViolation(row: Record<string, unknown>): string | null {
  const expect = row.expect
  if (expect === null || expect === undefined) {
    return '消息接收断言还差期望内容：请在 expect 填写窗口内要命中的消息文本（例 expect=pong）'
  }
  const op = String(row.op ?? '')
  if (op === '') {
    return null
  }
  const enumViolation = matchTypeViolation(op)
  if (enumViolation) {
    return `ws.receive 断言${enumViolation}`
  }
  if (op === 'range') {
    return expectPairViolation(row, '区间')
  }
  if (op === 'regex') {
    if (typeof expect !== 'string' || expect.trim() === '') {
      return '正则断言还差匹配规则：请在 expect 填写正则表达式（例 ^ok$）'
    }
    return regexViolation({ ...row, pattern: expect })
  }
  return null
}

function wsOrderViolation(row: Record<string, unknown>): string | null {
  const expect = row.expect
  if (!Array.isArray(expect) || expect.length === 0) {
    return 'ws.order 的 expect 必须是非空字符串数组（期望到达序列），例 ["READY","DONE"]'
  }
  for (const item of expect) {
    if (typeof item !== 'string' && typeof item !== 'number') {
      return `ws.order 的 expect 每一项必须是字符串或数值（消息帧为文本），当前值「${JSON.stringify(item)}」`
    }
  }
  return null
}

function wsFrequencyViolation(row: Record<string, unknown>): string | null {
  const expect = row.expect
  const value = typeof expect === 'string' && /^\d+$/.test(expect.trim())
    ? Number(expect.trim())
    : (typeof expect === 'number' ? expect : Number.NaN)
  if (!Number.isInteger(value) || value < 1) {
    return `ws.frequency 的 expect 必须是正整数（窗口内最少消息条数），当前值「${String(expect)}」`
  }
  return null
}

/** WebSocket-face row check — mirrors core wsRowViolation (protocol-crossed grammar). */
function wsRowViolation(row: Record<string, unknown>, type: string): string | null {
  if (!(WS_ASSERTION_TYPES as readonly string[]).includes(type)) {
    if ((ASSERTION_TYPES as readonly string[]).includes(type)) {
      return `断言类型「${type}」不适用于 WebSocket 传输面（当前仅 ${WS_ASSERTION_TYPES.join(' / ')}），`
        + 'HTTP 型断言请切回 http 协议'
    }
    return `断言类型仅支持 ${[...WS_ASSERTION_TYPES].sort().join(' / ')}（禁自由表达式与脚本断言），当前值「${type}」`
  }
  const windowViolation = wsWindowViolation(row)
  if (windowViolation) {
    return windowViolation
  }
  switch (type) {
    case 'ws.receive':
      return wsReceiveViolation(row)
    case 'ws.order':
      return wsOrderViolation(row)
    default:
      return wsFrequencyViolation(row)
  }
}

export interface AssertionTypeMeta {
  
  label: string
  
  ops: string[]
  
  example: string
  
  expectHint: string
}


export const ASSERTION_TYPE_META: Record<string, AssertionTypeMeta> = {
  status: {
    label: '状态码',
    ops: [],
    example: '例：等于 200',
    expectHint: '100–599 整数',
  },
  duration: {
    label: '响应耗时(ms)',
    ops: ['range'],
    example: '例：range [0,3000]',
    expectHint: '[min,max] 数值对',
  },
  body: {
    label: 'JSONPath 断言',
    ops: ['exact', 'contains', 'regex', 'range'],
    example: '例：$.data.id 等于 10086',
    expectHint: '期望值（regex 填正则 / range 填 [min,max]）',
  },
  header: {
    label: '响应头断言',
    ops: ['exact', 'contains', 'regex'],
    example: '例：Content-Type 包含 json',
    expectHint: '期望值（regex 填正则）',
  },
  extract: {
    label: '变量提取(saveAs)',
    ops: [],
    example: '例：source=body 按 $.data.token 提取；source=header / cookie 按名称读取（如 Set-Cookie），存为 TOKEN 后用 {{VAR:TOKEN}} 引用',
    expectHint: '无需期望值（先选提取来源 source）',
  },
  schema: {
    label: 'JSON 结构校验',
    ops: [],
    example: '例：来源=引用已保存的响应结构（可指定响应码），或粘贴 JSON Schema 文本',
    expectHint: '选择来源：contract / inline',
  },
  'ws.receive': {
    label: '窗口内收到消息',
    ops: ['exact', 'contains', 'regex', 'range'],
    example: '例：3 秒内收到含 pong 的消息（op 缺省=contains）',
    expectHint: '期望消息内容（regex 填正则 / range 填 [min,max]）',
  },
  'ws.order': {
    label: '消息到达顺序',
    ops: [],
    example: '例：expect=["READY","DONE"] 按序到达（前缀相等即过）',
    expectHint: '非空字符串数组（期望到达序列）',
  },
  'ws.frequency': {
    label: '消息频率（条数）',
    ops: [],
    example: '例：2 秒内至少收到 5 条（withinMs 可选，缺省 3000）',
    expectHint: '正整数（窗口内最少条数）',
  },
}

function matchTypeOptions(): string {
  return [...MATCH_TYPES].sort().join(' / ')
}

function matchTypeViolation(op: string): string | null {
  if (MATCH_TYPES.includes(op)) {
    return null
  }
  return `matchType 仅支持 ${matchTypeOptions()}，当前值「${op}」`
}

function expectPairViolation(row: Record<string, unknown>, field: string): string | null {
  const expect = row.expect
  if (!Array.isArray(expect) || expect.length === 0 || expect.length > 2) {
    return `${field}断言 expect 必须是 [min,max] 数值对（例 [0,3000]）`
  }
  for (const bound of expect) {
    if (typeof bound !== 'number' || Number.isNaN(bound)) {
      return `${field}断言的区间端点必须是数值，当前值「${String(bound)}」`
    }
  }
  return null
}

function regexViolation(row: Record<string, unknown>): string | null {
  const pattern = row.pattern ?? row.expect
  if (typeof pattern !== 'string' || pattern.trim() === '') {
    return '正则断言还差匹配规则：请在 expect 填写正则表达式（例 ^ok$）'
  }
  if (pattern.length > PATTERN_MAX_LENGTH) {
    return `正则 pattern 超长：${pattern.length} 字符，上限 ${PATTERN_MAX_LENGTH}`
  }
  if (NESTED_QUANTIFIER.test(pattern)) {
    return '正则 pattern 含嵌套量词（如 (a+)+ 形态），已拒——防灾难性回溯拖垮执行器，请改写为非嵌套形态'
  }
  try {
    void new RegExp(pattern)
  } catch {
    return `正则 pattern 不可编译：${pattern}`
  }
  return null
}

function jsonPathViolation(path: unknown): string | null {
  if (typeof path !== 'string' || path.trim() === '') {
    return 'expect.body 每一项还差字段路径：请填写 path（支持 $.a.b[0].c 写法）'
  }
  if (JSON_PATH_SUBSET.test(path.trim())) {
    return null
  }
  return 'JsonPath 仅支持受限子集（例 $.data.items[0].status）：禁 filter（[?(...)]）、'
    + `script、通配（* / ..）、切片（[1:2]）表达式，当前值「${path}」`
}

function matchRowViolation(row: Record<string, unknown>): string | null {
  const op = String(row.op ?? '')
  const enumViolation = matchTypeViolation(op)
  if (enumViolation) {
    return enumViolation
  }
  if (op === 'range') {
    return expectPairViolation(row, '区间')
  }
  if (op === 'regex') {
    return regexViolation(row)
  }
  if (row.expect === null || row.expect === undefined) {
    return '断言还差期望值：请在 expect 填写预期返回的内容'
  }
  return null
}

/** Schema row shape check — mirrors core schemaViolation (dialect + inline parseability). */
function schemaViolation(row: Record<string, unknown>): string | null {
  const via = String(row.via ?? '').trim()
  if (via === '') {
    return '结构校验断言必须选择来源：inline（粘贴 JSON Schema）或 contract（引用接口已保存的响应结构样本 responseSchemas）'
  }
  if (!SCHEMA_VIAS.includes(via)) {
    return `结构校验来源仅支持 inline / contract，当前值「${via}」`
  }
  if (via === 'inline') {
    return inlineSchemaViolation(row.schema)
  }
  const code = row.code
  if (code === null || code === undefined) {
    return null
  }
  const parsed = typeof code === 'number' ? code
    : (typeof code === 'string' && /^\d+$/.test(code.trim()) ? Number(code.trim()) : -1)
  if (!Number.isInteger(parsed) || parsed < STATUS_MIN || parsed > STATUS_MAX) {
    return `结构校验引用的响应码 code 必须是 100–599 的整数（缺省=按实际响应码取对应结构样本），当前值「${String(code)}」`
  }
  return null
}

function inlineSchemaViolation(schema: unknown): string | null {
  if (schema === null || schema === undefined) {
    return 'inline 必须粘贴 JSON Schema（对象或 JSON 文本），例 {"type":"object","required":["code"]}'
  }
  if (typeof schema === 'object' && !Array.isArray(schema)) {
    return null
  }
  if (typeof schema !== 'string') {
    return 'JSON Schema 必须是对象或 JSON 文本，当前形态无法判定'
  }
  try {
    const parsed: unknown = JSON.parse(schema)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return 'JSON Schema 必须是对象或 JSON 文本，当前形态无法判定'
    }
  } catch {
    return `JSON Schema 不可解析（粘贴内容不是合法 JSON）：${schema.length <= 60 ? schema : `${schema.slice(0, 60)}…`}`
  }
  return null
}

/** Inline check of one assertion form row (violation text, null = legal; the
 * builder shows the return value directly). Protocol-crossed grammar per the
 * core contract: http face = six types, ws face = the three ws.* types. */
export function validateAssertionRow(row: unknown, protocol = 'http'): string | null {
  const trimmed = String(protocol ?? '').trim().toLowerCase()
  const normalized = (TRANSPORT_PROTOCOLS as readonly string[]).includes(trimmed)
    ? trimmed
    : (trimmed === '' ? 'http' : null)
  if (normalized === null) {
    return `传输协议不合法：仅支持 http / ws，当前值「${protocol}」`
  }
  if (row === null || typeof row !== 'object' || Array.isArray(row)) {
    return '断言每行必须是对象（type/op/expect 表单行）'
  }
  const rec = row as Record<string, unknown>
  const type = String(rec.type ?? '')
  if (normalized === 'ws') {
    return wsRowViolation(rec, type)
  }
  if ((WS_ASSERTION_TYPES as readonly string[]).includes(type)) {
    return `断言类型「${type}」不适用于 HTTP 传输面——请在请求上切到 ws 协议`
      + '（ws.receive / ws.order / ws.frequency），或改用 HTTP 型断言'
  }
  if (!ASSERTION_TYPES.includes(type as (typeof ASSERTION_TYPES)[number])) {
    return `断言类型仅支持 ${[...ASSERTION_TYPES].sort().join(' / ')}（禁自由表达式与脚本断言），当前值「${type}」`
  }
  switch (type) {
    case 'status': {
      const expect = rec.expect
      if (typeof expect !== 'number' || !Number.isInteger(expect)
        || expect < STATUS_MIN || expect > STATUS_MAX) {
        return '状态码断言 expect 必须是 100–599 的整数，例 200'
      }
      return null
    }
    case 'duration': {
      const opViolation = matchTypeViolation(String(rec.op ?? ''))
      if (opViolation) {
        return `耗时断言${opViolation}（例：op=range, expect=[0,3000]）`
      }
      return expectPairViolation(rec, '耗时')
    }
    case 'body': {
      const pathViolation = jsonPathViolation(rec.path)
      if (pathViolation) {
        return pathViolation
      }
      return matchRowViolation(rec)
    }
    case 'header': {
      if (String(rec.name ?? '').trim() === '') {
        return '响应头断言还差头名：请在 name 填写要校验的头名称（例 name=Content-Type）'
      }
      return matchRowViolation(rec)
    }
    case 'schema':
      return schemaViolation(rec)
    default: {
      // extract: three lanes (source defaults to body; legacy rows keep the JsonPath lane).
      // Messages mirror core ApiAssertionFormContract exactly — shared fixture pins them.
      const rawSource = String(rec.source ?? '').trim().toLowerCase()
      const source = rawSource === '' ? 'body' : rawSource
      if (!EXTRACT_SOURCE_VALUES.includes(source)) {
        return `变量提取来源 source 仅支持 ${EXTRACT_SOURCE_VALUES.join(' / ')}（缺省=body，按 JsonPath 提取），当前值「${String(rec.source ?? '')}」`
      }
      if (source === 'body') {
        const pathViolation = jsonPathViolation(rec.path)
        if (pathViolation) {
          return pathViolation
        }
      } else if (String(rec.name ?? '').trim() === '') {
        return `按 ${source} 提取变量还差名称：请在 name 填写要提取的头或 Cookie 名称（例 name=X-Auth-Token）`
      }
      const saveAs = rec.saveAs
      if (saveAs === null || saveAs === undefined) {
        return '变量提取断言还差保存变量名：请在 saveAs 填写变量名（例 saveAs=TOKEN，后续请求用 {{VAR:TOKEN}} 引用）'
      }
      if (!SAVE_AS_NAME.test(String(saveAs))) {
        return `变量提取断言 的 saveAs 必须是合法变量名（字母/下划线开头，后续字母数字下划线，跨集合共享请用 global.键名，供 {{VAR:${String(saveAs).trim() === '' ? '名字' : String(saveAs)}}} 引用），当前值「${String(saveAs)}」`
      }
      return null
    }
  }
}
