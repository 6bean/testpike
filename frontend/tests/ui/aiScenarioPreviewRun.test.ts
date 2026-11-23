/**
 * Stage0 scenario-preview run contract .
 *
 * The 30-row enumeration runs row by row against the server and one row can
 * legitimately take tens of seconds (the server forwards with a 360s budget).
 * Two regressions this contract pins shut:
 * 1. the enumeration call must carry its OWN long read timeout — the global
 * axios default (30s) stays untouched for every other endpoint;
 * 2. one failing row must NOT abort the plan — every remaining row keeps
 * enumerating, failures are returned for the caller to present honestly
 * (partial state visible, never silently swallowed).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it, vi } from 'vitest'

const requestCalls: Array<Record<string, unknown>> = []
/**
 * Per-row transport seam: the stalled-transport cases below need a row that
 * never answers on its own (it only dies when the abort signal fires), which no
 * real axios call can be made to do deterministically inside a unit test.
 * Everything else keeps the plain resolve-one behaviour.
 */
const requestBehavior: { impl: ((config: Record<string, unknown>) => Promise<unknown>) | null } = { impl: null }
vi.mock('@/api/request', () => ({
  request: vi.fn((config: Record<string, unknown>) => {
    requestCalls.push(config)
    return requestBehavior.impl ? requestBehavior.impl(config) : Promise.resolve(1)
  }),
}))

import { ht } from '@/utils/dictLabel'
import {
  SCENARIO_PREVIEW_REQUEST_TIMEOUT_MS,
  buildPreviewRunPlan,
  enumeratePreviewPlan,
  type PreviewRunStep,
} from '@/utils/aiScenarioSelection'
import { runScenarioPreview } from '@/api/ai'

function hangUntilAborted(config: Record<string, unknown>): Promise<unknown> {
  return new Promise((_resolve, reject) => {
    const signal = config.signal as AbortSignal
    signal.addEventListener('abort', () => reject(new Error('transport aborted')))
  })
}

function step(seq: number): PreviewRunStep {
  return { batchId: 9, seq, projectId: 7, requirementText: `r${seq}`, priority: 1 }
}

describe('scenario preview per-call timeout', () => {
  it('sends every enumeration row with an explicit long read timeout', async () => {
    await runScenarioPreview({ batchId: 1, seq: 0, projectId: 7, requirementText: '登录支持短信验证码', priority: 1 })
    expect(requestCalls).toHaveLength(1)
    const cfg = requestCalls[0]
    expect(cfg.url).toBe('/ai/batch/scenario-preview')
    expect(cfg.method).toBe('post')
    expect(SCENARIO_PREVIEW_REQUEST_TIMEOUT_MS).toBeGreaterThanOrEqual(120_000)
    expect(cfg.timeout).toBe(SCENARIO_PREVIEW_REQUEST_TIMEOUT_MS)
  })

  it('leaves the global axios default at 30s (scoped override only)', () => {
    const src = readFileSync(fileURLToPath(new URL('../../src/api/request.ts', import.meta.url)), 'utf8')
    expect(src).toMatch(/timeout:\s*30000,/)
  })
})

describe('scenario preview per-row fault tolerance', () => {
  it('keeps enumerating after a failed row and records the failure plainly', async () => {
    const seen: number[] = []
    const outcome = await enumeratePreviewPlan([step(0), step(1), step(2)], async (s) => {
      seen.push(s.seq)
      if (s.seq === 1) throw new Error('请求超时')
      return s.seq
    })
    expect(seen).toEqual([0, 1, 2])
    expect(outcome.completed).toBe(2)
    expect(outcome.failures).toEqual([{ seq: 1, message: '请求超时' }])
  })

  it('reports zero failures on the happy path', async () => {
    const outcome = await enumeratePreviewPlan([step(0), step(1)], async () => 0)
    expect(outcome).toEqual({ completed: 2, failures: [] })
  })

  it('wraps non-Error throws so the partial state still carries a message', async () => {
    const outcome = await enumeratePreviewPlan([step(0)], () => Promise.reject('bare-reason'))
    expect(outcome.completed).toBe(0)
    expect(outcome.failures).toEqual([{ seq: 0, message: 'bare-reason' }])
  })

  it('plans from the filled rows so the enumeration order stays stable', () => {
    const plan = buildPreviewRunPlan(
      [{ text: ' 第一行 ' }, { text: '   ' }, { text: '第二行' }],
      { batchId: 3, projectId: 7, priorityOf: () => 1 },
    )
    expect(plan.map((s) => s.seq)).toEqual([0, 1])
    expect(plan.map((s) => s.requirementText)).toEqual(['第一行', '第二行'])
  })
})

/**
 * Hard read deadline . The axios timeout only guards the
 * window before response headers arrive: a row whose body stalls forever never
 * trips it, and one hung row froze a 30-row plan for 31 minutes. So the call
 * must carry its own timer that rejects AND aborts the transport, keeping the
 * per-row fault tolerance of the enumeration loop alive.
 */
describe('scenario preview hard read deadline', () => {
  it('hands the transport an abort signal so a stalled row can be interrupted', async () => {
    await runScenarioPreview(step(0))
    const cfg = requestCalls[requestCalls.length - 1]
    expect(cfg.signal).toBeInstanceOf(AbortSignal)
    expect((cfg.signal as AbortSignal).aborted).toBe(false)
  })

  it('rejects exactly at the read budget and aborts the stalled transport', async () => {
    vi.useFakeTimers()
    requestBehavior.impl = hangUntilAborted
    try {
      let settled = false
      const pending = runScenarioPreview(step(0)).catch((err: unknown) => {
        settled = true
        return err as Error
      })
      await vi.advanceTimersByTimeAsync(SCENARIO_PREVIEW_REQUEST_TIMEOUT_MS - 1)
      expect(settled).toBe(false)
      await vi.advanceTimersByTimeAsync(1)
      expect(settled).toBe(true)
      const err = await pending
      expect(err).toBeInstanceOf(Error)
      expect(err.message).toBe(ht('http.timeout'))
      const cfg = requestCalls[requestCalls.length - 1]
      expect((cfg.signal as AbortSignal).aborted).toBe(true)
    } finally {
      requestBehavior.impl = null
      vi.useRealTimers()
    }
  })

  it('turns one hung row into a recorded failure and keeps enumerating the rest', async () => {
    vi.useFakeTimers()
    requestBehavior.impl = (config) => {
      const seq = (config.data as { seq: number }).seq
      return seq === 0 ? hangUntilAborted(config) : Promise.resolve(seq)
    }
    try {
      const pending = enumeratePreviewPlan([step(0), step(1), step(2)], runScenarioPreview)
      await vi.advanceTimersByTimeAsync(SCENARIO_PREVIEW_REQUEST_TIMEOUT_MS)
      const outcome = await pending
      expect(outcome.completed).toBe(2)
      expect(outcome.failures).toEqual([{ seq: 0, message: ht('http.timeout') }])
    } finally {
      requestBehavior.impl = null
      vi.useRealTimers()
    }
  })
})
