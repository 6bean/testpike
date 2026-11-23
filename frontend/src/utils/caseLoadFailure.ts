/**
 * Single-source handoff for a case id that cannot be opened.
 *
 * The server types the rejection itself (CaseServiceImpl.caseNotFound): a real
 * soft-delete record gets a recycle-bin sentence, a cleaned sentinel gets a
 * not-located sentence that names the id and points at the case-library
 * search. This module decides only which door the frontend may offer, so an
 * empty recycle bin is never advertised as a recovery path, and it builds the
 * deep link through the existing case-library segment channel.
 *
 * Classification is marker-based on purpose: if the server wording drifts the
 * result falls back to the generic kind, which still shows the server sentence
 * verbatim and simply stops offering the recycle door (honest degradation, no
 * invented affordance).
 */

export type CaseLoadFailureKind = 'deleted' | 'not-located' | 'generic'

/** Router-shaped location, kept structural so the logic is testable without a DOM. */
export interface CaseLibraryLocation {
  path: string
  query: Record<string, string>
}

/** Marker of the soft-delete branch of the typed rejection. */
const DELETED_MARKER = '用例已被删除'
/** Marker of the sentinel branch (row never existed or was physically purged). */
const NOT_LOCATED_MARKER = '无此记录也无删除痕迹'

export function classifyCaseLoadFailure(message?: string | null): CaseLoadFailureKind {
  if (!message) return 'generic'
  if (message.includes(NOT_LOCATED_MARKER)) return 'not-located'
  if (message.includes(DELETED_MARKER)) return 'deleted'
  return 'generic'
}

/** The recycle-bin door is honest only for the branch backed by a delete record. */
export function offersRecycleBinAction(kind: CaseLoadFailureKind): boolean {
  return kind === 'deleted'
}

/**
 * Case-library route for the handoff: the project segment rides the deep link when
 * it is known (same rule as every other cross-page jump) and the recycle flag is
 * set only when the caller has established that a delete record exists.
 */
export function caseLibraryRoute(projectId?: number | string | null, recycle = false): CaseLibraryLocation {
  const query: Record<string, string> = {}
  if (projectId !== null && projectId !== undefined && projectId !== '') {
    query.projectId = String(projectId)
  }
  if (recycle) query.recycle = '1'
  return { path: '/cases', query }
}
