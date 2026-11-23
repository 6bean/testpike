import { request } from './request'
import { td } from '@/utils/dictLabel'

/**
 * Contract change-analysis review queue (AH batch ; admin
 * /api/v1/api-spec-change-analyses six-endpoint bridge).
 * statusLabel and the human-formatted times are derived in core only —
 * the front-end reads them straight through and never re-maps a second
 * ledger (docChangeAnalysis.ts F3 precedent, same posture).
 * Write gates (reanalyze / disposition / batch) are enforced server-side
 * against the ROW's owning project; the page only hides what would be
 * rejected anyway.
 */

/** One endpoint-level change row of a version diff (mirror of admin
 *  ApiSpecChangeItemView). Impact lists are already closed in core —
 *  an endpoint that matches nothing still shows up with empty lists
 *  (state facts truthfully, never fake a closure). */
export interface ApiSpecChangeItemView {
  /** contract endpoint key (upper METHOD + normalized path) */
  endpointKey: string
  /** add | changed | removed */
  changeType: string
  requestIds: number[]
  caseIds: number[]
  mockStubIds: number[]
}

/** Queue row (mirror of admin ApiSpecChangeAnalysisView — 13 fields). */
export interface ApiSpecChangeAnalysisView {
  id: number
  projectId: number
  oldSpecId: number
  newSpecId: number
  changes: ApiSpecChangeItemView[]
  /** 0 = pending review, 1 = handled */
  status: number
  statusLabel: string
  /** upload | fetch | poll — which entry landed the version */
  detectedSource: string
  createdBy: number | null
  createdAt: string
  handledBy: number | null
  handledAt: string | null
  handleNote: string | null
}

/** Change-type chip label (value domain single point: add/changed/removed;
 *  unknown values fall through raw — never blank). Dict keys mirror the F3
 *  doc-change family where the vocabulary overlaps. */
export function specChangeTypeLabel(v: string): string {
  const map: Record<string, string> = {
    add: 'dict.changeType.add',
    changed: 'dict.changeType.modify',
    removed: 'dict.changeType.delete',
  }
  return td(map[v] ?? v)
}

/** Paged review queue (status null = all, 0 = pending, 1 = handled; id DESC). */
export function listSpecChangeAnalyses(params: {
  projectId: number
  status?: number | null
  page?: number
  size?: number
}): Promise<{ list: ApiSpecChangeAnalysisView[]; total: number }> {
  return request({ url: '/api-spec-change-analyses', method: 'get', params })
}

/** One queue row by id (deep-link landing). */
export function getSpecChangeAnalysis(id: number): Promise<ApiSpecChangeAnalysisView> {
  return request<ApiSpecChangeAnalysisView>({ url: `/api-spec-change-analyses/${id}`, method: 'get' })
}

/** Queue row of a contract version (badge deep link on the inventory page). */
export function getSpecChangeAnalysisBySpec(newSpecId: number): Promise<ApiSpecChangeAnalysisView> {
  return request<ApiSpecChangeAnalysisView>({
    url: `/api-spec-change-analyses/by-spec/${newSpecId}`,
    method: 'get',
  })
}

/** Explicit re-analysis of a version (ops retry / subscription catch-up;
 *  detectedSource = upload | fetch | poll). */
export function reanalyzeSpec(newSpecId: number, detectedSource: string): Promise<ApiSpecChangeAnalysisView> {
  return request<ApiSpecChangeAnalysisView>({
    url: '/api-spec-change-analyses/reanalyze',
    method: 'post',
    data: { newSpecId, detectedSource },
  })
}

/** Disposition trace (pending → handled; note is REQUIRED here — core
 *  rejects a blank note with a plain-language 400, duplicate dispositions
 *  name the original handler). */
export function handleSpecChangeAnalysis(id: number, note: string): Promise<ApiSpecChangeAnalysisView> {
  return request<ApiSpecChangeAnalysisView>({
    url: `/api-spec-change-analyses/${id}/handle`,
    method: 'post',
    data: { note },
  })
}

/** Batch disposition of checked rows (one trace for all; all-or-nothing —
 *  any row the caller may not write rejects the whole call before anything
 *  lands, and already-handled rows are named in the rejection). */
export function handleSpecChangeAnalysisBatch(ids: number[], note: string): Promise<ApiSpecChangeAnalysisView[]> {
  return request<ApiSpecChangeAnalysisView[]>({
    url: '/api-spec-change-analyses/handle-batch',
    method: 'post',
    data: { ids, note },
  })
}
