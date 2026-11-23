package com.sixbean.testpike.core.reportcenter.api.dto;

import com.sixbean.testpike.core.defects.api.dto.DefectBurnWindow;
import com.sixbean.testpike.core.journey.api.dto.JourneyTrafficLight;
import com.sixbean.testpike.core.perf.api.dto.PerfOverview;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Program-lens dashboard feed for one project set (aggregate report data source).
 *
 * <p>One entry per project the caller can see through the lens; pass-rate readings
 * come live from the caliber registry (NULL when a project has no executions in the
 * period — an honest empty reading, never a fabricated zero). {@code defectBurn}
 * carries the per-project defect burndown block over the same window (open at
 * start / created / closed / open at end, same visible segment — see DefectBurnWindow).
 * {@code journeyTrafficLight} carries the per-project journey light over the same
 * window (latest run decides: green succeeded / amber running / red failed /
 * grey no verdict — see JourneyTrafficLight).
 * {@code perfOverview} carries the per-project load-test block over the same window
 * (latest run decides: green normal end / amber running / red circuit-break stop /
 * grey no verdict) plus scenario and run counts and the worst in-window frame
 * readings — see PerfOverview.
 * {@code caliberRegistry}
 * carries the programmatic footnotes (display name + version per registered caliber)
 * so the UI quotes exactly what the server evaluated.
 */
public record ProgramOverview(long programId, String programName,
                              LocalDateTime periodFrom, LocalDateTime periodTo,
                              List<ProjectPassRate> projects,
                              List<DefectBurnWindow> defectBurn,
                              List<JourneyTrafficLight> journeyTrafficLight,
                              List<PerfOverview> perfOverview,
                              List<CaliberFootnote> caliberRegistry) {
}
