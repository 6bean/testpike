import { td } from '../utils/dictLabel'
import { request } from './request'




export interface PersonalReviewView {
  reviewedCount: number
  passCount: number
  needChangeCount: number
  rejectCount: number
  avgReviewSeconds: number
}


export interface TeamMemberStatView {
  userId: number
  username: string | null
  nickName: string | null
  reviewedCount: number
  passCount: number
  needChangeCount: number
  rejectCount: number
  backlogCount: number
}


export interface StatusCount {
  status: number
  label: string
  count: number
}


export interface AiQualityView {
  totalReviewed: number
  firstPassCount: number
  firstPassRate: number
  modifyCount: number
  modifyRate: number
  rejectCount: number
  rejectRate: number
  statusDist: StatusCount[]
}


export interface ChapterCoverage {
  chapter: string
  total: number
  covered: number
  rate: number
}


export interface CoverageView {
  totalRequirements: number
  coveredRequirements: number
  coverageRate: number
  byChapter: ChapterCoverage[]
}


export interface ExecTrendPoint {
  date: string
  passCount: number
  failCount: number
 
  timeoutCount: number
  
  passRate: number
}


export interface FailDistItem {
  errorType: number
  label: string
  count: number
}


export interface ExecutionStatView {
  trend: ExecTrendPoint[]
  failDist: FailDistItem[]
}


export interface ExportResultView {
  async: boolean
  taskId: number
  fileName: string | null
  
  content: string | null
}


export interface ExportTaskView {
  id: number
  reportType: string
  masked: number
  
  status: number
  fileKey: string | null
  rowCount: number | null
  errorDetail: string | null
  createdAt: string
  finishedAt: string | null
}


export interface ExportDownloadView {
  taskId: number
  fileName: string
  presignedUrl: string
  status: number
}


export interface CompareCoverage {
  rate: number
  covered: number
  total: number
}


export interface CompareExecStat {
  pass: number
  fail: number
  skip: number
}


export interface CompareItemView {
  projectId: number
  projectName: string
  archived: boolean
  coverage: CompareCoverage
  aiQuality: AiQualityView
  exec: CompareExecStat
  flaky: number
}


export function getReportCompare(projectIds: number[]): Promise<CompareItemView[]> {
  return request<CompareItemView[]>({
    url: '/reports/compare',
    method: 'get',
    params: { projectIds: projectIds.join(',') },
  })
}


export interface CaliberView {
  code: string
  displayName: string
  version: string
  description: string
 
  hoverText: string
}

let calibersCache: Promise<CaliberView[]> | null = null


export function getCalibers(): Promise<CaliberView[]> {
  if (!calibersCache) {
    calibersCache = request<CaliberView[]>({ url: '/reports/calibers', method: 'get' }).catch((err) => {
      calibersCache = null
      throw err
    })
  }
  return calibersCache
}


export type ReportType = 'personal_review' | 'team_board' | 'ai_quality' | 'coverage' | 'execution_stat'

export const REPORT_TYPE_LABEL: Record<ReportType, string> = {
  get personal_review() { return td('dict.report.type.personalReview') },
  get team_board() { return td('dict.report.type.teamBoard') },
  get ai_quality() { return td('dict.report.type.aiQuality') },
  get coverage() { return td('dict.report.type.coverage') },
  get execution_stat() { return td('dict.report.type.executionStat') },
}


export function getPersonalReview(projectId: number, userId?: number): Promise<PersonalReviewView> {
  return request<PersonalReviewView>({ url: '/reports/personal-review', method: 'get', params: { projectId, userId } })
}


export function getTeamBoard(projectId: number): Promise<TeamMemberStatView[]> {
  return request<TeamMemberStatView[]>({ url: '/reports/team-board', method: 'get', params: { projectId } })
}


export interface PhaseCount {
  phase: number
  label: string
  count: number
}


export function getAiQuality(projectId: number): Promise<AiQualityView> {
  return request<AiQualityView>({ url: '/reports/ai-quality', method: 'get', params: { projectId } })
}


export function getPhaseDist(projectId: number): Promise<PhaseCount[]> {
  return request<PhaseCount[]>({ url: '/reports/phase-dist', method: 'get', params: { projectId } })
}


export function getCoverage(projectId: number): Promise<CoverageView> {
  return request<CoverageView>({ url: '/reports/coverage', method: 'get', params: { projectId } })
}


export function getExecutionStat(projectId: number, planId?: number): Promise<ExecutionStatView> {
  return request<ExecutionStatView>({
    url: '/reports/execution-stat',
    method: 'get',
    params: { projectId, planId },
  })
}


export function exportReport(projectId: number, reportType: ReportType, masked: boolean): Promise<ExportResultView> {
  return request<ExportResultView>({ url: '/reports/export', method: 'post', data: { projectId, reportType, masked } })
}


export function listExportTasks(projectId: number): Promise<ExportTaskView[]> {
  return request<ExportTaskView[]>({ url: '/reports/export-tasks', method: 'get', params: { projectId } })
}


export function getExportTaskDownload(projectId: number, taskId: number): Promise<ExportDownloadView> {
  return request<ExportDownloadView>({
    url: `/reports/export-tasks/${taskId}/download`,
    method: 'get',
    params: { projectId },
  })
}
