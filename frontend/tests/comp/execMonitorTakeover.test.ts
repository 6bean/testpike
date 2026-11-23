
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'

const api = vi.hoisted(() => ({
  getTaskMonitorFrames: vi.fn(),
  requestTakeover: vi.fn(),
  agreeTakeover: vi.fn(),
  rejectTakeover: vi.fn(),
  forceTakeover: vi.fn(),
  endTakeover: vi.fn(),
  getTakeover: vi.fn(),
  enqueueTakeoverInput: vi.fn(),
}))
vi.mock('@/api/executor', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/executor')>()
  return { ...actual, getTaskMonitorFrames: api.getTaskMonitorFrames }
})
vi.mock('@/api/appauto', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/appauto')>()
  return {
    ...actual,
    requestTakeover: api.requestTakeover,
    agreeTakeover: api.agreeTakeover,
    rejectTakeover: api.rejectTakeover,
    forceTakeover: api.forceTakeover,
    endTakeover: api.endTakeover,
    getTakeover: api.getTakeover,
    enqueueTakeoverInput: api.enqueueTakeoverInput,
  }
})

const auth = vi.hoisted(() => ({ userId: 100, isAdmin: false, perms: [] as string[] }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: { userId: auth.userId, username: 'u', nickName: 'u', roles: auth.isAdmin ? ['ADMIN'] : [] },
    get isAdmin() { return auth.isAdmin },
    hasPerm: (code: string) => auth.isAdmin || auth.perms.includes(code),
  }),
}))

import ExecMonitorWindow from '@/components/ExecMonitorWindow.vue'

const TAKEOVER_KEY = 'appauto:watch:takeover'
const IMG = base64Jpeg1x1()
function base64Jpeg1x1(): string {
  const hex = 'ffd8ffe000104a46494600010100000100010000ffdb00430008060607060508070707'
    + '090908080a0c140d0c0b0b0c1912130f141d181f1e1d1a191c1c20242e2720222c211c'
    + '1c2837292c30313434341c27393d38323c2e343433ffc0000b0800010001011100ffc4'
    + '001f0000010501010101010100000000000000000102030405060708090a0bffc400b510'
    + '0002010303020403050504040000017d0102030004110512213141061351610722711432'
    + '8191a1082342b1c11552d1f02433627282090a161718191a25262728292a343536373839'
    + '3a434445464748494a535455565758595a636465666768696a737475767778797a838485'
    + '868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5'
    + 'c6c7c8c9cad1d2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffd9'
  const bytes = Uint8Array.from(hex.match(/../g)!.map((h) => parseInt(h, 16)))
  let bin = ''
  bytes.forEach((b) => { bin += String.fromCharCode(b) })
  return btoa(bin)
}

function dev(over: Record<string, unknown> = {}) {
  return { id: 11, projectId: 1, name: 'Pixel-7', serial: 'emulator-5554', deviceType: 'EMULATOR',
    labels: [], healthState: 'GREEN', consecutiveFailures: 0, platform: 'ANDROID', ...over }
}

function takeover(state: string, over: Record<string, unknown> = {}) {
  return { id: 7, deviceId: 11, projectId: 1, taskId: 9, requesterId: 100, occupierId: 200,
    state, reason: '联调', decidedBy: null, decidedAt: null,
    expiresAt: '2026-10-08T12:00:00', recordingRef: null, createdAt: '2026-10-08T10:00:00', ...over }
}

function frames(sensitive = false) {
  return { frames: [{ taskId: 9, stepNo: 1, image: IMG, sensitive }], dropped: 0, trimmed: 0 }
}

async function factory(props: Record<string, unknown> = {}) {
  if (!document.getElementById('t332-stage-size')) {
    const s = document.createElement('style')
    s.id = 't332-stage-size'
    s.textContent = '.monitor-img { width: 320px; height: 480px; max-width: none; }'
    document.head.appendChild(s)
  }
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  const w = mount(ExecMonitorWindow, {
    props: { taskId: 9, active: true, ...props },
    // The monitor window reads the global project context store when a pool
    // device falls back to the header project, so the harness must install Pinia.
    global: { plugins: [ElementPlus, i18n, createPinia()] },
    attachTo: document.body,
  })
  await flushPromises()
  return w
}


