/**
 * segment d — sliding-window hard contract for the process tail (R62).
 * Covers: memory tail-trim cap, heartbeat / interrupted / first-frame
 * degrade markers, DOM virtualisation with constant container height, the
 * unread badge while the follow is paused, and the hot-key config door.
 * The exact boundingBox geometry assertions (1→500 frames, height delta 0)
 * belong to the E2E real-chain spectrum (tests/e2e), not this file.
 * Names of cases stay Chinese for suite-wide readability parity with the
 * sibling taskTailDrawer.test.ts spectra.
 */
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'

vi.mock('@/api/request', () => ({
  getToken: () => 'test-token',
  request: vi.fn(),
  // Mirror every export the tail module graph names at import time:
  // the r19 renewal fix added these to taskEvents.ts; a browser-mode mock
  // factory missing a named export hard-fails collection (pre-existing red
  // cleared 11-22, proven identical on the clean tree via worktree probe).
  ensureFreshAccessToken: vi.fn(async () => 'test-token'),
  renewAccessTokenOnce: vi.fn(async () => false),
}))

const configRows = vi.fn()
vi.mock('@/api/config', () => ({
  listConfigs: (...args: unknown[]) => configRows(...args),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPerm: () => true }),
}))

import { useTestRunTail, type TestRunEvent } from '@/composables/useTestRunTail'
import {
  loadTailWindowConfig,
  resetTailWindowConfigLoad,
  surveyTailCap,
  tailFirstFrameMs,
  tailHeartbeatSeconds,
  tailTerminalDwellMs,
} from '@/composables/tailWindowConfig'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'

function row(cursor: number, summary = `step ${cursor}`): TestRunEvent {
  return { cursor, level: 'step', summary, detail: null, landedAt: '2026-11-18T10:00:00' }
}

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('useTestRunTail window options (T661 d)', () => {
  it('tail-cap：内存只保留最新 cap 帧，旧帧尾剪不丢游标', async () => {
    let calls = 0
    const fetch = async () => {
      calls += 1
      if (calls === 1) return Array.from({ length: 6 }, (_, i) => row(i + 1))
      if (calls === 2) return Array.from({ length: 4 }, (_, i) => row(i + 7))
      return []
    }
    const tail = useTestRunTail(fetch, {
      terminal: (r) => r.cursor >= 10,
      cap: 4,
      emptyGracePolls: 1,
      pollIntervalMs: 10,
    })
    await tail.openRun({ runToken: 't' })
    expect(tail.events.value.map((r) => r.cursor)).toEqual([7, 8, 9, 10])
    expect(tail.interrupted.value).toBe(false)
  })

  it('静默超 heartbeatMs=心跳态；无终态帧耗尽空轮=中断标记', async () => {
    vi.useFakeTimers()
    // no frames ever land: after three empty-grace polls the door flips
    // stalled (silence >= heartbeatMs) and interrupted (no terminal frame)
    const tail = useTestRunTail(async () => [], {
      emptyGracePolls: 3,
      heartbeatMs: 1200,
      pollIntervalMs: 500,
    })
    const p = tail.openRun({ runToken: 't' })
    await vi.advanceTimersByTimeAsync(3000)
    await p
    expect(tail.stalled.value).toBe(true)
    expect(tail.interrupted.value).toBe(true)
  })

  it('终态驻留（T661 e）：终态帧先上屏，停留 terminalDwellMs 后才自动收档', async () => {
    vi.useFakeTimers()
    let calls = 0
    const fetch = async () => {
      calls += 1
      if (calls === 1) return [row(1, '整页测绘：http://demo/page')]
      if (calls === 2) return [row(2, '测绘完成｜入账候选 2 条·已确认 0 条｜步 3 页 1')]
      return []
    }
    const tail = useTestRunTail(fetch, {
      terminal: (r) => r.summary.startsWith('测绘完成'),
      closeOnTerminal: true,
      terminalDwellMs: 1500,
      pollIntervalMs: 50,
    })
    const p = tail.openRun({ runToken: 't' })
    await vi.advanceTimersByTimeAsync(120)
    // the terminal frame is in the buffer and the drawer is STILL open —
    // the closing reason must be readable before the window collapses
    expect(tail.events.value.some((r) => r.summary.startsWith('测绘完成'))).toBe(true)
    expect(tail.drawerOpen.value).toBe(true)
    await vi.advanceTimersByTimeAsync(2000)
    await p
    expect(tail.drawerOpen.value).toBe(false)
    expect(tail.interrupted.value).toBe(false)
  })

  it('驻留缺省 0 零漂移：不传 terminalDwellMs 时终帧后即收档', async () => {
    let calls = 0
    const fetch = async () => {
      calls += 1
      if (calls === 1) return [row(2, '测绘完成｜入账候选 0 条')]
      return []
    }
    const tail = useTestRunTail(fetch, {
      terminal: (r) => r.summary.startsWith('测绘完成'),
      closeOnTerminal: true,
      emptyGracePolls: 1,
      pollIntervalMs: 10,
    })
    await tail.openRun({ runToken: 't' })
    expect(tail.drawerOpen.value).toBe(false)
  })

  it('旧同步门（登录态/凭据）缺省零漂移：无 cap 尾剪、终态启发式保留', async () => {
    let calls = 0
    const fetch = async () => {
      calls += 1
      if (calls === 1) return [row(1, '校验结束：通过')]
      if (calls === 2) return [row(2, '回执 通过')]
      return []
    }
    const tail = useTestRunTail(fetch, { pollIntervalMs: 10 })
    await tail.openRun({ runToken: 't', verdict: 'ok', verdictHuman: '通过' })
    expect(tail.events.value.map((r) => r.cursor)).toEqual([1, 2])
    expect(tail.drawerOpen.value).toBe(true)
  })
})

