


export interface MonitorFrame {
  taskId?: number
  stepNo?: number | null
  image?: string | null
  sensitive?: boolean | null
}


export interface MonitorFrameSummary {
  frames: MonitorFrame[]
  dropped: number
  trimmed: number
}


export interface MonitorViewState {
  
  latest: MonitorFrame | null
  
  pausedBySensitive: boolean
  
  latestStepNo: number | null
}

export const EMPTY_MONITOR_VIEW: MonitorViewState = {
  latest: null,
  pausedBySensitive: false,
  latestStepNo: null,
}

function asCount(v: unknown): number {
  const n = typeof v === 'string' ? Number(v) : typeof v === 'number' ? v : 0
  return Number.isFinite(n) && n >= 0 ? Math.floor(n) : 0
}


export function summarizeMonitorFrames(data: unknown): MonitorFrameSummary {
  const obj = (data && typeof data === 'object' ? data : {}) as Record<string, unknown>
  const raw = Array.isArray(obj.frames) ? obj.frames : []
  const frames = raw.filter(
    (f): f is MonitorFrame => !!f && typeof f === 'object' && typeof (f as MonitorFrame).image === 'string',
  )
  return { frames, dropped: asCount(obj.dropped), trimmed: asCount(obj.trimmed) }
}


export function applyMonitorFrames(prev: MonitorViewState | null, frames: MonitorFrame[]): MonitorViewState {
  const valid = frames.filter((f) => !!f && typeof f.image === 'string' && f.image !== '')
  if (!valid.length) return { ...EMPTY_MONITOR_VIEW }
  const head = valid[0]
  const stepNo = typeof head.stepNo === 'number' ? head.stepNo : null
  if (head.sensitive) {
    return { latest: prev?.latest ?? null, pausedBySensitive: true, latestStepNo: stepNo }
  }
  return { latest: head, pausedBySensitive: false, latestStepNo: stepNo }
}


export function degradeNotice(dropped: number, trimmed: number): 'dropped' | 'trimmed' | null {
  if (dropped > 0) return 'dropped'
  if (trimmed > 0) return 'trimmed'
  return null
}
