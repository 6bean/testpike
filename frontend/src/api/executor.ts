import { END_TYPE_LABEL } from './case'
import type { ClusterComponentRow } from './cluster'
import { td } from '../utils/dictLabel'
import { request, type PageResult } from './request'




export interface ExecutorInfo {
  id: number
  name: string
  groupIds?: string | null
  envIds?: string | null
  endTypes?: string | null
  browsers?: string | null
  
  capacity: number
  
  runningCnt: number
  
  status: number
  version?: string | null
  
  lastHeartbeat?: string | null
  
  capabilities?: Record<string, unknown> | null
}


export interface ExecTask {
  id: number
  projectId: number
  caseId: number
  executorId?: number | null
  
  status: number
  schemaVersion?: string | null
  
  errorType?: number | null
  
  errorDetail?: string | null
  
  aiFailureClass?: number | null
  
  aiFailureNote?: string | null
  
  stepsTimeline?: string | null
  
  screenshots?: string | null
  
  durationMs?: number | null
  
  log?: string | null
 
  resultExt?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  createdAt?: string | null
  /** Task kind badge (core single source: 1 case / 2 tool dry-run / 3 explore act / 4 api-test run / 5 pressure / 6 app auto) */
  taskType?: number
  /** Pinned pressure run of a taskType=5 row (badge deep-link target; null = no target) */
  perfRunId?: number | null
}


export interface ConsoleViolationItem {
  stepNo?: number
  
  level?: string
  text?: string
}


export interface NetHitItem {
  url?: string
  status?: number
  durationMs?: number | null
}


export interface ResultEvidence {
  consoleViolations: ConsoleViolationItem[]
  netHits: NetHitItem[]
  dbDigest: string[]
}


export interface AiLocateInfo {
  tier?: string
  score?: number
  badge?: string | null
  selfcheck?: string | null
}


export interface LocateEvidencePacket {
  stepNo?: number
  action?: string
  score?: number
  screenshotKey?: string | null
  annotated?: boolean
  candidates?: Array<Record<string, unknown>>
}


export interface StepTimelineItem {
  stepNo?: number
  action?: string
  startAt?: string
  endAt?: string
  /** ok / fail */
  status?: string
  detail?: string | null
  screenshotKey?: string | null
 
  tsStart?: number | null
  
  tsEnd?: number | null
  
  aiLocate?: AiLocateInfo | null
  
  locateEvidence?: LocateEvidencePacket | null
}


export interface TrialRunResult {
  taskId: number
}


export interface ExecTaskQuery {
  projectId: number
  page?: number
  size?: number
  /** whitelisted sort key forwarded to core ; empty = historical id DESC */
  sortBy?: string
  sortDir?: string
  /** narrow to one execution batch (AW2 batch-promotion picker) */
  batchId?: number
  /** comma separated status codes; core whitelists 1-6 and refuses anything else */
  statusIn?: string
}


export function trialRunCase(id: number, envId: number): Promise<TrialRunResult> {
  return request<TrialRunResult>({ url: `/cases/${id}/trial-run`, method: 'post', data: { envId } })
}


export function listExecutors(): Promise<ExecutorInfo[]> {
  return request<ExecutorInfo[]>({ url: '/executors', method: 'get' })
}


export function listExecTasks(params: ExecTaskQuery): Promise<PageResult<ExecTask>> {
  return request<PageResult<ExecTask>>({ url: '/executors/tasks', method: 'get', params })
}


export function getExecTask(taskId: number): Promise<ExecTask> {
  return request<ExecTask>({ url: `/executors/tasks/${taskId}`, method: 'get' })
}


export interface ScreenshotView {
  taskId: number
  objectKey: string
  url: string
}


export interface BatchCaseRerunResult {
  taskId: number
  created: boolean
  message: string
}


export function rerunBatchCase(taskId: number, engineCode?: string | null): Promise<BatchCaseRerunResult> {
  return request<BatchCaseRerunResult>({
    url: `/batch-cases/${taskId}/rerun`,
    method: 'post',
    params: engineCode ? { engineCode } : undefined
  })
}


