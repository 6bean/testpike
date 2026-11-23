/**
 * Chat dock width preference .
 *
 * Single source for the drawer width geometry so the grab strip, the keyboard
 * steps and the initial read all fold through ONE clamp — the same discipline
 * as useChatFabPosition:
 * - clamp pins the width inside [320,640]; a broken/non-finite stored value
 * falls back to the 420 default (a dock must never collapse to 0 width);
 * - the slot rides per-user localStorage (personal-preference ruling: never
 * on the server, never a K8 governance key);
 * - reset writes null = clear the bucket so the factory default shows again.
 */

/** Factory default width (double-click reset target). */
export const DOCK_DEFAULT_WIDTH = 420
/** Narrowest usable dock (conversation stays readable at this floor). */
export const DOCK_MIN_WIDTH = 320
/** Widest dock the shell allows (never crowds out the page underneath). */
export const DOCK_MAX_WIDTH = 640
/** Keyboard arrow step for the focused grab strip (X3 single-point rule). */
export const DOCK_RESIZE_STEP = 16

const DOCK_WIDTH_KEY = 'tp_chat_dock_width'
const ANON_BUCKET = 'anon'

type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

function storageBucket(userId: number | string | null | undefined): string {
  return `${DOCK_WIDTH_KEY}:${userId ?? ANON_BUCKET}`
}

/** Clamp a requested width into [320,640]; non-finite input → default. */
export function clampDockWidth(raw: number): number {
  if (!Number.isFinite(raw)) return DOCK_DEFAULT_WIDTH
  return Math.min(DOCK_MAX_WIDTH, Math.max(DOCK_MIN_WIDTH, Math.round(raw)))
}

/** Read the persisted width (per-user bucket); anything broken → default. */
export function readDockWidth(
  storage: StorageLike,
  userId: number | string | null | undefined,
): number {
  const raw = storage.getItem(storageBucket(userId))
  if (raw === null) return DOCK_DEFAULT_WIDTH
  return clampDockWidth(Number(raw))
}

/** Persist a width through the clamp; null = reset (bucket cleared). */
export function writeDockWidth(
  storage: StorageLike,
  userId: number | string | null | undefined,
  width: number | null,
): void {
  const bucket = storageBucket(userId)
  if (width === null) {
    storage.removeItem(bucket)
    return
  }
  storage.setItem(bucket, String(clampDockWidth(width)))
}
