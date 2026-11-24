/**
 * W11 contract: a frozen batch-progress read must never stay silent.
 * The probe window proved the failure mode end to end: when the browser
 * session expires mid-capture, every getAiBatch re-read rejects and the old
 * empty catch kept swallowing them, so the drawer dialed the door forever
 * while the progress head froze at its last numbers (no degradation marker).
 * Covers: consecutive failures reaching the hot-key threshold flip the
 * stalled flag and the failure count lands on the ledger (visible state),
 * a successful re-read zeroes both, the threshold comes from the single
 * tail-window config source (no page-level constant), the view wires the
 * marker with the named repair entry, and the old silent-catch shape can
 * never come back.
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { tailPollFailureThreshold } from '@/composables/tailWindowConfig'
import composableRaw from '@/composables/useAiBatchPolling?raw'
import viewRaw from '@/views/ai/AiGenerateView.vue?raw'
import zhLocale from '@/utils/locales/zh-CN'
import enLocale from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({ getAiBatch: vi.fn() }))
vi.mock('@/api/ai', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  ...api,
}))

import { useAiBatchPolling } from '@/composables/useAiBatchPolling'

function batchRow(total = 4) {
  return { id: 7, projectId: 1, total, pending: total, running: 0, done: 0, failed: 0, cancelled: 0, items: [] }
}

function failing(times: number) {
  let n = 0
  return async () => {
    n += 1
    if (n <= times) throw new Error('401 session expired')
    return batchRow()
  }
}

async function withPolling() {
  const batchIdRef = ref<number | null>(7)
  let state!: ReturnType<typeof useAiBatchPolling>
  const Comp = defineComponent({
    setup() {
      state = useAiBatchPolling(batchIdRef)
      return () => null
    },
  })
  mount(Comp)
  return state
}

describe('batch polling failure visibility (W11)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    api.getAiBatch.mockReset()
    tailPollFailureThreshold.value = 3
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('marks the read as stalled once consecutive failures reach the threshold', async () => {
    api.getAiBatch.mockImplementation(failing(10))
    const { startPolling, pollFailures, readStalled } = await withPolling()
    await startPolling()
    expect(readStalled.value).toBe(false)
    await vi.advanceTimersByTimeAsync(2000)
    await vi.advanceTimersByTimeAsync(2000)
    expect(pollFailures.value).toBe(3)
    expect(readStalled.value).toBe(true)
  })

  it('a successful re-read zeroes the failure count and clears the flag', async () => {
    api.getAiBatch.mockImplementation(failing(2))
    const { startPolling, pollFailures, readStalled, batch } = await withPolling()
    await startPolling()
    await vi.advanceTimersByTimeAsync(2000)
    expect(pollFailures.value).toBe(2)
    expect(readStalled.value).toBe(false)
    await vi.advanceTimersByTimeAsync(2000)
    expect(pollFailures.value).toBe(0)
    expect(readStalled.value).toBe(false)
    expect(batch.value?.total).toBe(4)
  })

  it('reads the threshold from the tail-window config single source', async () => {
    tailPollFailureThreshold.value = 2
    api.getAiBatch.mockImplementation(failing(5))
    const { startPolling, pollFailures, readStalled } = await withPolling()
    await startPolling()
    await vi.advanceTimersByTimeAsync(2000)
    expect(pollFailures.value).toBe(2)
    expect(readStalled.value).toBe(true)
  })

  it('the old empty-catch shape must never come back', () => {
    expect(composableRaw).not.toMatch(/catch\s*\{\s*\}/)
    expect(composableRaw).toMatch(/pollFailures\.value/)
    expect(composableRaw).toMatch(/tailPollFailureThreshold/)
  })

  it('the view wires the stalled marker with a named repair entry', () => {
    expect(viewRaw).toMatch(/readStalled/)
    expect(viewRaw).toMatch(/pollStalledTip/)
    const zh = (zhLocale as Record<string, Record<string, string>>).aiGenerate
    const en = (enLocale as Record<string, Record<string, string>>).aiGenerate
    expect(zh.pollStalledTip).toContain('{n}')
    expect(zh.pollStalledTip).toContain('重新登录')
    expect(en.pollStalledTip).toContain('{n}')
  })
})
