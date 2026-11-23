import { request, postMultipart } from './request'
import { td } from '../utils/dictLabel'
import type { TakeoverCommand } from '../utils/takeoverInput'




export interface AppautoDevice {
  id: number
  
  projectId: number | null
  name: string
  serial: string
  
  deviceType: string
  adbAddress?: string | null
  model?: string | null
  osVersion?: string | null
  resolution?: string | null
  dpi?: number | null
  labels: string[]
  
  healthState: string
  
  consecutiveFailures: number
  lastSeenAt?: string | null
  
  lastOverheatAt?: string | null
 
  platform?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}


export interface AppautoLeaseRun {
  id: number
  deviceId: number
  projectId: number
  batchId?: number | null
  holderExecutorId?: string | null
  
  state: string
  acquiredAt?: string | null
  
  expiresAt?: string | null
  releasedAt?: string | null
  
  releaseReason?: string | null
}


export interface DeviceSavePayload {
  projectId: number | null
  name: string
  serial: string
  deviceType: string
  adbAddress?: string | null
  model?: string | null
  osVersion?: string | null
  resolution?: string | null
  dpi?: number | null
  labels: string[]
}


export interface AppautoEngine {
  id: number
  code: string
  name: string
  platformCodes: string[]
  deviceKindCodes: string[]
  defaultPriority: number
  runtimeType: string
  runtimeVersionReq: Record<string, string>
  capabilities: Record<string, boolean>
  driverRequirements: string[]
  envCheckSpec: Record<string, string>[]
  
  status: number
  installDocUrl?: string | null
  license?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}


export interface AppautoPlatform {
  id: number
  code: string
  name: string
  deviceKindCodes: string[]
  
  carrierTypes: string[]
  
  note?: string | null
  status: number
  createdAt?: string | null
  updatedAt?: string | null
}


export interface AppautoEngineAvailability {
  code: string
  
  available: boolean
  
  onlineCount: number
  
  missingReasons: string[]
}


export function listAppautoEngineAvailability(includeDisabled = false): Promise<AppautoEngineAvailability[]> {
  return request<AppautoEngineAvailability[]>({ url: '/appauto/engines/availability', method: 'get', params: { includeDisabled } })
}


export interface AppautoEngineHealth {
  code: string
  name: string
  
  enabled: boolean
  
  onlineExecutors: number
  
  readyExecutors: number
  
  blockingItems: string[]
  
  aiDoctorEnabled: boolean
}


export function listAppautoEngineHealth(includeDisabled = false): Promise<AppautoEngineHealth[]> {
  return request<AppautoEngineHealth[]>({ url: '/appauto/engines/health', method: 'get', params: { includeDisabled } })
}


export interface EngineManagePayload {
  name: string
  defaultPriority: number
  installDocUrl?: string | null
  status: number
}


export interface PlatformManagePayload {
  note?: string | null
  status: number
}


export function listAppautoDevices(projectId: number, label?: string): Promise<AppautoDevice[]> {
  return request<AppautoDevice[]>({ url: '/appauto/devices', method: 'get', params: { projectId, label } })
}


export function registerAppautoDevice(data: DeviceSavePayload): Promise<number> {
  return request<number>({ url: '/appauto/devices', method: 'post', data })
}


export function findAppautoDevice(id: number): Promise<AppautoDevice> {
  return request<AppautoDevice>({ url: `/appauto/devices/${id}`, method: 'get' })
}


export function updateAppautoDevice(id: number, data: DeviceSavePayload): Promise<void> {
  return request<void>({ url: `/appauto/devices/${id}`, method: 'put', data })
}


export function unquarantineAppautoDevice(id: number): Promise<void> {
  return request<void>({ url: `/appauto/devices/${id}/unquarantine`, method: 'post' })
}


export function deleteAppautoDevice(id: number): Promise<void> {
  return request<void>({ url: `/appauto/devices/${id}`, method: 'delete' })
}


export function listDeviceLeaseRuns(deviceId: number): Promise<AppautoLeaseRun[]> {
  return request<AppautoLeaseRun[]>({ url: `/appauto/devices/${deviceId}/lease-runs`, method: 'get' })
}

