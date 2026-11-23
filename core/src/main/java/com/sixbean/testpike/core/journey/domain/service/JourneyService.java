package com.sixbean.testpike.core.journey.domain.service;

import com.sixbean.testpike.core.journey.api.dto.JourneyCreateInput;
import com.sixbean.testpike.core.journey.api.dto.JourneyInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneyPageView;
import com.sixbean.testpike.core.journey.api.dto.JourneySegmentInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneySegmentInput;

/**
 * Journey plan management for cross-project orchestration under a program .
 * A journey is an ordered list of segments; each segment belongs to one member project and
 * targets either a published shared-asset version row (registered as a pin on
 * tp_asset_reference with host kind "journey_segment") or a case of that project.
 * Execution expansion (runs, per-segment batches, sign-off) lands in segment B and reuses
 * this plan surface unchanged.
 */
public interface JourneyService {

    /**
     * Create an active journey inside a program. Unknown program, blank name and
     * non-positive owner are rejected before any SQL write; the (program, name) pair
     * must be unique.
     */
    JourneyInfo createJourney(long operatorId, JourneyCreateInput input);

    /**
     * Append one ordered segment to a journey. An asset target must be a published
     * tp_asset version row; the segment pin is registered through the shared
     * SharedAssetReferenceService single source (no second reference protocol). Duplicate
     * seqNo inside the same journey fails validation before the insert.
     */
    JourneySegmentInfo addSegment(long journeyId, JourneySegmentInput input);

    /**
     * Read the journey aggregate: header plus all segments ordered by seqNo.
     * Unknown journey id fails as not-found.
     */
    JourneyInfo getJourney(long journeyId);

    /**
     * Paged journey list under one program, newest first, with an optional
     * name-contains keyword (case-insensitive). Bad window arguments fail before any
     * read; rows carry the header only — segment editing reads the aggregate endpoint.
     */
    JourneyPageView pageJourneys(long programId, int page, int pageSize, String keyword);
}
