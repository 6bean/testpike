import { request } from './request'
import { td } from '@/utils/dictLabel'




export interface DocChangeItemView {
  
  chapter: string | null
  /** add / modify / delete */
  changeType: string
  requirementIds: number[]
  caseIds: number[]
  
  chapterLocatable: boolean
}


export interface DocChangeAnalysisView {
  id: number
  projectId: number
  oldDocumentId: number
  newDocumentId: number
  changes: DocChangeItemView[]
  
  chapterLocatable: boolean
  
  status: number
  statusLabel: string
  createdBy: number | null
  createdAt: string
  handledBy: number | null
  handledAt: string | null
  handleNote: string | null
}


export function changeTypeLabel(v: string): string {
  const map: Record<string, string> = {
    add: 'dict.changeType.add',
    modify: 'dict.changeType.modify',
    delete: 'dict.changeType.delete',
  }
  return td(map[v] ?? v)
}


export function reanalyzeDocument(newDocumentId: number): Promise<DocChangeAnalysisView> {
  return request<DocChangeAnalysisView>({
    url: '/doc-change-analyses/reanalyze',
    method: 'post',
    data: { newDocumentId },
  })
}


export function listDocChangeAnalyses(params: {
  projectId: number
  status?: number | null
  page?: number
  size?: number
}): Promise<{ list: DocChangeAnalysisView[]; total: number }> {
  return request({ url: '/doc-change-analyses', method: 'get', params })
}

/** Disposition note (pending → handled, note optional; duplicate handling is
 *  rejected by the core state machine with a plain business message). */
export function handleDocChangeAnalysis(id: number, note?: string): Promise<DocChangeAnalysisView> {
  return request<DocChangeAnalysisView>({
    url: `/doc-change-analyses/${id}/handle`,
    method: 'post',
    data: { note: note || null },
  })
}

/** Revision-draft pre-generation receipt (name-for-name mirror of the admin
 *  bridge DTO; sessionId = the conversation holding the pending confirmations,
 *  null when nothing was dispatched. skipped* counts never write a requirement
 *  — the confirmation door stays the human one-click accept in chat). */
export interface RevisionDraftPregenView {
  analysisId: number
  sessionId: number | null
  dispatched: number
  skippedExisting: number
  skippedForeign: number
  skippedMissing: number
  skippedNoContent: number
}

/** Pre-generate anchored revision drafts for one analysis row (same write
 *  gate as handle — the row's own project; drafts stay pending confirmation). */
export function pregenDrafts(id: number): Promise<RevisionDraftPregenView> {
  return request<RevisionDraftPregenView>({
    url: `/doc-change-analyses/${id}/pregen-drafts`,
    method: 'post',
  })
}
