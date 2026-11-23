import { request } from './request'




export interface ApiCollectionItem {
  id: number
  projectId: number
  parentId: number
  name: string
  tags: string
  children: ApiCollectionItem[]
  /** Interface-count badge for the rail : absent until the list endpoint
 * carries the column — the view then falls back to the loaded segment size of
 * the picked collection only (never a fake 0 on the others). */
  requestCount?: number | null
  /** Collection default headers as normalized jsonb text ([{name,value}] shape);
   *  mirror of the admin ApiCollectionView inheritance columns (null = never set). */
  headersJson?: string | null
  /** Collection default auth block as normalized jsonb text; same mirror contract. */
  authJson?: string | null
}


export interface ApiRequestItem {
  id: number
  collectionId: number
  name: string
  method: string
  urlPath: string
  headersJson: string
  bodyText: string
  authJson: string
  assertionsJson: string
  /** orchestration precondition row jsonb text ("" / {} = straight-line run). */
  preconditionJson: string
  /** orchestration loop policy row jsonb text ("" / {} = no repeat). */
  loopJson: string
  /** transport discriminator: http | ws (legacy default http). */
  protocol: string
  
  promotedCaseId: number | null
  
  contractStale: boolean
  /** Mock twin badge (contract diff hit + derived openapi stub exists; cleared on edit). */
  mockStale: boolean
}


export interface CollectionSavePayload {
  projectId: number
  parentId: number
  name: string
  tags: string
}


export interface RequestSavePayload {
  collectionId: number
  name: string
  method: string
  urlPath: string
  headersJson: string
  bodyText: string
  authJson: string
  assertionsJson: string
  /** precondition row JSON text mirrored to the core save gate. */
  preconditionJson?: string
  /** loop policy row JSON text mirrored to the core save gate. */
  loopJson?: string
  /** transport protocol (http | ws; absent = http at the core gate). */
  protocol?: string
}

/** Two-level collection rail (H1 project isolation). programId = header
 * program lens : set, the core bridge answers a multi-root
 * forest across every visible project of the program; absent = the
 * pre-feature single-project request. */
export function listCollections(projectId: number, programId?: number): Promise<ApiCollectionItem[]> {
  return request<ApiCollectionItem[]>({ url: '/apitest/collections', method: 'get', params: { projectId, programId } })
}


export function createCollection(payload: CollectionSavePayload): Promise<ApiCollectionItem> {
  return request<ApiCollectionItem>({ url: '/apitest/collections', method: 'post', data: payload })
}

/** Rename a collection or edit its inherited defaults (mirrors the four-field admin
 * CollectionUpdateRequest — callers must round-trip tags or they get wiped; omitted
 * headersJson/authJson keep the stored values, the inheritance face). */
export function renameCollection(
  id: number,
  name: string,
  tags: string,
  defaults?: { headersJson: string | null; authJson: string | null },
): Promise<ApiCollectionItem> {
  return request<ApiCollectionItem>({
    url: `/apitest/collections/${id}`,
    method: 'put',
    data: { name, tags, ...defaults },
  })
}


export function deleteCollection(id: number): Promise<void> {
  return request<void>({ url: `/apitest/collections/${id}`, method: 'delete' })
}


export function listRequests(collectionId: number): Promise<ApiRequestItem[]> {
  return request<ApiRequestItem[]>({ url: '/apitest/requests', method: 'get', params: { collectionId } })
}


export function createRequest(payload: RequestSavePayload): Promise<ApiRequestItem> {
  return request<ApiRequestItem>({ url: '/apitest/requests', method: 'post', data: payload })
}


export function updateRequest(id: number, payload: RequestSavePayload): Promise<ApiRequestItem> {
  return request<ApiRequestItem>({ url: `/apitest/requests/${id}`, method: 'put', data: payload })
}


export function deleteRequest(id: number): Promise<void> {
  return request<void>({ url: `/apitest/requests/${id}`, method: 'delete' })
}

/**
 * Persist the user-maintained order of requests inside a collection: the
 * drag order on the rail becomes the launch-preview and whole-set execution
 * order. Payload = FULL ordered id list; stale collection / foreign ids /
 * duplicates are refused by the core single validation point with a plain
 * 400 message that passes through untouched.
 */
export function reorderRequests(collectionId: number, orderedRequestIds: number[]): Promise<void> {
  return request<void>({ url: `/apitest/requests/${collectionId}/order`, method: 'put', data: orderedRequestIds })
}

/**
 * Promotion result (admin PromotionResultDto mirror; component names must stay
 * byte-identical to the wire shape — drift deserializes to silent zeros).
 */
export interface PromotionResult {
  caseId: number
  caseNo: string
  requestId: number
  projectId: number
}

/**
 * Promote the tuned sandbox request into a regression case ( chain; admin
 * POST /apitest/requests/{id}/promote). All judgement is single-sourced in
 * core: repeat promotion answers 40904 naming the existing case number and
 * the copy passes through verbatim.
 */
