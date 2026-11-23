package com.sixbean.testpike.admin.reportcenter.api.dto;

import java.time.LocalDateTime;

/**
 * Admin-side mirror of the journey traffic-light block in the program overview
 * feed (shape parity with core journey api dto JourneyTrafficLight — field names
 * ride the JSON contract, the bridge never reshapes).
 *
 * <p>Light = the latest in-window run per project: green succeeded, amber
 * running, red failed, grey no verdict (cancelled or no run at all).
 */
public record ReportCenterJourneyTrafficLightView(long projectId, long journeyCount,
        long segmentCount, long runsInWindow, LocalDateTime latestRunAt, String light) {
}
