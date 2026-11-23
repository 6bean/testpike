package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * Admin mirror of the core flow-promotion result wire shape
 * flow promotion, {@code POST /api/v1/apitest/collections/{id}/promote-flow}
 * response payload).
 * <p>Field names must stay byte-identical to core
 * {@code com.sixbean.testpike.core.apitest.api.dto.FlowPromotionResultView} —
 * a drift deserializes to silent zero values on the bridge (same posture as
 * the single-request {@link PromotionResultDto}).
 *
 * @param caseId created regression case id (every member badge
 * points at this one case)
 * @param caseNo case number for plain-language echo
 * @param collectionId source collection (lineage dimension)
 * @param projectId owning project (H1 dimension, already guarded before dispatch)
 * @param promotedRequestCount members frozen into the script steps — the caller
 * asserts it against the order the rail flow draws
 */
public record FlowPromotionResultDto(long caseId, String caseNo, long collectionId,
                                     long projectId, int promotedRequestCount) {
}
