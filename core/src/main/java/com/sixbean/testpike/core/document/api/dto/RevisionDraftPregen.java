package com.sixbean.testpike.core.document.api.dto;

/**
 * Receipt of one revision-draft pre-generation pass over a pending document
 * change analysis . The numbers are a ledger of the
 * door at the moment of opening: every requirement named by the analysis is
 * either dispatched as one dialog run (exactly once, idempotent double-anchor)
 * or skipped with a named reason — never silently dropped.
 *
 * @param analysisId the analysis snapshot the pass ran over
 * @param sessionId the find-or-created deep-link session (source 3,
 * anchored on the analysis id); null when nothing was
 * ever dispatched and no session was needed
 * @param dispatched runs opened by this pass (user echoes sealed)
 * @param skippedExisting requirements already covered by a pending
 * REQUIREMENT_WRITE draft or an earlier user echo
 * @param skippedForeign requirements owned by another project than the
 * analysis (attribution guard, refused before any run)
 * @param skippedMissing requirements soft-deleted after the analysis was
 * taken (the snapshot id no longer resolves to a live row)
 * @param skippedNoContent change items whose new chapter content is empty
 * (deleted chapter) — honestly zero cards, the AI is
 * never asked to draft against a blank
 */
public record RevisionDraftPregen(long analysisId, Long sessionId,
                                  int dispatched, int skippedExisting,
                                  int skippedForeign, int skippedMissing,
                                  int skippedNoContent) {
}
