package com.sixbean.testpike.core.analysis.api.dto;

import java.util.List;

/**
 * Requirement-coverage summary projected for the AI conversation analysis channel
 * .
 * <p>Numbers come from the J4 coverage matrix single source — this view never
 * recomputes a second denominator. The contract-coverage exit gate fields reuse
 * the same K8 key and inventory read path as the plan exit check.
 *
 * @param projectId project whose coverage is summarized
 * @param chapter chapter filter applied (null = all chapters)
 * @param endType end-type filter applied (null = all ends)
 * @param total requirement points in scope (active, filtered)
 * @param covered requirement points linked to at least one active case
 * @param uncoveredTitles titles of uncovered requirement points, capped at 50
 * @param truncated true when more uncovered points exist than the cap shows
 * @param minApitestCoverage configured contract-coverage exit floor, 0 = gate off
 * @param gateEnabled true when the floor key is on (1..100)
 * @param apitestCoveragePercent measured contract-endpoint coverage, -1 when the
 * gate is off or the project has no contract yet
 * @param gateMessage null when satisfied or off; plain-language reason otherwise
 */
public record CoverageSummaryView(long projectId, String chapter, String endType,
                                  long total, long covered,
                                  List<String> uncoveredTitles, boolean truncated,
                                  int minApitestCoverage, boolean gateEnabled,
                                  int apitestCoveragePercent, String gateMessage) {
}
