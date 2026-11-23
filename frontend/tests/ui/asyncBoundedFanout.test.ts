/**
 * Bounded async fan-out utility contract (src/utils/asyncFanout.ts).
 *
 * Why it exists: the pinned-object panel fans one pending-count read out per
 * pinned row (worst scale 32+). Unbounded browser bursts stretch the tail
 * under the dev proxy past the evidence-window assertion budget (measured:
 * server answers a 32-wide burst in ~230ms, but the same 32 fired through
 * the UI path spread to ~1.8s+ and intermittently starved the badge assert).
 * A cap keeps every row's read in flight within a predictable window while
 * preserving per-row error isolation (the caller's thunk owns its catch).
 */
import { describe, expect, it } from 'vitest'
import { runBounded } from '@/utils/asyncFanout'

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

describe('runBounded', () => {
  it('never keeps more than limit tasks in flight and completes all of them', async () => {
    let active = 0
    let peak = 0
    const results = await runBounded(
      Array.from({ length: 12 }, (_, i) => async () => {
        active += 1
        peak = Math.max(peak, active)
        await sleep(5)
        active -= 1
        return i
      }),
      4,
    )
    expect(results.map((s) => (s.status === 'fulfilled' ? s.value : s.reason)))
      .toEqual(Array.from({ length: 12 }, (_, i) => i))
    expect(peak).toBeLessThanOrEqual(4)
    expect(peak).toBeGreaterThan(1)
  })

  it('a rejecting thunk does not stall the remaining queue', async () => {
    const boom = new Error('door down')
    const settled = await runBounded(
      [
        async () => 'ok-1',
        async () => { throw boom },
        async () => 'ok-2',
        async () => 'ok-3',
      ],
      2,
    )
    expect(settled.map((s) => (s.status === 'fulfilled' ? s.value : s.reason))).toEqual([
      'ok-1', boom, 'ok-2', 'ok-3',
    ])
  })

  it('rejects a non-positive limit instead of silently running nothing', async () => {
    await expect(runBounded([async () => 1], 0)).rejects.toThrow(/limit/i)
  })
})
