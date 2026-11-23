/**
 * — dock resize + click-outside collapse contract (§sixteen rule 6 / D2).
 *
 * Pure-function clamp contract (adjudication mechanical gate):
 * - clamp pins the width inside [320,640] and rounds; a non-finite value
 * falls back to the 420 default (never an invisible 0-width dock);
 * - the preference rides per-user localStorage under tp_chat_dock_width,
 * NEVER the server and never a K8 governance key;
 * - reset (double-click) clears the bucket so the factory default shows.
 *
 * Wiring pinned source-as-text (?raw precedent of chatDockWiring):
 * - a 4px grab strip on the drawer's left edge, keyboard-equivalent
 * ←/→ 16px steps (X3: a drag must never be the only path);
 * - releasing persists through the same clamp function (zero second copy);
 * - a click that lands outside the dock body AND outside the floating
 * bubble collapses the dock; streaming keeps running server-side (the
 * panel instance is never destroyed on close = resume re-reads the thread);
 * - the bubble carries a "running" halo while a stream is live and the
 * dock is closed (soft glow, no harsh bar).
 */
import { describe, expect, it } from 'vitest'
import {
  DOCK_DEFAULT_WIDTH,
  DOCK_MAX_WIDTH,
  DOCK_MIN_WIDTH,
  DOCK_RESIZE_STEP,
  clampDockWidth,
  readDockWidth,
  writeDockWidth,
} from '@/composables/useChatDockWidth'
import dockRaw from '@/components/ChatDock.vue?raw'
import widthRaw from '@/composables/useChatDockWidth.ts?raw'
import fabRaw from '@/components/ChatFab.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import dockStateRaw from '@/composables/useChatDock.ts?raw'
import { createMemoryStorage as memoryStorage } from './localStorageStub'

describe('dock width clamp pure contract', () => {
  it('pins the interval [320,640] with rounding', () => {
    expect(DOCK_MIN_WIDTH).toBe(320)
    expect(DOCK_MAX_WIDTH).toBe(640)
    expect(clampDockWidth(100)).toBe(320)
    expect(clampDockWidth(9999)).toBe(640)
    expect(clampDockWidth(500.6)).toBe(501)
  })

  it('a broken stored value falls back to the default, never a collapsed dock', () => {
    expect(clampDockWidth(Number.NaN)).toBe(DOCK_DEFAULT_WIDTH)
    expect(clampDockWidth(Infinity)).toBe(DOCK_DEFAULT_WIDTH)
  })
})

describe('dock width preference never touches the server', () => {
  it('reads the per-user bucket through the same clamp', () => {
    const storage = memoryStorage({ 'tp_chat_dock_width:7': '500' })
    expect(readDockWidth(storage, 7)).toBe(500)
    expect(readDockWidth(storage, 8)).toBe(DOCK_DEFAULT_WIDTH)
    const broken = memoryStorage({ 'tp_chat_dock_width:7': 'oops' })
    expect(readDockWidth(broken, 7)).toBe(DOCK_DEFAULT_WIDTH)
  })

  it('writes clamp the value; reset clears the bucket', () => {
    const storage = memoryStorage()
    writeDockWidth(storage, 7, 9999)
    expect(storage.dump.get('tp_chat_dock_width:7')).toBe('640')
    writeDockWidth(storage, 7, null)
    expect(storage.dump.has('tp_chat_dock_width:7')).toBe(false)
  })

  it('the component consumes the single-sourced helpers (zero second clamp)', () => {
    expect(dockRaw).toContain('readDockWidth')
    expect(dockRaw).toContain('writeDockWidth')
    expect(dockRaw).toContain('clampDockWidth')
    expect(dockRaw).toContain('authStore.user?.userId')
    expect(widthRaw).toMatch(/tp_chat_dock_width/)
    expect(widthRaw).not.toMatch(/axios|fetch\(|api\//)
  })
})

describe('grab strip and keyboard equivalent', () => {
  it('renders a focusable vertical separator on the dock edge', () => {
    expect(dockRaw).toContain('data-test="chat-dock-resize"')
    expect(dockRaw).toContain('role="separator"')
    expect(dockRaw).toContain('tabindex="0"')
    expect(dockRaw).toContain('@pointerdown')
    // Double-click on the strip resets to the factory default.
    expect(dockRaw).toContain('@dblclick')
    // X3 single-point alternative: arrow keys step the width.
    expect(dockRaw).toContain('keydown.left')
    expect(dockRaw).toContain('keydown.right')
    expect(DOCK_RESIZE_STEP).toBe(16)
  })

  it('releasing a drag persists through the clamp (live width is transform-cheap)', () => {
    expect(dockRaw).toMatch(/pointerup|pointermove/)
    expect(dockRaw).toContain('writeDockWidth')
  })
})

describe('click-outside collapse keeps the stream alive', () => {
  it('a pointerdown outside dock and bubble closes the dock', () => {
    expect(dockRaw).toContain('closeChatDock')
    expect(dockRaw).toMatch(/pointerdown/)
    expect(dockRaw).toContain('ai-chat-fab')
    expect(dockRaw).toContain('ai-chat-drawer')
  })

  it('closing the drawer never destroys the panel (collapse ≠ interrupt)', () => {
    // el-drawer keeps its content mounted; destroy-on-close must not appear.
    expect(dockRaw).not.toMatch(/destroy-on-close|destroyOnClose/)
  })

  it('the live running flag reaches the shared dock state for the badge', () => {
    expect(dockStateRaw).toContain('streaming')
    expect(dockStateRaw).toContain('setChatStreaming')
    expect(panelRaw).toContain('setChatStreaming')
  })
})

describe('bubble running halo', () => {
  it('the bubble marks an in-flight answer while the dock is closed', () => {
    expect(fabRaw).toContain('data-test="chat-fab-running"')
    expect(fabRaw).toContain('streaming')
    // Soft halo (animation), no harsh bar: pin the visual discipline.
    expect(fabRaw).toMatch(/animation|@keyframes|halo/)
  })
})
