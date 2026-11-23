
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({
  listAppautoDevices: vi.fn(),
  listDeviceLeaseRuns: vi.fn(),
}))
vi.mock('@/api/appauto', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/appauto')>()
  return {
    ...actual,
    listAppautoDevices: api.listAppautoDevices,
    listDeviceLeaseRuns: api.listDeviceLeaseRuns,
  }
})

import DeviceWallView from '@/views/appauto/DeviceWallView.vue'
import appautoApiRaw from '@/api/appauto?raw'
import { ElTooltip } from 'element-plus'


function device(over: Record<string, unknown> = {}) {
  return {
    id: 1, projectId: 1, name: 'Pixel-7-测试机', serial: 'EM5A0001', deviceType: 'ADB',
    adbAddress: '10.0.0.5:5555', model: 'Pixel 7', osVersion: 'Android 14',
    resolution: '1080x2404', dpi: 420, labels: ['smoke'],
    healthState: 'GREEN', consecutiveFailures: 0,
    lastSeenAt: '2026-10-06T18:30:00', lastOverheatAt: null, ...over,
  }
}

async function factory(rows: unknown[]) {
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN, 'en-US': enUS } })
  const pinia = createPinia()
  setActivePinia(pinia)
  // Since AK-batch wiring, DeviceWallView consumes useProjectPageContext
  // (reads route.query deep-link params) — the fixture needs a memory router
  // or setup crashes on route.query.
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div></div>' } }],
  })
  await router.push('/appauto/devices?projectId=1')
  await router.isReady()
  const w = mount(DeviceWallView, { global: { plugins: [ElementPlus, i18n, pinia, router] } })
  // Rows are fed manually after mount (deep-link projectId=1 already resolved
  // by the context; no need to write vm.projectId directly).
  await flushPromises()
  w.vm.rows = rows
  w.vm.loading = false
  await flushPromises()
  return w
}

beforeEach(() => {
  api.listAppautoDevices.mockReset().mockResolvedValue([])
  api.listDeviceLeaseRuns.mockReset().mockResolvedValue([])
})

describe('设备墙温度徽章（T329 段④-②）', () => {
  it('api 镜像闸：AppautoDevice 接口含 lastOverheatAt 字段', () => {
    expect(appautoApiRaw).toMatch(/lastOverheatAt\?:\s*string\s*\|\s*null/)
  })

  it('i18n 双轨闸：过热提示键 zh-CN/en-US 双写命中', () => {
    const zh = ((zhCN as Record<string, any>).appauto.wall).overheatHint
    const en = ((enUS as Record<string, any>).appauto.wall).overheatHint
    expect(typeof zh).toBe('string')
    expect(zh).toContain('{time}')
    expect(typeof en).toBe('string')
    expect(en).toContain('{time}')
  })

  it('越阈注记在位=过热徽章显，tooltip 含可读时刻全文（不截断）', async () => {
    const w = await factory([device({ lastOverheatAt: '2026-10-06T18:31:42' })])
    const badge = w.find('[data-test="device-wall-overheat-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('过热')
    const tip = w.findAllComponents(ElTooltip).find((x) => String(x.props('content')).includes('末次高温越阈'))
    expect(tip).toBeDefined()
    const content = String(tip!.props('content'))
    expect(content).toContain('2026-10-06 18:31:42')
    expect(content).not.toContain('T18')
  })

  it('未过热（null）=徽章缺位不装热（诚实缺位禁默认值冒充）', async () => {
    const w = await factory([device({ lastOverheatAt: null })])
    expect(w.find('[data-test="device-wall-overheat-badge"]').exists()).toBe(false)
  })

  it('字段缺位（undefined 旧缓存行）=徽章缺位（前端零猜测）', async () => {
    const d = device()
    delete (d as Record<string, unknown>).lastOverheatAt
    const w = await factory([d])
    expect(w.find('[data-test="device-wall-overheat-badge"]').exists()).toBe(false)
  })
})
