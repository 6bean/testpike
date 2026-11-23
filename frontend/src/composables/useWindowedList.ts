import { computed, ref, type ComputedRef, type Ref } from 'vue'

/**
 * Generic window-slice virtualiser for plain scrolling lists (ul/li, grids)
 * that are NOT el-table (the el-table variant with span-method/row-style
 * spacers lives in useVirtualRows — same window model, different host).
 *
 * Why a second piece instead of reusing useVirtualRows: that composable is
 * welded to el-table geometry (header/body table split, tbody rows). The
 * API-test rail renders request rows as li elements inside its own
 * max-height box; forcing el-table there would fork the row markup for no
 * user benefit. This composable keeps ONE scroll-state owner — the DOM
 * container's scrollTop — and derives everything (window, pad heights)
 * from it, so no duplicate scroll state ever exists (red line: no second
 * copy of scrollbook).
 *
 * Honesty rule for callers: when the whole source fits the window nothing
 * is padded (small lists keep identical DOM shape), and the window always
 * includes the bottom-most row when scrolled to the end — the tail must
 * never hide behind a spacer.
 */

/** Fallback row pitch px; corrected from real DOM rows by measurePitch(). */
export const DEFAULT_ROW_PITCH = 40

/** Row-box shape measured for pitch (HTMLElement subset). */
export interface WindowedRowBox {
  offsetHeight: number
  offsetTop?: number
}

export interface WindowedListOptions {
  /** Element carrying the scroll (the overflow box itself). `scrollHeight`
   * is read where available to recognise the true bottom; hosts that cannot
   * supply it keep the classic pitch-derived behaviour. */
  scroller: () => { scrollTop: number; clientHeight: number; scrollHeight?: number } | null
  /** Row pitch before the first real measurement. */
  rowPitch?: number
  /** Extra rows rendered above/below the visible viewport. */
  buffer?: number
  /** Variable-height hosts (11-20 tail-window report): when present, pads and
   * the scroll anchor derive from REAL cumulative row heights instead of one
   * averaged stride, so an expanded row scrolling out of the window never
   * rescales scrollHeight (that jump shifted the viewport and read as a
   * momentary blank). Return null/undefined for an unmeasured row and it
   * falls back to the current pitch. Hosts that never pass this behave
   * EXACTLY as before (uniform-pitch model). */
  rowHeight?: (index: number) => number | null | undefined
}

