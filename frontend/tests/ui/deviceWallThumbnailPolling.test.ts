/**
 * Device-wall live-thumbnail polling contract
 * (src/composables/useDeviceWallThumbnails.ts, P1 wall live face).
 *
 * Why it exists: the wall renders one 1fps thumbnail cell per device. A naive
 * per-cell component timer would unleash an unbounded browser burst (the exact
 * 32-wide proxy-stretch failure measured for the pinned-object panel), so the
 * page owns ONE round loop that fans the reads out through the shared
 * runBounded piece (single-source throttle, red line 6 — no second limiter).
 * Cell semantics the view relies on and this contract pins:
 *  - the server view passes through untouched (LIVE / IDLE / NO_FRAME with its
 *    honest reason wording — the composable never re-derives state);
 *  - a transport failure marks the cell failed and backs that device out of
 *    the next rounds (rate-limited retry), recovering on the next success;
 *  - devices removed from the wall stop being fetched and lose their cells;
 *  - a hidden tab skips rounds (no background request storm);
 *  - stop() ends the loop for good.
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { useDeviceWallThumbnails } from '@/composables/useDeviceWallThumbnails'

// Structural stand-in for the admin thumbnail envelope (see api/appauto.ts).
function cell(id: number) {
  return {
    deviceId: id,
    taskId: 7,
    state: 'LIVE',
    reason: null,
    imageBase64: `img-${id}`,
    imageWidth: 360,
    imageHeight: 720,
    sourceWidth: 1080,
    sourceHeight: 2160,
  }
}

function idleCell(id: number, reason: string) {
  return { ...cell(id), state: 'IDLE', reason, taskId: null, imageBase64: null }
}

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('useDeviceWallThumbnails', () => {
  it('fans each round through runBounded and never exceeds the in-flight cap', async () => {
    let active = 0
    let peak = 0
    const fetcher = vi.fn(async (id: number) => {
      active += 1
      peak = Math.max(peak, active)
      await Promise.resolve()
      await Promise.resolve()
      active -= 1
      return cell(id)
    })
    const ids = Array.from({ length: 12 }, (_, i) => i + 1)
    const { cells, start, stop } = useDeviceWallThumbnails(() => ids, { fetch: fetcher, limit: 4 })
    start()
    // finite advance: an active 1fps interval is by design a never-ending
    // loop, so runAllTimersAsync here would be an infinite timer chase
    await vi.advanceTimersByTimeAsync(1000)
    expect(peak).toBeGreaterThan(0)
    expect(peak).toBeLessThanOrEqual(4)
    expect(Object.keys(cells.value)).toHaveLength(12)
    expect(cells.value[3].view?.state).toBe('LIVE')
    stop()
  })

  it('passes server state and reason through untouched (LIVE frame vs honest IDLE cell)', async () => {
    const fetcher = vi.fn(async (id: number) => (id === 1 ? cell(1) : idleCell(2, '该设备当前无在跑任务')))
    const { cells, start, stop } = useDeviceWallThumbnails(() => [1, 2], { fetch: fetcher })
    start()
    await vi.advanceTimersByTimeAsync(0)
    expect(cells.value[1].view?.imageBase64).toBe('img-1')
    expect(cells.value[2].view?.state).toBe('IDLE')
    expect(cells.value[2].view?.reason).toBe('该设备当前无在跑任务')
    expect(cells.value[2].failed).toBe(false)
    stop()
  })

  it('backs a failing device out of the next rounds and recovers on a later success', async () => {
    const fetcher = vi.fn(async (id: number) => {
      if (id === 9) throw new Error('boom')
      return cell(id)
    })
    const { cells, start, stop } = useDeviceWallThumbnails(() => [9], { fetch: fetcher, intervalMs: 1000 })
    start()
    await vi.advanceTimersByTimeAsync(0) // round 1: failure → cell failed
    expect(cells.value[9].failed).toBe(true)
    const callsAfterFail = fetcher.mock.calls.length
    await vi.advanceTimersByTimeAsync(1000) // round 2: skipped by backoff
    expect(fetcher.mock.calls.length).toBe(callsAfterFail)
    // backoff of one round then the retry lands and clears the failure flag
    fetcher.mockImplementation(async (id: number) => cell(id))
    await vi.advanceTimersByTimeAsync(2000)
    expect(fetcher.mock.calls.length).toBeGreaterThan(callsAfterFail)
    expect(cells.value[9].failed).toBe(false)
    expect(cells.value[9].view?.state).toBe('LIVE')
    stop()
  })

  it('drops removed devices from later rounds and prunes their cells', async () => {
    let ids = [1, 2, 3]
    const fetcher = vi.fn(async (id: number) => cell(id))
    const { cells, start, stop } = useDeviceWallThumbnails(() => ids, { fetch: fetcher, intervalMs: 1000 })
    start()
    await vi.advanceTimersByTimeAsync(0)
    expect(Object.keys(cells.value)).toHaveLength(3)
    ids = [1, 2]
    await vi.advanceTimersByTimeAsync(1000)
    expect(cells.value[3]).toBeUndefined()
    const before = fetcher.mock.calls.map((c) => c[0])
    expect(before.filter((x) => x === 3)).toHaveLength(1) // only the pre-removal round
    await vi.advanceTimersByTimeAsync(1000)
    expect(fetcher.mock.calls.map((c) => c[0]).filter((x) => x === 3)).toHaveLength(1)
    stop()
  })

  it('skips rounds while the tab is hidden and resumes when visible again', async () => {
    const fetcher = vi.fn(async (id: number) => cell(id))
    let hidden = false
    const { start, stop } = useDeviceWallThumbnails(() => [1], {
      fetch: fetcher,
      intervalMs: 1000,
      isHidden: () => hidden,
    })
    start()
    await vi.advanceTimersByTimeAsync(0)
    const afterStart = fetcher.mock.calls.length
    hidden = true
    await vi.advanceTimersByTimeAsync(3000)
    expect(fetcher.mock.calls.length).toBe(afterStart)
    hidden = false
    await vi.advanceTimersByTimeAsync(1000)
    expect(fetcher.mock.calls.length).toBeGreaterThan(afterStart)
    stop()
  })

  it('stop() ends the loop — no further fetches', async () => {
    const fetcher = vi.fn(async (id: number) => cell(id))
    const { start, stop } = useDeviceWallThumbnails(() => [1], { fetch: fetcher, intervalMs: 1000 })
    start()
    await vi.advanceTimersByTimeAsync(0)
    const n = fetcher.mock.calls.length
    stop()
    await vi.advanceTimersByTimeAsync(5000)
    expect(fetcher.mock.calls.length).toBe(n)
  })
})
