/**
 * — dock entry badge: three-state presentation + unread counting.
 *
 * Contract pinned here (adjudication §two.3, D7 settled):
 * - counting = new messages COMPLETED while the dock was closed: sealed
 * answer 1, every pending-confirmation draft card 1, terminal failure 1;
 * live streaming frames never count frame by frame (fold point is seal);
 * - cap: value above the cap renders as "<cap>+" (honest, never truncated
 * silently); cap default 99, hot-read from the system config group when
 * the key exists (fail-soft to the default);
 * - zero unread and nothing running => the bubble shows no badge at all;
 * - the read anchor is per session: entering one session zeroes only it.
 *
 * Pure module is imported live; the wiring side rides the source-as-text
 * precedent (chatDockWiring.test.ts) so the fold point cannot silently
 * migrate to a second place.
 */
import { describe, expect, it } from 'vitest'
import fabRaw from '@/components/ChatFab.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import dockStateRaw from '@/composables/useChatDock.ts?raw'
import {
  DEFAULT_BADGE_CAP,
  badgeLabel,
  clearSession,
  countSealedUnread,
  markCompleted,
  readLedger,
  unreadTotal,
  writeLedger,
  type UnreadLedger,
} from '@/composables/chatUnreadBadge'

function fakeStorage(): { store: Record<string, string>; getItem: (k: string) => string | null; setItem: (k: string, v: string) => void } {
  const store: Record<string, string> = {}
  return {
    store,
    getItem: (k: string) => store[k] ?? null,
    setItem: (k: string, v: string) => {
      store[k] = v
    },
  }
}

describe('sealed-turn counting rule (never per frame)', () => {
  it('a sealed answer counts one', () => {
    expect(countSealedUnread({ state: 'done', repairs: [] })).toBe(1)
  })

  it('draft cards awaiting confirmation each add one on top', () => {
    expect(countSealedUnread({ state: 'done', repairs: [{ draftId: 1 }, { draftId: 2 }] })).toBe(3)
  })

  it('a terminal failure counts exactly one notification', () => {
    expect(countSealedUnread({ state: 'failed', repairs: [] })).toBe(1)
  })

  it('an unsealed (still running) turn counts zero — the fold only happens at seal', () => {
    expect(countSealedUnread({ state: 'running', repairs: [{ draftId: 1 }] })).toBe(0)
  })
})

describe('ledger arithmetic', () => {
  it('marking accumulates per session and totals across sessions', () => {
    let ledger: UnreadLedger = {}
    ledger = markCompleted(ledger, 's1', 2)
    ledger = markCompleted(ledger, 's2', 1)
    expect(ledger).toEqual({ s1: 2, s2: 1 })
    expect(unreadTotal(ledger)).toBe(3)
  })

  it('a non-positive delta never writes the ledger', () => {
    expect(markCompleted({}, 's1', 0)).toEqual({})
  })

  it('clearing one session leaves the others untouched (per-session read anchor)', () => {
    const cleared = clearSession({ s1: 4, s2: 2 }, 's1')
    expect(cleared).toEqual({ s2: 2 })
  })
})

describe('badge label and cap', () => {
  it('zero renders nothing (third state: neither halo nor number)', () => {
    expect(badgeLabel(0, DEFAULT_BADGE_CAP)).toBeNull()
  })

  it('within the cap shows the honest count', () => {
    expect(badgeLabel(7, DEFAULT_BADGE_CAP)).toBe('7')
    expect(badgeLabel(99, DEFAULT_BADGE_CAP)).toBe('99')
  })

  it('above the cap shows "<cap>+" — never a truncated lie', () => {
    expect(badgeLabel(140, DEFAULT_BADGE_CAP)).toBe('99+')
  })

  it('a hot-read cap of 5 turns six into "5+"', () => {
    expect(badgeLabel(6, 5)).toBe('5+')
  })
})

describe('local persistence (phase one, per user)', () => {
  it('round-trips through storage', () => {
    const env = fakeStorage()
    writeLedger(env, 'u1', { s1: 3 })
    expect(readLedger(env, 'u1')).toEqual({ s1: 3 })
  })

  it('corrupt payloads fall soft to an empty ledger', () => {
    const env = fakeStorage()
    env.store['tp_chat_unread_u1'] = '{oops'
    expect(readLedger(env, 'u1')).toEqual({})
  })

  it('negative or junk entries are dropped on read', () => {
    const env = fakeStorage()
    writeLedger(env, 'u1', { a: 2, b: -1 } as unknown as UnreadLedger)
    env.store['tp_chat_unread_u1'] = JSON.stringify({ a: 2, b: -1, c: 'x' })
    expect(readLedger(env, 'u1')).toEqual({ a: 2 })
  })
})

describe('wiring pins (single fold point, single presenter)', () => {
  it('the panel counts only at seal and only while the dock is closed', () => {
    expect(panelRaw).toContain('countSealedUnread')
    expect(panelRaw).toContain('markChatUnreadCompleted')
    expect(panelRaw).toMatch(/chatDockState\.visible\b/)
  })

  it('entering a session clears that session anchor', () => {
    expect(panelRaw).toContain('clearChatUnreadSession')
  })

  it('the bubble presents all three states from one computed', () => {
    expect(fabRaw).toContain('chat-fab-badge')
    expect(fabRaw).toContain('badgeLabel')
    expect(fabRaw).toContain('ai.chat.badge-cap')
  })

  it('the ledger state lives in the dock singleton (no second copy)', () => {
    expect(dockStateRaw).toContain('markChatUnreadCompleted')
    expect(dockStateRaw).toContain('chatUnreadTotal')
  })
})