export function findStepScreenshot(
  shots: ScreenshotView[], step: StepTimelineItem | null | undefined,
): ScreenshotView | null {
  if (!step?.screenshotKey || !shots.length) return null
  const base = (step.screenshotKey.split(/[\\/]/).pop() || '').trim()
  if (!base) return null
  return shots.find((s) => {
    const objBase = s.objectKey.split('/').pop() || ''
    return objBase === base || objBase.endsWith(`_${base}`)
  }) ?? shots.find((s) => (s.objectKey.split('/').pop() || '').includes(base)) ?? null
}


export interface ExecutorTaskPage {
  pending: number
  running: number
  done: number
  failed: number
  list: ExecTask[]
  total: number
}

/** Executor-dimension task query (statusGroup: all|pending|running|done|failed;
 * optional sortBy/sortDir whitelist pair — server-side order, blank = historical
 * id DESC). */
export interface ExecutorTaskQuery {
  statusGroup?: string
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
}


export function listExecutorTasks(executorId: number, params: ExecutorTaskQuery): Promise<ExecutorTaskPage> {
  return request<ExecutorTaskPage>({ url: `/executors/${executorId}/tasks`, method: 'get', params })
}


export function getExecTaskLog(taskId: number): Promise<{ log: string | null }> {
  return request<{ log: string | null }>({ url: `/executors/tasks/${taskId}/logs`, method: 'get' })
}

export function getExecTaskScreenshots(taskId: number): Promise<ScreenshotView[]> {
  return request<ScreenshotView[]>({ url: `/executors/tasks/${taskId}/screenshots`, method: 'get' })
}


export interface ExecStepSnapshot {
  taskId: number
  stepNo: number
  kind: string
  
  truncated: boolean
  
  rawBytes: number
  
  request: string | null
  
  response: string | null
  
  assertions: string | null
 
  tlsMode: string | null
}

export function getExecTaskStepSnapshots(taskId: number): Promise<ExecStepSnapshot[]> {
  return request<ExecStepSnapshot[]>({ url: `/executors/tasks/${taskId}/step-snapshots`, method: 'get' })
}


export interface ExecStepEvidence {
  taskId: number
  stepNo: number
  
  action: string | null
  screenshotKey: string | null
  screenshotUrl: string | null
  viewTreeKey: string | null
  viewTreeUrl: string | null
  logTailKey: string | null
  logTailUrl: string | null
 
  recordKey: string | null
  
  recordUrl: string | null
 
  netTimelineKey: string | null
  
  netTimelineUrl: string | null
 
  perfTimelineKey: string | null
  
  perfTimelineUrl: string | null
}


export function listStepEvidence(taskId: number): Promise<ExecStepEvidence[]> {
  return request<ExecStepEvidence[]>({ url: `/executors/tasks/${taskId}/step-evidence`, method: 'get' })
}


export interface CaseExecHistoryItem {
  id: number
  batchId?: number | null
  
  batchName?: string | null
  
  batchType?: number | null
  
  envVersion?: string | null
  
  status: number
  errorType?: number | null
  errorDetail?: string | null
  
  aiFailureClass?: number | null
  
  aiFailureNote?: string | null
  
  engineCode?: string | null
  durationMs?: number | null
  startedAt?: string | null
  finishedAt?: string | null
  screenshots: ScreenshotView[]
}


export function getCaseExecHistory(
  caseId: number,
  projectId: number,
  page = 1,
  size = 10,
): Promise<PageResult<CaseExecHistoryItem>> {
  return request<PageResult<CaseExecHistoryItem>>({
    url: `/executors/cases/${caseId}/history`,
    method: 'get',
    params: { projectId, page, size },
  })
}


export function parseStepsTimeline(task: ExecTask | null): StepTimelineItem[] {
  if (!task?.stepsTimeline) return []
  try {
    const arr = JSON.parse(task.stepsTimeline)
    return Array.isArray(arr) ? (arr as StepTimelineItem[]) : []
  } catch {
    return []
  }
}


