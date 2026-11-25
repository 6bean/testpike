import { request, type PageResult } from './request'
import type { ExtractionEvent } from './taskEvents'
import { MOCK_ENRICH_TERMINAL_PREFIXES } from '@/utils/serverTextWire'

/**
 * API Mock inspector API (mirrors admin /api/v1/mock contract: spaces / hits /
 * load-state — the three gates the request inspector page consumes).
 * Field names mirror the admin DTO records one-to-one; the serving plane never
 * invents a second shape here.
 */

/** Mock space view (admin MockSpaceView record mirror). */
export interface MockSpaceItem {
  id: number
  projectId: number
  name: string
  spaceKey: string
  alias: string | null
  envId: number | null
  basePathMode: string
  status: number | null
  authMode: string | null
  rateLimitRps: number | null
  quotaDaily: number | null
  priority: number | null
  /** Space-level chaos dial 0-100 (admin MockSpaceView mirror, . */
  chaosPercent: number | null
  /** Miss ladder rung (admin MockSpaceView mirror, :
 * passthrough | reject | llm-fallback; the rung spectrum is core-side
 * MockMissPolicies — this face only renders what the row carries. */
  missPolicy: string | null
  version: number | null
  /** Stub inventory size, enabled+disabled both (list projection; null on single reads). */
  ruleCount?: number | null
  /** Matched/total over the hit ledger; null = no traffic sampled yet (never a fake 0%). */
  hitRate?: number | null
  createdAt: string | null
  updatedAt: string | null
}

/** One recorded request on the serving plane (admin MockHitView mirror).
 * Values arrive pre-masked by the pipeline — the UI renders them as-is and
 * never triggers a second unmasking path. */
export interface MockHitItem {
  id: number | null
  spaceId: number | null
  stubId: number | null
  requestSnapshot: Record<string, unknown> | null
  responseSnapshot: Record<string, unknown> | null
  matched: boolean | null
  matchTrace: Array<Record<string, unknown>> | null
  latencyMs: number | null
  faultApplied: string | null
  sessionId: string | null
  hitAt: string | null
  /** Honest provenance of the answering plane: stub | llm | proxy;
   *  null on pre-provenance or plain-miss rows (never a fabricated default). */
  source: string | null
}

/** Load-state self-check of the serving plane (admin MockLoadStateView mirror). */
export interface MockLoadState {
  everLoaded: boolean
  pinnedSpaceKey: string | null
  pinnedVersion: string | null
  lastSuccessAt: string | null
  stale: boolean
  consecutiveFailures: number
  lastError: string | null
  hitBuffered: number
  hitDegraded: boolean
  hitOverflowDrops: number
}

/** Spaces of one project in routing order (empty list = honest empty state). */
export function listSpaces(projectId: number): Promise<MockSpaceItem[]> {
  return request<MockSpaceItem[]>({ url: '/mock/spaces', method: 'get', params: { projectId } })
}

/** One inspector page for a space, newest first; matched absent = both spectra. */
export function listHits(
  spaceId: number,
  matched: boolean | undefined,
  page: number,
  pageSize: number,
  sort?: MockSortOptions,
): Promise<PageResult<MockHitItem>> {
  return request<PageResult<MockHitItem>>({
    url: `/mock/spaces/${spaceId}/hits`,
    method: 'get',
    params: { matched, page, pageSize, sortBy: sort?.sortBy, sortDir: sort?.sortDir },
  })
}

/** Live load-state of the serving plane for a space (no cache on purpose). */
export function getLoadState(spaceId: number): Promise<MockLoadState> {
  return request<MockLoadState>({ url: `/mock/spaces/${spaceId}/load-state`, method: 'get' })
}

/** Stub row of one space (admin MockStubView mirror; rows arrive in priority order). */
export interface MockStubItem {
  id: number
  spaceId: number
  name: string
  priority: number | null
  method: string
  pathMatchType: string
  pathPattern: string
  matchersJson: string | null
  responseJson: string | null
  scenarioName: string | null
  requiredState: string | null
  newState: string | null
  source: string | null
  sourceRef: string | null
  enrichedBy?: string | null
  /** persisted flag: the imported endpoint left the contract */
  contractStale?: boolean | null
  enabled: boolean | null
  /** Optimistic lock echo (V240) — the edit door must send it back. */
  version?: number | null
  createdAt: string | null
  updatedAt: string | null
}

