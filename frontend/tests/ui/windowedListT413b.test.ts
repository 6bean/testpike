/**
 * stage-3: generic window-slice composable for non-table lists.
 *
 * The API-test rail renders request rows as ul/li inside its own scrolling
 * box, so the el-table virtualiser (useVirtualRows, span-method/row-style)
 * does not apply. This composable keeps ONE piece of scroll state (the DOM
 * container's scrollTop) and derives the render window from it — the rail
 * must never mount 200 real rows just to show ~17.
 *
 * Pure-logic layer (node): window math, pad heights, clamping, row-pitch
 * measurement. Real-browser behaviour is asserted by the worst-scale E2E.
 */
import { ref } from 'vue'
import { describe, expect, it } from 'vitest'
import { DEFAULT_ROW_PITCH, useWindowedList } from '../../src/composables/useWindowedList'

function source200() {
  return ref(Array.from({ length: 200 }, (_, i) => ({ id: i + 1 })))
}

describe('windowed list: worst-scale window math', () => {
  it('renders only the viewport window (+buffer) for a 200-row source at scroll top', () => {
    const src = source200()
    const el = ref({ scrollTop: 0, clientHeight: 320 })
    const w = useWindowedList(src, { scroller: () => el.value, rowPitch: 36, buffer: 4 })
    w.sync()
    // window = ceil(320/36) + 2*4 = 9 + 8 = 17 rows, never the full 200
    expect(w.visible.value.length).toBe(17)
    expect(w.visible.value[0]).toBe(src.value[0])
    expect(w.padTop.value).toBe(0)
    expect(w.padBottom.value).toBe((200 - 17) * 36)
  })

  it('follows the scroll position: mid-list scroll shifts the window', () => {
    const src = source200()
    const el = ref({ scrollTop: 3600, clientHeight: 320 })
    const w = useWindowedList(src, { scroller: () => el.value, rowPitch: 36, buffer: 4 })
    w.sync()
    expect(w.start.value).toBe(96)
    expect(w.visible.value[0]).toBe(src.value[96])
    expect(w.padTop.value).toBe(96 * 36)
    expect(w.padBottom.value).toBe((200 - 113) * 36)
  })

  it('scroll to the bottom always exposes the LAST row (no phantom-covered tail)', () => {
    const src = source200()
    const el = ref({ scrollTop: 200 * 36 - 320, clientHeight: 320 })
    const w = useWindowedList(src, { scroller: () => el.value, rowPitch: 36, buffer: 4 })
    w.sync()
    expect(w.visible.value[w.visible.value.length - 1]).toBe(src.value[199])
    expect(w.padBottom.value).toBe(0)
  })

  it('small sources pass through untouched (honesty: no pads when everything fits)', () => {
    const src = ref([
      { id: 1 },
      { id: 2 },
      { id: 3 },
    ])
    const el = ref({ scrollTop: 0, clientHeight: 320 })
    const w = useWindowedList(src, { scroller: () => el.value, rowPitch: 36 })
    w.sync()
    expect(w.visible.value.length).toBe(3)
    expect(w.padTop.value).toBe(0)
    expect(w.padBottom.value).toBe(0)
  })

  it('missing scroller is a no-op, never a crash (empty-state v-if window)', () => {
    const src = source200()
    const w = useWindowedList(src, { scroller: () => null, rowPitch: 36 })
    w.sync()
    expect(w.visible.value.length).toBeGreaterThan(0)
  })

  it('measurePitch corrects the fallback from real DOM rows (>2px drift only)', () => {
    const src = source200()
    const w = useWindowedList(src, { scroller: () => null, rowPitch: DEFAULT_ROW_PITCH })
    w.measurePitch([{ offsetHeight: 36 }])
    expect(w.pitch.value).toBe(36)
    w.measurePitch([{ offsetHeight: 37 }])
    expect(w.pitch.value).toBe(36)
    w.measurePitch([])
    expect(w.pitch.value).toBe(36)
  })

  it('measurePitch ignores EXPANDED rows (user report 11-20: blank band + jitter in every tail window)', () => {
    // Tail rows collapse to ~23px; one row with an open trail block sits at
    // ~217px. The old first-two-rows stride measured 217 whenever the expanded
    // row landed at the window head: padTop = start*217 inflated past the real
    // content (blank spacer band while scrolling up) and the value snapped
    // back next scroll (jitter loop). Min adjacent stride keeps the true
    // collapsed pitch no matter where the tall row sits.
    const src = source200()
    const w = useWindowedList(src, { scroller: () => null, rowPitch: DEFAULT_ROW_PITCH })
    const collapsed = Array.from({ length: 12 }, (_, i) => ({ offsetHeight: 23, offsetTop: i * 23 }))
    // expanded row FIRST: the old code read 217 here
    w.measurePitch([{ offsetHeight: 217, offsetTop: 0 }, ...collapsed.slice(1).map((r, i) => ({ offsetHeight: 23, offsetTop: 217 + (i + 1) * 23 }))])
    expect(w.pitch.value).toBe(23)
    // expanded row in the MIDDLE
    const mixed = collapsed.map((r, i) => (i === 5 ? { offsetHeight: 217, offsetTop: r.offsetTop } : r))
    w.measurePitch(mixed)
    expect(w.pitch.value).toBe(23)
  })

  it('resetWindow returns to the top and clamps within a shrinking source', () => {
    const src = source200()
    const el = ref({ scrollTop: 6000, clientHeight: 320 })
    const w = useWindowedList(src, { scroller: () => el.value, rowPitch: 36, buffer: 4 })
    w.sync()
    expect(w.start.value).toBeGreaterThan(0)
    w.resetWindow()
    expect(w.start.value).toBe(0)
    // filter the source down under the current start: sync must clamp, not blank
    src.value = src.value.slice(0, 10)
    el.value.scrollTop = 6000
    w.sync()
    expect(w.visible.value.length).toBe(10)
    expect(w.padBottom.value).toBe(0)
  })

  it('bottom snap survives an OVERESTIMATED pitch (variable-height rows: the tail must never hide behind the spacer)', () => {
    // Chat-panel geometry measured live (2026-11-01 tail-render probe): the
    // pitch was corrected from one tall row (164px) while the rendered rows
    // average ~90px, and the box sat exactly at the bottom (dist = 0).
    // The old math derived anchored = floor(3861/164) - 6 = 17 < maxStart =
    // 34 - 16 = 18, so the brand-new running-assistant row hid behind a
    // 164px bottom spacer and no further scroll event could ever release it.
    const src = ref(Array.from({ length: 34 }, (_, i) => ({ id: i + 1 })))
    const el = ref({ scrollTop: 3861, clientHeight: 653, scrollHeight: 4514 })
    const w = useWindowedList(src, { scroller: () => el.value, rowPitch: 164, buffer: 6 })
    w.sync()
    expect(w.visible.value[w.visible.value.length - 1]).toBe(src.value[33])
    expect(w.padBottom.value).toBe(0)
  })
})

