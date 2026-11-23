import { ht } from '../utils/dictLabel'
import { request, type PageResult } from './request'
import { fetchAndDownloadBlob } from '@/utils/exportDownload'




export interface ReportCenterListItem {
  id: number
  scopeType: number
  scopeId: number
  reportType: number
  periodStart: string
  periodEnd: string
  generatedAt: string
  createdBy: number
}


export interface ReportCenterDetail {
  id: number
  scopeType: number
  scopeId: number
  reportType: number
  periodStart: string
  periodEnd: string
  payloadJson: string
  generatedAt: string
  createdBy: number
}


export interface ReportCenterQuery {
  page?: number
  size?: number
  reportType?: number | null
  scopeType?: number | null
  scopeId?: number | null
  periodFrom?: string
  periodTo?: string
  /** Server-side sort key (core tp_report whitelist; empty = default order). */
  sortBy?: string
  sortDir?: 'asc' | 'desc'
  /** Program lens (project set) id; absent = no lens, request keeps the
   *  pre-feature shape (legacy zero drift). Server narrows the ledger to
   *  lens projects ∩ caller visibility. */
  programId?: number
}


export interface ReportCenterGeneratePayload {
  reportType: number
  scopeType: number
  scopeId?: number | null
  periodStart: string
  periodEnd: string
}


export interface ReportPayloadCaliberBlock {
  display_name: string
  version: string
  value?: number | null
  rows?: { value: string; extra: string }[]
}


export interface ReportPayload {
  report_type: string
  scope: { type: string; id: number }
  period: { start: string; end: string }
  calibers: Record<string, ReportPayloadCaliberBlock>
  caliber_registry_size: number
}


export function listReportCenter(query: ReportCenterQuery): Promise<PageResult<ReportCenterListItem>> {
  return request<PageResult<ReportCenterListItem>>({ url: '/report-center', method: 'get', params: query })
}


export function getReportCenterDetail(id: number): Promise<ReportCenterDetail> {
  return request<ReportCenterDetail>({ url: `/report-center/${id}`, method: 'get' })
}


export function generateReportCenter(data: ReportCenterGeneratePayload): Promise<ReportCenterDetail> {
  return request<ReportCenterDetail>({ url: '/report-center/generate', method: 'post', data })
}


export function downloadReportCenterFile(
  id: number,
  format: 'csv' | 'pdf',
  theme?: 'light' | 'dark',
): Promise<void> {
  const q = theme ? `&theme=${theme}` : ''
  return fetchAndDownloadBlob(`/api/v1/report-center/${id}/export?format=${format}${q}`, ht('http.reportSnapshotFile', { format }))
}


export function parseReportPayload(payloadJson: string): ReportPayload {
  return JSON.parse(payloadJson) as ReportPayload
}

/** Per-project live pass rate inside the program overview feed; null = no executions in the window. */
export interface ProgramProjectPassRateView {
  projectId: number
  passRate: number | null
}

/** Caliber footnote read straight from the server registry (UI quotes it verbatim, never recomputes). */
export interface ProgramCaliberFootnoteView {
  code: string
  displayName: string
  version: string
}

/**
 * Per-project defect burndown block over the feed window (open caliber status ≠ closed;
 * invariant openAtEnd = openAtStart + createdWithin - closedWithin holds server-side).
 * A defect-free project reports an honest all-zero row rather than a missing entry.
 */
export interface ProgramDefectBurnWindowView {
  projectId: number
  openAtStart: number
  createdWithin: number
  closedWithin: number
  openAtEnd: number
}

/**
 * Per-project journey traffic light over the feed window (latest in-window run
 * decides: green succeeded / amber running / red failed / grey no verdict — a
 * cancelled run or no run at all reads grey, never a missing entry).
 */
export interface ProgramJourneyTrafficLightView {
  projectId: number
  journeyCount: number
  segmentCount: number
  runsInWindow: number
  latestRunAt: string | null
  light: 'green' | 'amber' | 'red' | 'grey'
}

/**
 * Per-project load-test overview block over the feed window (latest in-window run
 * decides: green normal end / amber running / red circuit-break stop / grey no
 * verdict — a manually stopped run or no run at all reads grey; peaks are the worst
 * in-window frames and stay null when the window holds no measurement).
 */
export interface ProgramPerfOverviewView {
  projectId: number
  scenarioCount: number
  runsInWindow: number
  latestRunId: number | null
  latestRunAt: string | null
  light: 'green' | 'amber' | 'red' | 'grey'
  peakRps: number | null
  peakP95Ms: number | null
  peakErrorRate: number | null
}

/**
 * Live program-lens dashboard feed (admin ReportCenterProgramOverviewView mirror;
 * field names ride the JSON contract, the bridge never reshapes).
 */
export interface ProgramOverviewView {
  programId: number
  programName: string
  periodFrom: string
  periodTo: string
  projects: ProgramProjectPassRateView[]
  defectBurn: ProgramDefectBurnWindowView[]
  journeyTrafficLight: ProgramJourneyTrafficLightView[]
  perfOverview: ProgramPerfOverviewView[]
  caliberRegistry: ProgramCaliberFootnoteView[]
}

/**
 * Program-lens dashboard overview (project set id; from/to optional ISO date-times,
 * server defaults the window when absent). Outsiders get an empty feed, not 403;
 * an unknown project set returns business code 40401.
 */
export function getProgramOverview(programId: number, from?: string, to?: string): Promise<ProgramOverviewView> {
  return request<ProgramOverviewView>({ url: '/report-center/program-overview', method: 'get', params: { programId, from, to } })
}
