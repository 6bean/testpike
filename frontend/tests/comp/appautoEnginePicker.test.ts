
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({
  listAppautoEngines: vi.fn(),
  listAppautoEngineAvailability: vi.fn(),
  listAppautoEnvProfiles: vi.fn(),
}))
vi.mock('@/api/appauto', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  ...api,
}))

import AppautoEnginePicker from '@/components/AppautoEnginePicker.vue'
import caseApiRaw from '@/api/case?raw'
import appautoApiRaw from '@/api/appauto?raw'


function engine(over: Record<string, unknown> = {}) {
  return {
    id: 1, code: 'MOBILEWRIGHT', name: 'Mobilewright',
    platformCodes: ['ANDROID', 'IOS'], deviceKindCodes: ['EMULATOR', 'ADB', 'USB'],
    defaultPriority: 10, runtimeType: 'node', runtimeVersionReq: {},
    capabilities: { 'app.launch': true }, driverRequirements: [], envCheckSpec: [],
    status: 1, installDocUrl: null, license: 'Apache-2.0',
    ...over,
  }
}


function avail(code: string, available: boolean, missingReasons: string[] = [], onlineCount = available ? 1 : 0) {
  return { code, available, onlineCount, missingReasons }
}

function factory(props: Record<string, unknown>) {
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const w = mount(AppautoEnginePicker, {
    props,
    global: { plugins: [i18n, ElementPlus] },
  })
  return w
}

beforeEach(() => {
  vi.clearAllMocks()
  api.listAppautoEngines.mockResolvedValue([
    engine(),
    engine({ id: 2, code: 'APPPIUM', name: 'Appium', defaultPriority: 30 }),
    engine({ id: 3, code: 'MINIUM', name: 'Minium', platformCodes: ['MP'], deviceKindCodes: ['WECHAT_DEVTOOLS'] }),
    engine({ id: 4, code: 'HMNEXTAUTO', name: '鸿蒙自动化', platformCodes: ['HARMONY_NEXT'], deviceKindCodes: ['HDC'], defaultPriority: 20 }),
  ])
  api.listAppautoEngineAvailability.mockResolvedValue([
    avail('MOBILEWRIGHT', true),
    avail('APPPIUM', false, ['没有在线执行器上报 APPIUM 能力——请在执行机安装引擎后启动执行器']),
    avail('MINIUM', true),
    avail('HMNEXTAUTO', false, ['没有在线执行器上报 HMNEXTAUTO 能力——请在执行机安装引擎后启动执行器']),
  ])
  api.listAppautoEnvProfiles.mockResolvedValue([])
})

describe('api 契约镜像闸', () => {
  it('case.ts 三 payload 含自动化目标三键', () => {
    expect(caseApiRaw).toContain('automationTarget')
    expect(caseApiRaw).toContain('appautoEngine')
    expect(caseApiRaw).toContain('appautoEnvProfileId')
  })
  it('appauto.ts 环境档案口镜像在位（列表 GET /appauto/env-profiles）', () => {
    expect(appautoApiRaw).toContain('env-profiles')
    expect(appautoApiRaw).toContain('listAppautoEnvProfiles')
  })
})

