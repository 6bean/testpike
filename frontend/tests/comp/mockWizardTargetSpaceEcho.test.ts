/**
 * Wizard destination-space echo contract (vision rubric high-severity fix):
 * steps 2 and 3 of the contract-direct stub wizard must keep showing WHICH
 * mock space the rules are about to be written into. Today the destination
 * only appears in step 1, so by the confirm step the user is choosing a write
 * target blind. The echo is read-only, renders identically for both mount
 * points (preset from the space workbench, picked from the API-test page) and
 * carries a stable data-test anchor for E2E.
 */
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElSelect, ElTable } from 'element-plus'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const mockApi = vi.hoisted(() => ({
  listSpaces: vi.fn(),
  previewStubDraftsAdvised: vi.fn(),
  fetchSpecUpgradePlan: vi.fn(),
  markRemovedUpgradePlan: vi.fn(),
  importStubsFromSpec: vi.fn(),
}))
vi.mock('@/api/mock', () => mockApi)

const specApi = vi.hoisted(() => ({ listApiSpecs: vi.fn(), uploadApiSpec: vi.fn() }))
vi.mock('@/api/apiSpec', () => specApi)

import wizardRaw from '@/components/mock/MockStubDirectWizard.vue?raw'
import MockStubDirectWizard from '@/components/mock/MockStubDirectWizard.vue'

const ECHO = '[data-test="stub-direct-target-space-echo"]'

const specRow = {
  id: 21,
  name: 'orders-contract',
  specVersion: 3,
  specFormat: 'OPENAPI3',
  endpoints: [{ method: 'GET', path: '/orders' }],
}

const draftRow = {
  endpointKey: 'GET /orders',
  name: 'list orders',
  method: 'GET',
  pathMatchType: 'exact',
  pathPattern: '/orders',
  matchersJson: '{}',
  responseJson: '{}',
  source: 'spec',
}

function spaceRow(over: Record<string, unknown> = {}) {
  return {
    id: 9, projectId: 3, name: '空间九', spaceKey: 'k9', alias: '别名',
    envId: null, basePathMode: 'prefix', status: 1, ...over,
  }
}

async function open(props: Record<string, unknown>) {
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const w = mount(MockStubDirectWizard, {
    props: { modelValue: false, projectId: 3, ...props },
    global: { plugins: [ElementPlus, i18n, createPinia()] },
  })
  await w.setProps({ modelValue: true })
  await flushPromises()
  // select the contract through the same row-click path the UI uses
  w.findComponent(ElTable).vm.$emit('row-click', specRow)
  await flushPromises()
  return w
}

async function toStep2(w: ReturnType<typeof mount>) {
  const btn = w.find('[data-test="stub-direct-preview-btn"]')
  expect(btn.exists(), 'preview button must be rendered in step 1').toBe(true)
  await btn.trigger('click')
  await flushPromises()
}

async function toStep3(w: ReturnType<typeof mount>) {
  const btn = w.find('[data-test="stub-direct-import-btn"]')
  expect(btn.exists(), 'import button must be rendered in step 2').toBe(true)
  await btn.trigger('click')
  await flushPromises()
}

describe('wizard step 2/3 target-space echo', () => {
  beforeEach(() => {
    specApi.listApiSpecs.mockReset().mockResolvedValue({ list: [specRow], total: 1 })
    specApi.uploadApiSpec.mockReset()
    mockApi.listSpaces.mockReset().mockResolvedValue([spaceRow()])
    mockApi.previewStubDraftsAdvised.mockReset().mockResolvedValue([draftRow])
    mockApi.fetchSpecUpgradePlan.mockReset()
      .mockResolvedValue({ added: [], changed: [], removed: [], hasPrevious: false })
    mockApi.markRemovedUpgradePlan.mockReset().mockResolvedValue(0)
    mockApi.importStubsFromSpec.mockReset()
      .mockResolvedValue({ createdStubIds: [77], skippedEndpointKeys: [] })
  })

  it('workbench mount (preset space): echo names the preset space in steps 2 and 3', async () => {
    const w = await open({ presetSpaceId: 5, presetSpaceName: '演示空间' })
    await toStep2(w)
    const echo2 = w.find(ECHO)
    expect(echo2.exists(), 'step 2 must render the destination-space echo').toBe(true)
    expect(echo2.text()).toContain('演示空间')
    await toStep3(w)
    const echo3 = w.find(ECHO)
    expect(echo3.exists(), 'step 3 must render the destination-space echo').toBe(true)
    expect(echo3.text()).toContain('演示空间')
    expect(w.find('[data-test="stub-direct-done-created"]').text()).toContain('1')
  })

  it('API-test mount (picked space): echo shows the picked space label incl. alias', async () => {
    const w = await open({ presetSpaceId: null, presetSpaceName: '' })
    w.findComponent(ElSelect).vm.$emit('update:modelValue', 9)
    await flushPromises()
    await toStep2(w)
    const echo = w.find(ECHO)
    expect(echo.exists(), 'step 2 must render the destination-space echo').toBe(true)
    expect(echo.text()).toContain('空间九（别名）')
    await toStep3(w)
    expect(w.find(ECHO).text()).toContain('空间九（别名）')
  })

  it('echo is read-only text, never a second space selector', () => {
    // The destination must not become editable at the confirm step (single
    // decision point in step 1); assert no select lives inside step 2/3.
    expect(wizardRaw).toContain('stub-direct-target-space-echo')
    const step2Block = /v-else-if="step === 1"[\s\S]*?v-else class="sd-body sd-done"/.exec(wizardRaw)?.[0] ?? ''
    expect(step2Block).not.toBe('')
    expect(step2Block).not.toContain('el-select')
    expect(step2Block).toContain('stub-direct-target-space-echo')
  })

  it('locale dual track defines the echo sentence with a {name} slot', () => {
    for (const raw of [zhCN, enUS] as unknown as string[]) {
      const block = JSON.stringify(raw)
      expect(block).toContain('targetSpaceEcho')
      expect(block).toContain('{name}')
    }
    const zh = (zhCN as any).apitest.stubImport.targetSpaceEcho as string
    const en = (enUS as any).apitest.stubImport.targetSpaceEcho as string
    expect(zh).toContain('{name}')
    expect(en).toContain('{name}')
  })
})
