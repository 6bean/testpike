package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * Admin mirror of the core promotion result wire shape ( sandbox-to-case
 * chain, {@code POST /api/v1/apitest/requests/{id}/promote} response payload).
 * <p>Field names must stay byte-identical to core
 * {@code com.sixbean.testpike.core.apitest.api.dto.PromotionResultView} —
 * a drift deserializes to silent zero values on the bridge (contract test
 * PromotionBridgeTest pins both the wire round-trip and the four-component shape).
 *
 * @param caseId created regression case id (badge written back onto the request)
 * @param caseNo case number for plain-language echo (repeat promotion names it)
 * @param requestId source sandbox request id
 * @param projectId owning project (H1 dimension, already guarded before dispatch)
 */
public record PromotionResultDto(long caseId, String caseNo, long requestId, long projectId) {
}
