package com.sixbean.testpike.core.journey.api.dto;

/**
 * Stored segment row of a journey plan, ordered by {@code seqNo}. Same locator semantics
 * as {@link JourneySegmentInput}; ids are resolved after persistence.
 */
public record JourneySegmentInfo(long id, int seqNo, long projectId, String targetKind,
                                 Long assetRowId, Long caseId, String paramsOverrideJson) {
}
