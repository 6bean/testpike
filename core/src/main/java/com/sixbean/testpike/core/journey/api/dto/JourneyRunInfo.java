package com.sixbean.testpike.core.journey.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A journey run: one expansion of a journey plan into per-project batches.
 * status: 1 = running, 2 = all segments succeeded, 3 = any segment failed, 4 = cancelled.
 * signedBy/signedAt stay NULL until the journey owner signs the run exit; a run may be
 * signed exactly once.
 */
public record JourneyRunInfo(long id,
                             long journeyId,
                             int status,
                             Long signedBy,
                             OffsetDateTime signedAt,
                             List<JourneyRunSegmentInfo> segments) {
}
