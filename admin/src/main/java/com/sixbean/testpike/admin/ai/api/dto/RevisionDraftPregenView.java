package com.sixbean.testpike.admin.ai.api.dto;

/**
 * Pre-generation receipt view (admin bridge; name-for-name mirror of core
 * RevisionDraftPregen — cross-service deserialization rides field names).
 *
 * @param sessionId        the find-or-created deep-linked session, null when
 *                         nothing was ever dispatched and no session was needed
 * @param skippedExisting  requirements already covered by a pending
 *                         REQUIREMENT_WRITE draft or an earlier anchored echo
 */
public record RevisionDraftPregenView(long analysisId, Long sessionId,
                                      int dispatched, int skippedExisting,
                                      int skippedForeign, int skippedMissing,
                                      int skippedNoContent) {
}
