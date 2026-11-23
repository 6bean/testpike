/**
 * fault-form contract (stub-level fault create surface).
 *
 * What must never regress:
 * 1. the docs page carries a structured create entry next to the curl box —
 * without it the stub-level fault spectrum (backend MockFaultRules + the
 * data-plane injector, both already shipped) is unreachable from the UI;
 * 2. the drawer echoes ownership read-only (iron rule 1) and submits through
 * the createStub door in create mode (V240 added the update door: the same
 * editor now round-trips edits too — see MockStubEditDelete L2 and the
 * V240 E2E pair; this contract keeps the CREATE path pinned);
 * 3. the fault switch reveals the five-type select sourced from ONE dictionary
 * (mockFaultLabels FAULT_TYPES) and per-type parameter fields; omitted
 * probability stays absent from the payload — never a fabricated 0/100;
 * 4. parameter widgets carry the domain bounds the core save gate enforces
 * (probability 0-100, delay 0<=minMs, status 500-599, dribble chunkBytes>=1
 * / chunkDelayMs>=0) as input constraints only — legality is decided once
 * at the core gate, whose plain-word refusal shows untouched;
 * 5. i18n dual track: every key the drawer uses exists in both zh-CN and en-US.
 *
 * Posture notes: the option-popover of el-select is teleport+animation bound,
 * so the type choice is driven by emitting the model event on the component
 * instance (the contract under test is the model reaction, not the popover),
 * and the single-dictionary rule is pinned as a source-layout contract
 * (mockHeaderLayoutT396 precedent).
 */
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import docsRaw from '@/views/mock/MockDocsView.vue?raw'
import editorRaw from '@/components/MockStubEditorDrawer.vue?raw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({
  listSpaces: vi.fn(),
  listStubs: vi.fn(),
  listScenarios: vi.fn(),
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
    getServingUrl: api.getServingUrl,
    createStub: api.createStub,
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

