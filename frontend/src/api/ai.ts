import { ht, td } from '../utils/dictLabel'
import type { AiBatchItemPayload } from '../utils/aiBatchLinkage'
import { SCENARIO_PREVIEW_REQUEST_TIMEOUT_MS } from '../utils/aiScenarioSelection'
import { request } from './request'




export interface AiGeneratePayload {
  projectId: number
  requirement: string
  requirementId?: number
}


export interface AiTask {
  id: number
  projectId: number
  operatorId: number
  
  taskType: number
  /** 1=pending 2=running 3=done 4=failed 5=cancelled (stopped by a human) */
  status: number
  requirementText: string
  requirementId?: number | null
  
  caseId?: number | null
  scriptId?: number | null
  
  errorCode?: string | null
  
  errorMsg?: string | null
  retryCnt: number
  createdAt?: string
  updatedAt?: string
}


export const AI_TASK_STATUS: Record<number, { label: string; type: 'info' | 'warning' | 'success' | 'danger' }> = {
  1: { get label() { return td('dict.ai.task.queued') }, type: 'info' },
  2: { get label() { return td('dict.ai.task.generating') }, type: 'warning' },
  3: { get label() { return td('dict.ai.task.done') }, type: 'success' },
  4: { get label() { return td('dict.ai.task.failed') }, type: 'danger' },
  // Status 5 = stopped by a human through the cancel door; it is a settled
  // state, so the cards need a word here instead of the raw number.
  5: { get label() { return td('dict.ai.task.cancelled') }, type: 'info' },
}

export const AI_TASK_TYPE_LABEL: Record<number, string> = {
  get 1() { return td('dict.ai.type.caseGen') },
  get 2() { return td('dict.ai.type.scriptGen') },
}


