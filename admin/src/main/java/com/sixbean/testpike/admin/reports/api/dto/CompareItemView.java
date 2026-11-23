package com.sixbean.testpike.admin.reports.api.dto;


public record CompareItemView(long projectId, String projectName, boolean archived,
                              CompareCoverage coverage, AiQualityView aiQuality,
                              CompareExecStat exec, long flaky) {
}
