/**
 * Keyboard focus reveal for wide Element Plus tables with pinned columns.
 *
 * Why: when a table overflows horizontally, right-pinned (sticky) cells paint
 * over the scrollable columns beneath them. A focusable control that sits
 * partially behind that overlay — typically a sortable header's caret button —
 * passes a load-time geometry check but fails WCAG 2.4.11 (Focus Not Obscured):
 * tabbing to it leaves focus fully covered, because the browser only scrolls
 * to reveal content outside the scroll port, never content hidden below a
 * sticky sibling. Observed live on the login-state ledger at 1440x900 by the
 * experience gate (X-2 focusObscured on .caret-wrapper behind the pinned
 * action column).
 *
 * What the enhancer does: on every focusin inside a table, if the focused
 * control intersects a sticky pinned-column overlay, nudge the table's
 * horizontal scroll container just far enough to clear the overlay. Pure
 * geometry lives in revealDelta() so the contract test can pin it; pages must
 * NOT add local focus/scroll patches (guard: tests/ui/tableFocusReveal.test.ts).
 *
 * Single source: installed once from main.ts, applies to all `.el-table`
 * instances site-wide, mirroring the viewport scrollbar enhancer's posture.
 */

export interface HorzRect {
  left: number
  right: number
}

/** Breathing room between the revealed control and the overlay edge. */
export const REVEAL_MARGIN = 8

/** Horizontal scrollLeft delta that frees `elem` from the pinned overlays.
 *  `leftEdge` = right edge of the widest left-pinned overlay (or null),
 *  `rightEdge` = left edge of the narrowest right-pinned overlay (or null).
 *  Positive = scroll right, negative = scroll left, 0 = already clear. */
export function revealDelta(
  elem: HorzRect,
  leftEdge: number | null,
  rightEdge: number | null,
  margin: number = REVEAL_MARGIN,
): number {
  // Controls sitting inside the pinned column itself are exempt upstream
  // (only scrollable-body controls reach here), so anything crossing an
  // overlay edge — partially or wholly — must be pulled out of the band.
  if (rightEdge !== null && elem.right + margin > rightEdge) {
    return elem.right + margin - rightEdge
  }
  if (leftEdge !== null && elem.left - margin < leftEdge) {
    return elem.left - margin - leftEdge
  }
  return 0
}

export interface PinnedCellSample {
  side: 'left' | 'right'
  left: number
  right: number
}

/** Pure overlay-edge reduction: widest left band edge, narrowest right band
 *  edge, out of whatever sticky cells the DOM scan collected. */
export function scanPinnedEdges(samples: PinnedCellSample[]): { left: number | null; right: number | null } {
  let left: number | null = null
  let right: number | null = null
  for (const c of samples) {
    if (c.side === 'left') {
      left = left === null ? c.right : Math.max(left, c.right)
    } else {
      right = right === null ? c.left : Math.min(right, c.left)
    }
  }
  return { left, right }
}

/** Sticky edge scan: overlay bounds actually painted over the scroll area.
 *  Only cells whose computed position is `sticky` count (Element Plus drops
 *  the fixed classes without pinning when no overflow exists, and a
 *  non-sticky cell can never cover anything). */
export function pinnedEdges(
  table: Element,
  focused: Element,
): { left: number | null; right: number | null } {
  if (focused.closest('.el-table-fixed-column--left, .el-table-fixed-column--right')) {
    return { left: null, right: null }
  }
  const samples: PinnedCellSample[] = []
  for (const cell of table.querySelectorAll(
    '.el-table-fixed-column--left, .el-table-fixed-column--right',
  )) {
    if (getComputedStyle(cell).position !== 'sticky') continue
    const rect = cell.getBoundingClientRect()
    if (rect.width === 0) continue
    samples.push({
      side: cell.className.includes('el-table-fixed-column--left') ? 'left' : 'right',
      left: rect.left,
      right: rect.right,
    })
  }
  return scanPinnedEdges(samples)
}

/** The scrollable body wrapper of a table (Element Plus 2 structure). */
function scrollWrapOf(table: Element): HTMLElement | null {
  return table.querySelector<HTMLElement>('.el-table__body-wrapper .el-scrollbar__wrap')
}

/** Synchronously mirror a horizontal delta onto the native-scroll band
 * wrappers (header/footer). Why: Element Plus 2.14 only copies the body
 * scrollbar's scrollLeft into `.el-table__header-wrapper` inside the body
 * "scroll" event listener (table/style-helper.mjs syncPosition), and
 * programmatic scrollLeft changes fire that event asynchronously — a
 * hit-test taken in the same turn as our nudge would still see the old
 * header geometry (live X-2 recurrence, ). Mirroring the identical
 * delta keeps body and bands aligned so the async sync lands as a no-op. */
export function mirrorBandScroll(table: Element, delta: number): void {
  if (delta === 0) return
  for (const band of table.querySelectorAll<HTMLElement>(
    '.el-table__header-wrapper, .el-table__footer-wrapper',
  )) {
    band.scrollLeft += delta
  }
}

/** EP 2.14 makes the tbody itself clickable-focusable (tabindex=-1); a
 *  container spans the whole table width BY DESIGN, so "revealing" it is
 *  meaningless and any scroll for it would snap the bar right. Click-focus on
 *  containers is owned by tableLabelFocusGuard's body-click jump guard. */
export function isContainerFocus(tagName: string): boolean {
  return tagName === 'TABLE' || tagName === 'THEAD' || tagName === 'TBODY' || tagName === 'TFOOT' || tagName === 'TR'
}

function revealFocused(target: EventTarget | null): void {
  if (!(target instanceof Element)) return
  if (isContainerFocus(target.tagName)) return
  const table = target.closest('.el-table')
  if (!table) return
  const wrap = scrollWrapOf(table)
  if (!wrap || wrap.scrollWidth - wrap.clientWidth <= 4) return
  const edges = pinnedEdges(table, target)
  const delta = revealDelta(target.getBoundingClientRect(), edges.left, edges.right)
  if (delta === 0) return
  wrap.scrollLeft += delta
  mirrorBandScroll(table, delta)
}

export function installTableFocusReveal(root: Document | ShadowRoot = document): void {
  root.addEventListener('focusin', (e) => revealFocused(e.target), true)
}
