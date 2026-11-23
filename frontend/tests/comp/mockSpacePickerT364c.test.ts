/**
 * Mock-space binding picker contract (Vitest browser mode, real Chromium).
 * - api mirror gate: case.ts (CaseItem/Create/Update) and suites.ts
 * (SuiteView/Create/Update) carry mockSpaceId under the same JSON name the
 * admin DTOs use — the binding cannot silently drop out of the payload.
 * - MockSpacePicker options come from the space list projection: a published
 * space shows "rules N · hit rate x%"; a space with no sampled traffic shows
 * the honest no-traffic wording, never a fake 0%; draft/retired spaces are
 * rendered but unbindable with a plain-word reason (mirrors the core gate).
 * - Picking an option emits update:modelValue with the space id (null = the
 * explicit "no mock" choice).
 * - i18n mirror gate: every mockPicker.* key the component uses exists in
 * both zh-CN and en-US.
 * Full-page wiring + reload persistence = E2E ii), not this file.
 */
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({ listSpaces: vi.fn() }))
vi.mock('@/api/mock', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  ...api,
}))

import MockSpacePicker from '@/components/MockSpacePicker.vue'
import caseApiRaw from '@/api/case?raw'
import suitesApiRaw from '@/api/suites?raw'

/** admin MockSpaceView mirror fixture (list projection with thumbnails). */
function space(over: Record<string, unknown> = {}) {
  return {
    id: 11, projectId: 7, name: 'PayStub', spaceKey: 'pay-stub', alias: null,
    envId: null, basePathMode: 'RELATIVE', status: 1, authMode: null,
    rateLimitRps: null, quotaDaily: null, priority: null, chaosPercent: 0,
    version: 1, ruleCount: 3, hitRate: 0.6667,
    createdAt: null, updatedAt: null,
    ...over,
  }
}

function factory() {
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  return mount(MockSpacePicker, {
    props: { projectId: 7, modelValue: null },
    global: { plugins: [i18n, ElementPlus] },
  })
}

/** Open the dropdown and wait until option rows exist in the popper. */
async function openDropdown(w: ReturnType<typeof factory>) {
  await w.find('[data-test="mock-space-select"] .el-select__wrapper').trigger('click')
  await vi.waitFor(() => {
    expect(document.querySelector('[data-test="mock-space-option-11"]')).not.toBeNull()
  })
}

beforeEach(() => {
  document.body.innerHTML = ''
  vi.clearAllMocks()
  api.listSpaces.mockResolvedValue([
    space(), // published with traffic sample
    space({ id: 12, name: 'IdleStub', ruleCount: 0, hitRate: null }), // published, never served
    space({ id: 13, name: 'DraftStub', status: 0, ruleCount: 1, hitRate: null }),
    space({ id: 14, name: 'DeadStub', status: 2, ruleCount: 4, hitRate: 0.5 }),
  ])
})

describe('api contract mirror gate', () => {
  it('case.ts carries mockSpaceId in all three shapes (CaseItem/Create/Update)', () => {
    expect(caseApiRaw).toContain('mockSpaceId')
    expect((caseApiRaw.match(/mockSpaceId/g) || []).length).toBeGreaterThanOrEqual(3)
  })
  it('suites.ts carries mockSpaceId in all three shapes (SuiteView/Create/Update)', () => {
    expect(suitesApiRaw).toContain('mockSpaceId')
    expect((suitesApiRaw.match(/mockSpaceId/g) || []).length).toBeGreaterThanOrEqual(3)
  })
})

describe('mockPicker i18n surface mirror', () => {
  it('every mockPicker key the component uses exists in both zh-CN and en-US', () => {
    const keys = ['placeholder', 'unbound', 'rules', 'hitRate', 'noTraffic', 'draftUnbindable', 'retiredUnbindable']
    for (const k of keys) {
      expect((zhCN as any).mockPicker?.[k], `zh-CN missing mockPicker.${k}`).toBeTruthy()
      expect((enUS as any).mockPicker?.[k], `en-US missing mockPicker.${k}`).toBeTruthy()
    }
  })
})

describe('MockSpacePicker component state', () => {
  it('loads spaces per project on mount, options carry rule count and hit rate from the projection', async () => {
    const w = factory()
    await vi.waitFor(() => { expect(api.listSpaces).toHaveBeenCalledWith(7) })
    await openDropdown(w)
    const opt = document.querySelector('[data-test="mock-space-option-11"]') as HTMLElement
    expect(opt.getAttribute('data-rule-count')).toBe('3')
    expect(opt.getAttribute('data-thumbnail')).toContain('3')
    // 0.6667 -> 67% (rounded whole percent keeps the thumbnail one-screen readable)
    expect(opt.getAttribute('data-thumbnail')).toContain('67%')
  })

  it('a space with no sampled traffic shows the honest no-traffic wording, never a fake 0%', async () => {
    const w = factory()
    await openDropdown(w)
    const opt = document.querySelector('[data-test="mock-space-option-12"]') as HTMLElement
    const thumb = opt.getAttribute('data-thumbnail') || ''
    expect(thumb).toContain((zhCN as any).mockPicker.noTraffic)
    expect(thumb).not.toMatch(/0(\.0+)?%/)
  })

  it('draft/retired spaces render in the list but stay unbindable (data-bindable=false, mirrors the core gate)', async () => {
    const w = factory()
    await openDropdown(w)
    for (const id of [13, 14]) {
      const opt = document.querySelector(`[data-test="mock-space-option-${id}"]`) as HTMLElement
      expect(opt.getAttribute('data-bindable')).toBe('false')
      expect(opt.className).toContain('is-disabled')
    }
    const pub = document.querySelector('[data-test="mock-space-option-11"]') as HTMLElement
    expect(pub.getAttribute('data-bindable')).toBe('true')
  })

  it('clicking a published space emits update:modelValue with its id', async () => {
    const w = factory()
    await openDropdown(w)
    ;(document.querySelector('[data-test="mock-space-option-11"]') as HTMLElement).click()
    await vi.waitFor(() => {
      expect(w.emitted('update:modelValue')?.[0]).toEqual([11])
    })
  })

  it('the explicit no-mock option emits null when clicked from a bound state (parent maps null -> 0 on save)', async () => {
    const w = factory()
    await openDropdown(w)
    await w.setProps({ modelValue: 11 })
    const unbound = document.querySelector('[data-test="mock-space-option-unbound"]') as HTMLElement
    expect(unbound).not.toBeNull()
    unbound.click()
    await vi.waitFor(() => {
      expect(w.emitted('update:modelValue')?.at(-1)).toEqual([null])
    })
  })
})
