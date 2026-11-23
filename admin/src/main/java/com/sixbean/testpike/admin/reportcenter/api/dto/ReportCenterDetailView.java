package com.sixbean.testpike.admin.reportcenter.api.dto;

import java.time.LocalDateTime;

/**
 *
 * @param reportType 1=weekly 2=monthly
 */
public record ReportCenterDetailView(Long id, Integer scopeType, Long scopeId, Integer reportType,
                                     LocalDateTime periodStart, LocalDateTime periodEnd,
                                     String payloadJson, LocalDateTime generatedAt, Long createdBy) {
}
