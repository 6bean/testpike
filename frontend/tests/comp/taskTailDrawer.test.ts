
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import type { ExtractionEvent } from '@/api/taskEvents'

vi.mock('@/api/request', () => ({
  getToken: () => 'test-token',
  getRefreshToken: () => 'test-refresh',
  setToken: vi.fn(),
  clearToken: vi.fn(),
  request: vi.fn(),
  // Mirror every export the tail module graph names at import time (browser
  // mode hard-fails collection on a missing named export; r19 renewal fix
  // added these two to taskEvents.ts — pre-existing red cleared 11-22).
  ensureFreshAccessToken: vi.fn(async () => 'test-token'),
  renewAccessTokenOnce: vi.fn(async () => false),
}))
// d: the drawer now consumes tailWindowConfig for the heartbeat default,
// which imports the auth store (the store imports the token helpers above).
vi.stubGlobal('fetch', vi.fn(async () => {
  throw new TypeError('stream door refused in unit spectra')
}))

import {
  mergeEvents,
  openExtractionEventTail,
  openTaskProcessEventTail,
  fetchTaskProcessEvents,
  formatDetailForDisplay,
  TAIL_MAX_EVENTS,
  TAIL_POLL_INTERVAL_MS,
} from '@/api/taskEvents'
import { request } from '@/api/request'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'

function ev(cursor: number, level: string, summary = `step ${cursor}`): ExtractionEvent {
  return { cursor, level, summary, detail: null, landedAt: `2026-11-09T12:${String(cursor % 60).padStart(2, '0')}:0${cursor % 10}` }
}

async function factory(events: ExtractionEvent[], live = true) {
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  const w = mount(TaskTailDrawer, {
    props: { events, live },
    global: { plugins: [ElementPlus, i18n] },
  })
  return w
}

describe('TaskTailDrawer 组件契约', () => {
  it('渲染全部级别行，时间列取时钟段', async () => {
    const w = await factory([ev(1, 'step'), ev(2, 'thinking'), ev(3, 'error')])
    expect(w.findAll('[data-test^="task-tail-row-"]').length).toBe(3)
    const times = w.findAll('.task-tail-time').map(el => el.text())
    expect(times[0]).toMatch(/^12:\d{2}:\d{2}$|^2026-11-09T12/)
  })

  it('级别芯片筛选：关掉 thinking 后该级行消失，其余保留', async () => {
    const w = await factory([ev(1, 'step'), ev(2, 'thinking')])
    await w.get('[data-test="task-tail-chip-thinking"]').trigger('click')
    const rows = w.findAll('.task-tail-summary').map(el => el.text())
    expect(rows).toEqual(['step 1'])
  })

  it('全灭回弹：逐一关掉三级后自动恢复全选', async () => {
    const w = await factory([ev(1, 'step')])
    for (const level of ['thinking', 'step', 'error']) {
      await w.get(`[data-test="task-tail-chip-${level}"]`).trigger('click')
    }
    expect(w.findAll('.task-tail-summary').map(el => el.text())).toEqual(['step 1'])
  })

  it('错误红锚显示计数并可点击定位', async () => {
    const w = await factory([ev(1, 'step'), ev(2, 'error'), ev(3, 'error')])
    const anchor = w.get('[data-test="task-tail-error-anchor"]')
    expect(anchor.text()).toContain('2')
    await anchor.trigger('click')
    expect(w.get('[data-test="task-tail-paused"]').exists()).toBe(true)
  })

  it('无事件且进行中=等待文案；非进行中=暂无文案', async () => {
    const live = await factory([], true)
    expect(live.get('[data-test="task-tail-empty"]').text()).toContain('等待')
    const done = await factory([], false)
    expect(done.get('[data-test="task-tail-empty"]').text()).toContain('暂无')
  })
})

describe('mergeEvents 游标账契约', () => {
  it('去重、升序、旧游标重放不重复入账', () => {
    const merged = mergeEvents([ev(5, 'step'), ev(7, 'step')], [ev(7, 'step'), ev(6, 'thinking'), ev(4, 'error')])
    expect(merged.map(r => r.cursor)).toEqual([4, 5, 6, 7])
    expect(merged.find(r => r.cursor === 7)?.summary).toBe('step 7')
  })

  it('封顶保最新：超过 TAIL_MAX_EVENTS 时摘最旧', () => {
    const many = Array.from({ length: TAIL_MAX_EVENTS + 50 }, (_, i) => ev(i + 1, 'step'))
    const merged = mergeEvents([], many)
    expect(merged.length).toBe(TAIL_MAX_EVENTS)
    expect(merged[0].cursor).toBe(51)
  })
})

