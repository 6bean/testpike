
import type { ExecStepEvidence } from '@/api/executor'
import type { StepTimelineItem } from '@/api/executor'


export interface EvidencePane {
  slot: 'screenshot' | 'viewTree' | 'logTail' | 'record' | 'netTimeline' | 'perfTimeline'
  key: string | null
  url: string | null
  present: boolean
}


export interface PerfSampleRow {
  ts: number
  totalFrames?: number
  jankyFrames?: number
  jankPct?: number
  pssKb?: number
  cpuPct?: number
  tempC?: number
}


export type StepEvidenceRow = ExecStepEvidence


export interface FailedStepEvidence {
  stepNo: number
  
  action: string | null
  panes: EvidencePane[]
}


export function normalizeEvidenceRows(raw: unknown): ExecStepEvidence[] {
  if (!Array.isArray(raw)) return []
  return raw.filter(
    (x): x is ExecStepEvidence => !!x && typeof x === 'object' && typeof (x as ExecStepEvidence).stepNo === 'number',
  )
}


function paneOf(slot: EvidencePane['slot'], key: string | null, url: string | null): EvidencePane {
  const u = typeof url === 'string' && url.length > 0 ? url : null
  return { slot, key: key ?? null, url: u, present: u != null }
}


export function evidencePanesOf(row: ExecStepEvidence): EvidencePane[] {
  return [
    paneOf('screenshot', row.screenshotKey, row.screenshotUrl),
    paneOf('viewTree', row.viewTreeKey, row.viewTreeUrl),
    paneOf('logTail', row.logTailKey, row.logTailUrl),
    paneOf('record', row.recordKey ?? null, row.recordUrl ?? null),
    paneOf('netTimeline', row.netTimelineKey ?? null, row.netTimelineUrl ?? null),
    paneOf('perfTimeline', row.perfTimelineKey ?? null, row.perfTimelineUrl ?? null),
  ]
}


export function parsePerfSampleRows(text: string): PerfSampleRow[] {
  const out: PerfSampleRow[] = []
  for (const line of (text || '').split('\n')) {
    const t = line.trim()
    if (!t) continue
    let obj: unknown
    try {
      obj = JSON.parse(t)
    } catch {
      continue
    }
    if (!obj || typeof obj !== 'object') continue
    const row = obj as Record<string, unknown>
    if (typeof row.ts !== 'number' || !Number.isFinite(row.ts)) continue
    const item: PerfSampleRow = { ts: row.ts }
    for (const k of ['totalFrames', 'jankyFrames', 'jankPct', 'pssKb', 'cpuPct', 'tempC'] as const) {
      if (typeof row[k] === 'number' && Number.isFinite(row[k] as number)) {
        item[k] = row[k] as number
      }
    }
    out.push(item)
  }
  return out
}


export function slicePerfSamplesByStep(
  samples: PerfSampleRow[],
  step: { tsStart?: number | null; tsEnd?: number | null },
): PerfSampleRow[] {
  const a = typeof step.tsStart === 'number' ? step.tsStart : null
  const b = typeof step.tsEnd === 'number' ? step.tsEnd : null
  if (a == null && b == null) return samples
  const from = a ?? b!
  const to = b ?? a!
  const lo = Math.min(from, to)
  const hi = Math.max(from, to)
  return samples.filter((s) => s.ts >= lo && s.ts <= hi)
}


export function triptychHasAny(e: FailedStepEvidence): boolean {
  return e.panes.some((p) => p.present)
}


export function buildFailedStepEvidence(
  timeline: StepTimelineItem[],
  rows: ExecStepEvidence[],
): FailedStepEvidence[] {
  const byStep = new Map<number, ExecStepEvidence>()
  for (const r of rows) byStep.set(r.stepNo, r)
  const out: FailedStepEvidence[] = []
  for (const item of timeline) {
    if (typeof item.stepNo !== 'number') continue
    if (typeof item.status !== 'string' || item.status.length === 0) continue
    if (item.status === 'ok') continue
    const ev = byStep.get(item.stepNo)
    out.push({
      stepNo: item.stepNo,
      action: ev?.action ?? item.action ?? null,
      panes: evidencePanesOf(ev ?? emptyEvidenceRow(item.stepNo)),
    })
  }
  return out
}


function emptyEvidenceRow(stepNo: number): ExecStepEvidence {
  return {
    taskId: 0,
    stepNo,
    action: null,
    screenshotKey: null,
    screenshotUrl: null,
    viewTreeKey: null,
    viewTreeUrl: null,
    logTailKey: null,
    logTailUrl: null,
    recordKey: null,
    recordUrl: null,
    netTimelineKey: null,
    netTimelineUrl: null,
    perfTimelineKey: null,
    perfTimelineUrl: null,
  }
}
