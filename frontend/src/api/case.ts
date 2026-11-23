import { td } from '../utils/dictLabel'
import { request, type PageResult } from './request'
import type { BatchStartResult } from './batch'




export interface CaseItem {
  id: number
  caseNo: string
  projectId: number
  title: string
  precondition?: string | null
  expectedResult?: string | null
  /** 0=P0 1=P1 2=P2 */
  priority: number
  
  confidence: number
  /** web/mobile/pos/multi */
  endType: string
  
  status: number
  
  phase?: number | null
  
  tags?: Array<{ id: number; projectId: number; name: string; type: number; caseCount: number }>
  version: number
  assigneeId?: number | null
  /** 1=AI generated 2=template 3=legacy import 4=clone 5=explore-to-case 6=sandbox promotion */
  source: number
  sourceRef?: string | null
  createdBy?: number
  createdAt?: string
  updatedAt?: string
  steps?: CaseStep[]
  
  flaky?: number
 
  offlineLinked?: boolean | null
  
  flakyReason?: string | null
  
  flakyMarkedAt?: string | null
 
  excellent?: number
 
  automationTarget?: string | null
  
  appautoEngine?: string | null
  
  appautoEnvProfileId?: number | null
  /** Declared mock binding (admin CaseView mirror; null = runs against the real environment) */
  mockSpaceId?: number | null
  /** Platform evidence grade 0=none 1=off-site self-report 2=off-site attested 3=platform re-verified (core owns the value set; admin CaseView tail pass-through) */
  evidenceGrade?: number | null
  /** Provenance facet chat|off_agent|import|manual (single-sourced by core CaseSourceFacet; the frontend never re-derives it) */
  sourceFacet?: string | null
}


export interface CaseStep {
  stepNo?: number
  description: string
  isAssert?: number
  /** Step-level evidence anchor (canonical jsonb text owned by the core normalizer; null/absent = none recorded) */
  anchor?: string | null
}


export interface CaseQuery {
  projectId: number
  status?: number
  keyword?: string
  priority?: number
  confidence?: number
  assigneeId?: number
  
  phase?: number
  
  tagIds?: number[]
 
  excellentOnly?: boolean
  sortBy?: string
  sortDir?: string
  /**
 * header program lens (MP4-b): optional project-set scope. The page
 * forwards useProgramContextStore.activeProgramId verbatim; absent =
 * no lens, exact pre-feature request (legacy zero drift — core conjuncts
 * programId ∧ projectId server-side).
 */
  programId?: number
  /** Provenance quick filter (chat|off_agent|import|manual; core rejects unknown values with a pointed message) */
  sourceFacet?: string
  /** Evidence-grade quick filter (0..3; unknown values rejected server-side) */
  evidenceGrade?: number
  page?: number
  size?: number
}


export interface CreateCasePayload {
  projectId: number
  title: string
  precondition?: string
  expectedResult?: string
  priority?: number
  confidence?: number
  endType?: string
  status?: number
  phase?: number
  steps?: CaseStep[]
 
  tagIds?: number[]
 
  assigneeId?: number
 
  automationTarget?: string
  
  appautoEngine?: string
  
  appautoEnvProfileId?: number
  /** Declarative mock binding (0/null = unbound; must be a published own-project space — core gate) */
  mockSpaceId?: number | null
}


export interface UpdateCasePayload {
  title?: string
  precondition?: string
  expectedResult?: string
  priority?: number
  confidence?: number
  endType?: string
  phase?: number
  steps?: CaseStep[]
 
  tagIds?: number[]
 
  assigneeId?: number
 
  automationTarget?: string
  
  appautoEngine?: string
  
  appautoEnvProfileId?: number
  /** Mock binding (null = untouched; 0 = unbind back to the real environment — core gate owns legality) */
  mockSpaceId?: number | null
  version: number
}


export type ReviewAction = 'pass' | 'need_change' | 'reject'

export interface ReviewPayload {
  action: ReviewAction
  remark?: string
  version: number
  
  feedbackTypes?: string[]
}

export interface ReviewResult {
  caseId: number
  status: number
  version: number
}


export interface BatchReviewResult {
  succeeded: number[]
  skipped: Array<{ id: number; reason: string }>
}


