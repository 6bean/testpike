import { request } from './request'
import type { ExtractionEvent } from './taskEvents'
import { SURVEY_TERMINAL_PREFIXES } from '@/utils/serverTextWire'

/**
 * Web element ledger API (WE batch S0 , admin /api/v1/web-elements
 * contract mirror). Field names follow the admin record components verbatim
 * (Jackson record names on the wire) — never invent aliases here.
 * All validation and ownership gates live server-side (core webelement
 * service); this layer and the page only mirror UX intercepts.
 */

/** One locator anchor; the caller's order is the runtime try order. */
export interface WebElementAnchor {
  /** css | xpath | text | placeholder | alt | testid | rel | role | label (server-enforced set) */
  type: string
  value: string
  /** 0..1 quality weight (server-enforced bounds) */
  strength?: number | null
  note?: string | null
}

/** Ledger row as stored. state: 0=candidate 1=confirmed 2=stale 3=retired. */
export interface WebElementLedgerRow {
  id: number
  projectId: number
  name: string
  pageKey: string
  anchors: WebElementAnchor[]
  state: number
  /** execution-feedback recomputed hit quality, not a model self-score */
  confidence: number
  hitCount: number
  missCount: number
  /** optimistic lock consumed by anchor and state writes */
  revision: number
  createdAt?: string | null
  updatedAt?: string | null
}

/** Page-folded roll-up with lifecycle counts (the ledger default view). */
export interface WebElementPageGroup {
  pageKey: string
  elements: WebElementLedgerRow[]
  total: number
  candidateCount: number
  confirmedCount: number
  staleCount: number
  retiredCount: number
}

export const WEB_ELEMENT_ANCHOR_TYPES = [
  'css', 'xpath', 'text', 'placeholder', 'alt', 'testid', 'rel', 'role', 'label',
] as const

export const WEB_ELEMENT_STATES = [0, 1, 2, 3] as const

export function webElementPages(projectId: number): Promise<WebElementPageGroup[]> {
  return request({ url: '/web-elements/pages', method: 'get', params: { projectId } })
}

export function webElementList(projectId: number, pageKey?: string): Promise<WebElementLedgerRow[]> {
  return request({
    url: '/web-elements',
    method: 'get',
    params: pageKey ? { projectId, pageKey } : { projectId },
  })
}

export function webElementGet(id: number): Promise<WebElementLedgerRow> {
  return request({ url: `/web-elements/${id}`, method: 'get' })
}

export function webElementCreate(payload: {
  projectId: number
  name: string
  pageUrl: string
  anchors: WebElementAnchor[]
}): Promise<WebElementLedgerRow> {
  return request({ url: '/web-elements', method: 'post', data: payload })
}

/** Replace the ordered anchor set; stale revisions refuse server-side. */
export function webElementUpdateAnchors(
  id: number,
  anchors: WebElementAnchor[],
  expectedRevision: number,
): Promise<WebElementLedgerRow> {
  return request({
    url: `/web-elements/${id}/anchors`,
    method: 'put',
    data: { anchors, expectedRevision },
  })
}

/**
 * Rename one ledger element . Script references address
 * elements by name, so this is the manual fix door for a mis-named row; the
 * same-page duplicate and the stale revision token refuse server-side.
 */
export function webElementRename(
  id: number,
  name: string,
  expectedRevision: number,
): Promise<WebElementLedgerRow> {
  return request({
    url: `/web-elements/${id}/name`,
    method: 'put',
    data: { name, expectedRevision },
  })
}

/**
 * One self-heal evidence row (tp_exec_step_heal_evidence, admin record
 * mirror; §8.4). healed/firstAnchorFailed are true by channel contract
 * (the write door only accepts rescue rows), kept in the payload as audit.
 */
export interface StepHealEvidenceRow {
  id: number
  taskId: number | null
  elementId: number | null
  stepNo: number | null
  healed: boolean | null
  firstAnchorFailed: boolean | null
  fromAnchorType: string | null
  fromAnchorValue: string | null
  usedAnchorType: string | null
  usedAnchorValue: string | null
  viewport: string | null
  ledgerRevision: number | null
  similarityScore: number | null
  createdAt: string | null
}

/** Paged self-heal evidence result; projectId is filled only by the task door. */
export interface HealEvidencePage {
  items: StepHealEvidenceRow[]
  total: number
  page: number
  pageSize: number
  projectId: number | null
}

/** Heal evidence of one execution task (report step badges, single bounded page). */
export function webElementHealEvidenceByTask(taskId: number): Promise<HealEvidencePage> {
  return request({ url: '/web-elements/heal-evidence', method: 'get', params: { taskId } })
}

/** Recent heals of one ledger element, newest first (detail drawer, bounded page). */
export function webElementHealEvidenceByElement(
  id: number,
  page = 1,
  pageSize = 10,
): Promise<HealEvidencePage> {
  return request({ url: `/web-elements/${id}/heal-evidence`, method: 'get', params: { page, pageSize } })
}

/** One referencing-case row: a live case whose step anchor evidence names this element (mirror of the admin record). */
export interface ReferencingCaseRow {
  caseId: number
  caseNo: string
  caseTitle: string
  caseVersion: number
  stepNo: number
  stepDescription: string
}

/** Bounded referencing-case page; an unknown element answers an empty page with null elementName/projectId. */
export interface ReferencingCasePage {
  items: ReferencingCaseRow[]
  total: number
  page: number
  pageSize: number
  elementName: string | null
  projectId: number | null
}

