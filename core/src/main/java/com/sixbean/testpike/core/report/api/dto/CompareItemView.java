package com.sixbean.testpike.core.report.api.dto;


public record CompareItemView(long projectId, String projectName, boolean archived,
                              CompareCoverage coverage, AiQualityView aiQuality,
                              CompareExecStat exec, long flaky) {
}
