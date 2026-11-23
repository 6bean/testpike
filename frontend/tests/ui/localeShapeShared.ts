/**
 * Shared locale-shape helpers for contract tests (R40 single source: the
 * flatten-a-locale-object shape has a second consumer, so it lives here once
 * instead of being copied per suite).
 */

/** Every dotted key path of a nested locale object, alphabetically sorted. */
export function flatKeys(obj: Record<string, unknown>, prefix = ''): string[] {
  const out: string[] = []
  for (const [k, v] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object') out.push(...flatKeys(v as Record<string, unknown>, path))
    else out.push(path)
  }
  return out.sort()
}

/** Every string leaf of a nested locale object, in traversal order. */
export function flatValues(obj: Record<string, unknown>): string[] {
  const out: string[] = []
  const walk = (node: Record<string, unknown>): void => {
    for (const v of Object.values(node)) {
      if (v && typeof v === 'object') walk(v as Record<string, unknown>)
      else if (typeof v === 'string') out.push(v)
    }
  }
  walk(obj)
  return out
}
