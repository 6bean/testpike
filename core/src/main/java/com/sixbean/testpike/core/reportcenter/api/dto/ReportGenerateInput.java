package com.sixbean.testpike.core.reportcenter.api.dto;

/**
 *
 * @param reportType 1=weekly 2=monthly
 * @param scopeType 1=project 2=global
 */
public record ReportGenerateInput(Integer reportType, Integer scopeType, Long scopeId,
                                  String periodStart, String periodEnd) {
}