describe('AppautoEnginePicker 组件态', () => {
  it('target=app 仅渲染平台面含 ANDROID/IOS 的引擎卡，mp 目标仅 MP', async () => {
    const w = factory({ modelValue: null, target: 'app', projectId: 7 })
    await w.vm.$nextTick()
    await vi.waitFor(() => { expect(w.findAll('[data-test^="picker-card-"]').length).toBe(2) })
    expect(w.find('[data-test="picker-card-MOBILEWRIGHT"]').exists()).toBe(true)
    expect(w.find('[data-test="picker-card-APPPIUM"]').exists()).toBe(true)
    expect(w.find('[data-test="picker-card-MINIUM"]').exists()).toBe(false)
    await w.setProps({ target: 'mp' })
    await vi.waitFor(() => { expect(w.findAll('[data-test^="picker-card-"]').length).toBe(1) })
    expect(w.find('[data-test="picker-card-MINIUM"]').exists()).toBe(true)
  })

  it('可用性灯两态：绿=available；灰=不可用且 missingReasons 浮层数据在位', async () => {
    const w = factory({ modelValue: null, target: 'app', projectId: 7 })
    await vi.waitFor(() => { expect(w.findAll('[data-test^="picker-card-"]').length).toBe(2) })
    expect(w.find('[data-test="picker-card-MOBILEWRIGHT"]').attributes('data-available')).toBe('true')
    const gray = w.find('[data-test="picker-card-APPPIUM"]')
    expect(gray.attributes('data-available')).toBe('false')
    expect(gray.attributes('data-missing-reasons')).toContain('APPIUM')
  })

  it('点引擎卡 emit update:modelValue 携带 code（灰灯仍可选=如实标注不禁选）', async () => {
    const w = factory({ modelValue: null, target: 'app', projectId: 7 })
    await vi.waitFor(() => { expect(w.findAll('[data-test^="picker-card-"]').length).toBe(2) })
    await w.find('[data-test="picker-card-APPPIUM"]').trigger('click')
    expect(w.emitted('update:modelValue')?.[0]).toEqual(['APPPIUM'])
  })

  it('推荐徽章=匹配目标且可用引擎中 defaultPriority 最小者', async () => {
    api.listAppautoEngineAvailability.mockResolvedValue([
      avail('MOBILEWRIGHT', true),
      avail('APPPIUM', true),
      avail('MINIUM', true),
    ])
    const w = factory({ modelValue: null, target: 'app', projectId: 7 })
    await vi.waitFor(() => { expect(w.findAll('[data-test^="picker-card-"]').length).toBe(2) })
    expect(w.find('[data-test="picker-card-MOBILEWRIGHT"]').attributes('data-recommended')).toBe('true')
    expect(w.find('[data-test="picker-card-APPPIUM"]').attributes('data-recommended')).toBe('false')
  })

  it('target=web 不渲染卡片面（清挂联动=编辑区隐藏由父级承载）', async () => {
    const w = factory({ modelValue: null, target: 'web', projectId: 7 })
    await w.vm.$nextTick()
    await vi.waitFor(() => { expect(w.find('[data-test="appauto-engine-picker"]').exists()).toBe(false) })
  })

  it('无匹配启用引擎=人话空态不白屏', async () => {
    api.listAppautoEngines.mockResolvedValue([
      engine({ id: 3, code: 'MINIUM', platformCodes: ['MP'] }),
    ])
    const w = factory({ modelValue: null, target: 'app', projectId: 7 })
    await vi.waitFor(() => { expect(w.find('[data-test="picker-empty"]').exists()).toBe(true) })
    expect(w.find('[data-test="picker-empty"]').text()).toContain('引擎')
  })
})

describe('T342 包③ 端属性三段流程：harmony 目标与自动常驻档', () => {
  it('target=harmony 仅渲染平台面含 HARMONY_NEXT 的引擎卡（镜像 core wantedPlatformsForTarget 三分支）', async () => {
    const w = factory({ modelValue: null, target: 'harmony', projectId: 7 })
    await w.vm.$nextTick()
    await vi.waitFor(() => { expect(w.findAll('[data-test^="picker-card-"]').length).toBe(1) })
    expect(w.find('[data-test="picker-card-HMNEXTAUTO"]').exists()).toBe(true)
    expect(w.find('[data-test="picker-card-MOBILEWRIGHT"]').exists()).toBe(false)
  })

  it('「自动」常驻档在位：modelValue=null 即自动档选中态，点自动档 emit update:modelValue null（禁前端猜引擎）', async () => {
    const w = factory({ modelValue: null, target: 'app', projectId: 7 })
    await vi.waitFor(() => { expect(w.findAll('[data-test^="picker-card-"]').length).toBeGreaterThanOrEqual(2) })
    const auto = w.find('[data-test="picker-auto-option"]')
    expect(auto.exists(), '自动档卡片缺失（v2.64：引擎=运行时可选项默认自动）').toBe(true)
    expect(auto.attributes('aria-pressed')).toBe('true')
    await auto.trigger('click')
    expect(w.emitted('update:modelValue')?.[0]).toEqual([null])
  })

  it('显式挂引擎时自动档非选中；点具体引擎卡 emit code（既有语义零回归）', async () => {
    const w = factory({ modelValue: 'MOBILEWRIGHT', target: 'app', projectId: 7 })
    await vi.waitFor(() => { expect(w.findAll('[data-test^="picker-card-"]').length).toBeGreaterThanOrEqual(2) })
    const auto = w.find('[data-test="picker-auto-option"]')
    expect(auto.exists()).toBe(true)
    expect(auto.attributes('aria-pressed')).toBe('false')
    await w.find('[data-test="picker-card-APPPIUM"]').trigger('click')
    expect(w.emitted('update:modelValue')?.[0]).toEqual(['APPPIUM'])
  })
})

describe('i18n 触面镜像闸', () => {
  it('组件用到的 appauto.picker.* 键 zh-CN/en-US 双写全命中', () => {
    const usedKeys = [
      'lightGreen', 'lightGray', 'recommended', 'emptyEngines', 'loadFailed',
      'autoOption', 'autoHint', 'autoSelectedTag',
    ]
    const zh = (zhCN as Record<string, any>).appauto?.picker
    const en = (enUS as Record<string, any>).appauto?.picker
    for (const k of usedKeys) {
      expect(zh?.[k], `zh-CN 缺 appauto.picker.${k}`).toBeTruthy()
      expect(en?.[k], `en-US 缺 appauto.picker.${k}`).toBeTruthy()
    }
  })
})
