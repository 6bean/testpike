/**
 * Worst-scale and action-band contract for the program-set quality dashboard.
 *
 * Posture = disk truth (same helper shape as caseListActionBands): the page
 * templates are read from disk, no memory lists. Assertions:
 * 1. R21 bands — the dashboard filter row uses the canonical .filter-bar
 * wrapper, the window echo stays in the condition flow and the only
 * view-state action (refresh) rides the .filter-bar-actions trailing slot
 * (the legacy .toolbar wrapper was an S6 scanner blind spot);
 * and the pager sits in the canonical .pagination-bar container with a
 * size selector; a 30-project feed must never inline 30 rows at once;
 * 3. reference material folds — the caliber footnotes start collapsed behind
 * a toggle that names the entry count and carries aria-expanded;
 * 4. R40 single source — both client-paged pages consume
 * useClientPagination and neither re-implements the window arithmetic;
 * 5. the composable itself pages correctly (window sizes, last page, snap
 * back to page 1 when the segment shrinks below the current page).
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { nextTick, ref } from 'vue'
import { describe, expect, it } from 'vitest'
import { useClientPagination } from '../../src/composables/useClientPagination'

const SRC = join(__dirname, '..', '..', 'src')
const DASH = readFileSync(join(SRC, 'views/report/ProgramDashboardView.vue'), 'utf8')
const PROGRAM_LIST = readFileSync(join(SRC, 'views/program/ProgramListView.vue'), 'utf8')

/** Index of `needle` asserted to exist, so ordering checks never pass silently. */
function at(src: string, needle: string, where: string): number {
  const i = src.indexOf(needle)
  expect(i, `${where} must be present on disk`).toBeGreaterThan(-1)
  return i
}

describe('program dashboard action bands (R21)', () => {
  it('pins the view-state refresh action to the trailing slot', () => {
    at(DASH, 'class="filter-bar"', 'dashboard filter band')
    const trailing = at(DASH, 'class="filter-bar-actions"', 'dashboard trailing slot')
    const refresh = at(DASH, 'data-test="dashboard-refresh"', 'dashboard refresh anchor')
    const echo = at(DASH, 'data-test="dashboard-window"', 'dashboard window echo')
    expect(refresh, 'refresh belongs in the trailing slot, not the condition flow').toBeGreaterThan(trailing)
    expect(echo, 'the window echo is condition context and stays before the slot').toBeLessThan(trailing)
    expect(DASH).not.toContain('class="toolbar"')
    expect(DASH).not.toContain('.toolbar {')
  })
})

describe('program dashboard worst-scale posture (S 组)', () => {
  it('pages the table and keeps the pager in the canonical container', () => {
    at(DASH, ':data="pagedRows"', 'dashboard table binds the paged window')
    const bar = at(DASH, 'class="pagination-bar"', 'dashboard pagination container')
    const pager = at(DASH, '<el-pagination', 'dashboard pager')
    expect(bar, 'the pager must sit inside the .pagination-bar container').toBeLessThan(pager)
    at(DASH, 'data-test="dashboard-pagination"', 'dashboard pagination anchor')
    at(DASH, 'layout="total, sizes, prev, pager, next"', 'dashboard size selector')
    expect(DASH, 'full-slice binding would defeat the paging affordance').not.toContain(':data="rows"')
  })

  it('folds the caliber footnotes behind an honest toggle', () => {
    at(DASH, 'const calibersOpen = ref(false)', 'calibers start collapsed')
    const toggle = at(DASH, 'data-test="dashboard-caliber-fold"', 'caliber fold anchor')
    at(DASH, ':aria-expanded="calibersOpen', 'caliber fold aria state')
    const registryRow = at(DASH, 'dashboard-caliber-${c.code}', 'caliber registry row anchor')
    expect(toggle, 'the fold anchor must not share the registry-row prefix (locator exclusivity)')
      .toBeLessThan(registryRow)
    at(DASH, 't(\'programDashboard.caliberShow\', { count:', 'fold label names the entry count')
  })
})

describe('client pagination is one implementation (R40)', () => {
  it('every client-paged page consumes the shared composable', () => {
    for (const [name, src] of [['dashboard', DASH], ['program list', PROGRAM_LIST]] as const) {
      at(src, "@/composables/useClientPagination", `${name} imports the single source`)
      expect(src, `${name} must not re-implement the window arithmetic`).not.toMatch(
        /slice\(\(pageNo\.value - 1\)/,
      )
    }
  })

  it('windows the slice, honours the last page and snaps back when it shrinks', async () => {
    const all = Array.from({ length: 30 }, (_, i) => i)
    const source = ref<number[]>([...all])
    const { pageNo, pageSize, pageSizes, paged, total, showPager, resetPage } =
      useClientPagination(() => source.value)
    expect(pageSizes).toEqual([20, 50, 100])
    expect(total.value).toBe(30)
    expect(showPager.value).toBe(true)
    expect(paged.value).toEqual(all.slice(0, 20))
    pageNo.value = 2
    await nextTick()
    expect(paged.value).toEqual(all.slice(20, 30))
    // A reload that empties the segment must land back on page 1 (no blank window).
    source.value = []
    await nextTick()
    expect(pageNo.value).toBe(1)
    expect(showPager.value).toBe(false)
    source.value = [...all]
    pageSize.value = 50
    await nextTick()
    expect(paged.value.length).toBe(30)
    resetPage()
    expect(pageNo.value).toBe(1)
  })
})
