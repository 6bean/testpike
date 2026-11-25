
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useProjectContextStore } from '@/stores/projectContext'

const mounted: { unmount: () => void }[] = []


const Harness = defineComponent({
  setup() {
    const { projectId, syncToContext } = useProjectPageContext()
    return { projectId, syncToContext }
  },
  render() {
    return h('div', { 'data-test': 'ctx-harness' }, [h('span', { 'data-test': 'ctx-pid' }, String(this.projectId ?? ''))])
  },
})

async function mountAt(path: string, seedIds: number[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const ctx = useProjectContextStore()
  ctx.select(seedIds)
  const router: Router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: Harness }],
  })
  await router.push(path)
  await router.isReady()
  const w = mount(Harness, { global: { plugins: [pinia, router] } })
  mounted.push(w)
  await flushPromises()
  return { w, ctx, router }
}

afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
})

describe('单选页初始化：URL query 优先链（刀3）', () => {
  it('URL 带合法 projectId=优先消费且不回写（分享链接语义保留）', async () => {
    const { w, router } = await mountAt('/tags?projectId=42', [7, 8])
    expect(w.get('[data-test="ctx-pid"]').text()).toBe('42')
    expect(router.currentRoute.value.query.projectId).toBe('42')
  })

  it('URL 缺参=消费上下文 primaryId 并回写 query（可分享性）', async () => {
    const { w, router } = await mountAt('/tags', [7, 8])
    expect(w.get('[data-test="ctx-pid"]').text()).toBe('7')
    expect(router.currentRoute.value.query.projectId).toBe('7')
  })

  it('URL 脏参按缺参处理（防 ?projectId=abc 劫持上下文消费位）', async () => {
    const { w, router } = await mountAt('/tags?projectId=abc', [7])
    expect(w.get('[data-test="ctx-pid"]').text()).toBe('7')
    expect(router.currentRoute.value.query.projectId).toBe('7')
  })

  it('URL 与上下文双空=诚实缺席 null 不回写（无项目态由页面空态指路）', async () => {
    const { w, router } = await mountAt('/tags', [])
    expect(w.get('[data-test="ctx-pid"]').text()).toBe('')
    expect(router.currentRoute.value.query.projectId).toBeUndefined()
  })
})

describe('页内切换：只写 store 单源 + query 同步（刀2）', () => {
  it('选中新项目→上下文变 [id]（头部摘要态自动一致）且 query 跟随', async () => {
    const { w, ctx, router } = await mountAt('/cases?projectId=7', [7, 8])
    w.vm.projectId = 9
    await nextTick()
    w.vm.syncToContext()
    await nextTick()
    await flushPromises()
    expect(ctx.selectedIds).toEqual([9])
    expect(router.currentRoute.value.query.projectId).toBe('9')
  })

  it('清空选择（null）=URL 参数摘除且上下文不误清空（取消选择≠清全站）', async () => {
    const { w, ctx, router } = await mountAt('/cases?projectId=7', [7])
    w.vm.projectId = null
    await nextTick()
    w.vm.syncToContext()
    await nextTick()
    await flushPromises()
    expect(ctx.selectedIds).toEqual([7])
    expect(router.currentRoute.value.query.projectId).toBeUndefined()
  })
})

describe('上下文水合不劫持显式参（§5a 规则3 时序面，E2E 实测回归锚）', () => {
  it('挂载后 MainLayout 二次水合（loadForUser 同内容重读）不得改写本页显式 ?projectId 消费位', async () => {
    const { w, ctx, router } = await mountAt('/tags?projectId=42', [7])
    ctx.loadForUser()
    await nextTick()
    await flushPromises()
    expect(w.get('[data-test="ctx-pid"]').text()).toBe('42')
    expect(router.currentRoute.value.query.projectId).toBe('42')
  })

  it('上下文真实变化（用户头部改选）仍驱动跟随（水合幂等不得吞真改动）', async () => {
    const { w, ctx } = await mountAt('/tags?projectId=42', [7])
    ctx.select([9])
    await nextTick()
    await flushPromises()
    expect(w.get('[data-test="ctx-pid"]').text()).toBe('9')
  })
})

describe('同路由深链切换：query projectId 变更必重解析（组件复用面 URL 优先）', () => {
  it('复用组件内跳转另一项目的深链→消费位与头部上下文跟随新项目', async () => {
    const { w, ctx, router } = await mountAt('/mock/spaces/28?projectId=20', [20])
    await router.push('/mock/spaces/29?projectId=21')
    await nextTick()
    await flushPromises()
    expect(w.get('[data-test="ctx-pid"]').text()).toBe('21')
    expect(ctx.selectedIds).toEqual([21])
    expect(router.currentRoute.value.query.projectId).toBe('21')
  })

  it('深链切回缺参路由=回落上下文首位并回写（不留旧项目残值）', async () => {
    const { w, ctx, router } = await mountAt('/tags?projectId=42', [7])
    await router.push('/tags')
    ctx.select([9])
    await nextTick()
    await flushPromises()
    expect(w.get('[data-test="ctx-pid"]').text()).toBe('9')
    expect(router.currentRoute.value.query.projectId).toBe('9')
  })
})
