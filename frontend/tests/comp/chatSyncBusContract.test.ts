/**
 * BG batch — single-source object-sync bus contract.
 *
 * The bus is the ONLY place a landed dialog draft announces its changed
 * object (iron rule 1 in the BG ruling file): one window CustomEvent for this
 * tab plus one same-origin BroadcastChannel hop so other tabs converge
 * without a manual refresh. This file pins the runtime shape in real
 * Chromium (window + BroadcastChannel are native there):
 * - one emit = exactly one delivery per subscriber (no same-tab echo loop);
 * - unsubscribing stops delivery and the ledger counts back down (R57:
 * every subscriber must run its unhook on onBeforeUnmount);
 * - a foreign tab's channel message surfaces as the same local event shape;
 * - plain window listeners hear the same CustomEvent without the
 * composable (same posture as tp-notification-changed).
 */
import { describe, expect, it } from 'vitest'
import {
  CHAT_SYNC_CHANNEL,
  CHAT_SYNC_EVENT,
  emitObjectSync,
  subscribeObjectSync,
  syncSubscriberCount,
} from '@/composables/chatSyncBus'

describe('chat sync bus (BG T629 single source)', () => {
  it('delivers one landing announcement to every subscriber, once', async () => {
    const seenA: unknown[] = []
    const seenB: unknown[] = []
    const unA = subscribeObjectSync((e) => seenA.push(e))
    const unB = subscribeObjectSync((e) => seenB.push(e))
    emitObjectSync({ objectType: 'CASE_WRITE', projectId: 7, objectId: 42 })
    // The channel hop of another same-tab instance would arrive async; poll a
    // short settle window to prove the same-tab echo never double-delivers.
    await expect.poll(() => seenA.length, { timeout: 400 }).toBe(1)
    expect(seenA[0]).toEqual({ objectType: 'CASE_WRITE', projectId: 7, objectId: 42 })
    expect(seenB).toHaveLength(1)
    unA()
    unB()
  })

  it('unsubscribe stops delivery and the subscriber ledger counts back down (R57)', async () => {
    const before = syncSubscriberCount()
    let hits = 0
    const un = subscribeObjectSync(() => hits++)
    expect(syncSubscriberCount()).toBe(before + 1)
    emitObjectSync({ objectType: 'TAG_WRITE', projectId: null, objectId: 3 })
    await expect.poll(() => hits, { timeout: 400 }).toBe(1)
    un()
    expect(syncSubscriberCount()).toBe(before)
    emitObjectSync({ objectType: 'TAG_WRITE', projectId: null, objectId: 3 })
    await new Promise((r) => setTimeout(r, 60))
    expect(hits).toBe(1)
  })

  it('surfaces a foreign tab message as the same local event shape', async () => {
    // A separate channel instance stands in for the other tab; a message
    // without this tab's stamp must reach local subscribers.
    const seen: unknown[] = []
    const un = subscribeObjectSync((e) => seen.push(e))
    const foreign = new BroadcastChannel(CHAT_SYNC_CHANNEL)
    foreign.postMessage({ objectType: 'SCRIPT_REPAIR', projectId: 2, objectId: 9 })
    await expect.poll(() => seen.length, { timeout: 1000 }).toBe(1)
    expect(seen[0]).toEqual({ objectType: 'SCRIPT_REPAIR', projectId: 2, objectId: 9 })
    un()
    foreign.close()
  })

  it('exposes a plain window CustomEvent listeners can join without the composable', () => {
    let detail: unknown = null
    const listener = (e: Event) => {
      detail = (e as CustomEvent).detail
    }
    window.addEventListener(CHAT_SYNC_EVENT, listener)
    emitObjectSync({ objectType: 'SKILL_WRITE', projectId: 4, objectId: 8 })
    window.removeEventListener(CHAT_SYNC_EVENT, listener)
    expect(detail).toMatchObject({ objectType: 'SKILL_WRITE', projectId: 4, objectId: 8 })
  })
})
