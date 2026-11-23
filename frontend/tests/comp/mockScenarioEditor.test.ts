/**
 * Scenario-surface contract (born in on the docs page; the
 * standalone information-architecture of — the 2026-10-14 mock IA
 * ruling doc on disk — moved the machine rows, the create drawer and the
 * graphs off the docs page onto /mock/scenarios (list) and
 * /mock/scenarios/:id (detail). This file was
 * carried onto the new surfaces with that ruling; the docs page keeps only a
 * summary bar.
 *
 * Contract points that must never regress:
 * 1. list rows show name, lane label, state/transition counts and a health
 * tag; the window comes from the paged door (never the unbounded list);
 * 2. the SVG graph renders on the DETAIL page only — declared states as
 * nodes (initial = double ring), legal transitions solid, dangling edges
 * dashed AND named in words (hiding a broken edge would be pretending);
 * 3. an unreadable declaration shows the plain-word refusal, never a half
 * graph;
 * 4. the create drawer blocks emptiness locally and pre-warns dangling
 * targets, while legality itself stays the core save gate (the write
 * funnels through the shared graph util = kernel-frozen four keys);
 * 5. read-only audiences get no create button and the honest empty state;
 * 6. the docs summary bar counts machines and jumps to the list carrying the
 * space context; the stub area screens client-side (search + collapsed
 * resource-path groups) over the already-served rows.
 */
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({
  listSpaces: vi.fn(),
  listStubs: vi.fn(),
  listScenarios: vi.fn(),
  pageScenarios: vi.fn(),
  createScenario: vi.fn(),
  getServingUrl: vi.fn(),
  createStub: vi.fn(),
}))
vi.mock('@/api/mock', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return {
    ...actual,
    listSpaces: api.listSpaces,
    listStubs: api.listStubs,
    listScenarios: api.listScenarios,
    pageScenarios: api.pageScenarios,
    createScenario: api.createScenario,
    getServingUrl: api.getServingUrl,
    createStub: api.createStub,
  }
})
vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return { ...actual, getProjects: vi.fn().mockResolvedValue({ list: [], total: 0 }) }
})
const auth = vi.hoisted(() => ({ perms: [] as string[] }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: { userId: 1, username: 'u', nickName: 'u', roles: [] },
    // AK prune law consumes auth.projects (visible domain); project 3 stays visible
    projects: [{ projectId: 3, name: 's', code: 's' }],
    get isAdmin() {
      return auth.perms.includes('*')
    },
    hasPerm: (code: string) => auth.perms.includes('*') || auth.perms.includes(code),
  }),
}))

import MockDocsView from '@/views/mock/MockDocsView.vue'
import MockScenarioListView from '@/views/mock/MockScenarioListView.vue'
import MockScenarioDetailView from '@/views/mock/MockScenarioDetailView.vue'
import mockApiRaw from '@/api/mock?raw'
import listRaw from '@/views/mock/MockScenarioListView.vue?raw'
import detailRaw from '@/views/mock/MockScenarioDetailView.vue?raw'
import docsRaw from '@/views/mock/MockDocsView.vue?raw'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages: { 'zh-CN': zhCN, 'en-US': enUS },
})

function machine(over: Record<string, unknown> = {}) {
  return {
    id: 7,
    spaceId: 3,
    name: 'order-flow',
    definitionJson: JSON.stringify({
      states: ['created', 'paid', 'shipped'],
      initial: 'created',
      scope: 'session',
      transitions: [
        { from: 'created', to: 'paid' },
        { from: 'paid', to: 'shipped' },
      ],
    }),
    createdAt: null,
    updatedAt: null,
    ...over,
  }
}

function spaceRow() {
  return {
    id: 3, projectId: 3, name: 's', spaceKey: 's', alias: null, status: 1,
    version: 0, priority: 100, authMode: 'none', envId: null,
    basePathMode: 'space', rateLimitRps: null, quotaDaily: null,
    createdAt: null, updatedAt: null,
  }
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/mock/scenarios', component: { template: '<div></div>' } },
      { path: '/mock/scenarios/:id', component: { template: '<div></div>' } },
      { path: '/mock/docs', component: { template: '<div></div>' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div></div>' } },
    ],
  })
}