describe('openExtractionEventTail 降级轮询谱', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('SSE 拒接→游标轮询续拉，已见帧不回卷、恰投一次', async () => {
    const seen: ExtractionEvent[][] = []
    const requestMock = vi.mocked(request)
    requestMock
      .mockResolvedValueOnce([ev(11, 'step'), ev(12, 'thinking')] as never)
      .mockResolvedValueOnce([ev(13, 'error')] as never)
      .mockResolvedValueOnce([] as never)
    const handle = openExtractionEventTail(42, 10, batch => seen.push(batch))
    await vi.advanceTimersByTimeAsync(TAIL_POLL_INTERVAL_MS)
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ url: '/ai/documents/42/extraction-events', method: 'get' }),
    )
    expect(seen.flat().map(r => r.cursor)).toEqual([11, 12])
    await vi.advanceTimersByTimeAsync(TAIL_POLL_INTERVAL_MS)
    expect(seen.flat().map(r => r.cursor)).toEqual([11, 12, 13])
    expect(handle.cursor()).toBe(13)
    await vi.advanceTimersByTimeAsync(TAIL_POLL_INTERVAL_MS)
    expect(seen.flat().length).toBe(3)
    handle.close()
    const before = seen.flat().length
    await vi.advanceTimersByTimeAsync(TAIL_POLL_INTERVAL_MS * 3)
    expect(seen.flat().length).toBe(before)
  })
})

describe('openTaskProcessEventTail task door (T594 S4 slice D)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('task window GET rides the shared pump over /ai/tasks/{id}/process-events', async () => {
    const seen: ExtractionEvent[][] = []
    const requestMock = vi.mocked(request)
    requestMock
      .mockResolvedValueOnce([ev(1, 'step'), ev(2, 'thinking')] as never)
      .mockResolvedValueOnce([ev(3, 'error')] as never)
    const handle = openTaskProcessEventTail(77, 0, batch => seen.push(batch))
    // First SSE attempt is refused (stubbed global fetch) -> poll road.
    await vi.advanceTimersByTimeAsync(TAIL_POLL_INTERVAL_MS)
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ url: '/ai/tasks/77/process-events', method: 'get' }),
    )
    expect(seen.flat().map(r => r.cursor)).toEqual([1, 2])
    await vi.advanceTimersByTimeAsync(TAIL_POLL_INTERVAL_MS)
    expect(seen.flat().map(r => r.cursor)).toEqual([1, 2, 3])
    expect(handle.cursor()).toBe(3)
    handle.close()
  })

  it('fetchTaskProcessEvents returns the window rows and survives a non-array body', async () => {
    const requestMock = vi.mocked(request)
    requestMock.mockResolvedValueOnce([ev(9, 'step')] as never)
    const rows = await fetchTaskProcessEvents(88, 8, 50)
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ url: '/ai/tasks/88/process-events', params: { cursor: 8, limit: 50 } }),
    )
    expect(rows.map(r => r.cursor)).toEqual([9])
    requestMock.mockResolvedValueOnce(undefined as never)
    expect(await fetchTaskProcessEvents(88, 0)).toEqual([])
  })
})

describe('detail 机读段上屏契约（vision 评审 D1，11-12）', () => {
  it('formatDetailForDisplay 纯函数：JSON 产出键=值零花括号零引号，非 JSON 与坏 JSON 原样', () => {
    expect(formatDetailForDisplay(null)).toBe('')
    expect(formatDetailForDisplay('plain text')).toBe('plain text')
    expect(formatDetailForDisplay('{"a":1,"b":"x"}')).toBe('a=1｜b=x')
    const out = formatDetailForDisplay('{"a":{"b":[1,2]},"n":null}')
    expect(out).not.toMatch(/[{}"]/)
    expect(out).toContain('a.b.0=1')
    expect(out).toContain('a.b.1=2')
    expect(out).toContain('n=')
    expect(formatDetailForDisplay('{not json')).toBe('{not json')
  })

  it('JSON detail 行渲染为键=值可读形态，花括号与引号零上屏，原文只留 title', async () => {
    const raw = '{"reasonCategory":"校验侧","tokens":1234,"nested":{"k":"v"}}'
    const w = await factory([{ ...ev(1, 'step'), detail: raw }])
    const detail = w.find('.task-tail-detail')
    expect(detail.exists()).toBe(true)
    expect(detail.text()).not.toMatch(/[{}"]/)
    expect(detail.text()).toContain('reasonCategory=校验侧')
    expect(detail.text()).toContain('nested.k=v')
    expect(detail.attributes('title')).toBe(raw)
  })

  // Stop door contract : the drawer only renders and emits; the host
  // owns the cancel call, so a failure reason always surfaces through the host.
  describe('stop door (can-stop)', () => {
    async function mountWithStop(events: ExtractionEvent[], live: boolean, canStop: boolean) {
      const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
      return mount(TaskTailDrawer, {
        props: { events, live, canStop },
        global: { plugins: [ElementPlus, i18n] },
      })
    }

    it('宿主未声明 can-stop 时停止钮零渲染（诚实缺席，禁假可点）', async () => {
      const w = await mountWithStop([ev(1, 'step')], true, false)
      expect(w.find('[data-test="task-tail-stop"]').exists()).toBe(false)
    })

    it('can-stop 且任务在跑时停止钮可见，点击只上抛 stop 事件不外呼', async () => {
      const w = await mountWithStop([ev(1, 'step')], true, true)
      const btn = w.find('[data-test="task-tail-stop"]')
      expect(btn.exists()).toBe(true)
      await btn.trigger('click')
      expect(w.emitted('stop')?.length).toBe(1)
      expect(request).not.toHaveBeenCalled()
    })

    it('can-stop 但任务已终态（live=false）时停止钮摘除', async () => {
      const w = await mountWithStop([ev(1, 'step')], false, true)
      expect(w.find('[data-test="task-tail-stop"]').exists()).toBe(false)
    })
  })
})
