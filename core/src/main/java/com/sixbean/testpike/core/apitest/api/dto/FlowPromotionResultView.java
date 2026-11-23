package com.sixbean.testpike.core.apitest.api.dto;

/**
 * Result of promoting a whole linear request chain of one collection into a
 * single regression case flow promotion, 02 contract
 * POST /internal/apitest/collections/{id}/promote-flow).
 * <p>Field names must stay byte-identical to the admin mirror
 * {@code FlowPromotionResultDto} — drift deserializes to silent zero values on
 * the bridge.
 *
 * @param caseId the created regression case id (every member badge
 * points at this one case)
 * @param caseNo case number for plain-language echo
 * @param collectionId source collection (lineage dimension)
 * @param projectId owning project (H1 dimension, admin guard reuses)
 * @param promotedRequestCount members frozen into the script steps (echo so the
 * caller can assert drawn-order == promoted-count)
 */
public record FlowPromotionResultView(long caseId, String caseNo, long collectionId,
                                      long projectId, int promotedRequestCount) {
}