export function promoteRequest(id: number): Promise<PromotionResult> {
  return request<PromotionResult>({ url: `/apitest/requests/${id}/promote`, method: 'post' })
}

/** Admin mirror of the core flow-promotion result (byte-identical field
 *  names to FlowPromotionResultDto — a drift deserializes to silent zeros). */
export interface FlowPromotionResult {
  caseId: number
  caseNo: string
  collectionId: number
  projectId: number
  promotedRequestCount: number
}

/**
 * Promote the WHOLE collection flow (the exact rail-order list the flow
 * pane draws) into ONE regression case ; admin
 * POST /apitest/collections/{id}/promote-flow, zero request body). All
 * judgement is single-sourced in core: the empty-flow 400, the
 * already-promoted 409 naming the existing case, and the orchestration /
 * ws honest refusals pass through verbatim as plain-language copy.
 */
export function promoteFlow(collectionId: number): Promise<FlowPromotionResult> {
  return request<FlowPromotionResult>({
    url: `/apitest/collections/${collectionId}/promote-flow`,
    method: 'post',
  })
}

/** Request detail (read door; powers the case-detail origin jump that must
 *  open the exact promoted request instead of the auto-selected first one). */
export function getRequest(id: number): Promise<ApiRequestItem> {
  return request<ApiRequestItem>({ url: `/apitest/requests/${id}`, method: 'get' })
}

/**
 * One merged header row of the effective preview (admin EffectiveHeaderDto
 * mirror): value as stored (template literals pass through unrendered) plus
 * the face it came from — rendered as a source chip so inheritance is never
 * invisible.
 */
export interface EffectiveHeaderItem {
  name: string
  value: string
  /** request | collection (winner keeps its own casing on same-name pairs). */
  source: string
}

/**
 * Final-effective preview (admin EffectivePreviewDto mirror; core
 * ApiEffectiveDefaultsResolver is the single merge verdict — the editor only
 * renders this payload and never re-implements the maths).
 */
export interface EffectivePreviewItem {
  requestId: number
  collectionId: number
  collectionName: string
  headers: EffectiveHeaderItem[]
  headersJson: string
  authJson: string
  /** Four-valued verdict: request | collection | none-explicit | none. */
  authSource: string
}

/** Effective-defaults preview of a saved request (403/404 ride the shared
 *  interceptor — the editor keeps the last verdict on failure). */
export function getEffectivePreview(id: number): Promise<EffectivePreviewItem> {
  return request<EffectivePreviewItem>({ url: `/apitest/requests/${id}/effective`, method: 'get' })
}

/**
 * Batch effective-defaults preview for a whole interface set (admin
 * GET /api/v1/apitest/requests/effective-batch?collectionId=, one round trip
 * mirroring the per-request verdict — the perf-wizard import panel pulls the
 * merged headers/auth for hundreds of rows in a single call, never a
 * per-request loop). Rows arrive in the set's maintained (frozen) order.
 */
export function listEffectiveBatch(collectionId: number): Promise<EffectivePreviewItem[]> {
  return request<EffectivePreviewItem[]>({
    url: '/apitest/requests/effective-batch',
    method: 'get',
    params: { collectionId },
  })
}

/**
 * Signature auth preset (admin AuthPresetDto mirror; record component names
 * stay byte-identical to the wire shape). Templates carry ${gen.*} functions
 * and {{CRED:key}} references only — never secret values — so the dropdown is
 * plain catalogue data judged entirely by the core save gate downstream.
 */
export interface AuthPresetAssemblyItem {
  into: string
  name: string
  value: string
}

export interface AuthPresetItem {
  presetKey: string
  label: string
  assemblies: AuthPresetAssemblyItem[]
}

/** Signature preset catalogue (GET /apitest/requests/auth-presets, K8 rows). */
export function listAuthPresets(): Promise<AuthPresetItem[]> {
  return request<AuthPresetItem[]>({ url: '/apitest/requests/auth-presets', method: 'get' })
}


export interface StepAliasItem {
  id: number
  projectId: number
  aliasName: string
  
  kind: string
  
  target: string
  ownerRef: string | null
}


export function listStepAliases(projectId: number): Promise<StepAliasItem[]> {
  return request<StepAliasItem[]>({ url: '/apitest/step-aliases', method: 'get', params: { projectId } })
}


export function createStepAlias(payload: {
  projectId: number; aliasName: string; kind: string; target: string; ownerRef?: string
}): Promise<StepAliasItem> {
  return request<StepAliasItem>({ url: '/apitest/step-aliases', method: 'post', data: payload })
}


export function updateStepAlias(id: number, payload: {
  kind?: string; target: string; ownerRef?: string
}): Promise<StepAliasItem> {
  return request<StepAliasItem>({ url: `/apitest/step-aliases/${id}`, method: 'put', data: payload })
}