/** Serving-URL supply (admin MockServingUrlView mirror, 02 v2.63). */
export interface MockServingUrl {
  publicBaseUrl: string
  trafficBasePath: string
}

/** Create payload (admin MockSpaceCreateRequest mirror; null = server default). */
export interface MockSpaceCreatePayload {
  projectId: number
  name: string
  spaceKey: string
  alias?: string | null
  envId?: number | null
  basePathMode?: string | null
  authMode?: string | null
  rateLimitRps?: number | null
  quotaDaily?: number | null
  priority?: number | null
  /** Space-level chaos dial 0-100 (null = 0, no injection). */
  chaosPercent?: number | null
  /** Miss ladder rung (null = server default llm-fallback; rung legality is
   * decided by the core save chain, admin MockSpaceCreateRequest mirror). */
  missPolicy?: string | null
}

/** Create payload (admin MockStubCreateRequest mirror; null = server default). */
export interface MockStubCreatePayload {
  name: string
  priority?: number | null
  method: string
  pathMatchType: string
  pathPattern: string
  matchersJson?: string | null
  responseJson?: string | null
  scenarioName?: string | null
  source?: string | null
  sourceRef?: string | null
  enabled?: boolean | null
}

/** Create a space (draft on birth; returns the new row id). */
export function createSpace(payload: MockSpaceCreatePayload): Promise<number> {
  return request<number>({ url: '/mock/spaces', method: 'post', data: payload })
}

/**
 * Move a space along the legal lifecycle edges (0 draft -> 1 published -> 2
 * retired). expectedVersion carries the row version untouched — a stale value
 * comes back as BizError code '40901' and the caller must reload before retry.
 */
export function transitionSpace(
  spaceId: number,
  targetStatus: number,
  expectedVersion: number | null,
): Promise<MockSpaceItem> {
  return request<MockSpaceItem>({
    url: `/mock/spaces/${spaceId}/transition`,
    method: 'post',
    data: { targetStatus, expectedVersion },
  })
}

/**
 * Save the space's miss ladder , admin PUT /mock/spaces/
 * {spaceId}/policy mirror). expectedVersion carries the listed row's version —
 * a stale value comes back as BizError code '40901' and the caller must reload;
 * rung-name legality is decided by the shared core spectrum, never here.
 */
export function updateSpacePolicy(
  spaceId: number,
  missPolicy: string,
  expectedVersion: number | null,
): Promise<MockSpaceItem> {
  return request<MockSpaceItem>({
    url: `/mock/spaces/${spaceId}/policy`,
    method: 'put',
    data: { missPolicy, expectedVersion },
  })
}

/** Stubs of one space in evaluation order (empty list = honest empty state). */
export function listStubs(spaceId: number): Promise<MockStubItem[]> {
  return request<MockStubItem[]>({ url: `/mock/spaces/${spaceId}/stubs`, method: 'get' })
}

/** One paged window of the space's rules in evaluation order (display-scale
 * iron law — bounded windows for list surfaces; clamp and truthful total are
 * core-side single point, order matches the serving snapshot precedence). */
export function pageStubs(
  spaceId: number,
  page: number,
  pageSize: number,
): Promise<PageResult<MockStubItem>> {
  return request<PageResult<MockStubItem>>({
    url: `/mock/spaces/${spaceId}/stubs/page`,
    method: 'get',
    params: { page, pageSize },
  })
}

/** Create a stub under one space (returns the new row id). */
export function createStub(spaceId: number, payload: MockStubCreatePayload): Promise<number> {
  return request<number>({ url: `/mock/spaces/${spaceId}/stubs`, method: 'post', data: payload })
}

/** Public serving base + traffic prefix for composing space URLs (02 v2.63). */
export function getServingUrl(): Promise<MockServingUrl> {
  return request<MockServingUrl>({ url: '/mock/serving-url', method: 'get' })
}

