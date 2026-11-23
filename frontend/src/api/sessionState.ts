import { request } from './request'




export interface LoginStateRow {
  id: number
  projectId: number
  stateName: string
  roleScope: string | null
  accountLabel: string | null
  kind: string
  credentialId: number | null
  credKey: string | null
  stateObjectKey: string | null
  scopeOrigins: string[] | null
  sourceKind: number | null
  expiresAt: string | null
  lastVerifiedAt: string | null
  status: number | null
  remark: string | null
  createdAt: string | null
  updatedAt: string | null
  
  extraAuthRefs: ExtraAuthRefDto[] | null
}

/**
 * One combined-session header row (admin ExtraAuthRef mirror, ):
 * a vault credential reference plus the header it should ride in. The
 * valueTemplate is an optional prefix template in the shared {{CRED:key}}
 * grammar — never a stored secret; validation lives in core.
 */
export interface ExtraAuthRefDto {
  credKey: string
  into: string
  headerName: string
  valueTemplate?: string | null
}

/** Ledger list for one project; enabledOnly keeps the consumable rows. */
export function listSessionStates(
  projectId: number,
  enabledOnly = true,
): Promise<LoginStateRow[]> {
  return request<LoginStateRow[]>({
    url: '/session-states',
    method: 'get',
    params: { projectId, enabledOnly },
  })
}

/**
 * Registration payload mirroring admin LoginStateRegisterRequest component
 * names one by one. `secret` is write-only: the vault stores it encrypted and
 * no read path here ever returns it — ledger rows only carry the reference key.
 */
export interface LoginStateRegisterPayload {
  projectId: number
  stateName: string
  roleScope?: string | null
  accountLabel?: string | null
  kind: string
  credKey?: string | null
  secret?: string | null
  stateObjectKey?: string | null
  scopeOrigins?: string[] | null
  remark?: string | null
  /**
 * combined-session header list. Omitted (undefined/null) = "no
 * list supplied": a re-registration keeps the stored headers untouched.
 * An explicit array — even empty — rewrites the list.
 */
  extraAuthRefs?: ExtraAuthRefDto[] | null
}

/** Single ledger row by id. */
export function getSessionState(id: number): Promise<LoginStateRow> {
  return request<LoginStateRow>({
    url: `/session-states/${id}`,
    method: 'get',
  })
}

/** Register a new ledger row; returns the generated id. */
export function registerSessionState(payload: LoginStateRegisterPayload): Promise<number> {
  return request<number>({
    url: '/session-states',
    method: 'post',
    data: payload,
  })
}

/**
 * Edit one ledger row in place (admin PUT /session-states/{id}, manage door).
 * Same payload shape as registration; the row is addressed by id so a rename
 * lands on the same row. `secret` keeps the write-only semantics — blank means
 * the stored vault value is untouched. `extraAuthRefs` sent as an explicit
 * array (including an empty one) rewrites the combined-header list wholesale.
 */
export function updateSessionState(id: number, payload: LoginStateRegisterPayload): Promise<void> {
  return request<void>({
    url: `/session-states/${id}`,
    method: 'put',
    data: payload,
  })
}

/** Resolve the row a consumer would get for (project, stateName, roleScope). */
export function resolveSessionState(
  projectId: number,
  stateName?: string | null,
  roleScope?: string | null,
): Promise<LoginStateRow> {
  return request<LoginStateRow>({
    url: '/session-states/resolve',
    method: 'get',
    params: { projectId, stateName, roleScope },
  })
}

/** Enable or disable one row (disable takes the consumption gate down). */
export function setSessionStateEnabled(id: number, enabled: boolean): Promise<void> {
  return request<void>({
    url: `/session-states/${id}/enabled`,
    method: 'put',
    params: { enabled },
  })
}

/** Remove one ledger row. */
export function deleteSessionState(id: number): Promise<void> {
  return request<void>({
    url: `/session-states/${id}`,
    method: 'delete',
  })
}

/** Paged ledger door mirror (admin PageView = {list,total}). */
export interface LoginStatePage {
  list: LoginStateRow[]
  total: number
}

/** Query components of the paged door; null/blank entries are omitted. */
export interface LoginStatePageQuery {
  projectId: number
  keyword?: string | null
  status?: number | null
  sortBy?: string | null
  sortDir?: string | null
  page: number
  size: number
}

/**
 * Paged + filtered ledger segment (S-group worst-size door): keyword matches
 * the state name or the account label, status 1=enabled 0=disabled, sortBy is
 * refused server-side outside the ledger-column whitelist.
 */
export function listSessionStatesPage(q: LoginStatePageQuery): Promise<LoginStatePage> {
  return request<LoginStatePage>({
    url: '/session-states/page',
    method: 'get',
    params: {
      projectId: q.projectId,
      keyword: q.keyword || undefined,
      status: q.status ?? undefined,
      sortBy: q.sortBy || undefined,
      sortDir: q.sortDir || undefined,
      page: q.page,
      size: q.size,
    },
  })
}

/** "Test now" door: one synchronous session replay against a target. */
export interface ProbeOutcomeDto {
  runToken: string
  verdict: string
  verdictHuman: string
}

/** One masked trail row straight from the shared process-event ledger. */
export interface TestRunEventDto {
  /** Ledger row id, the strict-increasing replay cursor (wire name = core TaskEventRow). */
  cursor: number
  level: string
  summary: string
  detail: string | null
  landedAt: string
}

export function probeLoginState(
  id: number,
  url?: string,
  run?: { method: string; bodyTemplate?: string | null },
): Promise<ProbeOutcomeDto> {
  return request<ProbeOutcomeDto>({
    url: `/session-states/${id}/probe`,
    method: 'post',
    params: url ? { url } : {},
    data: run,
  })
}

export function listProbeEvents(id: number, runToken: string, cursor: number): Promise<TestRunEventDto[]> {
  return request<TestRunEventDto[]>({
    url: `/session-states/${id}/probe-events`,
    method: 'get',
    params: { runToken, cursor },
  })
}
