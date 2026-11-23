package com.sixbean.testpike.admin.journey.api.dto;

/**
 * One planned segment of a journey (form row in the ordered segment table).
 * Field names mirror core {@code JourneySegmentInfo} one-to-one.
 */
public record JourneySegmentView(long id, int seqNo, long projectId, String targetKind,
                                 Long assetRowId, Long caseId, String paramsOverrideJson) {
}
