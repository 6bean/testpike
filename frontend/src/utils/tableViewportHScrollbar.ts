/**
 * Viewport-resident horizontal scrollbar for wide list tables
 * (user order 2026-11-09: the bottom horizontal scrollbar must float at the
 * viewport's lower edge, not scroll away with the rows).
 *
 * Why a proxy element: every Element Plus table root is `overflow: hidden`,
 * so a CSS `position: sticky` bar inside the table can never escape the
 * table's own box — it would stay pinned to the table bottom (off-screen on
 * long tables). The enhancer therefore renders one proxy bar per overflowing
 * table, fixed to the viewport bottom only while the table crosses that edge,
 * and drags the real scroll container (`scrollLeft`) through it.
 *
 * Single source: this module is installed once in main.ts and applies to all
 * `.el-table` instances site-wide, across two pin surfaces — top-level page
 * tables pin to the window viewport's lower edge, and tables inside dialogs/
 * drawers pin to the lower inner edge of their own body (user order
 * 2026-11-09, : the bar must stay reachable without scrolling rows down).
 * Styling (height / z-order / inset) lives once in theme.scss. Pages must NOT
 * add local scrollbar patches
 * (guard: frontend/tests/ui/tableViewportHScrollbar.test.ts).
 */

export interface PinRect {
  top: number
  bottom: number
  left: number
  width: number
}

export interface PinState {
  pinned: boolean
  left: number
  width: number
}

/** Dialog-surface pin state: `bottom` is the proxy bar's offset from the
 *  window's lower edge that lands it on the dialog body's lower edge. */
export interface DialogPinState extends PinState {
  bottom: number
}

export interface ThumbGeometry {
  left: number
  width: number
}

/** A table counts as horizontally overflowing only past a small tolerance,
 *  so sub-pixel rounding never raises a phantom bar. */
export function hasHorizontalOverflow(scrollWidth: number, clientWidth: number): boolean {
  return scrollWidth - clientWidth > 4
}

/** Decide whether the proxy should float for one table:
 *  - no horizontal overflow → null (zero render; native bar suffices),
 *  - the scroll body already ends inside the viewport → null (its own
 *    scrollbar is reachable, no duplicate),
 *  - the body starts below the fold → null (table not visible yet),
 *  - otherwise the body crosses the viewport's lower edge → pin. */
export function computePinState(rect: PinRect, viewportHeight: number, overflowing: boolean): PinState | null {
  if (!overflowing) return null
  if (rect.bottom <= viewportHeight) return null
  if (rect.top >= viewportHeight) return null
  return { pinned: true, left: rect.left, width: rect.width }
}

/** Dialog-surface variant of computePinState: the pin edge is the visible
 *  lower edge of the dialog/drawer body instead of the window viewport.
 *  - no horizontal overflow → null (native bar suffices),
 *  - the table body ends inside the visible body → null (its own bar is
 *    already reachable — no duplicate),
 *  - the table starts below the body's visible edge → null (not shown yet),
 *  - otherwise the table crosses the body's lower edge → pin there. */
export function computeDialogPinState(
  tableRect: PinRect,
  bodyRect: PinRect,
  viewportHeight: number,
  overflowing: boolean,
): DialogPinState | null {
  if (!overflowing) return null
  if (tableRect.bottom <= bodyRect.bottom) return null
  if (tableRect.top >= bodyRect.bottom) return null
  return {
    pinned: true,
    left: tableRect.left,
    width: tableRect.width,
    bottom: Math.max(0, viewportHeight - bodyRect.bottom),
  }
}

/** Thumb width = visible fraction of the track; left = scroll ratio over the
 *  free travel. Returns null when there is nothing to scroll. */
export function computeThumbGeometry(
  clientWidth: number,
  scrollWidth: number,
  scrollLeft: number,
  trackWidth: number,
): ThumbGeometry | null {
  if (!hasHorizontalOverflow(scrollWidth, clientWidth)) return null
  const width = Math.max(24, (clientWidth / scrollWidth) * trackWidth)
  const freeTrack = trackWidth - width
  const freeScroll = scrollWidth - clientWidth
  const ratio = freeScroll > 0 ? Math.min(1, Math.max(0, scrollLeft / freeScroll)) : 0
  return { left: freeTrack * ratio, width }
}

