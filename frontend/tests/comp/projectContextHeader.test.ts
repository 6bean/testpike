
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

vi.mock('@/api/notification', () => ({
  listNotifications: vi.fn(async () => ({ list: [], total: 0 })),
  markAllNotificationsRead: vi.fn(async () => ({ affected: 0 })),
  markNotificationRead: vi.fn(async () => ({ affected: 0 })),
  eventLabel: (x: string) => x,
  eventTagType: () => 'info',
}))
vi.mock('@/api/help', () => ({
  listHelpDocs: vi.fn(async () => []),
  getHelpDomainDocs: vi.fn(async () => []),
}))
vi.mock('@/api/project', () => ({
  getProjects: vi.fn(async () => ({ list: [], total: 0 })),
}))

import MainLayout from '@/layouts/MainLayout.vue'
import ProjectSelect from '@/components/ProjectSelect.vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectContextStore, PROJECT_CTX_KEY_PREFIX } from '@/stores/projectContext'

const mounted: { unmount: () => void }[] = []

function makeProjects() {
  return [
    { projectId: 11, projectName: '回归演示项目', projectCode: 'DEMO' },
    { projectId: 22, projectName: '支付项目', projectCode: 'PAY' },
    { projectId: 33, projectName: '订单项目', projectCode: 'ORD' },
  ]
}

async function mountLayout(userId = 7) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { userId, username: 'admin', nickName: '管理员', roles: ['ADMIN'] }
  auth.projects = makeProjects()
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/projects', name: 'ProjectList', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', name: 'blank', component: { template: '<div />' } },
    ],
  })
  router.push('/dashboard')
  await router.isReady()
  const w = mount(MainLayout, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, i18n, pinia, router] },
  })
  mounted.push(w)
  await flushPromises()
  return w
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
})

afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
  document.body.innerHTML = ''
})

describe('头部项目上下文摘要态（T390）', () => {
  it('未选项目=摘要态显示人话空态文案（不是静默空白）', async () => {
    const w = await mountLayout()
    const trigger = w.find('[data-test="project-ctx-trigger"]')
    expect(trigger.exists()).toBe(true)
    expect(trigger.text()).toContain('未选项目')
  })

  it('单选摘要：select 多值只留首项，摘要显示「项目：A」且无「+N」噪音', async () => {
    const w = await mountLayout()
    const ctx = useProjectContextStore()
    ctx.select([11, 22, 33])
    await flushPromises()
    expect(ctx.selectedIds).toEqual([11])
    const trigger = w.find('[data-test="project-ctx-trigger"]')
    expect(trigger.text()).toContain('回归演示项目')
    expect(trigger.text()).not.toContain('+2')
  })

  it('持久化按用户分键：选择落 tp_proj_ctx_7，重挂载恢复（单选契约=只留首项）', async () => {
    const w = await mountLayout()
    const ctx = useProjectContextStore()
    ctx.select([22, 11])
    await flushPromises()
    expect(JSON.parse(localStorage.getItem(`${PROJECT_CTX_KEY_PREFIX}7`) || '[]')).toEqual([22])
    w.unmount()
    const w2 = await mountLayout()
    const ctx2 = useProjectContextStore()
    expect(ctx2.selectedIds).toEqual([22])
    expect(w2.find('[data-test="project-ctx-select"]').exists()).toBe(true)
    mounted.push(w2)
  })

  it('可见域收缩=失效剔除人话 toast 且计数复位（一次失效一次打扰）', async () => {
    const w = await mountLayout()
    const ctx = useProjectContextStore()
    ctx.select([22])
    await flushPromises()
    const auth = useAuthStore()
    // project 22 archived = whole-group replacement of the visible domain (new
    // array reference force-prunes); 11 survives as the new domain
    auth.projects = [{ projectId: 11, projectName: '回归演示项目', projectCode: 'DEMO' }]
    await flushPromises()
    await flushPromises()
    expect(ctx.selectedIds).toEqual([])
    expect(ctx.lastPrunedCount).toBe(0)
    const msgs = Array.from(document.querySelectorAll('.el-message')).map((m) => m.textContent || '')
    expect(msgs.join(' ')).toContain('不可见')
    expect(w.exists()).toBe(true)
  })

  it('头部下拉=单选形态（布局契约：零 multiple，选项源=可见项目域，点选即生效）', async () => {
    const w = await mountLayout()
    const sel = w.findComponent(ProjectSelect)
    expect(sel.exists()).toBe(true)
    expect(sel.props('multiple')).toBe(false)
    // the retired multi-select popover must not come back in any shape
    expect(w.find('.proj-ctx-panel').exists()).toBe(false)
    expect(sel.props('options')).toHaveLength(3)
    // picking a project writes the store single source immediately
    const ctx = useProjectContextStore()
    sel.vm.$emit('update:modelValue', 33)
    await flushPromises()
    expect(ctx.selectedIds).toEqual([33])
    expect(ctx.primaryId).toBe(33)
  })

  it('可见项目为空=行内指路链接（点一下直达项目页，不藏在弹层里）', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = { userId: 9, username: 'admin', nickName: '管理员', roles: ['ADMIN'] }
    auth.projects = []
    const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN, 'en-US': enUS } })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/projects', name: 'ProjectList', component: { template: '<div />' } },
        { path: '/:pathMatch(.*)*', name: 'blank', component: { template: '<div />' } },
      ],
    })
    router.push('/dashboard')
    await router.isReady()
    const pushSpy = vi.spyOn(router, 'push')
    const w = mount(MainLayout, { attachTo: document.body, global: { plugins: [ElementPlus, i18n, pinia, router] } })
    mounted.push(w)
    await flushPromises()
    // inline hint (no popover layer anymore): visible right in the header when
    // the visible domain is empty — an empty state must not hide behind a click
    const link = w.find('[data-test="project-ctx-goto"]')
    expect(link.exists()).toBe(true)
    await link.trigger('click')
    await flushPromises()
    expect(pushSpy).toHaveBeenCalledWith('/projects')
  })
})