/** Wait for the lazily-rendered drawer pane (el-drawer opens on a
 *  false→true modelValue transition; animation frames, not flushPromises). */
async function drawerOf(w: ReturnType<typeof mount>, sel: string): Promise<HTMLElement> {
  await vi.waitFor(() => expect(w.find(sel).exists(), sel).toBe(true))
  return w.find(sel).element as HTMLElement
}

async function factoryList() {
  const pinia = createPinia()
  setActivePinia(pinia)
  localStorage.setItem('tp_proj_ctx_1', JSON.stringify([3]))
  const router = makeRouter()
  await router.push('/mock/scenarios?spaceId=3&projectId=3')
  await router.isReady()
  const w = mount(MockScenarioListView, { global: { plugins: [ElementPlus, i18n, pinia, router] } })
  await flushPromises()
  return w
}

async function factoryDetail() {
  const pinia = createPinia()
  setActivePinia(pinia)
  localStorage.setItem('tp_proj_ctx_1', JSON.stringify([3]))
  const router = makeRouter()
  await router.push('/mock/scenarios/7?spaceId=3&projectId=3')
  await router.isReady()
  const w = mount(MockScenarioDetailView, { global: { plugins: [ElementPlus, i18n, pinia, router] } })
  await flushPromises()
  return w
}

async function factoryDocs() {
  const pinia = createPinia()
  setActivePinia(pinia)
  localStorage.setItem('tp_proj_ctx_1', JSON.stringify([3]))
  const router = makeRouter()
  await router.push('/mock/docs?spaceId=3&projectId=3')
  await router.isReady()
  const w = mount(MockDocsView, { global: { plugins: [ElementPlus, i18n, pinia, router] } })
  await flushPromises()
  return w
}

