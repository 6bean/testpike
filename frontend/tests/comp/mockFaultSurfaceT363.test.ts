/**
 * fault-surface contract (mock fault injection, frontend form).
 *
 * What must never regress:
 * 1. the space create dialog carries the chaos dial (0-100) and submits it as
 * chaosPercent — the backend chain (core validator range gate, snapshot
 * pin, mock-server injector) already exists; without this field the whole
 * space-level layer is unreachable from the UI;
 * 2. an omitted/zero dial means "no injection" and is displayed honestly —
 * never a blank that could be read as unknown;
 * 3. the docs page names the fault carried by a stub's response builder
 * (type in plain words + probability), so a faulted rule is visible where
 * the rule is read;
 * 4. fault type labels live in ONE util (mockFaultLabels) — docs card, future
 * builder form and inspector alike must not fork a second dictionary;
 * 5. i18n dual track: every key the surface uses exists in both zh-CN and en-US.
 */
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'
import { faultTypeLabelKey } from '@/utils/mockFaultLabels'

const api = vi.hoisted(() => ({
  listSpaces: vi.fn(),
  listStubs: vi.fn(),
  listScenarios: vi.fn(),
  pageScenarios: vi.fn(),
  getServingUrl: vi.fn(),
  createSpace: vi.fn(),
  createStub: vi.fn(),
  transitionSpace: vi.fn(),
}))
vi.mock('@/api/mock', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return {
    ...actual,
    listSpaces: api.listSpaces,
    listStubs: api.listStubs,
    listScenarios: api.listScenarios,
    pageScenarios: api.pageScenarios,
    getServingUrl: api.getServingUrl,
    createSpace: api.createSpace,
    createStub: api.createStub,
    transitionSpace: api.transitionSpace,
  }
})
vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return { ...actual, getProjects: vi.fn().mockResolvedValue({ list: [], total: 0 }) }
})
const auth = vi.hoisted(() => ({ perms: ['*'] as string[] }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: { userId: 1, username: 'u', nickName: 'u', roles: [] },
    projects: [{ projectId: 3, name: 's', code: 's' }],
    get isAdmin() {
      return auth.perms.includes('*')
    },
    hasPerm: (code: string) => auth.perms.includes('*') || auth.perms.includes(code),
  }),
}))

import MockSpacesView from '@/views/mock/MockSpacesView.vue'
import MockDocsView from '@/views/mock/MockDocsView.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages: { 'zh-CN': zhCN, 'en-US': enUS },
})

function spaceRow(over: Record<string, unknown> = {}) {
  return {
    id: 3, projectId: 3, name: 's', spaceKey: 's', alias: null, status: 1,
    version: 0, priority: 100, authMode: 'none', envId: null,
    basePathMode: 'prefix', rateLimitRps: null, quotaDaily: null,
    chaosPercent: null,
    createdAt: null, updatedAt: null,
    ...over,
  }
}

function stubRow(over: Record<string, unknown> = {}) {
  return {
    id: 11, spaceId: 3, name: 'GET /orders', priority: 100, method: 'GET',
    pathMatchType: 'exact', pathPattern: '/orders', matchersJson: null,
    responseJson: JSON.stringify({ status: 200, bodyMode: 'static', body: '{}' }),
    scenarioName: null, source: 'manual', sourceRef: null, enabled: true,
    createdAt: null, updatedAt: null,
    ...over,
  }
}

function makeRoutes() {
  return [
    { path: '/mock/spaces', component: { template: '<div></div>' } },
    { path: '/mock/docs', component: { template: '<div></div>' } },
    { path: '/mock/scenarios', component: { template: '<div></div>' } },
    { path: '/:pathMatch(.*)*', component: { template: '<div></div>' } },
  ]
}

async function factorySpaces() {
  const pinia = createPinia()
  setActivePinia(pinia)
  localStorage.setItem('tp_proj_ctx_1', JSON.stringify([3]))
  const router = createRouter({ history: createMemoryHistory(), routes: makeRoutes() })
  await router.push('/mock/spaces?projectId=3')
  await router.isReady()
  const w = mount(MockSpacesView, { global: { plugins: [ElementPlus, i18n, pinia, router] } })
  await flushPromises()
  return w
}

async function factoryDocs() {
  const pinia = createPinia()
  setActivePinia(pinia)
  localStorage.setItem('tp_proj_ctx_1', JSON.stringify([3]))
  const router = createRouter({ history: createMemoryHistory(), routes: makeRoutes() })
  await router.push('/mock/docs?spaceId=3&projectId=3')
  await router.isReady()
  const w = mount(MockDocsView, { global: { plugins: [ElementPlus, i18n, pinia, router] } })
  await flushPromises()
  return w
}

