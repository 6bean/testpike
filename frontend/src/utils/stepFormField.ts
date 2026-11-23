
import type { VocabField } from '@/config/actionVocab'
import { checkJsonText } from '@/utils/jsonForm'
import { i18n } from '@/utils/i18n'
import type { ScopeStep } from '@/utils/stepScope'


function tv(key: string, params?: Record<string, unknown>): string {
  return String(i18n.global.t(key, params ?? {}))
}


export const LOCATOR_FIELDS: { key: string; label: string }[] = [
  { key: 'css', label: 'vocab.locator.css' },
  { key: 'xpath', label: 'vocab.locator.xpath' },
  { key: 'text', label: 'vocab.locator.text' },
  { key: 'role', label: 'vocab.locator.role' },
  { key: 'accessibilityId', label: 'vocab.locator.accessibilityId' },
  { key: 'chain', label: 'vocab.locator.chain' },
]


export interface LocatorModel {
  css: string
  xpath: string
  text: string
  role: string
  accessibilityId: string
  chain: string[]
}


export interface FieldEditResult {
  ok: boolean
  step: ScopeStep
  error?: string
}

function isBlank(s: string): boolean {
  return !s.trim()
}


export function toControlValue(step: ScopeStep, field: VocabField): unknown {
  const raw = step[field.key]
  switch (field.type) {
    case 'bool':
      return raw === true
    case 'number':
      return typeof raw === 'number' && Number.isFinite(raw) ? raw : null
    case 'stringList':
      return Array.isArray(raw) ? raw.filter((x): x is string => typeof x === 'string') : []
    case 'json':
      return raw === undefined || raw === null ? '' : JSON.stringify(raw, null, 2)
    case 'locator':
      return locatorModelOf(raw)
    default:
      return typeof raw === 'string' ? raw : ''
  }
}

function locatorModelOf(raw: unknown): LocatorModel {
  const src = raw && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : {}
  const str = (k: keyof LocatorModel) => (typeof src[k] === 'string' ? (src[k] as string) : '')
  return {
    css: str('css'),
    xpath: str('xpath'),
    text: str('text'),
    role: str('role'),
    accessibilityId: str('accessibilityId'),
    chain: Array.isArray(src.chain) ? src.chain.filter((x): x is string => typeof x === 'string') : [],
  }
}


export function applyFieldEdit(step: ScopeStep, field: VocabField, value: unknown): FieldEditResult {
  const next: ScopeStep = { ...step }
  const key = field.key
  const fail = (error: string): FieldEditResult => ({ ok: false, step, error })

  switch (field.type) {
    case 'bool':
      if (value === true) next[key] = true
      else delete next[key]
      return { ok: true, step: next }
    case 'number': {
      if (value === null || value === undefined || value === '') {
        delete next[key]
        return { ok: true, step: next }
      }
      const n = typeof value === 'number' ? value : Number(value)
      if (!Number.isFinite(n)) return fail(tv('vocab.err.needNumber', { label: tv(field.label) }))
      if (field.min !== undefined && n < field.min) {
        return fail(tv('vocab.err.numberBelowMin', { label: tv(field.label), min: field.min }))
      }
      if (field.max !== undefined && n > field.max) {
        return fail(tv('vocab.err.numberAboveMax', { label: tv(field.label), max: field.max }))
      }
      next[key] = n
      return { ok: true, step: next }
    }
    case 'stringList': {
      const list = (Array.isArray(value) ? value : []).map((x) => String(x).trim()).filter((x) => x)
      if (!list.length && field.preserveEmptyList) {
        next[key] = []
      } else if (!list.length) {
        delete next[key]
      } else {
        next[key] = list
      }
      return { ok: true, step: next }
    }
    case 'json': {
      const text = typeof value === 'string' ? value : ''
      const check = checkJsonText(text)
      if (!check.ok) return fail(tv('vocab.err.notJson', { label: tv(field.label) }))
      if (check.value === null) delete next[key]
      else next[key] = JSON.parse(check.value)
      return { ok: true, step: next }
    }
    case 'locator': {
      const model = locatorModelOf(value)
      const assembled: Record<string, unknown> = {}
      for (const f of LOCATOR_FIELDS) {
        if (f.key === 'chain') {
          const chain = model.chain.map((x) => x.trim()).filter((x) => x)
          if (chain.length) assembled[f.key] = chain
        } else if (!isBlank(model[f.key as keyof LocatorModel] as string)) {
          assembled[f.key] = (model[f.key as keyof LocatorModel] as string).trim()
        }
      }
      if (!Object.keys(assembled).length) delete next[key]
      else next[key] = assembled
      return { ok: true, step: next }
    }
    default: {
      const s = typeof value === 'string' ? value : ''
      if (isBlank(s)) delete next[key]
      else next[key] = s
      return { ok: true, step: next }
    }
  }
}