describe('scenario list surface (T397 — was docs-inline in T361b)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    auth.perms = ['perm:mock:read', 'perm:mock:write']
    api.getServingUrl.mockResolvedValue({ publicBaseUrl: 'http://localhost:18086', trafficBasePath: '/mocks' })
    api.listSpaces.mockResolvedValue([spaceRow()])
    api.listStubs.mockResolvedValue([])
    api.listScenarios.mockResolvedValue([])
    api.pageScenarios.mockResolvedValue({ list: [], total: 0, page: 1, size: 20 })
    document.body.innerHTML = ''
  })

  it('rows come from the paged door with lane label and counts (never the unbounded list)', async () => {
    api.pageScenarios.mockResolvedValue({ list: [machine()], total: 1, page: 1, size: 20 })
    // 17cd0c6f added the server-side sort window (4th arg) — the paged door
    // pin rides the updated contract shape, still never the unbounded list.
    const w = await factoryList()
    expect(api.pageScenarios).toHaveBeenCalledWith(3, 1, 20, { sortBy: '', sortDir: '' })
    const row = w.find('[data-test="mock-scenarios-row"]')
    expect(row.exists()).toBe(true)
    expect(row.text()).toContain('order-flow')
    expect(w.text()).toContain('会话泳道')
    expect(w.find('[data-test="mock-scenarios-healthy"]').exists()).toBe(true)
  })

  it('an unreadable declaration shows the plain-word reason line, no graph row invented', async () => {
    api.pageScenarios.mockResolvedValue({ list: [machine({ definitionJson: '{oops' })], total: 1, page: 1, size: 20 })
    const w = await factoryList()
    expect(w.find('[data-test="mock-scenarios-unreadable"]').text()).toContain('not-json')
    expect(w.find('[data-test="mock-scenarios-healthy"]').exists()).toBe(false)
  })

  it('create drawer blocks empty name/states locally and pre-warns dangling targets before submit', async () => {
    const w = await factoryList()
    w.find('[data-test="mock-scenarios-new"]').trigger('click')
    const drawer = await drawerOf(w, '[data-test="mock-scenarios-create-drawer"]')
    // ownership echo (iron rule 1) lives in the dialog itself
    expect(drawer.querySelector('[data-test="owner-echo"]')).not.toBeNull()
    ;(drawer.querySelector('[data-test="mock-scenarios-save"]') as HTMLElement).click()
    await flushPromises()
    expect(api.createScenario).not.toHaveBeenCalled()
    expect(drawer.querySelector('[data-test="mock-scenarios-form-error"]')?.textContent).toContain('不能为空')
    // add one extra state row + one edge with an undeclared target -> dangling warn
    ;(drawer.querySelector('[data-test="mock-scenarios-f-add-state"]') as HTMLElement).click()
    ;(drawer.querySelector('[data-test="mock-scenarios-f-add-edge"]') as HTMLElement).click()
    await flushPromises()
    const edgeTo = drawer.querySelector('[data-test="mock-scenarios-f-edge-to-0"]') as HTMLInputElement
    expect(edgeTo).not.toBeNull()
    edgeTo.value = 'ghost'
    edgeTo.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(drawer.querySelector('[data-test="mock-scenarios-dangling-warn"]')).not.toBeNull()
    // with only blank states declared, save is still blocked locally (nothing invented)
    ;(drawer.querySelector('[data-test="mock-scenarios-save"]') as HTMLElement).click()
    await flushPromises()
    expect(api.createScenario).not.toHaveBeenCalled()
  })

  it('saves a well-formed machine through the shared writer with the kernel-frozen four keys', async () => {
    const w = await factoryList()
    w.find('[data-test="mock-scenarios-new"]').trigger('click')
    const drawer = await drawerOf(w, '[data-test="mock-scenarios-create-drawer"]')
    const name = drawer.querySelector('[data-test="mock-scenarios-f-name"]') as HTMLInputElement
    name.value = 'flow-x'
    name.dispatchEvent(new Event('input'))
    const state0 = drawer.querySelector('[data-test="mock-scenarios-f-state-0"]') as HTMLInputElement
    state0.value = 'idle'
    state0.dispatchEvent(new Event('input'))
    await flushPromises()
    ;(drawer.querySelector('[data-test="mock-scenarios-f-add-state"]') as HTMLElement).click()
    await flushPromises()
    const state1 = drawer.querySelector('[data-test="mock-scenarios-f-state-1"]') as HTMLInputElement
    state1.value = 'done'
    state1.dispatchEvent(new Event('input'))
    await flushPromises()
    api.createScenario.mockResolvedValue(9)
    ;(drawer.querySelector('[data-test="mock-scenarios-save"]') as HTMLElement).click()
    await flushPromises()
    expect(api.createScenario).toHaveBeenCalledTimes(1)
    const [spaceId, payload] = api.createScenario.mock.calls[0]
    expect(spaceId).toBe(3)
    expect(payload.name).toBe('flow-x')
    const def = JSON.parse(payload.definitionJson)
    expect(Object.keys(def).sort()).toEqual(['initial', 'scope', 'states', 'transitions'])
    expect(def.states).toEqual(['idle', 'done'])
    expect(def.initial).toBe('idle')
    expect(def.scope).toBe('session')
    // the created window reload is what feeds the rows — no optimistic row inserted
    expect(api.pageScenarios).toHaveBeenCalledTimes(2)
  })

  it('hides the create entry for read-only audiences and keeps the honest empty state', async () => {
    auth.perms = ['perm:mock:read']
    const w = await factoryList()
    expect(w.find('[data-test="mock-scenarios-new"]').exists()).toBe(false)
    expect(w.find('[data-test="mock-scenarios-empty"]').exists()).toBe(true)
  })
})

