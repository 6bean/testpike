import { td } from '../utils/dictLabel'
import { request, type PageResult } from './request'




export interface RequirementItem {
  id: number
  projectId: number
  
  source: number
  sourceId?: string | null
  title: string
  acceptanceCriteria?: string | null
  
  status: number
  chapter?: string | null
  createdBy?: number
  createdAt?: string
  updatedAt?: string
  /** Row revision for the optimistic lock (admin RequirementView echo). */
  version?: number
  /** Stable content fingerprint computed by the database (generated column,
   *  V641 requirement_fingerprint function is the only implementation);
   *  identical content re-extracted replays to the same value. */
  fingerprint?: string | null
  /** Recorded provenance of the point (admin RequirementView mirror): the source
   *  document, its chunk rows and the verbatim excerpt captured at adoption.
   *  Null for hand-made points and legacy rows - the view-original door then
   *  renders disabled with a readable reason instead of a dead link. */
  sourceAnchor?: RequirementSourceAnchor | null
}

/** Mirror of admin RequirementSourceAnchorView (core evidence anchor, field for field). */
export interface RequirementSourceAnchor {
  documentId?: number | null
  chunkIds?: number[]
  evidence?: string | null
}


export interface RequirementQuery {
  projectId: number
  status?: number
  keyword?: string
  page?: number
  size?: number
  /** Server-side sort key (core whitelist; undefined = default order) */
  sortBy?: string
  /** asc / desc — anything else falls back to descending */
  sortDir?: string
  /** Id allow-list for the structured deep-link hand-off (sent comma-separated).
   *  The list page's exact-id filter also lands here as the raw comma-separated text;
   *  the server-side parser fails closed on a malformed token (never a dropped id). */
  ids?: number[] | string
  /** Exact content-fingerprint filter */
  fingerprint?: string
}


export interface CreateRequirementPayload {
  source?: number
  sourceId?: string
  title: string
  acceptanceCriteria?: string
  chapter?: string
}


export interface UpdateRequirementPayload {
  title?: string
  source?: number
  sourceId?: string
  acceptanceCriteria?: string
  chapter?: string
  status?: number
  /** Optimistic-lock baseline: stale values are refused with 40901 (core single source). */
  expectedVersion?: number
}


export interface RequirementCaseBrief {
  id: number
  caseNo: string
  title: string
  status: number
  priority: number
  confidence: number
}


export interface LinkResult {
  requirementId: number
  caseIds: number[]
  linkedCount: number
}

export function listRequirements(params: RequirementQuery): Promise<PageResult<RequirementItem>> {
  const { ids, ...rest } = params
  // ids cross the HTTP boundary comma-separated (the server-side parser fails closed
  // on a malformed token); an empty list is sent as no filter at all, not as `ids=`.
  const idsText = Array.isArray(ids)
    ? (ids.length > 0 ? ids.join(',') : undefined)
    : (typeof ids === 'string' && ids.trim() !== '' ? ids.trim() : undefined)
  return request<PageResult<RequirementItem>>({
    url: `/projects/${params.projectId}/requirements`,
    method: 'get',
    params: { ...rest, ...(idsText ? { ids: idsText } : {}) },
  })
}

/** Case-gap page: requirements of the project with no covering live case.
 *  Server-computed; refuses with a settings pointer while the AI advice
 *  switch is off (the caller treats a refusal as "chips stay absent"). */
export function listUncoveredRequirements(projectId: number, page = 1, size = 10): Promise<PageResult<RequirementItem>> {
  return request<PageResult<RequirementItem>>({ url: `/projects/${projectId}/requirements/uncovered`, method: 'get', params: { page, size } })
}

export function createRequirement(projectId: number, data: CreateRequirementPayload): Promise<RequirementItem> {
  return request<RequirementItem>({ url: `/projects/${projectId}/requirements`, method: 'post', data })
}

export function updateRequirement(projectId: number, reqId: number, data: UpdateRequirementPayload): Promise<RequirementItem> {
  return request<RequirementItem>({ url: `/projects/${projectId}/requirements/${reqId}`, method: 'put', data })
}

export function deleteRequirement(projectId: number, reqId: number): Promise<number> {
  return request<number>({ url: `/projects/${projectId}/requirements/${reqId}`, method: 'delete' })
}


export function linkCases(requirementId: number, caseIds: number[]): Promise<LinkResult> {
  return request<LinkResult>({ url: `/requirements/${requirementId}/cases`, method: 'post', data: { caseIds } })
}


export function unlinkCase(requirementId: number, caseId: number): Promise<number> {
  return request<number>({ url: `/requirements/${requirementId}/cases/${caseId}`, method: 'delete' })
}


export function listLinkedCases(requirementId: number): Promise<RequirementCaseBrief[]> {
  return request<RequirementCaseBrief[]>({ url: `/requirements/${requirementId}/cases`, method: 'get' })
}


export function listCaseRequirements(caseId: number): Promise<RequirementItem[]> {
  return request<RequirementItem[]>({ url: `/cases/${caseId}/requirements`, method: 'get' })
}


export function unlinkCaseRequirement(caseId: number, reqId: number): Promise<number> {
  return request<number>({ url: `/cases/${caseId}/requirements/${reqId}`, method: 'delete' })
}


export const REQUIREMENT_SOURCE: Record<number, { label: string; tag: 'primary' | 'warning' | 'info' }> = {
  1: { get label() { return td('dict.req.source.jira') }, tag: 'primary' },
  2: { get label() { return td('dict.req.source.docExtract') }, tag: 'warning' },
  3: { get label() { return td('dict.req.source.manual') }, tag: 'info' },
}


export const REQUIREMENT_STATUS: Record<number, { label: string; tag: 'success' | 'warning' | 'danger' | 'info' }> = {
  1: { get label() { return td('dict.req.status.active') }, tag: 'success' },
  2: { get label() { return td('dict.req.status.changed') }, tag: 'warning' },
  3: { get label() { return td('dict.req.status.retired') }, tag: 'info' },
}

/** Requirement version chain snapshot row (AL batch read surface; mirrors
 *  admin GET /requirements/{id}/versions RequirementVersionView, version desc). */
import type { VersionChainFields } from './versionChain'

export interface RequirementVersionItem extends VersionChainFields {
  requirementId: number
}

/** Version chain for the ledger page history dialog (project member, cross-project 403). */
export function listRequirementVersions(id: number): Promise<RequirementVersionItem[]> {
  return request<RequirementVersionItem[]>({ url: `/requirements/${id}/versions`, method: 'get' })
}
