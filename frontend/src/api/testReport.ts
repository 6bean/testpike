import { ht } from '../utils/dictLabel'
import { request, type PageResult } from './request'
import { fetchAndDownloadBlob } from '@/utils/exportDownload'



export interface ReportRecordView {
  id: number
  projectId: number
  planId: number | null
  planName: string | null
  title: string
  rangeType: number
  rangeLabel: string
  createdByName: string
  createdAt: string
}

export interface ReportDetailView {
  id: number
  projectId: number
  planId: number | null
  planName: string | null
  title: string
  rangeType: number
  rangeLabel: string
  scope: { planName: string | null; rangeLabel: string; targetVersion: string; caseTotal: number; planCount: number }
  results: { total: number; notRun: number; running: number; passed: number; failed: number; skipped: number; passRate: number; unstableSkipped: number }
  defectDist: {
    total: number
    openCount: number
    bySeverity: { label: string; count: number }[]
    byStatus: { label: string; count: number }[]
  }
  envVersions: { envName: string; version: string; batchCount: number; passed: number; failed: number }[]
  conclusion: { pass: boolean; summary: string }
  createdByName: string
  createdAt: string
}

export interface ReportGeneratePayload {
  projectId: number
  rangeType: number
  planId?: number | null
  startTime?: string | null
  endTime?: string | null
  title?: string | null
}

export function generateReport(payload: ReportGeneratePayload): Promise<ReportRecordView> {
  return request<ReportRecordView>({ url: '/reports/generate', method: 'post', data: payload })
}

export function listReports(params: {
  projectId: number
  keyword?: string
  page?: number
  size?: number
  /** core testreport SORT_COLUMNS whitelist key (undefined = default id DESC) */
  sortBy?: string
  /** asc | desc; anything else falls back to desc on the backend */
  sortDir?: string
}): Promise<PageResult<ReportRecordView>> {
  return request<PageResult<ReportRecordView>>({ url: '/reports', method: 'get', params })
}

export function getReport(id: number): Promise<ReportDetailView> {
  return request<ReportDetailView>({ url: `/reports/${id}`, method: 'get' })
}


export function downloadReportFile(id: number, format: 'xlsx' | 'pdf'): Promise<void> {
  return fetchAndDownloadBlob(`/api/v1/reports/${id}/export?format=${format}`, ht('http.reportSummaryFile', { format }))
}