describe('scenario detail surface (T397 — the one-graph-per-screen home)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    auth.perms = ['perm:mock:read', 'perm:mock:write']
    api.listScenarios.mockResolvedValue([])
    api.listSpaces.mockResolvedValue([spaceRow()])
    document.body.innerHTML = ''
  })

  it('draws the SVG graph: node per state, solid legal edges, dashed dangling edge named in words, double ring on initial', async () => {
    api.listScenarios.mockResolvedValue([
      machine({
        definitionJson: JSON.stringify({
          states: ['a', 'b'],
          initial: 'a',
          scope: 'role',
          transitions: [{ from: 'a', to: 'b' }, { from: 'b', to: 'ghost' }],
        }),
      }),
    ])
    const w = await factoryDetail()
    const svg = w.find('[data-test="mock-scenario-detail-graph"]')
    expect(svg.exists()).toBe(true)
    expect(svg.findAll('[data-node]')).toHaveLength(2)
    expect(svg.find('[data-node="a"]').attributes('data-initial')).toBe('true')
    expect(svg.findAll('[data-edge="solid"]')).toHaveLength(1)
    expect(svg.findAll('[data-edge="dangling"]')).toHaveLength(1)
    // the broken edge is named in words too — never a silent dashed line alone
    expect(w.find('[data-test="mock-scenario-detail-dangling-note"]').text()).toContain('b→ghost')
    // deep-link discipline: the return door exists on every reachable state
    expect(w.find('[data-test="mock-scenario-detail-back"]').exists()).toBe(true)
  })

  it('refuses to render a half graph: unreadable declaration shows the plain-word reason line', async () => {
    api.listScenarios.mockResolvedValue([machine({ definitionJson: '{oops' })])
    const w = await factoryDetail()
    expect(w.find('[data-test="mock-scenario-detail-graph"]').exists()).toBe(false)
    expect(w.find('[data-test="mock-scenario-detail-unreadable"]').text()).toContain('not-json')
  })

  it('a machine gone from the space is reported missing, never rendered from a stale guess', async () => {
    api.listScenarios.mockResolvedValue([machine({ id: 8 })])
    const w = await factoryDetail()
    expect(w.find('[data-test="mock-scenario-detail-missing"]').exists()).toBe(true)
    expect(w.find('[data-test="mock-scenario-detail-graph"]').exists()).toBe(false)
  })
})

