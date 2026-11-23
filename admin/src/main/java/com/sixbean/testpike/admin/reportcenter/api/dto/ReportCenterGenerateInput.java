package com.sixbean.testpike.admin.reportcenter.api.dto;

/**
 *
 * @param reportType 1=weekly 2=monthly
 * @param scopeType 1=project 2=global 3=program
 */
public record ReportCenterGenerateInput(Integer reportType, Integer scopeType, Long scopeId,
                                        String periodStart, String periodEnd) {
}
