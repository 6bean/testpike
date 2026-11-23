
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
  markNotificationRead: vi.fn(async () => ({})),
  eventLabel: (x: string) => x,
  eventTagType: () => 'info',
}))
vi.mock('@/api/help', () => ({
  listHelpDocs: vi.fn(async () => []),
  getHelpDomainDocs: vi.fn(async () => []),
}))

import MainLayout from '@/layouts/MainLayout.vue'
import mainLayoutRaw from '@/layouts/MainLayout.vue?raw'
import { useAuthStore } from '@/stores/auth'
import {
  SIDEBAR_DEFAULT_WIDTH,
  SIDEBAR_MIN_WIDTH,
  SIDEBAR_MAX_WIDTH,
  SIDEBAR_COLLAPSED_WIDTH,
  SIDEBAR_WIDTH_KEY,
  SIDEBAR_COLLAPSED_KEY,
  clampSidebarWidth,
} from '@/composables/useSidebarPrefs'

const mounted: { unmount: () => void }[] = []

async function factory(userId = 7) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { userId, username: 'admin', nickName: '管理员', roles: ['ADMIN'] }
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', name: 'blank', component: { template: '<div />' } }],
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


function aside(w: { find: (s: string) => { element: HTMLElement } }) {
  const el = w.find('.layout-aside').element
  const px = Number.parseFloat(getComputedStyle(el).width)
  return { el, px, rect: el.getBoundingClientRect().width }
}


async function drag(w: ReturnType<typeof factory> extends Promise<infer T> ? T : never, fromX: number, toX: number) {
  const resizer = w.find('[data-test="sidebar-resizer"]').element
  resizer.dispatchEvent(new MouseEvent('mousedown', { clientX: fromX, button: 0, bubbles: true }))
  await flushPromises()
  window.dispatchEvent(new MouseEvent('mousemove', { clientX: toX, bubbles: true }))
  await flushPromises()
  window.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
  await flushPromises()
}

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
  document.body.innerHTML = ''
})

describe('侧栏可调宽裁决件（T343 第三刀）', () => {
  it('常量单源面值与裁决一致（240 默认/200–320 区间/64 折叠）', () => {
    expect(SIDEBAR_DEFAULT_WIDTH).toBe(240)
    expect(SIDEBAR_MIN_WIDTH).toBe(200)
    expect(SIDEBAR_MAX_WIDTH).toBe(320)
    expect(SIDEBAR_COLLAPSED_WIDTH).toBe(64)
    expect(clampSidebarWidth(100)).toBe(SIDEBAR_MIN_WIDTH)
    expect(clampSidebarWidth(999)).toBe(SIDEBAR_MAX_WIDTH)
    expect(clampSidebarWidth(250.6)).toBe(251)
    expect(clampSidebarWidth(Number.NaN)).toBe(SIDEBAR_DEFAULT_WIDTH)
  })

  it('MainLayout 撤魔法数：无 200px/240px 散写字面量+拖拽条在位', () => {
    expect(mainLayoutRaw).not.toMatch(/'200px'/)
    expect(mainLayoutRaw).not.toMatch(/'240px'/)
    expect(mainLayoutRaw).toMatch(/data-test="sidebar-resizer"/)
    expect(mainLayoutRaw).toMatch(/dblclick/)
  })

  it('默认宽 240（真布局：style 与 getBoundingClientRect 同宽）', async () => {
    const w = await factory()
    const a = aside(w)
    expect(a.px).toBe(240)
    expect(Math.abs(a.rect - 240)).toBeLessThanOrEqual(2)
  })

  it('拖拽跟手+区间极值夹取+松手落 localStorage（按用户 id 分键）', async () => {
    const w = await factory()
    await drag(w, 1000, 1060)
    expect(aside(w).px).toBe(300)
    await drag(w, 1000, 2000)
    expect(aside(w).px).toBe(SIDEBAR_MAX_WIDTH)
    await drag(w, 2000, 1000)
    expect(aside(w).px).toBe(SIDEBAR_MIN_WIDTH)
    expect(localStorage.getItem(`${SIDEBAR_WIDTH_KEY}:7`)).toBe(String(SIDEBAR_MIN_WIDTH))
  })

  it('双击拖拽条=回默认 240', async () => {
    const w = await factory()
    await drag(w, 1000, 1060)
    expect(aside(w).px).toBe(300)
    w.find('[data-test="sidebar-resizer"]').element.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))
    await flushPromises()
    expect(aside(w).px).toBe(SIDEBAR_DEFAULT_WIDTH)
    expect(localStorage.getItem(`${SIDEBAR_WIDTH_KEY}:7`)).toBe(String(SIDEBAR_DEFAULT_WIDTH))
  })

  it('持久回读：卸载重挂（模拟刷新）保持上次宽度', async () => {
    const w = await factory()
    await drag(w, 1000, 1080)
    expect(aside(w).px).toBe(320)
    w.unmount()
    const w2 = await factory()
    expect(aside(w2).px).toBe(320)
  })

  it('宽度按用户 id 分键不串味：用户 8 首登=默认宽（用户 7 的 320 不带入）', async () => {
    const w = await factory(7)
    await drag(w, 1000, 1080)
    const w8 = await factory(8)
    expect(aside(w8).px).toBe(SIDEBAR_DEFAULT_WIDTH)
  })

  it('折叠态：64px+持久；再展开恢复上次宽度', async () => {
    const w = await factory()
    await drag(w, 1000, 1050) // 290
    w.find('.collapse-btn').element.click()
    await flushPromises()
    expect(aside(w).px).toBe(SIDEBAR_COLLAPSED_WIDTH)
    expect(localStorage.getItem(`${SIDEBAR_COLLAPSED_KEY}:7`)).toBe('1')
    w.find('.collapse-btn').element.click()
    await flushPromises()
    expect(aside(w).px).toBe(290)
    w.find('.collapse-btn').element.click()
    await flushPromises()
    expect(aside(w).px).toBe(SIDEBAR_COLLAPSED_WIDTH)
    w.unmount()
    const w2 = await factory()
    expect(aside(w2).px).toBe(SIDEBAR_COLLAPSED_WIDTH)
    w2.find('.collapse-btn').element.click()
    await flushPromises()
    expect(aside(w2).px).toBe(290)
    expect(localStorage.getItem(`${SIDEBAR_COLLAPSED_KEY}:7`)).toBe('0')
  })

  it('折叠态子项名弹出不回归：说明面=行尾问号锚折叠隐藏（v-show）+el-menu collapse 面在位', () => {
    expect(mainLayoutRaw).toMatch(/v-show="!collapsed"[^>]*class="menu-leaf-help"|class="menu-leaf-help"[\s\S]{0,60}v-show="!collapsed"/)
    expect(mainLayoutRaw.match(/menu-leaf-help/g)!.length).toBeGreaterThanOrEqual(2)
    expect(mainLayoutRaw).toMatch(/:collapse="collapsed"/)
  })
})