export function parseScreenshots(task: ExecTask | null): string[] {
  if (!task?.screenshots) return []
  try {
    const arr = JSON.parse(task.screenshots)
    return Array.isArray(arr) ? arr.filter((x): x is string => typeof x === 'string') : []
  } catch {
    return []
  }
}


export function parseResultEvidence(task: ExecTask | null): ResultEvidence {
  const empty: ResultEvidence = { consoleViolations: [], netHits: [], dbDigest: [] }
  if (!task?.resultExt) return empty
  try {
    const obj = JSON.parse(task.resultExt) as Partial<ResultEvidence> | null
    if (!obj || typeof obj !== 'object') return empty
    return {
      consoleViolations: Array.isArray(obj.consoleViolations) ? obj.consoleViolations : [],
      netHits: Array.isArray(obj.netHits) ? obj.netHits : [],
      dbDigest: Array.isArray(obj.dbDigest)
        ? obj.dbDigest.filter((x): x is string => typeof x === 'string')
        : [],
    }
  } catch {
    return empty
  }
}


export interface RecordEventsEvidence {
  
  level: string
  events: Record<string, unknown>[]
  dropped: number
}


export function parseRecordEvents(task: ExecTask | null): RecordEventsEvidence | null {
  if (!task?.resultExt) return null
  try {
    const obj = JSON.parse(task.resultExt) as { recordEvents?: RecordEventsEvidence } | null
    const rec = obj && typeof obj === 'object' ? obj.recordEvents : null
    if (!rec || typeof rec !== 'object' || !Array.isArray(rec.events)) return null
    return {
      level: typeof rec.level === 'string' ? rec.level : '',
      events: rec.events.filter((x): x is Record<string, unknown> => !!x && typeof x === 'object'),
      dropped: typeof rec.dropped === 'number' ? rec.dropped : 0,
    }
  } catch {
    return null
  }
}


export const EXEC_TASK_STATUS: Record<number, { label: string; type: 'info' | 'warning' | 'success' | 'danger' | 'primary' }> = {
  1: { get label() { return td('dict.exec.task.pending') }, type: 'info' },
  2: { get label() { return td('dict.exec.task.executing') }, type: 'primary' },
  3: { get label() { return td('dict.exec.task.success') }, type: 'success' },
  4: { get label() { return td('dict.exec.task.fail') }, type: 'danger' },
  5: { get label() { return td('dict.exec.task.timeout') }, type: 'danger' },
  6: { get label() { return td('dict.exec.task.skipped') }, type: 'info' },
  7: { get label() { return td('dict.exec.task.canceled') }, type: 'warning' },
}


export const AI_FAILURE_CLASS: Record<number, { label: string; type: 'info' | 'warning' | 'success' | 'danger' | 'primary' }> = {
  1: { get label() { return td('dict.exec.aiclass.defect') }, type: 'danger' },
  2: { get label() { return td('dict.exec.aiclass.locator') }, type: 'warning' },
  3: { get label() { return td('dict.exec.aiclass.env') }, type: 'info' },
}


export interface RunningTaskInfo {
  taskId: number
  projectId: number
  caseTitle: string | null
  batchId: number | null
  executorId: number | null
  startedAt: string | null
  elapsedMs: number
  timeoutMs: number
}

/** Running-process page query (cross-batch executing task list).
 * optional sort pair — server-side order over the whole
 * running window, blank keeps the historical id ASC order. */
export interface RunningTaskSortQuery {
  sortBy?: string
  sortDir?: string
}

export function listRunningTasks(page = 1, size = 20, sort?: RunningTaskSortQuery): Promise<PageResult<RunningTaskInfo>> {
  return request<PageResult<RunningTaskInfo>>({ url: '/executors/running', method: 'get', params: { page, size, ...sort } })
}


export interface ProjectRunningInfo {
  projectId: number
  running: number
}

export interface ParallelWaterLevelInfo {
  globalRunning: number
  globalMax: number
  perProjectMax: number
  projects: ProjectRunningInfo[]
}


