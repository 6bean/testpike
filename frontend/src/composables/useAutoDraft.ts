import { toRaw, watch } from 'vue'
import type { WatchStopHandle } from 'vue'




export const AUTO_DRAFT_DEBOUNCE_MS = 8000

export const AUTO_DRAFT_MAX_BYTES = 1024 * 1024

export const AUTO_DRAFT_TTL_MS = 24 * 60 * 60 * 1000

const SUMMARY_TITLE_MAX = 200


export interface AutoDraftRecord {
  data: Record<string, unknown>
  savedAt: string
  truncated?: boolean
}


export function autoDraftKey(entity: string, scope: 'create' | 'edit', idOrProject: number | string): string {
  return `tp-auto-draft:${entity}:${scope}:${idOrProject}`
}


const pendingTimers = new Map<string, ReturnType<typeof setTimeout>>()


export function scheduleAutoDraft(
  key: string,
  formOrGetter: Record<string, unknown> | (() => Record<string, unknown>),
  delayMs: number = AUTO_DRAFT_DEBOUNCE_MS,
  isValid: () => boolean = () => true,
): void {
  const readForm = typeof formOrGetter === 'function'
    ? (formOrGetter as () => Record<string, unknown>)
    : () => formOrGetter
  const existing = pendingTimers.get(key)
  if (existing) clearTimeout(existing)
  pendingTimers.set(key, setTimeout(() => {
    pendingTimers.delete(key)
    if (!isValid()) return
    writeDraft(key, readForm())
  }, delayMs))
}


export function clearAutoDraft(key: string): void {
  const existing = pendingTimers.get(key)
  if (existing) {
    clearTimeout(existing)
    pendingTimers.delete(key)
  }
  removeStored(key)
}


export function peekAutoDraft(key: string): AutoDraftRecord | null {
  let raw: string | null = null
  try {
    raw = localStorage.getItem(key)
  } catch {
    return null
  }
  if (!raw) return null
  const parsed = safeParseRecord(raw)
  const valid = !!parsed
    && typeof parsed.data === 'object' && parsed.data !== null && !Array.isArray(parsed.data)
    && typeof parsed.savedAt === 'string' && !Number.isNaN(Date.parse(parsed.savedAt))
    && Date.now() - Date.parse(parsed.savedAt) <= AUTO_DRAFT_TTL_MS
  if (!valid) {
    removeStored(key)
    return null
  }
  return parsed as unknown as AutoDraftRecord
}


export function useAutoDraft(
  key: string | (() => string),
  form: Record<string, unknown>,
  options: {
    delayMs?: number
    enabled?: () => boolean
 
    snapshot?: () => Record<string, unknown>
  } = {},
): { stop: () => void } {
  const delayMs = options.delayMs ?? AUTO_DRAFT_DEBOUNCE_MS
  const isEnabled = options.enabled ?? (() => true)
  const source: Record<string, unknown> | (() => Record<string, unknown>) = options.snapshot ?? form
  const resolveKey = typeof key === 'function' ? key : () => key
  const stopWatch: WatchStopHandle = watch(
    form,
    () => {
      if (!isEnabled()) return
      const scheduledKey = resolveKey()
      scheduleAutoDraft(scheduledKey, source, delayMs, () => resolveKey() === scheduledKey)
    },
    { deep: true },
  )
  return { stop: () => stopWatch() }
}


export function applyAutoDraftData(form: Record<string, unknown>, data: Record<string, unknown>): boolean {
  let applied = false
  for (const [k, raw] of Object.entries(data)) {
    if (!(k in form) || raw === undefined || raw === null) continue
    const cur = form[k]
    if (Array.isArray(cur) && Array.isArray(raw)) {
      cur.splice(0, cur.length, ...(raw as unknown[]))
      applied = true
    } else if (cur === undefined || cur === null || typeof raw === typeof cur) {
      form[k] = raw
      applied = true
    }
  }
  return applied
}


function safeParseRecord(text: string): Record<string, unknown> | null {
  try {
    const v = JSON.parse(text) as unknown
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function removeStored(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
  }
}


function buildSummary(data: Record<string, unknown>): Record<string, unknown> {
  const summary: Record<string, unknown> = { title: '', stepCount: 0 }
  const title = data.title
  if (typeof title === 'string') summary.title = title.slice(0, SUMMARY_TITLE_MAX)
  if (Array.isArray(data.steps)) summary.stepCount = data.steps.length
  return summary
}


function writeDraft(key: string, form: Record<string, unknown>): void {
  const data = toRaw(form)
  let serialized: string | null = null
  try {
    serialized = JSON.stringify(data)
  } catch {
    serialized = null
  }
  let truncated = false
  if (serialized === null || serialized.length > AUTO_DRAFT_MAX_BYTES) {
    truncated = true
    try {
      serialized = JSON.stringify(buildSummary(data))
    } catch {
      return
    }
  }
  const envelope: AutoDraftRecord = truncated
    ? { data: JSON.parse(serialized) as Record<string, unknown>, savedAt: new Date().toISOString(), truncated: true }
    : { data: JSON.parse(serialized) as Record<string, unknown>, savedAt: new Date().toISOString() }
  try {
    localStorage.setItem(key, JSON.stringify(envelope))
  } catch {
  }
}
