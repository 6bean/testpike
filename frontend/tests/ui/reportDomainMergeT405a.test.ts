/**
 * AN-3 report-domain merge (half A) layout contract (RED first).
 *
 * Ruling (AN design doc row): the standalone coverage-matrix page is
 * merged into the reports page as a tab — the requirement-coverage blind
 * spots and the coverage-rate board are one story, so readers switch tabs
 * instead of switching menu entries. Same invariants as the precedents:
 * - the retired /coverage path keeps a redirect route that LANDS ON THE TAB
 * and PRESERVES the incoming query (projectId deep links from the AI
 * generate page and from E2E/⌘K must not be dropped — hence a functional
 * redirect, not a static string, which would lose the caller's query);
 * - the reports page hosts CoverageMatrixView by component import (reuse),
 * never by copying its matrix markup (no second source of the matrix);
 * - the matrix view drops its inner page-card in embedded mode (the host
 * card already frames it — no frame-in-frame), same switch as the fires
 * and process views;
 * - tab state rides the URL query (?tab=coverage) for refresh-stable
 * sharing, mirroring the execution/batches pages.
 *
 * Permission keys: zero change (AN red line). Both pages carried no route
 * perm key before the merge (backend single-point guards), so the coverage
 * tab is visible to every role that sees the reports page.
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

describe('T405 报告域合并半刀A（coverage→/report tab）路由与组件复用契约', () => {
  it('/coverage 路由退役为函数 redirect→/report?tab=coverage 且保留调用方 query（摘叶不断深链）', () => {
    const cov = findRoute(routes as readonly unknown[], 'coverage')
    expect(cov, 'routes.ts 缺 coverage 条目（redirect 也必须占位）').toBeTruthy()
    expect(cov!.component, 'redirect 路由不得再挂组件').toBeUndefined()
    expect(typeof cov!.redirect, '深链带 ?projectId 必须函数式 redirect 透传 query（静态串会丢参）').toBe('function')
    const target = (cov!.redirect as (to: { query: Record<string, string> }) => unknown)(
      { query: { projectId: '42' } },
    ) as { path: string; query: Record<string, string | undefined> }
    expect(target.path).toBe('/report')
    expect(target.query.tab).toBe('coverage')
    expect(target.query.projectId).toBe('42')
  })

  it('/report 页通过 import 复用 CoverageMatrixView（禁复制矩阵 markup=第二真源）', () => {
    const src = readFileSync(join(SRC, 'views', 'report', 'ReportView.vue'), 'utf8')
    expect(src, '报表页必须 import CoverageMatrixView 组件复用').toContain(
      "import CoverageMatrixView from '@/views/coverage/CoverageMatrixView.vue'",
    )
    expect(src).toContain('<CoverageMatrixView')
    // Tab identity rides the URL query so a shared link lands on the right tab.
    expect(src, '页级 tab 态必须消费 route query（进页即定位 tab）').toMatch(/route\.query\.tab|query\.tab/)
  })

  it('矩阵页 embedded 模式摘内层 page-card（宿主卡已框=禁框中框），独立形态语义保留', () => {
    const src = readFileSync(join(SRC, 'views', 'coverage', 'CoverageMatrixView.vue'), 'utf8')
    expect(src, '矩阵页必须声明 embedded prop 供宿主 tab 复用').toMatch(/embedded\?:\s*boolean/)
    expect(src, '内层卡片必须按 embedded 切换（禁无条件 page-card）').toMatch(
      /embedded\s*\?\s*'[\w-]+-embedded'\s*:\s*'page-card'/,
    )
  })

  it('菜单叶退役：coverage 不再出现在 menuStructure（重定向仍在路由面保深链）', () => {
    const src = readFileSync(join(SRC, 'config', 'menuStructure.ts'), 'utf8')
    expect(src, '覆盖矩阵叶已并入 /report tab，菜单不得留双入口').not.toContain("'/coverage'")
  })
})
