// Wall filter wiring contract (monitor matrix page).
//
// The wall page consumes the paged server-side channel of /monitor/targets
// (status/type/keyword filters + page/pageSize), not the unbounded project
// list. The filters live in the shared filter-bar band with the create entry
// digested into the trailing action slot; the submit control sits inline right
// after the last filter condition (R21 bands). Page state is URL-restorable:
// status/type/keyword/page/size round-trip through the query string.
//
// Assertions parse the shipped source (house style for wiring contracts),
// so a later edit that drops a wiring point fails here rather than silently
// regressing the wall to client-only filtering.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(dirname(fileURLToPath(import.meta.url)), '../../src')

const view = readFileSync(join(SRC, 'views/monitor/MonitorView.vue'), 'utf8')
const api = readFileSync(join(SRC, 'api/monitor.ts'), 'utf8')

const idx = (needle: string, text: string): number => {
  const i = text.indexOf(needle)
  if (i === -1) throw new Error(`missing: ${needle}`)
  return i
}

describe('monitor wall paged channel', () => {
  it('api mirror exposes the paged wall query', () => {
    expect(api).toContain('pageMonitorTargets')
    expect(api).toContain("url: '/monitor/targets/page'")
  })

  it('view consumes the paged channel instead of the unbounded list', () => {
    expect(view).toContain('pageMonitorTargets')
    expect(view).not.toContain('listMonitorTargets')
  })

  it('filter band carries status, type and keyword controls (all searchable selects)', () => {
    const bar = idx('data-test="monitor-filter-bar"', view)
    expect(bar).toBeLessThan(idx('data-test="monitor-create-btn"', view))
    for (const anchor of ['monitor-status-filter', 'monitor-type-filter', 'monitor-keyword-input']) {
      const at = idx(`data-test="${anchor}"`, view)
      expect(at).toBeGreaterThan(bar)
      if (anchor.endsWith('filter')) {
        const open = view.lastIndexOf('<el-select', at)
        const close = view.indexOf('>', at)
        expect(view.slice(open, close)).toMatch(/(?<![\w-])filterable(?![\w-])/)
      }
    }
  })

  it('submit button is inline right after the keyword input, create rides the trailing slot', () => {
    const kw = idx('data-test="monitor-keyword-input"', view)
    const query = idx('data-test="monitor-query-btn"', view)
    const actions = idx('class="filter-bar-actions"', view)
    const create = idx('data-test="monitor-create-btn"', view)
    expect(kw).toBeLessThan(query)
    expect(query).toBeLessThan(actions)
    expect(actions).toBeLessThan(create)
  })

  it('pager exists with size options and total travels from the server', () => {
    expect(view).toContain('data-test="monitor-pager"')
    expect(view).toMatch(/layout="[^"]*sizes[^"]*"/)
    expect(view).toContain('total, sizes')
    expect(view).toContain('total.value')
  })

  it('page state round-trips through the URL query', () => {
    expect(view).toContain('route.query.status')
    expect(view).toContain('route.query.type')
    expect(view).toContain('route.query.keyword')
    expect(view).toContain('route.query.page')
    expect(view).toContain('route.query.size')
    expect(view).toContain('router.replace')
  })

  it('group heads toggle collapse with an accessible expanded state', () => {
    expect(view).toContain('data-test="monitor-group-toggle"')
    expect(view).toContain('aria-expanded')
  })

  it('filtered empty state offers the clear-filters way out', () => {
    expect(view).toContain('common.emptyFilteredResult')
    expect(view).toMatch(/:filtered=/)
  })

  it('new copy keys exist in both locales', () => {
    const zh = readFileSync(join(SRC, 'utils/locales/zh-CN.ts'), 'utf8')
    const en = readFileSync(join(SRC, 'utils/locales/en-US.ts'), 'utf8')
    for (const key of ['statusFilterPh', 'typeFilterPh', 'keywordPh']) {
      const zhAt = zh.indexOf(`monitor: {`)
      const enAt = en.indexOf(`monitor: {`)
      expect(zh.slice(zhAt)).toContain(`${key}:`)
      expect(en.slice(enAt)).toContain(`${key}:`)
    }
  })
})
