import type { LocationQuery, LocationQueryRaw } from 'vue-router'

/**
 * AG batch §12a seamless-jump contract helpers (single source,
 * views only wire them; unit home = tests/ui/mockJump.test.ts).
 *
 * Cross-page jumps between test assets and the mock domain ride on URL query
 * keys (projectId / spaceId / source) — pre-filter context, never a route
 * alias double-mount (M-g ruling: independent menu group + seamless links).
 * Consumption is honest: a pre-filled id only lands if it exists in the
 * target page's loaded domain; otherwise the page falls back to its own
 * first-item rule instead of guessing.
 */

/** Query value shape vue-router hands back (string, array, or absent). */
export type QueryVal = string | (string | null)[] | null | undefined

/** Resolve a space id from the URL against the loaded space list. */
export function resolveSpaceIdFromQuery(raw: QueryVal | number, allowed: number[]): number | null {
  const v = Array.isArray(raw) ? null : raw
  const id = typeof v === 'number' ? v : Number(v)
  if (!Number.isInteger(id) || id <= 0) return null
  return allowed.includes(id) ? id : null
}

export interface MockJumpKeys {
  projectId?: number | null
  spaceId?: number | null
  source?: string | null
}

/**
 * Merge mock jump keys over the current query for a router.replace payload.
 * null-valued keys are dropped (a cleared filter must not linger in the URL);
 * unrelated keys survive untouched.
 */
export function withMockJumpQuery(
  currentQuery: LocationQuery,
  keys: MockJumpKeys,
): { query: LocationQueryRaw } {
  const next: Record<string, unknown> = { ...currentQuery }
  for (const [k, v] of Object.entries(keys)) {
    if (v === null || v === undefined) delete next[k]
    else next[k] = v
  }
  return { query: next as LocationQueryRaw }
}

export interface StubSourceRef {
  source: string | null
  sourceRef: string | null
  projectId: number | null
  spaceId: number | null
}

export interface JumpTarget {
  path: string
  query: LocationQueryRaw
}

/**
 * Return door for a rule's origin badge (docs page -> the page the rule was
 * born from). Manual and unknown sources have no door — the caller hides the
 * jump affordance instead of inventing a route for a source spectrum it does
 * not know.
 */
export function jumpTargetForSource(stub: StubSourceRef): JumpTarget | null {
  switch (stub.source) {
    case 'case-flip': {
      const caseId = Number(stub.sourceRef)
      if (Number.isInteger(caseId) && caseId > 0) {
        return { path: `/cases/${caseId}`, query: {} }
      }
      return {
        path: '/cases',
        query: stub.projectId == null ? {} : ({ projectId: stub.projectId } as LocationQueryRaw),
      }
    }
    case 'openapi':
      return {
        path: '/api-test',
        query: stub.projectId == null ? {} : ({ projectId: stub.projectId } as LocationQueryRaw),
      }
    case 'record':
    case 'nl-ai': {
      // AN-4: the traffic inspector merged into the per-space
      // workbench. A rule that knows its space lands straight on that
      // space's inspector tab; a rule without one lands honestly on the
      // spaces list (never a guessed space).
      if (stub.spaceId != null) {
        const q: Record<string, unknown> = { tab: 'inspector' }
        if (stub.projectId != null) q.projectId = stub.projectId
        return { path: `/mock/spaces/${stub.spaceId}`, query: q as LocationQueryRaw }
      }
      return {
        path: '/mock/spaces',
        query: stub.projectId == null ? {} : ({ projectId: stub.projectId } as LocationQueryRaw),
      }
    }
    default:
      return null
  }
}