export interface CaseReviewLogItem {
  id: number
  caseId: number
  userId: number
  
  operatorName?: string | null
  
  action: string
  remark?: string | null
  
  feedbackTypes?: string[] | null
  createdAt: string
}


export interface AssignResult {
  caseId: number
  assigneeId: number
}


export interface BatchAssignResult {
  assigned: number[]
  skipped: Array<{ id: number; reason: string }>
}


export interface CaseScript {
  id: number
  caseId: number
  schemaVersion: string
  actionJson: string
  status: number
  generatedBy: number
  lastTrialResult?: number | null
  version: number
  createdAt?: string
  updatedAt?: string
}


export interface ScriptRegenResult {
  taskId?: number
 
  need_confirm?: boolean
  message?: string
}

export function listCases(params: CaseQuery): Promise<PageResult<CaseItem>> {
  return request<PageResult<CaseItem>>({ url: '/cases', method: 'get', params })
}

export function getCase(id: number): Promise<CaseItem> {
  return request<CaseItem>({ url: `/cases/${id}`, method: 'get' })
}


export function checkCaseReferences(id: number): Promise<string[]> {
  return request<string[]>({ url: `/cases/${id}/references`, method: 'get' })
}

export function createCase(data: CreateCasePayload): Promise<CaseItem> {
  return request<CaseItem>({ url: '/cases', method: 'post', data })
}

export function updateCase(id: number, data: UpdateCasePayload): Promise<CaseItem> {
  return request<CaseItem>({ url: `/cases/${id}`, method: 'put', data })
}


export function deleteCase(id: number): Promise<number> {
  return request<number>({ url: `/cases/${id}`, method: 'delete' })
}


export interface CaseRecycleItem {
  id: number
  caseNo: string
  projectId: number
  title: string
  priority: number
  status: number
  version: number
  deletedBy?: number | null
  deletedByName?: string | null
  deletedAt?: string | null
}


export function listRecycleBin(params: { projectId: number; keyword?: string; page?: number; size?: number }):
  Promise<PageResult<CaseRecycleItem>> {
  return request<PageResult<CaseRecycleItem>>({ url: '/cases/recycle-bin', method: 'get', params })
}


export function restoreCase(id: number, version: number): Promise<CaseItem> {
  return request<CaseItem>({ url: `/cases/${id}/restore`, method: 'put', data: { version } })
}


export function purgeCase(id: number): Promise<number> {
  return request<number>({ url: `/cases/${id}/purge`, method: 'delete' })
}


export function clearRecycleBin(projectId: number): Promise<number> {
  return request<number>({ url: '/cases/recycle-bin', method: 'delete', params: { projectId } })
}


export interface CaseExportResult {
  async: boolean
  taskId: number
  fileName?: string
  content?: string
  total: number
  removed: number
}


export interface ExportCasesPayload {
  projectId: number
  ids?: number[]
  status?: number
  keyword?: string
  priority?: number
  confidence?: number
  assigneeId?: number
  phase?: number
  tagIds?: number[]
  /** Provenance channel quick filter (chat|off_agent|import|manual) */
  sourceFacet?: string
  /** Evidence-grade quick filter (0..3) */
  evidenceGrade?: number
}


export function exportCases(data: ExportCasesPayload): Promise<CaseExportResult> {
  return request<CaseExportResult>({ url: '/cases/export', method: 'post', data })
}


