import { i18n } from './i18n'


const MANAGED_PREFIXES = ['dict.', 'http.']

export function td(key: string): string
export function td(key: string | undefined): string | undefined
export function td(key: string | undefined): string | undefined {
  if (key === undefined) return undefined
  if (MANAGED_PREFIXES.some((p) => key.startsWith(p))) return i18n.global.t(key)
  return key
}


export function ht(key: string, named?: Record<string, unknown>): string {
  return i18n.global.t(key, named ?? {})
}
