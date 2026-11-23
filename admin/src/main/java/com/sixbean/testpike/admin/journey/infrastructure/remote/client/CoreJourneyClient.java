package com.sixbean.testpike.admin.journey.infrastructure.remote.client;

import com.sixbean.testpike.admin.journey.api.dto.AddJourneySegmentRequest;
import com.sixbean.testpike.admin.journey.api.dto.CreateJourneyRequest;
import com.sixbean.testpike.admin.journey.api.dto.JourneyPageView;
import com.sixbean.testpike.admin.journey.api.dto.JourneyRunView;
import com.sixbean.testpike.admin.journey.api.dto.JourneySegmentView;
import com.sixbean.testpike.admin.journey.api.dto.JourneyView;

/**
 * core journey service client (service layering: admin → HTTP → core
 * /internal/journeys/**, same topology as {@code CoreProgramClient}).
 * <p>Whole-object pass-through rule: the request DTOs the controller receives are
 * forwarded verbatim — no per-field copying at the bridge (field-loss incident class,
 * see JourneyBridgeTest). Expansion, sign-off ownership and audit all stay in core.
 */
public interface CoreJourneyClient {

    /** Create the journey plan; returns the new journey id. */
    long createJourney(CreateJourneyRequest request);

    /** Append one ordered segment (asset targets pin through the shared reference source). */
    JourneySegmentView addSegment(long journeyId, AddJourneySegmentRequest request);

    /** Journey aggregate: header plus all segments ordered by seqNo. */
    JourneyView getJourney(long journeyId);

    /** Paged journey wall under a program (S-group contract; keyword = name contains, judged in core). */
    JourneyPageView pageJourneys(long programId, int page, int pageSize, String keyword);

    /** Start a run: expand the plan into per-project batches in seqNo order. */
    JourneyRunView startRun(long journeyId);

    /** Run with all segment rows — the timeline traffic-light source. */
    JourneyRunView getRun(long runId);

    /** Exit sign-off; core rejects non-owners, non-terminal runs and re-signing. */
    JourneyRunView signOff(long runId);
}
