package com.sixbean.testpike.core.journey.api.dto;

import java.time.OffsetDateTime;

/**
 * One expanded segment row of a journey run (traffic-light data for the timeline page).
 * status: 0 = pending, 1 = running, 2 = succeeded, 3 = failed, 4 = skipped because an
 * upstream segment failed. batchId is NULL while pending or when expansion failed before
 * the batch row existed; failureCode/failureMessage name the reason for a failed segment.
 */
public record JourneyRunSegmentInfo(long id,
                                    long segmentId,
                                    int seqNo,
                                    long projectId,
                                    Long batchId,
                                    int status,
                                    String failureCode,
                                    String failureMessage) {
}
