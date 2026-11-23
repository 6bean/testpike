
import { PERF_RUN_STATUS } from '@/api/perf'
import { td } from '@/utils/dictLabel'

export const CHART_W = 640
export const CHART_H = 240
export const CHART_PAD = 10


export function maxOf(vals: (number | null | undefined)[]): number {
  let m = 0
  for (const v of vals) {
    if (typeof v === 'number' && v > m) m = v
  }
  return m
}


export function scaleX(min: number, max: number): (offset: number) => number {
  const left = CHART_PAD
  const right = CHART_W - CHART_PAD
  const span = max > min ? max - min : 1
  return (offset: number) => left + ((offset - min) / span) * (right - left)
}


export function scaleY(max: number): (v: number) => number {
  const innerTop = CHART_PAD
  const innerBottom = CHART_H - CHART_PAD
  const m = max > 0 ? max : 1
  return (v: number) => innerBottom - (Math.min(v, m) / m) * (innerBottom - innerTop)
}


export function buildPolySegments<T>(
  rows: T[],
  xOf: (row: T, i: number) => number,
  valOf: (row: T) => number | null | undefined,
  yOf: (v: number) => number,
): string[] {
  const segs: string[] = []
  let cur: string[] = []
  rows.forEach((row, i) => {
    const v = valOf(row)
    if (v === null || v === undefined) {
      if (cur.length >= 2) segs.push(cur.join(' '))
      cur = []
      return
    }
    cur.push(`${xOf(row, i).toFixed(1)},${yOf(v).toFixed(1)}`)
  })
  if (cur.length >= 2) segs.push(cur.join(' '))
  return segs
}


export function flatSegment(y: number): string {
  return `${CHART_PAD},${y.toFixed(1)} ${CHART_W - CHART_PAD},${y.toFixed(1)}`
}


export function parseSnapshot(snap: string | null | undefined): Record<string, unknown> | null {
  if (!snap) return null
  try {
    const o: unknown = JSON.parse(snap)
    return o && typeof o === 'object' ? (o as Record<string, unknown>) : null
  } catch {
    return null
  }
}

/** GA-5 pinned target version read (single helper, no second parser): the launch
 *  snapshot freezes the pressed environment's deployed build as targetVersion;
 *  absent/blank = null so callers render the honest "not recorded" wording
 *  instead of a blank row or a guessed label. */
export function targetVersionOf(snap: string | null | undefined): string | null {
  const v = parseSnapshot(snap)?.targetVersion
  return typeof v === 'string' && v.trim() ? v.trim() : null
}

/** B-10 pinned TLS tier read ( , single helper over the shared
 * parser — same posture as targetVersionOf): the launch snapshot freezes the
 * effective environment's verification tier (strict / custom_ca / insecure).
 * Absent or blank = null = a legacy run whose tier was never pinned — the
 * honest "not recorded" wording, never a fabricated "strict". */
export function tlsTierOf(snap: string | null | undefined): string | null {
  const v = parseSnapshot(snap)?.tlsMode
  return typeof v === 'string' && v.trim() ? v.trim() : null
}

/** CF-6 canary pre-flight read (single helper over the shared parser): when the
 *  hot switch was on at launch, the snapshot pins the low-load probe outcome
 *  under "canary" (probe counts, measured baseline p95, engine, compile
 *  pre-warm flag). Absent or malformed = null so the header line disappears
 *  honestly — never an empty row, never a guessed label (switch off keeps the
 *  report face byte-for-byte as before). */
export function canaryOf(
  snap: string | null | undefined,
): { probes: number; answered: number; baselineP95Ms: number; engine: string | null; prewarmed: boolean } | null {
  const raw = parseSnapshot(snap)?.canary
  if (!raw || typeof raw !== 'object') return null
  const o = raw as Record<string, unknown>
  if (typeof o.probes !== 'number' || typeof o.answered !== 'number' || typeof o.baselineP95Ms !== 'number') return null
  return {
    probes: o.probes,
    answered: o.answered,
    baselineP95Ms: o.baselineP95Ms,
    engine: typeof o.engine === 'string' && o.engine.trim() ? o.engine.trim() : null,
    prewarmed: o.prewarmed === true,
  }
}


export function runStatusTag(status: number): { label: string; type: 'primary' | 'success' | 'danger' | 'warning' | 'info' } {
  const entry = PERF_RUN_STATUS[status]
  return entry ? { label: td(entry.label), type: entry.type } : { label: String(status), type: 'info' as const }
}


export function fmtTime(v: string | null | undefined): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

export function fmtNum(v: number | null | undefined, digits = 2): string {
  return v === null || v === undefined ? '—' : v.toFixed(digits)
}


export function fmtRate(v: number | null | undefined): string {
  return v === null || v === undefined ? '—' : `${(v * 100).toFixed(2)}%`
}
