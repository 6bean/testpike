import { request, type PageResult } from './request'

/**
 * Project-memory ledger API mirror ; 02 §2.16
 * /api/v1/project-memory six routes → admin ProjectMemoryController).
 * <p>Permission posture lives server-side only — read routes mount
 * perm:aichat:project-memory plus the H1 member guard, write routes reuse
 * SkillAccessGuard.assertCanManage (a refusal is a 403 with a human message
 * that never reaches core). The frontend renders zero copied checks: the
 * tab is mounted by the VIEW key, write buttons stay visible and the backend
 * answer is the authority. State machine, audit and recycle semantics are
 * single-source in core ProjectMemoryLedgerService.
 */

/** Ledger row (admin ProjectMemoryView record mirror, field names aligned 1:1). */
export interface ProjectMemoryItem {
  id: number
  /** owning project — entries never cross projects */
  projectId: number
  /** convention text injected into assembly */
  content: string
  /** traceability anchors JSON, e.g. [{"type":"manual"}] or [{"type":"case","id":12}] */
  sourceRef: string
  /** 1 = active (injected), 0 = operator-disabled, 2 = distilled awaiting confirmation, 3 = rejected through the ledger */
  injectStatus: number
  /** times this entry took part in assembly (read-only) */
  hitCount: number
  createdAt: string
  updatedAt: string
  /**
   * Newest still-open proposal card anchored to this row (null = the row has
   * no confirmation door). The confirm button rides this id through the SHARED
   * draft door (aiChat requestDraftConfirmTicket → applyRepairDraft) — no
   * second confirmation protocol is born here.
   */
  draftId?: number | null
}

/** Manual fixed-injection entry payload (blank content / unknown project refusals are core single-source). */
export interface ProjectMemoryCreatePayload {
  projectId: number
  content: string
}

/** Ledger page query (projectId required — the ledger is project-scoped). */
export interface ProjectMemoryQuery {
  projectId: number
  keyword?: string
  /** 1 = active, 0 = disabled, 2 = pending confirmation, 3 = rejected; omit = all */
  injectStatus?: number | null
  page: number
  size: number
}

/** Paged ledger rows with keyword/injection-state filters. */
export function listProjectMemory(q: ProjectMemoryQuery): Promise<PageResult<ProjectMemoryItem>> {
  return request<PageResult<ProjectMemoryItem>>({ url: '/project-memory', method: 'get', params: q })
}

/** Create a manual entry (lands effective — it is a human-written fact). */
export function createProjectMemory(payload: ProjectMemoryCreatePayload): Promise<ProjectMemoryItem> {
  return request<ProjectMemoryItem>({ url: '/project-memory', method: 'post', data: payload })
}

/** Enable/disable; a disabled entry is absent from the next assembly through the same consumption door. */
export function setProjectMemoryStatus(id: number, projectId: number, status: number): Promise<ProjectMemoryItem> {
  return request<ProjectMemoryItem>({ url: `/project-memory/${id}/status`, method: 'put', params: { projectId, status } })
}

/** Reject a distilled entry awaiting confirmation (pending → rejected, audited core-side; never injected, stays visible in the ledger). */
export function rejectProjectMemory(id: number, projectId: number): Promise<ProjectMemoryItem> {
  return request<ProjectMemoryItem>({ url: `/project-memory/${id}/reject`, method: 'post', params: { projectId } })
}

/** Soft-delete into the recycle bin (stale-conflict messages are core single-source). */
export function deleteProjectMemory(id: number, projectId: number): Promise<void> {
  return request<void>({ url: `/project-memory/${id}`, method: 'delete', params: { projectId } })
}

/** Recycle bin page (deleted entries, project-scoped, paged). */
export function listProjectMemoryRecycleBin(q: {
  projectId: number
  page: number
  size: number
}): Promise<PageResult<ProjectMemoryItem>> {
  return request<PageResult<ProjectMemoryItem>>({ url: '/project-memory/recycle-bin', method: 'get', params: q })
}

/** Restore from the recycle bin (previous injection state returns). */
export function restoreProjectMemory(id: number, projectId: number): Promise<ProjectMemoryItem> {
  return request<ProjectMemoryItem>({ url: `/project-memory/${id}/restore`, method: 'post', params: { projectId } })
}
