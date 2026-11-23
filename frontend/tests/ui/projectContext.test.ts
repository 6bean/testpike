
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import type { LoginUser, ProjectBrief } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useProjectContextStore, PROJECT_CTX_KEY_PREFIX } from '@/stores/projectContext'
import { resolveProjectRouteParam } from '@/composables/useProjectContextRoute'
import { installLocalStorageStub } from './localStorageStub'

function user(uid: number): LoginUser {
  return { userId: uid, username: `u${uid}` } as LoginUser
}

function project(pid: number): ProjectBrief {
  return { projectId: pid, projectName: `P${pid}`, projectCode: `c${pid}` }
}

let disk: Map<string, string>
let auth: ReturnType<typeof useAuthStore>
let ctx: ReturnType<typeof useProjectContextStore>

beforeEach(() => {
  disk = installLocalStorageStub()
  setActivePinia(createPinia())
  auth = useAuthStore()
  ctx = useProjectContextStore()
})

afterEach(() => {
  delete (globalThis as Record<string, unknown>).localStorage
})

describe('projectContext store 全谱', () => {
  it('T398 单选契约：select 多值只留首项（点选序），primaryId=唯一选中项', () => {
    auth.user = user(7)
    auth.projects = [project(1), project(2), project(3)]
    ctx.select([3, 1])
    expect(ctx.primaryId).toBe(3)
    expect(ctx.selectedIds).toEqual([3])
    expect(JSON.parse(disk.get(`${PROJECT_CTX_KEY_PREFIX}7`)!)).toEqual([3])
  })

  it('重复 id 去重后仍恒 ≤1 元素（多选时代的地基语义已随 T398 退役）', () => {
    auth.user = user(7)
    ctx.select([2, 5, 2, 5, 9])
    expect(ctx.selectedIds).toEqual([2])
    expect(ctx.primaryId).toBe(2)
  })

  it('空选择 primaryId=null（单选页消费位诚实缺席）', () => {
    auth.user = user(7)
    ctx.select([])
    expect(ctx.primaryId).toBeNull()
  })

  it('剪枝剔除不可见 id+计数上抛+落盘同步（失效惰性剔除，UI 层凭 lastPrunedCount 给人话 toast）', () => {
    auth.user = user(7)
    auth.projects = [project(1), project(2), project(3)]
    // : select only ever admits the first id, so an invisible id can no
    // longer enter through select — the stale-on-disk path is the real pruning
    // scenario (legacy payload whose head project went invisible).
    disk.set(`${PROJECT_CTX_KEY_PREFIX}7`, JSON.stringify([99, 1]))
    ctx.loadForUser()
    ctx.pruneToVisible()
    expect(ctx.selectedIds).toEqual([])
    expect(ctx.lastPrunedCount).toBe(1)
    expect(JSON.parse(disk.get(`${PROJECT_CTX_KEY_PREFIX}7`)!)).toEqual([])
  })

  it('可见域刷新自动触发剪枝（与 auth.projects 联动，钉版=整组替换语义）', async () => {
    auth.user = user(7)
    auth.projects = [project(1), project(2)]
    // : select([1,2]) lands as [1] (single-select); replacing the visible
    // domain with a group that no longer contains 1 force-prunes it to empty.
    ctx.select([1, 2])
    expect(ctx.selectedIds).toEqual([1])
    auth.projects = [project(2)]
    await nextTick()
    expect(ctx.selectedIds).toEqual([])
    expect(ctx.lastPrunedCount).toBe(1)
  })

  it('多账号互不串味：A 选完登出清 A 键，B 无上下文；切回 A 恢复', () => {
    auth.user = user(7)
    auth.projects = [project(1), project(2)]
    ctx.select([2, 1])
    ctx.clearOnLogout()
    expect(ctx.selectedIds).toEqual([])
    expect(disk.has(`${PROJECT_CTX_KEY_PREFIX}7`)).toBe(false)
    auth.user = user(8)
    ctx.loadForUser()
    expect(ctx.selectedIds).toEqual([])
    ctx.select([1])
    auth.user = user(7)
    ctx.loadForUser()
    expect(ctx.selectedIds).toEqual([])
    disk.set(`${PROJECT_CTX_KEY_PREFIX}7`, JSON.stringify([2, 1]))
    ctx.loadForUser()
    // : a legacy multi-select payload restores as its first item only
    expect(ctx.selectedIds).toEqual([2])
    expect(JSON.parse(disk.get(`${PROJECT_CTX_KEY_PREFIX}8`)!)).toEqual([1])
  })

  it('脏盘容错：非法 JSON/非数组/混入非数字一律不采信（坏数据不进上下文）', () => {
    auth.user = user(7)
    auth.projects = [project(3), project(6)]
    disk.set(`${PROJECT_CTX_KEY_PREFIX}7`, 'not-json{')
    ctx.loadForUser()
    expect(ctx.selectedIds).toEqual([])
    disk.set(`${PROJECT_CTX_KEY_PREFIX}7`, JSON.stringify({ a: 1 }))
    ctx.loadForUser()
    expect(ctx.selectedIds).toEqual([])
    disk.set(`${PROJECT_CTX_KEY_PREFIX}7`, JSON.stringify([3, 'x', null, 4.5, 6]))
    ctx.loadForUser()
    // : valid entries truncate to the first one (legacy multi payload migration)
    expect(ctx.selectedIds).toEqual([3])
  })

  it('非显式登出路（token 过期跳登录=user 置空）同样清该用户盘上键（watch 携带旧 id 定位）', async () => {
    auth.user = user(7)
    ctx.select([1, 2])
    expect(disk.has(`${PROJECT_CTX_KEY_PREFIX}7`)).toBe(true)
    await nextTick()
    auth.user = null
    await nextTick()
    expect(ctx.selectedIds).toEqual([])
    expect(disk.has(`${PROJECT_CTX_KEY_PREFIX}7`)).toBe(false)
  })

  it('登录态落用户自动载入该用户上下文（userId 变化驱动，免页面手动接线）', () => {
    disk.set(`${PROJECT_CTX_KEY_PREFIX}9`, JSON.stringify([4, 5]))
    auth.projects = [project(4), project(5), project(6)]
    auth.user = user(9)
    return nextTick().then(() => {
      // : legacy two-id payload auto-loads truncated to its first item
      expect(ctx.selectedIds).toEqual([4])
    })
  })
})

