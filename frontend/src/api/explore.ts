import { request } from './request'
import type { ExtractionEvent } from './taskEvents'
import { EXPLORE_TERMINAL_PREFIXES } from '@/utils/serverTextWire'




export interface ExploreSession {
  id: number
  projectId: number
  goalText: string
  startUrl: string
  status: number
  stepsUsed: number
  pagesVisited: number
  costCents: number
  reportMd: string | null
  createdBy: number | null
  budgetSnapshot: string | null
  envId?: number | null
  credRef?: string | null
  loginStateName?: string | null
}


export interface ExploreStep {
  seq: number
  stepAction: string | null
  stepResult: string | null
  screenshotKey: string | null
  domSnapshotKey: string | null
  llmThinkMd: string | null
}


export interface ExploreDetail {
  session: ExploreSession
  steps: ExploreStep[]
}


// loginStateName : web-shape login-state ledger handle; a
// namespace separate from credRef, never flowing into the mobile shape.
export interface ExploreCreateInput {
  projectId: number
  goalText: string
  startUrl: string
  confirm?: boolean
  envId?: number | null
  credRef?: string | null
  loginStateName?: string | null
}


export interface ExploreEstimate {
  estimatedTokens: number
  estimatedCostCents: number
  maxSteps: number
}


export interface ExploreCreateResult {
  session: ExploreSession | null
  estimate: ExploreEstimate | null
}


export interface ExploreCancelResult {
  sessionId: number
  action: string
}


export type ExploreToCasesResult = number[]

/** Paged session list (PageView list/total keys, page starts at 1).
 * optional sort pair — server-side order over the whole window through the
 * core column whitelist; blank keeps the historical id DESC order. */
export interface ExploreSessionSortQuery {
  sortBy?: string
  sortDir?: string
}

export function getExploreSessions(projectId: number, page = 1, size = 20, sort?: ExploreSessionSortQuery) {
  return request<{ list: ExploreSession[]; total: number }>({
    url: '/explore/sessions',
    method: 'get',
    params: { projectId, page, size, ...sort },
  })
}


export function postExploreSession(data: ExploreCreateInput) {
  return request<ExploreCreateResult>({
    url: '/explore/sessions',
    method: 'post',
    data,
  })
}


export function getExploreDetail(id: number) {
  return request<ExploreDetail>({
    url: `/explore/sessions/${id}`,
    method: 'get',
  })
}


export function getExploreScreenshotUrl(id: number, seq: number) {
  return request<string>({
    url: `/explore/sessions/${id}/steps/${seq}/screenshot`,
    method: 'get',
  })
}


export function postExploreToCases(id: number, seqs: number[]) {
  return request<ExploreToCasesResult>({
    url: `/explore/sessions/${id}/to-cases`,
    method: 'post',
    data: { seqs },
  })
}


export function deleteExploreSession(id: number) {
  return request<ExploreCancelResult>({
    url: `/explore/sessions/${id}`,
    method: 'delete',
  })
}

/**
 * Cursor window of one exploration run's process events admin
 * door `GET /api/v1/explore/sessions/{id}/process-events`). Rows share the
 * ExtractionEvent shape used by every other tail (one decoder, R40): the
 * cursor is the strictly increasing ledger row id and replays are idempotent.
 */
export async function fetchExploreProcessEvents(
  sessionId: number,
  cursor: number,
  limit?: number,
): Promise<ExtractionEvent[]> {
  const rows = await request<ExtractionEvent[]>({
    url: `/explore/sessions/${sessionId}/process-events`,
    method: 'get',
    params: limit === undefined ? { cursor } : { cursor, limit },
  })
  return Array.isArray(rows) ? rows : []
}

/**
 * Terminal-frame prefixes of the explore tail — single source is the server
 * contract test greps both Java sources so a writer-side wording drift
 * fails here instead of silently keeping the tail window open.
 */
export { EXPLORE_TERMINAL_PREFIXES }

export function isExploreTerminalRow(row: { summary: string }): boolean {
  return EXPLORE_TERMINAL_PREFIXES.some((prefix) => row.summary.startsWith(prefix))
}
