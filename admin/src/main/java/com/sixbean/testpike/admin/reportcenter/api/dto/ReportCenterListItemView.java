package com.sixbean.testpike.admin.reportcenter.api.dto;

import java.time.LocalDateTime;

/**
 *
 * @param scopeType 1=project 2=global 3=program
 * @param reportType 1=weekly 2=monthly
 */
public record ReportCenterListItemView(Long id, Integer scopeType, Long scopeId, Integer reportType,
                                       LocalDateTime periodStart, LocalDateTime periodEnd,
                                       LocalDateTime generatedAt, Long createdBy) {
}
