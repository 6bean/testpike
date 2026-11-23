package com.sixbean.testpike.core.report.api.dto;


public record CoverageView(long totalRequirements, long coveredRequirements, double coverageRate,
                           java.util.List<ChapterCoverage> byChapter) {
}