/**
 * Device-wall live thumbnail cell (admin bridge read face). State semantics
 * mirror the core view single-source: LIVE = downscaled JPEG base64 of the
 * running task's newest monitor frame; IDLE = no running task addresses the
 * device; NO_FRAME = task running but the live ring is still empty. Non-live
 * cells carry the plain-language reason; the wall renders it as-is.
 */
export interface AppautoDeviceThumbnail {
  deviceId: number
  taskId: number | null
  state: string
  reason: string | null
  imageBase64: string | null
  imageWidth: number | null
  imageHeight: number | null
  sourceWidth: number | null
  sourceHeight: number | null
}

export function fetchDeviceThumbnail(deviceId: number): Promise<AppautoDeviceThumbnail> {
  return request<AppautoDeviceThumbnail>({ url: `/appauto/devices/${deviceId}/thumbnail`, method: 'get' })
}


export function listAppautoEngines(includeDisabled = false): Promise<AppautoEngine[]> {
  return request<AppautoEngine[]>({ url: '/appauto/engines', method: 'get', params: { includeDisabled } })
}


export function findAppautoEngine(code: string): Promise<AppautoEngine> {
  return request<AppautoEngine>({ url: `/appauto/engines/${code}`, method: 'get' })
}


export function updateAppautoEngine(code: string, data: EngineManagePayload): Promise<void> {
  return request<void>({ url: `/appauto/engines/${code}`, method: 'put', data })
}


export function listAppautoPlatforms(includeDisabled = false): Promise<AppautoPlatform[]> {
  return request<AppautoPlatform[]>({ url: '/appauto/platforms', method: 'get', params: { includeDisabled } })
}


export function updateAppautoPlatform(code: string, data: PlatformManagePayload): Promise<void> {
  return request<void>({ url: `/appauto/platforms/${code}`, method: 'put', data })
}


export interface AppautoEnvProfile {
  id: number
  projectId: number
  name: string
  
  profileKind: string
  payload: Record<string, unknown>
  remark?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}


export function listAppautoEnvProfiles(projectId: number, profileKind?: string): Promise<AppautoEnvProfile[]> {
  return request<AppautoEnvProfile[]>({ url: '/appauto/env-profiles', method: 'get', params: { projectId, profileKind } })
}


export function enabledCapabilityKeys(e: AppautoEngine): string[] {
  return Object.entries(e.capabilities || {}).filter(([, v]) => v).map(([k]) => k)
}


export function disabledCapabilityKeys(e: AppautoEngine): string[] {
  return Object.entries(e.capabilities || {}).filter(([, v]) => !v).map(([k]) => k)
}


export const DEVICE_TYPES = ['EMULATOR', 'ADB', 'USB']


export const HEALTH_STATE_LABEL: Record<string, string> = {
  get GREEN() { return td('dict.appauto.health.green') },
  get YELLOW() { return td('dict.appauto.health.yellow') },
  get RED() { return td('dict.appauto.health.red') },
  get QUARANTINED() { return td('dict.appauto.health.quarantined') },
  get UNKNOWN() { return td('dict.appauto.health.unknown') },
}


export const LEASE_STATE_LABEL: Record<string, string> = {
  get ACTIVE() { return td('dict.appauto.lease.active') },
  get RELEASED() { return td('dict.appauto.lease.released') },
  get EXPIRED() { return td('dict.appauto.lease.expired') },
}


export interface AppautoStepSuggestion {
  id: number
  projectId?: number | null
  caseId?: number | null
  sourceTaskId?: number | null
  
  stepNo: number
  failureClass: number
  anchor: string
  source: string
  
  proposedAction: string
  
  proposedTarget: string
  
  reason?: string | null
  x?: number | null
  y?: number | null
  status: number
  adoptedScriptVersion?: number | null
  createdAt?: string | null
}


export function listStepSuggestions(
  projectId: number,
  caseId: number,
  status?: number,
  limit = 100,
): Promise<AppautoStepSuggestion[]> {
  return request<AppautoStepSuggestion[]>({
    url: '/appauto/step-suggestions',
    method: 'get',
    params: { projectId, caseId, status, limit },
  })
}


export function findStepSuggestion(id: number): Promise<AppautoStepSuggestion> {
  return request<AppautoStepSuggestion>({ url: `/appauto/step-suggestions/${id}`, method: 'get' })
}


