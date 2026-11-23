/**
 * Chat dock entry badge — unread ledger .
 *
 * Pure counting/label/persistence helpers for the bubble's three-state badge:
 * running = breathing halo + the count gathered so far;
 * idle = static red badge with the count when it is above zero;
 * quiet = nothing at all when neither runs nor waits.
 *
 * Only SEALED events count (one per completed answer, one per draft card
 * awaiting confirmation, one per terminal failure) — live stream frames never
 * touch the ledger; the panel folds at the seal point. The read anchor is per
 * session and phase one persists it in localStorage per user, so a reload
 * keeps the honest number. The message center's own notices stay on their
 * channel: nothing here double-counts a site message.
 */

export const DEFAULT_BADGE_CAP = 99

/** session id (stringified) -> unread completions gathered while closed. */
export type UnreadLedger = Record<string, number>

/**
 * How many badge events one sealed turn contributes. `running` never counts
 * (the fold happens after the stream settles; guarding it here keeps a
 * mis-wired call site honest instead of silently over-counting).
 */
export function countSealedUnread(turn: {
  state?: string
  repairs?: { draftId?: number | null }[]
}): number {
  if (turn.state === 'running') return 0
  if (turn.state === 'failed') return 1
  return 1 + (turn.repairs?.length ?? 0)
}

/** Ledger total across all sessions (what the bubble shows). */
export function unreadTotal(ledger: UnreadLedger): number {
  return Object.values(ledger).reduce((sum, n) => sum + (Number.isInteger(n) && n > 0 ? n : 0), 0)
}

/** Add `delta` completions to one session; non-positive deltas never write. */
export function markCompleted(ledger: UnreadLedger, sessionKey: string, delta: number): UnreadLedger {
  if (!Number.isFinite(delta) || delta <= 0) return ledger
  return { ...ledger, [sessionKey]: (ledger[sessionKey] ?? 0) + delta }
}

/** The user entered this session: its anchor zeroes, other sessions stay. */
export function clearSession(ledger: UnreadLedger, sessionKey: string): UnreadLedger {
  if (!(sessionKey in ledger)) return ledger
  const rest = { ...ledger }
  delete rest[sessionKey]
  return rest
}

/**
 * Badge text: null = show nothing; above the cap shows "<cap>+" so the user
 * learns there is MORE, never a silently truncated number.
 */
export function badgeLabel(count: number, cap: number = DEFAULT_BADGE_CAP): string | null {
  if (count <= 0) return null
  if (count > cap) return `${cap}+`
  return String(count)
}

/** Same persistence shape the dock singleton exposes (one definition). */
export type MiniStorage = { getItem: (k: string) => string | null; setItem: (k: string, v: string) => void }

function ledgerKey(userId: string): string {
  return `tp_chat_unread_${userId}`
}

/** Fail-soft read: corrupt payloads or junk entries fall back to an honest
 *  (possibly empty) ledger of positive integers only. */
export function readLedger(storage: MiniStorage, userId: string): UnreadLedger {
  try {
    const raw = storage.getItem(ledgerKey(userId))
    if (!raw) return {}
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return {}
    const clean: UnreadLedger = {}
    for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof v === 'number' && Number.isInteger(v) && v > 0) clean[k] = v
    }
    return clean
  } catch {
    return {}
  }
}

export function writeLedger(storage: MiniStorage, userId: string, ledger: UnreadLedger): void {
  try {
    storage.setItem(ledgerKey(userId), JSON.stringify(ledger))
  } catch {
    // Quota/private-mode failures only cost persistence across reloads.
  }
}
