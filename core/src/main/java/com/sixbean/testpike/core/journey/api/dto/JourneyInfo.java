package com.sixbean.testpike.core.journey.api.dto;

import java.util.List;

/**
 * Journey aggregate read model: plan header plus its segments in execution order.
 * Segment pins are registered against the shared asset pool with host kind
 * "journey_segment", so the asset pool's "who uses me" view names this journey
 * without any second protocol (single source: SharedAssetReferenceService).
 *
 * @param id          journey row id
 * @param programId   owning program (project set) id
 * @param name        journey name (unique inside the program)
 * @param ownerUserId sole exit sign-off authority for runs of this journey
 * @param status      1 = active, 2 = archived
 * @param segments    segments ordered by seqNo
 */
public record JourneyInfo(long id, long programId, String name, long ownerUserId,
                          int status, List<JourneySegmentInfo> segments) {
}