export function adoptStepSuggestion(id: number, expectedScriptVersion?: number): Promise<AppautoStepSuggestion> {
  return request<AppautoStepSuggestion>({
    url: `/appauto/step-suggestions/${id}/adopt`,
    method: 'post',
    params: { expectedScriptVersion },
  })
}


export function rejectStepSuggestion(id: number): Promise<void> {
  return request<void>({ url: `/appauto/step-suggestions/${id}/reject`, method: 'post' })
}


export const SUGGESTION_STATE_LABEL: Record<string, string> = {
  get '1'() { return td('dict.appauto.suggestion.status.pending') },
  get '2'() { return td('dict.appauto.suggestion.status.adopted') },
  get '3'() { return td('dict.appauto.suggestion.status.rejected') },
}


export const SUGGESTION_SOURCE_LABEL: Record<string, string> = {
  get vision() { return td('dict.appauto.suggestion.source.vision') },
  get a11y() { return td('dict.appauto.suggestion.source.a11y') },
  get cache() { return td('dict.appauto.suggestion.source.cache') },
  get manual() { return td('dict.appauto.suggestion.source.manual') },
}


export interface AiSample {
  sampleId: number
  projectId?: number | null
  caseId?: number | null
  deviceId?: number | null
  stepNo?: number | null
  anchor: string
  hitSource: string
  reviewResult: string
  x?: number | null
  y?: number | null
  sourceTaskId?: number | null
  createdAt?: string | null
}


export interface AiSampleHitRate {
  total: number
  confirmed: number
  rejected: number
  confirmRate: number
}


export function listAiSamples(
  projectId: number,
  caseId: number,
  limit = 100,
): Promise<AiSample[]> {
  return request<AiSample[]>({
    url: '/appauto/ai-samples',
    method: 'get',
    params: { projectId, caseId, limit },
  })
}


export function getAiSampleHitRate(projectId: number, caseId: number): Promise<AiSampleHitRate> {
  return request<AiSampleHitRate>({
    url: '/appauto/ai-samples/hit-rate',
    method: 'get',
    params: { projectId, caseId },
  })
}


export const SAMPLE_REVIEW_LABEL: Record<string, string> = {
  get confirmed() { return td('dict.appauto.sample.review.confirmed') },
  get rejected() { return td('dict.appauto.sample.review.rejected') },
}


export interface NlStepView {
  steps: Record<string, unknown>[]
}


export function generateNlStep(
  projectId: number,
  engineCode: string,
  description: string,
): Promise<NlStepView> {
  return request<NlStepView>({
    url: '/appauto/nl-step',
    method: 'post',
    data: { projectId, engineCode, description },
  })
}


export interface RecordFlowView {
  draftId: number
  stepCount: number
}


export interface RecordDraftView {
  id: number
  projectId?: number | null
  caseId?: number | null
  sourceTaskId?: number | null
  title?: string | null
  
  eventSource?: string | null
  items?: string | null
  status: number
  
  scriptVersion?: number | null
  createdAt?: string | null
}


export interface RecordFlowGeneratePayload {
  projectId: number
  taskId: number
  engineCode: string
  eventSource: string
  title?: string
  
  events: Record<string, unknown>[]
}


export function generateRecordFlow(payload: RecordFlowGeneratePayload): Promise<RecordFlowView> {
  return request<RecordFlowView>({
    url: '/appauto/record-flow',
    method: 'post',
    data: payload,
  })
}


export function listRecordDrafts(params: {
  projectId: number
  caseId: number
  status?: number
  limit?: number
}): Promise<RecordDraftView[]> {
  return request<RecordDraftView[]>({
    url: '/appauto/record-drafts',
    method: 'get',
    params: { limit: 100, ...params },
  })
}


export function findRecordDraft(id: number): Promise<RecordDraftView> {
  return request<RecordDraftView>({
    url: `/appauto/record-drafts/${id}`,
    method: 'get',
  })
}


export function confirmRecordDraft(
  id: number,
  checkedStepNos: number[],
  expectedScriptVersion?: number,
): Promise<RecordDraftView> {
  return request<RecordDraftView>({
    url: `/appauto/record-drafts/${id}/confirm`,
    method: 'post',
    data: { checkedStepNos, expectedScriptVersion },
  })
}


