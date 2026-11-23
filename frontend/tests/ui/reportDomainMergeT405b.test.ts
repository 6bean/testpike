/**
 * AN-3 report-domain merge (half B) layout contract (RED first).
 *
 * Ruling (AN design doc row): the standalone report-center page is
 * merged into the reports page as the third tab (exports & snapshots) — the
 * snapshot ledger, its detail drawer and CSV/PDF exports belong to the same
 * story as the board and the coverage matrix, so readers switch tabs instead
 * of switching menu entries. Same invariants as the /T405a precedents:
 * - the retired /report-center path keeps a redirect route that LANDS ON
 * THE TAB and PRESERVES the incoming query (bookmarks and ⌘K deep links
 * must not be dropped — hence a functional redirect, not a static string);
 * - the reports page hosts ReportCenterView by component import (reuse),
 * never by copying its ledger markup (no second source of the ledger);
 * - the center view drops its inner page-card in embedded mode (the host
 * card already frames it — no frame-in-frame), same switch as the
 * coverage-matrix view from half A;
 * - tab state rides the URL query (?tab=export) for refresh-stable
 * sharing, mirroring the execution/batches pages.
 *
 * Permission keys: zero change (AN red line). The route carried no perm key
 * before the merge (backend single-point guards: cross-project list and
 * manual regenerate are ADMIN-side checks), so the tab is visible to every
 * role that sees the reports page. Evidence kept here instead of a gate.
 *
 * Scope-filter note: the ledger keeps its in-page project filter (rc-scope)
 * because that filter is the report SCOPE dimension itself (cross-project
 * ADMIN view / member single-project view, page-filter-self-managed lineage)
 * — not a second header-context selector. The host board ProjectSelect only
 * renders on the board tab, so the two never share the screen.
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

describe('T405 报告域合并半刀B（report-center→/report 导出与快照 tab）路由与组件复用契约', () => {
  it('/report-center 路由退役为函数 redirect→/report?tab=export 且保留调用方 query（摘叶不断深链）', () => {
    const rc = findRoute(routes as readonly unknown[], 'report-center')
    expect(rc, 'routes.ts 缺 report-center 条目（redirect 也必须占位）').toBeTruthy()
    expect(rc!.component, 'redirect 路由不得再挂组件').toBeUndefined()
    expect(typeof rc!.redirect, '深链带 query 必须函数式 redirect 透传（静态串会丢参）').toBe('function')
    const target = (rc!.redirect as (to: { query: Record<string, string> }) => unknown)(
      { query: { scopeId: '7' } },
    ) as { path: string; query: Record<string, string | undefined> }
    expect(target.path).toBe('/report')
    expect(target.query.tab).toBe('export')
    expect(target.query.scopeId).toBe('7')
  })

  it('/report 页通过 import 复用 ReportCenterView（禁复制台账 markup=第二真源），export tab 消费 URL query', () => {
    const src = readFileSync(join(SRC, 'views', 'report', 'ReportView.vue'), 'utf8')
    expect(src, '报表页必须 import ReportCenterView 组件复用').toContain(
      "import ReportCenterView from '@/views/report-center/ReportCenterView.vue'",
    )
    expect(src).toContain('<ReportCenterView')
    // The export tab value must be reachable from the URL query (redirect +
    // ⌘K deep links land on the right tab).
    expect(src, "页级 tab 必须识别 ?tab=export").toMatch(/tab\s*===\s*'export'|'export'\s*\?|\|\s*'export'/)
  })

  it('报表中心页 embedded 模式摘内层 page-card（宿主卡已框=禁框中框），独立形态语义保留', () => {
    const src = readFileSync(join(SRC, 'views', 'report-center', 'ReportCenterView.vue'), 'utf8')
    expect(src, '报表中心必须声明 embedded prop 供宿主 tab 复用').toMatch(/embedded\?:\s*boolean/)
    expect(src, '内层卡片必须按 embedded 切换（禁无条件 page-card）').toMatch(
      /embedded\s*\?\s*'[\w-]+-embedded'\s*:\s*'page-card'/,
    )
  })

  it('菜单叶退役：report-center 不再出现在 menuStructure（重定向仍在路由面保深链）', () => {
    const src = readFileSync(join(SRC, 'config', 'menuStructure.ts'), 'utf8')
    expect(src, '报表中心叶已并入 /report tab，菜单不得留双入口').not.toContain("'/report-center'")
  })
})
