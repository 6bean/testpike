/**
 * R67 expiry pointer single-source contract (T701d-expiry-1).
 *
 * The retention pointer ("frames existed, the replay window came back
 * empty") used to be copied into three host dialogs with three private
 * locale sentences. This spectrum pins the NEW shape: the drawer itself
 * renders the three-part sentence (cause names the record + the domain's
 * hot-reload parameter key verbatim + the settings page path, R63), the
 * hook owns the expired flag as the single state door, and the absent
 * state is asserted hard — no conditional wrapping (iron law 5d).
 * Case names stay Chinese for suite-wide readability parity with the
 * sibling taskTailTrailDoors.test.ts spectra.
 */
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'

const request = vi.fn()
vi.mock('@/api/request', () => ({
  getToken: () => 'test-token',
  request: (...args: unknown[]) => request(...args),
  ensureFreshAccessToken: vi.fn(async () => 'test-token'),
  renewAccessTokenOnce: vi.fn(async () => false),
}))

vi.mock('@/api/config', () => ({
  listConfigs: vi.fn(async () => []),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPerm: () => true }),
}))

import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import { useTestRunTail, type TestRunEvent } from '@/composables/useTestRunTail'

function mountDrawer(props: Record<string, unknown>) {
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  return mount(TaskTailDrawer, {
    props: { events: [], live: false, ...props } as never,
    global: { plugins: [i18n, ElementPlus] },
  })
}

describe('过期三要素句由尾窗抽屉单源渲染（R67 expiry-1）', () => {
  it('expired 为真时硬断言渲染指路块，点名参数键与设置页路径', () => {
    const wrapper = mountDrawer({
      expired: true,
      retentionParam: 'ai.stream.event-retention-days',
    })
    const pointer = wrapper.find('[data-test="task-tail-expired"]')
    expect(pointer.exists()).toBe(true)
    expect(pointer.text()).toContain('ai.stream.event-retention-days')
    expect(pointer.text()).toContain('参数设置')
    // The generic empty hint must not leak alongside the pointer.
    expect(wrapper.find('[data-test="task-tail-empty"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-windowed-row]')).toHaveLength(0)
  })

  it('expired 缺席时不渲染指路块，保留诚实空态（两分支硬断言）', () => {
    const wrapper = mountDrawer({})
    expect(wrapper.find('[data-test="task-tail-expired"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="task-tail-empty"]').exists()).toBe(true)
  })

  it('宿主传入的域参数键原样上屏，不同域共用同一单源句', () => {
    const wrapper = mountDrawer({
      expired: true,
      retentionParam: 'element.survey-site.event-retention-days',
    })
    expect(wrapper.find('[data-test="task-tail-expired"]').text())
      .toContain('element.survey-site.event-retention-days')
  })
})

describe('useTestRunTail 透传 expired 状态门（唯一状态源）', () => {
  const terminalRow: TestRunEvent = {
    cursor: 1,
    level: 'error',
    summary: '步骤 1：任务结束：失败',
    detail: null,
    landedAt: '2026-11-23T10:00:00',
  }

  it('缺省为假，每次 openRun 复位，宿主写真即抽屉可见', async () => {
    const { expired, openRun } = useTestRunTail(
      async () => [terminalRow],
      { terminal: (row) => row.level === 'error', pollIntervalMs: 1 },
    )
    expect(expired.value).toBe(false)
    expired.value = true
    await openRun({ runToken: 'run-expiry-1' })
    expect(expired.value).toBe(false)
    expired.value = true
    expect(expired.value).toBe(true)
  })
})

describe('noteReplayExpiry 回放过期判定公式单源（T701d-expiry-3，R40）', () => {
  const terminalRow: TestRunEvent = {
    cursor: 1,
    level: 'error',
    summary: '步骤 1：任务结束：失败',
    detail: null,
    landedAt: '2026-11-23T10:00:00',
  }

  it('回放为空且台账帧计数大于零时置过期（唯一公式，宿主禁再复写）', async () => {
    const { expired, noteReplayExpiry, openRun } = useTestRunTail(
      async () => [],
      { pollIntervalMs: 1, emptyGracePolls: 1, maxPolls: 2 },
    )
    await openRun({ runToken: 'run-expiry-empty' })
    expect(expired.value).toBe(false)
    noteReplayExpiry(7)
    expect(expired.value).toBe(true)
  })

  it('回放到手帧时即便计数大于零也不置过期（防误报空窗）', async () => {
    const { expired, noteReplayExpiry, openRun } = useTestRunTail(
      async () => [terminalRow],
      { terminal: (row) => row.level === 'error', pollIntervalMs: 1 },
    )
    await openRun({ runToken: 'run-expiry-frames' })
    noteReplayExpiry(7)
    expect(expired.value).toBe(false)
  })

  it('台账帧计数为零或缺席时保持诚实空态，不谎报过期', async () => {
    const { expired, noteReplayExpiry, openRun } = useTestRunTail(
      async () => [],
      { pollIntervalMs: 1, emptyGracePolls: 1, maxPolls: 2 },
    )
    await openRun({ runToken: 'run-expiry-none' })
    noteReplayExpiry(0)
    noteReplayExpiry(undefined)
    expect(expired.value).toBe(false)
  })
})
