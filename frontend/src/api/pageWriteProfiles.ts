import { request } from './request'

/**
 * Page write-risk ledger API (admin /api/v1/page-write-profiles mirror).
 * Rows are the durable facts the survey guard booked per page: the write
 * verbs seen inside the page-load baseline, the write requests attributed
 * to probe actions, the HTTP failures observed, and the sticky snapshot
 * downgrade once a page was caught autosaving.
 * The door is row-edit-free by design — risk rows are probe observations,
 * not user-editable data — so no create/update/delete wrapper exists here.
 * The single POST below is the conversion door: it files a booked failure as
 * a pending draft into the draft ledger (nothing lands until the human
 * confirms the card), touching no risk row.
 */

/** One guarded network fact as booked by the probe (params already redacted). */
export interface GuardedRequestFact {
  url: string | null
  method: string
  status?: number | null
  stepNo?: number
  /** failure classification booked at source: 1 = product defect (4xx broken
   * reference / 5xx failing endpoint), 3 = environment (reset / timeout).
   * Only present on netFailures rows. */
  errorType?: number | null
  /** requestfailed reason text for rows that never received an HTTP status */
  failureText?: string | null
}

/** One risk row as served; guard columns ride as decoded JSON lists. */
export interface PageWriteProfileRow {
  id: number
  projectId: number
  pageKey: string
  pageUrl: string
  /** write verbs observed inside the load baseline window */
  writeSurface: string[]
  /** write requests outside the baseline, attributed to probe actions */
  writeEvents: GuardedRequestFact[]
  /** HTTP failures booked by the network attribution window */
  netFailures: GuardedRequestFact[]
  /** sticky conservative downgrade: later probes stay read-only here */
  snapshotOnly: boolean
  lastRunId: number | null
  firstSeenAt: string | null
  lastSeenAt: string | null
}

/** Paged segment of the ledger (S-group worst-scale contract). */
export interface PageWriteProfilePage {
  list: PageWriteProfileRow[]
  total: number
  page: number
  pageSize: number
}

export interface PageWriteProfileQuery {
  projectId: number
  page?: number
  pageSize?: number
  keyword?: string
}

/** Risk pages of one project segment, newest activity first. */
export function listPageWriteProfiles(query: PageWriteProfileQuery): Promise<PageWriteProfilePage> {
  return request<PageWriteProfilePage>({
    url: '/page-write-profiles',
    method: 'get',
    params: query,
  })
}

/** The three keys the admin conversion door reads as request parameters;
 *  failureIndex is the position inside the row's decoded netFailures list
 *  (same order the panel lists). */
export interface CaseDraftFromFailureRequest {
  projectId: number
  profileId: number
  failureIndex: number
}

/**
 * File one booked network failure as a pending case draft; resolves to the
 * draft ledger id. Mirrors POST /api/v1/page-write-profiles/case-draft.
 * Nothing is written to the case base here — the card waits behind the
 * generic human confirmation door.
 */
export function toCaseDraftFromFailure(req: CaseDraftFromFailureRequest): Promise<number> {
  return request<number>({
    url: '/page-write-profiles/case-draft',
    method: 'post',
    params: { projectId: req.projectId, profileId: req.profileId, failureIndex: req.failureIndex },
  })
}
