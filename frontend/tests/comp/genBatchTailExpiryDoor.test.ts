/**
 * R67 revisit door for the AI batch generation face (T701d-expiry, last
 * pending face of the XB ledger).
 *
 * The batch progress face already follows the live tail while the batch is
 * running, but once every sub-task settled and the sweeper retired the
 * frames, the drawer used to fall back to a plain empty box — no cause, no
 * parameter, no way back (R63 false path). This spectrum pins the fixed
 * shape: the shared noteReplayExpiry formula from useTestRunTail is the ONE
 * decision door (no second derivation, R40), and the drawer receives both
 * the expired flag and the domain retention parameter verbatim.
 * Case names stay Chinese for suite-wide readability parity with the
 * sibling taskTailExpiryPointer.test.ts spectra.
 */
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import type { AiBatch } from '@/api/ai'

const openTaskProcessEventTail = vi.fn()
const fetchBatchProcessRuns = vi.fn()

vi.mock('@/api/taskEvents', () => ({
  mergeEvents: (seen: unknown[], incoming: unknown[]) => [...seen, ...incoming],
  openTaskProcessEventTail: (...args: unknown[]) =>
    openTaskProcessEventTail(...(args as [number, number, (w: unknown) => void])),
}))
vi.mock('@/api/batch', () => ({
  fetchBatchProcessRuns: (...args: unknown[]) => fetchBatchProcessRuns(...(args as [number])),
}))

import { useGenBatchEventTail } from '@/composables/useGenBatchEventTail'

function settledBatch(): AiBatch {
  return {
    id: 4242,
    projectId: 1,
    operatorId: 1,
    total: 1,
    pending: 0,
    running: 0,
    done: 1,
    failed: 0,
    cancelled: 0,
    items: [{ taskId: 7 } as never],
  } as AiBatch
}

let host: { assessExpiry: () => Promise<void>; expired: { value: boolean } } | null = null

function installHost() {
  const batch = ref<AiBatch | null>(settledBatch())
  const comp = defineComponent({
    setup() {
      const tail = useGenBatchEventTail(batch)
      host = tail as never
      return () => h('div')
    },
  })
  return mount(comp, { global: { plugins: [ElementPlus] } })
}

beforeEach(() => {
  host = null
  openTaskProcessEventTail.mockReset()
  openTaskProcessEventTail.mockImplementation(() => ({ close: vi.fn(), cursor: () => 0 }))
  fetchBatchProcessRuns.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('AI 生成批次的回看门过期判定（R67 expiry，T701d 末面）', () => {
  it('批次终态且尾窗为空时，按运行史事件总数经单源公式点亮 expired 并出指路', async () => {
    installHost()
    await nextTick()
    expect(host).not.toBeNull()
    expect(host!.expired.value).toBe(false)
    fetchBatchProcessRuns.mockResolvedValue([{ runToken: 'r1', eventCount: 3 }] as never)

    await host!.assessExpiry()

    expect(fetchBatchProcessRuns).toHaveBeenCalledWith(4242)
    expect(host!.expired.value).toBe(true)
  })

  it('运行史证明从未有过帧时保持未点亮，指路块不得出现（缺席硬断言，铁律 5d）', async () => {
    installHost()
    await nextTick()
    fetchBatchProcessRuns.mockResolvedValue([{ runToken: 'r1', eventCount: 0 }] as never)

    await host!.assessExpiry()

    expect(host!.expired.value).toBe(false)
  })

  it('尾窗仍有帧时不判过期，也不发起运行史读取', async () => {
    openTaskProcessEventTail.mockImplementation(
      (_taskId: number, _cursor: number, onEvents: (w: unknown[]) => void) => {
        onEvents([{ cursor: 1, kind: 'step', text: 'x', id: 'a' }])
        return { close: vi.fn(), cursor: () => 1 }
      },
    )
    installHost()
    await nextTick()

    await host!.assessExpiry()

    expect(fetchBatchProcessRuns).not.toHaveBeenCalled()
    expect(host!.expired.value).toBe(false)
  })

  it('批次仍在进行中时保持跟随态，过期判定不介入', async () => {
    const batch = ref<AiBatch | null>({ ...settledBatch(), running: 1 })
    let tail: { assessExpiry: () => Promise<void>; expired: { value: boolean } } | null = null
    mount(
      defineComponent({
        setup() {
          tail = useGenBatchEventTail(batch) as never
          return () => h('div')
        },
      }),
      { global: { plugins: [ElementPlus] } },
    )
    await nextTick()

    await tail!.assessExpiry()

    expect(fetchBatchProcessRuns).not.toHaveBeenCalled()
    expect(tail!.expired.value).toBe(false)
  })
})
