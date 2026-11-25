/**
 * Windowing + path-group helpers for the contract-stub wizard draft list.
 *
 * Worst scale for this list is a few hundred endpoints from one OpenAPI
 * contract (MJ P7: 500 rows). Inlining every row costs the browser and
 * makes scrolling useless, so the wizard mounts a moving window instead:
 * at most 2x the visible unit count in the DOM at any time, with honest
 * spacer heights above and below so the scrollbar keeps the true scale.
 * Rows also group by the first resource-path segment; collapsing a group
 * removes its rows from the unit stream (the header stays, with a count).
 *
 * Pure functions only — the component owns scroll position and state.
 */

/** Uniform height used for window math (keep in sync with .sd-card/.sd-group-head CSS). */
export const DRAFT_UNIT_HEIGHT_PX = 44

/** Units fully visible in the fixed-height card viewport. */
export const DRAFT_VISIBLE_UNITS = 12

export type DraftUnit<T> =
  | { kind: 'group'; key: string; count: number }
  | { kind: 'draft'; row: T }

/**
 * Bucket key = first non-empty segment of the resource path. A path with
 * no segment (root "/") honestly returns "" and the UI labels that bucket.
 */
export function pathGroupKey(pattern: string): string {
  const seg = (pattern || '').split('/').find((s) => s.length > 0)
  return seg ?? ''
}

/**
 * Flatten rows into render units in first-appearance order: one group
 * header per bucket plus its rows, skipping the rows of collapsed groups.
 */
export function flattenDraftUnits<T extends { pathPattern: string }>(
  rows: T[],
  collapsed: Set<string>,
): DraftUnit<T>[] {
  const order: string[] = []
  const byKey = new Map<string, T[]>()
  for (const row of rows) {
    const key = pathGroupKey(row.pathPattern)
    let bucket = byKey.get(key)
    if (!bucket) {
      bucket = []
      byKey.set(key, bucket)
      order.push(key)
    }
    bucket.push(row)
  }
  const units: DraftUnit<T>[] = []
  for (const key of order) {
    const bucket = byKey.get(key) ?? []
    units.push({ kind: 'group', key, count: bucket.length })
    if (!collapsed.has(key)) {
      for (const row of bucket) units.push({ kind: 'draft', row })
    }
  }
  return units
}

export interface DraftWindowRange {
  start: number
  end: number
  padTopPx: number
  padBottomPx: number
}

/**
 * Window over `total` units for a container scrolled `scrollTop` px.
 * The mount span never exceeds 2x DRAFT_VISIBLE_UNITS so the DOM stays
 * bounded no matter how far the list grows; lists within the mount
 * budget render fully with zero padding.
 */
export function draftWindowRange(
  scrollTop: number,
  total: number,
  visible: number = DRAFT_VISIBLE_UNITS,
): DraftWindowRange {
  const mountBudget = 2 * visible
  if (total <= mountBudget) {
    return { start: 0, end: total, padTopPx: 0, padBottomPx: 0 }
  }
  const firstVisible = Math.floor(Math.max(0, scrollTop) / DRAFT_UNIT_HEIGHT_PX)
  // Center the visible band inside the mounted window while keeping the
  // head pinned at 0 and the tail pinned at total.
  const start = Math.min(Math.max(0, firstVisible - Math.floor(visible / 2)), total - visible)
  const end = Math.min(total, start + mountBudget)
  return {
    start,
    end,
    padTopPx: start * DRAFT_UNIT_HEIGHT_PX,
    padBottomPx: (total - end) * DRAFT_UNIT_HEIGHT_PX,
  }
}
