/**
 * Floating chat-entry bubble position .
 *
 * The always-present bottom-right chat bubble is draggable so users can move
 * it off whatever it covers. This file is the single source of that geometry:
 * - clamp keeps the bubble fully inside the viewport (12px safety margin) and
 * out of caller-supplied blocked bands (top header, pager/scrollbar hit
 * zones) — the E2E occlusion assertions judge against this same function;
 * - releasing a drag snaps to the nearer edge;
 * - a pointer move under 8px counts as a click (anti-jitter);
 * - the slot rides per-user localStorage (personal-preference ruling: never
 * on the server, never on K8 governance keys), mirroring useRailWidthPrefs.
 */

/** Bubble edge inset when clamping/snapping (adjudication §thirteen guard-rail b). */
export const FAB_EDGE_SAFETY = 12
/** Default slot offset from the right/bottom viewport edges. */
export const FAB_DEFAULT_MARGIN = 24
/** Pointer travel that separates a drag from a click (adjudication: ≥8px = drag). */
export const FAB_DRAG_THRESHOLD = 8
/** Rendered bubble diameter in px (kept in sync with the component CSS). */
export const FAB_SIZE = 48

const FAB_POS_KEY = 'tp_chat_fab'
const ANON_BUCKET = 'anon'

export interface FabPoint {
  left: number
  top: number
}

/** Rectangular zone the bubble must never cover (header band, pager band, …). */
export interface BlockedBand {
  top: number
  bottom: number
  left: number
  right: number
}

type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

function overlapsBand(p: FabPoint, band: BlockedBand): boolean {
  return (
    p.left < band.right &&
    p.left + FAB_SIZE > band.left &&
    p.top < band.bottom &&
    p.top + FAB_SIZE > band.top
  )
}

function clampAxis(value: number, size: number, extent: number, safety: number): number {
  return Math.min(extent - size - safety, Math.max(safety, Math.round(value)))
}

/** Default slot = 24px off the right/bottom edges. */
export function defaultFabPosition(viewport: { width: number; height: number }): FabPoint {
  return {
    left: viewport.width - FAB_SIZE - FAB_DEFAULT_MARGIN,
    top: viewport.height - FAB_SIZE - FAB_DEFAULT_MARGIN,
  }
}

/**
 * Resolve a dragged position: clamp inside the viewport with the safety
 * margin, then escape any blocked band by sliding along the smaller axis
 * (up first for the header band, up/down against the pager band). Falls back
 * to the default slot if no escape fit — the bubble never ends up occluding a
 * band or half off-screen.
 */
export function clampFabPosition(
  left: number,
  top: number,
  viewport: { width: number; height: number },
  bands: BlockedBand[],
): FabPoint {
  let p: FabPoint = {
    left: clampAxis(left, FAB_SIZE, viewport.width, FAB_EDGE_SAFETY),
    top: clampAxis(top, FAB_SIZE, viewport.height, FAB_EDGE_SAFETY),
  }
  for (const band of bands) {
    if (!overlapsBand(p, band)) continue
    // Escape vertically: prefer the direction that needs the smaller move.
    const upMove = p.top + FAB_SIZE - band.top + FAB_EDGE_SAFETY
    const downMove = band.bottom - p.top + FAB_EDGE_SAFETY
    const upTop = clampAxis(p.top - upMove, FAB_SIZE, viewport.height, FAB_EDGE_SAFETY)
    const downTop = clampAxis(p.top + downMove, FAB_SIZE, viewport.height, FAB_EDGE_SAFETY)
    if (!overlapsBand({ ...p, top: upTop }, band)) {
      p = { ...p, top: upTop }
    } else if (!overlapsBand({ ...p, top: downTop }, band)) {
      p = { ...p, top: downTop }
    } else {
      const dflt = defaultFabPosition(viewport)
      p = bands.every((b) => !overlapsBand(dflt, b)) ? dflt : { ...p, top: upTop }
    }
  }
  return p
}

/** Snap a released drag to the nearer horizontal edge (12px inset). */
export function snapFabToEdge(left: number, viewport: { width: number }): number {
  const min = FAB_EDGE_SAFETY
  const max = viewport.width - FAB_SIZE - FAB_EDGE_SAFETY
  const center = viewport.width / 2
  const clamped = Math.min(max, Math.max(min, Math.round(left)))
  return clamped - min <= max - clamped ? min : max
}

/** Move-and-drag discrimination: pointer travel (Euclidean) ≥8px = a drag. */
export function isFabDrag(dx: number, dy: number): boolean {
  return Math.hypot(dx, dy) >= FAB_DRAG_THRESHOLD
}

function storageBucket(userId: number | string | null | undefined): string {
  return `${FAB_POS_KEY}:${userId ?? ANON_BUCKET}`
}

/** Read the persisted slot; anything unreadable returns null (default slot). */
export function readFabPosition(
  storage: StorageLike,
  userId: number | string | null | undefined,
): FabPoint | null {
  const raw = storage.getItem(storageBucket(userId))
  if (raw === null) return null
  try {
    const parsed = JSON.parse(raw) as Partial<FabPoint>
    if (
      parsed &&
      typeof parsed.left === 'number' &&
      typeof parsed.top === 'number' &&
      Number.isFinite(parsed.left) &&
      Number.isFinite(parsed.top)
    ) {
      return { left: parsed.left, top: parsed.top }
    }
  } catch {
    // corrupted bucket — treat as absent instead of trusting it
  }
  return null
}

/** Persist the slot; null clears the bucket (reset-to-default keeps no residue). */
export function writeFabPosition(
  storage: StorageLike,
  userId: number | string | null | undefined,
  point: FabPoint | null,
): void {
  const key = storageBucket(userId)
  if (point === null) storage.removeItem(key)
  else storage.setItem(key, JSON.stringify(point))
}
