import { request } from './request'

/**
 * Site profile ledger API (admin /api/v1/site-profiles contract mirror).
 * Field names follow the admin record components verbatim — the server
 * derives the scope prefix from startUrl and never accepts it from the
 * browser. Ownership gates, optimistic locking (revision) and the
 * delete-keeps-history rule all live server-side; this layer only mirrors
 * the doors for the profile drawer.
 */

/** One profile row as stored. */
export interface SiteProfileRow {
  id: number
  projectId: number
  name: string
  startUrl: string
  /** server-derived segment-aligned scope ("/pr/", "/" for the whole host) */
  scopePrefix: string
  excludeGlobs: string[]
  ignoreParams: string[]
  verbsGuardEnabled: boolean
  /** per-profile budget overrides keyed by the system config suffix */
  budgetOverrides: Record<string, unknown>
  loginStateName: string | null
  enabled: boolean
  /** optimistic lock token; forward it unchanged on the next edit */
  revision: number
  updatedAt: string | null
}

/** Browser write payload; null list/boolean/map fields keep server defaults. */
export interface SiteProfilePayload {
  projectId: number
  name: string
  startUrl: string
  excludeGlobs?: string[] | null
  ignoreParams?: string[] | null
  verbsGuardEnabled?: boolean | null
  budgetOverrides?: Record<string, unknown> | null
  loginStateName?: string | null
  enabled?: boolean | null
  revision?: number | null
}

/** Profiles of one project segment, newest activity first. */
export function listSiteProfiles(projectId: number): Promise<SiteProfileRow[]> {
  return request<SiteProfileRow[]>({
    url: '/site-profiles',
    method: 'get',
    params: { projectId },
  })
}

/** One profile by id (membership checked against the row's own project). */
export function getSiteProfile(id: number): Promise<SiteProfileRow> {
  return request<SiteProfileRow>({
    url: `/site-profiles/${id}`,
    method: 'get',
  })
}

/** Create a profile inside the project picked in the header. */
export function createSiteProfile(payload: SiteProfilePayload): Promise<SiteProfileRow> {
  return request<SiteProfileRow>({
    url: '/site-profiles',
    method: 'post',
    data: payload,
  })
}

/** Update a profile; the payload carries the revision read at open time. */
export function updateSiteProfile(id: number, payload: SiteProfilePayload): Promise<SiteProfileRow> {
  return request<SiteProfileRow>({
    url: `/site-profiles/${id}`,
    method: 'put',
    data: payload,
  })
}

/** Retire a profile row. Survey runs and filed elements stay kept. */
export function deleteSiteProfile(id: number): Promise<void> {
  return request<void>({
    url: `/site-profiles/${id}`,
    method: 'delete',
  })
}

/**
 * One whole-site survey run as the drawer lists it (admin
 * /api/v1/site-profiles/runs contract mirror). Counters are the honest live
 * values the server ledger keeps while the crawl runs; a row is terminal
 * once its status is no longer "running".
 */
export interface SiteSurveyRunRow {
  runId: number
  profileId: number
  profileName: string
  startUrl: string
  status: string
  pagesTotal: number
  pagesOk: number
  pagesFailed: number
  ledgerUpserted: number
  namingDegraded: number
  exploreSessionId: number | null
  failureNotes: string[]
  startedAt: string | null
  finishedAt: string | null
}

/** Survey-record rows of one project segment, newest first (capped server-side). */
export function listSiteSurveyRuns(projectId: number, limit = 50): Promise<SiteSurveyRunRow[]> {
  return request<SiteSurveyRunRow[]>({
    url: '/site-profiles/runs',
    method: 'get',
    params: { projectId, limit },
  })
}

/** Dispatch a whole-site survey crawl for one profile; resolves with the new run id. */
export function dispatchSiteSurveyRun(profileId: number): Promise<number> {
  return request<number>({
    url: `/site-profiles/${profileId}/survey`,
    method: 'post',
  })
}
