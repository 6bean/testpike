import { describe, expect, it } from 'vitest'
import {
  projectMobilePerfRows,
  type MobilePerfRollup,
} from '@/utils/mobilePerfDisplay'

// Locale stub: keys resolve to the key itself so assertions stay readable
// and pinned to the single label source (t() call site, not hard-coded copy).
const t = (key: string) => key

describe('mobile perf rollup display projection', () => {
  it('renders one row per reported metric with server-provided numbers only', () => {
    const rollup: MobilePerfRollup = {
      metrics: {
        jankPct: { median: 12.5, taskCount: 4, prevMedian: 9.0, delta: 3.5 },
        launchTotalMs: { median: 5652, taskCount: 2, prevMedian: null, delta: null },
      },
      prevBatchId: 88,
      sampleCount: 41,
    }
    const rows = projectMobilePerfRows(rollup, t)
    expect(rows).toHaveLength(2)
    const jank = rows.find((r) => r.key === 'jankPct')
    expect(jank?.medianText).toBe('12.5%')
    expect(jank?.prevText).toBe('9%')
    // delta arrives pre-computed at finalization; the projection forwards it
    // verbatim (display must not re-derive median-prevMedian)
    expect(jank?.deltaText).toBe('+3.5%')
    expect(jank?.tone).toBe('worse')
    const launch = rows.find((r) => r.key === 'launchTotalMs')
    expect(launch?.medianText).toBe('5652ms')
    expect(launch?.prevText).toBe('—')
    expect(launch?.deltaText).toBe('—')
    expect(launch?.tone).toBe('none')
  })

  it('lower-is-better metrics read a decrease as an improvement', () => {
    const rollup: MobilePerfRollup = {
      metrics: { pssMb: { median: 120, taskCount: 3, prevMedian: 150, delta: -30 } },
      prevBatchId: 87,
      sampleCount: 12,
    }
    const [row] = projectMobilePerfRows(rollup, t)
    expect(row.tone).toBe('better')
    expect(row.deltaText).toBe('-30MB')
  })

  it('volume metrics never claim better or worse from a delta', () => {
    const rollup: MobilePerfRollup = {
      metrics: { totalFrames: { median: 300, taskCount: 1, prevMedian: 240, delta: 60 } },
      prevBatchId: null,
      sampleCount: 5,
    }
    const [row] = projectMobilePerfRows(rollup, t)
    expect(row.tone).toBe('neutral')
    expect(row.deltaText).toBe('+60')
  })

  it('a zero delta is honest flat, not an improvement', () => {
    const rollup: MobilePerfRollup = {
      metrics: { cpuPct: { median: 30, taskCount: 2, prevMedian: 30, delta: 0 } },
      prevBatchId: 90,
      sampleCount: 8,
    }
    const [row] = projectMobilePerfRows(rollup, t)
    expect(row.tone).toBe('flat')
    expect(row.deltaText).toBe('0%')
  })

  it('absent rollup renders no rows instead of zero-filled metrics', () => {
    expect(projectMobilePerfRows(null, t)).toEqual([])
    expect(projectMobilePerfRows(undefined, t)).toEqual([])
    expect(projectMobilePerfRows({ metrics: {}, prevBatchId: null, sampleCount: null }, t)).toEqual([])
  })

  it('unknown metric keys pass through with their raw name and no invented unit', () => {
    const rollup: MobilePerfRollup = {
      metrics: { futureGauge: { median: 7, taskCount: 1, prevMedian: null, delta: null } },
      prevBatchId: null,
      sampleCount: 1,
    }
    const [row] = projectMobilePerfRows(rollup, t)
    expect(row.label).toBe('futureGauge')
    expect(row.medianText).toBe('7')
    expect(row.tone).toBe('none')
  })

  it('labels come from the locale single source, keyed per metric', () => {
    const rollup: MobilePerfRollup = {
      metrics: { jankPct: { median: 1, taskCount: 1, prevMedian: null, delta: null } },
      prevBatchId: null,
      sampleCount: 1,
    }
    const [row] = projectMobilePerfRows(rollup, t)
    expect(row.label).toBe('batch.mobilePerfMetrics.jankPct')
  })
})