export function generateCases(data: AiGeneratePayload, idempotencyKey?: string): Promise<{ taskId: number }> {
  return request<{ taskId: number }>({
    url: '/ai/generate',
    method: 'post',
    data,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
}


export function getAiTask(taskId: number): Promise<AiTask> {
  return request<AiTask>({ url: `/ai/tasks/${taskId}`, method: 'get' })
}


export function retryAiTask(taskId: number): Promise<void> {
  return request<void>({ url: `/ai/tasks/${taskId}/retry`, method: 'post' })
}



export interface AiBatchGeneratePayload {
  projectId: number
  /** legacy plain-text mirror (kept one compatibility cycle; items win server-side) */
  requirements: string[]
  /** structured entries carrying the optional requirement-point link */
  items?: AiBatchItemPayload[]
  /** stage0 preview group id (with a preview ledger the estimate multiplies
   *  lines x scenarios x ring rounds; after circle-selection the expanded
   *  submit omits it so the count is the real task count, no double multiply) */
  previewBatchId?: number | null
}


export interface AiBatchEstimate {
  count: number
  estimatedTokens: number
  estimatedCostYuan: number
  maxCount: number
  /** total scenarios of the stage0 ledger (null = legacy shape, zero drift) */
  scenarioCount?: number | null
  /** deliberation rounds multiplied into this estimate (K8 ai.kernel.deliberation-rounds, read live) */
  deliberationRounds?: number | null
}

// ---------- A2 stage0 scenario preview (draft enumeration before circle-selection; never writes cases) ----------

/** One enumerated scenario (hyphen kind codes are owned by the enumeration service; the UI mirrors them). */
export interface ScenarioPreviewItemView {
  kind: string
  title: string
  anchor?: string | null
  note?: string | null
}

/** Preview row read back for the circle-selection step (mirror of tp_ai_scenario_preview). */
export interface ScenarioPreviewView {
  id: number
  batchId?: number | null
  seq: number
  projectId: number
  requirementText: string
  /** 0=P0 1=P1 2=P2 */
  priority: number
  expansionEnabled: boolean
  scenarios: ScenarioPreviewItemView[]
  residualClaims: string[]
  roundsUsed: number
  modelCalls: number
  converged: boolean
}

/**
 * One stage0 enumeration row (POST /ai/batch/scenario-preview; returns the
 * row id, honest 0 when the switch is off). Each row can legitimately run
 * for tens of seconds server-side, so this call carries its own long read
 * budget (SCENARIO_PREVIEW_REQUEST_TIMEOUT_MS) instead of the global 30s
 * axios default — scoped here only, every other endpoint keeps it.
 * The budget is enforced twice on purpose: axios asks the transport to give
 * up, and the timer below rejects. The axios timeout alone is not enough —
 * XHR timeouts stop counting once response headers arrive, so a row whose
 * body stalls mid-stream never trips it and one hung row freezes the whole
 * plan. Rejecting also aborts the signal, which releases the socket; the
 * rejection lands as that row's entry in enumeratePreviewPlan so the other
 * rows keep enumerating, and a row that reaches the ledger late is still
 * picked up by the read-back of the server list.
 */
export function runScenarioPreview(data: {
  batchId?: number | null
  seq: number
  projectId: number
  requirementText: string
  priority: number
}): Promise<number> {
  const controller = new AbortController()
  let deadline: ReturnType<typeof setTimeout> | undefined
  return new Promise<number>((resolve, reject) => {
    deadline = setTimeout(() => {
      controller.abort()
      reject(new Error(ht('http.timeout')))
    }, SCENARIO_PREVIEW_REQUEST_TIMEOUT_MS)
    request<number>({
      url: '/ai/batch/scenario-preview',
      method: 'post',
      data,
      signal: controller.signal,
      timeout: SCENARIO_PREVIEW_REQUEST_TIMEOUT_MS,
    }).then(resolve, reject)
  }).finally(() => clearTimeout(deadline))
}

/** Read a preview group back for circle-selection (GET /ai/batch/scenario-previews?batchId=; empty list returns empty). */
export function listScenarioPreviews(batchId: number): Promise<ScenarioPreviewView[]> {
  return request<ScenarioPreviewView[]>({ url: '/ai/batch/scenario-previews', method: 'get', params: { batchId } })
}


export function getAiBatchLimit(): Promise<{ maxCount: number; autoEnumerate?: number }> {
  return request<{ maxCount: number; autoEnumerate?: number }>({ url: '/ai/batch/limit', method: 'get' })
}


export interface AiBatchItem {
  taskId: number
  /** 1=pending 2=running 3=done 4=failed 5=cancelled (stopped by a human) */
  status: number
  requirementText: string
  
  caseId?: number | null
  errorCode?: string | null
  errorMsg?: string | null
}


export interface AiBatch {
  id: number
  projectId: number
  operatorId: number
  total: number
  pending: number
  running: number
  done: number
  failed: number
  createdAt?: string
  /** Stopped-by-a-human bucket, counted separately from failed (cancel door ). */
  cancelled: number
  items: AiBatchItem[]
  /** Server-assembled waiting notice (reason / current backlog / recovery
   *  estimate); null while there is nothing worth telling. Rendered verbatim. */
  queueNotice?: string | null
}

/** Stop a live generation task (POST /ai/tasks/{taskId}/cancel). Queued/running rows settle as
 *  cancelled(5); settled rows are refused with a message naming the current state, repeated
 *  stops stay idempotent, and every later writeback of that run is rejected — the stop
 *  semantic itself lives in core (single source). */
export function cancelAiTask(taskId: number): Promise<void> {
  return request<void>({ url: `/ai/tasks/${taskId}/cancel`, method: 'post' })
}


export function estimateBatchGenerate(data: AiBatchGeneratePayload): Promise<AiBatchEstimate> {
  return request<AiBatchEstimate>({ url: '/ai/batch/estimate', method: 'post', data })
}


export function batchGenerateCases(data: AiBatchGeneratePayload, idempotencyKey?: string): Promise<{ batchId: number }> {
  return request<{ batchId: number }>({
    url: '/ai/batch-generate',
    method: 'post',
    data,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
}


export function getAiBatch(batchId: number): Promise<AiBatch> {
  return request<AiBatch>({ url: `/ai/batches/${batchId}`, method: 'get' })
}



export interface AiGenerationLatest {
  batch: AiBatch | null
  task: AiTask | null
}


export interface AiRequirementLive {
  requirementId: number
  taskId: number
  
  batchId: number | null
  
  status: number
}


export function getLatestGeneration(projectId: number): Promise<AiGenerationLatest> {
  return request<AiGenerationLatest>({ url: '/ai/generation/latest', method: 'get', params: { projectId } })
}


export function listLiveRequirementGenerations(projectId: number, ids: number[]): Promise<AiRequirementLive[]> {
  if (ids.length === 0) return Promise.resolve([])
  return request<AiRequirementLive[]>({
    url: '/ai/generation/live-requirements',
    method: 'get',
    params: { projectId, ids: ids.join(',') },
  })
}