describe('docs page keeps only the summary bar + client-side stub screening (T397)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    auth.perms = ['perm:mock:read', 'perm:mock:write']
    api.getServingUrl.mockResolvedValue({ publicBaseUrl: 'http://localhost:18086', trafficBasePath: '/mocks' })
    api.listSpaces.mockResolvedValue([spaceRow()])
    api.listStubs.mockResolvedValue([])
    api.listScenarios.mockResolvedValue([])
    document.body.innerHTML = ''
  })

  it('summary bar counts machines and the jump rides the space context; no graph is drawn here', async () => {
    api.listScenarios.mockResolvedValue([
      machine(),
      machine({
        id: 9,
        name: 'broken-flow',
        definitionJson: JSON.stringify({
          states: ['a'], initial: 'a', scope: 'session',
          transitions: [{ from: 'a', to: 'ghost' }],
        }),
      }),
    ])
    const w = await factoryDocs()
    const bar = w.find('[data-test="mock-docs-scenario-summary"]')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('2')
    expect(w.find('[data-test="mock-docs-scenario-needsfix"]').exists()).toBe(true)
    // the heavy asset is retired from this page — zero scenario graphs
    // (note: el-icon itself renders an svg, so the pin targets the graph class)
    expect(w.find('[data-test="mock-docs-scenario-graph"]').exists()).toBe(false)
    expect(w.findAll('.scenario-graph')).toHaveLength(0)
    w.find('[data-test="mock-docs-scenario-list"]').trigger('click')
    await flushPromises()
  })

  it('stub area groups by resource path and screens instantly without refetch', async () => {
    api.listStubs.mockResolvedValue([
      { id: 1, spaceId: 3, name: 'get user', priority: 10, method: 'GET', pathMatchType: 'PREFIX', pathPattern: '/users/{id}', matchersJson: null, responseJson: '{}', scenarioName: null, requiredState: null, newState: null, source: null, sourceRef: null, enabled: true, createdAt: null, updatedAt: null },
      { id: 2, spaceId: 3, name: 'create user', priority: 20, method: 'POST', pathMatchType: 'EXACT', pathPattern: '/users', matchersJson: null, responseJson: '{}', scenarioName: null, requiredState: null, newState: null, source: null, sourceRef: null, enabled: false, createdAt: null, updatedAt: null },
      { id: 3, spaceId: 3, name: 'list orders', priority: 30, method: 'GET', pathMatchType: 'EXACT', pathPattern: '/orders', matchersJson: null, responseJson: '{}', scenarioName: null, requiredState: null, newState: null, source: null, sourceRef: null, enabled: true, createdAt: null, updatedAt: null },
    ])
    const w = await factoryDocs()
    const groups = w.findAll('[data-test="mock-docs-stub-group"]')
    expect(groups).toHaveLength(2) // orders | users
    // V240 follow-up: a small space (3 rules ≤ cap 8 AND 2 groups ≤ cap 5)
    // auto-expands — the collapsed wall used to hide freshly created rules.
    expect(w.findAll('[data-test="mock-docs-card"]')).toHaveLength(3)
    // instant search narrows groups client-side — no second listStubs call
    expect(api.listStubs).toHaveBeenCalledTimes(1)
    // el-input forwards attrs onto the native input (platform pitfall: the
    // data-test anchor IS the input element, not a wrapper)
    const search = w.find('[data-test="mock-docs-stub-search"]')
    await search.setValue('orders')
    expect(w.findAll('[data-test="mock-docs-stub-group"]')).toHaveLength(1)
    expect(api.listStubs).toHaveBeenCalledTimes(1)
  })

  it('api mirror pins: both doors exist on the bridge shape and views funnel through the shared graph util', () => {
    expect(mockApiRaw).toContain('`/mock/spaces/${spaceId}/scenarios`')
    expect(mockApiRaw).toContain('`/mock/spaces/${spaceId}/scenarios/page`')
    expect(mockApiRaw).toContain('`/mock/spaces/${spaceId}/stubs/page`')
    // the list consumes the bounded window; the docs bar only counts
    expect(listRaw).toContain('pageScenarios')
    expect(docsRaw).toContain('listScenarios')
    expect(docsRaw).not.toContain('pageScenarios')
    // one wire shape for the machine = the kernel-frozen writer, never a second serializer
    expect(listRaw).toContain('writeScenarioDefinition')
    expect(listRaw).toContain('readScenarioGraph')
    expect(detailRaw).toContain('readScenarioGraph')
    // the graph math has exactly one home now (the shared layout module)
    expect(docsRaw).not.toMatch(/function buildLayout/)
    expect(detailRaw).toContain("from '@/utils/mockScenarioGraphLayout'")
  })

  it('i18n mirror: the scenario key families exist in zh-CN and en-US', () => {
    const zh = zhCN as Record<string, any>
    const en = enUS as Record<string, any>
    for (const k of [
      'pageTitle', 'pageSubtitle', 'newScenario', 'colName', 'colLane', 'colStates',
      'colTransitions', 'colHealth', 'colUpdated', 'healthyTag', 'danglingTag', 'unreadable',
      'empty', 'searchNote', 'drawerTitle', 'fieldName', 'fieldStates', 'fieldTransitions',
      'danglingWarn', 'formIncomplete', 'laneSession', 'laneRole', 'laneGlobal',
    ]) {
      expect(typeof zh.mockScenarios[k], `zh mockScenarios.${k}`).toBe('string')
      expect(typeof en.mockScenarios[k], `en mockScenarios.${k}`).toBe('string')
    }
    for (const k of ['back', 'missing', 'unreadable', 'danglingNote', 'metaLane', 'metaInitial']) {
      expect(typeof zh.mockScenarioDetail[k], `zh mockScenarioDetail.${k}`).toBe('string')
      expect(typeof en.mockScenarioDetail[k], `en mockScenarioDetail.${k}`).toBe('string')
    }
    for (const k of ['scenarioTitle', 'scenarioSummaryHint', 'scenarioCount', 'scenarioHealthy', 'scenarioNeedsFix', 'scenarioListJump', 'stubSearchPh', 'stubGroupCount']) {
      expect(typeof zh.mockDocs[k], `zh mockDocs.${k}`).toBe('string')
      expect(typeof en.mockDocs[k], `en mockDocs.${k}`).toBe('string')
    }
  })
})