export function rejectRecordDraft(id: number): Promise<void> {
  return request<void>({
    url: `/appauto/record-drafts/${id}/reject`,
    method: 'post',
  })
}


export interface AppautoPopupRule {
  id: number
  projectId: number | null
  sourceTaskId: number | null
  title: string | null
  rule: string | null
  status: number
  source: string | null
  createdAt: string | null
}


export function learnPopupRule(payload: { taskId: number; projectId: number }): Promise<number> {
  return request<number>({
    url: '/appauto/popup-rules/learn',
    method: 'post',
    data: payload,
  })
}


export function listPopupRules(params: {
  projectId: number
  status?: number
  limit?: number
}): Promise<AppautoPopupRule[]> {
  return request<AppautoPopupRule[]>({
    url: '/appauto/popup-rules',
    method: 'get',
    params: { limit: 100, ...params },
  })
}


export function findPopupRule(id: number): Promise<AppautoPopupRule> {
  return request<AppautoPopupRule>({
    url: `/appauto/popup-rules/${id}`,
    method: 'get',
  })
}


export function confirmPopupRule(id: number): Promise<AppautoPopupRule> {
  return request<AppautoPopupRule>({
    url: `/appauto/popup-rules/${id}/confirm`, method: 'post',
  })
}


export function rejectPopupRule(id: number): Promise<void> {
  return request<void>({
    url: `/appauto/popup-rules/${id}/reject`, method: 'post',
  })
}


export interface AppautoPortability {
  engineCode: string
  engineName: string
  caseId: number
  caseTitle: string
  verdict: string
  items: string[]
  stepCount: number
}


export function precheckPortability(
  projectId: number,
  caseId: number,
  engineCode: string,
): Promise<AppautoPortability> {
  return request<AppautoPortability>({
    url: '/appauto/portability',
    method: 'get',
    params: { projectId, caseId, engineCode },
  })
}


export interface MatrixCombo {
  engineCode: string
  platformCode: string
  deviceKindCode: string
  
  envProfileId?: number | null
}


export interface MatrixFanoutPayload {
  projectId: number
  caseId: number
  name?: string | null
  envId: number
  combos: MatrixCombo[]
}


export interface MatrixPrecheckView {
  fanoutTasks: number
  minutesPerTask: number
  aheadPendingTasks: number
  idleCapacity: number
  estQueueMinutes: number
  estimatedStartAt: string
  fanoutMaxAllowed: number
  budgetHighWater: boolean
  message: string
}


export interface MatrixDispatchResult {
  batchId: number
  batchNo: string
  createdTasks: number
  taskIds: number[]
  message: string
}


export interface MatrixStepFailRow {
  stepNo: number
  action: string
  failCount: number
  sampleDetail?: string | null
}


export interface MatrixModelRow {
  model: string
  osVersion?: string | null
  samples: number
  passSamples: number
  failSamples: number
  passRate: number
  stepFailures: MatrixStepFailRow[]
  message: string
}


export function precheckMatrix(payload: MatrixFanoutPayload): Promise<MatrixPrecheckView> {
  return request<MatrixPrecheckView>({ url: '/appauto/matrix-precheck', method: 'post', data: payload })
}


export function fanoutMatrix(payload: MatrixFanoutPayload): Promise<MatrixDispatchResult> {
  return request<MatrixDispatchResult>({ url: '/appauto/matrix-fanout', method: 'post', data: payload })
}


export function getMatrixModelReport(projectId: number, caseId: number): Promise<MatrixModelRow[]> {
  return request<MatrixModelRow[]>({
    url: '/appauto/matrix-model-report',
    method: 'get',
    params: { projectId, caseId },
  })
}


export interface MatrixSamplePayload {
  projectId: number
  caseId: number
  topN?: number | null
}


export interface MatrixSampleView {
  recommendations: MatrixCombo[]
  coverageMessage: string
  costMessage: string
  summary: string
}


export function matrixSample(payload: MatrixSamplePayload): Promise<MatrixSampleView> {
  return request<MatrixSampleView>({ url: '/appauto/matrix-sample', method: 'post', data: payload })
}


