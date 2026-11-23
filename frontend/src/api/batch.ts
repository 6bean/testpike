import { request } from './request'
import type { MobilePerfRollup } from '@/utils/mobilePerfDisplay'
import type { AiTaskRunRow } from './taskEvents'
import type { TestRunEvent } from '@/composables/useTestRunTail'
import { BATCH_TERMINAL_PREFIX } from '@/utils/serverTextWire'




export interface BatchView {
  id: number
  projectId: number
  batchNo: string
  name: string
  envId: number | null
  envName: string
  status: number
  statusLabel: string
  
  type: number
  templateId: number | null
  caseCount: number
  queuePos: number | null
  createdBy: number | null
  createdAt: string | null
  startedAt: string | null
  finishedAt: string | null
  
  message: string | null
 
  notifyFeishu?: number
 
  poolTagId?: number | null
  
  browser?: string | null
  
  runtimeChangeCount?: number
 
  cleanAppData?: number
}


export interface BatchCaseView {
  id: number
  batchId: number
  caseId: number
  caseNo: string
  title: string
  endType: string
  caseVersion: number
  sortIndex: number
  result: number
  resultLabel: string
  manualSkip: number
  flaky: number
}


export interface BatchTemplateView {
  id: number
  projectId: number
  name: string
  caseIds: number[]
  envPolicy: string | null
  createdBy: number | null
  createdAt: string | null
  
  cronExpr: string | null
  
  enabled: number
  
  scheduleEnvId: number | null
 
  cleanAppData?: number
}

export interface BatchCreatePayload {
  projectId: number
  name: string
  envId?: number | null
  caseIds: number[]
 
  notifyFeishu?: number | null
 
  poolTagId?: number | null
  
  browser?: string | null
 
  cleanAppData?: number | null
}


export interface BatchUpdatePayload {
  name?: string | null
  envId?: number | null
  addCaseIds?: number[] | null
  removeCaseIds?: number[] | null
  
  browser?: string | null
 
  cleanAppData?: number | null
}


export interface BatchSkipItem {
  caseId: number
  caseNo: string
  title: string
  status: number
  
  reason: string | null
}


export interface BatchStartResult {
  batchId: number
  status: number
  
  statusKey: string
  statusLabel: string
  queuePos: number | null
  estimatedStartAt: string | null
  skipCases: BatchSkipItem[]
  message: string | null
}


export function listBatches(params: {
  projectId: number
  status?: number | null
  keyword?: string
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
  /** Optional program lens id : when set, the server conjuncts it
 * with projectId (three-branch ProgramLensScope path in core). Omitted =
 * legacy request shape, zero drift for users without a program lens. */
  programId?: number | null
}): Promise<{ list: BatchView[]; total: number }> {
  return request({ url: '/batches', method: 'get', params })
}


export function createBatch(payload: BatchCreatePayload): Promise<BatchView> {
  return request({ url: '/batches', method: 'post', data: payload })
}


export function getBatch(id: number): Promise<BatchView> {
  return request({ url: `/batches/${id}`, method: 'get' })
}


export function updateBatch(id: number, payload: BatchUpdatePayload): Promise<BatchView> {
  return request({ url: `/batches/${id}`, method: 'put', data: payload })
}


export function listBatchCases(id: number): Promise<BatchCaseView[]> {
  return request({ url: `/batches/${id}/cases`, method: 'get' })
}


export function saveBatchAsTemplate(id: number, templateName: string): Promise<BatchTemplateView> {
  return request({ url: `/batches/${id}/save-as-template`, method: 'post', data: { templateName } })
}


export function listBatchTemplates(projectId: number): Promise<BatchTemplateView[]> {
  return request({ url: '/batches/templates', method: 'get', params: { projectId } })
}


export function createBatchFromTemplate(payload: {
  templateId: number
  name?: string | null
  envId: number
}): Promise<BatchView> {
  return request({ url: '/batches/from-template', method: 'post', data: payload })
}


export function deleteBatchTemplate(templateId: number): Promise<void> {
  return request({ url: `/batches/templates/${templateId}`, method: 'delete' })
}


export function updateTemplateSchedule(templateId: number, payload: {
  cronExpr: string | null
  enabled: number
  scheduleEnvId: number | null
}): Promise<BatchTemplateView> {
  return request({ url: `/batches/templates/${templateId}/schedule`, method: 'put', data: payload })
}


export function startBatch(id: number): Promise<BatchStartResult> {
  return request({ url: `/batches/${id}/start`, method: 'post' })
}


export function pauseBatch(id: number): Promise<BatchView> {
  return request({ url: `/batches/${id}/pause`, method: 'post' })
}


export function resumeBatch(id: number): Promise<BatchView> {
  return request({ url: `/batches/${id}/resume`, method: 'post' })
}


export function cancelBatch(id: number): Promise<BatchView> {
  return request({ url: `/batches/${id}/cancel`, method: 'post' })
}


export interface BatchRecoveryResult {
  batchId: number
  batchStatus: number
  batchStatusLabel: string
  recoveredCount: number
  sweepReclaimedCount: number
  runningCount: number
  terminalCount: number
  pendingCount: number
  message: string
}


