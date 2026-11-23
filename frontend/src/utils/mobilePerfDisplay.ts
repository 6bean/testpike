/**
 * Display projection for the batch mobile-performance rollup
 * (GET /batches/{id}/report field `mobilePerf`, core BatchReportSupport
 * snapshot). Pure formatting only: median / prevMedian / delta arrive
 * pre-computed at finalization and are forwarded verbatim — re-deriving
 * numbers here would create a second calculation site.
 *
 * The `tone` a delta carries depends on what the metric means: time, memory,
 * CPU, temperature and jank read lower-as-better, frame totals are volume
 * counts where a change is neither an improvement nor a regression, and any
 * future metric key outside the catalogue stays honest (raw name, no unit,
 * no verdict).
 */

export interface MobilePerfMetric {
  median: number
  taskCount?: number | null
  prevMedian?: number | null
  delta?: number | null
}

export interface MobilePerfRollup {
  metrics: Record<string, MobilePerfMetric>
  prevBatchId?: number | null
  sampleCount?: number | null
}

export type MobilePerfTone = 'better' | 'worse' | 'flat' | 'neutral' | 'none'

export interface MobilePerfRow {
  key: string
  label: string
  medianText: string
  prevText: string
  deltaText: string
  tone: MobilePerfTone
  taskCount: number | null
}

/** Units and direction semantics per metric, mirroring the executor probe. */
const METRIC_CATALOGUE: Record<string, { unit: string; lowerIsBetter: boolean }> = {
  jankPct: { unit: '%', lowerIsBetter: true },
  pssMb: { unit: 'MB', lowerIsBetter: true },
  cpuPct: { unit: '%', lowerIsBetter: true },
  tempC: { unit: '°C', lowerIsBetter: true },
  totalFrames: { unit: '', lowerIsBetter: false },
  jankyFrames: { unit: '', lowerIsBetter: true },
  launchTotalMs: { unit: 'ms', lowerIsBetter: true },
  launchWaitMs: { unit: 'ms', lowerIsBetter: true },
}

const ABSENT = '—'

function withUnit(value: number, unit: string): string {
  return `${value}${unit}`
}

function signedUnit(value: number, unit: string): string {
  return `${value > 0 ? '+' : ''}${value}${unit}`
}

function toneFor(delta: number | null | undefined, known: boolean, lowerIsBetter: boolean): MobilePerfTone {
  if (delta === null || delta === undefined || !known) return 'none'
  if (delta === 0) return 'flat'
  if (!lowerIsBetter) return 'neutral'
  return delta > 0 ? 'worse' : 'better'
}

export function projectMobilePerfRows(
  rollup: MobilePerfRollup | null | undefined,
  t: (key: string) => string,
): MobilePerfRow[] {
  const metrics = rollup?.metrics
  if (!metrics) return []
  return Object.entries(metrics).map(([key, value]) => {
    const entry = METRIC_CATALOGUE[key]
    const known = entry !== undefined
    const unit = entry?.unit ?? ''
    return {
      key,
      label: known ? t(`batch.mobilePerfMetrics.${key}`) : key,
      medianText: withUnit(value.median, unit),
      prevText: value.prevMedian === null || value.prevMedian === undefined ? ABSENT : withUnit(value.prevMedian, unit),
      deltaText: value.delta === null || value.delta === undefined ? ABSENT : signedUnit(value.delta, unit),
      tone: toneFor(value.delta, known, entry?.lowerIsBetter ?? false),
      taskCount: value.taskCount ?? null,
    }
  })
}
