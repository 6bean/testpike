package com.sixbean.testpike.core.apitest.api.dto;

/**
 * Promotion result of a tuned sandbox request into a regression case (
 * promotion chain, 02 contract POST /internal/apitest/requests/{id}/promote).
 *
 * @param caseId the created regression case id (badge target written back
 * onto tp_apitest_request.promoted_case_id)
 * @param caseNo case number for plain-language echo (the 409 repeat-promotion
 * answer names it so the user finds the existing asset)
 * @param requestId source sandbox request id
 * @param projectId case/request owning project (H1 dimension, admin guard reuses)
 */
public record PromotionResultView(long caseId, String caseNo, long requestId, long projectId) {
}