async function activeWindow(deviceOver: Record<string, unknown> = {}) {
  api.getTakeover.mockResolvedValue(takeover('active'))
  api.getTaskMonitorFrames.mockResolvedValue(frames())
  return factory({ device: dev(deviceOver), takeover: takeover('active') })
}

beforeEach(() => {
  auth.userId = 100
  auth.isAdmin = false
  auth.perms = [TAKEOVER_KEY]
  api.getTaskMonitorFrames.mockReset().mockResolvedValue({ frames: [], dropped: 0, trimmed: 0 })
  for (const fn of [api.requestTakeover, api.agreeTakeover, api.rejectTakeover, api.forceTakeover,
    api.endTakeover, api.getTakeover, api.enqueueTakeoverInput]) fn.mockReset()
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('监看窗接管操作区（T332 切片③b-2）', () => {
  it('无设备会话=接管区不现身零接管外呼（存量监看零漂移）', async () => {
    const w = await factory({ device: null })
    expect(w.find('[data-test="takeover-zone"]').exists()).toBe(false)
    expect(api.getTakeover).not.toHaveBeenCalled()
    expect(api.requestTakeover).not.toHaveBeenCalled()
    w.unmount()
  })

  it('缺 watch:takeover 权限=申请钮不现身，出线人话拒因', async () => {
    auth.perms = []
    const w = await factory({ device: dev() })
    expect(w.find('[data-test="takeover-request-btn"]').exists()).toBe(false)
    expect(w.find('[data-test="takeover-perm-hint"]').text()).toContain('watch:takeover')
    w.unmount()
  })

  it('空闲态：申请接管钮出线，点击带 deviceId/taskId 走 request 口并转入等待态', async () => {
    api.requestTakeover.mockResolvedValue(takeover('pending'))
    const w = await factory({ device: dev() })
    await w.find('[data-test="takeover-request-btn"]').trigger('click')
    await flushPromises()
    expect(api.requestTakeover).toHaveBeenCalledTimes(1)
    expect(api.requestTakeover.mock.calls[0][0]).toMatchObject({ deviceId: 11, taskId: 9 })
    expect(w.find('[data-test="takeover-pending-wait"]').exists()).toBe(true)
    w.unmount()
  })

  it('pending 身份分派：申请人本人=等待无判定钮；占用者本人=同意/驳回双钮', async () => {
    api.requestTakeover.mockResolvedValue(takeover('pending', { requesterId: 100, occupierId: 200 }))
    const w = await factory({ device: dev() })
    await w.find('[data-test="takeover-request-btn"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="takeover-agree-btn"]').exists()).toBe(false)
    w.unmount()

    api.getTakeover.mockResolvedValue(takeover('pending', { requesterId: 300, occupierId: 100 }))
    const w2 = await factory({ device: dev(), takeover: takeover('pending', { requesterId: 300, occupierId: 100 }) })
    expect(w2.find('[data-test="takeover-agree-btn"]').exists()).toBe(true)
    expect(w2.find('[data-test="takeover-reject-btn"]').exists()).toBe(true)
    await w2.find('[data-test="takeover-agree-btn"]').trigger('click')
    await flushPromises()
    expect(api.agreeTakeover).toHaveBeenCalledWith(7)
    w2.unmount()
  })

  it('接管态轮询并入既有 2s tick 节律（零独立计时器；getTakeover 数据源驱动态迁移）', async () => {
    vi.useFakeTimers()
    try {
      api.getTakeover.mockResolvedValue(takeover('active'))
      const w = await factory({ device: dev(), takeover: takeover('pending') })
      const before = api.getTakeover.mock.calls.length
      vi.advanceTimersByTime(2000)
      await flushPromises()
      expect(api.getTakeover.mock.calls.length).toBeGreaterThan(before)
      expect(w.find('[data-test="takeover-active-hint"]').exists()).toBe(true)
      w.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('active+ANDROID：画面原地按下抬起=装配 tap 入队（真矩形归一≈中心 5000）', async () => {
    api.enqueueTakeoverInput.mockResolvedValue({ accepted: 1 })
    const w = await activeWindow()
    const img = w.find('[data-test="monitor-frame"]')
    expect(img.exists()).toBe(true)
    const rect = (img.element as HTMLElement).getBoundingClientRect()
    expect(rect.width).toBeGreaterThan(0)
    const cx = rect.left + rect.width / 2
    const cy = rect.top + rect.height / 2
    await img.trigger('mousedown', { clientX: cx, clientY: cy, button: 0 })
    await img.trigger('mouseup', { clientX: cx, clientY: cy, button: 0 })
    await flushPromises()
    expect(api.enqueueTakeoverInput).toHaveBeenCalledTimes(1)
    const [id, cmds] = api.enqueueTakeoverInput.mock.calls[0]
    expect(id).toBe(7)
    expect(cmds).toHaveLength(1)
    expect(cmds[0].verb).toBe('tap')
    expect(Math.abs(cmds[0].x - 5000)).toBeLessThanOrEqual(300)
    expect(Math.abs(cmds[0].y - 5000)).toBeLessThanOrEqual(300)
    w.unmount()
  })

  it('active+ANDROID：按下拖动抬起=装配 swipe 入队（起止点序正确）', async () => {
    api.enqueueTakeoverInput.mockResolvedValue({ accepted: 1 })
    const w = await activeWindow()
    const img = w.find('[data-test="monitor-frame"]')
    const rect = (img.element as HTMLElement).getBoundingClientRect()
    await img.trigger('mousedown', { clientX: rect.left + 10, clientY: rect.top + 10, button: 0 })
    await img.trigger('mouseup', { clientX: rect.left + rect.width - 10, clientY: rect.top + rect.height - 10, button: 0 })
    await flushPromises()
    const cmds = api.enqueueTakeoverInput.mock.calls[0][1]
    expect(cmds[0].verb).toBe('swipe')
    expect(cmds[0].x2).toBeGreaterThan(cmds[0].x1)
    expect(cmds[0].y2).toBeGreaterThan(cmds[0].y1)
    w.unmount()
  })

  it('active+ANDROID：键盘映射键入队（Enter→66），表外键零外呼', async () => {
    api.enqueueTakeoverInput.mockResolvedValue({ accepted: 1 })
    const w = await activeWindow()
    const stage = w.find('[data-test="monitor-stage"]')
    await stage.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(api.enqueueTakeoverInput).toHaveBeenCalledTimes(1)
    expect(api.enqueueTakeoverInput.mock.calls[0][1][0]).toMatchObject({ verb: 'key', keycode: 66 })
    api.enqueueTakeoverInput.mockClear()
    await stage.trigger('keydown', { key: 'F7' })
    await flushPromises()
    expect(api.enqueueTakeoverInput).not.toHaveBeenCalled()
    w.unmount()
  })

  it('active+ANDROID：文本框发送=装配 text 入队；129 字超限诚实拒零外呼', async () => {
    api.enqueueTakeoverInput.mockResolvedValue({ accepted: 1 })
    const w = await activeWindow()
    await w.find('[data-test="takeover-text-input"]').setValue('你好设备')
    await w.find('[data-test="takeover-text-send"]').trigger('click')
    await flushPromises()
    expect(api.enqueueTakeoverInput).toHaveBeenCalledWith(7, [
      { platform: 'ANDROID', serial: 'emulator-5554', verb: 'text', text: '你好设备' },
    ])
    api.enqueueTakeoverInput.mockClear()
    await w.find('[data-test="takeover-text-input"]').setValue('x'.repeat(129))
    await w.find('[data-test="takeover-text-send"]').trigger('click')
    await flushPromises()
    expect(api.enqueueTakeoverInput).not.toHaveBeenCalled()
    w.unmount()
  })

  it('HARMONY_NEXT：点滑可发、键/文本钮置灰并出鸿蒙拒因', async () => {
    const w = await activeWindow({ platform: 'HARMONY_NEXT' })
    const keyBtn = w.find('[data-test="takeover-key-back-btn"]')
    expect(keyBtn.exists()).toBe(true)
    expect(keyBtn.attributes('disabled')).not.toBeUndefined()
    expect(w.find('[data-test="takeover-gray-reason"]').text()).toContain('鸿蒙设备暂只支持点按与滑动')
    api.enqueueTakeoverInput.mockResolvedValue({ accepted: 1 })
    const img = w.find('[data-test="monitor-frame"]')
    const rect = (img.element as HTMLElement).getBoundingClientRect()
    await img.trigger('mousedown', { clientX: rect.left + 5, clientY: rect.top + 5, button: 0 })
    await img.trigger('mouseup', { clientX: rect.left + 5, clientY: rect.top + 5, button: 0 })
    await flushPromises()
    expect(api.enqueueTakeoverInput).toHaveBeenCalledTimes(1)
    expect(api.enqueueTakeoverInput.mock.calls[0][1][0].verb).toBe('tap')
    w.unmount()
  })

  it('无注入面平台（MP）=操作面整置灰+人话拒因；未登记平台=补录引导拒因', async () => {
    const w = await activeWindow({ platform: 'MP' })
    expect(w.find('[data-test="takeover-gray-reason"]').text()).toContain('暂无网页注入通道')
    const w2 = await activeWindow({ platform: null })
    expect(w2.find('[data-test="takeover-gray-reason"]').text()).toContain('补录平台')
    w.unmount()
    w2.unmount()
  })

  it('G4 敏感遮罩期：画面点击零投递+接管暂停提示', async () => {
    vi.useFakeTimers()
    try {
      api.getTakeover.mockResolvedValue(takeover('active'))
      api.getTaskMonitorFrames
        .mockResolvedValueOnce(frames(false))
        .mockResolvedValue(frames(true))
      const w = await factory({ device: dev(), takeover: takeover('active') })
      vi.advanceTimersByTime(2000)
      await flushPromises()
      expect(w.find('[data-test="monitor-sensitive-mask"]').exists()).toBe(true)
      const img = w.find('[data-test="monitor-frame"]')
      expect(img.exists()).toBe(true)
      const rect = (img.element as HTMLElement).getBoundingClientRect()
      await img.trigger('mousedown', { clientX: rect.left + 5, clientY: rect.top + 5, button: 0 })
      await img.trigger('mouseup', { clientX: rect.left + 5, clientY: rect.top + 5, button: 0 })
      await flushPromises()
      expect(api.enqueueTakeoverInput).not.toHaveBeenCalled()
      expect(w.find('[data-test="takeover-sensitive-paused"]').exists()).toBe(true)
      w.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('ADMIN 对 pending 出强接钮；forced 视同操作态；结束接管回空闲', async () => {
    auth.isAdmin = true
    api.getTakeover.mockResolvedValue(takeover('pending'))
    const w = await factory({ device: dev(), takeover: takeover('pending') })
    expect(w.find('[data-test="takeover-force-btn"]').exists()).toBe(true)
    await w.find('[data-test="takeover-force-btn"]').trigger('click')
    await flushPromises()
    expect(api.forceTakeover).toHaveBeenCalledWith(7)
    w.unmount()

    api.getTakeover.mockResolvedValue(takeover('forced'))
    api.endTakeover.mockResolvedValue(takeover('ended'))
    const w2 = await factory({ device: dev(), takeover: takeover('forced') })
    await flushPromises()
    expect(w2.find('[data-test="takeover-end-btn"]').exists()).toBe(true)
    await w2.find('[data-test="takeover-end-btn"]').trigger('click')
    await flushPromises()
    expect(api.endTakeover).toHaveBeenCalledWith(7)
    expect(w2.find('[data-test="takeover-active-hint"]').exists()).toBe(false)
    expect(w2.find('[data-test="takeover-request-btn"]').exists()).toBe(true)
    w2.unmount()
  })
})