describe('space chaos dial surface (T363 blade 5a)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getServingUrl.mockResolvedValue({ publicBaseUrl: 'http://m:18086', trafficBasePath: '/mocks' })
    api.listSpaces.mockResolvedValue([spaceRow()])
    api.listStubs.mockResolvedValue([])
    api.listScenarios.mockResolvedValue([])
    api.pageScenarios.mockResolvedValue({ list: [], total: 0 })
    api.createSpace.mockResolvedValue(99)
  })

  it('create dialog exposes the chaos field and submits it as chaosPercent', async () => {
    const w = await factorySpaces()
    await w.find('[data-test="mock-spaces-new"]').trigger('click')
    await flushPromises()
    // The save guard requires name+spaceKey first (ownership echo posture);
    // fill them so the submit actually reaches the API.
    await w.find('[data-test="mock-spaces-f-name"]').setValue('chaos-space')
    await w.find('[data-test="mock-spaces-f-key"]').setValue('chaos-space')
    const dial = w.find('[data-test="mock-spaces-f-chaos"]')
    expect(dial.exists(), 'chaos dial input must exist in the create dialog').toBe(true)
    const inner = dial.find('input')
    await inner.setValue('30')
    await inner.trigger('change')
    await inner.trigger('blur')
    await flushPromises()
    await w.find('[data-test="mock-spaces-create-submit"]').trigger('click')
    await flushPromises()
    expect(api.createSpace).toHaveBeenCalledTimes(1)
    const payload = api.createSpace.mock.calls[0][0] as Record<string, unknown>
    expect(payload.chaosPercent).toBe(30)
  })

  it('zero/omitted dial is honestly displayed as no-injection, and a set dial shows its percent', async () => {
    api.listSpaces.mockResolvedValue([spaceRow({ id: 3, chaosPercent: 30 }), spaceRow({ id: 4, name: 't', chaosPercent: null })])
    const w = await factorySpaces()
    const rows = w.findAll('[data-test="mock-spaces-table"] tbody tr')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('30')
    expect(rows[1].text()).toContain(i18n.global.t('mockSpaces.chaosNone'))
  })
})

describe('docs card fault visibility (T363 blade 5a)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getServingUrl.mockResolvedValue({ publicBaseUrl: 'http://m:18086', trafficBasePath: '/mocks' })
    api.listScenarios.mockResolvedValue([])
    api.pageScenarios.mockResolvedValue({ list: [], total: 0 })
    api.listSpaces.mockResolvedValue([spaceRow()])
  })

  it('a stub whose builder payload carries a fault names it on the card', async () => {
    api.listStubs.mockResolvedValue([stubRow({
      responseJson: JSON.stringify({
        status: 200, bodyMode: 'static', body: '{}',
        fault: { type: 'delay', minMs: 800, maxMs: 1200, probability: 20 },
      }),
    })])
    const w = await factoryDocs()
    // V240 follow-up posture: single-rule spaces auto-expand on load (the
    // collapsed wall hid just-created rules — root cause of the t359b reds),
    // so the card is readable without a manual open at this fixture size.
    const chip = w.find('[data-test="mock-docs-card-fault"]')
    expect(chip.exists(), 'faulted stub must show a fault chip on the docs card').toBe(true)
    expect(chip.text()).toContain(i18n.global.t('mockFault.delay'))
    expect(chip.text()).toContain('20')
  })

  it('a faultless stub shows no chip (absence must not be invented)', async () => {
    api.listStubs.mockResolvedValue([stubRow()])
    const w = await factoryDocs()
    const head = w.find('[data-test^="mock-docs-stub-group-head"]')
    if (head.exists()) {
      await head.trigger('click')
      await flushPromises()
    }
    expect(w.find('[data-test="mock-docs-card-fault"]').exists()).toBe(false)
  })

  it('fault type label keys all resolve in BOTH locales (i18n dual track)', () => {
    for (const type of ['delay', 'status', 'malformed', 'dribble', 'disconnect', 'chaos']) {
      const key = faultTypeLabelKey(type)
      expect((zhCN as Record<string, any>).mockFault[type], `zh-CN mockFault.${type}`).toBeTruthy()
      expect((enUS as Record<string, any>).mockFault[type], `en-US mockFault.${type}`).toBeTruthy()
      expect(key, `label key for ${type}`).toBe(`mockFault.${type}`)
    }
  })
})