export interface AppautoCredential {
  id: number
  projectId: number
  name: string
  
  credKey: string
  
  secretMask: string
  
  status: number
  /** Evidence grade of the last test run (four-state); null means never tested. */
  testGrade?: string | null
  remark?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}


export interface CredentialSavePayload {
  projectId: number
  name: string
  credKey: string
  secret: string
  remark?: string | null
}


export function listCredentials(projectId: number, enabledOnly?: boolean): Promise<AppautoCredential[]> {
  return request<AppautoCredential[]>({ url: '/appauto/credentials', method: 'get', params: { projectId, enabledOnly } })
}


export function createCredential(payload: CredentialSavePayload): Promise<number> {
  return request<number>({ url: '/appauto/credentials', method: 'post', params: { projectId: payload.projectId }, data: payload })
}


export function updateCredential(id: number, payload: CredentialSavePayload): Promise<void> {
  return request<void>({ url: `/appauto/credentials/${id}`, method: 'put', data: payload })
}


export function setCredentialEnabled(id: number, enabled: boolean): Promise<void> {
  return request<void>({ url: `/appauto/credentials/${id}/enabled`, method: 'put', params: { enabled } })
}


export interface AppautoApp {
  id: number
  projectId: number
  name: string
  packageName: string
  version?: string | null
  platformCode?: string | null
  permissionRuleRef?: string | null
  artifactKey?: string | null
  artifactSizeBytes?: number | null
  artifactSha256?: string | null
  source?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}


export interface AppPackageSavePayload {
  projectId: number
  name: string
  packageName: string
  version?: string | null
  platformCode?: string | null
  permissionRuleRef?: string | null
}

export function listAppPackages(projectId: number): Promise<AppautoApp[]> {
  return request<AppautoApp[]>({ url: '/appauto/packages', method: 'get', params: { projectId } })
}


export function createAppPackage(payload: AppPackageSavePayload): Promise<number> {
  return request<number>({ url: '/appauto/packages', method: 'post', data: payload })
}

export function findAppPackage(id: number): Promise<AppautoApp> {
  return request<AppautoApp>({ url: `/appauto/packages/${id}`, method: 'get' })
}


