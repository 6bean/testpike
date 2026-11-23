/**
 * stage 1 — floating chat entry position contract (adjudication §thirteen).
 *
 * Pins the pure geometry behind the always-present bottom-right chat bubble:
 * - default slot = 24px baseline from the right/bottom edges;
 * - any resolved position stays fully inside the viewport with a 12px safety
 * margin and never overlaps the blocked bands (top header, pager/horizontal
 * scrollbar hit zones) handed in by the caller;
 * - releasing a drag snaps the bubble to the nearer edge (12px inset);
 * - a pointer move below 8px is a click, not a drag (anti-jitter);
 * - reset writes back the default slot; the position rides per-user
 * localStorage and never the server (personal-preference ruling).
 */
import { describe, expect, it } from 'vitest'
import {
  FAB_EDGE_SAFETY,
  FAB_DEFAULT_MARGIN,
  FAB_DRAG_THRESHOLD,
  FAB_SIZE,
  clampFabPosition,
  defaultFabPosition,
  isFabDrag,
  readFabPosition,
  snapFabToEdge,
  writeFabPosition,
} from '@/composables/useChatFabPosition'
import { createMemoryStorage as memoryStorage } from './localStorageStub'

const VP = { width: 1440, height: 900 }
/** Top header band the bubble must never cover (y in [0, 60)). */
const HEADER = { top: 0, bottom: 60, left: 0, right: 1440 }
/** Bottom pager/scrollbar hit band (y in [850, 900)). */
const PAGER = { top: 850, bottom: 900, left: 1000, right: 1440 }

function overlaps(rect: { top: number; bottom: number; left: number; right: number }, x: number, y: number) {
  return x < rect.right && x + FAB_SIZE > rect.left && y < rect.bottom && y + FAB_SIZE > rect.top
}

describe('chat fab default slot', () => {
  it('sits 24px off the right/bottom edges', () => {
    const p = defaultFabPosition(VP)
    expect(p.left).toBe(VP.width - FAB_SIZE - FAB_DEFAULT_MARGIN)
    expect(p.top).toBe(VP.height - FAB_SIZE - FAB_DEFAULT_MARGIN)
  })
})

describe('chat fab viewport clamp', () => {
  it('keeps the bubble fully inside the viewport with the 12px safety margin', () => {
    const p = clampFabPosition(-100, -100, VP, [])
    expect(p.left).toBe(FAB_EDGE_SAFETY)
    expect(p.top).toBe(FAB_EDGE_SAFETY)
    const far = clampFabPosition(9999, 9999, VP, [])
    expect(far.left).toBe(VP.width - FAB_SIZE - FAB_EDGE_SAFETY)
    expect(far.top).toBe(VP.height - FAB_SIZE - FAB_EDGE_SAFETY)
  })

  it('pushes the bubble out of the top header band', () => {
    const p = clampFabPosition(700, 10, VP, [HEADER])
    expect(overlaps(HEADER, p.left, p.top)).toBe(false)
    expect(p.top).toBeGreaterThanOrEqual(HEADER.bottom + FAB_EDGE_SAFETY)
  })

  it('pushes the bubble out of the pager/scrollbar band on the right', () => {
    // default-ish slot right-bottom collides with the pager band → lifted above it
    const p = clampFabPosition(VP.width - FAB_SIZE - 20, VP.height - FAB_SIZE - 20, VP, [PAGER])
    expect(overlaps(PAGER, p.left, p.top)).toBe(false)
    expect(p.top + FAB_SIZE).toBeLessThanOrEqual(PAGER.top)
  })

  it('never reports a position outside the viewport even when every band is blocked', () => {
    const p = clampFabPosition(0, 0, VP, [HEADER, PAGER])
    expect(p.left).toBeGreaterThanOrEqual(0)
    expect(p.top).toBeGreaterThanOrEqual(0)
    expect(p.left + FAB_SIZE).toBeLessThanOrEqual(VP.width)
    expect(p.top + FAB_SIZE).toBeLessThanOrEqual(VP.height)
  })
})

describe('chat fab snap-to-edge', () => {
  it('snaps to the nearer of the two edges with the 12px inset', () => {
    expect(snapFabToEdge(100, VP)).toBe(FAB_EDGE_SAFETY)
    expect(snapFabToEdge(1000, VP)).toBe(VP.width - FAB_SIZE - FAB_EDGE_SAFETY)
  })
})

describe('chat fab drag threshold', () => {
  it('treats moves under 8px as a click', () => {
    expect(isFabDrag(7, 0)).toBe(false)
    expect(isFabDrag(0, -7)).toBe(false)
    expect(isFabDrag(3, 3)).toBe(false)
  })
  it('treats moves at or beyond 8px as a drag', () => {
    expect(isFabDrag(8, 0)).toBe(true)
    expect(isFabDrag(0, -8)).toBe(true)
    expect(isFabDrag(6, 6)).toBe(true)
  })
})

describe('chat fab per-user localStorage', () => {
  it('round-trips the saved slot under the user-keyed bucket', () => {
    const store = memoryStorage()
    writeFabPosition(store, 7, { left: 300, top: 400 })
    expect(readFabPosition(store, 7)).toEqual({ left: 300, top: 400 })
    // other users never see this bucket (personal preference isolation)
    expect(readFabPosition(store, 8)).toBeNull()
  })

  it('drops unreadable payloads instead of trusting them', () => {
    const store = memoryStorage()
    store.dump.set('tp_chat_fab:7', 'not-json')
    expect(readFabPosition(store, 7)).toBeNull()
    store.dump.set('tp_chat_fab:7', JSON.stringify({ left: 'x', top: 5 }))
    expect(readFabPosition(store, 7)).toBeNull()
  })

  it('reset clears the bucket so the default slot renders again', () => {
    const store = memoryStorage()
    writeFabPosition(store, 7, { left: 300, top: 400 })
    writeFabPosition(store, 7, null)
    expect(readFabPosition(store, 7)).toBeNull()
    expect(store.dump.size).toBe(0)
  })

  it('threshold constant matches the adjudication floor', () => {
    expect(FAB_DRAG_THRESHOLD).toBe(8)
  })
})
