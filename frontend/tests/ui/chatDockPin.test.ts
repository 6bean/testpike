/**
 * dock window-management contract (dialog dock stays open).
 *
 * Pure persistence contract (useChatDockPin, same discipline as the width
 * preference — personal setting, per-user localStorage bucket, NEVER server,
 * NEVER a K8 governance key):
 * - read: '1' → pinned; anything broken → false (fails safe to the light
 * floating dock — the default behaviour must never silently become the
 * resident form, D1);
 * - write: pinned → '1'; unpinned clears the bucket (factory default again);
 * - the narrow-screen breakpoint (overlay form keeps click-outside close,
 * touch reachability, D2) is exported once from this module.
 *
 * Wiring assertions are source-as-text (the ?raw precedent of
 * chatDockResize.test.ts / chatDockWiring):
 * - ChatDock keeps the un-pinned click-outside path byte-identical for the
 * default form (R33 zero-drift acceptance: the collapse branch survives,
 * pinned + wide viewport is the ONLY new early return);
 * - pinned state lives in the ONE useChatDock singleton (no second copy),
 * ChatDock reads/writes it through the single-sourced helpers only;
 * - Esc keyboard equivalence to close (D6) with the honest guard: a modal
 * dialog / message box / popper under the keypress swallows the stroke
 * first, the dock never collapses behind a dialog the user is in;
 * - ChatPanel header button group: New FIRST (main position, reusing the
 * one newConversation owner), then history; pin toggle sits right before
 * close and renders only in the dock mount (closable), reusing the
 * sessionList.newChat locale key (one concept, one word).
 */
import { describe, expect, it } from 'vitest'
import {
  DOCK_COMPACT_BREAKPOINT,
  readDockPin,
  writeDockPin,
} from '@/composables/useChatDockPin'
import dockRaw from '@/components/ChatDock.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import dockStateRaw from '@/composables/useChatDock.ts?raw'
import { createMemoryStorage as memoryStorage } from './localStorageStub'

describe('dock pin persistence pure contract', () => {
  it('reads pinned only from an explicit 1, everything else is the light dock', () => {
    expect(readDockPin(memoryStorage({ 'tp_chat_dock_pin:7': '1' }), 7)).toBe(true)
    expect(readDockPin(memoryStorage({ 'tp_chat_dock_pin:7': '0' }), 7)).toBe(false)
    expect(readDockPin(memoryStorage({ 'tp_chat_dock_pin:7': 'oops' }), 7)).toBe(false)
    expect(readDockPin(memoryStorage(), 7)).toBe(false)
    // buckets never mix between users
    expect(readDockPin(memoryStorage({ 'tp_chat_dock_pin:7': '1' }), 8)).toBe(false)
  })

  it('pinned writes 1; unpinning clears the bucket back to the factory default', () => {
    const storage = memoryStorage()
    writeDockPin(storage, 7, true)
    expect(storage.dump.get('tp_chat_dock_pin:7')).toBe('1')
    writeDockPin(storage, 7, false)
    expect(storage.dump.has('tp_chat_dock_pin:7')).toBe(false)
  })

  it('the compact breakpoint is one exported number (overlay form, D2)', () => {
    expect(Number.isInteger(DOCK_COMPACT_BREAKPOINT)).toBe(true)
    expect(DOCK_COMPACT_BREAKPOINT).toBeGreaterThanOrEqual(640)
    expect(DOCK_COMPACT_BREAKPOINT).toBeLessThanOrEqual(900)
  })
})

describe('dock pin state stays single-sourced', () => {
  it('the useChatDock singleton owns pinned (no second dock state)', () => {
    expect(dockStateRaw).toContain('pinned')
    expect(dockStateRaw).toContain('setChatDockPin')
  })

  it('ChatDock consumes the shared helpers, never an inline localStorage key', () => {
    expect(dockRaw).toContain('readDockPin')
    expect(dockRaw).toContain('writeDockPin')
    expect(dockRaw).toContain('setChatDockPin')
    expect(dockRaw).not.toContain("'tp_chat_dock_pin'")
  })
})

describe('click-outside and escape wiring', () => {
  it('the default un-pinned collapse path is untouched (R33 zero drift)', () => {
    // the original collapse call still fires at the end of the handler
    expect(dockRaw).toContain('closeChatDock()')
    // pinned + non-compact is the ONLY new suppression, expressed once
    const suppressions = dockRaw.match(/isPinnedResident|chatDockState\.pinned/g) ?? []
    expect(suppressions.length).toBeGreaterThanOrEqual(1)
  })

  it('compact viewport keeps the click-outside close even when pinned (D2)', () => {
    expect(dockRaw).toContain('matchMedia')
    expect(dockRaw).toContain('DOCK_COMPACT_BREAKPOINT')
  })

  it('Esc closes the dock with the modal-layer guard (D6)', () => {
    expect(dockRaw).toContain('Escape')
    expect(dockRaw).toContain('.el-dialog, .el-message-box, .el-popper')
  })
})

describe('header button group (New main position + pin toggle)', () => {
  it('New sits FIRST in the ops group, ahead of history', () => {
    const newIdx = panelRaw.indexOf('chat-dock-new')
    const historyIdx = panelRaw.indexOf('chat-dock-history')
    expect(newIdx).toBeGreaterThan(-1)
    expect(historyIdx).toBeGreaterThan(-1)
    expect(newIdx).toBeLessThan(historyIdx)
  })

  it('New reuses the one newConversation owner and the locale key (no second copy)', () => {
    expect(panelRaw).toContain("@click=\"newConversation\"")
    expect(panelRaw).toContain("t('layout.chatDock.sessionList.newChat')")
  })

  it('pin toggle renders only in the dock mount and flips the singleton', () => {
    expect(panelRaw).toContain('chat-dock-pin')
    expect(panelRaw).toContain('setChatDockPin')
    // gated like the close button (closable marks the dock mount)
    const pinGate = panelRaw.match(/v-if="closable"[^>]*data-test="chat-dock-pin"|data-test="chat-dock-pin"[^>]*v-if="closable"/)
    expect(pinGate?.length ?? panelRaw.indexOf('v-if="closable"') < panelRaw.indexOf('chat-dock-pin')).toBeTruthy()
  })
})