function makeRoutes() {
  return [
    { path: '/mock/spaces', component: { template: '<div></div>' } },
    { path: '/mock/docs', component: { template: '<div></div>' } },
    { path: '/mock/scenarios', component: { template: '<div></div>' } },
    { path: '/:pathMatch(.*)*', component: { template: '<div></div>' } },
  ]
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

/** Wait for the lazily-rendered drawer pane (opens on a false→true modelValue
 *  transition; animation frames, not flushPromises). */
async function drawerOf(w: ReturnType<typeof mount>, sel: string): Promise<HTMLElement> {
  await vi.waitFor(() => expect(w.find(sel).exists(), sel).toBe(true))
  return w.find(sel).element as HTMLElement
}

/** Flip the fault switch (real click on the native checkbox). */
async function toggleFault(w: ReturnType<typeof mount>): Promise<void> {
  const input = w.find('[data-test="mock-docs-f-fault-switch"] input')
  expect(input.exists(), 'fault switch must exist under its anchor').toBe(true)
  await input.trigger('click')
  await flushPromises()
}

/** Choose a fault type by clicking the option in the live popover (browser
 *  mode renders the teleported dropdown; text match via the shared labels). */
async function pickFaultType(w: ReturnType<typeof mount>, type: string): Promise<void> {
  const label = i18n.global.t(`mockFault.${type}`)
  const trigger = w.find('[data-test="mock-docs-f-fault-type-wrap"] .el-select__wrapper')
  expect(trigger.exists(), 'fault type select must exist once the switch is on').toBe(true)
  await trigger.trigger('click')
  await vi.waitFor(() => {
    const hit = Array.from(document.body.querySelectorAll('.el-select-dropdown__item'))
      .some((o) => (o.textContent ?? '').includes(label))
    expect(hit, `option ${label}`).toBe(true)
  })
  const opt = Array.from(document.body.querySelectorAll('.el-select-dropdown__item'))
    .find((o) => (o.textContent ?? '').includes(label)) as HTMLElement
  await opt.click()
  await flushPromises()
}

/** Type into a numeric/text widget: the anchor sits on the form-item wrapper,
 *  the native input lives inside it (pit 23 posture). */
async function setField(w: ReturnType<typeof mount>, anchor: string, value: string): Promise<void> {
  const wrap = w.find(`[data-test="${anchor}"]`)
  expect(wrap.exists(), anchor).toBe(true)
  const input = wrap.find('input')
  expect(input.exists(), `native input under ${anchor}`).toBe(true)
  await input.setValue(value)
}

function nativeInput(w: ReturnType<typeof mount>, anchor: string): HTMLInputElement {
  const input = w.find(`[data-test="${anchor}"]`).find('input').element as HTMLInputElement
  expect(input, `input under ${anchor}`).toBeTruthy()
  return input
}

async function openDrawer(w: ReturnType<typeof mount>): Promise<HTMLElement> {
  await w.find('[data-test="mock-docs-new-structured"]').trigger('click')
  return drawerOf(w, '[data-test="mock-docs-create-drawer"]')
}

describe('stub fault create form (T363 blade 5b)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    auth.perms = ['*']
    api.getServingUrl.mockResolvedValue({ publicBaseUrl: 'http://m:18086', trafficBasePath: '/mocks' })
    api.listSpaces.mockResolvedValue([spaceRow()])
    api.listStubs.mockResolvedValue([])
    api.listScenarios.mockResolvedValue([])
    api.createStub.mockResolvedValue(101)
    document.body.innerHTML = ''
  })

  it('docs page carries a structured create entry and the drawer echoes ownership', async () => {
    const w = await factoryDocs()
    const entry = w.find('[data-test="mock-docs-new-structured"]')
    expect(entry.exists(), 'structured create entry must exist on the docs page').toBe(true)
    const drawer = await openDrawer(w)
    expect(drawer.querySelector('[data-test="owner-echo"]'), 'drawer must echo project ownership').not.toBeNull()
    expect(drawer.querySelector('[data-test="mock-docs-f-fault-switch"]'), 'fault switch must exist').not.toBeNull()
    expect(drawer.querySelector('[data-test="mock-docs-f-name"]'), 'name field must exist').not.toBeNull()
    expect(api.createStub).toBeDefined()
  })

  it('V240 zero-fork: both consumer pages mount the SAME shared editor', () => {
    // the docs page AND the endpoint inventory page import + mount the one
    // MockStubEditorDrawer component (user ruling 10-16: act where you find —
    // two doors, and the pin here is that no page keeps a private copy).
    expect(docsRaw).toMatch(/import MockStubEditorDrawer from '@\/components\/MockStubEditorDrawer.vue'/)
    expect(docsRaw).toMatch(/<MockStubEditorDrawer/)
    expect(editorRaw).toMatch(/data-test="mock-docs-create-drawer"/)
    expect(editorRaw).toMatch(/v-for="\(?[\w,\s]*\)?\s+in\s+FAULT_TYPES"/)
  })

  it('fault-type select iterates the ONE dictionary (source-layout contract)', () => {
    // V240 lift: the form lives in the shared editor component now
    const src = editorRaw
    expect(src).toMatch(/import \{[^}]*FAULT_TYPES[^}]*\} from '@\/utils\/mockFaultLabels'/)
    const typeBlock = /<el-select[\s\S]{0,200}?data-test="mock-docs-f-fault-type"[\s\S]*?<\/el-select>/.exec(src)
    expect(typeBlock, 'fault-type select must exist in the source').not.toBeNull()
    expect(typeBlock![0]).toMatch(/v-for="\(?[\w,\s]*\)?\s+in\s+FAULT_TYPES"/)
    // `chaos` is the synthetic space-slider label — never a stub-level choice.
    expect(typeBlock![0]).not.toMatch(/chaos/)
  })

  it('delay fault submits minMs/maxMs/probability inside the builder payload', async () => {
    const w = await factoryDocs()
    await openDrawer(w)
    await setField(w, 'mock-docs-f-name', 'GET /orders slow')
    await setField(w, 'mock-docs-f-path', '/orders')
    await toggleFault(w)
    await pickFaultType(w, 'delay')
    await setField(w, 'mock-docs-f-minms', '800')
    await setField(w, 'mock-docs-f-maxms', '1200')
    await setField(w, 'mock-docs-f-probability', '20')
    await w.find('[data-test="mock-docs-f-save"]').trigger('click')
    await vi.waitFor(() => expect(api.createStub).toHaveBeenCalledTimes(1))
    const [spaceArg, payload] = api.createStub.mock.calls[0] as [number, Record<string, unknown>]
    expect(spaceArg).toBe(3)
    expect(payload.method).toBe('GET')
    expect(payload.pathPattern).toBe('/orders')
    expect(payload.pathMatchType).toBe('exact')
    const resp = JSON.parse(payload.responseJson as string) as Record<string, unknown>
    expect(resp.bodyMode).toBe('static')
    expect(resp.fault).toMatchObject({ type: 'delay', minMs: 800, maxMs: 1200, probability: 20 })
  })

  it('omitted probability stays absent — never a fabricated always/never value', async () => {
    const w = await factoryDocs()
    await openDrawer(w)
    await setField(w, 'mock-docs-f-name', 'disconnect stub')
    await setField(w, 'mock-docs-f-path', '/boot')
    await toggleFault(w)
    await pickFaultType(w, 'disconnect')
    await w.find('[data-test="mock-docs-f-save"]').trigger('click')
    await vi.waitFor(() => expect(api.createStub).toHaveBeenCalledTimes(1))
    const [, payload] = api.createStub.mock.calls[0] as [number, Record<string, unknown>]
    const resp = JSON.parse(payload.responseJson as string) as Record<string, unknown>
    expect(resp.fault).toEqual({ type: 'disconnect' })
  })

  it('parameter widgets mirror the core domain bounds (steering only)', async () => {
    const w = await factoryDocs()
    await openDrawer(w)
    await toggleFault(w)
    await pickFaultType(w, 'delay')
    const minms = nativeInput(w, 'mock-docs-f-minms')
    expect(Number(minms.min)).toBeGreaterThanOrEqual(0)
    await pickFaultType(w, 'status')
    const sc = nativeInput(w, 'mock-docs-f-statuscode')
    expect(Number(sc.min)).toBe(500)
    expect(Number(sc.max)).toBe(599)
    await pickFaultType(w, 'dribble')
    const cb = nativeInput(w, 'mock-docs-f-chunkbytes')
    const cd = nativeInput(w, 'mock-docs-f-chunkdelay')
    expect(Number(cb.min)).toBeGreaterThanOrEqual(1)
    expect(Number(cd.min)).toBeGreaterThanOrEqual(0)
    const prob = nativeInput(w, 'mock-docs-f-probability')
    expect(Number(prob.min)).toBe(0)
    expect(Number(prob.max)).toBe(100)
  })

  it('every drawer i18n key resolves in BOTH locales (dual track)', () => {
    const keys = [
      'mockDocs.structuredCreate',
      'mockDocs.drawerTitle',
      'mockDocs.fName',
      'mockDocs.fMethod',
      'mockDocs.fPath',
      'mockDocs.fPathMatch',
      'mockDocs.fStatus',
      'mockDocs.fBody',
      'mockDocs.fContentType',
      'mockDocs.faultSwitch',
      'mockDocs.faultTypeLabel',
      'mockDocs.fProbability',
      'mockDocs.fMinMs',
      'mockDocs.fMaxMs',
      'mockDocs.fStatusCode',
      'mockDocs.fChunkBytes',
      'mockDocs.fChunkDelayMs',
      'mockDocs.fSave',
      'mockDocs.createdStructured',
      'mockDocs.formNeedsNamePath',
    ]
    const pick = (obj: Record<string, any>, path: string[]) => path.reduce<any>((o, k) => (o == null ? undefined : o[k]), obj)
    for (const k of keys) {
      const seg = k.split('.')
      expect(pick(zhCN as Record<string, any>, seg), `zh-CN ${k}`).toBeTruthy()
      expect(pick(enUS as Record<string, any>, seg), `en-US ${k}`).toBeTruthy()
    }
  })
})
