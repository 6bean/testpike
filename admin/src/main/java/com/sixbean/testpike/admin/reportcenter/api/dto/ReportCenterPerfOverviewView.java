package com.sixbean.testpike.admin.reportcenter.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin-side mirror of the load-test overview block in the program overview feed
 * (shape parity with core perf api dto PerfOverview — field names ride the JSON
 * contract, the bridge never reshapes).
 *
 * <p>Light = the latest in-window run per project: green normal end, amber running,
 * red circuit-break stop, grey no verdict (a manually stopped run or no run at all).
 * Peaks are the worst in-window metric frames; NULL = no frames in the window.
 */
public record ReportCenterPerfOverviewView(long projectId, long scenarioCount,
        long runsInWindow, Long latestRunId, LocalDateTime latestRunAt, String light,
        BigDecimal peakRps, BigDecimal peakP95Ms, BigDecimal peakErrorRate) {
}
