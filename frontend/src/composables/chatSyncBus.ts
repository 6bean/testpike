/**
 * Single-source object-change bus for landed dialog drafts .
 *
 * Why it exists: when a draft write landed through the confirmation door, the
 * business page still showing that object kept rendering the stale value
 * until the user refreshed by hand. The BG iron rule moves the announcement
 * to ONE point — the receipt wrappers in api/aiChat.ts — and pages subscribe
 * here to re-read silently. Nobody copies the broadcast again (R40).
 *
 * Shape, one event pattern { objectType, projectId, objectId } on two hops:
 * - a window CustomEvent (CHAT_SYNC_EVENT) for this tab, consumable with or
 * without the composable (same posture as tp-notification-changed);
 * - a same-origin BroadcastChannel (CHAT_SYNC_CHANNEL) so other tabs holding
 * the same object converge without any reload. Messages are stamped with
 * the posting tab's id and a stamp equal to ours is dropped, so an emit
 * never echoes back as a second delivery in the tab that made it.
 *
 * Lifecycle (R57): every subscriber gets an unhook from subscribeObjectSync
 * and MUST run it in onBeforeUnmount; syncSubscriberCount is the leak
 * ledger the bus contract test asserts back down to its baseline, and the
 * channel object itself is a bounded singleton (created lazily on first use,
 * never re-created per subscriber).
 */

export const CHAT_SYNC_EVENT = 'tp-chat-object-sync'
export const CHAT_SYNC_CHANNEL = 'tp-chat-object-sync'

export interface ChatObjectSyncEvent {
  objectType: string
  projectId: number | null
  objectId: number | null
}

type SyncHandler = (event: ChatObjectSyncEvent) => void

/** Identifies THIS tab on channel traffic so own posts never echo back. */
const tabStamp = typeof crypto !== 'undefined' && 'randomUUID' in crypto
  ? crypto.randomUUID()
  : `tab-${Date.now()}-${Math.random().toString(36).slice(2)}`

interface Binding {
  listener: EventListener
}

const bindings = new Set<Binding>()

let channel: BroadcastChannel | null = null

/** Strips the transport stamp and re-surfaces a foreign tab message locally. */
function toLocalEvent(data: unknown): ChatObjectSyncEvent | null {
  if (!data || typeof data !== 'object') return null
  const raw = data as Partial<ChatObjectSyncEvent> & { __tab?: string }
  if (raw.__tab === tabStamp) return null
  if (typeof raw.objectType !== 'string') return null
  return {
    objectType: raw.objectType,
    projectId: typeof raw.projectId === 'number' ? raw.projectId : null,
    objectId: typeof raw.objectId === 'number' ? raw.objectId : null,
  }
}

/** Lazy bounded singleton: one channel for the whole app, no per-page copies. */
function ensureChannel(): void {
  if (channel || typeof BroadcastChannel === 'undefined') return
  channel = new BroadcastChannel(CHAT_SYNC_CHANNEL)
  channel.onmessage = (message: MessageEvent) => {
    const event = toLocalEvent(message.data)
    if (!event) return
    window.dispatchEvent(new CustomEvent<ChatObjectSyncEvent>(CHAT_SYNC_EVENT, { detail: event }))
  }
}

/**
 * Announce one landed (or rolled-back) object. Called ONLY from the receipt
 * door wrappers in api/aiChat.ts — keep it that way; the coverage gate reads
 * those call sites as the single source of "what got broadcast".
 */
export function emitObjectSync(event: ChatObjectSyncEvent): void {
  window.dispatchEvent(new CustomEvent<ChatObjectSyncEvent>(CHAT_SYNC_EVENT, { detail: event }))
  ensureChannel()
  channel?.postMessage({ ...event, __tab: tabStamp })
}

/**
 * Listen for landing announcements. Returns the unhook that MUST run in
 * onBeforeUnmount (R57 recycle path — the contract test proves the ledger
 * drops back when pages leave).
 */
export function subscribeObjectSync(handler: SyncHandler): () => void {
  const listener: EventListener = (e: Event) => {
    const detail = (e as CustomEvent<ChatObjectSyncEvent>).detail
    if (detail && typeof detail.objectType === 'string') handler(detail)
  }
  const binding: Binding = { listener }
  bindings.add(binding)
  window.addEventListener(CHAT_SYNC_EVENT, listener)
  ensureChannel()
  return () => {
    window.removeEventListener(CHAT_SYNC_EVENT, listener)
    bindings.delete(binding)
  }
}


export function syncSubscriberCount(): number {
  return bindings.size
}