export function deleteStepAlias(id: number): Promise<void> {
  return request<void>({ url: `/apitest/step-aliases/${id}`, method: 'delete' })
}

/**
 * Collection-run contracts (admin /api/v1/apitest/run-sets mirror; record
 * component names stay byte-identical to the wire shape).
 * Interruption modes = core ApiRunSetPlanSupport.MODES single set; the page
 * only offers the three values, all planning/stop judgement stays in core.
 */

/** Set statuses: 0=queued 1=running 2=done-passed 3=done-with-failures 4=interrupted. */
export interface RunSetItem {
  id: number
  collectionId: number
  projectId: number
  envId: number | null
  name: string
  interruptionMode: string
  status: number
  pinnedJson: string
  initialVarsJson: string
  totalSteps: number
  passedCount: number
  failedCount: number
  skippedCount: number
}

/** Child run row: 0=pending 1=passed 2=assert-failed 3=execution-error 4=skipped. */
export interface RunSetStepItem {
  runId: number
  requestId: number
  seqNo: number
  name: string
  method: string
  status: number
  durationMs: number | null
  errorMsg: string | null
  /** Transport face of the source request: 'http' | 'ws' (attribution line input). */
  protocol: string
  /** Assertion rows landed in assert_detail — 0 means none were ever configured. */
  assertCount: number
}

export interface RunSetDetail {
  ledger: RunSetItem
  steps: RunSetStepItem[]
}

/** Launch-preview row (core computes order/extracts/edges; UI renders only). */
export interface RunSetPreviewDep {
  key: string
  producerRequestId: number
  /** false = the producer sits after this consumer: honest forward-reference flag. */
  producerEarlier: boolean
}

export interface RunSetPreviewStep {
  requestId: number
  seqNo: number
  name: string
  method: string
  urlPath: string
  extracts: string[]
  references: string[]
  deps: RunSetPreviewDep[]
}

export interface RunSetCreatePayload {
  collectionId: number
  envId: number | null
  name: string
  interruptionMode: string
}

/** Launch a whole-collection run (empty collection / foreign env rejected in core with plain words). */
export function createRunSet(payload: RunSetCreatePayload): Promise<RunSetItem> {
  return request<RunSetItem>({ url: '/apitest/run-sets', method: 'post', data: payload })
}

/** Run-set detail: ledger + child steps in seq order (poll this while running). */
export function getRunSetDetail(id: number): Promise<RunSetDetail> {
  return request<RunSetDetail>({ url: `/apitest/run-sets/${id}`, method: 'get' })
}

/** Launch-preview card: tree order + variable dependency arrows for a collection. */
export function previewRunSet(collectionId: number): Promise<RunSetPreviewStep[]> {
  return request<RunSetPreviewStep[]>({ url: '/apitest/run-sets/previews', method: 'get', params: { collectionId } })
}

/** Paged run-set history window (display-scale iron law: list door carries page/pageSize). */
export interface RunSetPage {
  list: RunSetItem[]
  total: number
  page: number
  pageSize: number
}

/** One paged window of the run-set history of one collection, newest first. */
export function listRunSets(collectionId: number, page = 1, pageSize = 20): Promise<RunSetPage> {
  return request<RunSetPage>({ url: '/apitest/run-sets', method: 'get', params: { collectionId, page, pageSize } })
}

/**
 * Response-compare wire mirror (core apitest read model, W-c5). The tri-state
 * and the four-color vocabulary are core-owned — these literal unions lock the
 * wire shape so a rename on either side fails here instead of silently
 * dropping values.
 */
export type ResponseCompareMode = 'JSON' | 'TEXT' | 'BINARY'
export type ResponseDiffKind = 'ADDED' | 'REMOVED' | 'TYPE_CHANGED' | 'VALUE_CHANGED'

/** One leaf difference (JSON mode) or one line difference (TEXT mode, path=line:N). */
export interface ResponseDiffEntry {
  path: string
  kind: ResponseDiffKind
  leftType: string
  rightType: string
  leftSummary: string | null
  rightSummary: string | null
}

/** Compare verdict: mode + honest truncation flags + digests + sorted entries. */
export interface ResponseCompareView {
  mode: ResponseCompareMode
  leftTruncated: boolean
  rightTruncated: boolean
  leftSha256: string
  rightSha256: string
  entries: ResponseDiffEntry[]
}

/**
 * Compare the recorded response bodies of two runs (guarded admin bridge:
 * both sides pass the H1 view-guard before core is reached; a run without a
 * stored snapshot answers 400 in plain words, never a fabricated empty diff).
 */
export function compareRuns(left: number, right: number): Promise<ResponseCompareView> {
  return request<ResponseCompareView>({ url: '/apitest/runs/compare', method: 'get', params: { left, right } })
}
