package com.sixbean.testpike.admin.journey.api.dto;

/**
 * One run-segment row: which project's batch this segment expanded into and how it ended.
 * failureCode names the reason (PARAMS_MISSING / LAUNCH_FAILED / batch terminal failure)
 * so the timeline page can point straight at the failing batch detail.
 * Field names mirror core {@code JourneyRunSegmentInfo} one-to-one.
 */
public record JourneyRunSegmentView(long id, long segmentId, int seqNo, long projectId,
                                    Long batchId, int status,
                                    String failureCode, String failureMessage) {
}
