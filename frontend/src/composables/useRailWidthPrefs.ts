/**
 * Workbench rail width preference ( stage 2, ruling = docs/change-eval/
 * drag range 280..480).
 * - Mirrors useSidebarPrefs discipline: single source constants, per-user
 * localStorage bucket (personal preference never touches K8 governance keys),
 * defensive read (bad/missing values fall back to default).
 * - Shape differs from the global sidebar (different default/min/max and key
 * namespace), so it is its own file instead of widening the sidebar piece.
 */

export const RAIL_DEFAULT_WIDTH = 360
export const RAIL_MIN_WIDTH = 280
export const RAIL_MAX_WIDTH = 480

const RAIL_WIDTH_KEY = 'tp_apitest_rail_width'
const ANON_BUCKET = 'anon'

export function clampRailWidth(raw: number): number {
  if (!Number.isFinite(raw)) return RAIL_DEFAULT_WIDTH
  return Math.min(RAIL_MAX_WIDTH, Math.max(RAIL_MIN_WIDTH, Math.round(raw)))
}

/** Read the persisted rail width; anything unreadable returns the default. */
export function readRailWidth(userId: number | string | null | undefined): number {
  const raw = localStorage.getItem(`${RAIL_WIDTH_KEY}:${userId ?? ANON_BUCKET}`)
  if (raw === null) return RAIL_DEFAULT_WIDTH
  return clampRailWidth(Number(raw))
}

export function writeRailWidth(userId: number | string | null | undefined, width: number): void {
  localStorage.setItem(`${RAIL_WIDTH_KEY}:${userId ?? ANON_BUCKET}`, String(clampRailWidth(width)))
}
