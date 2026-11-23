package com.sixbean.testpike.admin.plans.api.dto;

/**
 *
 * @param pendingSpecChanges pending contract change-review count (filled when the
 * hint switch is on, 0 when off)
 * @param reviewWarn plain-language review hint (null when switch off or queue
 * clean — a hint only, it never blocks exit)
 * @param apitestCoveragePercent measured project-wide contract-endpoint coverage
 * percent (gate on) or -1 when the coverage gate is off
 * @param minApitestCoverage active floor percent from the K8 key (0 when off)
 */
public record CompletionCheckView(boolean passed, String message, double passRate, double minPassRate,
                                  long openDefectCount, long p0OpenDefectCount, long total, long executed,
                                  long passedCount, long failedCount, long skippedCount,
                                  long notRunCount,
                                  long pendingSpecChanges, String reviewWarn,
                                  int apitestCoveragePercent, int minApitestCoverage) {
}
