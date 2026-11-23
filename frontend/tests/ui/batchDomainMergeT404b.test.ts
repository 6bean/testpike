/**
 * AN-2 execution-domain merge (half B) layout contract (RED first).
 *
 * Ruling P-1: the schedule-fires page (timed-trigger ledger) is merged into
 * the batches page as a tab instead of living as its own menu leaf — the
 * batch journey (create batch / template schedule -> did it actually fire)
 * reads as one story, so the ledger rides next to the batches it produced.
 * Same three invariants as half A (executionDomainMergeT404):
 * - the retired /schedule/fires path keeps a redirect route to
 * /batches?tab=fires (AN red line: removing a menu leaf never removes
 * the deep link — bookmarks and old help anchors must not 404);
 * - the batches page hosts ScheduleFireListView by component import
 * (reuse), never by copying its table markup (no second source);
 * - the fires view drops its inner page-card in embedded mode (the host
 * card already frames it — no frame-in-frame) and its tab state rides
 * the URL query, same protocol as the execution page.
 *
 * No permission gate: the fires list carries no route perm key today
 * (backend single-point read guard), so the tab is visible to every role
 * that sees the batches page — visibility semantics unchanged (AN red line
 * "permission keys zero change").
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

import { routes } from '../../src/router/routes'

const SRC = join(__dirname, '..', '..', 'src')

function findRoute(list: readonly unknown[], path: string): Record<string, unknown> | undefined {
  for (const item of list) {
    const rec = item as Record<string, unknown>
    if (rec.path === path) return rec
    if (Array.isArray(rec.children)) {
      const hit = findRoute(rec.children, path)
      if (hit) return hit
    }
  }
  return undefined
}

describe('T404 执行域合并半刀B（fires→/batches tab）路由与组件复用契约', () => {
  it('/schedule/fires 路由退役为 redirect→/batches?tab=fires（摘叶不断深链）', () => {
    const fires = findRoute(routes as readonly unknown[], 'schedule/fires')
    expect(fires, 'routes.ts 缺 schedule/fires 条目（redirect 也必须占位）').toBeTruthy()
    expect(fires!.redirect).toBe('/batches?tab=fires')
    expect(fires!.component, 'redirect 路由不得再挂组件').toBeUndefined()
  })

  it('/batches 页通过 import 复用 ScheduleFireListView（禁复制留痕表 markup=第二真源）', () => {
    const src = readFileSync(join(SRC, 'views', 'batch', 'BatchListView.vue'), 'utf8')
    expect(src, '批次页必须 import ScheduleFireListView 组件复用').toContain(
      "import ScheduleFireListView from '@/views/schedule/ScheduleFireListView.vue'",
    )
    expect(src).toContain('<ScheduleFireListView')
    // Tab identity rides the URL query so a shared link lands on the right tab.
    expect(src, 'tab 态必须消费 route query（进页即定位 tab）').toMatch(/route\.query\.tab|query\.tab/)
  })

  it('留痕页 embedded 模式摘内层 page-card（宿主卡已框=禁框中框），独立形态语义保留', () => {
    const src = readFileSync(join(SRC, 'views', 'schedule', 'ScheduleFireListView.vue'), 'utf8')
    expect(src, '留痕页必须声明 embedded prop 供宿主 tab 复用').toMatch(/embedded\?:\s*boolean/)
    expect(src, '内层卡片必须按 embedded 切换（禁无条件 page-card）').toMatch(
      /embedded\s*\?\s*'[\w-]+-embedded'\s*:\s*'page-card'/,
    )
  })
})
