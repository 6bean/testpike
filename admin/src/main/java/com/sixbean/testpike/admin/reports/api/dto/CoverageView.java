package com.sixbean.testpike.admin.reports.api.dto;

import java.util.List;


public record CoverageView(long totalRequirements, long coveredRequirements, double coverageRate,
                           List<ChapterCoverage> byChapter) {
}