export function reviewCase(id: number, data: ReviewPayload, idempotencyKey?: string): Promise<ReviewResult> {
  return request<ReviewResult>({
    url: `/cases/${id}/review`,
    method: 'post',
    data,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
}


export function batchReview(data: { action: ReviewAction; caseIds: number[]; remark?: string; confirmLowConfidence?: boolean; feedbackTypes?: string[] }): Promise<BatchReviewResult> {
  return request<BatchReviewResult>({ url: '/cases/batch-review', method: 'post', data })
}


export function listCaseReviewLogs(id: number): Promise<CaseReviewLogItem[]> {
  return request<CaseReviewLogItem[]>({ url: `/cases/${id}/review-logs`, method: 'get' })
}


import type { VersionChainFields } from './versionChain'

export interface CaseVersionItem extends VersionChainFields {
  caseId: number
}


export interface CaseVersionChangeItem {
  field: string
  label: string
  before: unknown
  after: unknown
}


export interface CaseVersionDiffResult {
  caseId: number
  fromVersion: number
  toVersion: number
 
  truncated: boolean
  changes: CaseVersionChangeItem[]
}


export interface CaseRollbackResult {
  caseId: number
  rolledBackFrom: number
  newVersion: number
  version: number
}


export function listCaseVersions(id: number): Promise<CaseVersionItem[]> {
  return request<CaseVersionItem[]>({ url: `/cases/${id}/versions`, method: 'get' })
}


export function diffCaseVersion(id: number, version: number, baseVersion?: number): Promise<CaseVersionDiffResult> {
  return request<CaseVersionDiffResult>({
    url: `/cases/${id}/versions/${version}/diff`, method: 'get',
    params: baseVersion != null ? { baseVersion } : undefined,
  })
}


export function rollbackCaseVersion(id: number, version: number, expectedVersion: number): Promise<CaseRollbackResult> {
  return request<CaseRollbackResult>({
    url: `/cases/${id}/versions/${version}/rollback`, method: 'post',
    data: { version: expectedVersion },
  })
}


export function setCaseFlaky(id: number, isolated: boolean, reason?: string): Promise<CaseItem> {
  return request<CaseItem>({ url: `/cases/${id}/flaky`, method: 'put', data: { isolated, reason } })
}

/**
 * Read-only projection of a quarantined case whose re-probe ledger already reached
 * the green bar (admin CaseController GET /cases/flaky-proposals mirror of
 * com.sixbean.testpike.admin.cases.api.dto.FlakyRestoreProposalView). Carries no write
 * semantics: clearing the flaky mark still goes through setCaseFlaky above.
 */
export interface FlakyRestoreProposal {
  caseId: number
  caseNo: string
  caseTitle: string
  /** Consecutive green post-quarantine runs already verified by the ledger. */
  greenStreak: number
  /** Live value of the hot key re-read per call, so retuning shows up immediately. */
  greenThreshold: number
  proposedAt: string | null
}

/** Pending restore proposals of one project segment (empty array = nothing to confirm). */
export function listFlakyRestoreProposals(projectId: number): Promise<FlakyRestoreProposal[]> {
  return request<FlakyRestoreProposal[]>({ url: '/cases/flaky-proposals', method: 'get', params: { projectId } })
}


export function setCaseExcellent(id: number, excellent: boolean, version: number): Promise<CaseItem> {
  return request<CaseItem>({ url: `/cases/${id}/excellent`, method: 'put', data: { excellent, version } })
}

export function assignCase(id: number, assigneeId: number): Promise<AssignResult> {
  return request<AssignResult>({ url: `/cases/${id}/assign`, method: 'post', data: { assigneeId } })
}

export function batchAssign(data: { projectId: number; caseIds: number[]; assigneeId?: number }): Promise<BatchAssignResult> {
  return request<BatchAssignResult>({ url: '/cases/batch-assign', method: 'post', data })
}


export interface AutoAssignRow {
  userId: number
  name: string
  count: number
}

export interface AutoAssignResult {
  assignedCount: number
  byAssignee: AutoAssignRow[]
}


export function autoAssignCases(data: { projectId: number; caseIds?: number[]; preview: boolean }): Promise<AutoAssignResult> {
  return request<AutoAssignResult>({ url: '/cases/auto-assign', method: 'post', data })
}


export interface BatchEditFailureItem {
  caseId: number
  reason: string
}

export interface BatchEditResult {
  succeeded: number[]
  failures: BatchEditFailureItem[]
}

export function batchEdit(data: { projectId: number; caseIds: number[]; phase?: number | null; priority?: number | null; tagIds?: number[] | null }): Promise<BatchEditResult> {
  return request<BatchEditResult>({ url: '/cases/batch-edit', method: 'post', data })
}


export function getCaseScript(id: number): Promise<CaseScript | null> {
  return request<CaseScript | null>({ url: `/cases/${id}/script`, method: 'get' })
}


export function regenerateScript(id: number, force = false): Promise<ScriptRegenResult> {
  return request<ScriptRegenResult>({
    url: `/cases/${id}/script/regenerate`,
    method: 'post',
    params: force ? { force: true } : undefined,
  })
}


export interface ScriptVersionItem {
  id: number
  caseId: number
  scriptId: number
  
  caseVersion: number
  
  scriptVersion: number
  schemaVersion: string
  
  reason: string
  createdAt: string
 
  corrupted: boolean
}


export interface ScriptVersionDiffResult {
  caseId: number
  fromVersion: number
  toVersion: number
 
  corrupted: boolean
  corruptedMessage?: string | null
  changes: CaseVersionChangeItem[]
}


export function listScriptVersions(id: number): Promise<ScriptVersionItem[]> {
  return request<ScriptVersionItem[]>({ url: `/cases/${id}/scripts/versions`, method: 'get' })
}


export function diffScriptVersion(id: number, version: number, baseVersion?: number): Promise<ScriptVersionDiffResult> {
  return request<ScriptVersionDiffResult>({
    url: `/cases/${id}/scripts/versions/${version}/diff`, method: 'get',
    params: baseVersion != null ? { baseVersion } : undefined,
  })
}


export interface CaseFlowchartNode {
  
  id: string
  
  stepIndex: number
}


export interface CaseFlowchart {
 
  mermaid: string
  
  stepCount: number
  
  nodes: CaseFlowchartNode[]
}


export function getCaseFlowchart(id: number): Promise<CaseFlowchart> {
  return request<CaseFlowchart>({ url: `/cases/${id}/flowchart`, method: 'get' })
}


export function saveCaseScript(
  id: number,
  data: { schemaVersion: string; actionJson: string },
  expectedVersion: number,
): Promise<CaseScript> {
  return request<CaseScript>({ url: `/cases/${id}/script`, method: 'put', data, params: { expectedVersion } })
}


export function previewCaseFlowchart(id: number, actionJson: string): Promise<CaseFlowchart> {
  return request<CaseFlowchart>({ url: `/cases/${id}/flowchart/preview`, method: 'post', data: { actionJson } })
}


export function quickExecuteCase(id: number, envId?: number | null, browser?: string | null): Promise<BatchStartResult> {
  return request<BatchStartResult>({
    url: `/cases/${id}/quick-execute`,
    method: 'post',
    data: { envId: envId ?? null, browser: browser ?? null },
  })
}


export function cloneCase(id: number, targetProjectId?: number | null): Promise<CaseItem> {
  return request<CaseItem>({
    url: `/cases/${id}/clone`,
    method: 'post',
    data: { targetProjectId: targetProjectId ?? null },
  })
}


export interface ReviewLockInfo {
  caseId: number
  holderId: number
  holderName: string
  leaseRemainingMillis: number
}


export function acquireReviewLock(id: number): Promise<ReviewLockInfo> {
  return request<ReviewLockInfo>({ url: `/cases/${id}/review-lock`, method: 'post' })
}


export function heartbeatReviewLock(id: number): Promise<ReviewLockInfo> {
  return request<ReviewLockInfo>({ url: `/cases/${id}/review-lock/heartbeat`, method: 'put' })
}


export function releaseReviewLock(id: number): Promise<void> {
  return request<void>({ url: `/cases/${id}/review-lock`, method: 'delete' })
}


export interface SimilarityHit {
  
  caseId: number
  
  title: string
  
  similarity: number
  
  level: 'HINT' | 'STRONG'
  
  content: string
}


export function checkCaseSimilarity(data: { projectId: number; queryText: string; excludeCaseId?: number }): Promise<SimilarityHit[]> {
  return request<SimilarityHit[]>({ url: '/cases/similarity-check', method: 'post', data })
}


export const CASE_STATUS: Record<number, { label: string; type: 'info' | 'warning' | 'success' | 'danger' | 'primary' }> = {
  1: { get label() { return td('dict.case.status.pendingReview') }, type: 'info' },
  2: { get label() { return td('dict.case.status.reviewing') }, type: 'warning' },
  3: { get label() { return td('dict.case.status.approved') }, type: 'success' },
  4: { get label() { return td('dict.case.status.needChange') }, type: 'danger' },
  5: { get label() { return td('dict.case.status.rejected') }, type: 'danger' },
  6: { get label() { return td('dict.case.status.executing') }, type: 'primary' },
  7: { get label() { return td('dict.case.status.execFailed') }, type: 'danger' },
  8: { get label() { return td('dict.case.status.regressFailed') }, type: 'danger' },
  9: { get label() { return td('dict.case.status.archived') }, type: 'info' },
}

export const PRIORITY_LABEL: Record<number, string> = { 0: 'P0', 1: 'P1', 2: 'P2' }

export const PRIORITY_TYPE: Record<number, 'danger' | 'warning' | 'info'> = { 0: 'danger', 1: 'warning', 2: 'info' }

export const CONFIDENCE_LABEL: Record<number, string> = {
  get 1() { return td('dict.case.confidence.high') },
  get 2() { return td('dict.case.confidence.mid') },
  get 3() { return td('dict.case.confidence.low') },
}

export const CONFIDENCE_TYPE: Record<number, 'success' | 'warning' | 'danger'> = { 1: 'success', 2: 'warning', 3: 'danger' }

export const END_TYPE_LABEL: Record<string, string> = {
  get web() { return td('dict.case.endType.web') },
  get mobile() { return td('dict.case.endType.mobile') },
  get pos() { return td('dict.case.endType.pos') },
  get multi() { return td('dict.case.endType.multi') },
}


export const PHASE_LABEL: Record<number, string> = {
  get 0() { return td('dict.case.phase.unassigned') },
  get 1() { return td('dict.case.phase.smoke') },
  get 2() { return td('dict.case.phase.functional') },
  get 3() { return td('dict.case.phase.regression') },
  get 4() { return td('dict.case.phase.releaseVerify') },
}

/**
 * Provenance channel of a case, mirroring core CaseSourceFacet — the server is
 * the only judge, the filter, the badge and the export payload all read these
 * lists so no page ever re-derives where a case came from.
 */
export const CASE_FACET_VALUES = ['chat', 'off_agent', 'import', 'manual'] as const

/** tp_test_case.evidence_grade domain: 0 none, 1 off-site self-report,
 *  2 off-site attested evidence, 3 platform re-proof. */
export const EVIDENCE_GRADE_VALUES = [0, 1, 2, 3] as const

export const FACET_LABEL: Record<string, string> = {
  get chat() { return td('dict.case.facet.chat') },
  get off_agent() { return td('dict.case.facet.offAgent') },
  get import() { return td('dict.case.facet.import') },
  get manual() { return td('dict.case.facet.manual') },
}

export const FACET_TYPE: Record<string, 'primary' | 'warning' | 'info' | 'success'> = {
  chat: 'primary', off_agent: 'warning', import: 'info', manual: 'info',
}

export const EVIDENCE_GRADE_LABEL: Record<number, string> = {
  get 0() { return td('dict.case.evidence.none') },
  get 1() { return td('dict.case.evidence.selfReport') },
  get 2() { return td('dict.case.evidence.attested') },
  get 3() { return td('dict.case.evidence.platformReproof') },
}

export const EVIDENCE_GRADE_TYPE: Record<number, 'danger' | 'warning' | 'primary' | 'success'> = {
  0: 'danger', 1: 'warning', 2: 'primary', 3: 'success',
}

/**
 * The weak-evidence test, defined exactly once: grade 0/1 still wait for
 * platform proof, so such rows go to the head of the human review queue. Both
 * the red row rule and the row badge consume this — they cannot drift apart.
 */
export function isWeakEvidence(row: Pick<CaseItem, 'evidenceGrade'>): boolean {
  return (row.evidenceGrade ?? 0) < 2
}

export const PHASE_TYPE: Record<number, 'info' | 'danger' | 'primary' | 'warning' | 'success'> = {
  0: 'info', 1: 'danger', 2: 'primary', 3: 'warning', 4: 'success',
}

export const SOURCE_LABEL: Record<number, string> = {
  get 1() { return td('dict.case.source.ai') },
  get 2() { return td('dict.case.source.template') },
  get 3() { return td('dict.case.source.imported') },
  get 4() { return td('dict.case.source.clone') },
  get 6() { return td('dict.case.source.sandboxPromotion') },
}

export const REVIEW_ACTION_LABEL: Record<ReviewAction, string> = {
  get pass() { return td('dict.case.reviewAction.pass') },
  get need_change() { return td('dict.case.reviewAction.needChange') },
  get reject() { return td('dict.case.reviewAction.reject') },
}