describe('URL query 优先链（刀3 协议成文件）', () => {
  it('query 带合法 projectId=优先消费且不回写（分享链接/书签语义保留）', () => {
    const r = resolveProjectRouteParam('42', [7, 8])
    expect(r).toEqual({ projectId: 42, writeBack: false })
  })

  it('URL 缺参=消费 primaryId 并回写 query（可分享性顺带达成）', () => {
    const r = resolveProjectRouteParam(undefined, [7, 8])
    expect(r).toEqual({ projectId: 7, writeBack: true })
  })

  it('query 非法值（非数字/负数）按缺参处理（防脏参数劫持上下文）', () => {
    expect(resolveProjectRouteParam('abc', [7]).projectId).toBe(7)
    expect(resolveProjectRouteParam('-3', [7]).projectId).toBe(7)
    expect(resolveProjectRouteParam('', [7]).projectId).toBe(7)
  })

  it('上下文也空=诚实缺席 null 不回写（无项目态由页面空态指路）', () => {
    expect(resolveProjectRouteParam(undefined, [])).toEqual({ projectId: null, writeBack: false })
    expect(resolveProjectRouteParam(undefined, null)).toEqual({ projectId: null, writeBack: false })
  })

  it('query 值不在多选上下文内仍优先（URL=显式导航意图，上下文只是缺省来源）', () => {
    expect(resolveProjectRouteParam('99', [7, 8]).projectId).toBe(99)
  })
})
