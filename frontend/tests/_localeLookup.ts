/**
 * Locale-tree lookup shared by the contract specs (single source, R40).
 *
 * The UI contracts in `tests/ui` and `tests/comp` all need the same thing: resolve a dotted key
 * (`layout.chatDock.draft.titleApiAccess`) against an imported locale object and answer `undefined`
 * when a segment is missing, so a spec can assert "this key exists in both locales" without
 * re-implementing the walk. Twelve suites had each grown their own copy of this six-line walk;
 * a copy is how drift starts - one file adds a guard for arrays, the next does not, and the same
 * missing key passes in one suite and fails in another. New suites consume this module instead of
 * copying the walk again.
 */
export function lookupLocale(obj: unknown, dotted: string): unknown {
  let cur: unknown = obj
  for (const k of dotted.split('.')) {
    if (cur && typeof cur === 'object' && k in (cur as Record<string, unknown>)) {
      cur = (cur as Record<string, unknown>)[k]
    } else {
      return undefined
    }
  }
  return cur
}
