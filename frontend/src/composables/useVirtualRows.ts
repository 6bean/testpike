import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, type ComputedRef, type Ref } from 'vue'

/**
 *
 *
 *
 * :data="vs.renderData" :span-method="vs.spanMethod" :row-style="vs.rowStyle"
 */


export interface VirtualSpacerRow {
  __vspacer: number
  id: number
}

export type VirtualRow<T> = T | VirtualSpacerRow

export interface VirtualRowsOptions {
  
  enabled: ComputedRef<boolean> | Ref<boolean>
  
  scroller: () => HTMLElement | null
  
  tableEl: () => HTMLElement | null
  
  columnCount: ComputedRef<number> | Ref<number>
  
  rowHeight?: number
  
  buffer?: number
  
  storageKey?: () => string
}

export const DEFAULT_ROW_HEIGHT = 40


export function isSpacerRow(row: unknown): row is VirtualSpacerRow {
  return typeof row === 'object' && row !== null && '__vspacer' in row
}

export function useVirtualRows<T extends object>(source: Ref<T[]>, opts: VirtualRowsOptions) {
  const rowH = ref(opts.rowHeight ?? DEFAULT_ROW_HEIGHT)
  const buffer = opts.buffer ?? 6
  const start = ref(0)
  const viewportH = ref(600)

  
  const windowSize = computed(() => Math.ceil(Math.max(100, viewportH.value) / rowH.value) + buffer * 2)

  
  const end = computed(() => Math.max(start.value, Math.min(start.value + windowSize.value, source.value.length)))

  
  const renderData = computed<VirtualRow<T>[]>(() => {
    if (!opts.enabled.value) return source.value
    const rows: VirtualRow<T>[] = []
    const topPad = start.value * rowH.value
    const bottomPad = (source.value.length - end.value) * rowH.value
    if (topPad > 0) rows.push({ __vspacer: topPad, id: -1 })
    rows.push(...(source.value as T[]).slice(start.value, end.value))
    if (bottomPad > 0) rows.push({ __vspacer: bottomPad, id: -2 })
    return rows
  })

  
  function spanMethod(args: { row: unknown; columnIndex: number }): [number, number] | undefined {
    if (!opts.enabled.value || !isSpacerRow(args.row)) return undefined
    return args.columnIndex === 0 ? [1, opts.columnCount.value] : [0, 0]
  }

  
  function rowStyle(args: { row: unknown }): Record<string, string> | undefined {
    if (!opts.enabled.value || !isSpacerRow(args.row)) return undefined
    return { height: `${args.row.__vspacer}px` }
  }

  
  function rowClassName<C extends object>(caller?: (args: { row: C; rowIndex: number }) => string) {
    return (args: { row: unknown; rowIndex: number }): string => {
      if (opts.enabled.value && isSpacerRow(args.row)) return 'virtual-spacer-row'
      return caller ? caller({ row: args.row as C, rowIndex: args.rowIndex }) : ''
    }
  }

  
  function geometry(): { scrolledPast: number; visibleH: number } | null {
    const el = opts.scroller()
    const table = opts.tableEl()
    if (!el || !table) return null
    const elBox = el.getBoundingClientRect()
    const tBox = table.getBoundingClientRect()
    return {
      scrolledPast: Math.max(0, elBox.top - tBox.top),
      visibleH: Math.max(100, el.clientHeight),
    }
  }

  
  function onScroll(persist = true): void {
    if (!opts.enabled.value) return
    const geo = geometry()
    const el = opts.scroller()
    if (!geo || !el) return
    const wasAtBottom = el.scrollTop >= el.scrollHeight - el.clientHeight - 2
    const prevRowH = rowH.value
    measureRowHeight(el)
    viewportH.value = geo.visibleH
    const next = Math.max(0, Math.floor(geo.scrolledPast / rowH.value) - buffer)
    const maxStart = Math.max(0, source.value.length - windowSize.value)
    start.value = Math.min(next, maxStart)
    if (rowH.value !== prevRowH && wasAtBottom) {
      void nextTick(() =>
        requestAnimationFrame(() => {
          const s = opts.scroller()
          if (s) s.scrollTop = s.scrollHeight
        }))
    }
    if (persist && opts.storageKey) sessionStorage.setItem(opts.storageKey(), String(el.scrollTop))
  }

  
  function measureRowHeight(scope: HTMLElement): void {
    const firstReal = Array.from(scope.querySelectorAll('tbody tr'))
      .find((tr) => !tr.classList.contains('virtual-spacer-row')) as HTMLElement | undefined
    const h = firstReal?.offsetHeight
    if (h && h > 8 && Math.abs(h - rowH.value) > 2) rowH.value = h
  }

  
  function bind(): void {
    const el = opts.scroller()
    if (!el) return
    el.removeEventListener('scroll', scrollListener)
    el.addEventListener('scroll', scrollListener, { passive: true })
    onScroll(false)
  }

  
  function scrollListener(): void {
    onScroll(true)
  }

  
  function resetWindow(): void {
    start.value = 0
    if (opts.storageKey) sessionStorage.removeItem(opts.storageKey())
    const el = opts.scroller()
    if (el) el.scrollTop = 0
  }

 
  function restoreScroll(): void {
    void nextTick(() => {
      const saved = opts.storageKey ? Number(sessionStorage.getItem(opts.storageKey()) || '0') : 0
      const el = opts.scroller()
      if (el && saved) el.scrollTop = saved
      bind()
    })
  }

  function unbind(): void {
    const el = opts.scroller()
    if (el) el.removeEventListener('scroll', scrollListener)
  }

  
  function refreshLayout(tableRef: { doLayout?: () => void } | null): void {
    if (tableRef && typeof tableRef.doLayout === 'function') nextTick(() => tableRef.doLayout?.())
  }

  onMounted(() => void nextTick(bind))
  onActivated(() => bind())
  onDeactivated(unbind)
  onBeforeUnmount(unbind)

  return { renderData, spanMethod, rowStyle, rowClassName, onScroll, bind, unbind, resetWindow, restoreScroll, refreshLayout, start, rowH, windowSize }
}
