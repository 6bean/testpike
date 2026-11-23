package com.sixbean.testpike.core.cases.api.dto;

/**
 * Review action result (POST /cases/{id}/review response data: caseId/status/version).
 *
 * @param caseId Case ID.
 * @param status Status code after review (9 states).
 * @param version Optimistic lock version number after review.
 */
public record ReviewResult(long caseId, int status, int version) {
}
