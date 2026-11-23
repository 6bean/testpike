/**
 * One dictionary naming mock fault types , red line 6 — the
 * docs card, the response-builder form and the traffic inspector all read
 * fault labels through this file; a second copy could drift and mislabel a
 * served fault). Keys are i18n message paths, never baked-in words.
 *
 * The spectrum mirrors backend MockFaultRules.FAULT_TYPES plus the synthetic
 * `chaos` label the space-level slider injects (a stub must not pretend to be
 * chaos — the label exists to name what the ledger recorded).
 */

/** Fault types the stub spectrum accepts (backend MockFaultRules twin). */
export const FAULT_TYPES = ['delay', 'status', 'malformed', 'dribble', 'disconnect'] as const

/** i18n key for one fault type label. Unknown types keep their raw token in
 * the key so the lookup misses loudly — the UI must rename nothing that the
 * data plane actually said (the ledger could carry `chaos`, the synthetic
 * space-slider label, which is registered too). */
export function faultTypeLabelKey(type: string): string {
  return `mockFault.${type}`
}

/**
 * Summarise the fault key of a response-builder payload for display:
 * plain type name plus the probability gate when present. Returns null when
 * the payload carries no fault (absence is never invented).
 */
export function faultChipText(
  responseJson: string | null,
  label: (key: string) => string,
): string | null {
  if (!responseJson) return null
  let root: Record<string, unknown>
  try {
    root = JSON.parse(responseJson) as Record<string, unknown>
  } catch {
    return null
  }
  const fault = root.fault
  if (!fault || typeof fault !== 'object' || Array.isArray(fault)) return null
  const record = fault as Record<string, unknown>
  const type = typeof record.type === 'string' ? record.type : ''
  if (!type) return null
  const name = label(`mockFault.${type}`)
  const probability = typeof record.probability === 'number' ? record.probability : null
  return probability === null ? name : `${name} · ${probability}%`
}