export function uploadAppPackage(
  id: number,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<string> {
  return postMultipart<string>(`/appauto/packages/${id}/upload`, file, undefined, onProgress, 120000)
}

export function deleteAppPackage(id: number): Promise<void> {
  return request<void>({ url: `/appauto/packages/${id}`, method: 'delete' })
}


export function deleteCredential(id: number): Promise<void> {
  return request<void>({ url: `/appauto/credentials/${id}`, method: 'delete' })
}



export interface AppautoTakeover {
  id: number
  deviceId: number
  projectId: number
  taskId?: number | null
  requesterId: number
  occupierId: number
  state: string
  reason?: string | null
  decidedBy?: number | null
  decidedAt?: string | null
  expiresAt?: string | null
  recordingRef?: string | null
  createdAt?: string | null
}


export interface TakeoverCreatePayload {
  deviceId: number
  taskId?: number | null
  reason?: string | null
}


export function requestTakeover(payload: TakeoverCreatePayload): Promise<AppautoTakeover> {
  return request<AppautoTakeover>({ url: '/appauto/takeover/request', method: 'post', data: payload })
}


export function agreeTakeover(id: number): Promise<AppautoTakeover> {
  return request<AppautoTakeover>({ url: `/appauto/takeover/${id}/agree`, method: 'post' })
}


export function rejectTakeover(id: number): Promise<AppautoTakeover> {
  return request<AppautoTakeover>({ url: `/appauto/takeover/${id}/reject`, method: 'post' })
}


export function forceTakeover(id: number): Promise<AppautoTakeover> {
  return request<AppautoTakeover>({ url: `/appauto/takeover/${id}/force`, method: 'post' })
}


export function endTakeover(id: number): Promise<AppautoTakeover> {
  return request<AppautoTakeover>({ url: `/appauto/takeover/${id}/end`, method: 'post' })
}


export function getTakeover(id: number): Promise<AppautoTakeover> {
  return request<AppautoTakeover>({ url: `/appauto/takeover/${id}`, method: 'get' })
}


export function enqueueTakeoverInput(
  id: number,
  commands: TakeoverCommand[],
): Promise<{ accepted: number }> {
  return request<{ accepted: number }>({
    url: `/appauto/takeover/${id}/input`,
    method: 'post',
    data: { commands },
  })
}


export interface AppautoReservation {
  id: number
  
  projectId: number | null
  deviceId: number
  
  batchId: number | null
  holderUserId: number
  
  holderName: string
  startsAt: string
  endsAt: string
  
  state: string
  reason?: string | null
  approvedBy?: number | null
  approvedAt?: string | null
  releasedAt?: string | null
  releaseReason?: string | null
  createdAt?: string | null
}


export interface ReservationCreatePayload {
  projectId: number | null
  deviceId: number
  batchId?: number | null
  startsAt: string
  endsAt: string
  reason?: string | null
}


export interface ReservationCalendarLane {
  deviceId: number
  name: string
  model?: string | null
  reservations: AppautoReservation[]
  
  activeLease?: AppautoLeaseRun | null
}


export interface ReservationCalendarView {
  lanes: ReservationCalendarLane[]
  
  pendingApprovalCount: number
  from: string
  to: string
}


export const RESERVATION_STATE_LABEL: Record<string, string> = {
  get PENDING_APPROVAL() { return td('dict.appauto.reservation.pendingApproval') },
  get ACTIVE() { return td('dict.appauto.reservation.active') },
  get RELEASED() { return td('dict.appauto.reservation.released') },
  get EXPIRED() { return td('dict.appauto.reservation.expired') },
  get CANCELLED() { return td('dict.appauto.reservation.cancelled') },
  get REJECTED() { return td('dict.appauto.reservation.rejected') },
}


export function createReservation(payload: ReservationCreatePayload): Promise<AppautoReservation> {
  return request<AppautoReservation>({ url: '/appauto/reservation', method: 'post', data: payload })
}


export function listReservations(params: {
  projectId: number
  deviceId?: number | null
  batchId?: number | null
  includeTerminal?: boolean
}): Promise<AppautoReservation[]> {
  return request<AppautoReservation[]>({
    url: '/appauto/reservation',
    method: 'get',
    params: {
      projectId: params.projectId,
      deviceId: params.deviceId ?? undefined,
      batchId: params.batchId ?? undefined,
      includeTerminal: params.includeTerminal ?? false,
    },
  })
}


export function getReservationCalendar(
  projectId: number,
  from?: string,
  to?: string,
): Promise<ReservationCalendarView> {
  return request<ReservationCalendarView>({
    url: '/appauto/reservation/calendar',
    method: 'get',
    params: { projectId, from: from || undefined, to: to || undefined },
  })
}


export function bindReservationBatch(id: number, batchId: number | null): Promise<AppautoReservation> {
  return request<AppautoReservation>({
    url: `/appauto/reservation/${id}/bind-batch`,
    method: 'post',
    params: { batchId: batchId ?? undefined },
  })
}


export function releaseReservation(id: number, reason?: string): Promise<AppautoReservation> {
  return request<AppautoReservation>({
    url: `/appauto/reservation/${id}/release`,
    method: 'post',
    params: { reason: reason || undefined },
  })
}


export function cancelReservation(id: number, reason?: string): Promise<AppautoReservation> {
  return request<AppautoReservation>({
    url: `/appauto/reservation/${id}/cancel`,
    method: 'post',
    params: { reason: reason || undefined },
  })
}


export function approveReservation(id: number): Promise<AppautoReservation> {
  return request<AppautoReservation>({ url: `/appauto/reservation/${id}/approve`, method: 'post' })
}


export function rejectReservation(id: number, reason?: string): Promise<AppautoReservation> {
  return request<AppautoReservation>({
    url: `/appauto/reservation/${id}/reject`,
    method: 'post',
    params: { reason: reason || undefined },
  })
}

// ---------------------------------------------------------------------------
// Element library (/api/v1/appauto/elements, W5 app-automation phase two P1):
// locator profiles referenced by name from app steps. JSONB columns travel as
// raw JSON text (sibling appauto views posture); parsing lives in the page.
// Locator resolution itself stays executor-side (element_candidates.py single
// source) — this surface is profile CRUD only.
// ---------------------------------------------------------------------------

/** Locator-type value set mirrored from the core CHECK (IMAGE/COORD/REGION/OCR). */
export const ELEMENT_LOCATOR_TYPES = ['IMAGE', 'COORD', 'REGION', 'OCR']

export interface AppautoElement {
  id: number
  projectId: number
  name: string
  locatorType: string
  /** JSON object text, e.g. {"resourceId":"com.demo:id/login"}; parsed by the page. */
  locatorConfig?: string | null
  /** JSON array text of {resolution, artifactKey} variants; parsed by the page. */
  variants?: string | null
  confidenceThreshold?: number | null
  createdAt?: string
  updatedAt?: string
}

export interface AppautoElementPage {
  list: AppautoElement[]
  total: number
}

export interface AppautoElementInputPayload {
  name: string
  locatorType: string
  locatorConfig: Record<string, unknown> | null
  variants: Array<Record<string, unknown>> | null
  confidenceThreshold: number | null
}

/** Paged, newest-first listing with keyword + locator-type filters. */
export function pageElements(params: {
  projectId: number
  keyword?: string
  locatorType?: string
  page?: number
  pageSize?: number
}): Promise<AppautoElementPage> {
  return request<AppautoElementPage>({
    url: '/appauto/elements',
    method: 'get',
    params: {
      projectId: params.projectId,
      keyword: params.keyword || undefined,
      locatorType: params.locatorType || undefined,
      page: params.page ?? 1,
      pageSize: params.pageSize ?? 20,
    },
  })
}

export function findElement(id: number): Promise<AppautoElement> {
  return request<AppautoElement>({ url: `/appauto/elements/${id}`, method: 'get' })
}

/** Create one profile against the target project (manage gate; returns the new id). */
export function createElement(projectId: number, payload: AppautoElementInputPayload): Promise<number> {
  return request<number>({ url: '/appauto/elements', method: 'post', params: { projectId }, data: payload })
}

/** Collaborative capture payload (mirror of the admin DTO): cut one rectangle out
 *  of a live monitor frame and file it as an IMAGE element. frameIndex null=newest
 *  buffered frame; scales empty/null falls back to the server-side default list. */
export interface AppautoElementCapturePayload {
  taskId: number
  frameIndex: number | null
  name: string
  x: number
  y: number
  width: number
  height: number
  scales: number[] | null
  confidenceThreshold: number | null
}

/** File a live-frame crop as a new image element (manage gate on the target
 *  project; region/frame rejections travel from core unmutated). Returns the id. */
export function captureElementFromFrame(
  projectId: number,
  payload: AppautoElementCapturePayload,
): Promise<number> {
  return request<number>({ url: '/appauto/elements/capture', method: 'post', params: { projectId }, data: payload })
}

export function updateElement(id: number, payload: AppautoElementInputPayload): Promise<AppautoElement> {
  return request<AppautoElement>({ url: `/appauto/elements/${id}`, method: 'put', data: payload })
}

export function deleteElement(id: number): Promise<void> {
  return request<void>({ url: `/appauto/elements/${id}`, method: 'delete' })
}


export interface CredentialProbeOutcome {
  runToken: string
  verdict: string
  /** Four-state evidence grade name; null when no request ran. */
  grade?: string | null
  /** Badge wording served by the backend (single wording source). */
  gradeHuman?: string | null
  /** Verdict sentence with the fix-up pointer (reason + where to change). */
  note?: string | null
  /** Vault references that actually rode the wire (URL + headers + body). */
  injections?: number | null
}

export interface CredentialTestForm {
  url: string
  method: string
  bodyTemplate: string
  /** Repeatable header rows; values may carry the vault placeholder. */
  headers?: { name: string; value: string }[]
}

export function testCredential(id: number, form: CredentialTestForm): Promise<CredentialProbeOutcome> {
  return request<CredentialProbeOutcome>({ url: `/appauto/credentials/${id}/test`, method: 'post', data: form })
}

export function listCredentialTestEvents(
  id: number, runToken: string, cursor: number,
): Promise<{  cursor: number; level: string; summary: string; detail: string | null; landedAt: string }[]> {
  return request({ url: `/appauto/credentials/${id}/test-events`, method: 'get', params: { runToken, cursor } })
}
