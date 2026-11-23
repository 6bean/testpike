/**
 * auth-editor segment: auth-form kernel — TS mirror of the UX pre-gate.
 * Authority = core AuthFormContract (Java, save gate on api-test requests);
 * this file mirrors the same five-type vocabulary and plain-language verdicts
 * so the auth editor can pinpoint a bad row before submit. Both ends consume
 * the same fixture shared/auth_form_cases.json (assertionForm.ts /
 * orchestrationForm.ts precedent) — one dialect, no second verdict copy.
 *
 * Executor runtime (api_runner._auth_headers) accepts the same five types,
 * so a shape accepted here is always executable downstream. Secret slots
 * (bearer token, header/api-key value, basic password) must carry a credential
 * reference — @cred/ prefix or a {{CRED:key}} placeholder — never a plaintext
 * value (red line 4: plaintext secrets would land in stored rows and every
 * read-back snapshot).
 */

/** Auth types the executor injects (mirror SUPPORTED_TYPES — change all three together). */
export const AUTH_TYPES = ['api-key', 'basic', 'bearer', 'header', 'signature'] as const

/** Placement slots for api-key injection (mirror PLACEMENTS). */
export const AUTH_PLACEMENTS = ['header', 'query'] as const

/** Secret-slot reference (mirror CRED_REF): whole @cred/ path, whole {{CRED:key}} or embedded. */
const CRED_REF = /^(@cred\/\S+|\{\{CRED:[A-Za-z_][A-Za-z0-9_]*\}\}|.*\{\{CRED:[A-Za-z_][A-Za-z0-9_]*\}\}.*?)$/

/** Sorted type list used by verdicts (mirror typeList()). */
export function authTypeList(): string {
  return [...AUTH_TYPES].sort().join(' / ')
}

/** Java JsonNode.asText("") posture: scalars stringify, containers/absent become ''. */
function textOf(v: unknown): string {
  if (v === null || v === undefined) return ''
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return ''
}

/** Red line: a secret slot must be a credential reference, never a plaintext value. */
function secretSlotViolation(slot: string, value: string): string | null {
  if (!CRED_REF.test(value.trim())) {
    return `${slot} 必须是凭据引用（@cred/ 开头，或 {{CRED:键}} 占位）——`
      + '明文密钥禁入请求定义，否则会落库并进历史快照；'
      + '请先在凭据管理中登记该密钥再以引用名接入'
  }
  return null
}

function bearerViolation(auth: Record<string, unknown>): string | null {
  const token = textOf(auth.token).trim()
  if (token === '') {
    return 'bearer 认证缺少 token（凭据引用形态：@cred/ 开头或 {{CRED:键}} 占位，'
      + '在凭据管理中登记后引用）'
  }
  return secretSlotViolation('bearer 的 token', token)
}

function headerViolation(auth: Record<string, unknown>): string | null {
  if (textOf(auth.name).trim() === '') {
    return 'header 认证缺少头名（name），例 name=X-API-KEY'
  }
  const value = textOf(auth.value)
  if (value.trim() === '') {
    return 'header 认证缺少头值（value）——凭据引用形态：@cred/ 开头或 {{CRED:键}} 占位'
  }
  return secretSlotViolation('header 的 value', value)
}

function basicViolation(auth: Record<string, unknown>): string | null {
  if (textOf(auth.username).trim() === '') {
    return 'basic 认证缺少用户名（username）'
  }
  const password = textOf(auth.password)
  if (password.trim() === '') {
    return 'basic 认证缺少口令——请在字段 password 填凭据引用'
      + '（@cred/ 开头或 {{CRED:键}} 占位，在凭据管理中登记后引用）'
  }
  return secretSlotViolation('basic 的 password', password)
}

function apiKeyViolation(auth: Record<string, unknown>): string | null {
  if (textOf(auth.name).trim() === '') {
    return 'api-key 认证缺少键名（name），例 name=apiKey'
  }
  const value = textOf(auth.value)
  if (value.trim() === '') {
    return 'api-key 认证缺少键值（value）——凭据引用形态：@cred/ 开头或 {{CRED:键}} 占位'
  }
  const secret = secretSlotViolation('api-key 的 value', value)
  if (secret) return secret
  const placement = (textOf(auth.placement) || 'header').trim().toLowerCase()
  if (!AUTH_PLACEMENTS.includes(placement as (typeof AUTH_PLACEMENTS)[number])) {
    return `api-key 注入位置「${placement}」不支持（仅 header / query，缺省 header）`
      + '——请调整请求定义'
  }
  return null
}

