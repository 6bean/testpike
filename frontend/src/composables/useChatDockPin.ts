/**
 * Chat dock pin preference .
 *
 * Same discipline as useChatDockWidth — a personal preference, per-user
 * localStorage bucket, NEVER the server and NEVER a K8 governance key:
 * - pinned persists across reloads (D5): value '1' → pinned, the dock keeps
 * its place while the rest of the page is clicked;
 * - anything missing or broken reads as false (D1: the default form is the
 * light floating dock — a corrupt bucket must never silently turn the
 * resident form on);
 * - unpinning clears the bucket so the factory default shows again;
 * - DOCK_COMPACT_BREAKPOINT is exported once: below it the dock switches to
 * the overlay form where click-outside close stays available even when
 * pinned (D2, touch reachability — a wide overlay you cannot dismiss by
 * tapping past it traps the page).
 */

const DOCK_PIN_KEY = 'tp_chat_dock_pin'
const ANON_BUCKET = 'anon'

/** Below this viewport width the dock is an overlay that keeps click-outside close. */
export const DOCK_COMPACT_BREAKPOINT = 768

type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

function storageBucket(userId: number | string | null | undefined): string {
  return `${DOCK_PIN_KEY}:${userId ?? ANON_BUCKET}`
}

/** Read the persisted pin flag; only an explicit '1' pins, everything else is the light dock. */
export function readDockPin(
  storage: StorageLike,
  userId: number | string | null | undefined,
): boolean {
  return storage.getItem(storageBucket(userId)) === '1'
}

/** Persist the pin state; false clears the bucket back to the factory default. */
export function writeDockPin(
  storage: StorageLike,
  userId: number | string | null | undefined,
  pinned: boolean,
): void {
  const bucket = storageBucket(userId)
  if (pinned) {
    storage.setItem(bucket, '1')
  } else {
    storage.removeItem(bucket)
  }
}
