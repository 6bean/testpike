package com.sixbean.testpike.core.perf.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Load-test overview block for one project inside a dashboard window (project-set
 * quality board, consumed by the reportcenter overview feed).
 *
 * <p>Light caliber = the LATEST tp_perf_run that started inside the window for the
 * project: 1 running → amber, 2 normal end → green, 3 circuit-break stop → red;
 * 4 manually stopped — and no run at all — reads grey, because stopping by hand is
 * not a test outcome, it is honestly "no verdict this window". Peaks are the worst
 * second-level frames (throughput / p95 latency / error rate) of the runs inside the
 * window only: a frame of a pre-window run never raises a reading, and a project with
 * no frames keeps them NULL rather than showing a fabricated zero.
 *
 * @param projectId      the project this block describes
 * @param scenarioCount  load-test scenarios owned by the project (window-independent)
 * @param runsInWindow   runs that started inside the window
 * @param latestRunId    id of the run that decided the light (null = none in window)
 * @param latestRunAt    start time of that run (null = none in window)
 * @param light          green | amber | red | grey (see DashboardRunRow.lightOf)
 * @param peakRps        highest throughput among in-window frames (null = none)
 * @param peakP95Ms      worst p95 latency among in-window frames (null = none)
 * @param peakErrorRate  worst error rate among in-window frames (null = none)
 */
public record PerfOverview(long projectId, long scenarioCount, long runsInWindow,
                           Long latestRunId, LocalDateTime latestRunAt, String light,
                           BigDecimal peakRps, BigDecimal peakP95Ms, BigDecimal peakErrorRate) {

    /** Quiet row for a project the queries returned nothing for (an honest no-verdict, not a missing row). */
    public static PerfOverview quiet(long projectId) {
        return new PerfOverview(projectId, 0L, 0L, null, null, "grey", null, null, null);
    }
}