describe('tailWindowConfig 热更键门（T661 d/e keys）', () => {
  afterEach(() => resetTailWindowConfigLoad())

  it('四键取值：element.survey.tail-cap / ui.tail.* 落库值上屏', async () => {
    configRows.mockResolvedValue([
      { configKey: 'element.survey.tail-cap', configValue: '600' },
      { configKey: 'ui.tail.heartbeat-seconds', configValue: '20' },
      { configKey: 'ui.tail.first-frame-ms', configValue: '3000' },
      { configKey: 'ui.tail.terminal-dwell-ms', configValue: '2500' },
    ])
    await loadTailWindowConfig()
    expect(surveyTailCap.value).toBe(600)
    expect(tailHeartbeatSeconds.value).toBe(20)
    expect(tailFirstFrameMs.value).toBe(3000)
    expect(tailTerminalDwellMs.value).toBe(2500)
  })

  it('越界值与拉取失败=保守缺省 400/15/2000/1500，不放大坏值', async () => {
    configRows.mockResolvedValue([
      { configKey: 'element.survey.tail-cap', configValue: '9' },
      { configKey: 'ui.tail.heartbeat-seconds', configValue: '0' },
      { configKey: 'ui.tail.terminal-dwell-ms', configValue: '999999' },
    ])
    await loadTailWindowConfig()
    expect(surveyTailCap.value).toBe(400)
    expect(tailHeartbeatSeconds.value).toBe(15)
    expect(tailFirstFrameMs.value).toBe(2000)
    expect(tailTerminalDwellMs.value).toBe(1500)
    resetTailWindowConfigLoad()
    configRows.mockRejectedValue(new Error('config door down'))
    await loadTailWindowConfig()
    expect(surveyTailCap.value).toBe(400)
  })
})

async function mountDrawer(props: Record<string, unknown>) {
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  // attachTo matters for the geometry spectra: a detached tree has zero
  // layout in a real browser (boundingBox/clientHeight would fake-fail)
  return mount(TaskTailDrawer, { props, attachTo: document.body, global: { plugins: [ElementPlus, i18n] } })
}

describe('TaskTailDrawer 窗口化契约（T661 d）', () => {
  it('500 帧：DOM 行数远小于源（虚拟窗生效），底部垫块补足差量', async () => {
    const events = Array.from({ length: 500 }, (_, i) => row(i + 1))
    const w = await mountDrawer({ events, live: true })
    const rendered = w.findAll('[data-windowed-row]')
    expect(rendered.length).toBeGreaterThan(0)
    expect(rendered.length).toBeLessThanOrEqual(40)
    expect(rendered.length * 2).toBeLessThanOrEqual(500)
    expect(w.find('[data-test="task-tail-pad-bottom"]').exists()).toBe(true)
    w.unmount()
  })

  it('容器高度恒定：1 帧与 500 帧的列表盒高度一致（弹窗不随内容放大）', async () => {
    const small = await mountDrawer({ events: [row(1)], live: true })
    const big = await mountDrawer({ events: Array.from({ length: 500 }, (_, i) => row(i + 1)), live: true })
    const h1 = small.get('[data-test="task-tail-list"]').element.getBoundingClientRect().height
    const h2 = big.get('[data-test="task-tail-list"]').element.getBoundingClientRect().height
    expect(h1).toBeGreaterThan(0)
    expect(Math.abs(h1 - h2)).toBeLessThan(1)
    small.unmount()
    big.unmount()
  })

  it('心跳行/中断行/首帧降级提示按态上屏（人话文案）', async () => {
    const beat = await mountDrawer({ events: [row(1)], live: true, stalled: true, heartbeatSeconds: 15 })
    expect(beat.get('[data-test="task-tail-heartbeat"]').text()).toContain('暂无新内容')
    beat.unmount()

    const cut = await mountDrawer({ events: [row(1)], live: false, interrupted: true })
    expect(cut.find('[data-test="task-tail-interrupted"]').exists()).toBe(true)
    cut.unmount()

    const first = await mountDrawer({ events: [], live: true, firstFrameMs: 0 })
    expect(first.find('[data-test="task-tail-first-frame"]').exists()).toBe(true)
    expect(first.find('[data-test="task-tail-empty"]').exists()).toBe(false)
    first.unmount()

    // legacy posture without the firstFrameMs door keeps the old waiting
    // text — the synchronous doors (login-state / credential) zero-drift
    const legacy = await mountDrawer({ events: [], live: true })
    expect(legacy.get('[data-test="task-tail-empty"]').text()).toContain('等待')
    legacy.unmount()
  })

  it('上翻暂停后新帧计入未读徽标，回到最新清零', async () => {
    const w = await mountDrawer({
      events: [
        { ...row(1), level: 'error' },
        row(2),
        row(3),
      ],
      live: true,
    })
    await w.get('[data-test="task-tail-error-anchor"]').trigger('click')
    expect(w.find('[data-test="task-tail-paused"]').exists()).toBe(true)
    expect(w.find('[data-test="task-tail-unread"]').exists()).toBe(false)
    await w.setProps({
      events: [
        { ...row(1), level: 'error' },
        row(2),
        row(3),
        row(4),
        row(5),
      ],
    })
    expect(w.get('[data-test="task-tail-unread"]').text()).toContain('2')
    await w.get('[data-test="task-tail-jump-latest"]').trigger('click')
    expect(w.find('[data-test="task-tail-unread"]').exists()).toBe(false)
    expect(w.find('[data-test="task-tail-paused"]').exists()).toBe(false)
    w.unmount()
  })
})