describe('variable-height path (11-20 tail blank/jitter second layer)', () => {
  it('pads come from REAL cumulative heights, not one averaged stride', () => {
    // 200 collapsed rows at 23px with 3 expanded neighbours at 217px inside
    // the window head: the old uniform pitch priced the whole head at 23px,
    // so every window-edge crossing rescaled scrollHeight mid-scroll (the
    // observed 1067↔1710 jump = momentary blank while scrolling up).
    const src = ref(Array.from({ length: 200 }, (_, i) => ({ id: i + 1 })))
    const tall = new Set([0, 1, 2])
    const el = ref({ scrollTop: 0, clientHeight: 260 })
    const w = useWindowedList(src, {
      scroller: () => el.value,
      rowPitch: 23,
      buffer: 4,
      rowHeight: (i) => (tall.has(i) ? 217 : 23),
    })
    w.sync()
    expect(w.padTop.value).toBe(0)
    // total = 3*217 + 197*23 = 5230
    const total = w.padTop.value + w.visible.value.reduce((a, _r, vi) => a + (tall.has(vi + w.start.value) ? 217 : 23), 0) + w.padBottom.value
    expect(total).toBe(3 * 217 + 197 * 23)
  })

  it('scroll anchor binary-searches offsets: a scrolled-past expanded row does not skew the window', () => {
    const src = ref(Array.from({ length: 100 }, (_, i) => ({ id: i + 1 })))
    // row 0 expanded (217), rest 23
    const w0 = useWindowedList(src, {
      scroller: () => ({ scrollTop: 217 + 10 * 23, clientHeight: 260 }),
      rowPitch: 23,
      buffer: 4,
      rowHeight: (i) => (i === 0 ? 217 : 23),
    })
    w0.sync()
    // anchored row under scrollTop = index 11, minus buffer -> start 7
    expect(w0.start.value).toBe(7)
    // padTop exactly equals real offset of row 7 (217 + 6*23 = 355)
    expect(w0.padTop.value).toBe(355)
  })

  it('offsetOf returns exact content offset and bumpGeometry re-anchors', () => {
    const src = ref(Array.from({ length: 50 }, (_, i) => ({ id: i + 1 })))
    const heights = new Map<number, number>()
    const el = ref({ scrollTop: 0, clientHeight: 260 })
    const w = useWindowedList(src, {
      scroller: () => el.value,
      rowPitch: 23,
      buffer: 4,
      rowHeight: (i) => heights.get(i) ?? null,
    })
    expect(w.offsetOf(10)).toBe(230)
    heights.set(2, 217)
    w.bumpGeometry()
    expect(w.offsetOf(10)).toBe(230 - 23 + 217)
  })

  it('hosts without a rowHeight resolver keep the exact old arithmetic', () => {
    const src = ref(Array.from({ length: 200 }, (_, i) => ({ id: i + 1 })))
    const el = ref({ scrollTop: 360, clientHeight: 320 })
    const w = useWindowedList(src, { scroller: () => el.value, rowPitch: 36, buffer: 4 })
    w.sync()
    expect(w.padTop.value).toBe(w.start.value * 36)
    expect(w.padBottom.value).toBe((200 - Math.min(w.start.value + w.windowSize.value, 200)) * 36)
  })
})
