package com.sixbean.testpike.admin.journey.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Journey run view (timeline traffic-light source). Status codes are the single run
 * vocabulary: 0 pending / 1 running / 2 succeeded / 3 failed / 4 skipped;
 * signedBy/signedAt stay null until the owner signs the exit.
 * Field names mirror core {@code JourneyRunInfo} one-to-one.
 */
public record JourneyRunView(long id, long journeyId, int status,
                             Long signedBy, OffsetDateTime signedAt,
                             List<JourneyRunSegmentView> segments) {
}
