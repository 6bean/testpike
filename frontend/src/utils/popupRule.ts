
import type { AppautoPopupRule } from '@/api/appauto'


const STATUS_TAG: Record<number, 'warning' | 'success' | 'info'> = {
  1: 'warning',
  2: 'success',
  3: 'info',
}


export const RULE_STATUS_LABEL_KEY: Record<number, string> = {
  1: 'appauto.popupRules.statusPending',
  2: 'appauto.popupRules.statusStored',
  3: 'appauto.popupRules.statusRejected',
}


export function normalizePopupRuleRows(raw: unknown): AppautoPopupRule[] {
  if (!Array.isArray(raw)) return []
  return raw.filter(
    (x): x is AppautoPopupRule => !!x && typeof x === 'object' && typeof (x as AppautoPopupRule).id === 'number',
  )
}


export function popupRuleStatusTag(status: number): 'warning' | 'success' | 'info' {
  return STATUS_TAG[status] ?? 'info'
}


export function formatRuleJson(rule: string | null): string {
  if (typeof rule !== 'string' || rule.length === 0) return ''
  try {
    return JSON.stringify(JSON.parse(rule), null, 2)
  } catch {
    return rule
  }
}
