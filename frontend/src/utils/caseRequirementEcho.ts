/**
 *
 * Why: generated cases auto-link their requirement points inside the
 * generation flow, but the block gave no provenance label and no way to jump
 * to the requirement ledger row. These pure helpers keep both decisions in
 * one place so the vitest contract can pin them; the view must not
 * re-derive either rule (guard: tests/ui/caseRequirementEcho.test.ts).
 *
 * Provenance note: tp_case_requirement records only the operator id, not the
 * channel, so "generation auto-link" is asserted from the case's own
 * creation source (tp_test_case.source, 1 = AI generated). A manual link
 * added later onto a generated case therefore over-claims; the follow-up
 * ticket for a per-row provenance column is booked in the chain progress
 * notes — never silently dropped.
 */

/** tp_test_case.source value for cases produced by the AI generation flow. */
export const CASE_SOURCE_AI_GENERATED = 1

/** True when requirement rows of this case originate from generation auto-link. */
export function isAutoLinkedRequirement(caseSource: number | null | undefined): boolean {
  return caseSource === CASE_SOURCE_AI_GENERATED
}

/** Vue Router location for the requirement ledger filtered to one point.
 * Rides the existing ids filter channel of RequirementListView (URL is the
 * single source of list state); null fails closed for invalid ids so the
 * view can skip navigation instead of emitting a dead link.
 * The case's own projectId rides the URL too (CoverageMatrixView precedent,
 * useProjectPageContext URL-first chain): without it the landing segment is
 * whatever the header happens to hold — an unselected or foreign project
 * renders a misleading empty list even though the row exists.
 * Missing/invalid projectId falls back to the header context instead of
 * fabricating one. */
export function requirementLedgerDeepLink(
  requirementId: number,
  projectId?: number | null,
): { path: string; query: Record<string, string> } | null {
  if (!Number.isInteger(requirementId) || requirementId <= 0) return null
  const query: Record<string, string> = { ids: String(requirementId) }
  if (Number.isInteger(projectId) && (projectId as number) > 0) {
    query.projectId = String(projectId)
  }
  return { path: '/requirements', query }
}