export function recoverBatch(id: number): Promise<BatchRecoveryResult> {
  return request({ url: `/batches/${id}/recover`, method: 'post' })
}


export interface BatchReportResult {
  batchId: number
  batchStatus: number
  batchStatusLabel: string
  total: number
  passCount: number
  failCount: number
  skipCount: number
  timeoutCount: number
  
  passRate: number | null
  
  failDist: Record<string, number>
  
  attr: Record<string, number>
  durationMs: number | null
  envVersionSnapshot: string | null
  /** Tasks served under mocks (V238 snapshot column straight from core; 0 = plain
   *  batch. Read-only mirror — neither the bridge nor the front end recomputes it) */
  mockTaskCount: number
  /** Tasks answered by the paid LLM fallback rung (source=llm provenance snapshot;
   *  a subset of mockTaskCount. Read-only mirror — zero recomputation on the bridge) */
  llmTaskCount: number
  /** Batch mobile sampling rollup (V486 snapshot column, read verbatim by core and
   *  forwarded as-is: median per metric with task count, plus prevMedian/delta from
   *  finalization — the display layer formats, never recomputes). null = the batch
   *  reported no mobile metric; the page renders no performance block for it. */
  mobilePerf?: MobilePerfRollup | null
}


export function getBatchReport(id: number): Promise<BatchReportResult> {
  return request({ url: `/batches/${id}/report`, method: 'get' })
}

/**
 * Batch failure-insight card ( AO-7 stage 3 read path; admin
 * BatchInsightView mirror — clusters arrive ranked, nothing is recomputed
 * here). null data = honest absence (all-pass batch, insight gate off, or
 * clustering still pending): the report page hides the card entirely.
 */
export interface BatchInsightResult {
  batchId: number
  failCount: number
  unattributedCount: number
  /** Ranked groups, each entry {class, label, count} (class=attribution code) */
  clusters: Array<{ class?: string; label?: string; count?: number }>
  summary: string | null
}

export function getBatchInsight(id: number): Promise<BatchInsightResult | null> {
  return request({ url: `/batches/${id}/insight`, method: 'get' })
}


export function skipBatchCase(id: number): Promise<string> {
  return request({ url: `/batches/${id}/skip-case`, method: 'post' })
}


export function rerunFailedBatch(id: number): Promise<number> {
  return request({ url: `/batches/${id}/rerun-failed`, method: 'post' })
}


export function batchStatusTag(status: number): 'info' | 'primary' | 'warning' | 'success' | 'danger' {
  const map = ['', 'info', 'primary', 'warning', 'warning', 'success', 'success', 'danger', 'info']
  return (map[status] || 'info') as 'info' | 'primary' | 'warning' | 'success' | 'danger'
}


export function batchResultTag(result: number): 'success' | 'danger' | 'warning' | 'info' {
  const map = ['', 'success', 'danger', 'warning', 'info']
  return (map[result] || 'info') as 'success' | 'danger' | 'warning' | 'info'
}

/**
 * R62 B-face: cursor window over one batch execution's process-event ledger
 * (admin door GET /api/v1/batches/{id}/process-events). Rows share the ledger
 * shape consumed by useTestRunTail (cursor/level/summary/detail/landedAt) —
 * same strictly increasing replay contract as the explore tail door, one
 * consumer of the shared base, no second window implementation (R40).
 */
export async function fetchBatchProcessEvents(
  batchId: number,
  cursor: number,
  limit?: number,
): Promise<TestRunEvent[]> {
  const rows = await request<TestRunEvent[]>({
    url: `/batches/${batchId}/process-events`,
    method: 'get',
    params: limit === undefined ? { cursor } : { cursor, limit },
  })
  return Array.isArray(rows) ? rows : []
}

/**
 * Run-history revisit door (R67, admin bridge
 * `GET /api/v1/batches/{id}/process-runs`): aggregated per-run rows newest
 * first, riding the shared AiTaskRunRow shape used by every revisit list
 * (R40). Lets the detail page tell "frames aged out of retention" apart
 * from "this run produced nothing" when the cursor window comes back empty.
 */
export async function fetchBatchProcessRuns(
  batchId: number,
  limit?: number,
): Promise<AiTaskRunRow[]> {
  const rows = await request<AiTaskRunRow[]>({
    url: `/batches/${batchId}/process-runs`,
    method: 'get',
    params: limit === undefined ? {} : { limit },
  })
  return Array.isArray(rows) ? rows : []
}

/**
 * Terminal-frame prefix of the batch tail — single source is the server
 * writer side (BatchFinalizeSupport: sealed frame on finalize, cancelled
 * frame on the cancel door, watchdog frame on the timeout sweep). The
 * contract test greps that Java source so a writer-side wording drift fails
 * here instead of silently keeping the tail window open.
 */
export { BATCH_TERMINAL_PREFIX }

export function isBatchTerminalRow(row: { summary: string }): boolean {
  return row.summary.startsWith(BATCH_TERMINAL_PREFIX)
}
