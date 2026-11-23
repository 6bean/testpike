package com.sixbean.testpike.admin.reportcenter.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin-side mirror of the core program-lens dashboard feed (shape parity with
 * core reportcenter api dto ProgramOverview — field names ride the JSON contract,
 * the bridge never reshapes).
 *
 * <p>One entry per project the caller can see through the lens; passRate is NULL
 * when a project has no executions in the window (honest empty reading).
 * defectBurn carries the per-project burndown block over the same window and the
 * same visible segment.
 * journeyTrafficLight carries the per-project journey light (latest in-window run
 * decides: green / amber / red / grey) over the same window and segment.
 * perfOverview carries the per-project load-test block (latest in-window run colours
 * the light; peaks are worst in-window frames, null = no reading).
 * caliberRegistry carries the programmatic footnotes so the UI quotes exactly
 * what the server evaluated.
 */
public record ReportCenterProgramOverviewView(long programId, String programName,
        LocalDateTime periodFrom, LocalDateTime periodTo,
        List<ReportCenterProjectPassRateView> projects,
        List<ReportCenterDefectBurnWindowView> defectBurn,
        List<ReportCenterJourneyTrafficLightView> journeyTrafficLight,
        List<ReportCenterPerfOverviewView> perfOverview,
        List<ReportCenterCaliberFootnoteView> caliberRegistry) {
}