interface TrackedTable {
  table: HTMLElement
  wrap: HTMLElement
  proxy: HTMLElement
  thumb: HTMLElement
  boundScroll: () => void
  /** Non-null for tables inside a dialog/drawer: the proxy then pins to this
   *  body's lower inner edge instead of the window viewport bottom. */
  dialogBody: HTMLElement | null
  /** Observes the dialog host's inline style (draggable moves it without
   *  firing scroll/resize); disconnected when the entry is destroyed. */
  hostObserver: MutationObserver | null
  /** Dialog/drawer host whose inner scroll and open animation must re-pin the
   *  proxy: a right-sliding drawer moves the wrap box through a transform,
   *  which fires neither the wrap's scroll event nor a style mutation, and
   *  the body's vertical scroll happens above the wrap, not inside it. */
  dialogHost: HTMLElement | null
}

interface ScrollSurface {
  mode: 'page' | 'dialog' | 'detached'
  body: HTMLElement | null
  host: HTMLElement | null
}

const TRACKED = new WeakMap<HTMLElement, TrackedTable>()
const pending: TrackedTable[] = []
let rafId = 0
let scanScheduled = false
let globalHooksInstalled = false

/** Route one table to its pin surface: dialog/drawer bodies ( dialog
 * mode), the window viewport (page mode), or detached overlay content
 * (poppers and similar teleported layers keep their tiny native bars). */
function scrollSurfaceFor(table: HTMLElement): ScrollSurface {
  const body = table.closest<HTMLElement>('.el-dialog__body, .el-drawer__body')
  if (body) {
    return { mode: 'dialog', body, host: table.closest<HTMLElement>('.el-dialog, .el-drawer') }
  }
  if (table.closest('.el-overlay')) {
    return { mode: 'detached', body: null, host: null }
  }
  return { mode: 'page', body: null, host: null }
}

/** Hot-tunable inset so the dialog bar sits slightly above the body edge. */
function readDialogInset(): number {
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--tp-hviewport-bar-dialog-inset')
  const value = parseFloat(raw)
  return Number.isFinite(value) ? value : 6
}

function createProxy(): { proxy: HTMLElement; thumb: HTMLElement } {
  const proxy = document.createElement('div')
  proxy.className = 'tp-hviewport-bar'
  proxy.setAttribute('role', 'scrollbar')
  proxy.setAttribute('aria-orientation', 'horizontal')
  proxy.setAttribute('aria-label', '横向滚动')
  proxy.tabIndex = 0
  const thumb = document.createElement('div')
  thumb.className = 'tp-hviewport-bar__thumb'
  proxy.appendChild(thumb)
  document.body.appendChild(proxy)
  return { proxy, thumb }
}

function ensureTracked(table: HTMLElement): void {
  if (TRACKED.has(table)) return
  const surface = scrollSurfaceFor(table)
  if (surface.mode === 'detached') return
  const wrap = table.querySelector<HTMLElement>('.el-table__body-wrapper .el-scrollbar__wrap')
  if (!wrap) return
  const { proxy, thumb } = createProxy()
  proxy.hidden = true
  if (surface.mode === 'dialog') {
    proxy.classList.add('tp-hviewport-bar--dialog')
  }
  const entry: TrackedTable = {
    table,
    wrap,
    proxy,
    thumb,
    boundScroll: () => scheduleUpdate(entry),
    dialogBody: surface.mode === 'dialog' ? surface.body : null,
    hostObserver: null,
    dialogHost: null,
  }
  wrap.addEventListener('scroll', entry.boundScroll, { passive: true })
  if (surface.mode === 'dialog' && surface.host) {
    entry.dialogHost = surface.host
    // Re-pin when anything inside the dialog/drawer scrolls (the body's
    // vertical scroll moves the wrap box but is not the wrap's own event)
    // and when its open/close transition or keyframe animation settles
    // (the slide-in transform shifts the wrap box without any scroll event).
    surface.host.addEventListener('scroll', entry.boundScroll, { passive: true, capture: true })
    surface.host.addEventListener('transitionend', entry.boundScroll, true)
    surface.host.addEventListener('animationend', entry.boundScroll, true)
    // Draggable dialogs move by rewriting their inline style, which fires no
    // scroll/resize event — re-pin the bar as the dialog travels.
    entry.hostObserver = new MutationObserver(() => scheduleUpdate(entry))
    entry.hostObserver.observe(surface.host, { attributes: true, attributeFilter: ['style'] })
  }
  TRACKED.set(table, entry)
  pending.push(entry)

  // Dragging the proxy thumb drives the real scroll container.
  let grabOffset = 0
  thumb.addEventListener('pointerdown', (event: PointerEvent) => {
    event.preventDefault()
    thumb.setPointerCapture(event.pointerId)
    const thumbRect = thumb.getBoundingClientRect()
    grabOffset = event.clientX - thumbRect.left
  })
  thumb.addEventListener('pointermove', (event: PointerEvent) => {
    if (!thumb.hasPointerCapture(event.pointerId)) return
    const trackRect = proxy.getBoundingClientRect()
    const freeTrack = trackRect.width - thumb.offsetWidth
    const freeScroll = wrap.scrollWidth - wrap.clientWidth
    if (freeTrack <= 0 || freeScroll <= 0) return
    const x = Math.min(Math.max(event.clientX - trackRect.left - grabOffset, 0), freeTrack)
    wrap.scrollLeft = (x / freeTrack) * freeScroll
  })
  proxy.addEventListener('pointerdown', (event: PointerEvent) => {
    if (event.target !== proxy) return
    // Track click pages toward the pointer, like a native scrollbar trough.
    const trackRect = proxy.getBoundingClientRect()
    const thumbStart = trackRect.left + thumb.offsetLeft
    const direction = event.clientX > thumbStart + thumb.offsetWidth ? 1 : -1
    wrap.scrollLeft += direction * wrap.clientWidth * 0.9
  })
  proxy.addEventListener('keydown', (event: KeyboardEvent) => {
    const step = wrap.clientWidth * 0.25
    if (event.key === 'ArrowRight') wrap.scrollLeft += step
    else if (event.key === 'ArrowLeft') wrap.scrollLeft -= step
    else return
    event.preventDefault()
  })
}

