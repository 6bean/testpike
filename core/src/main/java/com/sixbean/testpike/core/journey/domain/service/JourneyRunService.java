package com.sixbean.testpike.core.journey.domain.service;

import com.sixbean.testpike.core.journey.api.dto.JourneyRunInfo;

/**
 * Journey run surface : expand a journey plan into per-project batches and
 * carry its exit sign-off. Data stays owned by each segment project (H1 isolation) — the
 * run only records the expansion result per segment, never hangs business tables off a
 * program id.
 */
public interface JourneyRunService {

    /**
     * Start a run of the given journey: insert the run row plus one pending run-segment
     * row per plan segment, then expand segments in seqNo order, each inside its own
     * transaction so one segment's pre-check failure never rolls back batches already
     * launched (honest partial launch). A case-target segment expands to a batch holding
     * that single case; an asset-target segment reads its case list (and optional env)
     * from params_override_json — a missing case list fails that segment with
     * PARAMS_MISSING. Any launch exception fails the segment with LAUNCH_FAILED, skips
     * every downstream segment and closes the run as failed.
     */
    JourneyRunInfo startRun(long operatorId, long journeyId);

    /**
     * Read a run with all its segment rows ordered by seqNo (traffic-light source for
     * the timeline page). Unknown run id fails as not-found.
     */
    JourneyRunInfo getRun(long runId);

    /**
     * Exit sign-off: only the journey owner (tp_journey.owner_user_id) may sign, the run
     * must be in a terminal state (2/3/4) and unsigned; re-signing is rejected and names
     * the existing sign-off time. Each accepted sign-off writes exactly one audit row
     * (object_type journey_signoff) after the update.
     */
    JourneyRunInfo signOff(long operatorId, long runId);
}
