package com.sixbean.testpike.core.journey.api.dto;

import java.time.LocalDateTime;

/**
 * Journey traffic-light block for one project inside a dashboard window
 * (project-set quality board, consumed by the reportcenter overview feed).
 *
 * <p>Light caliber = the LATEST journey run that touched the project inside
 * the window (join tp_journey_run_segment.project_id, so ownership follows the
 * segment's owning project — never the program id, the two "programId" words
 * in this system mean different things). Run status mapping: 1 running →
 * amber, 2 all segments succeeded → green, 3 any segment failed → red;
 * anything else (including 4 cancelled, and no run at all) reads grey —
 * cancellation is not an outcome, it is honestly "no verdict this window".
 *
 * @param projectId    the project this light describes
 * @param journeyCount journeys of the project set that own at least one segment here
 * @param segmentCount segments owned by this project across those journeys
 * @param runsInWindow runs inside the window that touched this project
 * @param latestRunAt  start time of the latest such run (null = none)
 * @param light        green | amber | red | grey (see DashboardRunRow.lightOf)
 */
public record JourneyTrafficLight(long projectId, long journeyCount, long segmentCount,
                                  long runsInWindow, LocalDateTime latestRunAt, String light) {

    /** Grey row for a project the queries returned nothing for (an honest no-verdict, not a missing row). */
    public static JourneyTrafficLight grey(long projectId) {
        return new JourneyTrafficLight(projectId, 0L, 0L, 0L, null, "grey");
    }
}