function signatureViolation(auth: Record<string, unknown>): string | null {
  const rows = auth.assemblies
  if (!Array.isArray(rows) || rows.length === 0) {
    return 'signature 认证缺少装配行（assemblies 至少一行 {into,name,value}，'
      + 'into=header/query 注入位置、name=键名、value=模板串'
      + '，时间戳/nonce/签名用 ${gen.*} 函数，密钥用 {{CRED:键}} 引用）'
  }
  for (const raw of rows) {
    if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
      return `signature 装配行必须是对象（into/name/value），当前值「${textOf(raw) || String(raw)}」`
    }
    const row = raw as Record<string, unknown>
    if (textOf(row.name).trim() === '') {
      return 'signature 装配行缺少注入键名（name）'
    }
    const valueText = textOf(row.value)
    if (valueText.trim() === '') {
      return 'signature 装配行缺少 value（模板串，例 ${gen.timestamp()} 或 ${gen.hmacSha256(...)}）'
    }
    const into = (textOf(row.into) || 'header').trim().toLowerCase()
    if (!AUTH_PLACEMENTS.includes(into as (typeof AUTH_PLACEMENTS)[number])) {
      return `signature 装配位置「${into}」不支持（仅 header / query，缺省 header）`
        + '——请调整请求定义'
    }
    // Dialect guard (docs/04 rule 6a): the executor's single ${gen.fn(k=v)}
    // parser never touches {{gen.*}} — it would dispatch verbatim as a literal
    // header value (fake green). Refusing at edit time keeps
    // "accepted here" == "executable downstream" (same anchor as the core gate).
    if (valueText.includes('{{gen.')) {
      return 'signature 装配行的模板函数须用 ${gen.函数名(参数=值)} 写法'
        + '（当前为 {{gen.}} 形态——执行器唯一解析器只认 ${gen.*}，'
        + '{{gen.}} 形态会原文发出导致假通过）'
    }
  }
  return null
}

/**
 * Auth object verdict (mirror AuthFormContract.violation): human-readable
 * violation text, null = legal. Absent/null/empty object/empty array = no auth.
 * A non-empty array is a shape violation (Java JsonNode.isEmpty() covers [] as
 * no-auth, ["x"] falls to the object-required verdict — both mirrored here).
 */
export function validateAuth(auth: unknown): string | null {
  if (auth === null || auth === undefined) return null
  if (Array.isArray(auth)) {
    if (auth.length === 0) return null
    return '认证配置必须是对象（形如 {"type":"bearer",...}；无需认证保持 {})'
  }
  if (typeof auth !== 'object') {
    return '认证配置必须是对象（形如 {"type":"bearer",...}；无需认证保持 {})'
  }
  const rec = auth as Record<string, unknown>
  if (Object.keys(rec).length === 0) return null
  const type = textOf(rec.type).trim().toLowerCase()
  if (type === '') {
    return `认证配置写了字段但缺少 type——支持的类型：${authTypeList()}；无需认证请保持空对象 {}`
  }
  if (!AUTH_TYPES.includes(type as (typeof AUTH_TYPES)[number])) {
    return `认证类型「${type}」未支持（当前仅 ${authTypeList()} 注入）——请调整请求定义，勿假装无认证执行`
  }
  switch (type) {
    case 'bearer': return bearerViolation(rec)
    case 'header': return headerViolation(rec)
    case 'basic': return basicViolation(rec)
    case 'api-key': return apiKeyViolation(rec)
    default: return signatureViolation(rec)
  }
}

/**
 * Login-state one-click auth template . A captured session
 * row references its vault credential by key; the generated auth block
 * carries that {{CRED:key}} reference only — the secret value stays in the
 * vault and the dispatch-time injector resolves it (no second resolver).
 * storage_state rows belong to the UI context-injection lane, so the API
 * side offers no shape for them.
 */
export interface ConsumableSessionState {
  stateName: string
  kind: string
  credKey: string | null | undefined
  expiresAt?: string | null
}

export function sessionStateAuthTemplate(
  state: ConsumableSessionState,
): Record<string, unknown> | null {
  const credKey = (state.credKey ?? '').trim()
  if (!credKey) return null
  switch (state.kind) {
    case 'token':
      return { type: 'bearer', token: `{{CRED:${credKey}}}` }
    case 'cookie_set':
      return { type: 'header', name: 'Cookie', value: `{{CRED:${credKey}}}` }
    default:
      return null
  }
}

/** Ledger rows the auth picker may offer: a template shape must exist. */
export function isConsumableSessionState(state: ConsumableSessionState): boolean {
  return sessionStateAuthTemplate(state) !== null
}