/** Cases referencing one ledger element (detail drawer, bounded page over the anchor ledger). */
export function webElementReferencingCasesByElement(
  id: number,
  page = 1,
  pageSize = 10,
): Promise<ReferencingCasePage> {
  return request({ url: `/web-elements/${id}/referencing-cases`, method: 'get', params: { page, pageSize } })
}

/** Lifecycle move (confirm / stale / retire) under the same CAS posture. */
export function webElementChangeState(
  id: number,
  targetState: number,
  expectedRevision: number,
): Promise<WebElementLedgerRow> {
  return request({
    url: `/web-elements/${id}/state`,
    method: 'put',
    params: { targetState, expectedRevision },
  })
}

/** Survey acknowledgement (admin bridge mirror of the core record). */
export interface WebElementSurveyResult {
  sessionId: number
  startUrl: string
}

/**
 * Dispatch one manual "survey this page" task (WE-S4 segment P4). The survey
 * runs asynchronously through the explore channel; this door answers the
 * session handle immediately — candidate rows land in the ledger as the
 * collection progresses, refresh to watch them arrive.
 */
export function webElementSurvey(payload: {
  projectId: number
  startUrl: string
}): Promise<WebElementSurveyResult> {
  return request({ url: '/web-elements/survey', method: 'post', data: payload })
}

/**
 * segment c3 (R62): cursor-window GET of one survey run's process tail,
 * mirroring the admin read door GET
 * /api/v1/web-elements/survey/{sessionId}/process-events. The door answers
 * { projectId, events } — rows share the ExtractionEvent shape used by every
 * other tail (one decoder, one merge rule, no second copy, R40). The limit
 * stays server-side when omitted (ai.stream.chunk-size fallback).
 */
export async function fetchSurveyProcessEvents(
  sessionId: number,
  cursor: number,
  limit?: number,
): Promise<ExtractionEvent[]> {
  const page = await request<{ projectId: number; events: ExtractionEvent[] }>({
    url: `/web-elements/survey/${sessionId}/process-events`,
    method: 'get',
    params: limit === undefined ? { cursor } : { cursor, limit },
  })
  return Array.isArray(page?.events) ? page.events : []
}

// Terminal-frame prefixes of the survey tail live in utils/serverTextWire
// (wire-contract literals, outside the P7e display sweep); the four end
// states are emitted by core ExploreServiceImpl and grepped by the survey
// tail contract test.

export function isSurveyTerminalRow(row: { summary: string }): boolean {
  return SURVEY_TERMINAL_PREFIXES.some((prefix) => row.summary.startsWith(prefix))
}

/**
 * Idempotency account of one census-posting round ( piece 4, mirror of
 * admin CensusPostingBridgeResult — record names verbatim). Both posting
 * kinds share this shape; per kind only the meaningful counts are shown
 * (ledger path fills alreadyLedgered, ref path fills scriptsScanned and
 * skippedNotLedgered; the other side answers 0 by construction).
 */
export interface CensusPostingResult {
  /** census entries the round looked at */
  entries: number
  /** new draft cards filed by this round */
  posted: number
  /** entries that already had a pending card — no duplicate filed */
  alreadyPending: number
  /** entries already covered by a confirmed ledger row */
  alreadyLedgered: number
  /** scripts scanned (reference-replacement path only) */
  scriptsScanned: number
  /** inline references skipped because the element is not in the library yet */
  skippedNotLedgered: number
}

/**
 * File census → ledger-creation draft cards for the current project segment
 * . Synchronous counting door: it only files cards, landing
 * still rides the draft-card human confirmation — no business write here.
 */
export function webElementCensusLedgerDrafts(projectId: number): Promise<CensusPostingResult> {
  return request({ url: '/web-elements/census/ledger-drafts', method: 'post', data: { projectId } })
}

/** Census → script reference-replacement draft cards, same gate posture. */
export function webElementCensusScriptRefDrafts(projectId: number): Promise<CensusPostingResult> {
  return request({ url: '/web-elements/census/script-ref-drafts', method: 'post', data: { projectId } })
}

/**
 * WE-S7 step-health reading for one ledger row (mirror of the admin
 * GET /web-elements/health record). The server owns the green/yellow/red
 * judgement from the rolling 7-day miss-rate windows (element.health.*
 * mark when aboutToBreak is set — no recomputation on this side (R40).
 */
export interface WebElementHealthRow {
  elementId: number
  reached: boolean
  missRatePercent: number
  badge: 'green' | 'yellow' | 'red' | 'none'
  aboutToBreak: boolean
}

/**
 * One storming page (mirror of the admin GET /web-elements/storms record):
 * elements that went stale together inside the rolling window plus the
 * elements currently marked about to break. The two lists stay side by
 */
export interface RevisionStormRow {
  pageKey: string
  staleElementIds: number[]
  aboutToBreakElementIds: number[]
}

/** Health readings of the project segment (pageKey narrows to one page). */
export function webElementHealth(projectId: number, pageKey?: string): Promise<WebElementHealthRow[]> {
  return request({
    url: '/web-elements/health',
    method: 'get',
    params: pageKey ? { projectId, pageKey } : { projectId },
  })
}

/** Revision storms of the project segment, one row per storming page. */
export function webElementStorms(projectId: number): Promise<RevisionStormRow[]> {
  return request({ url: '/web-elements/storms', method: 'get', params: { projectId } })
}
