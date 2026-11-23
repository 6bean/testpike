import { td } from '../utils/dictLabel'
import { request, type PageResult } from './request'




export const DEFECT_SEVERITY_LABEL: Record<number, string> = {
  get 1() { return td('dict.defectLib.severity.p0') },
  get 2() { return td('dict.defectLib.severity.p1') },
  get 3() { return td('dict.defectLib.severity.p2') },
  get 4() { return td('dict.defectLib.severity.p3') },
}


export const DEFECT_STATUS_LABEL: Record<number, string> = {
  get 1() { return td('dict.defectLib.status.new') },
  get 2() { return td('dict.defectLib.status.inProgress') },
  get 3() { return td('dict.defectLib.status.fixed') },
  get 4() { return td('dict.defectLib.status.verifying') },
  get 5() { return td('dict.defectLib.status.closed') },
  get 6() { return td('dict.defectLib.status.reopened') },
}


export const DEFECT_STATUS_TAG: Record<number, 'info' | 'primary' | 'success' | 'warning' | 'danger'> = {
  1: 'info',
  2: 'primary',
  3: 'success',
  4: 'warning',
  5: 'info',
  6: 'danger',
}

export interface DefectLibraryView {
  id: number
  projectId: number
  caseId: number | null
  planId: number | null
  title: string
  description: string | null
  severity: number
  status: number
  assigneeId: number | null
  assigneeName: string | null
  reporterId: number
  reporterName: string | null
  stepsJson: string | null
  externalKey: string | null
  closedReason: string | null
  source: number
  jiraSyncStatus: number
  jiraSyncError: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface DefectCreatePayload {
  projectId: number
  caseId?: number | null
  planId?: number | null
  title: string
  description?: string | null
  severity: number
  assigneeId?: number | null
  stepsJson?: string | null
  externalKey?: string | null
}

export interface DefectUpdatePayload {
  title: string
  description?: string | null
  severity: number
  assigneeId?: number | null
  stepsJson?: string | null
  externalKey?: string | null
  version: number
}

export interface DefectTransitionPayload {
  toStatus: number
  closedReason?: string | null
  version: number
}

export interface DefectSummaryView {
  caseId: number
  total: number
  openCount: number
}


export function listDefectLibrary(params: {
  projectId: number
  status?: number | null
  severity?: number | null
  assigneeId?: number | null
  caseId?: number | null
  keyword?: string | null
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
  /** Header program lens (MP4-b): optional project-set scope, conjuncted server-side (MP3b-2). */
  programId?: number
}): Promise<PageResult<DefectLibraryView>> {
  return request<PageResult<DefectLibraryView>>({ url: '/defect-library', method: 'get', params })
}


export function getDefect(id: number): Promise<DefectLibraryView> {
  return request<DefectLibraryView>({ url: `/defect-library/${id}`, method: 'get' })
}


export function createDefect(data: DefectCreatePayload): Promise<DefectLibraryView> {
  return request<DefectLibraryView>({ url: '/defect-library', method: 'post', data })
}


export function updateDefect(id: number, data: DefectUpdatePayload): Promise<DefectLibraryView> {
  return request<DefectLibraryView>({ url: `/defect-library/${id}`, method: 'put', data })
}


export function transitionDefect(id: number, data: DefectTransitionPayload): Promise<DefectLibraryView> {
  return request<DefectLibraryView>({ url: `/defect-library/${id}/transition`, method: 'post', data })
}


export function summaryByCase(caseId: number): Promise<DefectSummaryView> {
  return request<DefectSummaryView>({ url: '/defect-library/summary', method: 'get', params: { caseId } })
}


export interface DefectExportResult {
  async: boolean
  taskId: number
  fileName?: string
  content?: string
  total: number
  removed: number
}


export interface ExportDefectsPayload {
  projectId: number
  ids?: number[]
  status?: number | null
  severity?: number | null
  assigneeId?: number | null
  caseId?: number | null
  keyword?: string | null
}


export function exportDefects(data: ExportDefectsPayload): Promise<DefectExportResult> {
  return request<DefectExportResult>({ url: '/defect-library/export', method: 'post', data })
}