export function getParallelWaterLevel(): Promise<ParallelWaterLevelInfo> {
  return request<ParallelWaterLevelInfo>({ url: '/executors/parallel-water-level', method: 'get' })
}


export interface PoolProjectInfo {
  projectId: number
  projectName: string
  
  quotaPct: number | null
  
  slotCap: number
  
  runningInPool: number
  
  pendingCount: number
}


export interface PoolInfo {
  poolId: number
  poolName: string
  projectId: number
  
  memberCount: number
  onlineCount: number
  
  capacitySum: number
  runningTaskCount: number
  pendingTaskCount: number
  projects: PoolProjectInfo[]
}


export function listExecutorPools(): Promise<PoolInfo[]> {
  return request<PoolInfo[]>({ url: '/executors/pools', method: 'get' })
}


export interface ExecutorSelfCheckInfo {
  executorId: number
  name: string
  version: string | null
  
  status: number
  
  statusText: string | null
  lastHeartbeat: string | null
  
  capabilitiesReported: boolean
  hostOs: string | null
  
  tools: Record<string, unknown> | null
  
  missingTools: string[] | null
  runtimes: Record<string, unknown> | null
  
  engines: string[] | null
  platforms: string[] | null
  deviceKinds: string[] | null
  
  deviceCount: number | null
  components: ClusterComponentRow[] | null
}


export function getExecutorSelfCheck(executorId: number): Promise<ExecutorSelfCheckInfo> {
  return request<ExecutorSelfCheckInfo>({ url: `/executors/${executorId}/self-check`, method: 'get' })
}


export interface CancelTaskResult {
  ok: boolean
  
  status: number
}


export function cancelExecTask(taskId: number): Promise<CancelTaskResult> {
  return request<CancelTaskResult>({ url: `/executors/tasks/${taskId}/cancel`, method: 'post' })
}


export interface BatchCancelResult {
  cancelled: number
  rejectedTaskIds: number[]
}


export function batchCancelExecTasks(taskIds: number[]): Promise<BatchCancelResult> {
  return request<BatchCancelResult>({ url: '/executors/tasks/batch-cancel', method: 'post', data: taskIds })
}


export function getTaskMonitorFrames(taskId: number): Promise<unknown> {
  return request<unknown>({ url: `/executors/tasks/${taskId}/monitor-frames`, method: 'get' })
}


export const EXECUTOR_STATUS: Record<number, { label: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  1: { get label() { return td('dict.exec.node.online') }, type: 'success' },
  2: { get label() { return td('dict.exec.node.upgrading') }, type: 'warning' },
  3: { get label() { return td('dict.exec.node.offline') }, type: 'danger' },
}

/** Task-kind vocabulary (single source mirroring the core ticket column
 * task_type: 1 case / 2 tool dry-run / 3 explore action / 4 api-test run /
 * 5 pressure / 6 app automation). No second mapping anywhere. */
export const EXEC_TASK_TYPE: Record<number, { label: string; type: 'info' | 'warning' | 'success' | 'danger' | 'primary' }> = {
  1: { get label() { return td('dict.exec.taskType.case') }, type: 'info' },
  2: { get label() { return td('dict.exec.taskType.toolDryRun') }, type: 'primary' },
  3: { get label() { return td('dict.exec.taskType.exploreAct') }, type: 'primary' },
  4: { get label() { return td('dict.exec.taskType.apiRun') }, type: 'success' },
  5: { get label() { return td('dict.exec.taskType.perfRun') }, type: 'warning' },
  6: { get label() { return td('dict.exec.taskType.appAuto') }, type: 'info' },
}


export const ERROR_TYPE_LABEL: Record<number, string> = {
  get 1() { return td('dict.exec.error.product') },
  get 2() { return td('dict.exec.error.locator') },
  get 3() { return td('dict.exec.error.env') },
  get 4() { return td('dict.exec.error.dependency') },
}


export function endTypesLabel(raw?: string | null): string {
  if (!raw) return '-'
  return raw
    .split(',')
    .map((x) => x.trim())
    .filter(Boolean)
    .map((x) => END_TYPE_LABEL[x] ?? x)
    .join(' / ')
}
