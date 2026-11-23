/**
 * — composer height & WeChat-shape layout contract (§two.5/§two.6, D9).
 *
 * Pure clamp contract (mechanical gate):
 * - rows fold into [COMPOSER_MIN_ROWS, ceiling]; non-finite → factory 3
 * (the composer never collapses to 0 rows);
 * - the ceiling comes from the governance key ai.chat.composer-max-rows
 * (factory 10); a garbage or below-floor server value normalizes back to
 * the factory ceiling — it can never squeeze the box under its own default;
 * - the personal rows ride per-user localStorage (never the server), reset
 * clears the bucket so the factory rows show again.
 *
 * Wiring pinned source-as-text (?raw precedent of chatDockResize):
 * - textarea rows start at 3 (was a hard 2) and bind the composable state;
 * - a grab strip above the composer: pointer drag + double-click reset +
 * keyboard ←/→ steps (X3: a drag is never the only path), release
 * persists through the SAME clamp (zero second copy, R40);
 * - WeChat shape (D9): the textarea owns a full-width row — no button sits
 * between input and send any more; the action row underneath carries the
 * left icon group (photo attach door) and the right send group;
 * - the ceiling read goes through the existing grouped config channel with
 * a fail-soft default (same posture as the paste switch).
 */
import { describe, expect, it } from 'vitest'
import {
  COMPOSER_DEFAULT_ROWS,
  COMPOSER_FACTORY_MAX_ROWS,
  COMPOSER_MIN_ROWS,
  clampComposerRows,
  normalizeMaxRows,
  readComposerRows,
  writeComposerRows,
} from '@/composables/useChatComposerRows'
import panelRaw from '@/components/ChatPanel.vue?raw'
import rowsRaw from '@/composables/useChatComposerRows.ts?raw'
import { createMemoryStorage as memoryStorage } from './localStorageStub'

describe('composer rows clamp pure contract', () => {
  it('pins the interval [3, ceiling] with rounding', () => {
    expect(COMPOSER_MIN_ROWS).toBe(3)
    expect(COMPOSER_DEFAULT_ROWS).toBe(3)
    expect(COMPOSER_FACTORY_MAX_ROWS).toBe(10)
    expect(clampComposerRows(1)).toBe(3)
    expect(clampComposerRows(99)).toBe(10)
    expect(clampComposerRows(5.6)).toBe(6)
    expect(clampComposerRows(12, 6)).toBe(6)
  })

  it('a broken value falls back to the factory rows, never a collapsed composer', () => {
    expect(clampComposerRows(Number.NaN)).toBe(COMPOSER_DEFAULT_ROWS)
    expect(clampComposerRows(Infinity)).toBe(COMPOSER_DEFAULT_ROWS)
  })

  it('a garbage/below-floor server ceiling normalizes back to factory 10', () => {
    expect(normalizeMaxRows('abc')).toBe(COMPOSER_FACTORY_MAX_ROWS)
    expect(normalizeMaxRows('')).toBe(COMPOSER_FACTORY_MAX_ROWS)
    expect(normalizeMaxRows(null)).toBe(COMPOSER_FACTORY_MAX_ROWS)
    expect(normalizeMaxRows(2)).toBe(COMPOSER_FACTORY_MAX_ROWS)
    expect(normalizeMaxRows('6')).toBe(6)
    expect(clampComposerRows(Number.NaN, 'oops' as unknown as number)).toBe(COMPOSER_DEFAULT_ROWS)
  })
})

describe('composer rows persistence', () => {
  it('round-trips through per-user bucket, clamped on write', () => {
    const s = memoryStorage()
    writeComposerRows(s, '7', 14)
    expect(s.dump.get('tp_chat_composer_rows:7')).toBe('10')
    expect(readComposerRows(s, '7')).toBe(10)
  })

  it('reset clears the bucket so the factory rows show again', () => {
    const s = memoryStorage({ 'tp_chat_composer_rows:7': '8' })
    writeComposerRows(s, '7', null)
    expect(s.dump.has('tp_chat_composer_rows:7')).toBe(false)
    expect(readComposerRows(s, '7')).toBe(COMPOSER_DEFAULT_ROWS)
  })

  it('a broken stored value reads back the clamped default', () => {
    const s = memoryStorage({ 'tp_chat_composer_rows:anon': 'not-a-number' })
    expect(readComposerRows(s, null)).toBe(COMPOSER_DEFAULT_ROWS)
  })

  it('a lowered ceiling re-folds a stored oversized value on read', () => {
    const s = memoryStorage({ 'tp_chat_composer_rows:7': '9' })
    expect(readComposerRows(s, '7', 5)).toBe(5)
  })
})

describe('composer wiring (source-as-text)', () => {
  it('textarea binds the composable rows state at default 3, never a hard 2', () => {
    expect(panelRaw).toContain(':rows="composerRows"')
    expect(panelRaw).not.toContain(':rows="2"\n          resize="none"')
  })

  it('ships ONE grab strip with pointer drag, dblclick reset and keyboard steps', () => {
    expect(panelRaw).toContain('data-test="chat-composer-resize"')
    expect(panelRaw).toContain('@pointerdown="onComposerPointerDown"')
    expect(panelRaw).toContain('@dblclick="resetComposerRows"')
    expect(panelRaw).toContain('@keydown="onComposerResizeKeydown"')
    // Every path folds through the same clamp — no second arithmetic.
    const stripHandler = panelRaw.slice(
      panelRaw.indexOf('function onComposerPointerDown'),
      panelRaw.indexOf('/** Double-click on the strip'),
    )
    expect(stripHandler).toContain('clampComposerRows(')
    expect(stripHandler).not.toContain('Math.min(')
  })

  it('WeChat shape: textarea owns a full-width row, buttons live in the action row', () => {
    expect(panelRaw).toContain('class="chat-composer-actions"')
    expect(panelRaw).toContain('data-test="chat-action-group-left"')
    expect(panelRaw).toContain('data-test="chat-action-group-right"')
    const input = panelRaw.indexOf('data-test="chat-dock-input"')
    const left = panelRaw.indexOf('data-test="chat-action-group-left"')
    const send = panelRaw.indexOf('data-test="chat-dock-send"')
    expect(input).toBeGreaterThan(-1)
    expect(left).toBeGreaterThan(input)
    expect(send).toBeGreaterThan(left)
    // The old squeezed row is gone for good.
    expect(panelRaw).not.toContain('chat-composer-row')
  })

  it('pending-image queue carries the send-hint line', () => {
    expect(panelRaw).toContain('data-test="chat-image-queue-hint"')
    expect(panelRaw).toContain("t('layout.chatDock.image.queueHint')")
  })

  it('ceiling rides the governance key through the grouped config channel only', () => {
    expect(rowsRaw).toContain("COMPOSER_MAX_ROWS_KEY = 'ai.chat.composer-max-rows'")
    // One read channel only: the existing grouped config query (paste-switch
    // posture), no second remote endpoint wired for this key.
    expect(rowsRaw).toContain('listConfigs(')
    expect(rowsRaw).toContain('loadComposerMaxRowsSwitch')
    expect(panelRaw).toContain('void loadComposerMaxRowsSwitch()')
  })
})