describe('ProjectSelect multiple 扩展（单源件，禁第二份选择器）', () => {
  async function mountSelect(props: Record<string, unknown>) {
    const pinia = createPinia()
    setActivePinia(pinia)
    const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN, 'en-US': enUS } })
    const w = mount(ProjectSelect, {
      props: { ...props },
      global: { plugins: [ElementPlus, i18n] },
    })
    mounted.push(w)
    await flushPromises()
    return w
  }

  it('multiple=true 落到内部 el-select 的 multiple prop（透传契约）', async () => {
    const w = await mountSelect({ multiple: true, modelValue: [11], options: makeProjects() })
    const sel = w.findComponent({ name: 'ElSelect' })
    expect(sel.props('multiple')).toBe(true)
  })

  it('options 注入=本地选项源（头部零远程请求），随引用替换同步', async () => {
    const w = await mountSelect({ multiple: true, modelValue: [], options: makeProjects() })
    const { getProjects } = await import('@/api/project')
    expect(getProjects).not.toHaveBeenCalled()
    expect(w.findAllComponents({ name: 'ElOption' }).length).toBe(3)
    await w.setProps({ options: makeProjects().slice(0, 2) })
    await flushPromises()
    expect(w.findAllComponents({ name: 'ElOption' }).length).toBe(2)
  })

  it('multiple 回传归一为正整数数组（清空=null 视同空数组）', async () => {
    const w = await mountSelect({ multiple: true, modelValue: [11], options: makeProjects() })
    const sel = w.findComponent({ name: 'ElSelect' })
    sel.vm.$emit('update:modelValue', [11, 22])
    await flushPromises()
    const updates = w.emitted('update:modelValue') || []
    expect(updates[updates.length - 1]).toEqual([[11, 22]])
    sel.vm.$emit('update:modelValue', [])
    await flushPromises()
    const updates2 = w.emitted('update:modelValue') || []
    expect(updates2[updates2.length - 1]).toEqual([[]])
  })

  it('单选形态回归不扰动：modelValue 数字回传+autoSelectFirst 仍走远程选项', async () => {
    const w = await mountSelect({ modelValue: 5 })
    const sel = w.findComponent({ name: 'ElSelect' })
    sel.vm.$emit('update:modelValue', 8)
    await flushPromises()
    const updates = w.emitted('update:modelValue') || []
    expect(updates[updates.length - 1]).toEqual([8])
  })
})