export function useWindowedList<T>(source: Ref<T[]>, opts: WindowedListOptions) {
  const pitch = ref(opts.rowPitch ?? DEFAULT_ROW_PITCH)
  const buffer = opts.buffer ?? 4
  const start = ref(0)
  const viewportH = ref(320)
  /** Bumped by hosts whose real row heights changed (expand/collapse) so the
   * derived prefix recomputes without mutating the source array. */
  const geometryTick = ref(0)

  /** Cumulative offsets of the full source: prefix[i] = top of row i, last
   * element = total content height. With a uniform pitch this equals the old
   * inline products (i*pitch); with real heights it is exact. */
  const prefix = computed(() => {
    void geometryTick.value
    const out: number[] = [0]
    let acc = 0
    for (let i = 0; i < source.value.length; i++) {
      const h = opts.rowHeight ? opts.rowHeight(i) : undefined
      acc += h === null || h === undefined || h <= 0 ? pitch.value : h
      out.push(acc)
    }
    return out
  })

  /** Rows in one window = viewport rows + up/down buffers (min 100px floor
   * guards the transient zero-height frames during v-if switches). */
  const windowSize = computed(() => Math.ceil(Math.max(100, viewportH.value) / pitch.value) + buffer * 2)

  /** start never exceeds len - window (clamped, never negative). */
  const end = computed(() => Math.min(start.value + windowSize.value, source.value.length))

  const visible = computed(() => source.value.slice(start.value, end.value))

  /** Spacer heights that keep the scrollbar honest for the full source. */
  const padTop = computed(() => prefix.value[start.value] ?? 0)
  const padBottom = computed(() =>
    Math.max(0, (prefix.value[prefix.value.length - 1] ?? 0) - (prefix.value[end.value] ?? 0)))

  /** Recompute the window from the live scroll position (call on @scroll
   * and after source changes). Missing scroller (unmounted/empty state) is
   * a safe no-op — the window simply stays at its clamped value. */
  function sync(): void {
    const el = opts.scroller()
    if (!el) return
    viewportH.value = el.clientHeight
    // Window anchor: binary-search the prefix for the row under scrollTop
    // (pitch model = same result as floor(scrollTop/pitch) when heights are
    // uniform; exact when expanded rows shifted the offsets).
    let anchored = 0
    if (opts.rowHeight) {
      const p = prefix.value
      let lo = 0
      let hi = source.value.length - 1
      while (lo <= hi) {
        const mid = (lo + hi) >> 1
        if (p[mid + 1] <= el.scrollTop) { lo = mid + 1 } else { hi = mid - 1 }
      }
      anchored = Math.max(0, lo - buffer)
    } else {
      anchored = Math.max(0, Math.floor(el.scrollTop / pitch.value) - buffer)
    }
    const maxStart = Math.max(0, source.value.length - windowSize.value)
    // Bottom honesty with REAL geometry: pitch is one averaged stride, and
    // variable-height rows (chat md blocks) can make it overestimate the
    // scroll → row ratio, so floor(scrollTop/pitch) lands short of maxStart
    // exactly when the reader is at the bottom — the tail then hid behind a
    // full-spacer height and NO further scroll event could release it
    // (scrollTop already clamped; the 2026-11-01 steering E2E red proved it).
    // When the box reports distance-from-bottom <= 2px, the tail is what the
    // user is looking at: snap the window to it, whatever the pitch says.
    const atBottom =
      typeof el.scrollHeight === 'number' && el.scrollHeight - el.scrollTop - el.clientHeight <= 2
    start.value = atBottom ? maxStart : Math.min(anchored, maxStart)
  }

  /** Filter/data change: back to the top so the first match is on screen. */
  function resetWindow(): void {
    start.value = 0
    const el = opts.scroller()
    if (el) el.scrollTop = 0
  }

  /** Correct the pitch from rendered rows (>2px drift only, anti-jitter).
   * Rows can be variable height (a tail row's expandable trail block, a chat
   * markdown bubble), so the FIRST-TWO-ROWS stride was poisoned whenever row
   * 0 happened to be expanded: it read ~200px instead of the collapsed ~23px,
   * windowSize collapsed and padTop = start*pitch ballooned far past the real
   * content — a blank spacer band while scrolling up, and because measurePitch
   * runs on every scroll the pitch then snapped back to the collapsed stride
   * next frame (that row scrolled away), oscillating the layout = the reported
   * nonstop jitter. Fix: the base pitch is the SMALLEST adjacent stride among
   * rendered rows — an expanded row yields a large stride and is ignored, while
   * any collapsed neighbour pins the true pitch, so it never jumps mid-scroll.
   * Falls back to the smallest row offsetHeight when only one row (no pairs) is
   * present (matches the prior single-row contract). Caller may pass rows
   * explicitly, otherwise they are found inside the scroller. */
  function measurePitch(rows?: WindowedRowBox[]): void {
    const found = (opts.scroller() as HTMLElement | null)?.querySelectorAll<HTMLElement>('[data-windowed-row]')
    const list: WindowedRowBox[] = rows ?? (found ? Array.from(found) : [])
    if (list.length === 0) return
    // Prefer the smallest adjacent offsetTop stride (collapsed-row truth).
    let stride = Number.POSITIVE_INFINITY
    for (let i = 1; i < list.length; i++) {
      const prev = list[i - 1]
      const cur = list[i]
      if (typeof cur.offsetTop === 'number' && typeof prev.offsetTop === 'number') {
        const delta = cur.offsetTop - prev.offsetTop
        if (delta > 8 && delta < stride) stride = delta
      }
    }
    // No usable pair (single row, or every neighbour expanded so no collapsed
    // stride): fall back to the smallest single-row height.
    if (!Number.isFinite(stride)) {
      for (const r of list) if (r.offsetHeight > 8 && r.offsetHeight < stride) stride = r.offsetHeight
    }
    if (Number.isFinite(stride) && Math.abs(stride - pitch.value) > 2) pitch.value = stride
  }

  /** Notify the geometry after host-side row-height changes (expand/collapse)
   * so pads and anchors recompute immediately. */
  function bumpGeometry(): void {
    geometryTick.value += 1
    sync()
  }

  /** Exact content offset of row `index` (0-based into the full source);
   * hosts use it to scroll a specific row into view under variable heights. */
  function offsetOf(index: number): number {
    const i = Math.max(0, Math.min(index, source.value.length))
    return prefix.value[i] ?? 0
  }

  return { visible, padTop, padBottom, start, pitch, windowSize, sync, resetWindow, measurePitch, bumpGeometry, offsetOf }
}