/** Stateful scenario machine of one space (admin MockScenarioView mirror;
 * rows arrive in name order from core — never re-sort, the stub editor's
 * picklists read positions off this list). definitionJson is the raw machine
 * declaration; parsing semantics live in the shared kernel, not here. */
export interface MockScenarioItem {
  id: number
  spaceId: number
  name: string
  definitionJson: string
  createdAt: string | null
  updatedAt: string | null
}

/** Create payload (admin MockScenarioCreateRequest mirror). The machine rides
 * as raw JSON text — shape validation is the core save-chain single point. */
export interface MockScenarioCreatePayload {
  name: string
  definitionJson: string
}

/** Scenario machines declared under one space (empty list = honest empty state). */
export function listScenarios(spaceId: number): Promise<MockScenarioItem[]> {
  return request<MockScenarioItem[]>({ url: `/mock/spaces/${spaceId}/scenarios`, method: 'get' })
}

/** One paged window of the space's scenarios, name order (display-scale iron
 * law — the /mock/scenarios list page consumes this door; total is truthful
 * across pages, clamp semantics are core-side single point).
 * optional trailing sort rides the core whitelist keys (omitted = name order). */
export function pageScenarios(
  spaceId: number,
  page: number,
  pageSize: number,
  sort?: MockSortOptions,
): Promise<PageResult<MockScenarioItem>> {
  return request<PageResult<MockScenarioItem>>({
    url: `/mock/spaces/${spaceId}/scenarios/page`,
    method: 'get',
    params: { page, pageSize, sortBy: sort?.sortBy, sortDir: sort?.sortDir },
  })
}

/** File a machine declaration under a space (returns the new row id; a
 * duplicate name or broken machine comes back as core's plain-word refusal). */
export function createScenario(
  spaceId: number,
  payload: MockScenarioCreatePayload,
): Promise<number> {
  return request<number>({
    url: `/mock/spaces/${spaceId}/scenarios`,
    method: 'post',
    data: payload,
  })
}

/**
 * Contract-direct stub draft proposed by the core deterministic mapper
 * (admin MockStubDraftView record mirror — field-for-field, the wizard sends
 * chosen rows back verbatim so re-import stays idempotent).
 */
export interface MockStubDraftItem {
  endpointKey: string
  name: string
  method: string
  pathMatchType: string
  pathPattern: string
  matchersJson: string
  responseJson: string
  source: string
  sourceRef: string
  enrichedBy?: string | null
}

/** Outcome of one contract-direct import (admin MockStubImportResultView mirror). */
export interface MockStubImportResult {
  createdStubIds: number[]
  skippedEndpointKeys: string[]
}

/**
 * Preview the save-ready drafts of one spec row (wizard step one — a proposal,
 * persists nothing). Empty list = the contract has no mock-servable endpoint.
 */
export function previewStubDrafts(specId: number): Promise<MockStubDraftItem[]> {
  return request<MockStubDraftItem[]>({ url: `/mock/specs/${specId}/stub-drafts`, method: 'get' })
}

/**
 * Import chosen drafts into a space (wizard step two). specId rides the query
 * purely so the server-side guard can resolve the contract's owning project;
 * drafts must be the exact rows the preview served. Same method+path already
 * covered by the space comes back skipped — re-import is idempotent.
 */
export function importStubsFromSpec(
  spaceId: number,
  specId: number,
  drafts: MockStubDraftItem[],
): Promise<MockStubImportResult> {
  return request<MockStubImportResult>({
    url: `/mock/spaces/${spaceId}/import-from-spec`,
    method: 'post',
    params: { specId },
    data: drafts,
  })
}

/**
 * Cassette box /3b admin bridge MockRecordingView mirror —
 * field-for-field). status spectrum: recording | stopped | promoted | failed.
 * Nulls stay honest: an open box has no endedAt; capture fields
 * (summaryJson/cassetteRef) are empty until frames land.
 */
