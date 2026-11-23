import { td } from '../utils/dictLabel'
import { request } from './request'




export interface CaseScopeRule {
  phases: number[]
  priorities: number[]
  keyword?: string | null
  tagIds: number[]
}


export interface EntryCriteria {
  requireSmokePass: boolean
}


export interface ExitCriteria {
  minPassRate: number
  noOpenDefects: boolean
}

export interface PlanView {
  id: number
  projectId: number
  name: string
  targetVersion: string | null
  status: number
  statusLabel: string
  ownerId: number | null
  envIds: number[]
  caseScope: CaseScopeRule | null
  entryCriteria: EntryCriteria | null
  exitCriteria: ExitCriteria | null
  planStart: string | null
  planEnd: string | null
  caseCount: number
  assigneeCount: number
  createdBy: number
  createdAt: string
  lockMode: number
}

export interface PlanCreatePayload {
  projectId: number
  name: string
  targetVersion?: string | null
  ownerId?: number | null
  envIds: number[]
  caseScope: CaseScopeRule
  entryCriteria: EntryCriteria
  exitCriteria: ExitCriteria
  planStart?: string | null
  planEnd?: string | null
  lockMode?: number | null
}

export interface PlanCaseView {
  id: number
  planId: number
  caseId: number
  caseNo: string
  title: string
  phase: number | null
  assigneeId: number | null
  assigneeName: string | null
  status: number
  statusLabel: string
  batchCaseRef: number | null
}

export interface ProgressDimRow {
  key: string
  label: string
  total: number
  passed: number
  failed: number
  notRun: number
  running: number
  skipped: number
}

export interface PlanProgressView {
  total: number
  notRun: number
  running: number
  passed: number
  failed: number
  skipped: number
  passRate: number
  byAssignee: ProgressDimRow[]
  byPhase: ProgressDimRow[]
  byEnv: ProgressDimRow[]
}

export interface CompletionCheckView {
  passed: boolean
  message: string
  passRate: number
  minPassRate: number
  openDefectCount: number
  p0OpenDefectCount: number
  total: number
  executed: number
  passedCount: number
  failedCount: number
  skippedCount: number
  notRunCount: number
}

export interface SkipItemView {
  caseId: number
  caseNo: string
  title: string
  status: number
  
  reason?: string | null
}

export interface GenerateBatchResultView {
  planId: number
  batchCount: number
  taskCount: number
  skippedNoScript: number
  skipCount: number
  skippedCases: SkipItemView[]
  message: string
  
  envHealthWarning?: string | null
}


export interface PlanSummaryRow {
  planId: number
  planName: string
  status: number
  statusLabel: string
  caseCount: number
  passed: number
  failed: number
  notRun: number
  running: number
  skipped: number
  passRate: number
  planStart: string | null
  planEnd: string | null
}

/** Plan list (paging, H1 project isolation, whitelisted server-side sorting). */
export function listPlans(params: {
  projectId: number
  status?: number | null
  keyword?: string
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
}): Promise<{ list: PlanView[]; total: number }> {
  return request({ url: '/plans', method: 'get', params })
}


export function createPlan(payload: PlanCreatePayload): Promise<PlanView> {
  return request({ url: '/plans', method: 'post', data: payload })
}


export function getPlan(id: number): Promise<PlanView> {
  return request({ url: `/plans/${id}`, method: 'get' })
}


export function updatePlan(id: number, payload: PlanCreatePayload): Promise<PlanView> {
  return request({ url: `/plans/${id}`, method: 'put', data: payload })
}


export function listPlanCases(id: number, status?: number | null): Promise<PlanCaseView[]> {
  return request({ url: `/plans/${id}/cases`, method: 'get', params: { status } })
}


export function assignPlanCases(id: number, caseIds: number[], assigneeId: number): Promise<number> {
  return request({ url: `/plans/${id}/assign`, method: 'post', data: { caseIds, assigneeId } })
}


export function publishPlan(id: number): Promise<PlanView> {
  return request({ url: `/plans/${id}/publish`, method: 'post' })
}


export function generatePlanBatches(id: number): Promise<GenerateBatchResultView> {
  return request({ url: `/plans/${id}/generate-batches`, method: 'post' })
}


export function getPlanProgress(id: number): Promise<PlanProgressView> {
  return request({ url: `/plans/${id}/progress`, method: 'get' })
}


export function getPlanProgressSummary(projectId: number): Promise<PlanSummaryRow[]> {
  return request({ url: '/plans/progress-summary', method: 'get', params: { projectId } })
}


export function checkPlanCompletion(id: number): Promise<CompletionCheckView> {
  return request({ url: `/plans/${id}/completion-check`, method: 'get' })
}


export function cancelPlan(id: number): Promise<void> {
  return request({ url: `/plans/${id}/cancel`, method: 'post' })
}


export function deletePlan(id: number, force = false): Promise<void> {
  return request({ url: `/plans/${id}`, method: 'delete', params: { force } })
}


export interface PlanDeviationView {
  id: number
  planId: number
  caseId: number
  caseNo: string | null
  caseTitle: string | null
  deviationType: number
  deviationTypeLabel: string
  reason: string
  createdBy: number
  createdByName: string | null
  createdAt: string
}


export function registerPlanDeviation(
  id: number,
  payload: { caseId: number; deviationType: number; reason: string }
): Promise<PlanDeviationView> {
  return request({ url: `/plans/${id}/deviations`, method: 'post', data: payload })
}


export function listPlanDeviations(id: number): Promise<PlanDeviationView[]> {
  return request({ url: `/plans/${id}/deviations`, method: 'get' })
}


export function checkCaseAllowed(id: number, caseId: number): Promise<void> {
  return request({ url: `/plans/${id}/case-allowed`, method: 'get', params: { caseId } })
}


export function planStatusLabel(status: number): string {
  const keys = ['', 'dict.plan.status.draft', 'dict.plan.status.published',
    'dict.plan.status.executing', 'dict.plan.status.done', 'dict.plan.status.canceled']
  return keys[status] ? td(keys[status]) as string : String(status)
}

export function planStatusTag(status: number): 'info' | 'primary' | 'warning' | 'success' | 'danger' {
  return ['', 'info', 'primary', 'warning', 'success', 'danger'][status] as 'info' | 'primary' | 'warning' | 'success' | 'danger'
}
