/**
 * Client-side paging over a segment the server already returns in full.
 *
 * Two pages (program list, program-set quality dashboard) read a complete
 * stay usable at 50~200 rows) is served by paging in the browser instead of a
 * second server round-trip. The window arithmetic lives here once (R40):
 * pages bind `paged` to their table and mount the pager only when one page
 * cannot hold everything (zero disturbance below the first page size).
 */
import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'

/** Page-size ladder shared by every client-paged table (default first). */
export const DEFAULT_PAGE_SIZES = [20, 50, 100]

export interface ClientPagination<T> {
  /** 1-based current page (bound to el-pagination v-model:current-page). */
  pageNo: Ref<number>
  /** Current page size (bound to el-pagination v-model:page-size). */
  pageSize: Ref<number>
  /** Ladder offered by the size selector. */
  pageSizes: number[]
  /** Rows of the current window — bind this to the table, never the full segment. */
  paged: ComputedRef<T[]>
  /** Total rows behind the window (pager label + empty-state decisions). */
  total: ComputedRef<number>
  /** Pager visibility: hidden entirely while one page holds everything. */
  showPager: ComputedRef<boolean>
  /** Snap back to page 1 (call after a reload that replaces the segment). */
  resetPage: () => void
}

export function useClientPagination<T>(
  source: () => T[],
  sizes: number[] = DEFAULT_PAGE_SIZES,
): ClientPagination<T> {
  const ladder = sizes.length > 0 ? sizes : DEFAULT_PAGE_SIZES
  const pageSize = ref(ladder[0])
  const pageNo = ref(1)
  const total = computed(() => source().length)
  const paged = computed(() => {
    const start = (pageNo.value - 1) * pageSize.value
    return source().slice(start, start + pageSize.value)
  })
  const showPager = computed(() => total.value > pageSize.value)
  // A shrinking segment (narrower page, filtered result, empty reload) must never
  // leave the table rendering an out-of-range window — snap back to page 1.
  watch([total, pageSize], () => {
    if ((pageNo.value - 1) * pageSize.value >= total.value) pageNo.value = 1
  })
  function resetPage(): void {
    pageNo.value = 1
  }
  return { pageNo, pageSize, pageSizes: ladder, paged, total, showPager, resetPage }
}