export interface MockRecordingItem {
  id: number
  spaceId: number
  targetBaseUrl: string
  status: string
  summaryJson: string | null
  cassetteRef: string | null
  promotedCount: number | null
  startedAt: string | null
  endedAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** Outcome of one cassette playback (admin MockPlaybackResultView mirror). */
export interface MockPlaybackResult {
  createdStubIds: number[]
  skippedEndpointKeys: string[]
  promotedCount: number
}

/** Cassette boxes of one space, newest first (read gate perm:mock:read). */
export function listRecordings(spaceId: number): Promise<MockRecordingItem[]> {
  return request<MockRecordingItem[]>({ url: `/mock/spaces/${spaceId}/recordings`, method: 'get' })
}

/**
 * One paged window of the space's boxes display-scale iron
 * law: the recording ledger page consumes this door; total is truthful across
 * pages, clamp + whitelist order are core-side single points). Optional
 * trailing sort rides the core recording whitelist keys (omitted = the
 * historical newest-first id order).
 */
export function pageRecordings(
  spaceId: number,
  page: number,
  pageSize: number,
  sort?: MockSortOptions,
): Promise<PageResult<MockRecordingItem>> {
  return request<PageResult<MockRecordingItem>>({
    url: `/mock/spaces/${spaceId}/recordings/page`,
    method: 'get',
    params: { page, pageSize, sortBy: sort?.sortBy, sortDir: sort?.sortDir },
  })
}

/**
 * Open a recording box against an allowlisted upstream (write gate —
 * opening a proxy recording is a configuration act; core's allowlist ladder
 * is the single refusal point, the page never pre-checks legitimacy itself).
 */
export function startRecording(spaceId: number, targetBaseUrl: string): Promise<number> {
  return request<number>({
    url: `/mock/spaces/${spaceId}/recordings`,
    method: 'post',
    data: { targetBaseUrl },
  })
}

/** Seal an open box (recording→stopped; seal stamps and refusals are core's wording). */
export function stopRecording(spaceId: number, recordingId: number): Promise<MockRecordingItem> {
  return request<MockRecordingItem>({
    url: `/mock/spaces/${spaceId}/recordings/${recordingId}/stop`,
    method: 'post',
  })
}

/**
 * Replay preview of one box under strict|fuzzy — a proposal that persists
 * nothing, so it rides the read gate. Draft rows are the same shape the
 * contract-direct wizard consumes (single mirror, no fork).
 */
export function previewPlayback(
  spaceId: number,
  recordingId: number,
  mode: 'strict' | 'fuzzy',
): Promise<MockStubDraftItem[]> {
  return request<MockStubDraftItem[]>({
    url: `/mock/spaces/${spaceId}/recordings/${recordingId}/playback/preview`,
    method: 'get',
    params: { mode },
  })
}

/**
 * Confirm-and-file the replay plan through core's single import channel
 * (write gate). Idempotence: keys already covered come back skipped, the
 * box counter promotedCount accumulates across runs.
 */
export function playRecordingBack(
  spaceId: number,
  recordingId: number,
  mode: 'strict' | 'fuzzy',
): Promise<MockPlaybackResult> {
  return request<MockPlaybackResult>({
    url: `/mock/spaces/${spaceId}/recordings/${recordingId}/playback`,
    method: 'post',
    params: { mode },
  })
}

/**
 * Promote one answered hit-ledger row into a stub rule the
 * inspector's "make this request a stub" one click). Core re-reads the row,
 * strips the query facet, drops masked placeholders, and files through the
 * single import channel: a re-click on a covered method+path comes back as a
 * skip, never a twin rule. Rows with no response plane are refused in plain
 * words by the server (the UI mirrors the gate to keep the button honest).
 */
export function promoteHitToStub(
  spaceId: number,
  hitId: number,
): Promise<MockStubImportResult> {
  return request<MockStubImportResult>({
    url: `/mock/spaces/${spaceId}/hits/${hitId}/promote`,
    method: 'post',
  })
}

/** One row of the project-wide serving inventory (admin MockServingEndpointView
 * mirror): a mock endpoint across every space, URL composed page-side from
 * raw parts (spaceKey + pathPattern) — one function owns the composition. */
export interface MockServingEndpointItem {
  stubId: number
  spaceId: number
  spaceName: string
  spaceKey: string
  spaceStatus: number | null
  basePathMode: string | null
  ruleName: string
  priority: number | null
  method: string
  pathMatchType: string
  pathPattern: string
  matchersJson: string | null
  responseJson: string | null
  scenarioName: string | null
  requiredState: string | null
  newState: string | null
  source: string | null
  sourceRef: string | null
  enrichedBy?: string | null
  /** persisted flag: the imported endpoint left the contract */
  contractStale?: boolean | null
  enabled: boolean | null
  version: number | null
  updatedAt: string | null
  /** Matched serves in the last 7 days (0 = nobody is calling this rule). */
  hits7d: number | null
}

/** Server-side sort options (keys = core whitelist; omitted = routing order). */
export interface MockSortOptions {
  /** core whitelist key (space_name/space_status/path_pattern/hits_7d/updated_at) */
  sortBy?: string
  /** asc / desc — anything else falls back to descending */
  sortDir?: string
}

/** One paged window of the project-wide serving inventory. Filters run
 * server-side (a huge inventory never screens a lie about its pages). */
export function pageServingEndpoints(
  projectId: number,
  page: number,
  pageSize: number,
  status?: number | null,
  method?: string | null,
  keyword?: string | null,
  sort?: MockSortOptions,
): Promise<PageResult<MockServingEndpointItem>> {
  return request<PageResult<MockServingEndpointItem>>({
    url: '/mock/serving-inventory/page',
    method: 'get',
    params: {
      projectId,
      page,
      pageSize,
      status,
      method,
      keyword,
      sortBy: sort?.sortBy,
      sortDir: sort?.sortDir,
    },
  })
}

/** Edit payload (admin MockStubUpdateRequest mirror). The version echo the
 * editor read rides as expectedVersion — a stale echo comes back as the
 * plain-word 40901 conflict and the caller must reload before retry. */
export interface MockStubUpdatePayload {
  name: string
  priority?: number | null
  method: string
  pathMatchType: string
  pathPattern: string
  matchersJson?: string | null
  responseJson?: string | null
  scenarioName?: string | null
  requiredState?: string | null
  newState?: string | null
  enabled?: boolean | null
  expectedVersion: number
}

/** Edit one rule in place (same core gates as create; provenance immutable). */
export function updateStub(stubId: number, payload: MockStubUpdatePayload): Promise<MockStubItem> {
  return request<MockStubItem>({ url: `/mock/stubs/${stubId}/update`, method: 'post', data: payload })
}

/** Remove one rule (the traffic plane stops serving it on the next poll). */
export function deleteStub(stubId: number): Promise<null> {
  return request<null>({ url: `/mock/stubs/${stubId}`, method: 'delete' })
}

/** 7-day matched-serve count of one rule — the impact number the delete /
 * live-edit confirmation door shows before asking. */
export function recentStubHits(stubId: number, days = 7): Promise<number> {
  return request<number>({ url: `/mock/stubs/${stubId}/recent-hits`, method: 'get', params: { days } })
}

// ---- : AI drafting job mirrors (admin MockAiJobController, seven doors) --

/** One drafting job (admin MockAiJobView mirror). Status spectrum:
 * queued | running | preview | consumed | failed | cancelled. */
export interface MockAiJobItem {
  id: number
  spaceId: number
  jobType: string
  status: string
  /** Filed request payload as JSON text ({prompt, jobType}) — the card re-reads it. */
  inputJson: string | null
  /** Pinned cost snapshot as JSON text (estimates + quota; never a live quote). */
  budgetSnapshotJson: string | null
  resultPreviewRef: string | null
  errorMessage: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** Statuses where the drafting job is still an in-flight background process
 *  (queued / generating): the job drawer auto-follows while any card sits in
 *  one of these and stops once every card is settled. Values mirror the core
 *  MockAiJobServiceImpl STATUS_* literals; the auto-follow contract test
 *  cross-checks them against the Java source so drift fails loudly. */
export const MOCK_AI_JOB_PENDING_STATUSES = ['queued', 'running'] as const

export function isMockAiJobPending(job: { status: string }): boolean {
  return (MOCK_AI_JOB_PENDING_STATUSES as readonly string[]).includes(job.status)
}

/** Filing payload (admin MockAiJobCreateRequest mirror). Create dispatches
 *  the job immediately — the quote lives on the task card afterwards. */
export interface MockAiJobCreatePayload {
  spaceId: number
  jobType: string
  prompt: string
  /** Contract version row bound by the bulk data-enriching job (spec-enrich
   *  only — core requires it there and refuses it everywhere else). */
  specId?: number
}

/**
 * Cursor window over one AI drafting job's business-step process tail
 * (R62/R67 revisit door, admin GET /api/v1/mock/jobs/{id}/process-events).
 * Rows share the ExtractionEvent shape used by every other tail — one decoder,
 * one merge rule, no second copy (R40). The limit stays server-side when
 * omitted; an empty window while the run has not landed its first frame is
 * a valid answer, never an error.
 */
export async function listAiJobProcessEvents(
  jobId: number,
  cursor: number,
  limit?: number,
): Promise<ExtractionEvent[]> {
  const rows = await request<ExtractionEvent[]>({
    url: `/mock/jobs/${jobId}/process-events`,
    method: 'get',
    params: limit === undefined ? { cursor } : { cursor, limit },
  })
  return Array.isArray(rows) ? rows : []
}

/** Terminal frames of the bulk enrichment pipeline, sealed by the generation
 *  side (drafts-ready and the failure seal). Single source so the view and
 *  the wiring contract read one judgement, never two copies.
 *  (Wire-contract literals, same form as the survey/explore tail prefixes.) */
export function isMockEnrichTerminalRow(row: { summary: string }): boolean {
  return MOCK_ENRICH_TERMINAL_PREFIXES.some((prefix) => row.summary.startsWith(prefix))
}

/** Pre-run cost estimate (admin MockAiJobEstimateView mirror) — arithmetic
 * over the stored prompt, nothing burns. */
export interface MockAiJobEstimate {
  jobId: number
  jobType: string
  estimatedInputTokens: number
  outputQuotaTokens: number
  estimatedTotalTokens: number
}

/** One drafted rule read back from the job bundle (admin
 * MockAiJobDraftItemView mirror — eight fields, same order as core). */
export interface MockAiJobDraftItem {
  name: string
  method: string
  path: string
  pathMatchType: string
  responseJson: string
  scenarioName: string | null
  /** Raw fault object text (null = no fault attached; never invented). */
  faultJson: string | null
  sourceAnchor: string
}

/** Drafting jobs of one space, newest first. */
export function listAiJobs(spaceId: number): Promise<MockAiJobItem[]> {
  return request<MockAiJobItem[]>({ url: '/mock/jobs', method: 'get', params: { spaceId } })
}

/** File a drafting request (behind the AI master AND sub-switch gates — a
 * closed switch refuses with the exact config key in plain words). */
export function createAiJob(payload: MockAiJobCreatePayload): Promise<MockAiJobItem> {
  return request<MockAiJobItem>({ url: '/mock/jobs', method: 'post', data: payload })
}

/** One job's detail (task card status + pinned budget + error wording). */
export function getAiJob(jobId: number): Promise<MockAiJobItem> {
  return request<MockAiJobItem>({ url: `/mock/jobs/${jobId}`, method: 'get' })
}

/** Pre-run cost quote for an already-filed job. */
export function estimateAiJob(jobId: number): Promise<MockAiJobEstimate> {
  return request<MockAiJobEstimate>({ url: `/mock/jobs/${jobId}/estimate`, method: 'post' })
}

/** Drafted items of a preview/consumed job (confirmation drawer data door). */
export function listAiJobDrafts(jobId: number): Promise<MockAiJobDraftItem[]> {
  return request<MockAiJobDraftItem[]>({ url: `/mock/jobs/${jobId}/drafts`, method: 'get' })
}

/** Land the human-ticked drafts into the rule source (the only AI ingress;
 * provenance is service-forced — the page never sends source/sourceRef). */
export function confirmAiJob(jobId: number, selectedStubs: MockStubCreatePayload[]): Promise<MockStubItem[]> {
  return request<MockStubItem[]>({ url: `/mock/jobs/${jobId}/confirm`, method: 'post', data: selectedStubs })
}

/** Throw a live job away (terminal states keep their sealed history). */
export function ignoreAiJob(jobId: number): Promise<MockAiJobItem> {
  return request<MockAiJobItem>({ url: `/mock/jobs/${jobId}`, method: 'delete' })
}
