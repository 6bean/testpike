/**
 * AN-2 execution-domain merge layout contract (RED first).
 *
 * Ruling P-1: the process page (running tasks across batches) is merged into
 * the execution page as a tab instead of living as its own menu leaf. Two
 * invariants this contract pins down:
 * - the retired /process path must keep a route that redirects to
 * /execution?tab=process (zero broken external links — AN red line:
 * removing a menu leaf never removes the deep link);
 * - the execution page renders ProcessView by component reuse (import),
 * never by copying its table markup (no second source of truth).
 *
 * Disk truth source = routes.ts + view sources, same style as the
 * titleDedupContract reader (no memory lists).
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

describe('T404 执行域合并（P-1 裁决）路由与组件复用契约', () => {
  it('/process 路由退役为 redirect→/execution?tab=process（摘叶不断深链）', () => {
    const proc = findRoute(routes as readonly unknown[], 'process')
    expect(proc, 'routes.ts 缺 process 条目（redirect 也必须占位）').toBeTruthy()
    expect(proc!.redirect).toBe('/execution?tab=process')
    // A redirect route carries no component — the old page mount must be gone.
    expect(proc!.component, 'redirect 路由不得再挂组件').toBeUndefined()
  })

  it('/execution 页通过 import 复用 ProcessView（禁复制任务表 markup=第二真源）', () => {
    const src = readFileSync(join(SRC, 'views', 'execution', 'ExecutionCenterView.vue'), 'utf8')
    expect(src, '执行页必须 import ProcessView 组件复用').toContain("import ProcessView from './ProcessView.vue'")
    expect(src).toContain('<ProcessView')
    // Tab identity rides the URL query so a shared link lands on the right tab.
    expect(src, 'tab 态必须消费 route query（进页即定位 tab）').toMatch(/route\.query\.tab|query\.tab/)
  })

  it('进程 tab 权限键零改动=perm:exec:task-cancel 单源镜像（无键成员不见 tab）', () => {
    const src = readFileSync(join(SRC, 'views', 'execution', 'ExecutionCenterView.vue'), 'utf8')
    expect(src, '进程 tab 可见性必须挂 perm:exec:task-cancel（与摘除前路由 meta 同码）').toContain('perm:exec:task-cancel')
  })
})
