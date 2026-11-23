/**
 * P4 contract: the AI batch-generation progress face tails every
 * sub-task's process events through the SAME shared pump and drawer used by
 * the extraction / steps faces (no second pump copy, R40 single source).
 * Covers: one open per task id (repoll dedupe), merged ascending cursor,
 * live flag from pending+running, close+clear on batch reset and on scope
 * dispose, pump-reuse mirror gate, and the view wiring mirror gate.
 * The real SSE/poll roads stay covered by the shared-pump spectra pinned in
 * api-level tests; this file only tests aggregation state and wiring.
 */
import { describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'
import { useGenBatchEventTail } from '@/composables/useGenBatchEventTail'
import type { AiBatch } from '@/api/ai'
import type { ExtractionEvent } from '@/api/taskEvents'
import composableRaw from '@/composables/useGenBatchEventTail?raw'
import viewRaw from '@/views/ai/AiGenerateView.vue?raw'

// Shared-pump double: capture open calls, hand back a controllable handle.
const pump = vi.hoisted(() => ({ openTaskProcessEventTail: vi.fn() }))
vi.mock('@/api/taskEvents', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  ...pump,
}))

function ev(cursor: number, level = 'step'): ExtractionEvent {
  return {
    cursor,
    level,
    summary: `row-${cursor}`,
    detail: '',
    landedAt: '2026-11-12T03:00:00',
  } as unknown as ExtractionEvent
}

function batchOf(statuses: Array<[number, number]>): AiBatch {
  const count = (s: number) => statuses.filter(([, st]) => st === s).length
  return {
    id: 7,
    projectId: 1,
    operatorId: 2,
    total: statuses.length,
    pending: count(1),
    running: count(2),
    done: count(3),
    failed: count(4),
    items: statuses.map(([taskId, status]) => ({
      taskId,
      status,
      requirementText: `req ${taskId}`,
    })),
  } as unknown as AiBatch
}

async function withTail(initial: AiBatch | null) {
  const scope = effectScope()
  const batch = ref<AiBatch | null>(initial)
  const tail = scope.run(() => useGenBatchEventTail(batch))!
  await nextTick()
  return { batch, tail, scope }
}

describe('useGenBatchEventTail (T577 P4 aggregation)', () => {
  it('opens exactly one tail per task id and never reopens on repoll', async () => {
    pump.openTaskProcessEventTail.mockImplementation(() => ({ close: vi.fn(), cursor: () => 0 }))
    const { batch, scope } = await withTail(batchOf([[11, 2], [12, 1]]))
    batch.value = batchOf([[11, 2], [12, 1]])
    await nextTick()
    batch.value = batchOf([[11, 2], [12, 1]])
    await nextTick()
    const opened = pump.openTaskProcessEventTail.mock.calls.map(c => Number(c[0])).sort()
    expect(opened).toEqual([11, 12])
    scope.stop()
  })

  it('merges events from every task without cross-task cursor collisions and without duplicates', async () => {
    const sinks: Array<(rows: ExtractionEvent[]) => void> = []
    pump.openTaskProcessEventTail.mockImplementation(
      (_t: number, _c: number, onEvents: (rows: ExtractionEvent[]) => void) => {
        sinks.push(onEvents)
        return { close: vi.fn(), cursor: () => 0 }
      },
    )
    const { tail, scope } = await withTail(batchOf([[11, 2], [12, 2]]))
    sinks[0]([ev(5), ev(3)])
    sinks[1]([ev(4), ev(3)])
    // V615 fix: cursors are ledger ids unique PER SOURCE. The old merge keyed
    // on the bare cursor, so task 12's row 3 silently erased task 11's row 3
    // (two different events, one number). Rows now carry their source task as
    // scope and both survive; a re-delivered same-(scope,cursor) row still
    // dedupes to one.
    expect(tail.events.value.map(r => r.cursor)).toEqual([3, 3, 4, 5])
    expect(tail.events.value.filter(r => r.cursor === 3).map(r => r.scope).sort())
      .toEqual(['11', '12'])
    sinks[0]([ev(3)])
    expect(tail.events.value.length).toBe(4)
    expect(tail.events.value.filter(r => r.scope === '11' && r.cursor === 3).length).toBe(1)
    scope.stop()
  })

  it('live is true while any item is pending/running and false once sealed', async () => {
    pump.openTaskProcessEventTail.mockReturnValue({ close: vi.fn(), cursor: () => 0 })
    const { batch, tail, scope } = await withTail(batchOf([[11, 2], [12, 1]]))
    expect(tail.live.value).toBe(true)
    batch.value = batchOf([[11, 3], [12, 4]])
    await nextTick()
    expect(tail.live.value).toBe(false)
    scope.stop()
  })

  it('closes every handle and clears the ledger when the batch resets to null', async () => {
    const closeSpy = vi.fn()
    pump.openTaskProcessEventTail.mockReturnValue({ close: closeSpy, cursor: () => 0 })
    const { batch, tail, scope } = await withTail(batchOf([[11, 2], [12, 3]]))
    batch.value = null
    await nextTick()
    expect(closeSpy).toHaveBeenCalledTimes(2)
    expect(tail.events.value).toEqual([])
    scope.stop()
  })

  it('closing the owning scope also closes open handles (unmount leak guard, R57)', async () => {
    const closeSpy = vi.fn()
    pump.openTaskProcessEventTail.mockReturnValue({ close: closeSpy, cursor: () => 0 })
    const { tail, scope } = await withTail(batchOf([[11, 2]]))
    void tail
    scope.stop()
    expect(closeSpy).toHaveBeenCalledTimes(1)
  })
})

describe('single-source and wiring mirror gates', () => {
  it('the composable reuses the shared pump instead of copying it (R40)', () => {
    expect(composableRaw).toContain('openTaskProcessEventTail')
    expect(composableRaw).not.toContain('EventSource')
    expect(composableRaw).not.toContain('process-events')
    expect(composableRaw).toContain('mergeEvents')
  })

  it('AiGenerateView wires the shared drawer fed by the batch tail', () => {
    expect(viewRaw).toContain('useGenBatchEventTail')
    expect(viewRaw).toContain('TaskTailDrawer')
  })
})
