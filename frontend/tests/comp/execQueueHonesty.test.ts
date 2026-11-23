
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'

const api = vi.hoisted(() => ({
  listAppautoDevices: vi.fn(),
  listDeviceLeaseRuns: vi.fn(),
  listAppautoEngineAvailability: vi.fn(),
}))
vi.mock('@/api/appauto', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/appauto')>()
  return {
    ...actual,
    listAppautoDevices: api.listAppautoDevices,
    listDeviceLeaseRuns: api.listDeviceLeaseRuns,
    listAppautoEngineAvailability: api.listAppautoEngineAvailability,
  }
})

import ExecQueueHonestyPanel from '@/components/ExecQueueHonestyPanel.vue'

function appTask(status: number, over: Record<string, unknown> = {}) {
  return { id: 9, projectId: 1, caseId: 2, status, schemaVersion: 'appauto-v1', ...over }
}

async function factory(task: unknown) {
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  const w = mount(ExecQueueHonestyPanel, { props: { task }, global: { plugins: [ElementPlus, i18n] } })
  await flushPromises()
  return w
}

beforeEach(() => {
  api.listAppautoDevices.mockReset().mockResolvedValue([])
  api.listDeviceLeaseRuns.mockReset().mockResolvedValue([])
  api.listAppautoEngineAvailability.mockReset().mockResolvedValue([])
})

describe('排队诚实面板（T310 包④）', () => {
  it('网页任务整区隐藏且零外呼（存量零漂移）', async () => {
    const w = await factory({ ...appTask(1), schemaVersion: 'appauto-v2' })
    expect(w.find('[data-test="queue-honesty"]').exists()).toBe(false)
    expect(api.listAppautoDevices).not.toHaveBeenCalled()
    expect(api.listAppautoEngineAvailability).not.toHaveBeenCalled()
  })

  it('APP 终态任务隐藏且零外呼（不扰民）', async () => {
    const w = await factory(appTask(3))
    expect(w.find('[data-test="queue-honesty"]').exists()).toBe(false)
    expect(api.listAppautoDevices).not.toHaveBeenCalled()
  })

  it('APP 待派发：ACTIVE 租约出线占用者+预计释放，不可用引擎出线人话排队原因', async () => {
    api.listAppautoDevices.mockResolvedValue([
      { id: 11, projectId: 1, name: 'Pixel-7', serial: 'emulator-5554', deviceType: 'EMULATOR',
        labels: [], healthState: 'GREEN', consecutiveFailures: 0 },
    ])
    api.listDeviceLeaseRuns.mockResolvedValue([
      { id: 1, deviceId: 11, projectId: 1, batchId: 5, holderExecutorId: 'ex-mac-01',
        state: 'ACTIVE', acquiredAt: '2026-10-04T10:00:00', expiresAt: '2026-10-04T10:30:00',
        releasedAt: null, releaseReason: null },
    ])
    api.listAppautoEngineAvailability.mockResolvedValue([
      { code: 'MOBILEWRIGHT', available: false, onlineCount: 0,
        missingReasons: ['无在线执行器上报该引擎能力——任务将排队等待'] },
    ])
    const w = await factory(appTask(1))
    expect(w.find('[data-test="queue-honesty"]').exists()).toBe(true)
    expect(w.text()).toContain('Pixel-7')
    expect(w.text()).toContain('ex-mac-01')
    expect(w.text()).toContain('排队')
    expect(w.text()).toContain('无在线执行器上报该引擎能力')
  })

  it('设备全空闲=诚实「等待具备能力的执行器领取」不装派发了', async () => {
    api.listAppautoDevices.mockResolvedValue([
      { id: 12, projectId: 1, name: 'Mate-60', serial: 'abc', deviceType: 'USB',
        labels: [], healthState: 'GREEN', consecutiveFailures: 0 },
    ])
    api.listDeviceLeaseRuns.mockResolvedValue([
      { id: 2, deviceId: 12, projectId: 1, batchId: null, holderExecutorId: 'ex-1',
        state: 'RELEASED', acquiredAt: null, expiresAt: null,
        releasedAt: '2026-10-04T09:00:00', releaseReason: '完成归还' },
    ])
    api.listAppautoEngineAvailability.mockResolvedValue([
      { code: 'MOBILEWRIGHT', available: true, onlineCount: 2, missingReasons: [] },
    ])
    const w = await factory(appTask(2))
    expect(w.find('[data-test="queue-idle"]').exists()).toBe(true)
    expect(w.text()).not.toContain('ex-1')
  })

  it('拉取失败=诚实空态不炸页面（缺位≠空闲）', async () => {
    api.listAppautoDevices.mockRejectedValue(new Error('50000'))
    api.listAppautoEngineAvailability.mockRejectedValue(new Error('50000'))
    const w = await factory(appTask(1))
    expect(w.find('[data-test="queue-honesty"]').exists()).toBe(true)
    expect(w.find('[data-test="queue-unavailable"]').exists()).toBe(true)
  })
})