function applyEntry(entry: TrackedTable): void {
  if (!entry.table.isConnected || !entry.wrap.isConnected || (entry.dialogBody && !entry.dialogBody.isConnected)) {
    destroyEntry(entry)
    return
  }
  const overflowing = hasHorizontalOverflow(entry.wrap.scrollWidth, entry.wrap.clientWidth)
  let state: PinState | null
  if (entry.dialogBody) {
    const dialogState = computeDialogPinState(
      entry.wrap.getBoundingClientRect(),
      entry.dialogBody.getBoundingClientRect(),
      window.innerHeight,
      overflowing,
    )
    if (dialogState) {
      entry.proxy.style.bottom = `${dialogState.bottom + readDialogInset()}px`
    }
    state = dialogState
  } else {
    state = computePinState(entry.wrap.getBoundingClientRect(), window.innerHeight, overflowing)
    entry.proxy.style.bottom = ''
  }
  if (!state) {
    entry.proxy.hidden = true
    return
  }
  entry.proxy.hidden = false
  entry.proxy.style.left = `${state.left}px`
  entry.proxy.style.width = `${state.width}px`
  const thumb = computeThumbGeometry(
    entry.wrap.clientWidth,
    entry.wrap.scrollWidth,
    entry.wrap.scrollLeft,
    state.width,
  )
  if (thumb) {
    entry.thumb.style.left = `${thumb.left}px`
    entry.thumb.style.width = `${thumb.width}px`
  }
}

function destroyEntry(entry: TrackedTable): void {
  entry.wrap.removeEventListener('scroll', entry.boundScroll)
  if (entry.dialogHost) {
    entry.dialogHost.removeEventListener('scroll', entry.boundScroll, { capture: true })
    entry.dialogHost.removeEventListener('transitionend', entry.boundScroll, true)
    entry.dialogHost.removeEventListener('animationend', entry.boundScroll, true)
    entry.dialogHost = null
  }
  entry.hostObserver?.disconnect()
  entry.hostObserver = null
  entry.proxy.remove()
  TRACKED.delete(entry.table)
  const idx = pending.indexOf(entry)
  if (idx >= 0) pending.splice(idx, 1)
}

function flushUpdates(): void {
  rafId = 0
  for (const entry of pending.slice()) applyEntry(entry)
}

function scheduleUpdate(entry?: TrackedTable): void {
  if (entry && !pending.includes(entry)) pending.push(entry)
  if (rafId) return
  rafId = window.requestAnimationFrame(flushUpdates)
}

function scanTables(): void {
  for (const table of document.querySelectorAll<HTMLElement>('.el-table')) {
    ensureTracked(table)
  }
  scheduleUpdate()
}

function scheduleScan(): void {
  if (scanScheduled) return
  scanScheduled = true
  window.requestAnimationFrame(() => {
    scanScheduled = false
    scanTables()
  })
}

/** Install once from main.ts after app mount. */
export function installTableViewportHScrollbar(): void {
  if (globalHooksInstalled || typeof document === 'undefined') return
  globalHooksInstalled = true
  scanTables()
  const observer = new MutationObserver(scheduleScan)
  observer.observe(document.body, { childList: true, subtree: true })
  window.addEventListener('resize', scheduleScan, { passive: true })
  window.addEventListener('scroll', () => scheduleUpdate(), { passive: true, capture: true })
}
